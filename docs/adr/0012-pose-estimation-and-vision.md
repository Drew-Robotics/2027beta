# ADR 0012 — Pose estimation and the vision seam

## Status

Accepted — 2026-08-26.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Phoenix 6
`[source]` claims were read in
`wpiapi-java-26.50.0-alpha-1-sources.jar` from
`maven.ctr-electronics.com`, the version the
`Phoenix6-26.50.0-alpha-1.json` vendordep pins. `[field]` claims are
the eleven team-seasons surveyed in
[`docs/research/vision-filtering.md`](../research/vision-filtering.md).
An unqualified path is a file in this repo.

⚠️ **#20 and #27 were both decided without a Phoenix 6 jar on the box**,
and each left items marked unverified for that reason: one from #20
(does the Pigeon2 expose a full 3d rotation) and two from #27 (the
`AngularVelocityZWorld` default update frequency, and whether
`Pigeon2SimState` can drive it at all). The jar is present now, and all
three are resolved below. None of them changed a decision — one of them
changed a *number*, which is why the resolutions are written into
Decision rather than left in Open.

## Context

We want full-field pose estimation. We do not want to write a vision
subsystem, because the vision implementation is the part that changes
every September: different cameras, different pipeline, different game,
different tag layout. What survives the season rollover is the drive
base.

So the decision is about the **seam**, not about vision. Vision is an
external sensor that hands us pose measurements, and the question is
what the drive base has to expose for that to work — and, more usefully,
how little.

Two research passes answered the surrounding questions before this ADR
was written, and both narrowed it. Reading five teams across eleven
team-seasons found that **every gate teams actually apply needs camera
or pipeline data** — `tagCount`, ambiguity, tag distance, tag area,
MT1-vs-MT2 provenance, the tag ID list, and in one case the position of
an intake. **[field]** None of it crosses a seam that carries a pose, an
instant and four numbers. Filtering is therefore a season concern, and
this ADR does not build one.

The same pass found exactly one gate that is not camera-specific: *was
the robot spinning when the shutter opened?* That one needs drive-base
state, and it needs it **in the past**. It is the entire reason the
drive base's vision-facing surface is one method instead of zero.

## Decision

### The seam is one method on a class that already exists upstream

```java
void visionUpdate(Pose3d measurement, double timestamp, Matrix<N4, N1> stdDevs)
```

on `PoseEstimator` — the plain class ADR 0011 already put beside
`Drive` rather than inside it. The flagship's version
(`.../rebuiltcmdv3/PoseEstimator.java:45`) **[source]** is this method
already, with a 2d pose and no std devs; we take it with one addition
and one widening.

**There is no vision abstraction in this repo.** No `VisionSource`
interface, no `VisionMeasurement` record, no vision type of any kind. A
season's vision class holds a reference to `PoseEstimator` and calls
that method. Unplugging vision next September is **deleting that
class**, and the drive base never knew it existed. **[decided]**

`Drive` is untouched by this. It exposes `getGyroHeading()` and
`getModulePositions()` for pose, exactly as ADR 0011 left it, and
nothing was added to it *for vision* — see the yaw-rate section below
for the one method that looks like an exception and is not.

### Std devs are mandatory on every call

Only the 3-argument form exists on our seam. This is not politeness
about confidence; it closes a hole.

WPILib's 3-argument `addVisionMeasurement` is **sticky**. It sets the
std devs and never restores them:

```java
public void addVisionMeasurement(
    Pose3d visionRobotPose, double timestamp, Matrix<N4, N1> visionMeasurementStdDevs) {
  setVisionMeasurementStdDevs(visionMeasurementStdDevs);
  addVisionMeasurement(visionRobotPose, timestamp);
}
```

(`wpimath/src/main/java/org/wpilib/math/estimator/PoseEstimator3d.java:365-368`)
**[source]**, and its own javadoc says *"the vision measurement standard
deviations passed into this method will continue to apply to future
measurements until a subsequent call"* (`:351-353`) **[source]**.
Mix the 2-argument and 3-argument forms and whichever per-measurement
value happened to land last becomes the global default for every
measurement after it — silently, and with no way to read the state back.

Requiring std devs on every call makes that state **unobservable by
construction** rather than avoided by discipline: the next call always
overwrites before use. It costs nothing, and it pushes per-measurement
confidence — the thing that actually changes when the cameras and the
pipeline change — onto the season side, where it belongs. **[decided]**

Rejection is expressed the same way. Every source that wants to discard
a component inflates σ rather than dropping the measurement: 6328 use
`Double.POSITIVE_INFINITY` for σ_θ on single-tag frames and 254 `1e6`
**[field]**; PhotonVision uses `Double.MAX_VALUE` beyond 4 m and
Limelight's docs `9999999` **[source — #26]**. `σ = ∞` is safe against
2027's arithmetic — `r = σ²`, `k = q/(q + √(q·r))`, guarded on `q == 0`
(`PoseEstimator3d.java:100-119`) **[source]** — so it gives a gain of
exactly zero, not a NaN. The gain is per-axis with no covariance
anywhere in the implementation, so **a seam that carries only std devs
is more expressive than one with a separate reject path**, not less: a
boolean cannot say *trust the translation, ignore the rotation*.

