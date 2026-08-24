# WPILib 2027 built-in swerve, kinematics and odometry

Research for [issue #4](https://github.com/Drew-Robotics/2027beta/issues/4).

**Primary source:** local built checkout of `allwpilib` at `~/dev/allwpilib`,
version `v2027.0.0-alpha-6-366-gcafb0cc79` (effectively alpha-7). Everything
below is read from that source tree, not from `docs.wpilib.org` (which lags
badly and still documents the 2025/2026 API).

**Verification legend**

- **[V]** Verified — read directly from source in the checkout, or executed
  against the built `wpimath.jar` on this machine.
- **[E]** Empirically executed — I compiled and ran a probe against the built
  jars; the printed output is quoted.
- **[I]** Inferred — a conclusion I drew, not a statement the source makes.

---

## 0. Executive summary — the renames come first

Before anything else: **the swerve type names in the issue no longer exist.**
This is a bigger break than the two changes the issue calls out, because it
means every 2025/2026 code sample, tutorial, ChiefDelphi post, and LLM
completion you will find is wrong at the import line.

| 2025 / 2026 | 2027 alpha-7 | [V] |
|---|---|---|
| `ChassisSpeeds` | **`ChassisVelocities`** | V |
| `SwerveModuleState` | **`SwerveModuleVelocity`** | V |
| `SwerveModulePosition` | `SwerveModulePosition` (unchanged name) | V |
| — | **`ChassisAccelerations`** (new) | V |
| — | **`SwerveModuleAcceleration`** (new) | V |
| `edu.wpi.first.*` | **`org.wpilib.*`** | V |
| `edu.wpi.first.math.MathUtil` | **`org.wpilib.math.util.MathUtil`** | V |
| `edu.wpi.first.math.system.plant.DCMotor` | **`org.wpilib.math.system.DCMotor`** (`plant` package deleted) | V |
| `LinearSystemId` | **`org.wpilib.math.system.Models`** | V |
| `edu.wpi.first.wpilibj.simulation` | **`org.wpilib.simulation`** | V |
| `edu.wpi.first.wpilibj.TimedRobot` | **`org.wpilib.framework.TimedRobot`** | V |

Field names changed too:

| 2025 | 2027 | [V] |
|---|---|---|
| `ChassisSpeeds.vxMetersPerSecond` | `ChassisVelocities.vx` | V |
| `ChassisSpeeds.vyMetersPerSecond` | `ChassisVelocities.vy` | V |
| `ChassisSpeeds.omegaRadiansPerSecond` | `ChassisVelocities.omega` | V |
| `SwerveModuleState.speedMetersPerSecond` | `SwerveModuleVelocity.velocity` | V |
| `SwerveModulePosition.distanceMeters` | `SwerveModulePosition.distance` | V |

Source: `~/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/kinematics/`.

---

## 1. Rotation2d now wraps — exactly what, to what range, and what it breaks

### 1.1 The mechanism

`Rotation2d` no longer stores an angle. It stores **only a cosine and a sine**
[V]:

```java
// wpimath/src/main/java/org/wpilib/math/geometry/Rotation2d.java
@Json.Ignore private final double m_cos;
@Json.Ignore private final double m_sin;

public Rotation2d(@Json.Alias(value = "radians") double value) {
  m_cos = Math.cos(value);
  m_sin = Math.sin(value);
}
```

Every angular getter is therefore derived by `atan2`, which is *definitionally*
range-limited [V]:

```java
/**
 * Returns the radian value of the Rotation2d constrained within [-π, π].
 */
@Json.Property("radians")
public double getRadians() {
  return Math.atan2(m_sin, m_cos);
}

public double getDegrees() {
  return Math.toDegrees(getRadians());
}

public double getRotations() {
  return Units.radiansToRotations(getRadians());
}
```

**The wrap is not a policy decision applied at the getter — it is a consequence
of the storage format.** The angle information above ±π is destroyed at
*construction time*, not at read time. There is no unwrapped accessor, no
`getUnwrappedRadians()`, and no way to recover it. [V]

### 1.2 The exact ranges

| Method | Range | [V] |
|---|---|---|
| `getRadians()` | `[-π, π]` | V (javadoc + `atan2` contract) |
| `getDegrees()` | `[-180, 180]` | V |
| `getRotations()` | **`[-0.5, 0.5]`** | V |
| `getMeasure()` | `Radians.of(getRadians())`, so also `[-π, π]` | V |
| `getCos()`, `getSin()` | `[-1, 1]`, no wrapping concern | V |
| `getTan()` | unbounded (`m_sin / m_cos`); blows up near ±90° | V |

`getRotations()` returning `[-0.5, 0.5]` is the one most likely to bite, because
it does **not** match what a SPARK absolute encoder reports (`[0, 1)`).

### 1.3 Empirically confirmed behaviour [E]

Compiled and ran against `~/dev/allwpilib/wpimath/build/libs/wpimath.jar`:

```
fromDegrees(270).getDegrees()             = -90.000000
fromDegrees(360).getDegrees()             = -0.000000
fromDegrees(720).getDegrees()             = -0.000000
fromDegrees(180).getDegrees()             = 180.000000
fromDegrees(-180).getDegrees()            = -180.000000
fromRotations(2.25).getRotations()        = 0.250000
fromDegrees(270).times(2).getDegrees()    = 180.000000
fromDegrees(100).times(3).getDegrees()    = -60.000000   (naive answer: 300)
fromDegrees(170).plus(fromDegrees(20))    = -170.000000
sum of 10 × 36°                           = 0.000000     (not 360)
interpolate(170° -> -170°, t=0.5)         = -180.000000
PI.getDegrees()                           = 180.000000
fromDegrees(90).getTan()                  = 1.633124e+16
```

Two subtleties worth noting:

- `fromDegrees(180)` gives `+180` but `fromDegrees(-180)` gives `-180` — the
  boundary sign follows the sign of the (near-zero) sine, i.e. `atan2(+0, -1)`
  vs `atan2(-0, -1)`. **Do not write equality or `>=`/`<=` comparisons against
  the ±180 boundary.** [V/E]
- `interpolate(170°, -170°, 0.5)` lands on `-180`, not `0`. Rotation2d
  interpolation takes the short way round (`plus(endValue.minus(this).times(t))`),
  which is correct — but it means interpolating rotations is *not* a lerp of the
  degree values. [V/E]

### 1.4 Code patterns this silently breaks

This is the important list. Each of these compiles cleanly and produces wrong
numbers at runtime.

**(a) `Rotation2d.times(scalar)` and `div(scalar)` — wrap before scaling.** [V/E]

```java
public Rotation2d times(double scalar) {
  return new Rotation2d(getRadians() * scalar);   // getRadians() is already wrapped
}
```

`Rotation2d.fromDegrees(120).times(2)` gives `-120°`, not `240°`. Any code that
scales a rotation (gain scheduling, "half the turn", scaling a heading error)
is wrong. **Do the scalar arithmetic on doubles and construct the Rotation2d
last.**

**(b) Reading a "total turns" / continuous heading off a gyro Rotation2d.** [V/E]

`Pigeon2.getRotation2d()` (and `OnboardIMU.getRotation2d()`) hand you a
`Rotation2d`. Its `getDegrees()` is wrapped. If you want continuous accumulated
yaw for a spin-counter, a turret-style wrap-avoidance, or a "don't twist the
CANivore cable" check, **you must read the vendor's own continuous accumulator**
(`Pigeon2.getYaw()` in CTRE Phoenix returns a continuous, unbounded signal) and
never round-trip it through `Rotation2d`. Verified empirically: accumulating ten
36° `plus()` operations returns `0.0`, not `360`.

**(c) Odometry pose heading is not a lap counter.** [E]

```
pose heading after 10 full revolutions = 0.0000 deg   (not 3600)
```

`SwerveDriveOdometry` / `SwerveDrivePoseEstimator` return a `Pose2d`, whose
`getRotation()` is a `Rotation2d`. It cannot represent >½ turn of accumulated
heading. That is *correct* for pose, but anything that integrates
`pose.getRotation().getDegrees()` over time is wrong.

**(d) SPARK absolute-encoder round-trips lose the upper half-turn.** [E]

```
encoder reads 0.75 rotations (270°)
  -> Rotation2d.fromRotations(0.75).getRotations() = -0.2500
target Rotation2d.fromDegrees(200).getRotations()  = -0.4444  (i.e. -160°)
```

A REV absolute encoder configured with the default `[0, 1)` range, or a SPARK
closed-loop position controller configured with `PositionWrappingInputRange`
`0..1`, will disagree with anything `Rotation2d.getRotations()` produces for
angles in `(180°, 360°)`. The fix is either:

- configure the SPARK's position-wrapping range as `-0.5 .. 0.5` to match, **or**
- normalise explicitly: `MathUtil.inputModulus(rot.getRotations(), 0, 1)`.

This is the single most likely source of "one module points the wrong way" on
this project's REV hardware. [I — the failure mode is inferred; the wrapped
`getRotations()` values are verified]

