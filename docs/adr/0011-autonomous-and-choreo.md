# ADR 0011 — Autonomous and Choreo integration

## Status

Accepted — 2026-08-26. Amended 2026-08-28 by the implementation, in two
places: the follow timeout's margin is settled under *the follower is
WPILib's own interface* rather than left open, and the field-centre flip
is written per sample rather than through
`HolonomicTrajectory.transformBy`, which turns out not to express it —
see the fourth entry under *Traps*.

Amended again 2026-08-28 by #78, which closed the origin question and
found this ADR wrong in two places while doing it. Field centre is
confirmed as the destination and every artifact that ships today is
still corner-origin, so **the load converts the frame** — see
*Trajectories arrive in the robot's frame*, which replaces *Trajectories
cache raw*. And the *Traps* entry on `Transform2d` described the
blue-corner flip as a reflection, which is the wrong flip for a
rotationally symmetric field and is not what ChoreoLib does; corrected
there.

Amended again 2026-08-30 by #76, which adds the side mirror beside the
alliance flip — see *The side mirror is a reflection, chosen by a
dashboard boolean*. The *Consequences* entry reading *"Autonomous is
selected on the Driver Station, not a dashboard"* is **narrowed there**
to the string-chooser finding it rests on, which has nothing to say
about a boolean. *No splits, no event markers* gains the rule that
every pose trigger reads through `toAuthoredPathFrame`, and its example is
corrected to show it — the example predated `toAuthoredPathFrame` and read a raw
estimate.

Amended by ADR 0012, which owns the pose
estimator: `Drive.getGyroOrientation()` returns a `Rotation3d` rather than a
`Rotation2d`, and the estimator beside `Drive` is
`SwerveDrivePoseEstimator3d`. Two statements below survive the widening
because ADR 0012 keeps them true deliberately rather than by luck —
*"`PoseEstimator` speaks `Pose2d`"* and `resetPose(Pose2d)`: the 3d
estimator's own `resetPose` takes a `Pose3d`, and our wrapper keeps the
2d signature and widens inside it. ADR 0012 also adds one method to
`Drive` — `maxAbsYawRate` — which is a query about the drive base's own
past and not a vision type; *"`Drive` never learns cameras exist"* still
holds.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Choreo
`[source]` claims are file-and-line readings of the Choreo repository
recorded in [`docs/research/choreo.md`](../research/choreo.md) and are
not re-read here. `[measured]` claims are the Pi runs in
[`docs/research/jvm-tuning.md`](../research/jvm-tuning.md). An
unqualified path is a file in this repo.

## Context

Autonomous means driving a planned path. In 2026 that was a solved
problem with a library on top of it: you drew the path in Choreo,
ChoreoLib loaded it, and `HolonomicDriveController` or
`SwerveControllerCommand` turned it into module commands.

