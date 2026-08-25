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