### 3d wherever possible, 2d only where required

`SwerveDrivePoseEstimator3d`, not the 2d variant. **[decided]**

`Odometry3d.update` takes a `Rotation3d`, builds a `Twist3d` with
`dz = 0` in the **robot** frame, and `.exp()`s it into the world
(`wpimath/src/main/java/org/wpilib/math/kinematics/Odometry3d.java:119-132`)
**[source]** — so a pitched robot gets its wheel travel
cosine-projected instead of over-counted. A real gain on a ramp, and
exactly zero on flat carpet. That is the shape of the whole decision:
3d costs nothing when the floor is flat, and is already correct when it
is not.

Four consequences, all of them mechanical:

- **`Drive.getGyroHeading()` returns `Rotation3d`** — an amendment to
  ADR 0011, which had it as `Rotation2d`. ⚠️ #20 flagged this as
  assuming a full 3d rotation from the Pigeon2 and could not check.
  **It is there**: `Pigeon2.getRotation3d()` returns
  `new Rotation3d(getQuaternion())` (`Pigeon2.java:252-253`)
  **[source — Phoenix 6]**, over the four quaternion signals.
- **`getEstimatedPose()` flattens once, internally, to `Pose2d`**, with
  `getEstimatedPose3d()` alongside it for the log. Every consumer we
  have is 2d — ADR 0011's follower, its along-track/cross-track
  decomposition, `HolonomicSample`. One `.toPose2d()` inside the
  estimator is cheap; *N* of them scattered across consumers is a
  convention nobody maintains.
- **Std devs become `Matrix<N4,N1>`** — `[x, y, z, θ]`. The defaults are
  `0.1` for every state term and `0.9` for every vision term
  (`SwerveDrivePoseEstimator3d.java:63-64`) **[source]**. We do not use
  the defaulting constructor; the vision σ is caller-supplied on every
  call and the state σ is ours, at construction. See Traps.
- **`resetPose` stays 2d at our seam, and widens inside.**
  `PoseEstimator3d.resetPose` takes a `Pose3d` (`:145`) **[source]**
  where the 2d class takes a `Pose2d`
  (`PoseEstimator.java:132`) **[source]**. Our wrapper keeps the 2d
  signature — ADR 0011's only caller seeds from a trajectory's initial
  pose, which is a `Pose2d`, and there is no z or tilt to declare when
  the robot is sitting on the carpet. So `resetPose(Pose2d)` widens with
  a zero z, roll and pitch, and resets **both** estimators. **[decided]**
  Widening at the seam rather than at the call site is what keeps ADR
  0011's *"`PoseEstimator` speaks `Pose2d`"* true for every consumer.

### Two estimators, and both reset together

Two `SwerveDrivePoseEstimator3d` instances, ticked with identical gyro
and module positions. **Only one of them is ever told about vision.**
**[decided]**

The estimator keeps its odometry private, so *"where would we be without
vision"* is not recoverable from the fused estimate alone. Two instances
of the same class beats pairing an estimator with a bare
`SwerveDriveOdometry3d`: it is symmetric, and it reads correctly to a
student as *same object, one of them gets told about vision*.

**Both are reset together, on every reset.** Leaving the odometry-only
one un-reset is the tempting version — see Consequences for why it is
wrong.

### No vision at all is the normal state

**Nothing.** No flag, no alert, no configuration, no branch.
**[decided]**

An estimator that receives no vision updates *is* odometry, exactly. A
season, a practice session and simulation all work untouched. There is
no vision code in this repo, so vision-absent is not a fault condition —
it is Tuesday. An `Alert` that fires every single session is an alert
people learn to scroll past, which costs more than it buys.

A season that adds vision adds its own staleness alert, where it knows
how often frames are supposed to arrive.

### One vision-facing method on the drive base

```java
Optional<AngularVelocity> maxAbsYawRate(double startTime, double endTime)
```

on `Drive`. The vision-facing surface of the drive base goes from zero
methods to one, and this is the one. **[decided]**

The buffer behind it is built-in.
`TimeInterpolatableBuffer.createDoubleBuffer(historySize)`
(`wpimath/src/main/java/org/wpilib/math/interpolation/TimeInterpolatableBuffer.java:67`)
**[source]** evicts on `addSample` (`:77-79`, via `cleanUp` at
`:87-96`) **[source]** and exposes `getInternalBuffer()` as a
`NavigableMap<Double, Double>` (`:148-150`) **[source]**. The whole
implementation is a `subMap` and a stream:

```java
// buffer holds rad/s; the Pigeon signal reports deg/s
OptionalDouble max =
    buffer.getInternalBuffer()
        .subMap(startTime, true, endTime, true)
        .values().stream()
        .mapToDouble(Math::abs)
        .max();

return max.isPresent()
    ? Optional.of(RadiansPerSecond.of(max.getAsDouble()))
    : Optional.empty();
```

That comment is the only one the method gets, and it is there because
the unit changes at the boundary: `getAngularVelocityZWorld()` is
declared `val -> DegreesPerSecond.of(val)`
(`CorePigeon2.java:1677`) **[source — Phoenix 6]**, and what goes into
the buffer is `.in(RadiansPerSecond)`. A buffer of bare doubles carries
no unit, which is exactly the case CLAUDE.md's comment rule exists for.