None of those three exist for us. `SwerveControllerCommand` and
`HolonomicDriveController` were **deleted** from WPILib, with nothing
replacing them — no holonomic trajectory controller ships in 2027, and
WPILib's own flagship example writes its own. ChoreoLib has **no 2027
build**: the newest release targets WPILib 2026.1.1, and WPILib's
vendor compatibility matrix lists it ❌ for alpha-5 and alpha-6.
**[source — #6]**

What *does* exist is the other half. WPILib upstreamed Choreo's
trajectory model on purpose and says so: allwpilib issue #8160 proposes
*"upstream ChoreoLib's `Trajectory` class and `TrajectorySample`
interface/concept"*, and the design doc's stated non-goal is *"Replace
tools like Choreo and PathPlanner."* WPILib also vendors Sleipnir —
Choreo's own optimizer — with Java bindings. **[source — #6]**

So the question is not *which path library*. It is *which half of the
path library do we still need*, and the answer is: the half that draws
the path, which is the desktop tool, not the robot dependency.

## Decision

### Choreo authors the path. WPILib runs it. Nothing from ChoreoLib ships

Paths are drawn in the Choreo GUI and committed as the `.chor` project
plus its `.traj` files, under `src/main/deploy/choreo/` — where the
tool writes them, and which ADR 0003 already deploys. **[decided]**

At runtime the robot reads them into WPILib's
`HolonomicTrajectory`, from a directory resolved against
`Filesystem.getDeployDirectory()`
(`wpilibj/src/main/java/org/wpilib/system/Filesystem.java:57`)
**[source]**. **Not through
`HolonomicTrajectory.loadFromFile(String)`**
(`wpimath/src/main/java/org/wpilib/math/trajectory/HolonomicTrajectory.java:151`)
**[source]**: that deserialises WPILib's own JSON, and a `.traj` is not
it. The next section is the reason.

**There is no ChoreoLib vendordep, and there will not be one this
season.** See Rejected.

### Exactly one class in the robot knows Choreo exists

The two file formats carry the same numbers in different shapes.
Choreo writes flat doubles — `t, x, y, heading, vx, vy, omega, ax, ay,
alpha` — nested under `trajectory.samples`. **[source — #6]** WPILib
reads nested objects: `HolonomicSample` declares
`@Json.Property("pose") Pose2d`, `("velocity") ChassisVelocities` and
`("acceleration") ChassisAccelerations`
(`HolonomicSample.java:28-37`) **[source]**, and `duration` and the
interpolating sample map are `@Json.Ignore`
(`Trajectory.java:29, 32`) **[source]**, so the whole document is a
`samples` list.

The mapping is 1:1 with no arithmetic in it, and it lives in one class:

```java
public final class TrajectoryLoader {
  public TrajectoryLoader(Path deployDirectory) { ... }  // reads every .traj, once
  public HolonomicTrajectory get(String name) { ... }    // a lookup, never a read
}
```

`TrajectoryLoader` is the only file in the robot that names Choreo, or
reads a `.traj`, or knows what `alpha` means. `PathFollower` speaks
`ChassisVelocities`. `Drive` speaks `ChassisVelocities`.
`PoseEstimator` speaks `Pose2d`. **[decided]**

The loader **hard-fails on a schema version other than 3**, copying
Choreo's own behaviour — `loadTrajectoryString` throws a
`RuntimeException` if `version != 3` **[source — #6]**. A silently
drifted schema produces a subtly wrong path; a crash on the bench is
strictly better.

### The cache is eager, and `OpModeRobot` has no `robotInit`

Trajectories are read **once, at startup, into a cache on `Robot`**,
and never read again.

The number that decides this was measured. A 273 KB, 1000-sample
`HolonomicTrajectory` loaded on the loop thread 30 s into a warm run:

| trajectory handling at enable | pause |
|---|---|
| cold `loadFromFile` at enable | **63.32 ms** |
| classes warm, file re-read and re-parsed | 26.98 ms |
| **cached object reused** | **0.135 ms** |

**[measured — #31]** At a 5 ms period that is twelve iterations
swallowed in one gulp, inside autonomous. `loadFromFile` goes through
`Jsonb.instance()` (`HolonomicTrajectory.java:130`) **[source]**, so
the first call boots the entire avaje-jsonb machinery — which is why
the cold number dwarfs what a 273 KB read suggests.

**The standing rule this generalises to: a cache that exists to keep
work off a transition is populated eagerly.** **[decided — #31]** A
cache filled lazily on first use pays the full cost at exactly the
moment it was built to protect, and has bought nothing. This binds
every future cache, not just this one.

⚠️ **`OpModeRobot` has no `robotInit`.** Its overridable hooks are
`driverStationConnected()`, `robotPeriodic()`, `simulationInit()`,
`simulationPeriodic()`, `disabledInit()`, `disabledPeriodic()`,
`disabledExit()` and `nonePeriodic()` (`OpModeRobot.java:567-591`)
**[source]** — every one of them either periodic or tied to a mode
transition, and not one of them a once-at-startup init. The
`Robot` constructor is the equivalent, and it is where the cache is
filled — the same place ADR 0005 wires telemetry and ADR 0004
configures hardware.

Opmode constructors **never** load trajectories. ADR 0006 owns that
rule and ADR 0001 owns its reason: an opmode is constructed on every
Driver Station reselect, so anything expensive in its constructor is
paid again on every scroll of the dropdown.

### Pose lives beside `Drive`, not in it

`PoseEstimator` is a **plain class, not a `Mechanism`**, held as a
public field on `Robot` (`.../rebuiltcmdv3/Robot.java:27`)
**[source]**. What `Drive` exposes *for pose* is two accessors and
nothing else — `getGyroOrientation()` and `getModulePositions()`
(`.../rebuiltcmdv3/mechanisms/SwerveDrive.java:69, 78`) **[source]**.
It has no `getPose()`, no `resetPose()` and no estimator field. (The
flagship's `SwerveDrive` also exposes `getModuleVelocities()` (`:82`)
and its `kinematics` (`:33`) **[source]**; those are telemetry and
wiring, not pose.)

`robotPeriodic()` updates odometry **before** running the scheduler,
and the flagship comments the order because it matters
(`Robot.java:40-46`) **[source]**:

```java
@Override
public void robotPeriodic() {
  poseEstimator.odometryUpdate(drive.getGyroOrientation(), drive.getModulePositions());
  Scheduler.getDefault().run();
}
```

Reverse the two and every command in the loop reads a pose that is one
iteration stale.

**Why it is not a `Mechanism`, beyond copying the flagship:**
`Mechanism` exists in Commands v3 so a command can require *sole
ownership* of something. A pose estimator has no actuator and nothing
to own; making it a mechanism would let a command lock it, which means
nothing. It also keeps ADR 0012's vision seam out of `Drive` entirely —
vision calls `poseEstimator.visionUpdate(...)` and `Drive` never learns
cameras exist. **[decided]**

The flagship's `PoseEstimator` has **no reset method**, and autonomous
must seed pose at the start of a trajectory. Ours exposes
`SwerveDrivePoseEstimator.resetPose(Pose2d)`
(`wpimath/src/main/java/org/wpilib/math/estimator/PoseEstimator.java:132`)
**[source]**. The estimator itself is ADR 0012's.

### The follower is WPILib's own interface, with a timeout added

WPILib ships the seam it cut for us, as a deliberately-stubbed
interface whose javadoc reads *"No WPILib code exists for this; if you
want to follow paths on a real robot, use a third-party library
provided by the FRC programming community"*
(`.../rebuiltcmdv3/stubs/PathFollower.java:10-27`) **[source]**:

```java
public interface PathFollower {
  ChassisVelocities next();
  boolean isDone();
}
```

⚠️ **The interface does not say which frame `next()` is in.** Its
javadoc reads only *"Gets the next chassis velocities from the path"*
(`PathFollower.java:15-20`) **[source]**, and the word "field" appears
nowhere in the file **[executed]**. The frame is fixed by the only use
the flagship makes of it — feeding it to `driveFieldRelative`
(`SwerveDrive.java:225`) **[source]**. **Ours is field-relative**, and
because no type or javadoc enforces that, it is written here and in
Traps rather than left to be inferred. **[decided]**

We implement that shape, and we copy the flagship's command structure
with **one change**:

```java
public Command followPath(String pathName) {
  return run(coroutine -> {
        var follower = new HolonomicPathFollower(trajectories.apply(pathName), pose, PATH_FOLLOWER);
        coroutine.fork(driveFieldRelative(follower::next, follower::acceleration));
        var result = coroutine.waitUntil(follower::isDone, follower.timeout());
        telemetry.log("Following/TimedOut", result.timedOut());
      })
      .whenCanceled(this::stopModules)
      .named("Drive.FollowPath[" + pathName + "]");
}
```

Forking the existing `driveFieldRelative` reuses the kinematics path
already written and tested; a hand-rolled loop here would re-implement
the field→robot conversion (`SwerveDrive.java:197-200`) **[source]**
and the module-velocity call.

**Where the follower gets its two inputs is the obvious question, and
it is the reason `Drive` takes two constructor arguments it would not
otherwise need.** `trajectories` is a
`Function<String, HolonomicTrajectory>` — a lookup into `Robot`'s eager
cache, which is why nothing here reads a file. `pose` is a
`Supplier<Pose2d>`, a **read-only view** of the estimate.

That supplier is not a hole in the previous decision. `Drive` still
holds no estimator, still cannot reset pose, and still never learns
cameras exist; it can read where the robot is, which any closed-loop
follower must. Handing it the `PoseEstimator` itself would give it all
three. **[decided]**

**The change is the timeout overload**,
`Coroutine.waitUntil(BooleanSupplier, Time)`
(`commandsv3/src/main/java/org/wpilib/command3/Coroutine.java:717`)
**[source]**. The flagship uses the untimed form. A robot pinned
against a defender never reports `isDone`, and the untimed
`waitUntil` is documented to *"only return once the condition is met"*
(`:658-669`) **[source]** — so one stuck path hangs the entire
autonomous period with no recovery. **[decided]**

The clock starts inside `waitUntil` — `var timer = Timer.createStarted()`
(`:723`) **[source]** — not at the command's start, so the value is a
**margin over the trajectory's `duration`**, not an absolute. The margin
is **2 s**: the worst settling time the two committed paths take past
their own duration in simulation, rounded up. **[executed]** That figure
is dominated by the drive lagging its velocity setpoint with `kA`
configured zero, so it shrinks when ADR 0009 produces a `kA`.

`isDone` is not the clock alone. A follower that reports done the
instant `duration` elapses reports done for a robot held against a
defender at the start of the path, which makes the timeout unreachable
and hands the next command a robot nowhere near where it assumes. It is
the clock **and** arrival inside a position and heading tolerance, both
in the same config record as the gains. **[decided]**

The three PID gains the follower uses live in a **config record next to
the follower**, per ADR 0004 — not on `Drive`, because a second
follower (drive-to-point) wants different ones. Values are ADR 0009's.

### No splits, no event markers — a pose `Trigger` instead

`HolonomicTrajectory` supports neither: `grep -rn "splits\|EventMarker"`
over `wpimath/src/main/java/org/wpilib/math/trajectory/` returns
**zero hits** **[executed]**. Carrying them would mean inventing a
container of our own.

We use neither. **One path per file, composed in Commands v3.**
**[decided]** Event markers are a workaround for frameworks that cannot
express concurrency, and v3 can: `coroutine.fork`, `coroutine.await`,
and a pose trigger —

```java
public final Trigger inNeutralZone =
    new Trigger(() -> inZone(FieldConstants.toAuthoredPathFrame(poseEstimator.getEstimatedPose())));
```

A **pose**-triggered action beats a **time**-triggered one for the
reason that matters on a field: if the robot runs 300 ms late because a
wheel slipped, a time marker fires in the wrong place and a pose
trigger does not. Splits stop being necessary once each segment is its
own file.

⚠️ **Every pose trigger goes through `toAuthoredPathFrame`, and the threshold is
written against the path as drawn.** The estimate is where the robot
is; the threshold was read off Choreo. Compare the two raw and the
trigger fires at the wrong moment on any run the path was transformed
for. **Which way it goes is the threshold's sign, not a rule**:
`SweepLeftAuto`'s zone line is -4.27 m and the red path runs x +6.27 to
+2.77, so raw it is true on the *first loop*; a positive threshold on
the same path would never be reached. On a mirrored run a `y` threshold
fires on the wrong side of the field. Both transforms are their own
inverse and
`toAuthoredPathFrame` applies whichever are in force, so one call covers the
alliance flip and the side mirror together and will cover whatever is
added beside them.

This is a rule and not a mechanism. There is nothing to stop a trigger
reading `getEstimatedPose()` directly, and a wrong one throws nothing —
it fires early, late, or not at all. The only pose trigger that exists
reads through
`toAuthoredPathFrame`; the next one has to as well.

### Trajectories arrive in the robot's frame; the alliance flip happens at follower construction

The cache holds the trajectory **in the robot's own coordinate frame,
converted once at load**, and holds nothing about which alliance it is
for. The flip is applied when the `PathFollower` is constructed.
**[decided]**

The conversion is the origin and nothing else. Choreo emits blue-corner
coordinates and the robot works in field centre, so `TrajectoryLoader`
subtracts half the field's length and width from every sample pose as
it reshapes the file. It is a translation, so the heading, the velocity
and the acceleration all carry through untouched — which is what makes
it safe to do at load, where the flip is not. Field centre is where
2027 is going **[source]**, and when it arrives the two constants go to
zero and the conversion deletes.

Flipping at load is wrong, and the difference between the two is not
one of taste. A frame is a property of the file; an alliance is a
property of the match, and it is not known when the file is read. A
cached flip would bind the trajectory to whatever alliance was set the
first time it was read, so switching alliance for a test would need a
restart. The follower, by
contrast, is constructed inside `followPath`'s `run(coroutine -> …)`
body, which re-runs **every time the command is scheduled**. Change
alliance in the sim GUI → disable → enable → correct flip.

It is still one function and still never per-sample. A follower that
flips per sample is one that can be asked to flip *mid-path*.

⚠️ `MatchState.getAlliance()` returns `Optional<Alliance>` and is
**empty when no Driver Station is attached**
(`wpilibj/src/main/java/org/wpilib/driverstation/MatchState.java:43`)
**[source]**. Defaulting silently to blue is right on the bench and
wrong in a match. An empty `Optional` raises a `Level.HIGH` `Alert`,
per ADR 0004. `OpModeRobot.driverStationConnected()` advertises itself as the hook
for code *"needing the alliance information"*
(`OpModeRobot.java:565-567`) **[source]**, and **it is not that hook**:
it fires on the control word's DS-attached bit
(`OpModeRobot.java:617-619`) **[source]**, once, and the alliance
station arrives from the FMS some time after that. Reading the alliance
there can read `UNKNOWN`. **[executed, via #57]**

This ADR's decision is unaffected, and is what protects against it: the
flip happens at follower construction — after enable, by which point
the alliance has long arrived — rather than at load or at DS-connect.
The correction is to the parenthetical, not to the decision. ADR 0005
logs the alliance every loop for the same reason and raises this
alert whenever a DS is attached without one.

### The side mirror is a reflection, chosen by a dashboard boolean

An autonomous drawn for one side of the field runs on the other side
without a second opmode. The transform is a **reflection about the
field's long axis**, it sits beside the alliance flip in
`FieldConstants`, and it is applied at the same instant — follower
construction. **[decided]**

The two are different transforms and the difference is a sign, not a
convention. Under the field-centre origin the flip is a 180° rotation
about the origin and the mirror is a reflection across `y = 0`:

| | alliance flip | side mirror |
|---|---|---|
| x, y | `-x, -y` | `x, `**`-y`** |
| heading | `θ + π` | **`-θ`** |
| vx, vy | `-vx, -vy` | `vx, `**`-vy`** |
| ax, ay | `-ax, -ay` | `ax, `**`-ay`** |
| **ω, α** | **unchanged** | **negated** |

⚠️ **A rotation preserves handedness and a reflection does not.** A
mirror that copies the flip's sign convention tracks translation
perfectly and spins the wrong way, which on a path whose heading
changes reads as a tuning problem rather than as a sign error.
`FieldConstantsTest` pins each half of the table, and
`PathFollowingTest` drives the mirrored path end to end against the
drawn path's own tracking numbers — the heading bound is the one that
fails when `omega` carries through unmirrored.

**The two commute**, so nothing sequences them: `rot180 ∘ reflect` and
`reflect ∘ rot180` are both `diag(-1, 1)`, and the heading composes to
`-θ + π` either way. A test asserts it so the ordering cannot quietly
become necessary. Each is also its own inverse, so `toAuthoredPathFrame`
undoes both by applying whichever are in force again — a pose threshold
written against the drawn path is compared in the frame it was written
in on either side of the field, the same reason it already was on red.

⚠️ `toAuthoredPathFrame` reads the toggle **every loop**, where the trajectory
reads it once at follower construction, so toggling mid-autonomous puts
a pose trigger in a frame the path being driven is not in. That is the
shape the alliance already has and nobody can change the alliance
mid-match; a dashboard boolean an operator can reach at any instant is
the new part. Not worth a snapshot: the window is the fifteen seconds
nobody is on a dashboard.

The side is a `Tunables.addBoolean("Mirrored", false)`, and **false is
the path exactly as it was drawn** — the state a robot boots into.
`TunableBoolean` implements `BooleanSupplier` and `RobotBase` already
registers `NetworkTablesTunableBackend(inst, "/Tunables")`
(`RobotBase.java:237`) **[source]**, so no backend wiring is ours.
ADR 0005 logs it every loop beside `Alliance` and it raises a `LOW`
alert while armed: a non-default state that decides which half of the
field the robot drives at belongs in front of the operator before the
match, not reconstructed from the log after it.

**This narrows the *Consequences* entry below**, which reads
*"Autonomous is selected on the Driver Station, not a dashboard."* The
finding under that sentence is about **strings**: AdvantageScope cannot
write string NT values at all, and Elastic's support is an open,
conflicted PR (`docs/research/choreo.md:129-133`, matrix at
`:1337-1350`) **[source — #6]**. That rules out a string chooser and
says the opposite about a boolean — AdvantageScope's tuner is
**number/boolean only**: `LiveDataTuner.ts` declares `publish(key:
string, value: number | boolean)` and `NT4Tuner.ts` gates on
`LoggableType.Number || LoggableType.Boolean`
(`docs/research/choreo.md:1344-1346`) **[source — #6]**. A boolean is
the one shape the objection excludes rather than the one it covers.

The routine stays on the Driver Station selector, which is what it is
for; the side is a *modifier* on the selected routine, and putting it
on the selector is combinatorial — four side-symmetric autos become
eight entries an operator reads under time pressure to pick something
that is not a different routine.

One global boolean, not one per auto: a boolean per auto clutters a
dashboard the way an entry per auto would have cluttered the Driver
Station. The opmode's `@Autonomous(description = …)` names the side the
path was drawn for, so the operator reads the pair together.

⚠️ **The type is writable; the mode may not be.** The same reading
records that `hasTunableFields()` returns `false` for
`NT4Mode.Systemcore` and `NT4Mode.DriverStation` outright
(`docs/research/choreo.md:1346-1347`) **[source — #6]** — which is the
mode a real robot is in, and it gates *every* tunable rather than the
string. Glass/SimGUI writes tune topics and is the dashboard this is
known to work from. See *Open*.

### `kA` is in, at drivebase level, and only on the auto path

The follower passes an acceleration feedforward: `kA · a` in volts,
handed to the drive SPARK as `arbFeedforward` alongside the velocity
setpoint — `setSetpoint(setpoint, ctrl, slot, arbFeedforward,
ArbFFUnits)` exists in REVLib 2027, so no profiled control mode is
needed to apply it. **[source, via #32 — REVLib 2027 sources; not
re-read here]** **[decided — #32]**

**The acceleration is known, not derived.** It is
`sample.acceleration`, a `ChassisAccelerations` the trajectory
generator computed from a model of our drivetrain
(`HolonomicSample.java:36-37`) **[source]**. That is the whole
argument: differentiating a noisy velocity signal to synthesise
acceleration is what makes motor-level `kA` rarely worth its
performance, and here nothing is differentiated.

Both would land as `arbFeedforward` volts at the module. The
difference is where the acceleration comes from, and only one of the
two sources is trustworthy.

**So `kA` rides the path-following path and nowhere else. Teleop passes
no `arbFeedforward` at all.** A driver's stick position is a velocity
request with no acceleration attached; there is nothing to feed
forward. This also means `kS`/`kV` stay on the SPARK in
`FeedForwardConfig` at 1 kHz (ADR 0008) while `kA` runs on SystemCore
at 200 Hz, because it has nowhere else to go — the on-SPARK
`FeedForwardConfig.kA` applies only in MAXMotion modes and drive takes
no profile.

Exactly one home per term. See Traps.

⚠️ Converting `ChassisAccelerations` to per-module accelerations is
where this decision meets a trap that is live rather than theoretical.
See Traps, first entry.

The `kA` **number** does not exist yet; SysId's dynamic test has to
produce it. ADR 0009 owns that.

## Consequences

- **The log decomposes pose error into along-track and cross-track, not
  x and y.** This is the diagnostic the whole autonomous log exists
  for. Raw x/y error cannot distinguish *"on the path but running
  late"* from *"on time but a metre left"*, and those have completely
  different causes — traction and velocity gains for the first, heading
  and steering for the second. Three lines rotate the pose error into
  the sample's heading frame. **[decided — #15]** ADR 0005's
  `/Drive/Following` signals are amended accordingly:

  ```
  /Drive/Following/{Setpoint,AlongTrackError,CrossTrackError,HeadingError,TimedOut}
  /Auto/{RoutineName,PlannedPath,TimeElapsed}
  ```

  `Setpoint` is one signal rather than a separate target pose and
  target velocity — the **whole commanded state in one call**: `HolonomicSample` is
  `StructSerializable` with schema `"double time;Pose2d
  pose;ChassisVelocities velocity;ChassisAccelerations acceleration"`
  (`.../trajectory/struct/HolonomicSampleStruct.java:34-36`)
  **[source]**. One signal instead of two, and it carries the
  **acceleration** for free — which is how #15's *log
  `sample.acceleration` from day one* is satisfied without a signal
  anyone has to remember to add. `/Auto/PlannedPath` is a `Pose2d[]`
  written once at trajectory start, for the AdvantageScope field
  overlay.

- **The whole autonomous loop closes in plain JUnit.** ADR 0010's sim
  is a pure function of module voltages with no vendor types, no HAL
  and no `RobotBase`, so follower → `ChassisVelocities` → kinematics →
  module voltages → sim → pose → back into the follower runs as a unit
  test. *"Does this auto actually get driven?"* is a CI gate, asserting
  **max cross-track error under a threshold** and completion within
  duration plus margin. That assertion and the headline field
  diagnostic above are **the same number**, so a regression looks
  identical in CI and on the field. ADR 0013 owns the tier.

- **`arbFeedforward` is no longer unused, and ADR 0008's Rejected
  section is narrowed to what it actually rejected** — `arbFeedforward`
  as the home for `kS` and `kV`. That still stands. `kA` is a different
  term with a different unit and, now, a different home.

- **If ChoreoLib 2027 ships, one file changes.** `TrajectoryLoader` is
  the entire Choreo surface. The same is true if Choreo starts emitting
  WPILib-shaped JSON directly, which upstream's stated intent makes
  plausible.

- **Every path gets regenerated when the 2027 field map lands, and the
  frame conversion deletes with them.** The `.traj` schema is version 3
  today and a coordinate-origin change would almost certainly bump it —
  which is the signal, since `TrajectoryLoader` already refuses any
  other version. Plan on it rather than being surprised by it. The `.chor` is the source of truth; a regenerated
  `.traj` should be a reviewable diff, which is why both file kinds are
  committed.

- **A missing or malformed `.traj` kills `Robot`'s constructor.** That
  is the intended behaviour and the reason the cache is eager for a
  second time: the failure lands while a student is standing at the
  bench, not three seconds into a match. Choreo's own loader swallows
  every failure into `Optional.empty()` **[source — #6]**; ours throws,
  and it throws on the empty sample list a path added in the GUI and
  never generated leaves behind, which parses clean and follows in
  zero seconds.

- **Trajectory hot-reload in simulation is a re-read, not a clear.**
  ChoreoLib empties its cache under `RobotBase.isSimulation()` and
  fills it again on the next lookup, which is the one genuinely good
  idea in its source worth keeping. Ours is eager, so emptying it
  leaves every lookup throwing: the simulation version re-reads the
  directory rather than clearing it. Nothing asks for it yet, and it
  costs nothing on the robot.

- **ADR 0009 owes a `kA` figure.** SysId's dynamic test is the only
  source for it, and until it exists the follower's acceleration term
  is configured with zero — which is the current behaviour with the
  term present and inert, not a different code path.

- **The autonomous *routine* is selected on the Driver Station, not a
  dashboard.** `SendableChooser` is deleted, AdvantageScope's tuner
  cannot write string values at all, and Elastic's support is an open,
  conflicted PR. **[source — #6]** The DS opmode selector is the path,
  and it is ADR 0001's decision; autonomous inherits it and adds
  nothing. The finding is about **strings**, so it does not reach a
  boolean *modifier* on the selected routine: the side mirror is a
  tunable boolean — see *The side mirror is a reflection, chosen by a
  dashboard boolean*.

## Traps

- **`ChassisAccelerations.toWheelAccelerations()` hardcodes ω = 0 and
  silently drops the centripetal term.**

  ```java
  public SwerveModuleAcceleration[] toWheelAccelerations(
      ChassisAccelerations chassisAccelerations) {
    return toSwerveModuleAccelerations(chassisAccelerations, 0.0);   // omega hardcoded
  }
  ```

  (`wpimath/src/main/java/org/wpilib/math/kinematics/SwerveDriveKinematics.java:557-559`)
  **[source]** The method with the obvious name is the broken one. The
  dropped term **dominates during rotation** — which is exactly the
  manoeuvre where an acceleration feedforward earns its keep, so this
  is not a corner case for us, it is the main case. It compiles, it
  runs, and it is quietly wrong in proportion to how fast the robot is
  spinning.

  **Always the 2-argument form**,
  `toSwerveModuleAccelerations(accelerations, angularVelocity)`
  (`:551-553`) **[source]**, with the angular velocity from the same
  sample.

- **`waitUntil` with a timeout returns a value; it does not throw.**
  `WaitResult` is an enum of `CONDITION_MET` and `TIMED_OUT`
  (`Coroutine.java:633-655`) **[source]**, and the timed-out path looks
  identical to the finished path unless the return is read. A path that
  quietly timed out and one that completed produce the same log,
  the same next command and the same driver experience — until the
  robot is a metre short of where the next command assumes it is.
  **Read the result and log it.**

- **The follower's velocities are FIELD-relative.** `PathFollower.nextFieldRelativeVelocities()`
  is documented as such, and `driveFieldRelative` does the conversion
  with `toRobotRelative(getGyroOrientation())`
  (`SwerveDrive.java:197-200`) **[source]**. A follower that returns
  robot-relative velocities compiles, runs, and drives a path that is
  correct only while the robot faces field-forward. The symptom is a
  path that tracks perfectly on a straight run and diverges the moment
  the robot rotates.

- **`transformBy` does not express the flip, and the corner-origin form
  of it is a rotation rather than a reflection.** Written about a
  corner the flip is `x → length − x`, `y → width − y`, heading
  `→ θ + π` — the same rotation as the field-centre form, about a point
  that is not the origin. A **reflection** (`x → length − x`, heading
  `→ π − heading`) is a different flip, correct only for a
  mirror-symmetric field, and it is not what the 2026 field or
  ChoreoLib use — `Flipper.FRC_CURRENT` is `rotatedAround(FIELD_LENGTH,
  FIELD_WIDTH)` **[source]**. Reaching for the reflection gives a path
  that is plausibly shaped, wrong, and produces no error. We do not
  write either corner form: the frame is converted at load and the flip
  stays the field-centre one.

  `HolonomicTrajectory.transformBy(Transform2d)` does not save the
  field-centre case: it is rigid **about the trajectory's own first
  pose** rather than about the origin —
  `firstPose.transformBy(transform)`, then every later sample by
  `transformedFirstPose.plus(sample.pose.minus(firstPose))`
  (`HolonomicTrajectory.java:67-81`) **[source]** — and it carries
  `sample.velocity` and `sample.acceleration` through **unrotated**
  (`:72, 82-83`) **[source]**. Under a 180° flip that leaves every velocity
  pointing the way it did before, which compiles, runs, and drives the
  mirrored path backwards. The flip is written per sample instead. See
  Open.

- **A `kV` in `FeedForwardConfig` and a `kV·v` term in
  `arbFeedforward` double the feedforward, and nothing throws.** The
  units differ — Volts-per-velocity against Volts — so no type catches
  it, and the symptom reads as a gain that needs lowering rather than a
  term that needs deleting. Now that `arbFeedforward` carries `kA · a`
  on the auto path, that argument is a live place for a `kV` term to be
  added by someone who does not know `FeedForwardConfig` already has
  one. **Exactly one home per term**: `kS` and `kV` in
  `FeedForwardConfig` on the SPARK, `kA` in `arbFeedforward` from the
  follower, and nowhere else. ADR 0008 owns the first half.

- **Do not set `setCancelOnForkFailure(false)`.** `fork` returns a
  `ForkResult` that can report failure (`Coroutine.java:360`)
  **[source]**, and the flagship ignores it — which is safe only
  because the default is to cancel: `m_cancelOnForkFailure = true`
  (`Coroutine.java:30`) **[source]**. With it disabled, a
  `driveFieldRelative` that fails to fork leaves `waitUntil` spinning
  on a follower nothing is driving, and the whole autonomous period is
  spent waiting for the timeout.

- **A forked command dies with its parent, and `finally` never runs.**
  *"If one command schedules another (a 'parent' and 'child'), the
  child command will be canceled when the parent command completes. It
  is not possible to fork a child command and have it live longer than
  its parent"* (`Scheduler.java:529-531`) **[source]**, enforced by
  `removeOrphanedChildren` (`:1117`) **[source]**. That is what makes the timeout
  safe: when `followPath` gives up, the forked drive command is
  cancelled with it and the robot stops. But v3 cancellation is not an
  exception unwind — any cleanup belongs in `whenCanceled()`, and a
  `finally` block written around the fork will never execute. ADR 0006
  owns the rule.

- **`for(;;)` in a coroutine body compiles clean and hangs the robot.**
  The compiler plugin's missing-`yield` check overrides
  `visitWhileLoop` and nothing else
  (`javacPlugin/src/main/java/org/wpilib/javacplugin/CoroutineYieldInLoopDetector.java:175`)
  **[source]** — there is no `visitForLoop`, `visitEnhancedForLoop` or
  `visitDoWhileLoop` in the file **[executed]**. A follower loop is the
  natural place for someone to write one.
  ADR 0006 owns this; it is repeated here because autonomous is where
  loops in coroutine bodies actually get written.

## Open

- **When the field origin moves, and whether it moves under the 2026
  layouts too.** *That* it moves is settled: field centre is where 2027
  is going, from Peter Johnson in the WPILib Discord on 2026-04-11.
  **[source — #78]** Nothing that ships today is in it — the `fields`
  module still documents *"the origin at the bottom-right corner of the
  blue alliance wall"*
  (`fields/src/main/java/org/wpilib/fields/Field.java:32-33`),
  `OriginPosition` offers only `BLUE_ALLIANCE_WALL_RIGHT_SIDE` and
  `RED_ALLIANCE_WALL_RIGHT_SIDE` (`:43-48`) **[source]**, and Choreo's
  editor still nails its canvas to a corner **[source — #78]**.

  Nothing on the public tracker records the change: no issue, no PR, no
  milestone entry, no docs branch. **That is silence, not doubt** —
  re-running the search and reading it as absence is how #78 first got
  this backwards.

  So the robot works in field centre now and converts what it reads,
  and this is no longer a decision waiting on an event. What is left is
  timing, and one live hazard: **whether the 2026 layouts are
  retroactively converted** was asked in the same exchange and answered
  *"probably a good idea? I think?"*, with nothing since.
  **[unverified]** The field JSON declares no origin at all, so a
  retroactive conversion moves every tag by half a field with no schema
  bump behind it and an offset applied twice looks like a robot that is
  merely confident. `.traj` at least has the version-3 check
  `TrajectoryLoader` already fails on; the layout has nothing. ADR 0012
  owns the check that catches it.

  *Unblocked by* the 2027 field release and its AprilTag map, at which
  point both conversions go to zero and the flip is re-verified against
  the real field rather than against a frame we translated into.

- **Whether the converter is throwaway or permanent.** If ChoreoLib's
  `Trajectory` eventually **extends** `org.wpilib.math.trajectory.Trajectory`
  rather than merely borrowing the sample shape, `TrajectoryLoader`
  deletes. If it only borrows the shape, the converter stays for as
  long as we use Choreo. **[unverified]** *Unblocked by* the v3-native
  ChoreoLib port landing. Nothing depends on the answer today.

- **Whether the dashboard in the pit can write the `Mirrored` boolean
  on SystemCore.** The *type* is settled: AdvantageScope's tuner takes
  number and boolean, which is why the string finding does not reach
  this. The *mode* is not: `hasTunableFields()` returns `false` for
  `NT4Mode.Systemcore` outright, which gates every tunable and not just
  the string, and that reading is of AdvantageScope's source rather
  than of a robot anyone has tuned. **[unverified]** *Unblocked by*
  writing the boolean from the dashboard that will be in the pit,
  before a match depends on it. Glass/SimGUI is the fallback and the
  one this is known to work from.

- **Whether the `Mirrored` toggle should survive a reboot.** A tunable
  that persists carries a mirror left on from practice into a match;
  one that does not makes the operator set it every boot.
  `NetworkTablesTunableBackend` sets no persistent option on what it
  publishes and only *subscribes* to the `tune` topic
  (`NetworkTablesTunableBackend.java:127-132`) **[source]**, so
  persistence would have to come from the writing dashboard plus
  `networktables.json` — which nobody has checked. **[unverified]**

- **`kA` has no number.** ADR 0009 owns producing it. Until then the
  term is present and configured zero.

- **Match-time selection is unverifiable.** #6 records that simulation
  does not currently work with the real 2027 Driver Station, so the
  JUnit gate validates the **follower math** and not the selection
  flow. *Unblocked by* the DS/sim story settling; ADR 0013 tracks it.

  The same gap now bounds the transforms. A JUnit run loads no HAL
  simulation extension, and `DriverStationSim.setAllianceStationId`
  **segfaults the JVM** in `HALSIM_SetDriverStationAllianceStationId`
  when it is called there **[measured — #76]**, so no test can put
  itself on red. Each transform is pinned on its own and the pair is
  pinned by the commute and involution tests; *red and mirrored at
  once* is argued rather than executed.

## Rejected

### ChoreoLib as a dependency

Three independent reasons, any one of which is sufficient.

**There is no 2027 build.** The newest release is v2026.0.3
(2026-04-06), targeting WPILib 2026.1.1. The `ChoreoLib2027Alpha.json`
vendordep resolves but declares `"frcYear": "2027_alpha1"` — a June
2025 build against alpha-1, `edu.wpi.first` group IDs and Java 17,
which will not link against current alphas. WPILib's own vendor
compatibility matrix lists ChoreoLib ❌ for alpha-5 and alpha-6.
**[source — #6]**

**The build that is in flight is Commands v2 only, and wrapping it is
unsafe.** The transitional branch depends on
`org.wpilib.commandsv2:commandsv2-java` — a namespace migration, not a
v3 port. There is **no bridge between `org.wpilib.command2` and
`org.wpilib.command3`**; neither package references the other. A v2
`Subsystem` requirement and a v3 `Mechanism` requirement do not
interlock, so two schedulers would drive the same motors with **no
mutual exclusion** — destroying the single guarantee command-based
frameworks exist to provide. **[source — #6]**

**The maintainer says not to.** *"The library we release for this
transition will not represent our planned ChoreoLib API for 2027. Do
not invest time in preparing 2027 robot code templates using this
version."* **[source — #6]**

*Do not re-raise* until a v3-native ChoreoLib is published against a
released 2027 WPILib. When that happens the change is one file.

### PathPlanner

Real gains: on-the-fly path generation and AD\* pathfinding, neither of
which Choreo has, plus a vendordep that works against alpha-3 today.

Rejected because the readiness gap is narrower than the vendor matrix
suggests and points the wrong way. PathPlanner has **no stated 2027
roadmap** (issue #1173, zero comments), **no Commands v3 support**
(issue #1177, zero comments), and its `2027` branch has been stalled
since 2026-07-02. **[source — #6]** For a project locked to Commands
v3, Choreo is the only one of the two that has publicly committed to
supporting us.

The `PathFollower` seam keeps PathPlanner a drop-in if that ever
changes: it satisfies the same two methods.

### A build-time converter — a Gradle task emitting WPILib JSON

It would move the conversion off the robot entirely. Rejected on two
counts: it means editing the stock template, which ADR 0003 ruled
against, and it puts a generated artifact next to its source in the
deploy directory, where the next person cannot tell which of the two
files is authoritative. The runtime converter also buys simulation
hot-reload, which a build-time one cannot.

### Event markers and splits

Choreo emits both, and WPILib's trajectory model carries neither, so
supporting them means inventing our own container and threading it
through the converter and the follower. The functionality is
recoverable for free — `coroutine.fork` and a pose `Trigger` — and the
pose version is **better**, not merely equivalent, because it stays
correct when the robot runs late. This deletes a whole feature from the
converter rather than deferring it.

### Flipping the trajectory at load, or per sample

Both covered at the Decision: construction time is the only point that
is simultaneously once-per-run and re-evaluated on every schedule.

### Motor-level `kA`

Differentiating a per-module velocity setpoint to synthesise
acceleration. Rejected: acceleration is a numerical derivative of a
noisy signal, and it rarely earns its performance. **[decided — #32]**
The drivebase-level term is accepted precisely because the trajectory
already carries the acceleration and nothing is differentiated.

*Do not re-raise* without evidence that drivebase-level `kA` is
tracking well and the residual error is per-module.

### `getPose()` and `resetPose()` on the drive mechanism

#6 proposed exactly this, and the flagship does the opposite. Rejected
on the reason under Decision: a pose estimator has nothing to own, so
it is not a mechanism, and putting pose on `Drive` would drag ADR
0012's vision seam into the drive base with it. `Drive` exposing
`getGyroOrientation()` and `getModulePositions()` and nothing else is what
keeps both autonomous and vision out of it.

### Module-force feedforward

Choreo's samples carry per-module forces (`fx`/`fy`, four each, in
newtons) and ChoreoLib's own swerve example ignores them, punting
wheel-force feedforward to CTRE-specific APIs. **[source — #6]** We are
on REV SPARKs; no vendor path exists. Not rejected on merit — there is
simply nothing to call. Revisit only if REV ships an equivalent.

## Source

Decided across
[#6](https://github.com/Drew-Robotics/2027beta/issues/6), which
establishes that no 2027 ChoreoLib exists, that WPILib upstreamed the
trajectory model deliberately, and that a `.traj` will not load
directly;
[#15](https://github.com/Drew-Robotics/2027beta/issues/15), which
settles what the drive base exposes, the `PathFollower` shape and the
`waitUntil` timeout, the no-splits ruling, the flip point, and the
along-track/cross-track diagnostic; and
[#31](https://github.com/Drew-Robotics/2027beta/issues/31), whose
measurement of the enable-transition pause produces the eager-cache
rule.

[#32](https://github.com/Drew-Robotics/2027beta/issues/32) amends
#15 on acceleration feedforward: `kA` returns at drivebase level, on
the auto path only, and makes `toWheelAccelerations`' hardcoded ω = 0 a
live constraint rather than a general hazard. The opmode lifecycle it
leans on is [#17](https://github.com/Drew-Robotics/2027beta/issues/17)
and ADR 0001; the config convention for the follower's gains is
[#13](https://github.com/Drew-Robotics/2027beta/issues/13) and
ADR 0004; the log rules are
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and
ADR 0005; the sim that closes the loop in JUnit is
[#14](https://github.com/Drew-Robotics/2027beta/issues/14) and
ADR 0010; the vision half of the origin question is
[#20](https://github.com/Drew-Robotics/2027beta/issues/20) and
ADR 0012; `kA`'s value and the SysId run that produces it are
[#32](https://github.com/Drew-Robotics/2027beta/issues/32) and
ADR 0009.

Research: [`docs/research/choreo.md`](../research/choreo.md),
[`docs/research/wpilib-swerve.md`](../research/wpilib-swerve.md),
[`docs/research/jvm-tuning.md`](../research/jvm-tuning.md),
[`docs/research/commands-v3.md`](../research/commands-v3.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpimath/src/main/java/org/wpilib/math/trajectory/HolonomicTrajectory.java`,
`wpimath/src/main/java/org/wpilib/math/trajectory/HolonomicSample.java`,
`wpimath/src/main/java/org/wpilib/math/trajectory/Trajectory.java`,
`wpimath/src/main/java/org/wpilib/math/trajectory/struct/HolonomicSampleStruct.java`,
`wpimath/src/main/java/org/wpilib/math/kinematics/SwerveDriveKinematics.java`,
`wpimath/src/main/java/org/wpilib/math/kinematics/ChassisAccelerations.java`,
`wpimath/src/main/java/org/wpilib/math/estimator/PoseEstimator.java`,
`commandsv3/src/main/java/org/wpilib/command3/Coroutine.java`,
`wpilibj/src/main/java/org/wpilib/driverstation/MatchState.java`,
`wpilibj/src/main/java/org/wpilib/system/Filesystem.java`,
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`fields/src/main/java/org/wpilib/fields/Field.java`,
and the flagship example
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`
(`Robot.java`, `mechanisms/SwerveDrive.java`, `stubs/PathFollower.java`,
`opmodes/auto/SweepAuto.java`).