**(e) `enableContinuousInput` — safe, but only if the width is right.** [V/E]

`PIDController` only ever uses the *error modulus*, never the raw bounds [V]:

```java
if (m_continuous) {
  double errorBound = (m_maximumInput - m_minimumInput) / 2.0;
  m_error = MathUtil.inputModulus(m_setpoint - m_measurement, -errorBound, errorBound);
}
```

So `enableContinuousInput(-Math.PI, Math.PI)` and
`enableContinuousInput(0, 2*Math.PI)` behave identically — what matters is that
`max - min == 2π`. Feeding a wrapped `getRadians()` setpoint into a controller
whose measurement is `[0, 2π]` is fine. Feeding it into a controller configured
with the **wrong width** (e.g. `0..π`, or `-180..180` while your measurement is
in radians) is broken, and always was. Not a new break, but now easier to hit
because the setpoint source silently changed range.

**(f) Persisted / logged angle values change meaning.** [I]

The `Rotation2d` struct schema is `"double value"` [V] — the serialised value is
`getRadians()`, so it is always in `[-π, π]`. Any dashboard, log-replay tool, or
saved calibration constant from a 2025 robot that stored a >π angle will
deserialise to a different number. For this project (fresh 2027 codebase, no
replay) this is low risk, but module offset constants copied from a 2025 repo
need re-checking.

**(g) `getTan()` is new and unguarded.** [V/E] It is a plain `m_sin / m_cos`,
so it returns `1.63e16` at 90° rather than throwing or returning infinity.
Do not use it for anything near vertical.

### 1.5 What does *not* break

Worth stating explicitly, because it saves defensive code:

- **`plus` / `minus` / `rotateBy` / `relativeTo` are all exact.** They operate on
  the cos/sin pair via rotation-matrix multiply, never touching `atan2` [V]. A
  heading *delta* across the ±180 boundary is correct:
  `fromDegrees(-179).minus(fromDegrees(179)).getDegrees() == 2.0` [E], where the
  naive double subtraction gives `-358`.
- **Odometry is wrap-safe by construction.** `Odometry.update` computes the gyro
  delta in `Rotation2d` space *before* extracting radians [V]:

  ```java
  var twist = m_kinematics.toTwist2d(m_previousWheelPositions, wheelPositions);
  twist.dtheta = gyroAngle.minus(m_previousGyroAngle).getRadians();
  m_pose = m_pose.plus(twist.exp());
  ```

  Note also there is **no gyro-offset field any more** — 2027 `Odometry` is
  purely delta-based against `m_previousGyroAngle`. You do not need to (and
  cannot) hand it a pre-offset angle. [V]
- **`SwerveModuleVelocity.optimize` is wrap-safe.** It computes
  `angle.minus(currentAngle)` first, then tests `|delta| > 90°` [V]. Verified:
  `optimize(target=170°, current=-170°)` correctly does *not* flip, because the
  true delta is 20°, not 340° [E].
- **`MathUtil.angleModulus` / `inputModulus` still exist and still work** [V/E]:
  `angleModulus(3π) = π`, `inputModulus(370, 0, 360) = 10`.

---

## 2. `ChassisSpeeds` → `ChassisVelocities`: what actually changed

### 2.1 Correction to the issue's premise: it is NOT immutable

The issue says "`ChassisSpeeds` is now immutable". That is **not accurate** for
this build. `ChassisVelocities` has **public, non-final** fields [V]:

```java
public class ChassisVelocities
    implements ProtobufSerializable, StructSerializable, Interpolatable<ChassisVelocities> {
  /** Velocity along the x-axis in meters per second. (Fwd is +) */
  public double vx;
  /** Velocity along the y-axis in meters per second. (Left is +) */
  public double vy;
  /** Angular velocity of the robot frame in radians per second. (CCW is +) */
  public double omega;
```

The same is true of `ChassisAccelerations` (`public double ax, ay, alpha`),
`SwerveModuleVelocity` (`public double velocity; public Rotation2d angle`), and
`SwerveModulePosition` (`public double distance; public Rotation2d angle`). [V]

**What actually changed is that the API became value-returning.** There are no
in-place mutator *methods* left. Every operation allocates and returns a new
instance, and several are marked `@NoDiscard` so that ignoring the return is a
**compile error**, not a silent no-op. [V]

```java
// wpiannotations/src/main/java/org/wpilib/annotation/NoDiscard.java
/**
 * Marks a method as returning a value that must be used. The WPILib compiler plugin will check for
 * uses of methods with this annotation and report a compiler error if the value is unused.
 */
```

`SwerveModuleVelocity` is annotated `@NoDiscard` **at the type level**, which the
annotation's javadoc says propagates to every method returning that type. [V]

So the practical rule for this project is: **treat all of these as value types,
never mutate the fields, always reassign.** [I] The compiler will catch most
mistakes, but not direct field writes.

### 2.2 Static → instance method migration

| 2025 | 2027 | [V] |
|---|---|---|
| `ChassisSpeeds.fromFieldRelativeSpeeds(vx, vy, ω, angle)` | `chassisVelocities.toRobotRelative(angle)` | V |
| `ChassisSpeeds.fromRobotRelativeSpeeds(...)` | `chassisVelocities.toFieldRelative(angle)` | V |
| `ChassisSpeeds.discretize(speeds, dt)` (static) | `chassisVelocities.discretize(dt)` (instance) | V |
| `SwerveModuleState.optimize(state, angle)` (static, then in-place void) | `swerveModuleVelocity.optimize(angle)` → new | V |
| `state.cosineScale(angle)` (in-place) | `swerveModuleVelocity.cosineScale(angle)` → new | V |
| `SwerveDriveKinematics.desaturateWheelSpeeds(states[], max)` (void, in-place) | `SwerveDriveKinematics.desaturateWheelVelocities(vels[], max)` → **new array**, `@NoDiscard` | V |
| `kinematics.toSwerveModuleStates(speeds)` | `kinematics.toSwerveModuleVelocities(velocities)` | V |
| `kinematics.toChassisSpeeds(states...)` | `kinematics.toChassisVelocities(velocities...)` | V |

