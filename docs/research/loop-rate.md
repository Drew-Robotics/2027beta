# Loop rate on SystemCore

Measured 2026-08-25 on the SystemCore Pi (`192.168.1.202`, limelightosr-2027.0.0-beta14,
MRC API 11) against WPILib 2027 alpha-7. Harness: [`loop-bench/LoopBench.java`](loop-bench/LoopBench.java),
deployed via `./gradlew :developerRobot:deployJava` from `~/dev/allwpilib`.

## What was measured

A swerve-shaped workload in `robotPeriodic()`: 4-module `SwerveDriveKinematics`
→ `ChassisVelocities.discretize` → `desaturateWheelVelocities` → per-module
`optimize` → `SwerveDrivePoseEstimator3d.updateWithTime`, plus ~50 telemetry
signals. 3000 samples per phase. `work` is time inside the callback; `wake` is
wake-to-wake delta.

| period | phase | work p50 | p95 | p99 | max | wake p50 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| 20 ms | math only | 46 µs | 143 | 254 | 447 µs | 19.999 ms | 20.068 | 41.1 ms |
| 20 ms | math + NT | 73 µs | 140 | 215 | 9537 µs | 19.999 ms | 20.065 | 21.2 ms |
| 5 ms | math only | 38 µs | 127 | 210 | 1215 µs | 5.000 ms | 5.061 | 32.9 ms |
| 5 ms | math + NT | 58 µs | 135 | 207 | 6676 µs | 5.000 ms | 5.043 | 13.5 ms |
| 5 ms | math + NT + WPILOG | 65 µs | 123 | 225 | 954 µs | 5.000 ms | 5.045 | 14.3 ms |

WPILOG disk rate at 5 ms: **89.3 KB/s → 13.1 MB per 150 s match**.

## Findings

**Compute is not the constraint.** A full swerve math loop plus 50 logged
signals to both NetworkTables and a WPILOG is ~65 µs — a **1.3% duty cycle** at
5 ms, 4.5% at p99. The Pi holds a 5 ms period with 45 µs of p99 jitter.

**The jitter tail is absolute, not proportional.** Both periods showed
multi-millisecond hiccups (9.5 ms at 20 ms; 6.7 ms at 5 ms) and scheduler gaps
of 41.1 ms and 32.9 ms. These do not shrink with the period, so the same event
costs 4x as many iterations at 200 Hz.

**The watchdog does not see a missed deadline.** `m_watchdog` is reset at the
top of `loopFunc` (`OpModeRobot.java:610`) and measures only work *inside* it.
The 32.9 ms wake gap swallowed six iterations at 5 ms and produced no
`opmode-loop-overrun` alert and no epoch dump. This is the same blind spot
already recorded for an enabled op mode's `periodic()`, reached from a second
direction. Wake-to-wake delta must be logged explicitly.

**High-rate odometry is not free.** REVLib's `SignalsConfig` sets status-frame
periods per frame *group*. Polling `getPosition()` at 200 Hz against a 20 ms
frame returns the same cached value four times. The fidelity win requires
raising Status2, which is a CAN decision independent of the loop rate.

**WPILOG is cheaper than NetworkTables.** Adding a `DataLogTelemetryBackend`
alongside the NT backend cost ~7 µs p50 (58 → 65 µs).

## Traps

**`registerBackend` is a map keyed by prefix.** `TelemetryRegistry.registerBackend`
does `s_backends.put(normalizedPrefix, backend)` and then *closes* the displaced
backend (`TelemetryRegistry.java:205`). Registering a second backend at `""`
silently replaces NetworkTables rather than adding to it. Both together requires
`MultiTelemetryBackend`.

**`SignalsConfig` period setters take the minimum.** `setPeriodMsCore` does
`Math.min(existing, new)`, so within one config the fastest consumer sets the
whole frame group. Motor temperature cannot be slow if applied output is fast —
they are both Status0.

**Status frame rates do not survive a power cycle.** REV documents this
explicitly. A browned-out SPARK returns at default frame rates with no fault
and no log entry.

**The published SPARK MAX frame table is stale for 2027.** REVLib 2027
(`2027.0.0-alpha-6`) ships ten frame groups, not seven, and faults and
temperature have swapped frames relative to the doc:

