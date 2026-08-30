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
- `Sendable`, `SmartDashboard`, `RobotContainer`, `Subsystem`, and several old
  command classes are gone. Mechanisms are fields on `Robot`; opmodes own
  bindings.

Field hazards that still compile:

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