`discretize` is unchanged in behaviour and still ships the same skew warning in
its javadoc [V]:

```java
public ChassisVelocities discretize(double dt) {
  var desiredTransform = new Transform2d(vx * dt, vy * dt, new Rotation2d(omega * dt));
  var twist = desiredTransform.log();
  return new ChassisVelocities(twist.dx / dt, twist.dy / dt, twist.dtheta / dt);
}
```

> "However, scaling down the ChassisVelocities after discretizing (e.g., when
> desaturating swerve module velocities) rotates the direction of net motion in
> the opposite direction of rotational velocity, introducing a different
> translational skew which is not accounted for by discretization."

The stock examples still do `discretize` **then** `desaturate`, i.e. they
knowingly accept that residual skew. [V]

---

## 3. `ChassisAccelerations` and second-order kinematics

### 3.1 The new types

`ChassisAccelerations { double ax, ay, alpha }` and
`SwerveModuleAcceleration { double acceleration; Rotation2d angle }`. Both have
`toRobotRelative` / `toFieldRelative`, `plus`/`minus`/`times`/`div`,
`interpolate`, struct + proto serialisation, and `LinearAcceleration` /
`AngularAcceleration` unit constructors. [V]

### 3.2 The kinematics interface is now three-typed

```java
// wpimath/src/main/java/org/wpilib/math/kinematics/Kinematics.java
public interface Kinematics<P, S, A> extends Interpolator<P> {
  ChassisVelocities    toChassisVelocities(S wheelVelocities);
  S                    toWheelVelocities(ChassisVelocities chassisVelocities);
  ChassisAccelerations toChassisAccelerations(A wheelAccelerations);
  A                    toWheelAccelerations(ChassisAccelerations chassisAccelerations);
  Twist2d              toTwist2d(P start, P end);
  P                    copy(P positions);
  void                 copyInto(P positions, P output);
}
```

`SwerveDriveKinematics implements Kinematics<SwerveModulePosition[],
SwerveModuleVelocity[], SwerveModuleAcceleration[]>`. [V]

Internally it now keeps **four** matrices, not two [V]:

```java
private final SimpleMatrix m_firstOrderInverseKinematics;   // 2n × 3
private final SimpleMatrix m_firstOrderForwardKinematics;
private final SimpleMatrix m_secondOrderInverseKinematics;  // 2n × 4
private final SimpleMatrix m_secondOrderForwardKinematics;
```

```java
m_firstOrderInverseKinematics.setRow(i * 2 + 0, 0, 1, 0, -ry);
m_firstOrderInverseKinematics.setRow(i * 2 + 1, 0, 0, 1,  rx);
m_secondOrderInverseKinematics.setRow(i * 2 + 0, 0, 1, 0, -rx, -ry);
m_secondOrderInverseKinematics.setRow(i * 2 + 1, 0, 0, 1, -ry, +rx);
```

The second-order state vector is `[aₓ, a_y, ω², α]` — i.e. it carries the
**centripetal** term explicitly. Derivation is credited in the source to
"Swerve Drive Second Order Kinematics" by FRC 449 (Rafi Pedersen). [V]

### 3.3 The trap: `toWheelAccelerations` silently drops centripetal

```java
public SwerveModuleAcceleration[] toSwerveModuleAccelerations(
    ChassisAccelerations chassisAccelerations, double angularVelocity, Translation2d centerOfRotation)

public SwerveModuleAcceleration[] toSwerveModuleAccelerations(
    ChassisAccelerations chassisAccelerations, double angularVelocity)

@Override
public SwerveModuleAcceleration[] toWheelAccelerations(ChassisAccelerations chassisAccelerations) {
  return toSwerveModuleAccelerations(chassisAccelerations, 0.0);   // <-- ω hardcoded to zero
}
```

**The `Kinematics`-interface method `toWheelAccelerations` hardcodes
`angularVelocity = 0.0`.** [V] That throws away the `ω²r` centripetal
contribution, which is the *dominant* term whenever the robot is rotating at
speed — the library's own test comments compute `a_centripetal = 668.7 m/s²` vs
`a_tangential = 106.6 m/s²` for a 2π rad/s spin. [V]

**Always call the two-argument `toSwerveModuleAccelerations(accels, omega)` with
the real current or commanded ω.** Never use `toWheelAccelerations`. [I]

### 3.4 How this fits a feedforward strategy — and the complication

The natural plan is: get `a` for each module from second-order kinematics, feed
`ka * a` into the drive motor feedforward. **`SimpleMotorFeedforward` no longer
has an acceleration-taking `calculate` overload.** [V] The full surface is:

```java
// wpimath/src/main/java/org/wpilib/math/controller/SimpleMotorFeedforward.java
public SimpleMotorFeedforward(double ks, double kv)
public SimpleMotorFeedforward(double ks, double kv, double ka)
public SimpleMotorFeedforward(double ks, double kv, double ka, double dt)

public double calculate(double velocity)                                  // assumes a = 0
public double calculate(double currentVelocity, double nextVelocity)      // discrete form
```

```java
public double calculate(double currentVelocity, double nextVelocity) {
  if (ka < 1e-9) {
    return ks * Math.signum(nextVelocity) + kv * nextVelocity;
  } else {
    double A = -kv / ka;
    double B = 1.0 / ka;
    double A_d = Math.exp(A * m_dt);
    double B_d = A > -1e-9 ? B * m_dt : 1.0 / A * (A_d - 1.0) * B;
    return ks * Math.signum(currentVelocity) + 1.0 / B_d * (nextVelocity - A_d * currentVelocity);
  }
}
```

So the acceleration enters **implicitly**, as the difference between the current
and next velocity setpoints over `dt`. There are two workable strategies:

**Strategy A — next-velocity (idiomatic, recommended).** [I]
Compute module velocities for *this* timestep and for the *next* timestep, and
call `calculate(current, next)`. With a Choreo trajectory this is free: sample
at `t` and `t + dt`, run both through `toSwerveModuleVelocities`, feed the pair
in. This uses WPILib's own discrete-time exponential-integrator form and needs
no second-order kinematics at all.

**Strategy B — explicit ka term.** [I]
Use `toSwerveModuleAccelerations(chassisAccels, omega)` and add
`ka * moduleAccel.acceleration` manually on top of
`calculate(velocity)`. This is the only route if you want the *true* module
acceleration including the centripetal term, which Strategy A only approximates
via the velocity difference (and approximates well, since the velocity samples
already encode the curvature).

The subtlety that makes Strategy B awkward: `SwerveModuleAcceleration.angle` is
the direction of the module's **acceleration vector**, which is generally *not*
the module's steering angle. You cannot just take `.acceleration` as a signed
scalar along the wheel — you need to project it onto the module's current
heading:

```java
double alongWheel = moduleAccel.acceleration
    * moduleAccel.angle.minus(moduleVel.angle).getCos();
```

[I — the projection is my reasoning; the library provides no helper for it and
no example does this.]

**Recommendation for this project: use Strategy A.** [I] It is what the library
is shaped for, it composes directly with Choreo's `HolonomicSample`, and it
avoids the module-acceleration-angle projection entirely. Keep
`ChassisAccelerations` in the picture for (a) logging, and (b) as the
Choreo-sample carrier — see §5.

### 3.5 Nothing in WPILib consumes ChassisAccelerations for control