| | docs.revrobotics.com (2025) | REVLib 2027 |
|---|---|---|
| Status0 | applied output, faults, sticky faults, is-follower | appliedOutput, busVoltage, outputCurrent, motorTemperature, limits |
| Status1 | velocity, temperature, voltage, current | faults, warnings |
| Status2 | motor position | primaryEncoder velocity + position |
| Status3 | analog sensor | analogVoltage, analogVelocity, analogPosition |
| Status4 | alternate encoder | externalOrAltEncoder velocity + position |
| Status5 | duty-cycle absolute position | absoluteEncoder velocity + position |
| Status6 | duty-cycle absolute velocity | unadjustedDutyCycle, dutyCycle |
| Status7 | — | iAccumulation |
| Status8 | — | setpoint, isAtSetpoint, selectedSlot |
| Status9 | — | maxMotionSetpointPosition, maxMotionSetpointVelocity |

The 1 ms–32767 ms period range and the non-persistence both still hold; they are
firmware-level.

## Bus topology

`can_s0`–`can_s4` on the Pi are **mtu 72 (CAN FD)**; the Motioncore `can_d*`
buses are mtu 16 (classic). SPARKs are CAN 2.0 devices, so a bus shared with
them runs classic framing and the FD capability goes unused.

At ~131 bits per extended frame, 1 Mbit gives roughly 6400–7600 frames/s.

| source | rate | frames/s |
|---|---|---|
| Status2, encoder position + velocity × 8 | 5 ms | 1600 |
| setpoint writes × 8 | 5 ms | 1600 |
| Pigeon2 yaw | 5 ms | ~200 |
| Status1 faults/warnings × 8 | 50 ms | 160 |
| Status0 × 8 | 100 ms | 80 |
| Status5 absolute encoder × 8 | 200 ms | 40 |
| | **total** | **~3680** |

**48–57% utilisation**, plus the Pigeon's 0–5% diagnostic floor. The two 5 ms
rows are 87% of the traffic and are irreducible at 200 Hz.

## Caveats

No CAN hardware and no Driver Station were attached. Eight real `getPosition()`
calls per loop are **not** in these numbers. Duplicate suppression is on by
default and ~18 of the 50 logged signals were constants, so the 13.1 MB/match
figure understates a real log — budget nearer 20 MB.

## Real-time thread priority

Measured 2026-08-25 on the same Pi. Harness:
[`loop-bench/LoopBenchCpp.cpp`](loop-bench/LoopBenchCpp.cpp), built with
`:developerRobot:developerRobotCppLinuxsystemcoreExecutable` and deployed with
`:developerRobot:deployShared`.

