# ADR 0008 — Closed loop on the SPARK

## Status

Accepted — 2026-08-26. Superseded in part by ADR 0011: `kA` returns at
drivebase level, riding `arbFeedforward` on the path-following path only.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. REVLib
`[source]` claims were read in `REVLib-java 2027.0.0-alpha-6`, the
version `vendordeps/REVLib.json` pins, from its sources jar; paths below
are given as `com/revrobotics/...`. An unqualified path is a file in
this repo.

## Context

Every swerve module runs two control loops. One holds the module
pointing at an angle; the other holds the wheel turning at a speed.
Each loop compares where the mechanism is against where it was asked to
be, and decides how much voltage to apply.

There are two places that arithmetic can happen. It can run on
SystemCore, in our code, once every 5 ms (ADR 0002), with the
measurement arriving over CAN and the output going back out over CAN.
Or it can run on the SPARK itself, at the controller's own 1 kHz —
REV's documented 1 ms control loop **[source, via #29 — REV's SPARK
documentation; not re-read here]** — with the sensor wired directly
into the controller and CAN carrying only the target.

Historically FRC teams closed these loops on the roboRIO for one
concrete reason: WPILib's feedforward — the `kS` and `kV` terms that do
most of the work in a velocity loop — had no equivalent in SPARK
firmware, so a loop on the controller was a bare PID and a loop on the
robot was a PID *plus* a physical model. That is no longer true. 2027
adds `FeedForwardConfig`, which is WPILib's feedforward in firmware.

This ADR decides where each loop runs, which sensor the steer loop
closes against, and how that sensor is wired.

**The ground for the decision is latency, and the latency is
unmeasured.** A loop on the SPARK sees its sensor directly and never
waits for a bus; a loop on SystemCore pays a CAN round trip in every
iteration. That is **argued rather than measured**: there is no CAN
hardware attached to the bench Pi, so the round-trip cost the argument
turns on has never been put on a scope. **[unverified]** See Open.

## Decision

### Both loops close on the SPARK

**Steer position** closes on the SPARK against the **analog absolute
encoder**, with position wrapping enabled. **Drive velocity** closes on
the SPARK against the **primary encoder**, with `kS` + `kV` + `kP`.
**[decided]**

Robot-side code **writes a setpoint, never a voltage**. Once per loop a
module hands the SPARK an angle and a speed; the controller does the
rest at 1 kHz. Nothing in `Drive` computes a motor output.

### Steer closes on the analog absolute encoder

