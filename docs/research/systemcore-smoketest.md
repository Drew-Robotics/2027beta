# SystemCore smoke test: SSH, deploy, and a running robot program

Resolves [#10](https://github.com/Drew-Robotics/2027beta/issues/10). Everything here was
executed against the physical device on 2026-08-24, not read from documentation.

Device: Raspberry Pi 5 (8 GB) at `192.168.1.202`, also reachable as `robot.local`.

## 1. Key-based SSH

The device ships with password auth for `systemcore`/`systemcore`. To install a key:

```bash
PUB=$(cat ~/.ssh/id_ed25519.pub)
ssh -o PreferredAuthentications=password -o PubkeyAuthentication=no systemcore@192.168.1.202 \
  "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && \
   chmod 600 ~/.ssh/authorized_keys && grep -qxF '$PUB' ~/.ssh/authorized_keys || \
   echo '$PUB' >> ~/.ssh/authorized_keys"
```

`ssh-copy-id` does the same thing and is the normal way to do this interactively.

Verify: `ssh -o BatchMode=yes systemcore@192.168.1.202 hostname` → `robot`.

Convenience alias for `~/.ssh/config` — put it **above** any `Host *` block:

```
Host systemcore
    HostName 192.168.1.202
    User systemcore
    IdentityFile ~/.ssh/id_ed25519
```

The key survived an OS update applied via `.llupdate` payload; a full reflash from the
`.zip` image would wipe it.

### Missing on-device tools

The image is BusyBox-based and lacks several things scripts assume:
`timeout`, `pgrep`, `gcc`/`cc`, `perf`, `unzip`. Use `jcmd -l` instead of `pgrep` to find
the robot JVM. `python3`, `objdump`, `nm`, `readelf`, and the full JDK 25 toolchain
(`javac`, `jcmd`, `jfr`, `jmap`, `jstack`) **are** present.

## 2. Deploying without GradleRIO or Maven

`~/dev/allwpilib/developerRobot` is WPILib's own scratch robot project. It builds against
the repo sources and deploys straight to a SystemCore, so it needs no published artifacts
and no GradleRIO — the fastest way to get *something* running on the device.

```bash
cd ~/dev/allwpilib
./gradlew :developerRobot:build -x test    # ~13 s warm
./gradlew :developerRobot:deployJava       # ~7 s
ssh systemcore@192.168.1.202 sudo systemctl restart robot
ssh systemcore@192.168.1.202 journalctl -u robot -f
```

`deployJava` deploys two artifacts: the Java classpath, and the C++ executable — the
latter only because that is how the JNI `.so` files get onto the device.

Where things land:

| Path | Contents |
|---|---|
| `/home/systemcore/wpilib/allwpilibclasspath/` | 25 jars, 4.8 MB (note: **not** GradleRIO's `wpilib/classpath/`) |
| `/home/systemcore/wpilib/third-party/lib/` | 37 `.so` files, 88.5 MB (mostly OpenCV) |
| `/home/systemcore/robotCommand` | the generated launch line |
| `/home/systemcore/developerRobotCpp` | the C++ executable |

`robotCommand` as written by `developerRobot`:

```
/usr/bin/java -XX:+UseG1GC -Djava.library.path=/home/systemcore/wpilib/third-party/lib \
  --add-opens java.base/jdk.internal.vm=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
  -cp "/home/systemcore/wpilib/allwpilibclasspath/*" wpilib.robot.Main
```

⚠️ **This path hardcodes G1, not ZGC.** [#9](https://github.com/Drew-Robotics/2027beta/issues/9)
found GradleRIO defaults to ZGC for deployed robot programs; that applies to a GradleRIO
project, not to `developerRobot`. Confirmed on the running process:
`-XX:+UseG1GC`, `MaxHeapSize=2113929216` (2 GB = ¼ of RAM).

`robot.service` has `ConditionPathExists=/home/systemcore/robotCommand`, so before the
first deploy the unit is cleanly inactive. After a deploy it is enabled and starts on boot.

## 3. ⚠️ The MRC ABI gate — the real hazard

The HAL talks to the SystemCore OS through `libMrcLib.so`, which the **OS image** ships
(`/usr/lib/libMrcLib.so`) — allwpilib does not deploy it. The HAL is compiled against
mrclib *headers* carrying `MRC_API_VERSION`, and checks it at startup:

```cpp
// hal/src/main/native/cpp/mrclib/MrcLibDs.cpp
if (!MRC_CHECK_API_VERSION()) {
  wpi::util::print(stderr, "Error: MRC API version mismatch. Restarting app and retrying...");
  std::terminate();
}
```

On a mismatch the program aborts with SIGABRT before printing anything of its own, and
`Restart=always` turns that into a 3-second crash loop:

```
Error: MRC API version mismatch. Restarting app and retrying...terminate called without an active exception
robot.service: Main process exited, code=exited, status=134/n/a
```

You can read the image's supported version directly:

```bash
ssh systemcore@192.168.1.202 'python3 -c "
import ctypes
lib=ctypes.CDLL(\"/usr/lib/libMrcLib.so\")
f=lib.MRC_CheckApiVersion; f.argtypes=[ctypes.c_uint32]; f.restype=ctypes.c_int
print([v for v in range(40) if f(v)])"'
```

The check is `requested <= supported`, so the image accepts anything up to its own version.
The allwpilib side is one line — `mrclib` in `gradle/libs.versions.toml` — and the API
number is in that artifact's `mrclib/ApiVersion.h`.

Observed mapping:

| mrclib version | `MRC_API_VERSION` |
|---|---|
| `2027.1.0-alpha-1-60-g9458300` | 4 |
| `2027.1.0-alpha-1-80/84` | 5 |
| `2027.1.0-alpha-1-90/91` | 6 |
| `2027.1.0-alpha-1-99-g80a042d` | 9 |
| `2027.1.0-alpha-1-112-g3f8f56e` | 11 ← allwpilib `cafb0cc79` (alpha-7) |
| `2027.1.0-alpha-1-116-g5288562` | 12 ← open PR [allwpilib#9342](https://github.com/wpilibsuite/allwpilib/pull/9342) |

**Six API revisions in about two months.** This is not a stable ABI, and nothing warns you
at build time — you find out when the robot crash-loops on the device.

### What this cost us, concretely

The device was on OS build **beta14-201** (source commit `cce35b3d1108…`, 2026-08-13),
which caps at **API 9**. allwpilib `cafb0cc79` requires **11**, because
[allwpilib#9288](https://github.com/wpilibsuite/allwpilib/pull/9288) (`c2496f0e2`,
2026-08-16) bumped it. Deploying crash-looped.

Flashing **beta14-203** (source commit `932ee883…`, 2026-08-17) raised the device to
**API 11** and the same build then ran unmodified. OS images come from
[LimelightVision/systemcore-os-public](https://github.com/LimelightVision/systemcore-os-public)
— a `.llupdate` payload (671 MB) updates in place; the `.zip` (747 MB) is a full reflash.
Read the running image's build with `grep VERSION= /etc/os-release` and match the commit
against a release's "Source Commit".

**Pinning rule for this repo:** treat the OS image build and the allwpilib commit as a
*pair*. Record both. Do not bump one without checking the other. Note that open PR #9342
would push upstream to API 12, which beta14-203 does **not** accept.

## 4. Verified running

Trivial `OpModeRobot` program (a heartbeat print from `nonePeriodic()`), deployed and run
against unmodified allwpilib `cafb0cc79` on beta14-203:

```
********** Robot program starting **********
NT: Listening on port 5810
NT: mDNS announcing as service 'robot' on port 5810
[2027beta] OpRobot constructed. java=25.0.2 arch=aarch64
********** Robot program startup complete **********
[2027beta] nonePeriodic tick 50  dt=20.013 ms  maxJitter=15.608 ms
```

The program:

```java
public class OpRobot extends OpModeRobot {
  private long m_lastNanos = System.nanoTime();
  private int m_ticks;
  private double m_maxJitterMs;

  public OpRobot() {
    System.out.println("[2027beta] OpRobot constructed."
        + " java=" + System.getProperty("java.version")
        + " arch=" + System.getProperty("os.arch"));
  }

  @Override
  public void nonePeriodic() {
    long now = System.nanoTime();
    double dtMs = (now - m_lastNanos) / 1e6;
    m_lastNanos = now;
    if (m_ticks > 0) {
      m_maxJitterMs = Math.max(m_maxJitterMs, Math.abs(dtMs - getPeriod() * 1000.0));
    }
    if (++m_ticks % 50 == 0) {
      System.out.printf("[2027beta] nonePeriodic tick %d  dt=%.3f ms  maxJitter=%.3f ms%n",
          m_ticks, dtMs, m_maxJitterMs);
    }
  }
}
```

With `Main.java` changed to `RobotBase.startRobot(OpRobot::new)`.

⚠️ **allwpilib builds with `-Werror` and the `this-escape` lint.** Calling `getPeriod()`
inside the constructor failed the build. That is allwpilib's own config, not something a
robot project inherits — but it bites when using `developerRobot` as a scratchpad.

### First real numbers on hardware

`nonePeriodic()` at the default 20 ms period, no DS connected, no CAN hardware:

| Metric | Value |
|---|---|
| Loop period | min 19.956 / p50 20.002 / p95 20.166 / p99 20.376 / max 20.376 ms |
| Max jitter over ~9000 ticks | 15.6 ms — **all of it in the first few loops** |
| RSS | 83 MB |
| Threads | 36 |

The 15.6 ms outlier is startup, not steady state: the first `nonePeriodic()` calls take
~25 ms because of the `System.out.println` itself, which trips the watchdog:

```
Warning ... nonePeriodic(): 0.024848s
```

**Console printing is expensive enough to overrun a 20 ms loop.** Worth remembering when
deciding console strategy — journal output is not free.

### RT priorities are real

`real-time-thread-priorities.md` documents CAN = 50 and Notifier = 40. Confirmed empirically
with `chrt` on the live process — exactly two `SCHED_RR` threads:

```
tid=5667 java :: SCHED_RR priority 50     # CAN
tid=5673 java :: SCHED_RR priority 40     # Notifier
```

`LimitRTPRIO=50` in the unit file is honoured. Everything else, including the robot loop,
is normal priority — as [#9](https://github.com/Drew-Robotics/2027beta/issues/9) said.

### Nothing writes a WPILOG

Confirmed on a running program: `find /home /var /tmp -name '*.wpilog'` returns nothing.
This answers the map's open question "where do DataLog files land on SystemCore?" —
**nowhere, until we register a `DataLog` backend ourselves**, exactly as
[#2](https://github.com/Drew-Robotics/2027beta/issues/2) predicted.

## 5. Stopping it

The program is left deployed and running, and will restart on boot.

```bash
ssh systemcore@192.168.1.202 sudo systemctl stop robot     # stop now
ssh systemcore@192.168.1.202 sudo rm /home/systemcore/robotCommand   # stop it starting at boot
```
