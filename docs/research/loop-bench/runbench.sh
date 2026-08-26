#!/bin/bash
# usage: runbench.sh <label> <jvm-flags...>
LABEL="$1"; shift
FLAGS="$*"
BASE='-Djava.library.path=/home/systemcore/wpilib/third-party/lib --add-opens java.base/jdk.internal.vm=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED'
GCLOG="-Xlog:gc*:file=/home/systemcore/bench/gc-$LABEL.log:time,uptime,level,tags"
mkdir -p /home/systemcore/bench
sudo systemctl stop robot
rm -rf /home/systemcore/loopbench-logs
echo "$LABEL" > /home/systemcore/loopbench.label
echo "/usr/bin/java $FLAGS $GCLOG $BASE -cp \"/home/systemcore/wpilib/allwpilibclasspath/*\" wpilib.robot.Main" > /home/systemcore/robotCommand
chmod +x /home/systemcore/robotCommand
STAMP=$(date '+%Y-%m-%d %H:%M:%S')
sudo systemctl start robot
DEADLINE=$(( $(date +%s) + 400 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  sleep 10
  if journalctl -u robot --since "$STAMP" --no-pager -o cat 2>/dev/null | grep -q 'LOOPBENCH_GC DONE'; then break; fi
  if ! systemctl is-active --quiet robot; then echo "SERVICE DIED"; break; fi
done
echo "===== $LABEL ====="
journalctl -u robot --since "$STAMP" --no-pager -o cat | grep -E 'LOOPBENCH_GC|Error|Exception|error:|Unrecognized|Improperly' | head -40
echo "===== gc summary $LABEL ====="
grep -cE 'Pause' /home/systemcore/bench/gc-$LABEL.log 2>/dev/null