**Max over a window ending at the capture instant, not an instantaneous
read at consume time.** That is what the gate is actually asking: a
20 ms spin spike that corrupted a frame is caught even when the robot is
stationary by the time the packet lands. **[field]** An
`angularVelocityAt(timestamp)` read cannot answer it — see Rejected.

**It fails closed.** `Optional.empty()` means *no history covers that
window*, and the caller's line is:

```java
drive.maxAbsYawRate(tCapture - kWindow, tCapture)
     .map(rate -> rate.lt(kMaxSpin))
     .orElse(false);          // no history -> do not accept this frame
```

254's equivalent ends in `.orElse(Double.POSITIVE_INFINITY)`
**[field]**. Holding ADR 0005's *`Measure` everywhere* rule with no
exception deletes that sentinel: `orElse(false)` states the policy
directly instead of encoding it as a magic number chosen because it
happens to fail every comparison. The buffer still stores primitive
doubles, so the boxing happens once per query rather than once per
sample and the sampling path allocates nothing.

**The two timestamps stay raw `double` seconds.** They are instants on
the `Timer.getMonotonicTimestamp()` epoch, not durations, and they are
the *same* value the caller passes to `visionUpdate` one line later.
Wrapping an instant in `Time` would make the two halves of one call
site disagree about the type of one number.

**The rule that comes out of this, and it binds everything after: a
`Measure` for a quantity, a raw `double` second for a monotonic
instant.** **[decided]**

**History is 1.5 s**, matching `PoseEstimator3d.BUFFER_DURATION`
(`PoseEstimator3d.java:52`) **[source]**. Anything shorter is a second,
invisible staleness cutoff carrying a different number from the one the
estimator enforces — the gate would reject frames the estimator would
have accepted, and nobody would find it for a season.

**Nothing here is named for vision.** It is a query about the drive
base's own past, and any caller is welcome to it for any reason.

### The rate comes from the Pigeon's own signal, whose default frequency is too low

The sample is `getAngularVelocityZWorld()`, **not a differenced
`getGyroHeading()`**. Differencing over a 5 ms step multiplies
quantization noise by 200, and `max` is the worst possible statistic to
run over noisy data — it selects the noise spike by construction,
turning the gate into a false-reject machine. CTRE's rate signal is
filtered on-device. **[decided]**

⚠️ **#27 left the signal's default update frequency unverified, and it
is lower than the loop rate.** The javadoc on
`CorePigeon2.getAngularVelocityZWorld()` gives *"CAN 2.0: 10.0 Hz · CAN
FD: 100.0 Hz (TimeSynced with Pro)"* (`CorePigeon2.java:1638-1639`)
**[source — Phoenix 6]**. Our loop runs at **200 Hz** (ADR 0002), so
even on CAN FD — which SystemCore has, at 5 and 8 Mbps
(`docs/research/vendordeps.md`) — **half the buffer entries would be
duplicates of the sample before them**, and on CAN 2.0 nineteen out of
twenty would be.

So the frequency is raised explicitly, with
`BaseStatusSignal.setUpdateFrequencyForAll(Frequency, BaseStatusSignal...)`
(`BaseStatusSignal.java:678`) **[source — Phoenix 6]**, at the same
place ADR 0004 configures the rest of the hardware. **[decided]**

**The same applies to the heading itself, and that is the more
surprising half.** `getRotation3d()` goes through `getQuaternion()`,
which refreshes four signals — `BaseStatusSignal.refreshAll(m_quatWGetter,
m_quatXGetter, m_quatYGetter, m_quatZGetter)` (`Pigeon2.java:261-263`)
**[source — Phoenix 6]** — and those default to 50 Hz on CAN 2.0 and
100 Hz on CAN FD (`CorePigeon2.java:679-680`) **[source — Phoenix 6]**.
Odometry at 200 Hz against a 100 Hz heading is reading every value
twice. The signals go in the same `setUpdateFrequencyForAll` call.

⚠️ **No source ticket raised the heading signal's rate** — #27 asked
only about `AngularVelocityZWorld`. This half was surfaced by reading
the same javadoc for the same defect, and it is recorded here rather
than deferred because the two signals are one `setUpdateFrequencyForAll`
call and one line in ADR 0007's budget. Splitting them would leave
odometry quietly reading a 100 Hz heading at 200 Hz with nobody owning
the question.

Reading is cheap: `refresh()` *"performs a non-blocking refresh
operation"* (`StatusSignal.java:89, 112-113`) **[source — Phoenix 6]**,
so it reads Phoenix's local cache and does not wait on CAN.

**The CAN cost of raising five signals to 200 Hz is real and it is not
this ADR's to spend.** ADR 0007 owns the frame budget; this ADR owns the
requirement. See Open.

The sample is taken in `Drive`'s existing per-loop update. **No second
rate, no `addPeriodic` registration, no thread** — if the loop period
ever changes, the buffer gets the new rate for free with no code change.

### Simulation drives the same signal, on the same path

⚠️ #27's worst case was that nothing drives the Pigeon's
angular-velocity signal in simulation, the buffer fills with **zeros**,
and the gate silently becomes a no-op — exactly where a season would
first test its vision code.

