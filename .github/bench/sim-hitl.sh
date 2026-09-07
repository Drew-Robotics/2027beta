#!/usr/bin/env bash
# ADR 0013's Job 2. Runs a linuxarm64 simulation build on the bench Pi — real aarch64, real
# PREEMPT_RT kernel, real JDK 25, real Notifier scheduling, nothing plugged in — and watches the
# loop for a regression against a stored baseline. The Markdown on stdout is what the bench
# workflow puts in its step summary.
#
#   BENCH=systemcore@192.168.1.202 .github/bench/sim-hitl.sh
#
# DS=on additionally brings up the Driver Station harness. It is off by default because the
# Driver Station finds a simulation only on its own machine — see docs/bench-runner.md — so the
# loop measured here is the disabled one.
#
# An unreachable bench is a skip and exits 0.
set -uo pipefail

BENCH=${BENCH:-systemcore@192.168.1.202}
BENCH_HOST=${BENCH#*@}
RUN=${RUN:-45}
DS=${DS:-off}
OPMODE=${OPMODE:-DefaultTeleop}
OUT=${OUT:-build/bench}
REMOTE=/home/systemcore/simhitl
BASELINE=.github/bench/sim-hitl-baseline.env

cd "$(dirname "$0")/../.."
mkdir -p "$OUT"
RUNLOG="$OUT/sim-hitl-run.log"
WPILOG="$OUT/sim-hitl.wpilog"

on_bench() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$BENCH" "$@"; }

# The address this box reaches the bench from, which is the one the bench must answer on. A
# multi-homed runner has more than one and only this one is routable both ways.
route_source() { ip route get "$1" | awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }'; }

if ! on_bench true 2>/dev/null; then
  echo "### sim-hitl skipped"
  echo
  echo "The bench (\`$BENCH\`) did not answer. Nothing ran and nothing is asserted."
  exit 0
fi

held=
restore() {
  for pid in $held; do kill "$pid" 2>/dev/null; done
  held=
  # There is no pkill on the image and killall would take robot.service's JVM with it, so the
  # sim and the relay leave their own pids behind: `exec` keeps the shell's, so it is the JVM's.
  #
  # The three services come back one at a time, and the last program has to be gone before the
  # first of them starts: anything still registered on the system server makes the next program
  # abort with "Multiple user programs detected", and robot.service would crash-loop on it.
  on_bench "for f in $REMOTE/*.pid; do [ -f \"\$f\" ] && kill \$(cat \"\$f\") 2>/dev/null; done
            rm -f $REMOTE/*.pid
            sleep 2
            sudo systemctl restart mrccomm && sleep 3
            sudo systemctl restart limelight_diagnosticsprocess && sleep 3
            sudo systemctl start robot" >/dev/null 2>&1
}

./gradlew simHitlStage >"$OUT/sim-hitl-stage.log" 2>&1 || {
  echo "### sim-hitl is red — the linuxarm64 build did not stage"
  echo
  echo '```'
  tail -20 "$OUT/sim-hitl-stage.log"
  echo '```'
  exit 1
}

# robot.service holds the CAN bus and the NT port; mrccomm holds UDP 1110; the diagnostics
# service holds the system NetworkTables server on 6810. The sim wants all three, and it aborts
# with "Multiple user programs detected" if a previous program is still registered on 6810.
# Nothing above this line has touched the bench, and nothing below it may leave it stopped.
trap restore EXIT INT TERM
on_bench 'sudo systemctl stop robot mrccomm limelight_diagnosticsprocess'

tar -C build/simhitl -czf - . |
  on_bench "rm -rf $REMOTE && mkdir -p $REMOTE && tar xzf - -C $REMOTE" || {
  echo "### sim-hitl is red — the bundle did not reach the bench"
  exit 1
}
scp -q -o BatchMode=yes .github/bench/ds-relay.py "$BENCH:$REMOTE/"

# The sim runs in the foreground of an ssh that stays open: busybox nohup and setsid are applets
# of a setuid binary, and the loader strips LD_PRELOAD from anything it execs, which would take
# the REVLib shim with it.
: >"$RUNLOG"
rm -f "$WPILOG"
on_bench "cd $REMOTE && rm -rf logs && echo \$\$ > sim.pid \
  && LD_PRELOAD=\$PWD/lib/libwpiutil.so:\$PWD/lib/librevshim.so \
  LD_LIBRARY_PATH=\$PWD/lib HALSIM_EXTENSIONS=\$PWD/lib/libhalsim_ds_socket.so \
  exec /usr/bin/java -Djava.library.path=\$PWD/lib \
    --add-opens java.base/jdk.internal.vm=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
    -cp 'classpath/*' first.Main" >"$RUNLOG" 2>&1 &
held="$held $!"

for _ in $(seq 60); do
  grep -q 'Robot program startup complete' "$RUNLOG" && break
  sleep 1
done

if ! grep -q 'Robot program startup complete' "$RUNLOG"; then
  echo "### sim-hitl is red — the simulation never reached its loop"
  echo
  echo "60s after launch the program had not printed \`Robot program startup complete\`."
  echo
  echo '```'
  tail -25 "$RUNLOG"
  echo '```'
  exit 1
fi

if [ "$DS" = on ]; then
  runner=$(route_source "$BENCH_HOST")
  bench_lan=$(on_bench "ip route get $runner" |
    awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }')
  on_bench "echo \$\$ > $REMOTE/relay.pid
            exec python3 $REMOTE/ds-relay.py --role bench --peer $runner --bind $bench_lan" \
    >"$OUT/sim-hitl-relay.log" 2>&1 &
  held="$held $!"
  python3 .github/bench/ds-relay.py --role runner --peer "$BENCH_HOST" --bind "$runner" \
    >>"$OUT/sim-hitl-relay.log" 2>&1 &
  held="$held $!"
  ssh -o BatchMode=yes -N -L 6810:127.0.0.1:6810 -L 1740:127.0.0.1:1740 \
    -L 1741:127.0.0.1:1741 -L 5810:127.0.0.1:5810 "$BENCH" &
  held="$held $!"
  sleep 3
  python3 .github/bench/ds-harness.py --opmode "$OPMODE" --enable-seconds "$RUN" \
    >"$OUT/sim-hitl-ds.log" 2>&1
else
  sleep "$RUN"
fi

restore
sleep 2
on_bench "ls $REMOTE/logs/*.wpilog" >"$OUT/sim-hitl-logs.txt" 2>/dev/null
remote_log=$(tail -1 "$OUT/sim-hitl-logs.txt")
[ -n "$remote_log" ] && scp -q -o BatchMode=yes "$BENCH:$remote_log" "$WPILOG"

facts=
[ -s "$WPILOG" ] && facts=$(python3 .github/bench/wpilog-stats.py "$WPILOG")
fact() { echo "$facts" | sed -n "s/^$1=//p" | head -1; }

# shellcheck source=/dev/null
[ -f "$BASELINE" ] && . "$BASELINE"

# The enabled loop is the one worth watching, and it is only measurable with a Driver Station
# attached; without one this falls back to the disabled loop rather than reporting nothing.
if [ -n "$(fact ENABLED_P50)" ]; then
  state=enabled
  p50=$(fact ENABLED_P50); p95=$(fact ENABLED_P95); p99=$(fact ENABLED_P99); max=$(fact ENABLED_MAX)
  base_p50=${BASELINE_ENABLED_P50:-}; base_p95=${BASELINE_ENABLED_P95:-}; base_p99=${BASELINE_ENABLED_P99:-}
else
  state=disabled
  p50=$(fact DISABLED_P50); p95=$(fact DISABLED_P95); p99=$(fact DISABLED_P99); max=$(fact DISABLED_MAX)
  base_p50=${BASELINE_DISABLED_P50:-}; base_p95=${BASELINE_DISABLED_P95:-}; base_p99=${BASELINE_DISABLED_P99:-}
fi
tolerance=${BASELINE_TOLERANCE:-0.0005}

ms() { awk -v v="${1:-}" 'BEGIN { if (v == "") print "—"; else printf "%.3f ms", v * 1000 }'; }
delta() {
  awk -v v="${1:-}" -v b="${2:-}" 'BEGIN {
    if (v == "" || b == "") print "—"; else printf "%+.3f ms", (v - b) * 1000 }'
}
over() {
  awk -v v="${1:-}" -v b="${2:-}" -v t="$tolerance" 'BEGIN {
    print (v != "" && b != "" && v - b > t) ? "yes" : "no" }'
}

fail=0
assert() {
  if [ "$1" = pass ]; then
    printf -- '- ✅ **%s** — %s\n' "$2" "$3"
  else
    printf -- '- ❌ **%s** — %s\n' "$2" "$3"
    fail=1
  fi
}

{
  echo "### sim-hitl"
  echo
  echo "A \`linuxarm64\` simulation build on \`$BENCH\`: real aarch64, real kernel, real JDK, nothing plugged in."
  echo

  if grep -q 'HAL Extensions: Successfully loaded extension' "$RUNLOG"; then
    assert pass "the sim HAL ran on the Pi" \
      "\`halsim_ds_socket\` loaded, and the real HAL has no symbol for it."
  else
    assert fail "the sim HAL did not load" \
      "\`linuxarm64\` is the sim HAL and \`linuxsystemcore\` the real one; this build linked neither usefully."
  fi

  assert pass "the loop started" "\`Robot program startup complete\` is in the console log."

  if grep -qE 'Unhandled exception|quit unexpectedly' "$RUNLOG"; then
    assert fail "the program threw" "The console log holds an unhandled exception."
  else
    assert pass "nothing threw" "No unhandled exception in the console log."
  fi

  if [ -z "$p50" ]; then
    assert fail "no loop to measure" \
      "No \`/Telemetry/Robot/LoopDelta\` came back, so either the log did not survive the run or it never reached this box."
  elif [ -z "$base_p50" ]; then
    assert pass "no $state baseline to regress against" \
      "\`$BASELINE\` carries none, so this run reports numbers instead of asserting on them."
  elif [ "$(over "$p95" "$base_p95")" = no ] && [ "$(over "$p50" "$base_p50")" = no ]; then
    assert pass "the $state loop is where it was" \
      "p50 $(delta "$p50" "$base_p50"), p95 $(delta "$p95" "$base_p95") against the baseline."
  else
    assert fail "the $state loop got slower" \
      "p50 $(delta "$p50" "$base_p50"), p95 $(delta "$p95" "$base_p95"), past $(ms "$tolerance"). These are deltas, not a budget: rebaseline when the bench hardware changes, never to clear a red."
  fi

  # The bench belongs to whoever wants it next, and this job stopped three of its services.
  if [ "$(on_bench 'systemctl is-active robot' 2>/dev/null)" != active ]; then
    assert fail "the bench did not come back" \
      "\`robot.service\` is not active after the run. Restart it by hand: stop it, restart \`mrccomm\` and \`limelight_diagnosticsprocess\` one at a time, then start it."
  fi

  echo
  if [ "$state" = disabled ]; then
    echo "No Driver Station is attached, so this is the **disabled** loop. The Driver Station"
    echo "finds a simulation only on the machine it runs on; \`DS=on\` brings the harness up"
    echo "anyway. See docs/bench-runner.md."
    echo
  fi

  echo "| loop ($state) | this run | baseline | delta |"
  echo '|---|---|---|---|'
  printf '| p50 | %s | %s | %s |\n' "$(ms "$p50")" "$(ms "$base_p50")" "$(delta "$p50" "$base_p50")"
  printf '| p95 | %s | %s | %s |\n' "$(ms "$p95")" "$(ms "$base_p95")" "$(delta "$p95" "$base_p95")"
  printf '| p99 | %s | %s | %s |\n' "$(ms "$p99")" "$(ms "$base_p99")" "$(delta "$p99" "$base_p99")"
  printf '| max | %s | — | — |\n' "$(ms "$max")"
} > "$OUT/sim-hitl-summary.md"

cat "$OUT/sim-hitl-summary.md"
exit "$fail"
