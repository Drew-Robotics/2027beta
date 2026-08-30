# ADR 0009 — Characterisation and tuning

## Status

Accepted — 2026-08-26. Amended 2026-08-29, on implementing it: the
analyser fits one model over all four tests combined, so *every* routine
runs all four whatever it can use of the result — see *Traps* and the
steer decision. The routines are also enumerated now that there are
four of them, and a wheel-radius measurement joins them. Amended again
2026-08-29, on building the frame raise: the voltage column is the
applied output rather than the request, and it is honest under a stated
condition rather than unconditionally.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. REVLib
`[source]` claims were read in `REVLib-java 2027.0.0-alpha-6`, the
version `vendordeps/REVLib.json` pins, from its sources jar; paths below
are given as `com/revrobotics/...`. An unqualified path is a file in
this repo.

## Context

ADR 0008 put both module loops on the SPARK and decided no numbers. It
owes a `kS`, a `kV` and a `kP` for drive, a `kP`/`kD` and a `kS` for
steer. ADR 0011 owes a `kA`. None of those exist, and none of them can
be guessed: a feedforward gain is a measurement of a physical machine.

Characterisation is how the measurement is taken — drive the mechanism
with a known voltage, record what it does, and fit a model. Tuning is
the shorter loop after it: change a number at a bench, watch, change it
again.

This area opened on a premise that turned out to be wrong, and the
premise is worth naming because it is the reason this document is short.
`SysIdRoutine` — the WPILib class every team uses to run a
characterisation — is a Commands v2 class, and v2 and v3 cannot coexist
(ADR 0001). The conclusion drawn from that was that characterisation had
to be invented from scratch. It does not. **What is missing is one
class.**

This ADR decides how the numbers are produced, where they live, how they
are changed at a bench, and what an agent must know before it reads a
characterisation log.

## Decision

### Characterisation is `SysIdRoutine`, ported to v3 and carried locally

Three things make up SysId, and only one of them is v2-coupled.

**The log schema survives, in `wpilibj`.**
`org.wpilib.sysid.SysIdRoutineLog`
(`wpilibj/src/main/java/org/wpilib/sysid/SysIdRoutineLog.java`, 229
lines) has no command dependency at all. It writes `DoubleLogEntry` and
`StringLogEntry` straight into `DataLogManager`'s WPILOG (`:103`,
`:225`), with the test state under `sysid-test-state-<logName>`
(`:225`). **[source]**

**The analyser survives, in `tools/sysid`.** `OLS.cpp` (88 lines),
`FeedforwardAnalysis.cpp` (274), `FeedbackAnalysis.cpp` (85) and
`FilteringUtils.cpp` (446) are all present and building. **[source]**

**Only `SysIdRoutine` itself is v2-coupled** —
`commandsv2/src/main/java/org/wpilib/command2/sysid/SysIdRoutine.java`,
277 lines. **[source]**

So the port is the command wrapper and nothing else, and because the
schema and the analyser are already under `org.wpilib`, this is the
prefer-built-in-WPILib answer rather than a deviation from it.
**[decided]**

The port is small and mechanical. `Subsystem` becomes `Mechanism`;
`subsystem.runOnce(...).andThen(subsystem.run(...))` becomes
`Mechanism.run(Consumer<Coroutine>)` closed with `.named(...)`; and
`finallyDo` becomes `whenCanceled`. That last one is not a rename — see
Traps.

```java
return mechanism
    .run(coroutine -> {
      timer.restart();
      while (true) {
        drive.accept(rampRate.times(Seconds.of(timer.get() * sign)));
        log.accept(this);
        recordState(state);
        coroutine.yield();
      }
    })
    .whenCanceled(() -> {
      drive.accept(Volts.zero());
      recordState(State.NONE);
    })
    .named("sysid-" + state + "-" + name)
    .withTimeout(timeout);
```

The copy lives in **`first.robot.sysid`**, mirroring the upstream package
it came from so that it stays identifiable as carried code. Write it as
the upstream patch would be written. **[decided]** Filing it upstream is
not committed to here.

### The drive callback writes volts, and the SPARK has a voltage mode

`SparkBase.setVoltage(double)` issues `ControlType.kVoltage`
(`com/revrobotics/spark/SparkBase.java:217-220`) — the device holds the
requested voltage internally rather than the robot computing a
compensation **[source]**. So the `Voltage` the routine hands out is the
voltage the controller applies **while nothing else is limiting the
output**, and the current limit is something else limiting the output.
See *Traps*: the request and the application are the same number for the
quasistatic ramp and part of the dynamic step, and they part company
through the rest of it.

**So the column is not the request.** The voltage logged is
`getAppliedOutput() × getBusVoltage()` — the controller's own report of
the duty cycle it ran at, multiplied back by the rail it was a fraction
of. **[decided]** That is a number the robot reads rather than one it
wrote, which is what the frame raise below is for.

**The column is honest while the applied-output frame is at the loop
rate, and stale by up to one frame period otherwise.** It is a status
frame, not a readback: what `getAppliedOutput()` returns is the last
frame that arrived, so at the 100 ms diagnostic period a 5 ms log
repeats one sample twenty times. The raise is what makes the condition
hold for the length of a run, and nothing outside a characterisation
holds it.

