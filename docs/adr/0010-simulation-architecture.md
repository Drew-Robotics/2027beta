# ADR 0010 — Simulation architecture

## Status

Accepted — 2026-08-26. Resolves ADR 0003's `SparkSim` item, which held
that this ADR had nothing to write until that class loaded against our
WPILib. It still does not load, and this ADR does not use it — the
sensor sims beside it are clean, and they are what the decision rests
on. Amended 2026-08-29: applied output is a fourth simulated signal, and
it is written to the device's `SimDevice` directly because the class that
would write it is the one that does not load. Amended 2026-08-30: this
architecture now runs — ADR 0015's shim binds REVLib's native, and the
whole of `updateSim()` has been executed against a real `Robot` on the
desktop. `SparkSim` is still not loaded and this ADR still does not use
it.

The *Open* item asking whether CI runs a headless robot program is
answered by ADR 0013 and now sits under *Consequences*.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. REVLib
`[source]` claims were read in `REVLib-java 2027.0.0-alpha-6`, the
version `vendordeps/REVLib.json` pins, from its sources jar; paths are
given as `com/revrobotics/...`. Phoenix 6 `[source]` claims were read in
`26.50.0-alpha-1`, the version `vendordeps/Phoenix6-26.50.0-alpha-1.json`
pins, from its sources jar; paths are given as `com/ctre/phoenix6/...`.
An unqualified path is a file in this repo.

## Context

Simulation is how the drive base gets driven when there is no drive
base. That is not a nicety on this project: the modules are being
designed before they are built, autonomous has to be tested before a
field exists, and ADR 0012's vision fusion has nothing to fuse against
until something produces a pose.