Grepping the whole tree, `ChassisAccelerations` appears in: the kinematics
classes themselves, their struct/proto serializers, `HolonomicSample`,
`DifferentialSample`, `DrivetrainSplineSample`, and tests. **Zero examples use
it**, and no controller in `org.wpilib.math.controller` takes one. [V]

---

## 4. The joystick-to-module-output flow

### 4.1 TimedRobot flow (`swervebot` — the canonical example)

`~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/swervebot/`
(note: **`org.wpilib.examples`**, the `wpilibj` package segment is gone). [V]

**Step 1 — `Robot.java`: shape the sticks, scale to physical units.**

```java
private void driveWithJoystick(boolean fieldRelative) {
  final var xVelocity =
      -xVelocityLimiter.calculate(MathUtil.applyDeadband(controller.getLeftY(), 0.02))
          * Drivetrain.MAX_VELOCITY;
  final var yVelocity =
      -yVelocityLimiter.calculate(MathUtil.applyDeadband(controller.getLeftX(), 0.02))
          * Drivetrain.MAX_VELOCITY;
  final var rot =
      -rotLimiter.calculate(MathUtil.applyDeadband(controller.getRightX(), 0.02))
          * Drivetrain.MAX_ANGULAR_VELOCITY;

  swerve.drive(xVelocity, yVelocity, rot, fieldRelative, getPeriod());
}
```

Order: **deadband → slew-rate limit → negate → scale**. Note `getPeriod()` is
threaded through explicitly so `discretize` gets the real loop period. Input
class is `org.wpilib.driverstation.Gamepad` — `XboxController` is gone. [V]

**Step 2 — `Drivetrain.drive()`: the five-line pipeline.**

```java
public void drive(
    double xVelocity, double yVelocity, double rot, boolean fieldRelative, double period) {
  var chassisVelocities = new ChassisVelocities(xVelocity, yVelocity, rot);
  if (fieldRelative) {
    chassisVelocities = chassisVelocities.toRobotRelative(imu.getRotation2d());
  }
  chassisVelocities = chassisVelocities.discretize(period);

  var velocities =
      SwerveDriveKinematics.desaturateWheelVelocities(
          kinematics.toWheelVelocities(chassisVelocities), MAX_VELOCITY);

  frontLeft.setDesiredVelocity(velocities[0]);
  frontRight.setDesiredVelocity(velocities[1]);
  backLeft.setDesiredVelocity(velocities[2]);
  backRight.setDesiredVelocity(velocities[3]);
}
```

So the canonical order is:

```
raw stick
  -> MathUtil.applyDeadband
  -> SlewRateLimiter
  -> × MAX_VELOCITY
  -> new ChassisVelocities(vx, vy, omega)          [field frame]
  -> .toRobotRelative(gyroHeading)                 [robot frame]
  -> .discretize(period)                           [skew compensation]
  -> kinematics.toSwerveModuleVelocities(...)      [inverse kinematics]
  -> SwerveDriveKinematics.desaturateWheelVelocities(..., max)
  -> per-module: .optimize(currentAngle).cosineScale(currentAngle)
  -> drive PID + feedforward, steer ProfiledPID + feedforward
```

**Step 3 — `SwerveModule.setDesiredVelocity()`.**

```java
public void setDesiredVelocity(SwerveModuleVelocity desiredVelocity) {
  var encoderRotation = new Rotation2d(turningEncoder.getDistance());

  SwerveModuleVelocity velocity =
      desiredVelocity.optimize(encoderRotation).cosineScale(encoderRotation);

  final double driveOutput =
      drivePIDController.calculate(driveEncoder.getRate(), velocity.velocity)
          + driveFeedforward.calculate(desiredVelocity.velocity);

  final double turnOutput =
      turningPIDController.calculate(turningEncoder.getDistance(), velocity.angle.getRadians())
          + turnFeedforward.calculate(turningPIDController.getSetpoint().velocity);

  driveMotor.setVoltage(driveOutput);
  turningMotor.setVoltage(turnOutput);
}
```

with, in the constructor:

```java
turningPIDController.enableContinuousInput(-Math.PI, Math.PI);
```

The `optimize(...).cosineScale(...)` chain is now the blessed idiom — both are
instance methods returning new values:

```java
public SwerveModuleVelocity optimize(Rotation2d currentAngle) {
  var delta = angle.minus(currentAngle);
  if (Math.abs(delta.getDegrees()) > 90.0) {
    return new SwerveModuleVelocity(-velocity, angle.rotateBy(Rotation2d.PI));
  } else {
    return new SwerveModuleVelocity(velocity, angle);
  }
}

public SwerveModuleVelocity cosineScale(Rotation2d currentAngle) {
  return new SwerveModuleVelocity(velocity * angle.minus(currentAngle).getCos(), angle);
}
```

> **Suspected upstream bug in the Java example.** The drive PID uses the
> **post-optimize** `velocity.velocity`, but the drive feedforward uses the
> **pre-optimize** `desiredVelocity.velocity`. When `optimize()` flips the module
> (>90° error), those two terms have opposite signs and fight each other. The
> C++ port at `wpilibcExamples/.../SwerveBot/cpp/SwerveModule.cpp` uses
> `velocity.velocity` for **both**. **Use the optimized value for both terms in
> our code.** [V — the discrepancy is verified; that it is a bug is inferred]

### 4.2 Commands v3 flow (`rebuiltcmdv3` — directly relevant to us)

`~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`.
This is the only Commands v3 swerve in the tree. Its `SwerveDrive` is a
`org.wpilib.command3.Mechanism` and returns `Command` factories: [V]

```java
public Command driveFieldRelative(Supplier<ChassisVelocities> velocities) {
  return runRepeatedly(
          () -> {
            ChassisVelocities velocity = velocities.get().toRobotRelative(getGyroHeading());
            SwerveModuleVelocity[] swerveModuleVelocities =
                kinematics.toSwerveModuleVelocities(velocity);

            frontLeft.setTargetVelocity(swerveModuleVelocities[0]);
            frontRight.setTargetVelocity(swerveModuleVelocities[1]);
            rearLeft.setTargetVelocity(swerveModuleVelocities[2]);
            rearRight.setTargetVelocity(swerveModuleVelocities[3]);
          })
      .named("Drive.DriveSpeeds");
}

public Command driverControl(CommandGamepad controller) {
  return driveFieldRelative(
      () -> {
        double x = -controller.getLeftY();
        double y = -controller.getLeftX();
        double omega = -controller.getRightX();
        return new ChassisVelocities(
            DriveConstants.MAX_VELOCITY.times(x),      // LinearVelocity
            DriveConstants.MAX_VELOCITY.times(y),
            DriveConstants.MAX_TURN_RATE.times(omega)); // AngularVelocity
      });
}
```

**Do not copy this example verbatim.** It is a Commands-v3 teaching artefact,
not a reference swerve. It: [V]

- does **no** `discretize` (there is no fixed period in a `runRepeatedly`),
- does **no** `desaturateWheelVelocities`,
- does **no** `optimize` / `cosineScale` (delegates all closed loop to a
  hypothetical smart motor controller),
- does **no** deadband or stick shaping,
- declares `KINEMATICS = new SwerveDriveKinematics()` with zero modules, which
  **throws `IllegalArgumentException("A swerve drive requires at least two
  modules")`** at class-init if you actually run it,
- contains a latent `ClassCastException`: `getSwerveData` does
  `(T[]) new Object[4]` and returns it where a `SwerveModulePosition[]` is
  expected.