The closed loop is not running during a characterisation. Open-loop
voltage in, velocity out, is the whole test.

### Four routines, and what each one moves

**Drive velocity — all four modules, wheels locked forward.** The steer
loop holds zero azimuth through the ramp and the drive ramp is written
to every module. A gain measured off one powered wheel dragging three
unpowered ones is a measurement of a machine we do not have, and the
winner is applied to all four of that role anyway (see Consequences).
The wheels are *settled* into that azimuth before the ramp starts: a
module still slewing turns its own drive encoder through the module's
coupling, and that motion lands in exactly the low-voltage samples `kS`
is fitted from. **[decided]**

**Steer — one module.** Steer is a module-level measurement; the gain is
a property of one module's azimuth axis, and four modules working the
carpet at once is three extra ways for the one being measured to be
pushed. The other three are dropped. **[decided]**

**Whole-robot rotation — all four modules, wheels tangent to the spin.**
The columns are the *robot's*: the applied voltage against the Pigeon's
yaw and yaw rate. **It is for turning the robot to an angle** — the
rotate-to-angle side of teleop and autonomous, which no module-level test
says anything about. `kS` is the smallest chassis `omega` that breaks the
robot away at all, which is the floor a heading controller's output
disappears below; `kV` and `kA` are the largest a profile may ask for,
and they replace `MAX_ANGULAR_VELOCITY`'s nameplate arithmetic with a
measurement. **[decided]** The azimuths come from the kinematics
rather than being written out as four angles, so they cannot disagree
with it. Pigeon2 yaw spans ±368640°
(`com/ctre/phoenix6/hardware/core/CorePigeon2.java`, `getYaw`)
**[source]**, so the position column is continuous where a `Rotation2d`
would fold it into half a turn.

**Wheel radius — not a SysId routine at all.** It is 6328's measurement
(`Mechanical-Advantage/RobotCode2024Public`,
`src/main/java/org/littletonrobotics/frc2024/commands/WheelRadiusCharacterization.java`)
**[field]**: spin the robot slowly on the spot, and the arc each wheel
rolled through has to equal the arc the robot turned through at the
drive radius, so `radius = |yaw| · driveRadius / meanWheelRadians`. It
produces no feedforward gain and writes no sysid log — it writes one
number to the telemetry table, and that number is
`DRIVE_POSITION_FACTOR`'s. **[decided]**

The encoder's own position already carries the *assumed* radius, so the
wheel angle is recovered by dividing it back out. Dividing by the
nominal radius and then solving for the radius is what leaves a
measurement rather than a restatement of the constant.

Slowly, and this is the whole of the method's fragility: the arithmetic
assumes the wheels rolled rather than slipped, and the estimate means
nothing before a full turn, because the error in where the modules were
pointing at the start is otherwise a large share of the arc.

### The analyser is fed a simulated log before it is fed a real one

`./gradlew sysidLog` runs the three routines against ADR 0010's plant and
writes `logs/sysid-simulation.wpilog`, which opens in the analyser like
any other. It is the same run the pipeline test asserts on, so a log that
opens is a log CI checked. **[decided]**

The clock is what makes it worth anything. `DataLog` stamps every record
with `wpi::Now`, and the simulation HAL points that at its own monotonic
time (`hal/src/main/native/sim/MockHooks.cpp:27`) **[source]**, so a
harness left on the wall clock writes a minute of simulated ramp into a
fraction of a second and the analyser fits a derivative of nonsense. The
harness steps `SimHooks` instead, and a test asserts the span.
**[executed]**

⚠️ The gains it produces are the model's. Under free-space physics the
fit recovers whatever went into the plant, so a number read off this file
and written into `Constants` is the failure this ADR's sim/real split
exists to prevent.

### The analyser eats a WPILOG, and what it requires is the state strings

`tools/sysid` opens `*.wpilog` (`view/LogLoader.cpp:36`) **[source]**.
Inside the GUI you drag **one string entry** onto *Test State* and
**three numeric entries** onto *Velocity*, *Position* and *Voltage*
(`view/DataSelector.cpp:163-165`), and nothing analyses until all four
are set (`:173-174`). **[source]**

Tests are then discovered by splitting the state **values**: the first
token must be `quasistatic` or `dynamic` and the last must be `forward`
or `reverse`, or the entry is warned away and dropped
(`DataSelector.cpp:101-112`). **[source]** That is exactly what
`SysIdRoutineLog.State.toString()` emits
(`SysIdRoutineLog.java:54-60`). **[source]**

**Entry names are free; the state strings are not.** A port that
renames the log entries costs a reader two extra drags. A port that
changes `"quasistatic-forward"` produces a log the analyser refuses.

**We log voltage, position and velocity — the three the analyser
requires — and neither acceleration nor current.** **[decided]**
`SysIdRoutineLog` offers both as optional (`SysIdRoutineLog.java:166,
181, 196`) **[source]**; acceleration is a column the analyser derives
for itself with its own filter (see Traps), and nothing in this project
consumes the current column. Position is logged because the analyser
requires it, not because a velocity fit needs it.

