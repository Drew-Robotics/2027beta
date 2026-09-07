#!/usr/bin/env bash
# ADR 0013's Job 1. Deploys the real artifact to the bench Pi against an empty CAN bus, waits for
# the program to settle, and asserts four things about it. The Markdown on stdout is what the
# bench workflow puts in its step summary, so running this by hand gives the same report CI gives.
#
#   BENCH=systemcore@192.168.1.202 .github/bench/real-hal-boot.sh
#
# An unreachable bench is a skip and exits 0: there is one Pi, and a job that reddens because it
# is unplugged teaches people to ignore CI.
set -uo pipefail

BENCH=${BENCH:-systemcore@192.168.1.202}
SETTLE=${SETTLE:-30}
OUT=${OUT:-build/bench}

cd "$(dirname "$0")/../.."
mkdir -p "$OUT"
JOURNAL="$OUT/real-hal-boot-journal.txt"

on_bench() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$BENCH" "$@"; }

if ! on_bench true 2>/dev/null; then
  echo "### real-hal-boot skipped"
  echo
  echo "The bench (\`$BENCH\`) did not answer. Nothing was deployed and nothing is asserted."
  exit 0
fi

# The journal is read from here rather than from the boot, so a second run in the same boot does
# not assert on the first run's lines.
since=$(on_bench date +%s)

# NRestarts counts automatic restarts and nothing clears it on its own, so a crash three runs ago
# would otherwise fail this one.
on_bench sudo systemctl reset-failed robot

echo "Deploying to $BENCH ..." >&2
deploy_log="$OUT/real-hal-boot-deploy.txt"
./gradlew deploy 2>&1 | tee "$deploy_log" >&2
deploy_status=${PIPESTATUS[0]}

if [ "$deploy_status" -ne 0 ]; then
  echo "### real-hal-boot is red — the deploy failed"
  echo
  echo "Nothing was asserted, because nothing new is running. The Gradle output names the cause;"
  echo "an MRC API mismatch is the expected one on a nightly, and its remedy is to reflash the"
  echo "bench from LimelightVision/systemcore-os-public."
  echo
  echo '```'
  tail -25 "$deploy_log"
  echo '```'
  exit 1
fi

sleep "$SETTLE"

facts=$(on_bench 'sh -s' <<'REMOTE'
main=$(systemctl show robot -p MainPID --value)
pid=$main
# The unit runs robotCommand through bash, so the JVM is its child rather than the main pid.
if [ "$(cat /proc/$pid/comm 2>/dev/null)" != java ]; then
  pid=$(awk '{print $1}' /proc/$main/task/$main/children 2>/dev/null)
fi
echo "NRESTARTS=$(systemctl show robot -p NRestarts --value)"
echo "ACTIVESTATE=$(systemctl show robot -p ActiveState --value)"
echo "STARTED=$(systemctl show robot -p ExecMainStartTimestamp --value)"
echo "PID=$pid"
if [ -r "/proc/$pid/status" ]; then
  echo "RSS_KB=$(awk '/^VmRSS:/{print $2}' /proc/$pid/status)"
  echo "THREADS=$(awk '/^Threads:/{print $2}' /proc/$pid/status)"
  for task in /proc/$pid/task/*; do
    tid=${task##*/}
    line=$(chrt -p "$tid" 2>/dev/null | tr '\n' ' ')
    case "$line" in
      *SCHED_RR*) echo "RR=$tid:$(echo "$line" | sed -n 's/.*priority: \([0-9]*\).*/\1/p')" ;;
    esac
  done
fi
REMOTE
)

# An empty CAN bus floods the journal, so the two greps run over the whole window on the Pi and
# only the artifact is trimmed to the last 500 lines.
window="journalctl -u robot --since '@$since' -o short-iso-precise --no-pager"
on_bench "$window | tail -500" > "$JOURNAL"
started_ok=$(on_bench "$window | grep -c 'Robot program startup complete'")
mrc_mismatch=$(on_bench "$window | grep -c 'MRC API version mismatch'")
complete=$(on_bench "$window | grep -m1 'Robot program startup complete'" | cut -d' ' -f1)

fact() { echo "$facts" | sed -n "s/^$1=//p" | head -1; }

restarts=$(fact NRESTARTS)
active=$(fact ACTIVESTATE)
started=$(fact STARTED)
rss_kb=$(fact RSS_KB)
threads=$(fact THREADS)
rr=$(echo "$facts" | sed -n 's/^RR=//p' | paste -sd', ' -)

# Both timestamps are the Pi's, and only their difference is used, so the runner's clock and time
# zone do not enter into it.
startup=
if [ -n "$complete" ] && [ -n "$started" ]; then
  startup=$(date -u -d "$complete" +%s.%N 2>/dev/null | awk -v s="$(date -u -d "$started" +%s 2>/dev/null)" \
    '{ if (s != "") printf "%.1f s", $1 - s }')
fi

fail=0
report() { printf -- '- %s **%s** — %s\n' "$1" "$2" "$3"; }
assert() {
  if [ "$1" = pass ]; then
    report '✅' "$2" "$3"
  else
    report '❌' "$2" "$3"
    fail=1
  fi
}

{
  echo "### real-hal-boot"
  echo
  echo "The real artifact (\`linuxsystemcore\`) on \`$BENCH\`, ${SETTLE}s after the deploy, against an empty CAN bus."
  echo

  if [ "$restarts" = 0 ]; then
    assert pass "the program did not restart" "\`NRestarts=0\`."
  else
    assert fail "the program restarted ${restarts}×" \
      "systemd restarted it, so it died. The journal artifact holds the exception; \`Restart=always\` with \`RestartSec=3\` means it is still going round."
  fi

  if [ "$active" = active ]; then
    assert pass "the unit is active" "\`ActiveState=active\`."
  else
    assert fail "the unit is \`$active\`" "The program exited, which a robot program never does on its own."
  fi

  if [ "${started_ok:-0}" -gt 0 ]; then
    assert pass "the loop started" "\`Robot program startup complete\` is in the journal."
  else
    assert fail "the loop never started" \
      "The program came up and hung before the first loop. The journal artifact holds the last 500 lines."
  fi

  if [ "${mrc_mismatch:-0}" -gt 0 ]; then
    assert fail "the image and the library disagree" \
      "\`MRC API version mismatch\` is in the journal. The OS image and WPILib are a pair: reflash the bench from LimelightVision/systemcore-os-public, or move the WPILib pin back to an alpha the image accepts."
  else
    assert pass "no MRC API mismatch" "The image accepts the API this build asks for."
  fi

  echo
  echo "Four numbers, and they gate nothing. RSS creep, or a third \`SCHED_RR\` thread, is worth an eye."
  echo
  echo '| metric | this run | #10, on a heartbeat program |'
  echo '|---|---|---|'
  printf '| RSS | %s | 83 MB |\n' "${rss_kb:+$((rss_kb / 1024)) MB}"
  printf '| Threads | %s | 36 |\n' "${threads:-—}"
  printf '| `SCHED_RR` threads (tid:priority) | %s | two — 50 (CAN), 40 (Notifier) |\n' "${rr:-none}"
  printf '| start → `startup complete` | %s | — |\n' "${startup:-—}"
} > "$OUT/real-hal-boot-summary.md"

cat "$OUT/real-hal-boot-summary.md"
exit "$fail"