**It does not arise.** `Pigeon2SimState.setAngularVelocityZ(AngularVelocity)`
exists (`Pigeon2SimState.java:210-211`), alongside
`setAngularVelocityX`/`Y`, `setPitch` (`:134`) and `setRoll` (`:153`)
**[source — Phoenix 6]**. `Drive.updateSim()` pushes the simulation's
angular velocity into it, keeping simulation and real hardware on one
code path through the buffer — consistent with ADR 0010 putting the
vendor plumbing in `updateSim` and keeping `first.robot.sim`
vendor-free. **[decided]**

The map's standing note that *"`Pigeon2SimState` sets yaw only, leaving
roll and pitch at zero"* is **wrong for this Phoenix version** and is
corrected here: `setPitch` and `setRoll` are both present. What is true
is that our simulation model is flat-floor (ADR 0010), so nothing calls
them and `Odometry3d` degenerates to exactly the 2d answer.

### Single-threaded, and that is a project rule rather than a property of this seam

A season's vision class **polls its NetworkTables table from
`robotPeriodic()`**. It does not bind an NT listener. **[decided]**

This matters because `PoseEstimator3d` has **no synchronization
anywhere** **[executed]** — a `TreeMap` of vision updates (`:61`) and a
`TimeInterpolatableBuffer` (`:55`) **[source]**, both touched by
`update()` on the main loop. The realistic season implementation is a
PhotonVision or Limelight callback firing on NT's thread, which would
race `robotPeriodic()`'s odometry update and produce a corrupted map or
a torn pose read: intermittent, unreproducible, and blamed on the
camera.

Synchronizing inside `PoseEstimator` was rejected — see Rejected.

### Logging

ADR 0005 owns the log schema and its `/Drive/Odometry` subtree already
carries the pose half of this ADR:

```
/Drive/Odometry/{EstimatedPose,OdometryOnlyPose,GyroHeading,GyroRate}
```

Both poses are logged as `Pose3d`, which is what `getEstimatedPose3d()`
exists for — the flattening in `getEstimatedPose()` is for consumers,
not for the log, and a log that threw away z and tilt could not show a
tag flip putting the robot in the air. `OdometryOnlyPose` is the second
estimator. `GyroRate` is the latest buffer sample — **yaw** rate in
rad/s, the axis ADR 0005's name does not state — logged at the loop
rate, so *"was the robot spinning"* is answerable from a log **before
any season's vision code exists**.

ADR 0005 excluded vision internals and named this ADR as their owner.
They are:

| Signal | Rate | Why |
|---|---|---|
| `/Drive/Vision/Measurement` (`Pose3d`) | on arrival | what vision claimed |
| `/Drive/Vision/Residual` | on arrival | `measurement − sampleAt(timestamp)` — how wrong vision thought we were, *before* the Kalman gain damps it |
| `/Drive/Vision/Age` (seconds) | on arrival | `now − timestamp` |
| `/Drive/Vision/StdDevs` | on arrival | ADR 0004's rule: the log records what the git SHA cannot pin, and trust is now caller-supplied |

**`Residual` is the helping-or-hurting signal.** `sampleAt(timestamp)`
is public (`PoseEstimator3d.java:217`) **[source]**, so at the moment a
measurement arrives the raw disagreement is recoverable — which is what
makes a systematically biased camera obvious rather than inferrable.

**`Age` is a number, not a boolean.** `addVisionMeasurement` returns
`void` and drops silently, so acceptance is not directly observable;
logging the raw age means a reader sees `1.8` against a 1.5 s buffer and
knows immediately why nothing moved.

These four are logged **on arrival, not on the loop** — a deliberate
exception to ADR 0005's log-at-the-loop-rate rule **[decided]**, the
same shape as its alert-set exception, because a signal that only exists
on an event is a lie at 200 Hz.

⚠️ #20 proposed these under `/Pose/`. ADR 0005 landed the pose signals
under `/Drive/Odometry/` instead, and #27 proposed `/Drive/YawRate` for
a signal ADR 0005 already calls `/Drive/Odometry/GyroRate`. **ADR 0005's
names win**; the names above are the reconciliation, and neither of the
older spellings appears anywhere.

## Consequences

- **Leaving the odometry-only estimator un-reset would turn it into a
  wheel-slip meter that silently changes meaning at the first
  `resetPose`.** This is the tempting version of the two-estimator
  decision and the reason it was decided the other way. Un-reset, the
  divergence between the two is wheel error — genuinely the number you
  want. But only until the first `resetPose`, after which the two are in
  **different frames** and the gap is wheel error *plus* the reset
  offset, with nothing in the log separating the two. A signal that
  silently changes meaning at the start of autonomous is worse than no
  signal, because it will be believed. If we ever want that number, ADR
  0010's `/Sim/ModuleSlip` and a deliberate calibration routine are the
  honest ways to get it. **[decided]**

- **Unplugging vision is deleting one class.** Nothing in the drive
  base, the estimator, the follower or the log names a camera, a
  pipeline or a vendor. The season's vision class is the only file that
  imports one, and its only outward call is `visionUpdate`.