The modules carry Thrifty absolute magnetic encoders, and the Thrifty
is an **analog** sensor — not duty cycle. Six independent team wrappers
read it as `getAverageVoltage() / getVoltage5V()` **[field — #29]**. It
lands on data-port **pin 3**, and the feedback sensor is
`FeedbackSensor.kAnalogSensor`
(`com/revrobotics/spark/FeedbackSensor.java:34`) **[source]**, not
`kAbsoluteEncoder`, which is the pin-6 duty-cycle path.

The alternative was closing steer on the SPARK's integrated primary
encoder, seeded from the analog at boot. It was rejected on
**backlash**: the analog sensor sits on the module's output shaft and
reads the true module angle, while the integrated encoder reads motor
position and differs from it by the reduction's backlash. Closing on
the motor lets the module settle anywhere inside that band. At 12 bits
the analog resolves about 0.09° **[source, via #29 — Thrifty datasheet;
not re-read here]**, finer than the band it would be hiding.
Resolution and filtering favour the relative encoder; the quantity
being controlled favours the absolute, and that is the trade we take.
**[decided]**

### The wiring is the plain one, and there is no jumper

Encoder powered from **pin 2 (+5 V)**, signal to **pin 3**, ground to
**pin 10**, with the encoder unjumpered. **[source, via #33 — REV's
SPARK Flex data-port pinout]**

This works because the SPARK **Flex**'s analog input range is `0` to
`Vout` — the supply rail — where the SPARK **MAX**'s stops at 3.3 V.
The encoder is ratiometric to its supply and the ADC references that
same rail, so supply variation cancels. It is a better arrangement than
the MAX's, which needs either the MAX's 3.3 V pin or the Breakout
Board's 5 V→3.3 V amplifier precisely *because* its input stops below
the rail. **[source, via #33 — REV's SPARK MAX and SPARK Flex
specification tables]**

The Flex Breakout Cable's shrouded 3-pin servo connectors carry power,
ground and signal, so the encoder's own 3-wire cable mates directly
**[source, via #33]**. No adapter, no amplifier, no hardware change.
**Do not solder the encoder's 3.3 V jumper** — see Traps.

### The module zero offset is folded into the setpoint, here

`AnalogSensorConfig` has exactly three setters — `inverted`,
`positionConversionFactor`, `velocityConversionFactor`
(`com/revrobotics/spark/config/AnalogSensorConfig.java:59, 71, 83`)
**[source]**. There is no `zeroOffset`, no `zeroCentered` and no
`averageDepth`. The device cannot hold a zero, so we hold it.

The steer setpoint is therefore

```java
double setpoint = MathUtil.inputModulus(target.getRotations() + offset, 0, 1);
```

with the wrap range configured to match the converted sensor's, `[0,
1)`:

```java
config.closedLoop
    .feedbackSensor(FeedbackSensor.kAnalogSensor)
    .positionWrappingEnabled(true)
    .positionWrappingInputRange(0, 1);
```

`inputModulus` is `MathUtil.inputModulus`
(`wpimath/src/main/java/org/wpilib/math/util/MathUtil.java:233`)
**[source]**. The `+ offset` is not optional and neither is the
`inputModulus` around it — `getRotations()` and the sensor do not share
a range. See Traps.

Holding the offset in the repo is strictly better than holding it on
the device, and not only for config-as-code's sake: it is the one place
a stale zero could otherwise survive forever. ADR 0004 configures with
`kResetSafeParameters`, whose documented exception list — CAN ID, motor
type, idle mode, PWM input deadband and **duty cycle offset**
**[source, via #29 — REVLib documentation; not re-read here]** — would
let a device-held zero outlive every config pass. Ours cannot, because
the device never has one. **[decided]**

### Drive velocity is `kS` + `kV` + `kP`, and every term has exactly one home

`FeedForwardConfig` is new in 2027 and is WPILib's feedforward in
firmware: `kS` **in Volts** and `kV` **in Volts per velocity**, per
closed-loop slot, with combined setters
(`com/revrobotics/spark/config/FeedForwardConfig.java:64, 80, 285`)
**[source]**. So the standard WPILib velocity recipe runs on the
controller at 1 kHz with no CAN in the loop:

```java
config.closedLoop
    .feedbackSensor(FeedbackSensor.kPrimaryEncoder)
    .p(kP)
    .apply(new FeedForwardConfig().sv(kS, kV));
```

**`kS` and `kV` live in `FeedForwardConfig` and nowhere else.** The
`setSetpoint` overloads also accept an `arbFeedforward` term, and a
term written in both places is applied twice. See Traps.

There is **no `kA` on the SPARK**. `FeedForwardConfig.kA` is documented
as *"only applied in MAXMotion control modes"*
(`FeedForwardConfig.java:91`) **[source]** and drive takes no profile,
so it would be configured and ignored. That is a statement about *this*
controller, not about acceleration feedforward in general: ADR 0011
applies a drivebase-level `kA · a` from the trajectory's own
accelerations, robot-side, as `arbFeedforward`, on the path-following
path only.

### Steer takes no profile, and D is tuned together with `dFilter`

Steer runs plain position control — no MAXMotion, no trapezoid. `kV`
is documented as *"not applied in Position control mode"*
(`FeedForwardConfig.java:75`) **[source]**, so the steer loop is `kP`
and `kD` against a sawtooth analog signal, and the derivative term is
the one doing the delicate work.

**Expect to tune `kD` together with `dFilter` from the first attempt**,
rather than tuning D and reaching for the filter later.
`ClosedLoopConfig.dFilter` exists (`:205, 216`) **[source]** and its
javadoc says only *"The derivative filter value"* — no units, no range,
no statement of what the number means. **[source]** So this is one
two-variable problem in which one of the variables has no
specification, and treating it as two sequential one-variable problems
will waste a session. Gain values themselves belong to ADR 0009.

### Enough reaches the bus that the loop is not invisible

A loop on the controller is a loop whose error nobody can see. Most of
that concern dissolves on inspection: the **setpoint** is a value we
wrote, so it costs no bus; the **measurement** is already on the bus
for odometry; **error** is those two subtracted.

What we add is one always-on readback — the SPARK's own setpoint, at
100 ms. It is the only signal that separates *the SPARK never received
the setpoint* (it browned out and reset) from *the SPARK has it and
cannot reach it* (a mechanical bind). Both otherwise present as
identical large error. **[decided]**

Full-rate PID internals are a tuning-time cost, not a standing one, and
they are affordable on one controller rather than eight. The frame
budget is ADR 0007's.

## Consequences

- **CAN cost is net zero.** With steer closing on the analog, module
  angle for odometry comes from the analog's status frame rather than
  the primary encoder's — a different frame at the same rate, four
  drive SPARKs and four steer SPARKs, unchanged total. Closing steer on
  SystemCore would have needed *both* frames at 5 ms on the steer
  controllers, about +800 frames/s. The alternative was the expensive
  one. **[source, via #28 and #29 — the frame table; ADR 0007 owns the
  budget]**

- **The steer loop cannot be simulated by calling `SparkSim`.**
  `Drive.updateSim()` **models** the onboard loop instead — a PID plus
  wrapping, with no vendor types in it, which is what makes it belong
  in `first.robot.sim` under ADR 0003's rule. Sub-stepped at 1 kHz
  inside the 5 ms sim tick, so simulation reproduces the real asymmetry
  of 200 Hz commanding a 1 kHz loop. **[decided]** The details are
  ADR 0010's; the reason the model is not a shortcut is in Traps.

- **A tuned gain dies at the next power cycle unless it is written into
  the repo.** Gains on the SPARK cannot move without a second
  `configure()`, and the tuning opmode re-configures with
  `kNoPersistParameters` + `kNoResetSafeParameters`. That is
  deliberate: the hardware enforces *write the winner into the
  repository or lose it*, the same shape as `kResetSafeParameters`
  making a REV Hardware Client edit temporary. **[decided]** ADR 0009
  owns the procedure.

- **No `FeedForwardConfig.kA` on either loop.** It needs MAXMotion and
  neither loop takes a profile (`FeedForwardConfig.java:91`).
  **[source]** Acceleration feedforward is not thereby ruled out — it
  is ruled off the *controller*. ADR 0011 puts a drivebase-level term
  on the SystemCore side, where the trajectory supplies the
  acceleration.

- **Steer noise has one lever, and it is `dFilter`.** The analog path
  has no `averageDepth` — that setter belongs to
  `AbsoluteEncoderConfig`, which we do not use
  (`AnalogSensorConfig.java:59, 71, 83`). **[source]** If steer chatter
  turns out to matter, filtering happens in the D term or not at all.

- **Boot order matters slightly more than it did.** The steer setpoint
  depends on an offset from the repo and a sensor reading in volts;
  both are in place before the first setpoint is written because
  configuration happens in the mechanism constructor (ADR 0004).
  **[decided]**

## Traps

- **Do not solder the encoder's J2 3.3 V jumper on a Flex.** REV's
  specification tables — not the pinout pages — settle this: the SPARK
  MAX analog input is `0`–`3.3 V` (12-bit, 81 µV/count) and the MAX has
  a 3.3 V supply pin; the SPARK **Flex** analog input is `0`–`Vout` and
  the Flex has no 3.3 V pin at all. **[source, via #33 — REV's SPARK
  MAX and SPARK Flex specification tables]** The Flex did not
  lose the pin, it stopped needing one. Bridging J2 on a Flex confines
  a 0–3.3 V swing inside a 0–5 V window and throws away roughly a third
  of the ADC span, for nothing. The jumper exists for controllers whose
  analog input stops below the rail.

- **`Rotation2d.getRotations()` returns `[-0.5, 0.5]` and the steer
  sensor reads `[0, 1)`.** `getRotations()` is
  `radiansToRotations(getRadians())` and `getRadians()` is
  `atan2(sin, cos)`
  (`wpimath/src/main/java/org/wpilib/math/geometry/Rotation2d.java:318,
  :300`) **[source]**, so the two ranges agree on the first half turn
  and differ by **exactly one rotation** on the second: a target the
  sensor would read as `0.75` arrives from `getRotations()` as `-0.25`.
  That value is not merely wrong, it is **outside the configured
  `positionWrappingInputRange(0, 1)` entirely**, so wrapping does not
  rescue it.

  `AbsoluteEncoderConfig.zeroCentered(true)` reports position *"in the
  range (-0.5, 0.5], instead of the default range [0, 1)"*
  (`AbsoluteEncoderConfig.java:210-217`) **[source]** and would have
  deleted the conversion outright — **it does not exist for
  an analog sensor.** The `inputModulus` in the Decision is the whole
  fix and it is load-bearing.

- **A `kV` in `FeedForwardConfig` and a `kV·v` term in
  `arbFeedforward` double the feedforward, and nothing throws.** They
  are different kinds of quantity: the config `kV` is a *gain* in Volts
  per velocity that firmware multiplies by the setpoint
  (`FeedForwardConfig.java:80`), while `arbFeedforward` is an
  already-computed *voltage* added after the control mode
  (`com/revrobotics/spark/SparkClosedLoopController.java:140-148`).
  **[source]** Nothing compares them, nothing warns, and the symptom is
  a drive base that overshoots every velocity setpoint by a consistent
  ratio — which reads as a gain that needs lowering rather than a term
  that needs deleting. **Exactly one home per term**, and for us that
  home is `FeedForwardConfig`.

- **`setReference` is now `setSetpoint`, and `set` is now
  `setThrottle`** (`SparkClosedLoopController.java:88, 115, 145, 176`;
  `com/revrobotics/spark/SparkBase.java:202`). **[source]** Every SPARK
  snippet on the internet and every LLM completion is wrong at the
  call. Both fail to compile rather than misbehave, which is the good
  case — but a student who does not know why will burn an hour.

- **Plain-double getters are gone; reads return `Signal<T>`.**
  `SparkAnalogSensor.getPosition()` returns `Signal<Double>`
  (`com/revrobotics/spark/SparkAnalogSensor.java:64`), as do
  `SparkRelativeEncoder`'s (`:50, 57`) and `SparkBase`'s applied
  output, current, temperature and voltage (`:700-730`). **[source]**
  A `Signal` where a `double` is expected does not compile; a `Signal`
  logged or compared as an object does, and is wrong.

- **`kAnalogSensorMode` is no longer writable from any 2027 API**, so
  the analog is a sawtooth with no accumulator. The parameter still
  exists in the enum — `kAnalogSensorMode(122, Type.UINT32)`
  (`com/revrobotics/spark/config/SparkParameters.java:117`) — and
  **nothing else in REVLib references it** **[source]**; the
  absolute-vs-relative choice it carried in REVLib 2024/25 is gone.
  The practical consequence: position runs 0 → 1 and drops to 0, every
  revolution, and **any loop closed on it must wrap**. A non-wrapping
  loop does not merely take the long way round — it sees a
  one-rotation error at the boundary and applies full output in the
  direction of travel.

- **`SparkSim` cannot simulate this arrangement, and fails quietly.**
  For `ControlType.kPosition` it feeds `m_position` — the internal
  integrated position — into the closed-loop call **regardless of the
  selected feedback sensor** (`com/revrobotics/spark/SparkSim.java:293-296`),
  and it picks the conversion factor from a two-way branch of
  `kAbsoluteEncoder` versus everything else (`:230-252`), so
  **`kAnalogSensor` silently gets the primary encoder's factor**.
  `m_position` integrates monotonically and never wraps (`:264`), so
  there is no boundary to cross; the only occurrence of the string
  "wrap" in the file is an unrelated javadoc (`:61`). **[source]** A sim
  built on it would agree with itself and disagree with the robot.

- **Every `AbsoluteEncoderConfig` setter silently calls
  `setSparkMaxDataPortConfig()`** — nine of them
  (`AbsoluteEncoderConfig.java`) **[source]** — writing a SPARK MAX
  compatibility parameter. Harmless to us, because we configure the
  analog sensor and never the absolute encoder, but worth knowing
  before anyone reaches for that config on a Flex.

- **Raising one status-frame rate raises its neighbours.**
  `SignalsConfig`'s period setters keep the *smaller* of the requested
  and the already-set value — `putParameter(parameterId,
  Math.min(currentPeriodMs, periodMs))`
  (`com/revrobotics/spark/config/SignalsConfig.java:46`) **[source]** —
  and several signals share a frame. Raising the applied-output rate
  for tuning drags bus voltage, current and temperature up with it, so
  the bus cost of a tuning session is larger than the one signal you
  asked for.

- **Upstream defect: a detached encoder configured as the feedback
  sensor reads back as `kNoSensor`.** `FeedbackSensor.fromId(int)` maps
  only ids 1–4 and returns `kNoSensor` for 5
  (`kDetachedAbsoluteEncoder`) and 6 (`kDetachedRelativeEncoder`)
  (`FeedbackSensor.java:47-60`), and
  `ClosedLoopConfigAccessor.getFeedbackSensor()` goes through it.
  **[source]** It bites `SparkSim`'s sensor mirroring, and it is a
  reason any future readback of the feedback sensor cannot be trusted —
  ADR 0004 configures without reading parameters back, so nothing we
  ship today asks. We use no detached encoders either; delete this note
  when REVLib fixes `fromId`.

## Open

- **`positionWrappingEnabled` has never been run against
  `kAnalogSensor` on this hardware.** It is settled by deployment
  rather than by our bench: YAGSL configures `kAnalogSensor` and calls
  `configurePIDWrapping` unconditionally for every angle motor, with no
  primary-encoder seeding on that path, and five other repositories run
  the same combination, one of them carried across a full season — had
  wrapping silently not applied, every one of them would have watched
  modules unwind through 350°. **[field — #34]** The architecture agrees:
  `kPositionPIDWrapEnable` (149), `kPositionPIDMinInput` (150) and
  `kPositionPIDMaxInput` (151) are **global — not per-slot, not
  per-sensor** (`SparkParameters.java:137-139`), where every genuinely
  sensor-scoped parameter in that enum is namespaced
  (`kAnalogPositionConversion` 119, `kDutyCyclePositionFactor` 139),
  and the wrap block carries no sensor discriminator. **[source]**

  **The residual is narrow and Flex-specific.** Every field instance is
  a SPARK MAX at 0–3.3 V on REVLib 2024/25; we run a Flex at 0–5 V on
  2027 firmware. So **turn one module across the wrap boundary before
  tuning anything.** That is a sanity check, not a gate — it blocks no
  decision and no other work.

  *If it fails*, the fallback is to close steer on the **primary
  encoder, seeded from the analog at boot** —
  `SparkRelativeEncoder.setPosition(double)` exists
  (`com/revrobotics/spark/SparkRelativeEncoder.java:64`) **[source]** —
  and to accept the backlash the Decision rejected. It is **not** to
  fold shortest-path into the setpoint on SystemCore: with no
  accumulator on the analog, that needs `s = p + shortest(t − p)`
  recomputed every loop, and each boundary crossing then leaves a
  ≤5 ms window where the stale setpoint reads as a ~1-rotation error —
  full-output kick plus a saturating D spike, at one specific azimuth,
  every crossing.

- **The latency this decision rests on is unmeasured.**
  **[unverified]** No CAN hardware is attached to the bench Pi, so the
  round-trip cost of a SystemCore-side loop is argued from first
  principles rather than measured. *Unblocked by* a bus with a SPARK on
  it — at which point the number is worth recording in
  `docs/research/` whichever way it falls.

- **`dFilter` has no specification**, so the Decision's
  tune-D-and-the-filter-together instruction is a coping strategy
  rather than a method. **[source]** *Unblocked by* REV documenting the
  parameter, or by a bench sweep that establishes empirically what the
  number does.

- **Gain values, and the characterisation that produces them**, are
  ADR 0009's. This ADR fixes where the loops run and what feeds them;
  it decides no numbers.

## Rejected

### Closing either loop on SystemCore

The loop would run at 200 Hz instead of 1 kHz and pay a CAN round trip
per iteration, and for steer it would cost about +800 frames/s to carry
both encoder frames at rate. The feedforward argument that used to
justify it — *you need the RIO to get `kS` and `kV`* — died with
`FeedForwardConfig`.

*Do not re-raise* without a measured latency figure that says the round
trip is cheaper than assumed, or a control problem the SPARK's
firmware genuinely cannot express.

### Jitter immunity as the ground for this decision

A SPARK-side loop does not ride out SystemCore scheduler gaps worth
having, because the gaps are not there: steady-state worst-case wake is
5.16–5.23 ms **[measured — ADR 0002]**. What remains is cold code at a
mode transition, and that is close to symmetric — a frozen setpoint on
the SPARK and a frozen output on SystemCore are both harmless at
enable, when nothing is moving.

**Do not cite the 32.9 ms figure for this decision.** It is withdrawn
on #31, and this decision stands on latency instead.

### Steer on a boot-seeded primary encoder

Covered at the Decision: it closes the loop on motor position, and
motor position differs from module angle by the reduction's backlash.
Resolution and filtering favour the relative encoder; the quantity
being controlled favours the absolute, and that wins. It survives as
the named fallback under Open if wrapping fails on the Flex, and only
there.

### A duty-cycle absolute encoder into pin 6

Real benefits: `kAbsoluteEncoder` would restore `zeroCentered(true)`,
deleting the `Rotation2d` conversion outright rather than working
around it, and `averageDepth` would give steer a filtering lever the
analog path does not have. Both are conveniences, and replacing
hardware the team already owns needs a better reason than two
conveniences. The clipping hazard that would have been a better reason
does not exist — the Flex's analog input reaches the supply rail.

*Do not re-raise* unless steer noise is measured to matter and
`dFilter` is measured not to fix it.

### `arbFeedforward` as the home for `kS` and `kV`

It works — the term is applied in Volts, after the control mode and
before current limits — and it is how a team without
`FeedForwardConfig` would have done it. Rejected because it puts the
feedforward on the *robot* side of a loop that runs on the controller,
recomputed at 200 Hz for a loop iterating at 1 kHz, and because two
plausible homes for one term is exactly the configuration that produces
the doubling trap above. `arbFeedforward` carries exactly one thing,
and it is not a velocity term: ADR 0011's drivebase `kA · a`, on the
auto path. Teleop passes none at all.

### Calling `SparkSim.iterate()` for simulation fidelity

Covered in Traps: it feeds integrated position into the position loop
regardless of feedback sensor, applies the wrong conversion factor to
`kAnalogSensor`, and never wraps. #25 ruled that hand-writing a copy of
REV's onboard loop was not worth it for fidelity; a *model* is a
different thing at a different bar, and it is what ADR 0010 builds.

### Tuning gains through the REV Hardware Client

Not a discipline, a mechanism: PID gains are safe writable parameters
and are not on `kResetSafeParameters`' exception list, so a value typed
into the GUI is erased at the next boot. ADR 0004 owns the rule; it is
restated here because a controller mid-tune is exactly when someone
reaches for the GUI.

## Source

Decided in
[#29](https://github.com/Drew-Robotics/2027beta/issues/29), which
carries the loop placement, the sensor choice, the CAN accounting and
the feedforward finding; corrected by
[#33](https://github.com/Drew-Robotics/2027beta/issues/33), which
settles the Flex analog input range and the wiring, and voids the
clipping hazard and the duty-cycle alternative that rode on it; and
[#34](https://github.com/Drew-Robotics/2027beta/issues/34), which
answers `positionWrappingEnabled` against `kAnalogSensor` from field
deployment and bounds the residual.

The withdrawn jitter figure is
[#31](https://github.com/Drew-Robotics/2027beta/issues/31); the CAN
budget it leaves untouched is
[#28](https://github.com/Drew-Robotics/2027beta/issues/28) and
ADR 0007. The config rules it inherits are
[#13](https://github.com/Drew-Robotics/2027beta/issues/13) and
ADR 0004; the sim seam it hands off to is
[#14](https://github.com/Drew-Robotics/2027beta/issues/14) and
ADR 0010; the acceleration-feedforward question it settles for *this
controller only* is
[#15](https://github.com/Drew-Robotics/2027beta/issues/15) as amended
by [#32](https://github.com/Drew-Robotics/2027beta/issues/32) and
ADR 0011; gains and characterisation are ADR 0009.

Research: [`docs/research/vendordeps.md`](../research/vendordeps.md),
[`docs/research/wpilib-swerve.md`](../research/wpilib-swerve.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpimath/src/main/java/org/wpilib/math/geometry/Rotation2d.java`,
`wpimath/src/main/java/org/wpilib/math/util/MathUtil.java`.

In REVLib `2027.0.0-alpha-6` (sources jar):
`com/revrobotics/spark/FeedbackSensor.java`,
`com/revrobotics/spark/SparkBase.java`,
`com/revrobotics/spark/SparkAnalogSensor.java`,
`com/revrobotics/spark/SparkRelativeEncoder.java`,
`com/revrobotics/spark/SparkClosedLoopController.java`,
`com/revrobotics/spark/SparkSim.java`,
`com/revrobotics/spark/config/AnalogSensorConfig.java`,
`com/revrobotics/spark/config/AbsoluteEncoderConfig.java`,
`com/revrobotics/spark/config/ClosedLoopConfig.java`,
`com/revrobotics/spark/config/FeedForwardConfig.java`,
`com/revrobotics/spark/config/SignalsConfig.java`,
`com/revrobotics/spark/config/SparkParameters.java`.

REV's SPARK MAX and SPARK Flex specification tables are the source for
the analog input ranges and the data-port pinouts, read through #33.