### Characterisation raises one frame on one SPARK, not tuning's three

ADR 0007 already carries drive velocity (Status2) and steer position
(Status3) at the 5 ms loop rate for odometry, so the sysid columns are
already at rate. What is not at rate is **applied output**, which lives
on Status0 at 100 ms.

**Characterisation raises Status0 to loop rate on the single SPARK under
test** — 10 to 200 frames/s, **+190 to ~4110 total** **[unverified —
arithmetic; ADR 0007 owns the budget]** — and raises nothing else.

The raise is written by the command that runs the routine, not by the
opmode that binds it: it goes up before anything the command does and
comes back down when the routine ends, on both the path where the body
finishes and the path where it is cancelled. For drive and rotation the
settle sits between the two, so the frame is at rate well before the
first sample. **Steer has no settle**, so its raise and its first sample
share a loop, and a steer log can open on one sample up to a frame period
stale.

`appliedOutputPeriodMs` and `busVoltagePeriodMs` are both Status0
(`SignalsConfig.java:95-96, 115-116`) **[source]**, so the two signals the
column is computed from ride up together, and output current and motor
temperature come with them for no extra frame. Faults and warnings are
Status1 (`:192-193, 230-231`) **[source]** and are not touched, so the
restore does not quietly slow the fault frame down with it. **[decided]**

That is a *different* raise from ADR 0007's tuning allowance, which
takes Status0 + Status7 + Status8. Status7 is `iAccumulation` and
Status8 is the setpoint readback (`SignalsConfig.java:671-672`,
`:708-709`) **[source]**, and during a characterisation there is no
closed loop, so both are reporting nothing. Raising them buys a reader
two columns of zeros at 390 frames/s.

Which controller is instrumented is a constant naming one module, exactly
as ADR 0007 requires: a redeploy, not a dashboard setting. The motor role
is the routine's rather than a second constant — drive and rotation
instrument that module's drive SPARK, steer its steer SPARK — because a
routine that raised the frame on a controller it does not read would log
a column at 100 ms and a raise at 5 ms.

**The restore is a blocking `configure()` on the loop thread**, and it
runs from the cancellation path, so a run that ends in a cancel can
overrun its loop while the write retries. That is accepted: it happens
once, at the end of a supervised bench test, with the mechanism already
stopped — and deferring it is how a frame stays raised into a match.

### Feedforward stays split across two machines, and steer's is `kS` alone

`kS` and `kV` live on the SPARK in `FeedForwardConfig`, in Volts and
Volts per velocity, applied at 1 kHz against the controller's own
velocity estimate — which is the whole reason ADR 0008 put the loops
there. `kA` lives on the SystemCore side as ADR 0011's `arbFeedforward`
volts, because it has nowhere else to go. **[decided]**

**Exactly one home per term.** A `kV` in both places doubles the
feedforward with nothing thrown; ADR 0008 owns that trap.