- **The disabled-versus-enabled trust switch has to be done on the
  vision σ.** 254's move is to declare odometry 3–5× less trustworthy
  while disabled, so vision dominates and the pose converges on the wall
  with no discontinuity and nothing to forget to clear. **[field]** We
  cannot copy it directly — see Traps — but scaling the *vision* σ while
  disabled is free, lands in the season's class, and drives the identical
  ratio.

- **ADR 0011's field-origin question now has two consumers.** Vision
  measurements are field-absolute, so a tag layout in one origin and a
  trajectory in the other is a robot that drives confidently to the wrong
  half of the field. Under a blue-corner origin,
  `Field.setOrigin(OriginPosition)`
  (`fields/src/main/java/org/wpilib/fields/Field.java:349`) **[source]**
  and ADR 0011's trajectory flip must agree. That raises the cost of
  getting it wrong; it does not change who decides it, and it is still
  unresolved upstream. See Open.

- **The CAN frame budget grows by five signals at 200 Hz** — the yaw
  rate and the four quaternion components. That is a real cost against a
  bus that already carries eight SPARKs and the Pigeon's constant
  diagnostic overhead, and it lands on ADR 0007.

- **No simulated vision source and no wiring test.** A sim source feeding
  ADR 0010's true pose back noisily would mostly test
  `SwerveDrivePoseEstimator3d`, which is WPILib's code with WPILib's
  tests, and it would be the sole consumer of the abstraction we just
  declined to build. A single JUnit wiring test was considered and cut
  for the same reason. The one failure either would have caught is the
  timestamp epoch, and it is ours rather than WPILib's — so it is written
  into Traps instead of into a test.

- **3d is free on flat carpet and correct on a ramp.** With
  `Pigeon2SimState` driving yaw only in our flat-floor model, `Odometry3d`
  produces bit-for-bit the 2d answer. Nobody should go hunting for the
  missing pitch.

## Traps

- **Vision timestamps must come from `Timer.getMonotonicTimestamp()`,
  and the wrong epoch is a silent total failure.**

  `Timer.getTimestamp()` is documented as *"the time returned by
  getMonotonicTimestamp(). However, the return value of this method may
  be modified to use any time base, including non-monotonic time
  bases"* (`wpilibj/src/main/java/org/wpilib/system/Timer.java:20-26`)
  **[source]**. The two are the same number right up until something
  changes the time base, and then they are not.

  On the wrong epoch, **every** measurement fails the buffer check
  (`PoseEstimator3d.java:289`) **[source]**, `addVisionMeasurement`
  returns `void`, nothing throws, nothing logs, and the only symptom is
  a pose that never converges — which reads exactly like a miscalibrated
  camera. It takes the same epoch on both sides of the seam: the vision
  timestamp *and* the yaw-rate buffer's keys, or `subMap` returns empty
  and every frame fails closed instead.

  `/Drive/Vision/Age` is the diagnostic that makes this findable in one
  glance.