What it *is* good for: showing the Commands v3 idioms —
`Mechanism`, `runRepeatedly(...)`, `.named(...)`, `.until(...)`,
`coroutine.fork(...)`, `coroutine.waitUntil(...)`, `setDefaultCommand`,
`Scheduler.getDefault().run()`, `@Logged`. [V]

Also of note: it demonstrates the **unit-typed `ChassisVelocities`
constructor**, which is the cleanest way to do stick scaling:
`MAX_VELOCITY.times(x)` where `MAX_VELOCITY` is a `LinearVelocity`. [V]

### 4.3 `SwerveControllerCommand` is gone

Zero hits for `SwerveControllerCommand` or `swervecontrollercommand` anywhere in
the checkout, in any language. There is also **no `HolonomicDriveController`**
in `org.wpilib.math.controller`. [V] Trajectory following is demonstrated only
via a `PathFollower` *stub* in `rebuiltcmdv3`. See §5 and §9.

---

## 5. Trajectories: WPILib now ships the Choreo sample format

This is a significant, unlisted find and it bears directly on the locked
"Choreo for autonomous" decision.

`~/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/trajectory/` now contains
`HolonomicSample`, `HolonomicTrajectory`, `DifferentialSample`,
`DifferentialTrajectory`, `DrivetrainSplineTrajectory`, `TrajectorySample`, and
a generic `Trajectory<SampleType>`. [V]

```java
@Json
public class HolonomicSample extends TrajectorySample
    implements StructSerializable, ProtobufSerializable {
  @Json.Property("pose")         public Pose2d pose;
  @Json.Property("velocity")     public ChassisVelocities velocity;
  @Json.Property("acceleration") public ChassisAccelerations acceleration;
```

`HolonomicTrajectory` loads straight from JSON [V]:

```java
public static HolonomicTrajectory loadFromStream(InputStream stream) throws IOException {
  return Jsonb.instance().type(HolonomicTrajectory.class).fromJson(stream);
}
public static HolonomicTrajectory loadFromFile(File file) throws IOException
public static HolonomicTrajectory loadFromFile(String filename) throws IOException
```

and `Trajectory` gives you `sampleAt(double time)` / `sampleAt(Time time)`,
`start()`, `end()`, `duration`, `transformBy`, `concatenate`, `relativeTo`. [V]

`HolonomicSample.kinematicInterpolate` integrates with constant-acceleration
kinematics [V]:

```java
// vₖ₊₁ = vₖ + aₖΔt
// xₖ₊₁ = xₖ + vₖΔt + ½aₖ(Δt)²
```

**This is exactly the pose+velocity+acceleration triple Choreo emits.** [I]
The practical consequence: `ChassisAccelerations` is not primarily a control
input — **it is the acceleration channel of a trajectory sample**, and
`kinematicInterpolate` is where it earns its keep. That resolves "what does
`ChassisAccelerations` enable": trajectory samples that can be interpolated
correctly between waypoints, and a per-sample acceleration you can feed into
feedforward. [I]

**Open question:** whether the on-disk JSON schema of `HolonomicTrajectory`
matches Choreo's `.traj` format byte-for-byte, or whether a shim is needed. Not
determined — see §9.

---

## 6. Odometry and pose estimation

### 6.1 `SwerveDriveOdometry`

```java
public class SwerveDriveOdometry extends Odometry<SwerveModulePosition[]>

public SwerveDriveOdometry(SwerveDriveKinematics kinematics, Rotation2d gyroAngle,
                           SwerveModulePosition[] modulePositions, Pose2d initialPose)
public SwerveDriveOdometry(SwerveDriveKinematics kinematics, Rotation2d gyroAngle,
                           SwerveModulePosition[] modulePositions)   // -> Pose2d.ZERO
```

Inherited from `Odometry<T>`: `update(Rotation2d, T)`, `resetPosition(Rotation2d,
T, Pose2d)`, `resetPose(Pose2d)`, `resetTranslation(Translation2d)`,
`resetRotation(Rotation2d)`, `getPose()`. [V]

**Change from 2025:** there is no `m_gyroOffset` field. `Odometry` tracks
`m_previousGyroAngle` and integrates the delta, so "reset heading" is
`resetRotation(...)`, not an offset you maintain yourself. [V]

### 6.2 `SwerveDrivePoseEstimator`

```java
public class SwerveDrivePoseEstimator extends PoseEstimator<SwerveModulePosition[]>

public SwerveDrivePoseEstimator(SwerveDriveKinematics kinematics, Rotation2d gyroAngle,
                                SwerveModulePosition[] modulePositions, Pose2d initialPose)
// defaults: stateStdDevs (0.1, 0.1, 0.1), visionStdDevs (0.9, 0.9, 0.9)

public SwerveDrivePoseEstimator(SwerveDriveKinematics kinematics, Rotation2d gyroAngle,
                                SwerveModulePosition[] modulePositions, Pose2d initialPose,
                                Matrix<N3, N1> stateStdDevs, Matrix<N3, N1> visionMeasurementStdDevs)
```

`PoseEstimator<T>` surface: [V]

```java
void            setVisionMeasurementStdDevs(Matrix<N3, N1>)
void            resetPosition(Rotation2d gyroAngle, T wheelPositions, Pose2d pose)
void            resetPose(Pose2d) / resetTranslation(Translation2d) / resetRotation(Rotation2d)
Pose2d          getEstimatedPosition()
Optional<Pose2d> sampleAt(double timestamp)
void            addVisionMeasurement(Pose2d visionRobotPose, double timestamp)
void            addVisionMeasurement(Pose2d, double timestamp, Matrix<N3, N1> visionStdDevs)
Pose2d          update(Rotation2d gyroAngle, T wheelPositions)
Pose2d          updateWithTime(double currentTime, Rotation2d gyroAngle, T wheelPositions)
```

Notes: [V]

- Buffer duration is a hardcoded `private static final double BUFFER_DURATION = 1.5`
  seconds. Vision measurements older than that are dropped silently.
- Timestamps must share an epoch with **`org.wpilib.system.Timer.getMonotonicTimestamp()`**
  (the class moved from `edu.wpi.first.wpilibj.Timer`).
- The javadoc recommends "only adding vision measurements that are already
  within one meter or so of the current pose estimate."

`SwerveDrivePoseEstimator3d` and `SwerveDriveOdometry3d` also exist for
`Pose3d`/`Rotation3d` state. [V]

### 6.3 `Rotation3d` interpolation: lerp → slerp

```java
@Override
public Rotation3d interpolate(Rotation3d endValue, double t) {
  // https://en.wikipedia.org/wiki/Slerp#Quaternion_Slerp
  // slerp(q₀, q₁, t) = (q₁q₀⁻¹)ᵗq₀
  var q0 = m_q;
  var q1 = endValue.m_q;
  var delta = q1.times(q0.inverse());
  if (delta.getW() < 0.0) {
    delta = new Quaternion(-delta.getW(), -delta.getX(), -delta.getY(), -delta.getZ());
  }
  return new Rotation3d(delta.pow(t).times(q0));
}
```

Also `Rotation3d.times(scalar)` is now defined as
`Rotation3d.ZERO.interpolate(this, scalar)`, i.e. slerp-based. [V]

**Where it matters for us:** [I] only in 3D paths — `Pose3d` interpolation,
`SwerveDrivePoseEstimator3d`'s odometry buffer, and AprilTag pose
interpolation/latency compensation. Slerp takes the shortest arc and preserves
unit norm, so it is strictly better than the old component-wise lerp, which
could produce non-unit quaternions and non-constant angular rates. If we stay on
the 2D `SwerveDrivePoseEstimator` (recommended), this change is invisible to us.

---

