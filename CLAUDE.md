# 2027beta

FRC team 8852's swerve drive base for the 2027 season, on the SystemCore
control system. WPILib 2027 alpha, Java 25, Commands v3.

The architecture is being designed on the issue tracker before it is
built — see the map at issue #1. Until the ADRs land, that map is the
record of what has been decided.

## WPILib 2027 is not the WPILib you know

2027 is a rewrite. Every snippet on the internet, and every completion
your training gives you, is wrong at the import line. Check the source
at `~/dev/allwpilib` before believing yourself.

The package root is `org.wpilib`. There is no `edu.wpi.first`.

Renamed, with no deprecation shim:

| You will write | It is now |
|---|---|
| `ChassisSpeeds` | `ChassisVelocities` |
| `SwerveModuleState` | `SwerveModuleVelocity` |
| `vxMetersPerSecond`, `vyMetersPerSecond` | `vx`, `vy` |
| `MathUtil` | `math.util` |
| `LinearSystemId` | `Models` |
| `edu.wpi.first.math.system.plant` | deleted |

Deleted outright, so any code reaching for them will not compile:
`Sendable`, `SendableBuilder`, `SendableChooser`, `SmartDashboard`,
`SwerveControllerCommand`, `HolonomicDriveController`. Commands v3 has
no `Subsystem` type and no `RobotContainer` — mechanisms are fields on
`Robot`, and op modes carry the bindings.

Four traps that compile fine and fail on the field:

- `Rotation2d` stores cos/sin only, so `getRotations()` returns
  `[-0.5, 0.5]` while our steer sensor reads `[0, 1)`. Converting
  between them is not optional. `AbsoluteEncoderConfig.zeroCentered`
  would delete the mismatch, and does not exist for an analog sensor.
- `ChassisAccelerations.toWheelAccelerations()` hardcodes ω = 0 and
  silently drops the centripetal term, which dominates during rotation.
  Always use the 2-argument form.
- Commands v3 cancellation is not an exception unwind. Cleanup goes in
  `whenCanceled()`. A `finally` block never runs.
- The compiler plugin's missing-`yield` check covers `while` only.
  Loops in coroutine bodies are always `while` — a `for(;;)` compiles
  clean and hangs the robot.

`Alert` exists, at `org.wpilib.util.Alert`, and takes a mandatory `id`.
Duplicate `(group, id)` throws.

## REVLib 2027 renamed the two calls you use most

Every SPARK snippet on the internet is wrong at the call, not the
import. Read the sources from `maven.revrobotics.com`, not your memory.

| You will write | It is now |
|---|---|
| `controller.setReference(...)` | `controller.setSetpoint(...)` |
| `spark.set(throttle)` | `spark.setThrottle(throttle)` |

Plain-double getters are gone: reads return `Signal<T>`.

`configure()` throws on failure, except for `kTimeout` and
`kCannotPersistParametersWhileEnabled`, which it returns. Success is no
exception *and* `kOk` — checking only one of those misses half the
failures.

The steer loop closes on the SPARK, against the analog absolute
encoder. Robot-side code writes a setpoint, never a voltage, and the
module zero offset is added to that setpoint here, because
`AnalogSensorConfig` has no `zeroOffset`.

## Comments

Comment the line that confuses. A non-obvious unit, a surprising
ordering, a workaround for someone else's bug — one line, at the line.

Everything else the code says better, and says truthfully. A comment
describing intent is a claim that stops being checked the moment the
code changes.

Names are the documentation. When a block needs a paragraph to explain,
extract it and let the method name carry the paragraph.

Code names no issue, ADR or document. That history lives in the commit
message and the PR. The one exception is an *upstream* defect — an
allwpilib, REV or CTRE bug — because that link is the only thing that
tells a future reader when the workaround can be deleted.

Public methods get no Javadoc. Command names already follow
`Mechanism.Action`, and every quantity is a `Measure`, so a summary
line and a units line would both be restating the signature.

## Where things are

<!-- One line per ADR as they land. This file points; it never restates. -->

- `docs/research/` — verified findings from the design map, with sources.