- **The ~1 m sanity gate is recommended by javadoc, is not implemented,
  and must not be added.**

  > *"To promote stability of the pose estimate and make it robust to
  > bad vision data, we recommend only adding vision measurements that
  > are already within one meter or so of the current pose estimate."*

  That sentence is on **both** `addVisionMeasurement` overloads
  (`PoseEstimator3d.java:275, 348`) **[source]**, and WPILib implements
  none of it — and offers no hysteresis, timeout or escape hatch
  alongside it.

  Taken literally it is self-defeating: if odometry has genuinely
  drifted 2 m, a 1 m gate rejects the exact measurement that would fix
  it, **permanently**. **No team gates on disagreement with the current
  pose.** Across 6328, 254, 1678, 604 and 581, every gate anyone builds
  is *absolute* — field bounds, Z height, ambiguity, spin rate at
  capture, stale timestamp — and none takes the estimate as an input, so
  lock-out is structurally impossible. **[field]** The only FRC library
  that ever implemented the javadoc's advice is YAGSL, whose method is
  `@Deprecated(forRemoval = true)` and is never called.
  **[source — #26]**

  *Do not add it*, and do not accept it from a season's vision class
  either, without new evidence that is not this javadoc sentence.

- **Every `reset*` variant clears the 1.5 s buffer, silently dropping
  every in-flight measurement.** `resetPosition` (`:132-136`),
  `resetPose` (`:145-148`), `resetTranslation` (`:157-162`) and
  `resetRotation` (`:182-187`) all call `m_odometryPoseBuffer.clear()`
  and `m_visionUpdates.clear()` **[source]**. Any measurement whose
  timestamp predates the reset then has no odometry sample to match and
  is dropped without a word.

  So a mid-match reset costs latency compensation for every frame still
  in the air — which is a second, independent reason vision never
  resets, on top of the field evidence that nobody does it.

  ⚠️ `resetTranslation` and `resetRotation` are not simply *write the
  field and move on*: each re-applies the last vision update to the axis
  it did not reset (`:170`, `:195`) **[source]**.

- **`stateStdDevs` is constructor-only, so the trust switch must scale
  the vision σ.** `PoseEstimator3d` takes `stateStdDevs` in its
  constructor (`:76-85`) **[source]** and **there is no
  `setStateStdDevs` anywhere in `org.wpilib.math.estimator`**
  **[executed]** — only `setVisionMeasurementStdDevs` (`:100`)
  **[source]**. 254's disabled-versus-enabled switch is a CTRE
  `SwerveDrivetrain` feature and does not exist here. Reaching for the
  state σ at runtime finds nothing to call; the equivalent lever is the
  vision σ, which our seam already takes per call.

- **In 3d, one rotation gain covers roll, pitch and yaw together.**
  `setVisionMeasurementStdDevs` computes `m_vision_k[3]` from the single
  θ std dev and then assigns it to the other two axes:

  ```java
  double angle_gain = m_vision_k[3];
  m_vision_k[4] = angle_gain;
  m_vision_k[5] = angle_gain;
  ```

  (`PoseEstimator3d.java:117-119`) **[source]** There is one θ slot in
  `Matrix<N4,N1>` and it buys all three rotations. Vision's yaw cannot
  be trusted differently from its roll and pitch, and the useful reading
  of that is the inverse: one `σ_θ = ∞` says *the gyro owns all three
  rotations*, which is what we want anyway — the gyro owning yaw is
  unanimous across all five teams surveyed. **[field]**

- **The 3d rotation correction is componentwise Euler scaling, not an
  interpolation on SO(3).**

  ```java
  new Rotation3d(
      m_vision_k[3] * transform.getRotation().getX(),
      m_vision_k[4] * transform.getRotation().getY(),
      m_vision_k[5] * transform.getRotation().getZ())
  ```

  (`PoseEstimator3d.java:322-325`) **[source]** Scaling three Euler
  angles independently is an approximation that is fine while
  corrections are small and degrades as they grow. Together with the
  shared gain above, both arguments point the same way: **keep vision's
  rotation trust low.**

- **`addVisionMeasurement` returns `void` and rejects silently, in
  three places.** It returns early if the odometry buffer is empty or
  the measurement is older than the 1.5 s window (`:288-291`), if there
  is no odometry sample at that timestamp (`:299-301`), and if
  `sampleAt` yields nothing (`:307-309`) **[source]**. There is no
  return value, no exception and no log. Acceptance is not observable —
  which is exactly why `/Drive/Vision/Age` is logged as a raw number.

- **With the defaults, one measurement moves the estimate 10%, and the
  correction does not saturate.** `k = q/(q + √(q·r))` with state `0.1`
  and vision `0.9` gives `k = 0.01/(0.01 + 0.09) = 0.1`. A measurement
  5 m off moves the estimate 0.5 m, and a *stream* of wrong measurements
  drags it all the way there. The correction is proportional to the
  residual at constant gain with **no clamp anywhere**, so a measurement
  10 m off moves you ten times as far as one 1 m off — there is **no
  clamp on the correction anywhere in the class** **[executed]**.
  **Nothing about the built-in weighting makes a persistently wrong
  camera safe** — the
  Kalman gain is a blend, not a filter.

- **`sampleAt` fails open, so it is not a gate.** `PoseEstimator3d.sampleAt`
  is public over the 1.5 s pose buffer. It returns `Optional.empty()`
  for exactly one reason — the buffer is entirely empty (`:219-221`) —
  and otherwise clamps: `timestamp = Math.clamp(timestamp,
  oldestOdometryTimestamp, newestOdometryTimestamp)` (`:227`)
  **[source]**. So on a populated buffer a capture instant older than
  the window silently returns the **oldest pose** rather than nothing.
  That is the exact opposite of what a gate needs,
  and it is the trap that makes `maxAbsYawRate` worth existing: a season
  could otherwise difference two `sampleAt` calls 20 ms apart to get yaw
  rate — from an estimator whose heading vision itself moves, feeding
  the output back into the gate.

- **The yaw-rate buffer is never cleared on `resetPose`.** Every WPILib
  reset clears its buffers and this ADR resets both estimators together,
  so the reflex is to clear this one alongside them. **Do not.** Yaw
  *rate* is frame-independent — re-declaring where the robot is says
  nothing about how fast it was turning — and clearing it blinds the
  gate for a full 1.5 s starting at `autonomousInit`, which because the
  gate fails closed means **rejecting every frame at the moment vision
  matters most**. Living on `Drive` rather than on `PoseEstimator` is
  partly so nobody is tempted; it still earns the one-line comment at
  the reset path.

- **A signal left at its default frequency fills the buffer with
  duplicates, and a `max` over duplicates looks fine.** The rates are
  under Decision; the failure mode belongs here. Nothing errors, the
  buffer fills, `subMap` returns entries, and the gate returns a
  perfectly plausible number computed over a fraction of the
  information it appears to cover — and the spin spike the gate exists
  to catch is exactly what a too-slow signal aliases away. There is no
  symptom to notice, which is why `setUpdateFrequencyForAll` is not
  optional and why the fallback under Open is a matching sample rate
  rather than a silent default.

- **Do not synthesise yaw rate by differencing `getGyroHeading()`.** It
  compiles and it is one line, which is the whole danger; Decision owns
  the arithmetic. What it looks like on the field is the reason it is
  repeated here: the gate turns into a false-reject machine that
  rejects good frames at random, and every symptom points at the
  camera.

## Open

- **The field origin is unresolved, and this ADR is its second
  consumer.** ADR 0011 records it in full: the map owner reports 2027
  moves the origin to field centre, and the alpha-7 tree does not
  reflect that — `Field`'s class doc still describes *"the origin at the
  bottom-right corner of the blue alliance wall"*
  (`Field.java:32-33`) and `OriginPosition` offers only
  `BLUE_ALLIANCE_WALL_RIGHT_SIDE` and `RED_ALLIANCE_WALL_RIGHT_SIDE`
  (`:45-47`) **[source]**. **[unverified]** For vision the consequence
  is `Field.setOrigin(...)`; for autonomous it is the trajectory flip;
  and the two must agree or vision and the path disagree by a mirror.
  *Unblocked by* the 2027 field release and its AprilTag map.

- **The CAN frame budget for five 200 Hz Pigeon signals.** This ADR
  requires the rate; ADR 0007 decides whether the bus can pay for it,
  and what gives way if it cannot. The fallback if it cannot is a lower
  frequency and a matching sample rate, not a silent default —
  the trap above is what makes the silent version dangerous.
  *Unblocked by* ADR 0007.

- **Whether 200 Hz is enough.** The field precedent is 254's 250 Hz
  history, and our loop is 200 Hz. The gap is small and nobody has shown
  a case that turns on it, but it has not been measured either.
  *Unblocked by* #28, which owns the loop-period question generally;
  this gate is rate-agnostic and blocks on none of it. **[unverified]**

- **The gate's window length has no value, and it is not ours to
  choose.** With a global-shutter lens there is no rolling-shutter
  smear; what the gate actually defends against is motion blur within
  the exposure and, more importantly, **timestamp error × rate** — a
  latency error `d` at `ω` rad/s injects `ω·d` of heading error into the
  pose solve, which levers out into a translation error growing with tag
  distance. The physically-right window is therefore exposure plus
  timestamp-latency uncertainty, which is a property of the camera and
  the pipeline. The caller passes both endpoints and the drive base owns
  no constant. *Unblocked by* a season having a camera.

- **Phoenix 6 is at `26.50.0-alpha-1`.** Every Phoenix claim above is
  read from that jar's sources, and an alpha can move. The default
  update frequencies and the presence of
  `Pigeon2SimState.setAngularVelocityZ` are the two that would change a
  decision if they did. **[unverified]** *Unblocked by* the vendordep
  reaching a release build, at which point they are re-read rather than
  re-argued.

## Rejected

### Vision filtering, in the drive base or anywhere in this repo

Out of scope, and this is the decisive finding rather than a deferral.
Go through every gate the surveyed teams apply and check its inputs:
`tagCount`, `rawFiducials[].ambiguity`, `avgTagDist`, `avgTagArea`,
`distToCamera`, MT1-vs-MT2 provenance, the tag ID list, the active field
layout, and — for the best gate anyone wrote — the position of an
intake. **Not one of them crosses this seam.** **[field]**
`visionUpdate(Pose3d, double, Matrix<N4,N1>)` hands over a pose, an
instant and four numbers; by construction the drive base cannot compute
a single one of these, and giving it the ability would mean importing a
camera type into the drive base.

[`docs/research/vision-filtering.md`](../research/vision-filtering.md)
is the starting point for a season that needs one — five teams, eleven
team-seasons, with the constants and the per-file sources.

⚠️ **Those constants are a place to start tuning, not numbers to
believe.** `k·d^p/n^q` is the modal *shape*, but the xy coefficient
spans 4× across sources, 6328 changed their own by 2× and their
exponent from 1.2 to 2.0 between consecutive seasons **[field]**, and
PhotonVision's alternative family carries the source comment *"(Fake
values. Experiment and determine estimation noise on an actual
robot.)"* **[source — #26]** A threshold copied between pipelines is a
bug: the four published ambiguity constants span 0.19 to 0.7 and are not
even the same quantity.