## 7. Driver input shaping: `MathUtil` 2D variants

`org.wpilib.math.util.MathUtil`. Two breaking renames and one deletion: [V]

- `MathUtil.clamp` — **DELETED**. Use `Math.clamp` (Java 21+) or
  `Math.min`/`Math.max`.
- `MathUtil.copySignPow` → **`copyDirectionPow`**.
- `MathUtil.interpolate` → **`lerp`**; `inverseInterpolate` → **`inverseLerp`**.

The 2D variants operate on **`Vector<R>`** (`org.wpilib.math.linalg.Vector`),
**not** `Translation2d`, and — critically — they are **circular
(magnitude-based), not per-axis**: [V]

```java
public static <R extends Num> Vector<R> applyDeadband(
    Vector<R> value, double deadband, double maxMagnitude) {
  if (value.norm() < 1e-9) {
    return value.times(0);
  }
  return value.unit().times(applyDeadband(value.norm(), deadband, maxMagnitude));
}

public static <R extends Num> Vector<R> copyDirectionPow(
    Vector<R> value, double exponent, double maxMagnitude) {
  if (value.norm() < 1e-9) {
    return value.times(0);
  }
  return value.unit().times(copyDirectionPow(value.norm(), exponent, maxMagnitude));
}
```

Both take the norm, transform the scalar norm, and re-apply along the unit
direction. **Direction is exactly preserved.**

**Are these the right tool for driver input shaping? Yes, for the translation
stick — and they fix a real bug.** [I] Deadbanding X and Y independently
produces a *square* dead zone and distorts diagonal stick direction near the
deadband boundary; squaring X and Y independently distorts diagonals everywhere
(a full-diagonal push becomes 0.5/0.5 instead of 0.707/0.707 in magnitude terms,
but the direction survives — whereas a partial diagonal does not). The circular
versions give a **disc** dead zone and a magnitude-only curve, which is what a
holonomic drivetrain wants.

Recommended usage: [I]

```java
var stick = VecBuilder.fill(-controller.getLeftY(), -controller.getLeftX());
stick = MathUtil.applyDeadband(stick, 0.05);      // circular disc deadband
stick = MathUtil.copyDirectionPow(stick, 2.0);    // magnitude-only squaring
double vx = stick.get(0) * MAX_VELOCITY;
double vy = stick.get(1) * MAX_VELOCITY;
```

Use the **scalar** overloads for the rotation stick (it is one-dimensional).

The scalar deadband rescales rather than clipping, so output is continuous: [V]

```java
public static double applyDeadband(double value, double deadband, double maxMagnitude) {
  if (Math.abs(value) < deadband) { return 0; }
  if (value > 0.0) {
    return (1 + deadband / (maxMagnitude - deadband)) * (value - deadband);
  } else {
    return (1 + deadband / (maxMagnitude - deadband)) * (value + deadband);
  }
}
```

Full remaining `MathUtil` surface: `lerp`, `inverseLerp`, `applyDeadband` ×4,
`copyDirectionPow` ×4, `inputModulus`, `angleModulus`, `isNear` ×2,
`slewRateLimit(Translation2d, ...)`, `slewRateLimit(Translation3d, ...)`. [V]

---

## 8. Simulation, units, and telemetry

### 8.1 There is no swerve sim in WPILib

A tree-wide search for `SwerveSim|SwerveModuleSim|SwerveDriveSim|SwerveDrivetrainSim`
returns **zero hits** in Java, C++, or Python. The only drivetrain-level sim is
`DifferentialDrivetrainSim`. **We must compose swerve sim ourselves** from one
`DCMotorSim` per drive motor and one per steer motor, feeding
`SwerveDriveKinematics` for the chassis integration. [V]

`~/dev/allwpilib/simulation/` is the native HAL-sim extension layer
(`halsim_gui`, `halsim_ws_server`, …), not a Java sim library. [V]

### 8.2 `DCMotorSim` — one constructor, plant built via `Models`

`org.wpilib.simulation.DCMotorSim` (note: **not** `org.wpilib.wpilibj.simulation`). [V]

```java
public class DCMotorSim extends LinearSystemSim<N2, N1, N2>

public DCMotorSim(LinearSystem<N2, N1, N2> plant, DCMotor gearbox, double... measurementStdDevs)
```

The 2025 convenience overload `(DCMotor, J, gearing)` is **gone**; gearing and J
are back-solved from the plant matrices. [V] Build the plant with
`org.wpilib.math.system.Models` (ex-`LinearSystemId`): [V]

```java
static LinearSystem<N2, N1, N2> singleJointedArmFromPhysicalConstants(DCMotor motor, double J, double gearing)
static LinearSystem<N2, N1, N2> singleJointedArmFromSysId(double kV, double kA)
static LinearSystem<N1, N1, N1> flywheelFromPhysicalConstants(DCMotor motor, double J, double gearing)
static LinearSystem<N1, N1, N1> flywheelFromSysId(double kV, double kA)
static LinearSystem<N2, N1, N2> elevatorFromPhysicalConstants(...)
static LinearSystem<N2, N1, N2> elevatorFromSysId(double kV, double kA)
static LinearSystem<N2, N2, N2> differentialDriveFromPhysicalConstants(...)
static LinearSystem<N2, N2, N2> differentialDriveFromSysId(...)
```

**There is no `dcMotorSystem` / `createDCMotorSystem`.** For a position-tracking
motor (steer), use `singleJointedArmFromPhysicalConstants` — its matrices have
no gravity term; gravity lives in `SingleJointedArmSim`, not the plant. For a
velocity-only motor (drive), `flywheelFromPhysicalConstants` + `FlywheelSim` is
lighter. [V]

`DCMotorSim` API (all plain doubles, radians at the **output** shaft): [V]

```java
void    setState(double angularPosition, double angularVelocity)
void    setAngle(double) / setAngularVelocity(double)
double  getGearing() / getJ()
DCMotor getGearbox()
double  getAngularPosition()      // rad
double  getAngularVelocity()      // rad/s
double  getAngularAcceleration()  // rad/s²
double  getTorque() / getCurrentDraw() / getInputVoltage()
void    setInputVoltage(double volts)   // clamps to RobotController.getBatteryVoltage()
void    update(double dt)               // inherited from LinearSystemSim
```

### 8.3 REV motor factories — watch the casing

`org.wpilib.math.system.DCMotor` (the `system.plant` package no longer exists). [V]

```java
public static DCMotor getNEO(int numMotors)        // note: ALL CAPS
public static DCMotor getNeo550(int numMotors)     // note: mixed case
public static DCMotor getNeoVortex(int numMotors)  // note: mixed case
```

Values: [V]

```java
getNEO:       new DCMotor(12, 2.6,  105, 1.8, rpmToRadPerSec(5676),  numMotors)
getNeo550:    new DCMotor(12, 0.97, 100, 1.4, rpmToRadPerSec(11000), numMotors)
getNeoVortex: new DCMotor(12, 3.60, 211, 3.6, rpmToRadPerSec(6784),  numMotors)
```

Also `withReduction(double)`, `getCurrent(v, V)`, `getTorque(i)`,
`getVoltage(τ, ω)`, `getVelocity(τ, V)`. [V]

### 8.4 wpiunits: constructor-boundary only

**Every field, every getter, and every return value in `kinematics/` is a raw
`double`.** Units appear *only* as alternate constructors and as a handful of
`getMeasure*()` accessors on geometry types. [V]

Units-based constructors that exist: [V]