**Steer's feedforward is `kS` and nothing else.** `kV` is *"not applied
in Position control mode"* (`com/revrobotics/spark/config/FeedForwardConfig.java:75,
178`) and `kA` is *"only applied in MAXMotion control modes"* (`:91,
194`) **[source]**, and steer takes no profile (ADR 0008). So the only
number steer's characterisation keeps is the `kS` its quasistatic ramp
finds at break-away.

⚠️ **It still runs the dynamic pair, and that pair is not spare.**
This ADR said there was no dynamic test for steer. That was right about
where the gains can live and wrong about the measurement: the analyser
concatenates slow-forward, slow-backward, fast-forward and fast-backward
into one dataset and runs one regression over it
(`analysis/AnalysisManager.cpp:102-109, 154-181`) **[source]**, so the
dynamic data helps fit the `kS` steer keeps. What has nowhere to go is
the `kV` and `kA` printed beside it, and that is a fact about the
controller rather than about the run. See *Traps*.

### The loop-period rule does not bind the on-SPARK gains

ADR 0002 rules that gains do not transfer across loop periods unless the
period actually reached the controller, so a gain recorded without its
period is a number nobody can reproduce.

**That rule does not apply to `kS`, `kV` or `kP` on the SPARK, and this
is stated here rather than inherited.** Those gains belong to a
controller that runs at 1 kHz and never sees our 5 ms. Writing
`Constants.LOOP_PERIOD` beside them would record a number that misleads
a future reader into rescaling.

WPILib's own analyser says the same thing in the same place it would
matter: the REV feedback preset carries `period = 1_ms`
(`tools/sysid/src/main/native/include/wpi/sysid/analysis/FeedbackControllerPreset.hpp:137-138`),
against the `WPILIB` preset's 20 ms (`:74, 76`). **[source]**

**Record it once: the on-SPARK gains — drive's `kS`/`kV`/`kP` and
steer's `kS`/`kP`/`kD` — are closed on the SPARK at 1 kHz.**
**[decided]**

⚠️ ADR 0002's rule keeps full force on the SystemCore side, and `kA` is
now on that side — ADR 0011's `kA · a` is computed in our loop at
200 Hz, and it is recorded with the period like everything else there.

### Gains live in `Constants`, and vary for sim and real

Gains are constants in `Constants`, applied through ADR 0004's factory
method per motor role, with a **sim set and a real set branched on
`RobotBase.isSimulation()`**
(`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java:316`)
**[source]** — a compile-time branch, with no identity detection
anywhere in it. **[decided]**

The sim gains want a comment at the line saying they exist to make the
model track and are **not** a prediction of the real robot's. That is
the one thing about them a reader cannot recover from the code.

This is a narrow graduation of the robot-identity question, not the
whole of it. Competition-versus-practice gains stay fog — see Open.

### Runtime tuning is a `@Utility` opmode, and a tuned gain dies at power-off

Tuning changes a number without a redeploy: a `@Utility` opmode holding
`Tunable` gains re-`configure()`s the SPARK when one changes, with
`kNoPersistParameters` and `kNoResetSafeParameters`
(`com/revrobotics/PersistMode.java:32`,
`com/revrobotics/ResetMode.java:32`). **[source]**

So a tuned gain survives until the next power cycle and no longer. That
is a mechanism, not a discipline: the hardware enforces *write the
winner into the repository or lose it*. **[decided]** ADR 0008 records
the same shape from the other side.

### Characterisation runs on the ground, supervised

**On-blocks numbers are useless for a drive base.** `kS` is the voltage
that breaks the mechanism away from stiction, and the stiction of a
wheel carrying no robot is not the stiction of a wheel carrying one.
`kV` measured against no rolling resistance is a measurement of the
motor, not the drive base. **[decided]**

ADR 0006 carries the `@Utility` rule as *safe to run with the robot on
blocks, and must not require a field, a driver, or a game piece*.
Characterisation breaks it head-on, so **the rule is restated rather
than excepted**: its real content is *nothing runs unattended that can
hurt someone or something*, and "on blocks" was a proxy for that.
Restated as a supervision requirement, with characterisation named
explicitly as supervised-on-ground. A rule with an exception beside it
is a rule with two meanings. **[decided]** ADR 0006 and the house-style
document are amended to match.

### There is no `/tune` skill

The test #18 set was *process with a completion bar → skill; reference →
pointer*, and this is a pointer. **Declined for a structural reason, not
as a deferral.** **[decided]**

A sysid log **is a WPILOG**, so ADR 0014's `logtool query` already pulls
the columns with no new tooling, and turning columns into gains is
domain knowledge — which is exactly the boundary ADR 0014 draws: the
tool answers *what*, the agent answers *why*. The arithmetic an agent
would be reimplementing is small: `OLS.cpp` is 88 lines and
`FeedforwardAnalysis.cpp` is 274. **[source]**

The caveat is preprocessing, not regression, and it is the first entry
in Traps.

## Consequences

- **Sysid data bypasses the telemetry seam entirely.**
  `SysIdRoutineLog` calls `DataLogManager.getLog()` directly
  (`SysIdRoutineLog.java:103, 225`) **[source]** rather than going
  through a mechanism's `TelemetryTable` (ADR 0005). Under ADR 0005's
  `logNetworkTables(false)` that is what makes it work at all — and it
  means the sysid columns land in the same file, on the same USB stick,
  as every other signal, with no second data path to manage.

- **A local copy of a WPILib class is a carry, not a design.** It
  diverges the moment it is treated as ours. The rule is that it is
  edited only to track upstream or to complete the v3 port, and that
  anyone reaching for it to add a feature is doing something this ADR
  did not decide.

- **`tools/sysid` has no headless mode and no library target.**
  `Main.cpp`'s `main` takes at most a save directory
  (`tools/sysid/src/main/native/cpp/Main.cpp`) and the analysis lives in
  the same GUI binary. **[source]** The code is already
  directory-separated (`src/main/native/cpp/analysis/`, with `OLS.cpp`
  and `FeedforwardAnalysis.cpp` carrying no GUI dependency), so exposing
  it would be a Gradle change rather than a rewrite — a natural upstream
  contribution, noted here and not ticketed.

- **One SPARK is enough to tune four.** The modules are identical and
  ADR 0004 shares gains through a factory method per motor role, so the
  winner from one controller is applied to all four of that role.

- **ADR 0011's `kA` is a module gain; only its acceleration is
  drivebase-level.** The dynamic test produces Volts per unit of wheel
  acceleration on one module. What "drivebase level" names is the
  *source* of the acceleration — the trajectory's own samples, converted
  per module — not a different quantity. An implementer looking for a
  chassis-level `kA` to characterise will not find one.

- **The sim gains are a second set that no characterisation produces.**
  ADR 0013's assertions therefore run a controller the robot does not
  run. That is accepted; the assertions are about behaviour, not
  numbers. The failure mode to guard against is the inverse — sim gains
  quietly adjusted until a test passes, which converts a test into a
  tautology.

- **REVLib's Status Logger stays on by default.** It is REV's 2026
  successor to 6328's URCL and it is present in REVLib 2027 alpha-6
  (`com/revrobotics/util/StatusLogger.java`,
  `com/revrobotics/jni/StatusLoggerJNI.java`), and `REVLib-driver`,
  `RevLibBackendDriver` and `RevLibWpiBackendDriver` all list
  `linuxsystemcore` among their valid platforms
  (`vendordeps/REVLib.json`). **[source]** It is free
  insurance against under-logging, which matters more than usual with
  AdvantageKit rejected and no replay path (ADR 0005). Three limitations
  ride with that, and all three are recorded rather than discovered:

  1. **It has no configuration surface.** `StatusLogger` exposes
     exactly `start()`, `stop()` and `disableAutoLogging()`
     (`StatusLogger.java:47, 61, 78`) **[source]**. No path, no
     rotation policy, no device filter, no rate. What it writes and
     where it writes it are not ours to set.
  2. **`.revlog` is proprietary and invisible to `logtool`.** ADR 0014's
     tool reads WPILOG; converting a `.revlog` needs AdvantageScope or
     the npm package `@rev-robotics/revlog-converter`, a Node toolchain
     in a repo whose only tool is Python `uv run`. So nothing in
     `.revlog` reaches `/analyze-match`.
  3. **It has never run on SystemCore here.** **[unverified]** There
     are zero `.revlog` files on the bench Pi, re-checked 2026-08-26
     **[executed]**; the `linuxsystemcore` native is a different binary
     from the roboRIO one; and REV's documentation names *"roboRIO
     internal storage or USB"* as the write targets, on a box that is
     neither.

  Disk is **not** among the objections: the Pi holds 4.9 GB free of
  6.8 GB, 24% used **[executed — 2026-08-26]**, which is hundreds of
  matches at ADR 0005's measured 13.1 MB per match, so two rotation
  policies have room to coexist.

- **Status Logger does not dodge the CAN budget.** It is a sink, not a
  source: frame rates remain `SignalsConfig`'s, whose setters still take
  `Math.min` (`SignalsConfig.java:46`) **[source]**. It records what the
  bus already carries and cannot make a SPARK talk faster, so ADR 0007's
  arithmetic is untouched by it.

- **Steer's `dFilter` gets no help from any of this.** Characterisation
  produces feedforward, steer's feedforward is `kS`, and `dFilter` sits
  in the feedback half with no specification (ADR 0008). It stays a
  bench problem.

## Traps

- **An agent that OLS's the raw columns gets gains that look plausible
  and are wrong.** The regression is 88 lines; the preprocessing in
  front of it is 446, and it is not optional. **[source]** The five
  steps, with the function that performs each, so that a log-reading
  agent can reproduce them or say that it did not:

  - **`InitialTrimAndFilter`** (`FilteringUtils.cpp:342-423`) drives the
    rest. **Quasistatic data is trimmed before break-away**: every point
    with `|voltage| <= 0` or `|velocity| < velocityThreshold` is deleted
    (`:372-382`). This is the trim that matters most — a quasistatic
    ramp spends its first seconds raising voltage against a wheel that
    has not moved, and in that stretch `sgn(velocity)` is the sign of
    *noise*, so those samples enter a `kS · sgn(v) + kV · v` fit with
    random signs and drag `kS` toward zero.
  - **`GetNoiseFloor`** (`:207`) supplies that threshold when it is left
    unset, as the mean noise over a 9-sample window
    (`:355-364`, `FilteringUtils.hpp:26`). The settings default is
    `0.2` rather than "unset" (`AnalysisManager.hpp:61`), so which of
    the two a run used is itself a thing to record.
  - **`ApplyMedianFilter`** (`:275`) defaults to a window of 1, i.e.
    off (`AnalysisManager.hpp:66`) — a lever a reader chooses, not a
    step that already happened.
  - **`PrepareMechData`** (`:73-117`) recomputes acceleration as a
    central finite difference over a 3-point window (`:75, 99-115`),
    with `GetMeanTimeDelta` (`:232, 245`) supplying the timestep.
    **Acceleration is a numerical derivative of a noisy signal, and it
    is the analyser's derivative, not the log's.** `SysIdRoutineLog`
    calls the acceleration column optional and says *"SysId can perform
    an accurate fit without it"* (`SysIdRoutineLog.java:166, 181`)
    **[source]** precisely because of this. An agent that logs its own
    robot-side derivative and regresses on that is regressing on a
    different signal.
  - **`TrimStepVoltageData`** (`:119-204`) trims the dynamic data at
    both ends — to start at peak acceleration, and to a step duration
    computed from where 90% of max speed is reached — and
    **`AccelFilter`** (`:425-446`) then removes every remaining point
    whose acceleration is exactly zero.

- **`kV` is not applied in Position mode, and on-SPARK `kA` only in
  MAXMotion modes.** *"This is not applied in Position control mode"*
  (`FeedForwardConfig.java:75, 178`) and *"This is only applied in
  MAXMotion control modes"* (`:91, 194`). **[source]** Steer runs plain
  position with no profile, so **`FeedForwardConfig.kA` is permanently
  out of reach for steer** — there is no configuration that makes it
  apply. Both are configured-and-ignored failures of the worst shape:
  `configure()` succeeds, `kOk` comes back, no alert fires, and the gain
  does nothing. The only defence is knowing.

- **Cleanup goes in `whenCanceled`, and for these commands it is the
  only path that ever runs.** `Command.withTimeout` is implemented as
  `race(this, waitFor(timeout))` (`Command.java:218-222`) **[source]**,
  and the quasistatic and dynamic bodies loop forever — so the timeout
  always wins and the command is always **cancelled**. v2's `finallyDo`
  ran on both the end and the interrupt path (`SysIdRoutine.java:230-235,
  269-273`) **[source]**; a v3 port that puts the zero-volt write and the
  `State.NONE` record after the loop in the coroutine body compiles clean
  and never runs them. The symptom is a drive base that keeps its last
  ramp voltage after the test ends, on the ground, with people around
  it. `StagedCommandBuilder.whenCanceled(Runnable)`
  (`StagedCommandBuilder.java:126`) **[source]** is where they go.

- **The loop in the ported body must be `while`.** ADR 0006's rule, and
  this is the one place in the repo where the loop is being *invented*
  rather than copied — the v2 original has no loop, because v2 called
  `execute()` once per iteration. A `for(;;)` compiles clean, passes the
  compiler plugin's missing-`yield` check, and hangs the robot.

- **`setThrottle` is duty cycle; `setVoltage` is volts.** `setThrottle`
  issues `ControlType.kDutyCycle` (`SparkBase.java:202-207`) and
  `setVoltage` issues `ControlType.kVoltage` (`:217-220`). **[source]** A
  `drive` callback that converts the routine's `Voltage` into a throttle
  logs volts and applies a fraction of a sagging bus, and the fit is then
  against a voltage that was never applied. The error scales with how
  flat the battery is, so it is worst in the last test of a session.

- **The analyser's REV preset assumes a duty-cycle output and an RPM
  velocity.** `REV_NEO_BUILT_IN` is `{1.0/12.0, 60.0, 1_ms, false,
  112_ms}` (`FeedbackControllerPreset.hpp:137-138`). **[source]** The
  `1_ms` is right and is the reason the preset is the correct one to
  pick. The `1.0/12.0` and `60.0` are assumptions about *our*
  configuration: with `velocityConversionFactor` set so the encoder
  reports in our units rather than RPM, the factor of 60 is already
  applied and applying it again scales the feedback gain by 60. **This
  reaches the feedback gain the analyser offers, not `kS`/`kV`/`kA`** —
  the feedforward fit is on the logged columns and knows nothing about
  the preset. Picking the `WPILIB` preset instead is the quieter
  mistake: it is `DEFAULT`, at `20_ms` (`:74, 76`) **[source]**, which is
  neither our 5 ms nor the SPARK's 1 ms, and nothing about the number it
  returns looks wrong.

- **The SPARK's velocity signal is delayed about 112 ms by its own
  filter, by default.** The preset's `112_ms` is `(8 − 1) / 2 × 32 ms`,
  computed for an 8-sample moving average at 32 ms
  (`FeedbackControllerPreset.hpp:133-138`) **[source]** — and REVLib
  2027 still defaults `uvwAverageDepth` to **8** and
  `uvwMeasurementPeriod` to **32 ms**
  (`com/revrobotics/spark/config/EncoderConfig.java:145-152, 172-186`)
  **[source]**, so a number derived in 2022 is still this year's number.
  A quasistatic ramp is slow enough not to care. The **dynamic test is
  exactly where a 112 ms lag on the regressor bites**, and the dynamic
  test is what produces the `kA` ADR 0011 needs. The lever is those two
  encoder settings, and they change the *measurement*, not the plant —
  see Open.

- **A current-limited motor applies a voltage that rises with speed, and
  a column carrying the request would read it as flat.** A smart current
  limit holds the output to `backEmf ± currentLimit · R`; for a NEO
  Vortex at ADR 0008's 60 A that span is 3.41 V, so a 7 V step applies
  **3.41 V from rest** and only reaches 7 V at **1.82 m/s**. Constant
  current is constant torque, so acceleration is flat across that whole
  stretch — measured at 8.547 m/s² below 1.82 m/s in a simulated run
  **[executed — 2026-08-29]** — and it shows in the analyser as a
  horizontal segment in *Acceleration vs Velocity*.

  `tools/sysid` does not remove it. `TrimStepVoltageData` erases data
  before peak acceleration, and under limiting the acceleration *is* the
  peak from the first sample, so the limited stretch survives into the
  fit whole. In that run it took `kA` from the plant's 0.4052 to a
  fitted 0.4631, **+14%** — on the one gain the dynamic test exists to
  produce and the one ADR 0011 hands to `arbFeedforward`. **[executed]**
  The fix is to log what was applied rather than what was asked for. The
  column is `getAppliedOutput() × getBusVoltage()` and the frame carrying
  both runs at the loop rate for the length of a run, which is the
  decision above.

- **A step test begun on the move reports the time it spent stopping as
  measurement delay.** `TrimStepVoltageData` takes the velocity delay as
  the gap between the first sample carrying voltage and the first
  carrying near-peak acceleration, and it ranks acceleration by
  `sgn(velocity) · acceleration` (`analysis/FilteringUtils.cpp:143-170`)
  **[source]**. While the robot is still rolling the other way that
  product is negative, so nothing qualifies until the velocity crosses
  zero. Running the four tests back to back with no pause put a drive
  dynamic test on the log at −2.26 m/s and reported **332.5 ms** of
  velocity delay on a plant that models no measurement dynamics at all;
  settling first brought it to 5 ms, which is one loop period and the
  floor. **[executed — 2026-08-29]** The number is not nonsense and the
  fit is not saved by the trim being generous: it is a true measurement
  of a manoeuvre nobody is characterising. Every ramp gets a settle in
  front of it, and an operator running these by hand waits for the robot
  to stop.

- **The analyser refuses a log that is missing any of the four tests,
  whatever the mechanism can use.** `DataSelector` checks the discovered
  state values against
  `VALID_TESTS = {quasistatic-forward, quasistatic-reverse,
  dynamic-forward, dynamic-reverse}`
  (`view/DataSelector.hpp:81-83`) and collects whatever is absent
  (`view/DataSelector.cpp:138-145`); `App.cpp:112` hands the list to the
  analyzer, and `Analyzer::PrepareData` throws `MissingTestsError`
  before `AnalysisManager::PrepareData` runs at all
  (`view/Analyzer.cpp:276-277`), with *"The following tests were not
  detected: … Make sure to perform all four tests"*
  (`analysis/FilteringUtils.hpp:74-88`). **[source]** It is not a
  formality: all four are combined into one dataset before anything is
  fitted (`analysis/AnalysisManager.cpp:102-109`) **[source]**, so the
  four tests are one measurement rather than four. *"This mechanism has
  no gain for a dynamic test to produce"* is therefore an argument about
  which output column is usable, never about which tests to run. A
  routine that skips a pair produces a log the analyser will not open,
  and the failure arrives as a dialog after a bench session rather than
  as anything at the robot.

- **Two routines that share a mechanism name silently interleave.**
  `SysIdRoutineLog` names each entry `<field>-<motor>-<logName>`
  (`SysIdRoutineLog.java:105`), and the log name is the mechanism name
  passed at construction (`:47`, `SysIdRoutine.java:53`). **[source]**
  The javadoc's *"each complete routine ... should have its own
  `SysIdRoutineLog` instance, with a unique log name"*
  (`SysIdRoutineLog.java:30-32`) is the whole warning, and nothing
  enforces it. Two routines named alike append into one set of entries
  and one `sysid-test-state-` stream, and the analyser then sees one
  test run twice on data from two different mechanisms. Drive and steer
  are two routines and take two names.

## Open

- **How a chassis-level rotation gain reaches modules that close
  velocity on the SPARK.** **[unverified]** The rotation fit is in volts
  per unit of chassis angular velocity, and nothing in this drive base
  takes chassis volts: a heading controller emits an `omega`, hands it to
  `setVelocities`, and the module `kS`/`kV` do the voltage work from
  there. So the rotation numbers are usable immediately as *profile
  constraints and a stiction floor*, and using them as a feedforward
  voltage would need a path that does not exist. *Unblocked by* whoever
  writes the rotate-to-angle command deciding which of the two they
  want.

- **Whether to shorten the SPARK's velocity filter for the
  characterisation run.** **[unverified]** The default 8-sample /
  32 ms filter costs ~112 ms of lag on the one signal the dynamic fit
  regresses against, and shortening it changes the measurement rather
  than the machine, so the feedforward gains it produces are still the
  robot's. Nobody has checked whether the noise it buys back costs more
  than the lag. *Unblocked by* the first characterisation run: fit the
  dynamic test at the default and at a shortened filter and compare the
  residuals.

- **Nothing in this document has been executed.** **[unverified]** No
  sysid routine has run on this hardware, no WPILOG has been fed to the
  analyser, and the bench Pi has no CAN. Every claim here is about how
  to obtain numbers, and none of it is a number. *Unblocked by* a module
  on the ground with a bus behind it.

- **Competition-versus-practice gains stay fog.** The sim/real split
  needs no identity detection; a second robot does. That needs a
  mechanism on SystemCore nobody has researched — MAC address, a file, a
  jumper — and it drags in ADR 0004's rule that the identity must reach
  the log, or the config SHA stops pinning the configuration.
  *Unblocked by* research into SystemCore identity detection.

- **ADR 0008's wrap-boundary check comes before any of this.**
  `positionWrappingEnabled` has never been run against `kAnalogSensor`
  on a Flex. **[unverified]** Turn one module across the boundary before
  spending a session tuning steer — named here because "before tuning
  anything" is this document's procedure, not ADR 0008's.

- **`dFilter` has no specification** (ADR 0008), and nothing in
  characterisation produces one.

## Rejected

### Inventing a hand-rolled voltage-ramp characterisation

The premise it rested on — *no v3 `SysIdRoutine`, therefore no SysId* —
was wrong. The log schema and the analyser are both intact and both
already under `org.wpilib`; one command wrapper is missing. A
hand-rolled ramp would reimplement the wrapper badly *and* produce a log
the analyser cannot read, which converts a 277-line port into an
open-ended tooling project.

*Do not re-raise.*

### A first-principles `kV` from motor free speed, tuned by hand

It is a real technique and it produces a starting number, which is worth
having on the first day. It is not a substitute: free speed is a
property of the motor, and `kV` for a drive base is a property of the
motor, the gearbox, the wheels, the carpet and the robot's weight. It
also produces no `kS` at all, and `kS` is the term that decides whether
the robot moves when asked to move slowly.

Keep it as a sanity check on the fitted number, not as the source of it.

### `.revlog` as the characterisation data path

REV's Status Logger is present and does capture applied output (Status0)
and position/velocity (Status2 primary, Status3 analog), so this is not
a richness objection. It is the wrong file for three reasons of shape:
the analyser opens `*.wpilog` (`LogLoader.cpp:36`) **[source]**;
converting needs AdvantageScope or an npm package in a repo whose only
tool is Python `uv run`; and it is invisible to ADR 0014's `logtool` and
therefore to `/analyze-match`. It also does not carry the setpoint we
wrote — which, robot-side, we know without any frame at all.

Status Logger stays **on** as a sink; see Consequences.

URCL, its predecessor, is a dead end independently: latest release
`2026.0.0`, no 2027 build. **[source, via #32 — the URCL release
listing; not re-read here]**

### A `/tune` skill

Covered at the Decision. A WPILOG needs no new tool to read, the
arithmetic is 362 lines of C++ that an agent can carry as knowledge, and
what is left over — deciding *which* gain to move and by how much — is
judgement, which is what ADR 0014 puts on the agent's side of the line
in the first place.

*Do not re-raise* without a step whose completion bar a document cannot
carry.

### Characterisation on blocks

Covered at the Decision: `kS` and `kV` measured off the ground are
measurements of a motor, not of a drive base. The `@Utility` rule is
restated as a supervision requirement rather than given an exception,
because a rule with an exception beside it is a rule with two meanings.

### Recording `Constants.LOOP_PERIOD` beside the on-SPARK gains

It would satisfy ADR 0002's rule by writing down a number that has
nothing to do with the controller the gains run on, which is worse than
recording nothing: a future reader who found 5 ms beside a `kP` would
have a reason to rescale it. The rule keeps full force on the SystemCore
side, where `kA` now lives.

## Source

Decided in
[#32](https://github.com/Drew-Robotics/2027beta/issues/32), which
carries the SysId port, the WPILOG-not-`.revlog` call, the sim/real gain
split, the supervised-on-ground amendment and the second `/tune`
declination.

The loops these gains are for are
[#29](https://github.com/Drew-Robotics/2027beta/issues/29) and ADR 0008;
the `kA` this ADR owes is
[#15](https://github.com/Drew-Robotics/2027beta/issues/15) as amended by
#32, and ADR 0011. The `@Utility` rule restated here is
[#17](https://github.com/Drew-Robotics/2027beta/issues/17) and ADR 0006;
the skill test it is measured against is
[#18](https://github.com/Drew-Robotics/2027beta/issues/18). The gain
factory and the config rules are
[#13](https://github.com/Drew-Robotics/2027beta/issues/13) and ADR 0004;
the log the columns ride in is
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and ADR 0005;
the tool that reads them is
[#16](https://github.com/Drew-Robotics/2027beta/issues/16) and ADR 0014.
The frame budget is
[#28](https://github.com/Drew-Robotics/2027beta/issues/28) and ADR 0007;
the loop-period rule this ADR bounds is
[#30](https://github.com/Drew-Robotics/2027beta/issues/30) and ADR 0002.
The sim assertions that run the other gain set are
[#19](https://github.com/Drew-Robotics/2027beta/issues/19) and ADR 0013.
The wrap-boundary check that precedes tuning is
[#34](https://github.com/Drew-Robotics/2027beta/issues/34).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpilibj/src/main/java/org/wpilib/sysid/SysIdRoutineLog.java`,
`commandsv2/src/main/java/org/wpilib/command2/sysid/SysIdRoutine.java`,
`commandsv3/src/main/java/org/wpilib/command3/Command.java`,
`commandsv3/src/main/java/org/wpilib/command3/StagedCommandBuilder.java`,
`commandsv3/src/main/java/org/wpilib/command3/Mechanism.java`,
`tools/sysid/src/main/native/cpp/Main.cpp`,
`tools/sysid/src/main/native/cpp/view/LogLoader.cpp`,
`tools/sysid/src/main/native/cpp/view/DataSelector.cpp`,
`tools/sysid/src/main/native/cpp/analysis/FilteringUtils.cpp`,
`tools/sysid/src/main/native/include/wpi/sysid/analysis/FilteringUtils.hpp`,
`tools/sysid/src/main/native/include/wpi/sysid/analysis/AnalysisManager.hpp`,
`tools/sysid/src/main/native/include/wpi/sysid/analysis/FeedbackControllerPreset.hpp`.

In REVLib `2027.0.0-alpha-6` (sources jar):
`com/revrobotics/spark/SparkBase.java`,
`com/revrobotics/spark/config/FeedForwardConfig.java`,
`com/revrobotics/spark/config/EncoderConfig.java`,
`com/revrobotics/spark/config/SignalsConfig.java`,
`com/revrobotics/util/StatusLogger.java`,
`com/revrobotics/PersistMode.java`,
`com/revrobotics/ResetMode.java`.

The `.revlog` count and the disk figures were taken on the bench Pi at
`192.168.1.202` on 2026-08-26.
