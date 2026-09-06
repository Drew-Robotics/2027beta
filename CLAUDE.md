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
- Analog sensors have no zero offset. Apply the module offset to the setpoint.

## Vendor deps

GradleRIO refuses to configure if a vendordep's `wpilibYear` is not the exact
year string of the WPILib being built against, and no third-party vendor has
published an alpha-7 build yet. `wpilibYear` is edited to `2027_alpha7` by hand
in `REVLib.json`, `Phoenix6-*.json` and `photonlib.json`; the pinned artifact
versions are untouched, so this asserts a compatibility the tests have to carry.
Re-importing any of them from its vendor URL reverts the field and the next
build fails on the year rather than on anything real.

## Comments

Keep only short comments that explain a surprising unit, order, or workaround.
Let names and small methods explain the normal case. Do not add public-method
Javadoc or history links to code; link upstream bugs only when a workaround
needs a removal condition.

## Documents

- `docs/adr/` — architecture decisions.
- `CONTEXT.md` — project glossary.
- `docs/commands-v3-house-style.md` — command style.
- `docs/research/` — sources and measurements.