```java
ChassisVelocities(LinearVelocity vx, LinearVelocity vy, AngularVelocity omega)
ChassisAccelerations(LinearAcceleration ax, LinearAcceleration ay, AngularAcceleration alpha)
SwerveModuleVelocity(LinearVelocity velocity, Rotation2d angle)
SwerveModulePosition(Distance distance, Rotation2d angle)
SwerveModuleAcceleration(LinearAcceleration acceleration, Rotation2d angle)
Rotation2d(Angle angle)
SwerveDriveKinematics.desaturateWheelVelocities(vels[], LinearVelocity max)
SwerveDriveKinematics.desaturateWheelVelocities(vels[], ChassisVelocities,
    LinearVelocity, LinearVelocity, AngularVelocity)
```

`SwerveDriveKinematics(Translation2d...)` has **no** units overload — module
locations are bare meters. [V]

Measure-returning getters on geometry: `Rotation2d.getMeasure()`,
`Translation2d.getMeasureX/Y()`, `Pose2d.getMeasureX/Y()`,
`Rotation3d.getMeasureX/Y/Z()`, `Rotation3d.getMeasureAngle()`. [V]

Conventions, documented on the fields themselves: **meters, m/s, m/s², radians,
rad/s, rad/s², seconds; +x forward, +y left, +ω counter-clockwise.** [V]

### 8.5 Struct serialization and the new `telemetry` module

Struct schemas relevant to swerve: [V]

| Type | `getTypeName()` | `getSchema()` |
|---|---|---|
| `SwerveModuleVelocity` | `"SwerveModuleVelocity"` | `"double velocity;Rotation2d angle"` |
| `SwerveModulePosition` | `"SwerveModulePosition"` | `"double distance;Rotation2d angle"` |
| `SwerveModuleAcceleration` | **`"SwerveModuleAccelerations"`** (plural — upstream typo) | `"double acceleration;Rotation2d angle"` |
| `ChassisVelocities` | `"ChassisVelocities"` | `"double vx;double vy;double omega"` |
| `ChassisAccelerations` | `"ChassisAccelerations"` | `"double ax;double ay;double alpha"` |
| `Pose2d` | `"Pose2d"` | `"Translation2d translation;Rotation2d rotation"` |
| `Rotation2d` | `"Rotation2d"` | `"double value"` |
| `Translation2d` | `"Translation2d"` | `"double x;double y"` |
| `Twist2d` | `"Twist2d"` | `"double dx;double dy;double dtheta"` |

`SwerveDriveKinematics` is the one exception: no `struct` **field**, because the
schema depends on module count. Use
`SwerveDriveKinematics.getStruct(int numModules)`, whose type name is
`"SwerveDriveKinematics__" + n` and schema `"Translation2d modules[n]"`. [V]

**There is a new top-level `telemetry` module** at
`~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/`. Epilogue's old
`EpilogueBackend` / `NTEpilogueBackend` / `FileBackend` / `DataLogger` classes
are **gone**; `EpilogueConfiguration.backend` is now
`EpilogueConfiguration.table` (a `TelemetryTable`). [V]

For our explicit-logging, no-replay strategy, the static `Telemetry` facade is
exactly the right shape — no publisher objects to hold: [V]

```java
Telemetry.log("Drive/ModuleVelocities", moduleVelocities);  // SwerveModuleVelocity[] -> struct array
Telemetry.log("Drive/ModuleSetpoints",  setpoints);
Telemetry.log("Drive/ChassisVelocities", chassisVelocities);
Telemetry.log("Drive/Pose", poseEstimator.getEstimatedPosition());
```

`TelemetryTable.log(String, T)` pattern-matches on `StructSerializable`,
reflects the type's `struct` field, and calls `entry.logStruct(...)` /
`entry.logStructArray(...)`. Backends: `NetworkTablesTelemetryBackend`,
`DataLogTelemetryBackend`, combinable via `MultiTelemetryBackend`. The old
`StructArrayPublisher` / `StructArrayLogEntry` APIs still exist if we want
manual control. [V]

---

## 9. Key takeaways for this project

1. **Rename everything before writing a line.** `ChassisSpeeds` →
   `ChassisVelocities`, `SwerveModuleState` → `SwerveModuleVelocity`,
   `edu.wpi.first` → `org.wpilib`. Every LLM completion and every internet
   snippet will be wrong. Consider a project glossary / CLAUDE.md note so agents
   working in this repo don't reintroduce 2025 names.

2. **Adopt a strict value-type discipline.** Never write to a `.vx` / `.velocity`
   / `.distance` field. Always reassign the result. `@NoDiscard` catches most of
   it at compile time — do not suppress it.

3. **Standardise the drive pipeline** as:
   `deadband(Vector) → copyDirectionPow(Vector) → scale → ChassisVelocities →
   toRobotRelative(gyro) → discretize(period) → toSwerveModuleVelocities →
   desaturateWheelVelocities → per-module optimize().cosineScale()`.
   Wrap this in one method so it cannot be got wrong per-call-site.

4. **Use the circular `Vector<R>` deadband and `copyDirectionPow`** for the
   translation stick, and the scalar versions for rotation. This is strictly
   better than per-axis shaping and it's now first-class in the library.

5. **Fix the example's feedforward bug in our port:** feed the *optimized*
   velocity to both the drive PID and the drive feedforward.

6. **Beware `getRotations()` on REV hardware.** Either configure SPARK position
   wrapping to `-0.5 .. 0.5`, or normalise with
   `MathUtil.inputModulus(x, 0, 1)`. Pick one, write it down, apply it in exactly
   one place.

7. **Never use `Rotation2d.times()`/`div()` for angle scaling.** Do the
   arithmetic in doubles; construct the Rotation2d last.

8. **For continuous heading (cable-twist guard, spin counters), read the Pigeon2
   continuous yaw signal directly** — do not round-trip through `Rotation2d`.

9. **Feedforward: use `SimpleMotorFeedforward.calculate(current, next)`.** The
   acceleration-taking overload is gone. Sample the Choreo trajectory at `t` and
   `t + dt` and feed the velocity pair. Reserve
   `toSwerveModuleAccelerations(accels, omega)` for logging and analysis unless
   we specifically need the centripetal term — and if we do, remember to project
   the acceleration onto the module heading and to **never** call the
   zero-ω `toWheelAccelerations`.

10. **We must build our own swerve sim.** Budget for it: 8 × `DCMotorSim`
    (4 drive + 4 steer), `Models.flywheelFromPhysicalConstants` for drive and
    `Models.singleJointedArmFromPhysicalConstants` for steer,
    `DCMotor.getNeoVortex(1)` / `getNEO(1)`, plus a chassis integrator built on
    `SwerveDriveKinematics.toChassisVelocities`.

11. **Use `org.wpilib.telemetry.Telemetry.log(...)` for explicit logging.** It
    auto-detects `StructSerializable` and `StructSerializable[]`, so logging
    module states and poses is one line each with no publisher plumbing.

12. **`HolonomicTrajectory` / `HolonomicSample` in wpimath is Choreo-shaped.**
    Investigate before adding the ChoreoLib vendordep — WPILib may already give
    us loading, sampling, and interpolation for free.

---

## 10. Things that invalidate or complicate a locked decision