**WPILib 2027 ships no swerve simulation of any kind.** A grep across
the whole tree for `SwerveModuleSim`, `SwerveDriveSim` or `SwerveSim`
returns zero hits; there is no friction, slip or traction model
anywhere; and none of the four swerve examples has a
`simulationPeriodic` at all. `DifferentialDrivetrainSim` is the only
chassis-level sim in the library, and it is tank. **[executed — #8]**
So WPILib will spin our simulated wheels perfectly and will never tell
us where the robot went as a consequence.

ADR 0003 decided there is **no IO seam** — one class per mechanism,
owning both its real and its simulated hardware — on the reasoning that
the vendor sim classes are already the seam: `SparkSim` wraps a live
`SparkMax` and drives that object's signals, so the same object behaves
as hardware on the robot and as a model on a laptop. Then ADR 0008 put
both module loops on the SPARK, closing steer against an analog absolute
encoder. The vendor sim class that was supposed to close those loops
**cannot close this one** — see Traps — and so the seam has to sit
somewhere else.

This ADR says where it sits, what is allowed to cross it, what ticks it,
and what it writes to the log. It does not decide the physics behind it,
and it does not decide the CI tier that runs it.

## Decision

### The seam is a pure function of voltages

`SwerveDriveSim` lives in **`first.robot.sim`** and takes voltages in,
`dt` in, and gives module state and a true pose out:

```
in:   double[4] driveVolts, double[4] steerVolts, double dtSeconds
out:  per module { wheelPositionRad, wheelVelocityRadPerSec,
                   azimuth: Rotation2d, slipping: boolean }
      Pose2d truePose, ChassisVelocities trueVelocity
also: void resetPose(Pose2d)
```

Nothing in that signature knows what produced the voltages or what will
consume the pose. **[decided]**

### No vendor type crosses the seam, and that is the test

**If a class under `first.robot.sim` needs to import `com.revrobotics.*`
or `com.ctre.*`, it is on the wrong side of the seam.** That is the
whole rule, and it decides membership without anybody arguing about
layers. **[decided]**

The converse matters as much: `Drive.updateSim()` is *allowed* to be
saturated with vendor types, because holding them is its entire job.
There is no virtue in a thin `updateSim`.

What the rule buys is that the physics is testable in **plain JUnit with
no vendor jars, no HAL and no `RobotBase`** — which is what lets ADR
0011's whole autonomous loop close as a unit test, and what keeps the
sim runnable at all while REVLib's sim classes are broken against our
WPILib. It is also the contract handed to the physics map: everything
deferred lives strictly behind this signature and can be replaced
without `Drive` noticing.

ADR 0003 names the root package `first.robot`, so the package is
`first.robot.sim` — a sibling of `opmode/` and `mechanisms/`, and
deliberately not `util/`, which is where packages go to become junk
drawers. Two companion rules keep it honest:

- **`first.robot.sim` is only ever touched inside an `isSimulation()`
  guard or `simulationPeriodic()`.**
- **Sim state is written only in `simulationPeriodic()` and never read
  by mechanism logic.** The mechanism reads its encoders exactly as it
  does on hardware, because on hardware is the only case that matters.

### `Drive.updateSim()` owns the plumbing; `Robot.simulationPeriodic()` ticks it

`OpModeRobot` has the hooks: `simulationInit()` (`OpModeRobot.java:573`)
and `simulationPeriodic()` (`:576`), the first called once at startup
(`:774`) and the second once per period after `robotPeriodic()`, wrapped
in `HAL.simPeriodicBefore()` / `simPeriodicAfter()` (`:711-716`).
**[source]** Their default bodies are **empty, not a `println`**:
`OpModeRobot.simulationPeriodic()` is `{}` (`:576`) where
`IterativeRobotBase.simulationPeriodic()` prints *"Default
simulationPeriodic() method... Override me!"*
(`wpilibj/src/main/java/org/wpilib/framework/IterativeRobotBase.java:150-153`).
**[source]** That matters given that a single `println` overruns the
loop **[measured — #10]**.

There is no per-mechanism sim hook, because Commands v3 has no
`Subsystem` type. The pattern is therefore a habit rather than a
framework, and it is one sentence:

> A mechanism that needs simulating owns a plain-WPILib-math sim object
> in `first.robot.sim` with no vendor types, holds the vendor `*Sim`
> plumbing in its own `updateSim()`, and `Robot.simulationPeriodic()`
> calls each mechanism's `updateSim()` in turn. Anything simpler than a
> drivetrain is a `DCMotorSim` or an `ElevatorSim` with no
> `first.robot.sim` class at all.

No base class, no `Simulatable` interface, nothing to police.
**[decided]**

### `updateSim()` models the onboard loop, and `SparkSim` is never loaded

Because ADR 0008 closes both loops on the controller, the thing
`updateSim()` has to reproduce is a loop we do not run. It needs exactly
three things, and it has all three:

- **The last commanded setpoint.** The `SwerveModule` object *wrote* it,
  so it holds it. No readback, no bus.
- **A model of the SPARK's loop** — a PID plus position wrapping. Plain
  arithmetic, no vendor types, so it lives in `first.robot.sim` by the
  rule above.
- **A way to push state into the sensors.**
  `SparkAnalogSensorSim(SparkFlex)`
  (`com/revrobotics/sim/SparkAnalogSensorSim.java:67`) and
  `SparkRelativeEncoderSim(SparkFlex)`
  (`com/revrobotics/sim/SparkRelativeEncoderSim.java:67`) have public
  constructors and `setPosition(double)` / `setVelocity(double)`
  (`:120, :140` and `:100, :120`). They import nothing but
  `com.revrobotics.spark` and the HAL's `SimDouble` / `SimBoolean` /
  `SimDeviceSim` (`:31-36` in both files) **[source]**, so they are
  clean of the chain that breaks `SparkSim`.

**`SparkSim` is therefore never loaded at all.** It is not worked
around, not guarded, not conditionally constructed — the class does not
appear in the project.

This is a **model**, not a reimplementation of REV's firmware. #25
rejected hand-writing a copy of the onboard loop *for fidelity*; this is
a different artefact at a different bar — #19's Tier 2 assertions are
deliberately loose and #23's `sim-hitl` is *drive around in sim*.
**[decided]**

**Write positions; do not call `iterate()`.** Our model owns the
integration, so `setPosition` writes the value the model already
computed. `iterate(velocity, dt)` would integrate a second time on top
of it, and it carries a defect besides — see Traps.

### Applied output is written where `SparkSim` would write it

A fourth signal joined the three above once ADR 0009's voltage column
became the applied output rather than the request: **what the controller
put on the motor.** Nothing writes it today —
`SparkBase.getAppliedOutput`'s own javadoc says *"this value will not be
updated during simulation unless `SparkSim.iterate` is called"*
(`com/revrobotics/spark/SparkBase.java:707-708`) **[source]** — and
`SparkSim` is the one class this ADR cannot load.

Both values behind that column live on the device's own `SimDevice`:
`SparkSim` reaches them as `"Applied Output"` and `"Bus Voltage"` on
`SimDeviceSim("SPARK Flex [" + busId + "," + deviceId + "]")`
(`com/revrobotics/spark/SparkSim.java:80-85`) **[source]**, which is the
same door the sensor sims go through. **They are written directly, from
`first.robot.mechanisms`.** **[decided]** That is eight lines against a
class that will not load, and it is the *whole* of what `SparkSim` would
have contributed here: the plant already integrates and the loops are
already modelled, so nothing else in that class is wanted.

The number written is the plant's applied voltage, not the mechanism's
commanded one. `SwerveDriveSim` clamps against the current limit exactly
where the controller does and now reports what it clamped to, so the
simulated column has the same shape as the real one — including the flat
stretch at the bottom of a step that ADR 0009's traps are about.

`SimDeviceSim.getDouble` returns `null` for a name it cannot resolve
(`wpilibj/src/main/java/org/wpilib/simulation/SimDeviceSim.java:117-123`)
**[source]**, so a handle taken before its SPARK exists drops every write
rather than throwing — the same silent no-op the sensor sims have. See
Traps.

### The tick is 5 ms and the sub-step is 1 ms

`simulationPeriodic()` runs at `Constants.LOOP_PERIOD`, which ADR 0002
fixes at 5 ms. Inside it the model is advanced in **five sub-steps of
1 ms**, matching the SPARK's documented 1 ms control loop **[source, via
#29 — REV's SPARK documentation; not re-read here]**, with the commanded
setpoint held constant across all five.

Holding the setpoint constant *is* the point. It reproduces the real
asymmetry — **200 Hz commanding a 1 kHz loop** — which is a thing worth
simulating now that the loop is on the controller and was not worth
simulating when it was not.

The cost is noise. #8 benchmarked the whole DIY sim at **1.6 µs per
20 ms period**, five sub-ticks to the period **[measured — #8]**. Ours
is five sub-steps to a 5 ms period — four times as many sub-steps per
second — so call it **~6.4 µs per 20 ms of wall clock**, which is three
hundredths of a percent of a desktop's budget.

### Sim ships in the fat jar, behind `isSimulation()`

Same source set, `RobotBase.isSimulation()` (`RobotBase.java:316`)
**[source]** guarding construction, shipped in the shadow jar the
template already builds (`build.gradle:94, 104`). **[source]**

**No `src/sim/java`.** A separate source set means editing the stock
template's `build.gradle`, which ADR 0003 ruled against, and it buys
nothing: the sim classes are inert on the robot because nothing
constructs them. **[decided]**

### The gyro gets three deliberate additions

The write discipline is #5's and is unchanged: `setRawYaw`, **never**
`addYaw`; **degrees, not rotations**; NWU, so no sign flip; supply
voltage → plant → yaw, **in that order**; and retry up to 5× on a
non-OK `StatusCode`. ADR 0012 adds `setAngularVelocityZ` on the same
path, so the pose estimator's rotation gate sees a real number in sim.

On top of that, three additions this ADR makes deliberately:

**1. `setUpdateFrequency(Hertz.of(1000))` on the yaw signal, under
`isSimulation()` only.** CTRE simulates CAN latency, and a lagged gyro
makes odometry look broken for a reason that is not our code. 1000 Hz is
the documented ceiling — *"the minimum supported signal frequency is
4 Hz, and the maximum is 1000 Hz"*
(`com/ctre/phoenix6/BaseStatusSignal.java:176-177`) **[source]** — so
this is asking for the fastest thing on offer, not an arbitrary number.
There is no real bus to spend, so ADR 0007's frame budget is untouched;
the guard is what keeps it that way on the robot.

**2. A constant drift rate, about five lines.** Not maple-sim's
212-line gyro model. A pose estimator tuned against a perfect gyro is
tuned against a robot that does not exist, and ADR 0012's vision fusion
has nothing to correct if the heading never wanders. **[decided]**

**3. Both yaws zeroed in `simulationInit()`** — `setRawYaw(0)` on the
sim state *and* `setYaw(0)` on the device. Not one of them. See Traps
for why this is not belt-and-braces.

### Battery sag is in

```java
RoboRioSim.setVInVoltage(
    BatterySim.calculateDefaultBatteryLoadedVoltage(currents));
```

`BatterySim.calculateDefaultBatteryLoadedVoltage(double...)`
(`wpilibj/src/main/java/org/wpilib/simulation/BatterySim.java:42`) and
`RoboRioSim.setVInVoltage(double)` (`RoboRioSim.java:43`). **[source]**

Two lines, and without them simulation has a perfect 12 V forever: every
acceleration from a stop is better than the real one, and nothing ever
browns out.

### The log schema is identical to hardware, plus a `/Sim` subtree

Everything ADR 0005 lists is logged in simulation, on the same paths,
through the same backend, into the same `.wpilog`. A sim run opens in
the same tooling as a match log, and ADR 0014's analysis reads both.

Two signals exist only in simulation:

```
/Sim/TruePose      Pose2d struct
/Sim/ModuleSlip    per module
```

PascalCase, no unit suffixes, per ADR 0005's naming.

**`(/Sim/TruePose, /Drive/Odometry/OdometryOnlyPose)` is the headline
product of this architecture.** It is the only way to *see*
skid-induced drift rather than infer it, and it turns *"did the auto
work"* into an assertion. **Per-module slip is logged rather than
derived** for the same reason at finer grain: *why did it drift* should
be read off a timeline, not reconstructed.

### μ, lateral stiffness and MOI are plain constants

The three constants that cannot be derived — coefficient of friction,
lateral tire stiffness, and chassis moment of inertia — are plain
fields in the sim config record, seeded from maple-sim's published
`COTS.ofMAXSwerve()` presets — a legitimate source even though we
decline the dependency **[source, via #8]** — and from
`J ≈ m(w²+l²)/12`. **[decided]**

**Not `Tunable`s.** ADR 0004 rules that a tunable must be logged, so
making these tunable adds three signals to every log for a knob that
gets turned twice in the project's life. Promote them the day somebody
actually needs to sweep them. **[decided]**

### Running it

`./gradlew simulateJava` brings up the WPILib sim GUI, which the
template already enables (`build.gradle:88-89`) **[source]**, alongside
AdvantageScope. A scripted `@Utility` opmode that drives a path and
reports odometry error is the repeatable version of the same thing. The
numbered recipe belongs in the README, not here.

## Consequences

- **ADR 0003's open item closes, by never opening the file.** `SparkSim`
  holds a `MovingAverageFilterSim` as an initialised field
  (`SparkSim.java:53`), and that class imports
  `org.wpilib.math.util.Pair` (`MovingAverageFilterSim.java:32`) — a
  class that now lives at `org.wpilib.util.Pair`
  (`wpiutil/src/main/java/org/wpilib/util/Pair.java`). **[source]**
  Class resolution is lazy and we never name the class, so the breakage
  is not worked around; it is simply out of the program. Whether REVLib
  fixes it stops being on our critical path.

- **#19's Tier 2 and #23's `sim-hitl` un-dormant.** Both named `SparkSim`
  as their sole blocker. **[source, via #19 and #23, read through #29]**
  ADR 0013 runs both: Tier 2 drives the real `OpModeRobot` in process
  and headless, and `sim-hitl` deploys a `linuxarm64` sim build to the
  bench Pi.

- **CI does run a headless robot program, and the requirement this ADR
  set is met.** The sim is drivable with no display, deterministic given
  a fixed `dt`, and its pose readable programmatically — which is what
  ADR 0013's Tier 2 needs and all it asks for. The plain-JUnit tests of
  `SwerveDriveSim` are ours — terminal velocity under a constant
  voltage, pure rotation producing zero translation, over-command
  producing skid — and ADR 0013 makes that tier the home of every
  numeric assertion in the project.

- **The physics behind the seam can be replaced without `Drive`
  noticing.** That is the deliberate purpose of the signature, and it is
  what makes the physics a separate map rather than a blocker.

- **The half of the sim that is not plain-JUnit testable is about twenty
  lines of assignment.** The sensor sims and `Pigeon2SimState` reach the
  HAL through `SimDeviceSim`, so `updateSim()` needs a running
  `RobotBase`. That is exactly the code with no arithmetic in it, and it
  is the trade the seam was drawn to make.

- **ADR 0011's autonomous loop closes as a unit test.** Follower →
  `ChassisVelocities` → kinematics → module voltages → `SwerveDriveSim`
  → pose → back into the follower, with no HAL anywhere. The CI
  assertion and the field diagnostic are the same number.

- **A sim test that builds a mechanism without a `RobotBase` logs every
  `Measure` as its `toString()`.** The type handler is registered by
  `RobotBase` itself (`RobotBase.java:229-230`) **[source]** and ADR
  0005 makes `Measure` the default value type for everything. ADR 0003
  records this as a property of the framework; it lands here because the
  vendor-free seam is precisely what makes `RobotBase`-less tests
  possible, so this is the ADR whose tests meet it.

- **A student can select an opmode without a real Driver Station.** The
  sim GUI has a grouped auto/teleop/utility opmode selector
  (`simulation/halsim_gui/src/main/native/cpp/DriverStationGui.cpp:348-381,
  1390-1419`). **[source]** Pick from a dropdown, enable, go. Desktop sim
  runs on WSL2 through WSLg **[executed — #9]**.

- **We give up collisions, permanently and knowingly.** Nothing bounces
  off a wall, gets pushed by a defender, or touches a game piece. For a
  drive base that is an acceptable loss — arguably a feature, since
  nothing about our own code changes when we collide with something we
  have not modelled.

- **The project keeps building against a released alpha.** ADR 0003's
  rule that `~/dev/allwpilib` is reference-only is load-bearing here
  specifically: building against local `main` costs the sim story and
  nothing else, which is the failure that would be noticed last.

## Traps

- **`SparkSim` does not close the loop on the sensor you selected, and
  our steer arrangement is not simulatable through it at all.** Three
  separate defects, each individually fatal to ADR 0008's steer loop:

  - For `ControlType.kPosition` it hands the closed-loop call
    `m_position` — the **internal integrated position** — **regardless
    of the configured feedback sensor** (`SparkSim.java:291-296`).
  - It picks conversion factors from a **two-way branch** of
    `kAbsoluteEncoder` versus everything else (`:230-252`), so
    `kAnalogSensor` silently receives the **primary encoder's** factors.
  - `m_position` integrates monotonically and **never wraps** (`:264`);
    the string `wrap` appears nowhere in the file except an unrelated
    javadoc (`:61`).

  **[source]** The failure is not "less accurate". It is a simulation
  that agrees with itself, closes a loop on a sensor we do not use, in
  units we did not configure, across a boundary that never arrives —
  and disagrees with the robot. Nothing throws. This is the fact that
  moved the model into `first.robot.sim`, and it is why the model is not
  a shortcut.

- **`setYaw()` offsets survive `setRawYaw()` by design, so zeroing one
  is not zeroing the gyro.** `Pigeon2SimState.setRawYaw`'s javadoc is
  explicit: *"Inputs to this function over time should be continuous, as
  user calls of `Pigeon2#setYaw` will be accounted for in the callee"*,
  and *"Changes to `rawYawInput` will be integrated into the emulated
  yaw. This way a simulator can modify the yaw without overriding
  hardware API calls for home-ing the sensor"*
  (`com/ctre/phoenix6/sim/Pigeon2SimState.java:62-77`). **[source]**

  That is a feature — it is what stops a simulator from fighting a
  homing call. The consequence for us is that if **any** code ever homes
  the gyro, `getYaw()` silently stops matching what the sim wrote, and
  the pose estimator is reading a heading offset from the model's by a
  constant that appears in no log. Hence `setRawYaw(0)` **and**
  `setYaw(0)`, both, in `simulationInit()`. **[decided]**

- **Sim gains are the inverse of ADR 0009's failure mode, and the
  inverse is quieter.** A characterisation gain is a **measurement of a
  physical machine**, and getting it wrong shows up as a robot that does
  not track. A sim gain is the opposite: it is chosen so that **the
  model tracks**, and getting it wrong shows up as nothing at all,
  because the only thing checking it is a test we also wrote.

  The failure mode is turning sim gains until a test goes green. At that
  point the test proves the code correct against a robot that does not
  exist, and it will keep proving it after the real robot changes.
  Nothing at the call site distinguishes the two kinds of number — both
  are a `double` next to a `kP`.

  **Put a comment at the line**, saying these exist to make the model
  track and are not a prediction of the hardware. `CLAUDE.md` says
  comment the line that confuses; two numerically identical gains
  meaning opposite things is that line. **[decided]**

- **`SparkAnalogSensorSim.iterate()` divides by a conversion factor with
  no zero guard, where `SparkSim` has one.** `iterate` computes
  `velocity / getVelocityConversionFactor()`
  (`SparkAnalogSensorSim.java:201-205`), and `SparkSim.iterate` does the
  same division behind an explicit guard whose own comment says it is
  *"to prevent divide by 0 errors"* (`SparkSim.java:254-261`).
  **[source]** Nothing in REVLib's Java sources writes those sim-device
  fields — they come from the native driver — so what they hold before
  the first configure is **[unverified]**, and a zero there makes the
  sensor read `Infinity` with nothing thrown. We do not call `iterate()`
  at all, which sidesteps this as well as the double integration; it is
  written down so that nobody adds the call back as a tidy-up.

- **`setPosition` on a sensor sim takes the value *after* the conversion
  factor** — *"Set the position of the sensor, after your conversion
  factor"* (`SparkAnalogSensorSim.java:116-119`). **[source]** Handing
  it raw rotations while the config sets a conversion factor is a scale
  error that nothing reports: the model and the mechanism simply
  disagree about how far the wheel went, consistently, forever.

- **A sensor sim built before its SPARK exists silently no-ops.** The
  sim device is resolved by a name assembled from the bus and device ids
  — `"SPARK Flex [" + motor.getBusId() + "," + motor.getDeviceId() + "]
  ANALOG SENSOR"` (`SparkAnalogSensorSim.java:67-71`) — and
  `SimDeviceSim.getDouble` returns **null** for a handle that does not
  resolve (`wpilibj/.../simulation/SimDeviceSim.java:117-123`).
  **[source]** Every setter then hits `if (checkAndSetupSimDevice())
  return;` (`:88-95, :121`) and **drops the write**. **[source]** No
  exception, no warning: the model runs, the true pose moves, and the
  mechanism's encoders read zero forever. Construct each sensor sim
  after the SPARK it names — below the hardware in the mechanism's
  constructor, or in `simulationInit()` — never as a field initialised
  above it.

- **`toWheelAccelerations()` hardcodes ω = 0.**
  `SwerveDriveKinematics.toWheelAccelerations(ChassisAccelerations)` is
  `return toSwerveModuleAccelerations(chassisAccelerations, 0.0)`
  (`wpimath/src/main/java/org/wpilib/math/kinematics/SwerveDriveKinematics.java:556-560`).
  **[source]** Sim is where somebody will reach for acceleration
  kinematics to cross-check the model against the log, and that overload
  silently drops the centripetal term, which dominates during rotation.
  Always pass the angular velocity — `toSwerveModuleAccelerations(a, ω)`
  (`:551-554`). **[source]**

## Open

- **Nothing behind the seam has been measured against a robot.** μ,
  lateral stiffness and MOI are seeded from someone else's presets and a
  rectangular-slab MOI estimate. **[unverified]** *Unblocked by* a
  chassis to characterise — at which point the numbers, and the method,
  belong in `docs/research/`.

- **The physics layer is a separate map, and this ADR does not
  anticipate its answer.** What is settled is the signature it must
  satisfy and the log it must feed. The fidelity target is real contact
  physics — maple-sim's level, not the free-space model #8 recommended —
  and reaching it is deliberately deferred out of this map. The
  groundwork it inherits is on #14: **dyn4j** 6.0.0 is on Maven Central,
  493 KB, 298 classes, zero runtime dependencies, BSD-3, and has **no
  mutable global state**, with `applyForce(Vector2, Vector2)`
  accumulating ΣF and Σ(r×F) itself — which deletes #8's named biggest
  DIY risk, a sign error in the `r × F` cross product producing a sim
  that looks plausible and lies.
  **[source, via #14 — read on the published artifact]** Open there: the
  friction model, the stepping mode, what the field boundary is made
  of, who owns the `World`, and that dyn4j's gravity must be zeroed for
  a top-down world. Its cost is also recorded there — it cuts against
  the prefer-built-in-WPILib principle, and it is that map's to confirm.

## Rejected

### maple-sim, as a vendordep or as a fork

It is a good library and it solves the exact four things WPILib does not
— rigid-body chassis integration, per-module force generation, a
traction limit, and the odometry drift that falls out of them. It is
rejected on three grounds, none of them about physics:

**1. Its 2027 status is not "slow", it is absent.** No 2027 release, no
2027 branch, no issue mentioning 2027 or SystemCore, and absent from
WPILib's own SystemCore vendor compatibility matrix — which lists
AdvantageKit, the library we already rejected, two alphas ahead of it.
The only 2027 work is a community **draft** PR, unreviewed for 65 days
and already stale against `main`. Meanwhile the *2026* stable release,
publicly promised for March, had still not shipped seven months later,
and the maintainer's own notice asks for co-leads: contributions run
477 / 49 / 12 / 12. **[source, via #8 — repository, GitHub API and
release history]** **The failure mode of this path is "blocked, waiting
on someone else, in January."**

**2. Its integration path reopens a decision we closed.** The documented
main route is the AdvantageKit IO pattern — `MySubsystemIO` /
`…IOSparkMax` / `…IOSim` — and all four official templates are
AdvantageKit or CTRE submodules. The "easy" path still replaces your
drive subsystem wholesale in simulation. Making our real code run
unchanged would mean writing and maintaining a REV
`SimulatedMotorController` bridge that does not exist, in exactly the
area we would otherwise own outright. **[source, via #8]**

**3. Roughly half of it is scope we do not have.** Of 8,717 lines, about
4,200 are game pieces, intake, scoring and season-field code that a
drive base never touches. **[source, via #8]**

Forking and porting it was weighed as the next-best option and rejected
with it: the port is tractable — a community draft PR shows +450/−446
across 51 files **[source, via #8]** — but it means
owning 8,700 lines instead of the signature above, **and still needing
the REV bridge**.

**The physics layer is a separate map, and maple-sim is rejected for it
too.** This ADR rejects maple-sim as the thing behind the seam; the map
that designs what *is* behind it starts from #14's groundwork — **take
dyn4j directly, do not take or fork maple-sim** — on the observation
that all three reasons above are arguments *for* dyn4j. Bus factor and
no 2027 release: dyn4j has no FRC coupling at all, so there is nothing
for it to be ready for. The AdvantageKit IO pattern: dyn4j has no
opinion about our code structure. Absent from the vendor matrix: it is
not a vendordep, it is one line in `dependencies {}`. **[source, via
#14]** See Open.

*Do not re-raise* unless collision or defence simulation becomes a
stated requirement — that is the one capability the seam genuinely
cannot reach — and even then, dyn4j is the cheaper route to it.

**What is explicitly not rejected is maple-sim's numbers.** Its
MAXSwerve presets are published and are a legitimate seed for μ. Nor is
this a claim to be better: we do not compete on game pieces, opponent
robots, intake, scoring, or pre-tuned COTS presets, and writing that
disclaimer down is what stops the physics map becoming a physics-engine
project.

### Calling `SparkSim.iterate()`

Covered in Traps: wrong sensor, wrong conversion factor, no wrapping.
It is not a fidelity trade — for our steer arrangement it does not work
at all. #25's ruling that hand-writing a copy of REV's onboard loop was
not worth it stands as written; it weighed two options and this is a
third, at a different bar.

### A separate `src/sim/java` source set

It would need an edit to the stock template's `build.gradle`, which ADR
0003 ruled against, and it buys nothing over an `isSimulation()` guard:
the sim classes are already inert on the robot, because nothing
constructs them. The fat jar carrying a few unreferenced classes is not
a cost anybody can measure.

### A first-order slew for the steer azimuth

It was #8's suggestion for an MVP and it is the tempting shortcut. It
hides steer lag at the start of an autonomous routine — the sideways
lurch — and it bypasses the steer controller entirely, so what gets
tested is the slew rate rather than the loop. Since ADR 0008 the steer
loop is a thing we *model*, so replacing it with a slew would delete the
only copy of it we have. **[decided]**

### `Tunable` μ, lateral stiffness and MOI

Covered at the Decision: three signals in every log for a knob turned
twice. *Do not re-raise* until somebody is actually sweeping one of
them, at which point promoting a constant is a one-line change.

### Making the sim's physics fidelity this ADR's problem

The temptation is to settle the friction model here, since the seam and
the model arrive together. Splitting them is deliberate: the seam is
what unblocks ADR 0011's tests, #19's Tier 2 and #23's `sim-hitl`
**today**, and it is a decision that survives whatever the physics turns
out to be. Coupling the two would have made a settled question wait on
an open one.

## Source

Decided in
[#14](https://github.com/Drew-Robotics/2027beta/issues/14), which
carries the seam, the package rule, the gyro and battery decisions, the
log schema and the calibration-constants ruling; and superseded in part
by [#29](https://github.com/Drew-Robotics/2027beta/issues/29), which put
both loops on the SPARK and replaced #14's `SparkSim`-driven steer
simulation with a model of the onboard loop, sub-stepped at 1 kHz.
#14's own package name, `frc.robot.sim`, is written here as
`first.robot.sim`, which is what ADR 0003 settled the root package to
be.

The physics research, the maple-sim assessment and the benchmark are
[#8](https://github.com/Drew-Robotics/2027beta/issues/8) and
[`docs/research/physics-sim.md`](../research/physics-sim.md); the dyn4j
groundwork the physics map inherits is on #14. The vendor-sim baseline
and the "no seam" ruling this builds inside are
[#12](https://github.com/Drew-Robotics/2027beta/issues/12) and ADR 0003;
the loop placement that made the model necessary is ADR 0008; the log
schema it extends is ADR 0005; the pose signals it pairs with are
ADR 0012; the autonomous loop it closes is ADR 0011; the CI tiers it
un-dormants are
[#19](https://github.com/Drew-Robotics/2027beta/issues/19),
[#23](https://github.com/Drew-Robotics/2027beta/issues/23) and ADR 0013.
The gyro write discipline and the `SparkSim` class-loading chain are
[#5](https://github.com/Drew-Robotics/2027beta/issues/5) as narrowed by
[#25](https://github.com/Drew-Robotics/2027beta/issues/25).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java`,
`wpilibj/src/main/java/org/wpilib/simulation/SimDeviceSim.java`,
`wpilibj/src/main/java/org/wpilib/simulation/BatterySim.java`,
`wpilibj/src/main/java/org/wpilib/simulation/RoboRioSim.java`,
`wpilibj/src/main/java/org/wpilib/framework/IterativeRobotBase.java`,
`wpimath/src/main/java/org/wpilib/math/kinematics/SwerveDriveKinematics.java`,
`wpiutil/src/main/java/org/wpilib/util/Pair.java`,
`simulation/halsim_gui/src/main/native/cpp/DriverStationGui.cpp`.

In REVLib `2027.0.0-alpha-6` (sources jar):
`com/revrobotics/spark/SparkSim.java`,
`com/revrobotics/sim/SparkAnalogSensorSim.java`,
`com/revrobotics/sim/SparkRelativeEncoderSim.java`,
`com/revrobotics/sim/MovingAverageFilterSim.java`.

In Phoenix 6 `26.50.0-alpha-1` (sources jar):
`com/ctre/phoenix6/sim/Pigeon2SimState.java`,
`com/ctre/phoenix6/BaseStatusSignal.java`.