### A `VisionSource` interface, or any vision type in this repo

It would have exactly one implementation, and the number it has today is
**none**. ADR 0003 rejected a hardware-IO seam for the same reason and
this is that rule applied again. **[decided]**

### Inverting the dependency — handing vision a `Consumer`

Rejected: it buys decoupling in the direction we do not need. Vision
depending on the drive base is correct, **because vision is the half
that gets deleted**. A `Consumer` makes the drive base hold a reference
to something it must be given, which is more wiring for less.

### Vision-seeded reset

No team resets from vision mid-match. 6328 never reset from vision at
all; 254's was 2023 only and only when *disabled and never enabled* this
power cycle; 1678's is heading-only at `autonomousInit` from a
100-sample average accumulated while disabled; 581 shipped a 5-second
blind-reacquire reset in 2025 and **removed it for 2026**. **[field]**

And the reset mechanics argue against it independently: every reset
variant clears the 1.5 s buffer and silently drops every in-flight
measurement (Traps). Only `resetPose` at the start of autonomous seeds
the estimate, from the trajectory's initial pose.

*Do not re-raise* without a gate on tag count and ambiguity to make it
safe — which, per the section above, does not live in this repo.

### A general `angularVelocityAt(double timestamp)`

Cleaner-looking, and it pushes the max-over-window to the caller. Two
problems. The caller then needs to know the buffer's sample rate for the
answer to mean anything, which is drive-base internals leaking through a
tidier signature. And a single interpolated sample cannot answer the
question the gate is asking: *was there a spike anywhere in this
window*, not *what was the rate at this instant*.