| Locked decision | Status | Detail |
|---|---|---|
| **WPILib built-in swerve** | ✅ Holds, with a gap | Kinematics/odometry/estimator are all there and improved. But there is **no swerve simulation** and **no `SwerveControllerCommand` / `HolonomicDriveController`** — we write both ourselves. |
| **Choreo for autonomous** | ⚠️ **Needs a decision** | WPILib now ships `HolonomicTrajectory` + `HolonomicSample` (pose + `ChassisVelocities` + `ChassisAccelerations`) with JSON loading and kinematic interpolation. This may replace ChoreoLib entirely, or may need a schema shim. **Unplanned decision: WPILib-native trajectory loading vs. ChoreoLib vendordep.** |
| **Commands v3** | ✅ Holds | `org.wpilib.command3` is real (`Mechanism`, `Coroutine`, `Scheduler`, `Trigger`, `StateMachine`, `CommandGamepad`). But the only Commands v3 swerve example is a teaching stub that omits discretize/desaturate/optimize and would throw at class-init. **We are writing the first real one.** |
| **TimedRobot** | ⚠️ Note | The Commands v3 example uses `org.wpilib.framework.OpModeRobot` with `@Teleop`/`@Autonomous` op-modes, not `TimedRobot`. TimedRobot still exists at `org.wpilib.framework.TimedRobot` and the two v2-style swerve examples use it. **Unplanned decision: `TimedRobot` + Commands v3 vs. `OpModeRobot`.** Worth confirming they compose. |
| **Explicit telemetry logging** | ✅ Holds, better than expected | New `org.wpilib.telemetry.Telemetry` static facade with struct auto-detection is exactly this shape. Note Epilogue's backend API changed (`.backend` → `.table`). |
| **REV NEO/Vortex on SPARK** | ⚠️ Watch | `DCMotor.getNEO` / `getNeo550` / `getNeoVortex` all present. But `Rotation2d.getRotations()` ∈ `[-0.5, 0.5]` vs. REV absolute encoder `[0, 1)` is a real mismatch that needs an explicit project convention. |
| **CTRE Pigeon2** | ✅ Holds | Nothing WPILib-side blocks it; `Odometry`/`PoseEstimator` want a `Rotation2d`, which Pigeon2 provides. Just don't use it for continuous accumulated yaw. |
| **Java 25** | ❓ Unverified | Did not check the toolchain config. Source uses Java 21 pattern-matching switches; nothing observed that requires or forbids 25. |

---

## 11. Open questions / unknowns

1. **Choreo ↔ `HolonomicTrajectory` schema compatibility.** Does a Choreo
   `.traj` file deserialise directly via
   `HolonomicTrajectory.loadFromFile(...)`? The JSON property names are
   `pose` / `velocity` / `acceleration` with nested `Pose2d` / `ChassisVelocities`
   / `ChassisAccelerations` — plausible but unverified. **This is the highest-value
   follow-up.**

2. **Is there a ChoreoLib 2027 at all yet?** Not checked. If WPILib absorbed the
   format, ChoreoLib may be thin or unnecessary.

3. **What replaces `SwerveControllerCommand` / `HolonomicDriveController`?**
   Nothing found in `org.wpilib.math.controller`. Is a holonomic path-following
   controller planned for a later alpha, or is it now considered team-owned code?
   (`LTVUnicycleController` and `LTVDifferentialDriveController` still exist for
   differential.) The new `AntiTipping` controller
   (`org.wpilib.math.controller.AntiTipping`, `calculate(Rotation3d attitude)` →
   `ChassisVelocities`) suggests WPILib is still adding holonomic control pieces.

4. **`TimedRobot` + Commands v3 composition.** The v3 example uses `OpModeRobot`.
   Does `Scheduler.getDefault().run()` in `TimedRobot.robotPeriodic()` work as
   expected, and do we lose anything (op-mode annotations, `OpModeFetcher`)?
   `design-docs/commands-v3.md` and `design-docs/opmodes.md` in the checkout
   should answer this — not yet read.

5. **Java 25 toolchain.** Does the 2027 GradleRIO / vendordep chain accept
   Java 25? Not checked.

6. **REV 2027 vendordep API.** Whether SPARK's position-wrapping config exposes a
   `-0.5 .. 0.5` range cleanly, and what its 2027 API looks like. Out of scope
   for this checkout.

7. **`SwerveDrivePoseEstimator` 1.5 s buffer.** Hardcoded, not configurable. If
   our vision latency budget ever exceeds that we have no knob. Unclear whether
   this is intentional or an alpha simplification.

8. **Is the `swervebot` feedforward sign discrepancy an upstream bug?** The
   Java/C++ divergence is verified; whether it is known upstream is not. Worth
   filing.

9. **`SwerveModuleAccelerationStruct.getTypeName()` returns the plural
   `"SwerveModuleAccelerations"`.** Looks like a typo; it will surface under that
   name in AdvantageScope/Glass. Not confirmed as intentional.

---

## Appendix: files read

All paths relative to `~/dev/allwpilib`.

**wpimath — kinematics**
`wpimath/src/main/java/org/wpilib/math/kinematics/{ChassisVelocities,ChassisAccelerations,SwerveDriveKinematics,SwerveModuleVelocity,SwerveModulePosition,SwerveModuleAcceleration,SwerveDriveOdometry,Odometry,Kinematics}.java`

**wpimath — geometry / estimator / controller / trajectory**
`wpimath/src/main/java/org/wpilib/math/geometry/{Rotation2d,Rotation3d,Pose3d,Twist2d,Transform2d}.java`
`wpimath/src/main/java/org/wpilib/math/estimator/{SwerveDrivePoseEstimator,PoseEstimator}.java`
`wpimath/src/main/java/org/wpilib/math/controller/{PIDController,SimpleMotorFeedforward,AntiTipping}.java`
`wpimath/src/main/java/org/wpilib/math/trajectory/{HolonomicSample,HolonomicTrajectory,Trajectory}.java`
`wpimath/src/main/java/org/wpilib/math/util/MathUtil.java`
`wpimath/src/main/java/org/wpilib/math/system/{DCMotor,Models}.java`
`wpimath/src/main/java/org/wpilib/math/kinematics/struct/*.java`

**wpimath — tests**
`wpimath/src/test/java/org/wpilib/math/kinematics/{SwerveModuleVelocityTest,SwerveDriveKinematicsTest}.java`

**wpilibj**
`wpilibj/src/main/java/org/wpilib/simulation/{DCMotorSim,LinearSystemSim,FlywheelSim,ElevatorSim,SingleJointedArmSim}.java`
`wpilibj/src/main/java/org/wpilib/hardware/imu/OnboardIMU.java`
`wpilibj/src/main/java/org/wpilib/backend/{NetworkTablesTelemetryBackend,DataLogTelemetryBackend}.java`

**telemetry / epilogue / annotations**
`telemetry/src/main/java/org/wpilib/telemetry/{Telemetry,TelemetryTable,TelemetryEntry}.java`
`epilogue-runtime/src/main/java/org/wpilib/epilogue/{EpilogueConfiguration,EpilogueTelemetry}.java`
`wpiannotations/src/main/java/org/wpilib/annotation/NoDiscard.java`

**examples**
`wpilibjExamples/src/main/java/org/wpilib/examples/swervebot/{Robot,Drivetrain,SwerveModule}.java`
`wpilibjExamples/src/main/java/org/wpilib/examples/swervedriveposeestimator/{Robot,Drivetrain,SwerveModule,ExampleGlobalMeasurementSensor}.java`
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/{PoseEstimator,constants/DriveConstants,mechanisms/SwerveDrive,mechanisms/SwerveModule}.java`
`wpilibcExamples/src/main/cpp/examples/SwerveBot/cpp/SwerveModule.cpp` (for the FF comparison)

**Executed probes** against `wpimath/build/libs/wpimath.jar` for all `[E]` results.
