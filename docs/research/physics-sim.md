# Physics Simulation for 2027: maple-sim vs. Rolling Our Own

**Issue:** [Drew-Robotics/2027beta#8](https://github.com/Drew-Robotics/2027beta/issues/8)
**Date:** 2026-08-24
**Status:** Research complete — recommendation below.

---

## Bottom line

Build our own. Specifically: **keep the vendor-sim-at-the-hardware-boundary baseline, and add a
~300-line custom chassis-dynamics layer** that converts per-module wheel forces into rigid-body
motion of the robot on the field.

The measured cost of that layer is **1.6 µs per 20 ms robot period** on a desktop CPU, and it is
roughly 300 lines of code we fully control. maple-sim would give us modestly better fidelity (a real
constraint solver, collisions with walls and other robots) at the cost of a vendordep that, as of
today, **has no 2027 release, no 2027 branch, no entry in WPILib's official SystemCore vendor
compatibility matrix, and a two-month-old unreviewed draft PR as its only 2027 work** — plus an API
shape that reopens the IO-layer decision we already closed when we rejected AdvantageKit.

---

## 1. What WPILib 2027 gives us for free

Checked against the local build at `~/dev/allwpilib`, version `v2027.0.0-alpha-6-366-gcafb0cc79` —
i.e. **366 commits past the newest tagged release** (`v2027.0.0-alpha-6`, published 2026-05-08).
Some of what follows is on unreleased 2027 `main` and is not yet in any published alpha. That
distinction matters and is called out where relevant.

### 1.1 Motor and plant models — `org.wpilib.math.system`

The `plant` sub-package is **gone**. `DCMotor` now lives at
`/home/drew/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/system/DCMotor.java`
(was `edu.wpi.first.math.system.plant.DCMotor`).

```java
public class DCMotor implements ProtobufSerializable, StructSerializable {
  public final double nominalVoltage, stallTorque, stallCurrent, freeCurrent, freeSpeed, R, Kv, Kt;

  public DCMotor(double nominalVoltage, double stallTorque, double stallCurrent,
                 double freeCurrent, double freeSpeed, int numMotors);

  public double getCurrent(double velocity, double voltageInput);
  public double getCurrent(double torque);
  public double getTorque(double current);
  public double getVoltage(double torque, double velocity);
  public double getVelocity(double torque, double voltageInput);
  public DCMotor withReduction(double gearboxReduction);

  // static factories relevant to us:
  public static DCMotor getNEO(int numMotors);        // 12 V, 2.6 N·m stall, 105 A stall, 5676 RPM free
  public static DCMotor getNeoVortex(int numMotors);  // 12 V, 3.60 N·m stall, 211 A stall, 6784 RPM free
  public static DCMotor getNeo550(int numMotors);
  // plus CIM, MiniCIM, Falcon500(+Foc), KrakenX60(+Foc), KrakenX44(+Foc), Minion, ...
}
```

**Everything the DIY chassis model needs at the motor level is here** — `Kt`, `Kv`, `R`, and the
torque/current/voltage relations. This is the same data maple-sim consumes.

### 1.2 Linear system factories — `Models` (renamed from `LinearSystemId`)

`LinearSystemId` **no longer exists** in the 2027 tree. It is now
`/home/drew/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/system/Models.java` (370 lines) with
renamed methods:

```java
public static LinearSystem<N1, N1, N1> flywheelFromPhysicalConstants(DCMotor motor, double J, double gearing);
public static LinearSystem<N1, N1, N1> flywheelFromSysId(double kV, double kA);
public static LinearSystem<N2, N1, N2> elevatorFromPhysicalConstants(...);
public static LinearSystem<N2, N1, N2> elevatorFromSysId(double kV, double kA);
public static LinearSystem<N2, N1, N2> singleJointedArmFromPhysicalConstants(DCMotor motor, double J, double gearing);
public static LinearSystem<N2, N1, N2> singleJointedArmFromSysId(double kV, double kA);
public static LinearSystem<N2, N2, N2> differentialDriveFromPhysicalConstants(
        DCMotor motor, double mass, double r, double rb, double J, double gearing);
public static LinearSystem<N2, N2, N2> differentialDriveFromSysId(...);  // 2 overloads
```

> **Gotcha worth writing down now:** there is **no `createDCMotorSystem` / `dcMotorSystem`
> equivalent** in `Models`. `DCMotorSim`'s own Javadoc now points you at
> `Models.singleJointedArmFromPhysicalConstants(DCMotor, double J, double gearing)` instead. That
> factory is gravity-free (gravity is added by `SingleJointedArmSim`, not the plant), so it is a
> correct drop-in for a plain geared DC motor. Confirmed by reading `Models.java:161-183` — the A
> matrix is `[[0,1],[0, -G²Kt/(Kv·R·J)]]`, exactly the DC-motor position model.

### 1.3 Simulation classes — `org.wpilib.simulation`

Package moved from `edu.wpi.first.wpilibj.simulation` to `org.wpilib.simulation`
(`/home/drew/dev/allwpilib/wpilibj/src/main/java/org/wpilib/simulation/`). The full list:

`ADXL345Sim, AddressableLEDSim, AlertSim, AnalogEncoderSim, AnalogInputSim, BatterySim,
CTREPCMSim, CallbackStore, DCMotorSim, DIOSim, DifferentialDrivetrainSim, DigitalPWMSim,
DoubleSolenoidSim, DriverStationSim, DutyCycleEncoderSim, DutyCycleSim, ElevatorSim, EncoderSim,
FlywheelSim, GamepadSim, GenericHIDSim, I2CSim, JoystickSim, LinearSystemSim, NotifierSim,
OnboardIMUSim, PDPSim, PWMMotorControllerSim, PWMSim, PneumaticsBaseSim, REVPHSim, RoboRioSim,
SharpIRSim, SimDeviceSim, SimHooks, SingleJointedArmSim, SolenoidSim`

Key signatures:

```java
// DCMotorSim.java (184 lines)
public class DCMotorSim extends LinearSystemSim<N2, N1, N2> {
  public DCMotorSim(LinearSystem<N2, N1, N2> plant, DCMotor gearbox, double... measurementStdDevs);
  public void setState(double angularPosition, double angularVelocity);
  public double getGearing(); public double getJ(); public DCMotor getGearbox();
  public double getAngularPosition();     // rad
  public double getAngularVelocity();     // rad/s
  public double getAngularAcceleration(); // rad/s²
  public double getTorque(); public double getCurrentDraw();
  public double getInputVoltage(); public void setInputVoltage(double volts);
}

// LinearSystemSim.java (197 lines) — the base class; integration lives here
public class LinearSystemSim<States extends Num, Inputs extends Num, Outputs extends Num> {
  public LinearSystemSim(LinearSystem<States, Inputs, Outputs> system, double... measurementStdDevs);
  public void update(double dt);
  public Matrix<Outputs, N1> getOutput(); public double getOutput(int row);
  public void setInput(Matrix<Inputs, N1> u); public void setInput(int row, double value);
  public void setInput(double... u);
  public void setState(Matrix<States, N1> state);
}

// FlywheelSim.java (155 lines) — same shape, N1 state
public FlywheelSim(LinearSystem<N1, N1, N1> plant, DCMotor gearbox, double... measurementStdDevs);

// BatterySim.java
public static double calculateLoadedBatteryVoltage(double nominalVoltage, double resistance, double... currents);
public static double calculateDefaultBatteryLoadedVoltage(double... currents);

// RoboRioSim.java — still named RoboRioSim in 2027 (not renamed for SystemCore)
public static void setVInVoltage(double vInVoltage);
public static double getVInVoltage();
```

`DifferentialDrivetrainSim` (497 lines) is the **only** chassis-level sim WPILib ships. It models
mass, moment of inertia, trackwidth and wheel radius, and integrates a 7-state pose:

```java
public DifferentialDrivetrainSim(DCMotor driveMotor, double gearing, double j, double mass,
                                 double wheelRadius, double trackwidth,
                                 Matrix<N7, N1> measurementStdDevs);
public void setInputs(double leftVoltageVolts, double rightVoltageVolts);
public void update(double dt);
public Pose2d getPose(); public Rotation2d getHeading();
```

It is a **proof by existence that WPILib is comfortable shipping this kind of thing** — it just
never got a swerve counterpart.

### 1.4 New in 2027 that helps us

`OnboardIMU` — SystemCore has a built-in IMU, and WPILib ships a first-party sim hook for it:

```java
// org.wpilib.hardware.imu.OnboardIMU
public OnboardIMU(MountOrientation mountOrientation);
public double getYawRadians(); public void resetYaw();
public Rotation2d getRotation2d(); public Rotation3d getRotation3d(); public Quaternion getQuaternion();
public double getGyroRateZ(); public double getAccelX(); /* ... */

// org.wpilib.simulation.OnboardIMUSim — all static
public static void setYaw(double angleRad);
public static void setAngleZ(double angleRad);
public static void setGyroRateZ(double rateRadPerSec);
public static void setAccelX(double accelMpss); /* ... */
```

### 1.5 Kinematics API renames (2027 alpha 5+)

These are necessary for anything that touches swerve, ours or a vendor's:

| 2026 | 2027 |
|---|---|
| `ChassisSpeeds` | `ChassisVelocities` |
| `SwerveModuleState` | `SwerveModuleVelocity` |
| `kinematics.toChassisSpeeds(...)` | `kinematics.toChassisVelocities(...)` |
| `kinematics.toSwerveModuleStates(...)` | `kinematics.toSwerveModuleVelocities(...)` |
| `SwerveDriveKinematics.desaturateWheelSpeeds(...)` | `SwerveDriveKinematics.desaturateWheelVelocities(...)` |
| `edu.wpi.first.*` | `org.wpilib.*` |
| `math.system.plant.DCMotor` | `math.system.DCMotor` |
| `LinearSystemId` | `Models` (all methods renamed) |
| `wpilibj.simulation.*` | `org.wpilib.simulation.*` |

`ChassisVelocities` keeps `discretize(double dt)`, `toRobotRelative(Rotation2d)`,
`toFieldRelative(Rotation2d)`, `toTwist2d(double dt)`. New in alpha 5: `ChassisAccelerations`,
`SwerveModuleAcceleration`, and forward/inverse acceleration kinematics on `SwerveDriveKinematics`.

The WPILib 2027 changelog lists these under Breaking Changes ("Replace Speeds with Velocities in
method signatures where appropriate", "Make swerve and differential kinematics functions
immutable", "Reorganize java packages from `edu.wpi.first` to `org.wpilib`"). The
`LinearSystemId` → `Models` move and the `plant` package flattening are **not in the alpha-6
changelog** — they landed on `main` afterward, which is why the local checkout has them.

---

## 2. What WPILib 2027 does **not** give us

Verified by exhaustive grep across the whole `allwpilib` tree (Java and C++):

- **No swerve simulation of any kind.** `grep -ri "SwerveModuleSim|SwerveDriveSim|SwerveSim"` across
  the entire repo returns **zero hits**. There is no `SwerveModuleSim`, no `SwerveDriveSim`, no
  swerve equivalent of `DifferentialDrivetrainSim`.
- **No swerve example has a `simulationPeriodic`.** The four swerve examples
  (`swervebot`, `swervedriveposeestimator`, `rebuiltcmdv3`) contain no simulation code at all.
- **No friction, slip, or traction model anywhere.** `grep -ri "friction|slip|traction"` across
  `wpimath` and `wpilibj` returns only unrelated hits (spline coefficients, a `LinearFilter`
  Javadoc mentioning wheel slip in prose).
- **No rigid-body dynamics, no collision detection, no field elements.**

So the exact gap in our baseline strategy is confirmed: WPILib will spin our simulated wheels
perfectly, and will never tell us where the robot is as a consequence.

---

## 3. maple-sim: what it actually is

Repository: [`Shenzhen-Robotics-Alliance/maple-sim`](https://github.com/Shenzhen-Robotics-Alliance/maple-sim)
Docs: <https://shenzhen-robotics-alliance.github.io/maple-sim/>
License: BSD-3-Clause. Created 2024-09-19. 115 stars. Last push to `main`: **2026-07-08**.

### 3.1 Size and structure

Total Java source: **8,717 lines** across `org.ironmaple.*`.

| Package | Files | Lines | Drive base needs it? |
|---|---:|---:|---|
| `simulation/drivesims` | 6 | 2,315 | **Yes** |
| `simulation/drivesims/configs` | 3 | 463 | **Yes** |
| `simulation/motorsims` | 5 | 739 | **Yes** |
| `simulation/SimulatedArena.java` | 1 | 755 | **Yes** (world + tick loop) |
| `utils` + `utils/mathutils` | 5 | 264 | Partly |
| `simulation/gamepieces` | 3 | 909 | No |
| `simulation/IntakeSimulation.java` | 1 | 422 | No |
| `simulation/Goal.java` | 1 | 457 | No |
| `simulation/seasonspecific/*` | 22 | 2,393 | No (except 76-line `ArenaEvergreen`) |

**Roughly 4,500 lines are drive-relevant; roughly 4,200 lines are game-piece, intake, scoring and
season-field code that a drive base never touches.** Any argument for maple-sim on the basis of
game-piece interaction, intake simulation, or scoring is irrelevant to issue #14's scope.

The drive-relevant core breaks down as:

```
169  AbstractDriveTrainSimulation.java     dyn4j Body wrapper, bumper fixture, pose accessors
449  COTS.java                             preset configs (see below)
212  GyroSimulation.java                   gyro with drift + impact response
580  SelfControlledSwerveDriveSimulation   convenience wrapper (kinematics, PID, odometry)
367  SwerveDriveSimulation.java            *** the actual chassis dynamics ***
538  SwerveModuleSimulation.java           per-module force generation
323  DriveTrainSimulationConfig.java       builder
128  SwerveModuleSimulationConfig.java     builder
739  motorsims/*                           MapleMotorSim, SimMotorConfigs, SimulatedBattery, SimulatedMotorController
```

### 3.2 Dependency stack

```json
"javaDependencies": [
  { "groupId": "org.ironmaple", "artifactId": "maplesim-java", "version": "${version}" },
  { "groupId": "org.dyn4j",     "artifactId": "dyn4j",         "version": "5.0.2" }
]
```

One external dependency: **[dyn4j](https://github.com/dyn4j/dyn4j) 5.0.2**, a pure-Java 2D
rigid-body engine, BSD-3, no JNI, no natives. dyn4j itself is healthy — 537 stars, **6.0.0 released
2026-07-18**, actively committed. maple-sim pins the older 5.0.2. dyn4j being pure Java is
significant: it runs identically on x86 laptops and on arm64 SystemCore.

maple-sim targets **Java 17**; WPILib 2027 compiles with `options.release = 25`. Java 17 bytecode
runs fine on 25, so this is a tooling nit rather than a blocker.

### 3.3 The actual physics (`SwerveDriveSimulation.simulationSubTick`)

This is the part worth understanding, because it is what we would be reimplementing. Per sub-tick:

1. **`simulateChassisFrictionForce()`** — computes the difference between the chassis's actual ground
   velocity and the velocity the modules are *trying* to produce, converts that to a force with a
   hand-tuned gain, adds a centripetal term, and clamps the total to `μ · m · g`:
   ```java
   final double FRICTION_FORCE_GAIN = 3.0,
       totalGrippingForce = config.getGrippingForceNewtons(gravityForceOnEachModule) * nModules;
   final Vector2 speedsDifferenceFrictionForce = Vector2.create(
       Math.min(FRICTION_FORCE_GAIN * totalGrippingForce * speedDiff.getNorm(), totalGrippingForce),
       angleOf(speedDiff));
   ```
2. **`simulateChassisFrictionTorque()`** — the rotational analogue, with `FRICTION_TORQUE_GAIN = 1`
   and a deadband that snaps `ω` to zero below 1% of max.
3. **`simulateModulePropellingForces()`** — for each module, ask `SwerveModuleSimulation` for a force
   vector and apply it at the module's world position.
4. **`gyroSimulation.updateSimulationSubTick(...)`**.

Per-module force (`SwerveModuleSimulation.getPropellingForce`, lines 192-247):

```java
final double driveWheelTorque = getDriveWheelTorque();          // Kt·I·G, with a friction deadband
double propellingForceNewtons = driveWheelTorque / WHEEL_RADIUS;
final boolean skidding = Math.abs(propellingForceNewtons) > grippingForceNewtons;
if (skidding) propellingForceNewtons = Math.copySign(grippingForceNewtons, propellingForceNewtons);
// if gripped, the wheel is driven by the floor; if skidding, blend 50/50 toward motor equilibrium
```

Chassis body also carries `setLinearDamping(1.4)` and `setAngularDamping(1.4)` — flat empirical
damping, not derived from anything.

**Honest read: this is a good, pragmatic, heuristic model, not a rigorous tire model.** The
`FRICTION_FORCE_GAIN = 3.0`, the damping constants, and the 50/50 skid blend are tuned numbers. That
is not a criticism of maple-sim — it is the right engineering call for the problem — but it does
mean the fidelity bar we would have to clear to write our own is **much lower than "implement Pacejka
tire dynamics."**

### 3.4 Tick rate

`SimulatedArena` runs **5 sub-ticks per 20 ms robot period** (dt = 4 ms), configurable:

```java
private static int SIMULATION_SUB_TICKS_IN_1_PERIOD = 5;
private static Time SIMULATION_DT = Seconds.of(TimedRobot.kDefaultPeriod / SIMULATION_SUB_TICKS_IN_1_PERIOD);
public static synchronized void overrideSimulationTimings(Time robotPeriod, int simulationSubTicksPerPeriod);
```

and it self-reports cost to `SmartDashboard` under `MapleArenaSimulation/Dyn4jEngineCPUTimeMS`.

### 3.5 Presets that match our hardware

`COTS.java` ships `ofMAXSwerve(...)`, `ofMark4/4i/4n/5i/5n`, `ofSwerveX/XS/X2/X2S`, `ofThriftySwerve`,
plus `ofPigeon2()`, `ofNav2X()`, `ofGenericGyro()`. **`ofMAXSwerve()` and `ofPigeon2()` are a direct
match for our stack** — that is a genuine, concrete point in maple-sim's favour.

---

## 4. maple-sim: 2027 / SystemCore status and rollover history

This is the crux of the question, so all of it is sourced from the repository and GitHub API rather
than from forum hearsay.

### 4.1 Release history

| Tag | Published | Notes |
|---|---|---|
| `v0.4.0-beta` | **2026-01-17** | "2026 Rebuilt Game Release (Beta)" — **prerelease** |
| `v0.3.14` | 2025-08-20 | last stable |
| `v0.3.8` | 2025-02-05 | |
| `v0.3.3` | 2025-01-21 | "Reef Simulation!" |
| `v0.3.0` | 2025-01-07 | "Kickoff Release!" |
| `v0.2.8` | 2025-01-03 | "Pre-Kickoff Release" |

**2025 rollover was excellent**: pre-kickoff release Jan 3, kickoff release Jan 7, game-element
support Jan 21.

**2026 rollover was not**: the only 2026 release is a **beta**, published Jan 17 2026, and it is
still the newest release **seven months later**. The currently-served vendordep at
<https://shenzhen-robotics-alliance.github.io/maple-sim/vendordep/maple-sim.json> reads:

```json
"version": "0.4.0-beta-obstacles-fix",
"frcYear": "2026"
```

There is still no stable 2026 release.

### 4.2 The maintainer bandwidth problem (bus factor = 1)

Contributions: `catr1xLiu` **477**, next highest `twisterjafla` **49**, then 12, 12, 9, 8, 7, 6, 6, …

Issue [#158, "[Notice] Limited Maintenance for March"](https://github.com/Shenzhen-Robotics-Alliance/maple-sim/issues/158),
opened 2026-03-12 by the lead maintainer, verbatim:

> "Hey everyone! Want to share a quick update on MapleSim maintenance. As you might have noticed, I
> have been very inactive this season. My current co-op work term has been a bit more intense than I
> expected… **PR reviews**: PRs with new features will not be reviewed… **Releases**: No release will
> be made until March 24th… `March 25th onwards`: I will review the PRs accumulated so far and
> **create a stable 2026 release**… Meanwhile, I'm still entirely open to hearing if anyone is
> interested in **taking shared leadership of the project**."

The promised stable 2026 release **never shipped**. As of 2026-08-24 the newest release is still the
January beta.

Weekly commit counts for the trailing 52 weeks show a burst of 32/12/7/14 around kickoff, then
essentially flat — the last ~6 weeks are all zero.

### 4.3 2027 status: nothing shipped, nothing planned publicly

- **Branches on the repo:** `main`, `gh-pages`, `fix-timer`, `copilot/fix-swerve-module-bounding-check`,
  `copilot/sub-pr-146`. **No 2027 branch.**
- **Issue search for "2027":** zero results. **Issue search for "systemcore":** zero results.
- **The only 2027 work is a community draft PR**:
  [PR #161 "Upgrade to 2027 alpha-6"](https://github.com/Shenzhen-Robotics-Alliance/maple-sim/pull/161)
  by `BrightTheBackpack`, opened **2026-06-20**, `state: open`, `draft: true`,
  `mergeable_state: dirty`, **51 files changed, +450 / −446**, `updated_at` unchanged since the day
  it was opened. **No maintainer review in 65 days.**
- **maple-sim does not appear in WPILib's official SystemCore vendor compatibility matrix at all.**
  [`wpilibsuite/SystemCoreTesting`](https://github.com/wpilibsuite/SystemCoreTesting) lists per-vendor
  pages for Phoenix 6, REV, AdvantageKit, ChoreoLib, PathPlannerLib, ThriftyLib — and its
  compatibility table for WPILib `v2027.0.0-alpha-5/6` reads:

  | Library | alpha-2 | alpha-5/6 |
  |---|---|---|
  | CTRE Phoenix 6 | 25.90.0-alpha-1/2 | v26.50.0-alpha-1 |
  | REVLib | v2027.0.0-alpha-1 | **v2027.0.0-alpha-2** |
  | ReduxLib | v2027.0.0-alpha-2 | v2027.0.0-alpha-6 |
  | PathPlannerLib | 2027.0.0-alpha-2 | v2027.0.0-alpha-3 |
  | ChoreoLib | 2027.0.0-alpha-1 | ❌ |
  | AdvantageKit | v27.0.0-alpha-3 | v27.0.0-alpha-4 |
  | ThriftyLib | ❌ | v2027.0.0-alpha-1 |

  maple-sim is not a row. **AdvantageKit — the library we rejected — is two alphas ahead of
  maple-sim on 2027 readiness.**

### 4.4 How big is the 2027 port, really?

PR #161's diffstat (**+450/−446 over 51 files**) tells us the port is largely mechanical. maple-sim's
WPILib surface is small and shallow:

```
22× Translation2d   20× Rotation2d      16× Pose2d          13× DriverStation
12× ChassisSpeeds   10× Distance/Angle   9× Pose3d           8× NetworkTableInstance
 7× LinearVelocity   6× StructPublisher  5× system.plant.DCMotor
 4× SwerveModuleState  4× Timer          1× DCMotorSim  1× RoboRioSim  1× BatterySim
```

So the port is: `ChassisSpeeds`→`ChassisVelocities` (12 sites), `SwerveModuleState`→
`SwerveModuleVelocity` (4), `math.system.plant.DCMotor`→`math.system.DCMotor` (5),
`wpilibj.simulation.*`→`org.wpilib.simulation.*` (3), plus a global `edu.wpi.first`→`org.wpilib`
rename and the kinematics method renames. **Perfectly tractable — a weekend of work.**

But note: **PR #161 targets alpha-6, and `main` has moved past it** (the `plant` flattening and the
`LinearSystemId`→`Models` rename landed after alpha-6). So even that PR is already stale, and this
will keep happening through the alpha/beta cycle.

### 4.5 The API-shape problem (this is the one that actually matters)

maple-sim offers two integration paths, and **both push toward an IO-layer split we deliberately
rejected**:

- **The "hardware abstraction" path** — the documented main route, structured as
  `MySubsystemIO.java` / `MySubsystemIOTalonFX.java` / `MySubsystemIOSparkMax.java` /
  `MySubsystemIOSim.java`. This is the AdvantageKit IO pattern. All four official templates are
  `AdvantageKit-*-MapleSim` or `CTRE-Swerve-MapleSim` submodules; **there is no first-party
  plain-REV/Spark template.**
- **The "easy" path** (`SelfControlledSwerveDriveSimulation`, 580 lines) — still requires you to
  "create an interface abstraction of your drive subsystem" and instantiate
  `if (Robot.isReal()) { … } else { drive = new MapleSimSwerve(); }`. Your drive subsystem is
  replaced wholesale in sim; it is not your real code with simulated hardware underneath.

The escape hatch is `SimulatedMotorController`:

```java
public interface SimulatedMotorController {
    Voltage updateControlSignal(Angle mechanismAngle, AngularVelocity mechanismVelocity,
                                Angle encoderAngle, AngularVelocity encoderVelocity);
}
```

Implementing this against `SparkSim` would let our real subsystem code run unchanged with maple-sim
underneath — which is what the CTRE template does for Phoenix 6. **But no such REV bridge exists;
we would write and maintain it ourselves.** That is real work on top of adopting the vendordep,
and it is work in the exact area (the vendor-sim boundary) where we would otherwise own everything.

---

## 5. What maple-sim provides that WPILib does not — filtered to a drive base

| Capability | WPILib 2027 | maple-sim | Matters for a **drive base**? |
|---|---|---|---|
| Motor electrical model (Kt/Kv/R, current, torque) | ✅ `DCMotor` | uses WPILib's | — (already have it) |
| Single-mechanism rotational sim | ✅ `DCMotorSim`, `FlywheelSim` | `MapleMotorSim` | — (already have it) |
| Battery sag under load | ✅ `BatterySim` + `RoboRioSim.setVInVoltage` | `SimulatedBattery` | — (already have it) |
| Current limiting in sim | via `SparkSim.iterate()` | `GenericMotorController.withCurrentLimit` | — (vendor gives it) |
| **Chassis mass / MOI as a rigid body** | ❌ (tank only) | ✅ dyn4j `Body` | **YES — the core gap** |
| **Per-module force → chassis acceleration** | ❌ | ✅ | **YES** |
| **Traction limit / wheel slip** | ❌ | ✅ `μ·m·g` clamp | **YES — this is what makes autos honest** |
| **Odometry drift from skid** | ❌ | ✅ (falls out of the above) | **YES — this is the headline value** |
| Gyro drift + impact response | partial (`OnboardIMUSim` setters, no model) | ✅ `GyroSimulation` | Nice to have |
| Collisions with field walls | ❌ | ✅ (`ArenaEvergreen` = walls only) | Marginal |
| Collisions with opponent robots / defense | ❌ | ✅ | Marginal for a drive base |
| Game pieces, intake, scoring, projectiles | ❌ | ✅ (~4,200 lines) | **No — out of scope** |

**Reduced honestly, maple-sim's drive-base-specific value is four things:** rigid-body chassis
integration, per-module force generation, a traction/slip limit, and the skid-induced odometry error
that emerges from them. **Everything else is either already ours from WPILib+vendor sim, or is game
robot territory that issue #14 does not cover.**

---

## 6. DIY: the minimum viable swerve chassis model

### 6.1 The model

Six chassis states (`x, y, θ, vx, vy, ω`) plus per-module steer angle and wheel speed. Per sub-tick
(dt = 4 ms, 5 sub-ticks per 20 ms period):

1. **Steer** — advance each module's azimuth (either a `DCMotorSim` driven by `SparkSim`, or a
   first-order slew for the MVP).
2. **Module ground velocity** — `v_i = v_chassis + ω × r_i`, rotated into the field frame.
3. **Resolve into wheel frame** — longitudinal `v_long` and lateral `v_lat` components relative to
   the module's world-frame heading.
4. **Longitudinal force** — from the DC motor model:
   `F_long = Kt · I · G / r_wheel` where `I = (V − ω_motor/Kv) / R`.
5. **Lateral force** — tire stiffness `F_lat = −k · v_lat` (a swerve wheel resists sideways motion;
   `k` is one tuning constant).
6. **Friction circle** — clamp `‖(F_long, F_lat)‖ ≤ μ · m·g / 4`. If clamped, the module is
   *slipping*: relax wheel speed toward the motor's equilibrium instead of the ground speed. **This
   single branch is what produces realistic odometry drift.**
7. **Accumulate** — `ΣF` → `a = F/m`; `Σ(r_i × F_i)` → `α = τ/J`.
8. **Integrate** — semi-implicit Euler on velocity then pose.

Feed step 6's wheel position/velocity back into `SparkSim.iterate(...)` and step 8's `θ`/`ω` into
`Pigeon2SimState` (or `OnboardIMUSim.setYaw`), and **our real subsystem code runs unchanged**.

### 6.2 Measured cost and size

I wrote this model as a standalone benchmark
(`/tmp/.../scratchpad/diy/Diy.java`, JDK 25, AMD Ryzen 7 5800X3D):

```
robot periods simulated: 2000000
cost per 20ms robot period (5 sub-ticks): 1.592 us
% of a 20ms budget: 0.00796%
```

**Size: 53 non-comment lines** for config + state + `subTick()` + angle wrap. In real production
form — WPILib units and `Translation2d`, a proper config record, `DCMotorSim` for steer, encoder and
gyro feeds, `StructArrayPublisher` telemetry for AdvantageScope, and a pose-reset API — call it
**250–400 lines in 3–5 files**.

For calibration, maple-sim's equivalent (`SwerveDriveSimulation` + the force half of
`SwerveModuleSimulation`) is about **500 lines** *plus* dyn4j doing the integration and the collision
work.

### 6.3 What we would be signing up to maintain

**Honestly assessed:**

- **Low ongoing burden.** The model depends only on `DCMotor` (Kt/Kv/R), `Translation2d`,
  `Rotation2d`, `Pose2d`, and optionally `DCMotorSim`. Those are the *most* stable parts of WPILib.
  Season rollover means fixing import paths and any method renames — which is exactly what we would
  do to a maple-sim fork anyway, except across 300 lines we wrote instead of 8,700 we didn't.
- **One-time tuning cost.** Three constants need calibrating against the real robot: `μ`
  (coefficient of friction, ~1.1–1.5 for typical treads on carpet), lateral tire stiffness `k`, and
  chassis MOI `J`. maple-sim has this same problem — its `FRICTION_FORCE_GAIN = 3.0` and damping
  `1.4` are exactly these knobs, pre-tuned by someone else against a robot that isn't ours.
- **The real risk is subtle numerical bugs.** A sign error in the `r × F` cross product or a
  frame-conversion mistake produces a sim that looks plausible and lies. Mitigation: unit tests with
  analytic expectations (straight-line accel to a known terminal velocity; pure rotation about the
  center produces zero translation; commanding beyond the traction limit produces skid, i.e. wheel
  odometry diverging from true pose).
- **What we give up permanently:** collisions. Without a constraint solver, we cannot bounce off a
  wall or get pushed by a defender. For a drive base in an offseason 2027 project, that is an
  acceptable loss — arguably a feature, since nothing about our own code changes when we collide with
  something we haven't modelled.

---

## 7. The middle path

**Yes, and it is what I am recommending.** The seam is clean because the two halves of the problem
are already separate:

```
  real subsystem code (unchanged)
        │
        ▼
  SparkSim / Pigeon2SimState          ← vendor sim, the locked baseline
        │  voltage out ↓   ↑ encoder/gyro in
        ▼
  ── module physics ─────────────────  ← WPILib: DCMotor + DCMotorSim + Models.singleJointedArmFrom*
        │  wheel torque ↓  ↑ wheel speed
        ▼
  ── chassis dynamics ──────────────   ← ~300 lines we write: forces, friction circle, ΣF=ma, integrate
        │
        ▼
  true field pose  →  AdvantageScope / Elastic / vision sim
```

The chassis-dynamics layer is a **pure function of module forces**. It has no opinion about how those
forces were produced, so it composes with the existing baseline instead of replacing it. Concretely:

- Steer: `DCMotorSim(Models.singleJointedArmFromPhysicalConstants(DCMotor.getNeo550(1), J_steer, G_steer), gearbox)`
- Drive torque: `DCMotor.getNeoVortex(1).getTorque(current)` — no state-space needed, the wheel's
  speed is set by the chassis, not integrated independently.
- Chassis: ours.
- Battery: `RoboRioSim.setVInVoltage(BatterySim.calculateDefaultBatteryLoadedVoltage(currents…))`.

And **we can walk it back**: if the DIY layer turns out to be too crude, `SimulatedMotorController`
is exactly the interface a maple-sim adoption would need, so the port cost is not lost.

---

## 8. Loop time and SystemCore

All measured on JDK 25 / AMD Ryzen 7 5800X3D, cost normalised per 20 ms robot period with 5 sub-ticks:

| Configuration | µs / 20 ms period | % of budget |
|---|---:|---:|
| **DIY chassis model, 4 modules** | **1.6** | **0.008 %** |
| dyn4j 5.0.2: 1 robot, walls, no game pieces | 6.0 | 0.030 % |
| dyn4j 5.0.2: 1 robot + 40 game pieces | 47.0 | 0.235 % |
| dyn4j 5.0.2: 6 robots + 40 game pieces | 46.5 | 0.232 % |
| dyn4j 5.0.2: 6 robots + 100 game pieces | 110.9 | 0.555 % |

(The dyn4j numbers are the physics-engine step only, in a field-sized world with bumper-sized bodies
and maple-sim-like damping; they exclude maple-sim's own Java on top, which is small by comparison.)

**Conclusion: loop time is a non-issue for either option on a laptop.** Even the heaviest full-field
configuration uses about half a percent of the period.

**On SystemCore:** SystemCore is a **Raspberry Pi Compute Module 5** — Broadcom BCM2712, quad-core
Cortex-A76 at 2.4 GHz, 4 GB RAM, VideoCore VII, running real-time Linux. That is roughly a Pi 5 and
vastly more capable than a roboRIO (dual Cortex-A9 @ 667 MHz). Extrapolating a conservative 5–10×
per-core slowdown versus the 5800X3D:

- DIY model: ~8–16 µs → **under 0.1 % of the period**.
- dyn4j full field: ~0.55–1.1 ms → **under 6 % of the period**.

Both fit comfortably. Neither pure Java nor dyn4j has native code, so both run on arm64 unmodified.
**Caveat: this is arithmetic on a benchmark, not a measurement on real SystemCore hardware, and we do
not have a unit to test on.** Also worth stating plainly: this only matters if we intend a
hardware-in-the-loop mode where the sim runs on the robot controller. For ordinary desktop
simulation, the sim never touches SystemCore, and the question is moot.

---

## 9. Key takeaways for this project

1. **WPILib 2027 has no swerve simulation whatsoever.** Zero hits across the whole tree. Our baseline
   (vendor sim at the hardware boundary) genuinely stops at "the wheels turn correctly." This gap is
   real and confirmed, not assumed.
2. **The gap is smaller than it looks.** Reduced to a drive base, it is exactly four things:
   rigid-body integration, per-module force generation, a traction limit, and the odometry drift that
   emerges from the traction limit. Everything else maple-sim offers is either already ours or is
   game-robot scope.
3. **maple-sim's 2027 situation is worse than "slow."** No release, no branch, no issue, absent from
   WPILib's official SystemCore vendor matrix, and a two-month-old unreviewed community draft PR as
   the only work in flight — while the *2026* stable release, publicly promised for March, still has
   not shipped seven months later. Contribution counts are 477 / 49 / 12 / 12: a bus factor of one,
   with the maintainer openly asking for co-leads.
4. **Adopting maple-sim would reopen a decision we already closed.** Its documented integration is
   the AdvantageKit IO-layer pattern; all four official templates are AdvantageKit or CTRE. Making it
   work with our "real code, unchanged, on top of `SparkSim`" model means writing and maintaining a
   REV `SimulatedMotorController` bridge that does not exist. That is a hidden cost that erases much
   of the "just use the library" advantage.
5. **The DIY layer is small, cheap, and measured.** 53 lines as a benchmark, ~300 in production form,
   1.6 µs per robot period. maple-sim's own physics is heuristic (a hand-tuned `FRICTION_FORCE_GAIN =
   3.0`, flat damping of 1.4), so the fidelity bar we must clear is low.
6. **Loop time does not discriminate between the options.** Both are far under budget on a laptop and
   would be on a CM5.
7. **We keep the option open.** `SimulatedMotorController` is the same seam either way, so if the DIY
   layer disappoints we can adopt (or fork) maple-sim later without redesigning anything.

---

## 10. Recommendation

**Build our own thin chassis-dynamics layer. Do not take the maple-sim vendordep.**

Concretely:
- Keep the locked baseline: `SparkSim` / `Pigeon2SimState` at the hardware boundary, real code
  unchanged.
- Use WPILib for module physics: `DCMotor` for torque/current, `DCMotorSim` +
  `Models.singleJointedArmFromPhysicalConstants` for the steer mechanism, `BatterySim` +
  `RoboRioSim.setVInVoltage` for sag.
- Add ~300 lines of chassis dynamics (Section 6.1) that turns per-module forces into a field pose,
  with a friction circle producing genuine skid and odometry drift.
- Ship it with unit tests that assert analytic behaviour (terminal velocity, pure rotation, skid
  under over-command), and publish the true pose alongside the odometry pose so drift is visible in
  AdvantageScope.

### The trade-off, stated plainly

**What we give up:** collision response. We will not bounce off field walls, be pushed by a defender,
or interact with game pieces. We also give up a pre-tuned model that other teams have validated —
we own the calibration of `μ`, lateral stiffness, and chassis MOI ourselves, and we own any bug in
our cross products.

**What we get:** no dependency on a single-maintainer project that has not shipped a stable release
in seven months and has no 2027 plan; no reopening of the IO-layer decision; a model small enough
that any student on the team can read all of it in one sitting; and a season rollover cost of "fix
300 lines of imports" instead of "wait, or fork and port 8,700."

**The decisive factor is not fidelity — it is that the failure mode of the maple-sim path is
"blocked, waiting on someone else, in January."** "Fully sim capable" is a headline goal. A headline
goal cannot sit behind an unreviewed draft PR on someone else's repository.

### If this recommendation is rejected

The next-best option is **fork maple-sim and port it ourselves** (PR #161 shows the port is
+450/−446 across 51 files, and the maintainer explicitly recommends this: *"it will be easier to use
maple-sim in your own fork and use it through `publishToMavenLocal`"*). That gets collisions and
pre-tuned physics, at the cost of owning 8,700 lines instead of 300 and still needing the REV bridge.
I would only take that trade if collision/defense simulation becomes a stated requirement.

---

## 11. Open questions / unknowns

1. **Does the drive base actually need collisions?** If issue #14's acceptance criteria include
   "drive into the wall and stop," the DIY model needs a trivial wall clamp (cheap), but "get pushed
   by a defender" would genuinely favour maple-sim. **This should be settled before implementation
   starts.**
2. **Calibration data.** `μ`, lateral tire stiffness, and chassis MOI are guesses until measured
   against a real robot. Do we have a 2026 chassis to characterise, or are we tuning against
   maple-sim's MAXSwerve preset numbers as a starting point? (`COTS.ofMAXSwerve()` is a legitimate
   source for `μ` even if we don't take the dependency.)
3. **Is a hardware-in-the-loop / on-SystemCore sim mode actually wanted?** The loop-time analysis
   assumes it might be. If sim only ever runs on a laptop, Section 8 is moot and we should not spend
   effort on arm64 validation. No SystemCore unit is available to measure on regardless.
4. **`Pigeon2` vs. `OnboardIMU`.** SystemCore ships a built-in IMU with a first-party
   `OnboardIMUSim`. This is arguably a better sim story than `Pigeon2SimState` and one less CAN
   device. **This is an unplanned decision that touches the locked hardware list** — see below.
5. **WPILib 2027 API churn is not finished.** `LinearSystemId`→`Models` and the `plant` flattening
   landed on `main` *after* alpha-6 and are in no released alpha. Anything we write now against the
   local checkout may need renaming again before the season release. Prefer pinning to a tagged alpha
   for the codebase and treating the local `main` checkout as reference only.
6. **Vision sim.** maple-sim's true-pose output feeds PhotonVision's sim directly. Our DIY layer
   produces the same `Pose2d`, so this should compose — **unverified**, and depends on whether
   PhotonVision has a 2027 release (it is also absent from the SystemCore vendor matrix).
7. **Multi-robot / opponent sim** is entirely out of scope here and would be a maple-sim-only
   capability.

### Flags for locked decisions

- ⚠️ **Choreo (locked).** WPILib's SystemCore compatibility matrix lists **ChoreoLib as ❌ for WPILib
  `v2027.0.0-alpha-5/6`** — it only has a build against alpha-2. The Choreo team has publicly said on
  Chief Delphi that they intend to ship a SystemCore-compatible build for offseason use, but as of
  today there is no compatible release. **This does not invalidate the decision, but it is a
  dependency risk on the critical path for autonomous, and it deserves its own tracking.**
- ⚠️ **Pigeon2 (locked hardware).** SystemCore's built-in `OnboardIMU` + `OnboardIMUSim` may make a
  separate Pigeon2 redundant, and gives a cleaner sim seam. Worth a deliberate decision rather than
  inheriting the 2026 choice by default.
- ✅ **AdvantageKit rejection holds, and this research reinforces it.** maple-sim's official
  integration path is the AdvantageKit IO pattern; declining maple-sim keeps us consistent.
- ✅ **Vendor-sim-at-the-boundary baseline holds.** The recommendation layers on top of it rather than
  replacing it. REVLib already has `v2027.0.0-alpha-2` compatible with WPILib alpha-5/6, and
  `SparkSim.iterate()` supports simulated current limits and closed-loop control.

---

## 12. Sources

**Primary — local source (WPILib `v2027.0.0-alpha-6-366-gcafb0cc79`, 366 commits past alpha-6):**
- `/home/drew/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/system/DCMotor.java`
- `/home/drew/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/system/Models.java`
- `/home/drew/dev/allwpilib/wpilibj/src/main/java/org/wpilib/simulation/` (DCMotorSim, FlywheelSim, LinearSystemSim, DifferentialDrivetrainSim, BatterySim, RoboRioSim, OnboardIMUSim)
- `/home/drew/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/kinematics/` (SwerveDriveKinematics, ChassisVelocities, SwerveModuleVelocity)
- `/home/drew/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/` (swervebot, swervedriveposeestimator, rebuiltcmdv3)
- `/home/drew/dev/allwpilib/README.md`, `build.gradle:188` (`options.release = 25`)

**Primary — maple-sim source and GitHub API (`main` @ 2026-07-08):**
- <https://github.com/Shenzhen-Robotics-Alliance/maple-sim>
- `project/src/main/java/org/ironmaple/simulation/drivesims/SwerveDriveSimulation.java`
- `project/src/main/java/org/ironmaple/simulation/drivesims/SwerveModuleSimulation.java`
- `project/src/main/java/org/ironmaple/simulation/SimulatedArena.java`
- `project/src/main/java/org/ironmaple/simulation/motorsims/SimulatedMotorController.java`
- `project/build.gradle`, `project/maple-sim.json`, `.gitmodules`
- [Issue #158 — "[Notice] Limited Maintenance for March"](https://github.com/Shenzhen-Robotics-Alliance/maple-sim/issues/158)
- [PR #161 — "Upgrade to 2027 alpha-6"](https://github.com/Shenzhen-Robotics-Alliance/maple-sim/pull/161)
- <https://shenzhen-robotics-alliance.github.io/maple-sim/vendordep/maple-sim.json> (live vendordep)
- <https://shenzhen-robotics-alliance.github.io/maple-sim/> (docs: simulation-details, swerve-sim-easy, swerve-sim-hardware-abstraction)

**Primary — WPILib project:**
- <https://github.com/wpilibsuite/SystemCoreTesting> — README compatibility matrix, per-vendor pages
- <https://docs.wpilib.org/en/2027/docs/yearly-overview/yearly-changelog.html> — 2027 breaking changes
- <https://github.com/wpilibsuite/allwpilib/releases> — alpha-6 tagged 2026-05-08 (newest)

**Primary — dyn4j:**
- <https://github.com/dyn4j/dyn4j> — 6.0.0 released 2026-07-18; maple-sim pins 5.0.2

**Secondary:**
- SystemCore hardware (Raspberry Pi CM5 / BCM2712 / Cortex-A76 2.4 GHz / 4 GB):
  <https://docs.wpilib.org/en/latest/docs/software/systemcore-info/systemcore-introduction.html>,
  [SystemCore specification PDF](https://downloads.limelightvision.io/documents/systemcore_specifications_june15_2025_alpha.pdf)
- [Choreo SystemCore offseason use — Chief Delphi](https://www.chiefdelphi.com/t/official-choreo-systemcore-offseason-use/522484)
- [REVLib changelog](https://docs.revrobotics.com/revlib/home/install/changelog), [SparkSim API docs](https://codedocs.revrobotics.com/java/com/revrobotics/spark/sparksim)

**Benchmarks** (reproducible; JDK 25, AMD Ryzen 7 5800X3D, `-XX:+UseSerialGC`): DIY model and dyn4j
harness written for this research; numbers in Section 8. Sources are transient scratch files, not
committed.