### A fixed window inside `maxAbsYawRate`

254 hardcode 0.3 s. **[field]** The physically-right window is a
property of the camera and the pipeline, which is the scope line this
whole ADR is drawn along. The caller passes both endpoints; the drive
base owns no constant and no policy. See Open.

### Buffering `ChassisVelocities` to answer linear-speed gates too

1678's 2024 code gates above 4.0 m/s. **[field]** Adding it here is the
general robot-state history service #27 was told to watch for. Three of
the four sources gate on rotation, and a season wanting a linear gate
can difference `getEstimatedPose()`.

*Do not re-raise* as a generalisation. Re-raise only if a specific
season's camera needs it, and then as a second query, not a service.

### A third class beside `Drive` and `PoseEstimator` to hold the buffer

A class whose entire content is one `TimeInterpolatableBuffer`. The
buffer lives on `Drive` because that is where the signal is read and
where it is least likely to be cleared by someone tidying up the reset
path.

### Synchronizing inside `PoseEstimator`

It would be our own concurrency machinery wrapped around WPILib's
non-concurrent class, and it would have to cover `getEstimatedPose()`
too or the race would just move to the readers. The project rule — no
threads of our own — costs nothing here and removes the whole category.

### Pairing an estimator with a bare `SwerveDriveOdometry3d`

Functionally equivalent to the second estimator and asymmetric to read.
Two instances of one class says *same object, one of them gets told
about vision* without a paragraph of explanation.

### A simulated vision source, and a JUnit wiring test

Both covered under Consequences: each would mostly test WPILib's code,
and the one failure they would catch is the timestamp epoch, which is
recorded as a trap instead.

### Deriving yaw rate from `PoseEstimator3d.sampleAt`

A season *could* have done this with no drive-base change at all, and it
is wrong twice: it is circular, because the fused estimator's heading is
moved by vision, so the gate would feed the output back into itself; and
`sampleAt` clamps rather than returning empty, so it fails open exactly
when the gate needs to fail closed. Both in Traps.

## Source

Decided across
[#20](https://github.com/Drew-Robotics/2027beta/issues/20), which
settles the seam itself — one method, std devs mandatory, 3d wherever
possible, two estimators reset together, no behaviour at all with no
vision, single-threaded polling, and the log signals;
[#26](https://github.com/Drew-Robotics/2027beta/issues/26), whose survey
of five teams across eleven team-seasons turns *the drive base rejects
nothing* and *vision never resets* from placeholders into decisions, and
establishes that the ~1 m gate is a trap nobody builds; and
[#27](https://github.com/Drew-Robotics/2027beta/issues/27), which
amends the seam from zero vision-facing drive-base methods to one and
settles `maxAbsYawRate`'s shape, its fail-closed contract, its 1.5 s
history and its exemption from reset.

The pose estimator's placement beside `Drive`, and `Drive` exposing
`getGyroHeading()` and `getModulePositions()` and nothing else, are
[#15](https://github.com/Drew-Robotics/2027beta/issues/15) and ADR 0011;
this ADR amends `getGyroHeading()` to `Rotation3d`. The log naming and
the `Measure`-everywhere rule are
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and ADR 0005;
the config placement for the signal frequencies is
[#13](https://github.com/Drew-Robotics/2027beta/issues/13) and ADR 0004;
the 200 Hz loop is [#10](https://github.com/Drew-Robotics/2027beta/issues/10)
and ADR 0002, with the loop-period question itself at
[#28](https://github.com/Drew-Robotics/2027beta/issues/28); the
flat-floor simulation and `updateSim` are
[#14](https://github.com/Drew-Robotics/2027beta/issues/14) and ADR 0010;
the CAN frame budget this ADR spends is ADR 0007.

Research: [`docs/research/vision-filtering.md`](../research/vision-filtering.md),
[`docs/research/wpilib-swerve.md`](../research/wpilib-swerve.md),
[`docs/research/vendordeps.md`](../research/vendordeps.md).

WPILib source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpimath/src/main/java/org/wpilib/math/estimator/PoseEstimator3d.java`,
`wpimath/src/main/java/org/wpilib/math/estimator/SwerveDrivePoseEstimator3d.java`,
`wpimath/src/main/java/org/wpilib/math/kinematics/Odometry3d.java`,
`wpimath/src/main/java/org/wpilib/math/interpolation/TimeInterpolatableBuffer.java`,
`wpilibj/src/main/java/org/wpilib/system/Timer.java`,
`fields/src/main/java/org/wpilib/fields/Field.java`,
and the flagship example
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/PoseEstimator.java`.

Phoenix 6 source read for this ADR, in
`wpiapi-java-26.50.0-alpha-1-sources.jar`:
`com/ctre/phoenix6/hardware/Pigeon2.java`,
`com/ctre/phoenix6/hardware/core/CorePigeon2.java`,
`com/ctre/phoenix6/sim/Pigeon2SimState.java`,
`com/ctre/phoenix6/BaseStatusSignal.java`,
`com/ctre/phoenix6/StatusSignal.java`.