**The loop body does not run at real-time priority.** allwpilib's own
[`design-docs/real-time-thread-priorities.md`](https://github.com/wpilibsuite/allwpilib/blob/main/design-docs/real-time-thread-priorities.md)
lists exactly two RT threads, and `/proc` on a running robot confirms it — 2 of
39 threads are `SCHED_RR`, the other 37 are `SCHED_OTHER` at priority 0:

| thread | policy | rt_priority |
|---|---|---|
| CAN HAL (`hal/.../systemcore/CAN.cpp`) | `SCHED_RR` (2) | 50 |
| Notifier HAL (`hal/.../systemcore/Notifier.cpp`) | `SCHED_RR` (2) | 40 |
| **main thread — runs `loopFunc`/`robotPeriodic`** | **`SCHED_OTHER` (0)** | **0** |

`OpModeRobot.startCompetition()` runs `m_callbacks.runCallbacks(m_notifier)` in
a `while (true)` on the **calling thread**, so every periodic callback executes
on the main thread. The RT notifier only *signals* it.

The `robot` systemd unit sets `LimitRTPRIO=50`, so a thread may raise itself to
`SCHED_RR` up to 50 without `CAP_SYS_NICE`. The C++ deploy additionally runs
`setcap cap_sys_nice+eip` on the binary; the Java deploy does not, and relies on
the rlimit.

### C++ at 1000 Hz

Same swerve workload as the Java bench, 5000 samples, `OpModeRobot(1_ms)`:

| requested priority | resulting policy | work p50 | p95 | p99 | max | wake p50 | p95 | p99 | **wake max** |
|---|---|---|---|---|---|---|---|---|---|
| 0 | `SCHED_OTHER` | 28.5 µs | 39.0 | 42.6 | 205 µs | 0.9995 ms | 1.0151 | 1.1175 | **1.7245 ms** |
| 30 | `SCHED_RR` 30 | 28.3 µs | 38.9 | 42.2 | 79 µs | 0.9998 ms | 1.0088 | 1.0142 | **1.0463 ms** |
| 45 | `SCHED_RR` 45 | 30.8 µs | 40.1 | 42.3 | 81 µs | 0.9999 ms | 1.0067 | 1.0109 | **1.0373 ms** |

**1000 Hz is comfortable in C++**, at ~2.8% duty. Raising the loop thread to
`SCHED_RR` cuts worst-case wake jitter from **725 µs to 37 µs** — roughly 20x —
and worst-case work from 205 µs to 79 µs. Priority 30 versus 45 makes no
meaningful difference; what matters is crossing from `SCHED_OTHER` to `SCHED_RR`.

**The big tails in the Java benchmark were the JVM, not the OS.** C++ at
`SCHED_OTHER` and a *four times shorter* period still held a 1.72 ms worst case,
while Java at 5 ms saw 13.4 ms and 32.9 ms gaps. Those are GC and JIT pauses,
which no scheduling policy fixes — a stop-the-world collection pauses the RT
thread too.

### Upstream defect: `SetCurrentThreadPriority` returns inverted success

`HAL_Status` is **0 on success**, but both language bindings return the raw
status as a success boolean, so **they report failure when they succeed**:

- `wpilibc/src/main/native/cpp/system/Threads.cpp` — `return status != 0;`
  (also in `SetThreadPriority`)
- `hal/src/main/native/cpp/jni/ThreadsJNI.cpp` — `return static_cast<jboolean>(status);`

Observed directly: the bench logged `ok=0` while `/proc` showed the thread had
moved to `SCHED_RR` 45. **Check the resulting priority with
`GetCurrentThreadPriority()`; do not trust the return value.** Both functions
are also `@Deprecated` upstream, warning that misuse can lock up the system.

### Java gets the same knobs, and much less out of them

Nothing above is C++-only. The two RT HAL threads were measured **in the Java
process** — they are HAL-level, so a Java robot gets the RT CAN and notifier
threads identically. `org.wpilib.system.Threads.setCurrentThreadPriority` is the
Java binding of the same call, and it works: the bench logged
`before=0 requested=30 returned=false after=30 thread=main`, confirming both that
the main thread moved to `SCHED_RR` 30 and that the Java binding carries the same
inverted return value. It is `@Deprecated`, so it needs
`@SuppressWarnings("deprecation")` to compile under the repo's `-Werror`.

Java at 1000 Hz, 3000 samples per phase:

| period | prio | phase | work p50 | p95 | p99 | wake p95 | wake p99 | **wake max** |
|---|---|---|---|---|---|---|---|---|
| 1 ms | 0 | math + NT + WPILOG | 63.4 µs | 130.9 | 226.5 | 1.030 ms | 1.155 | **14.19 ms** |
| 1 ms | 30 | math + NT + WPILOG | 68.0 µs | 115.0 | 194.8 | **1.011 ms** | **1.021** | **12.33 ms** |
| 5 ms | 0 | math + NT + WPILOG | 65.9 µs | 117.8 | 205.0 | 5.015 ms | 5.045 | 14.36 ms |
| 5 ms | 30 | math + NT + WPILOG | 67.7 µs | 124.4 | 220.6 | 5.013 ms | 5.028 | 12.81 ms |

**Java holds 1000 Hz at p99** — 1.021 ms with RT — and JIT-compiled math is
within ~17% of C++ (33.3 µs vs 28.5 µs for the math-only phase).

**But RT priority buys Java far less than it buys C++.** In C++ it collapsed the
worst-case wake tail from 725 µs to 37 µs. In Java it improves p95/p99 —
visibly at 1 kHz, marginally at 5 ms — and leaves the worst case at **12–18 ms**,
because that tail is GC and JIT, which no scheduling policy touches. Raising the
loop thread is therefore a p99 optimisation in Java, not a worst-case guarantee.

The WPILOG byte rate was not measured at 1 ms — a 3-second phase is shorter than
the background writer's flush period, and the file had not grown when sampled.
