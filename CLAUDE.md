# 2027beta

Team 8852's 2027 SystemCore swerve drive base. Read the relevant ADR before
changing code.

## WPILib 2027

This is a major API rewrite. Check `~/dev/allwpilib` instead of copying online
examples.

- Package root: `org.wpilib`, not `edu.wpi.first`.
- `ChassisSpeeds` is now `ChassisVelocities`.
- `SwerveModuleState` is now `SwerveModuleVelocity`.
- `vxMetersPerSecond` and `vyMetersPerSecond` are now `vx` and `vy`.
- `MathUtil` is now `math.util`; `LinearSystemId` is now `Models`.
- `CANBus` is now `CANPort`; the maven artifact `commands3-java` is now
  `commandsv3-java`. The `org.wpilib.command3` package name did not change.
- Geometry constants lost the `k` prefix: `Rotation2d.kZero` is `ZERO`, `kPi` is
  `PI`, `kCCW_Pi_2` is `CCW_PI_2`.
- `Sendable`, `SmartDashboard`, `RobotContainer`, `Subsystem`, and several old
  command classes are gone. Mechanisms are fields on `Robot`; opmodes own
  bindings.

Field hazards that still compile:

- The WPI clock is nanoseconds, not microseconds. `RobotController.getTime()`,
  `getMonotonicTime()`, `getLoopStartTime()`, `wpi::Now()` and an alert's
  `activeStartTime` all changed base in alpha-7 with no change of signature, so
  a stale `Microseconds.of(...)` is off by 1000 and still compiles. `Timer`
  still answers seconds.
- The WPILOG *file* still stores microseconds. `DataLog` divides the
  nanoseconds it is given, and `DataLogRecord.getTimestamp()` multiplies them
  back, so the Java API is nanoseconds in both directions and only a
  hand-rolled parser sees the microseconds on disk.
- `Rotation2d` uses `[-0.5, 0.5]` rotations; the steer sensor uses `[0, 1)`.
- Use the two-argument `ChassisAccelerations.toWheelAccelerations()` so it keeps
  the centripetal term.
- Coroutine cancellation runs `whenCanceled()`, not `finally`.
- Coroutine loops must be `while`; `for (;;)` bypasses the missing-`yield` check.

`Alert` is `org.wpilib.util.Alert`. Its `id` is required and duplicate
`(group, id)` values throw.

## REVLib 2027

- Use `controller.setSetpoint(...)`, not `setReference(...)`.
- Use `spark.setThrottle(...)`, not `set(...)`.
- Getters return `Signal<T>`, not plain doubles.
- `configure()` can throw or return an error. Check for both.
- Analog sensors have no zero offset. The module offset is applied in the seed.
- The SPARK constructors take an `org.wpilib.hardware.bus.CANPort`, not an int.
- `SparkBase.getBusId()` is now `SparkLowLevel.getCanPort()`.
- Conversion factors are gone outright, with no replacement: `EncoderConfig` and
  `AnalogSensorConfig` have no `positionConversionFactor`/
  `velocityConversionFactor`. Sensors report native units — motor rotations and
  RPM for the encoder, volts for the analog — so every conversion lives in
  Java. `DriveConstants.onboardGains` is the one place a gain is rescaled;
  everything in `DriveConstants` itself is per metre per second and per module
  rotation.
- `ClosedLoopConfig.positionWrappingInputRange` is gone, and
  `positionWrappingEnabled` now folds an error over **exactly one native unit**
  — one volt on the analog, one *motor* rotation on the encoder. Nothing here
  uses it: steer closes on the motor's own encoder and takes the short way by
  writing an offset (`DriveConstants.steerSetpoint`). See ADR 0008 and
  `docs/research/revlib-alpha7-units.md`.

## Vendor deps

GradleRIO refuses to configure if a vendordep's `wpilibYear` is not the exact
year string of the WPILib being built against.

The WPILib vendordep marketplace now carries a `2027_alpha7` set, and both
`REVLib.json` and `Phoenix6-26.70.0-alpha-2.json` are that set's files
unedited — they declare `wpilibYear: 2027_alpha7` themselves, so re-importing
either is safe:
<https://github.com/wpilibsuite/vendor-json-repo/tree/main/2027_alpha7>

CTRE's own `jsonUrl` inside the Phoenix file still 404s; the marketplace is the
working source for it.

`photonlib.json` is the one file that still carries a hand-edited `wpilibYear`.
PhotonVision has published nothing since `v2027.0.0-alpha-2`, whose upstream
`wpilibYear` is `2027_alpha5`, and the marketplace has no photonlib in the
alpha-7 set, so re-importing it reverts the field and the next build fails on
the year rather than on anything real. Nothing in `src` imports it yet.

## Comments

Keep only short comments that explain a surprising unit, order, or workaround.
Let names and small methods explain the normal case. Do not add public-method
Javadoc or history links to code; link upstream bugs only when a workaround
needs a removal condition.

## Documents

- `docs/adr/` — architecture decisions.
- `CONTEXT.md` — project glossary.
- `docs/bench-runner.md` — the box the bench workflow runs on.
- `docs/commands-v3-house-style.md` — command style.
- `docs/research/` — sources and measurements.
