# The words this project uses

Every term this repository invents or overloads, defined once. An entry
says what a word *means* and points at the document that owns the
concept; it does not restate that document's decision. If an entry and
an ADR disagree, the ADR is right.

The index of the ADRs themselves is
[`docs/adr/README.md`](docs/adr/README.md).

## Reading this repo

- **ADR** — architecture decision record. One decision area per
  document, stated as it stands today, in [`docs/adr/`](docs/adr/).
- **Claim tag** — the `[source]` / `[measured]` / `[executed]` /
  `[field]` / `[decided]` / `[unverified]` marker on a factual claim,
  saying how it was checked. The table is in
  [`docs/adr/README.md`](docs/adr/README.md).
- **Open** — the ADR heading that names a question nobody has
  answered, and what would unblock it
  ([`docs/adr/README.md`](docs/adr/README.md)).
- **Fog** — a question you can see coming but cannot yet phrase
  sharply. It is recorded rather than omitted, and *Open* is where it
  lands.
- **Load-bearing** — said of a line, a rule or a fact that something
  else quietly depends on. The ADRs mark them so that a later reader
  does not tidy one away.
- **Enforcement machinery** — a framework, base class, lint rule or
  compliance test whose whole job is to make people follow a rule.
  This project repeatedly declines it in favour of a construction
  habit with one obvious right answer.
- **A carry** — a copy of somebody else's code kept in our tree,
  edited only to track upstream or to finish porting it. The v3
  `SysIdRoutine` is the one
  ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md)).

## The effort

- **The map** — GitHub issue
  [#1](https://github.com/Drew-Robotics/2027beta/issues/1), the effort
  that decided this architecture. It is the record of what was decided
  and why; the ADRs are that record moved into the repo.
- **The second map** — the effort that writes the robot code, opened
  against these documents. Anything an ADR describes but does not
  build belongs to it. It carries *map* only because it follows **the
  map**: it builds decisions it inherits, and resolves no fog of its
  own.
- **The frontier** — a map's open, unblocked, unclaimed tickets: the
  edge of what can be worked on right now.
- **The reviewer** — the PR review agent. Its remit is only what CI
  structurally cannot check, and it reads `CLAUDE.md` for those
  hazards rather than carrying them in a prompt
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).

## Upstream and the template

- **Upstream** — allwpilib and the vendor libraries, as opposed to this
  repo. An unqualified path in an ADR is a file here; a path beginning
  `wpilibj/`, `commandsv3/`, `com/revrobotics/` or `com/ctre/` is
  upstream.
- **The flagship** — `wpilibjExamples/.../examples/rebuiltcmdv3/`, the
  only complete Commands v3 swerve robot upstream ships. Most of the
  house style is adopted from it
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)).
- **The stock template** — the project the 2027 VSCode/GradleRIO
  generator produces. We use it as it produces it
  ([ADR 0003](docs/adr/0003-project-and-package-structure.md)).
- **Departure** — a named, justified edit to the stock template; every
  one carries a one-line comment at the edit, and `git diff` against the
  generator's output is what makes *unmodified* a checkable claim
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
  An ADR also uses the word for its own closing section, naming where
  it knowingly differs from the ticket it came from.
- **Vendordep** — the JSON file in `vendordeps/` that pulls in a
  vendor library. Committing it *is* the version pin.
- **The year gate** — GradleRIO's check that every vendordep's
  `wpilibYear` matches one project-wide string. It is why
  `vendordeps/CommandsV3.json` carries an edited year, and the edit
  inverts when GradleRIO ships alpha-7
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **alpha-7** — the local allwpilib checkout at commit `cafb0cc79`,
  366 commits past `v2027.0.0-alpha-6`. No alpha-7 has been tagged; it
  is called that because its vendordeps say `2027_alpha7`
  ([ADR 0003](docs/adr/0003-project-and-package-structure.md)).

## Writing robot code

- **Mechanism** — a class that commands need **exclusive ownership**
  of. That is the whole test: `Drive` is a mechanism because a command
  takes it for itself; `SwerveModule` is not, because `Drive` owns it
  and nothing commands it alone; `PoseEstimator` is not, because many
  commands read it at once
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)).
- **Config record** — the Java record holding everything one mechanism
  needs, constructed in that mechanism's own constants file —
  `DriveConstants`, `ArmConstants` — and handed to its constructor.
  `Constants` itself holds only what belongs to no mechanism
  ([ADR 0003](docs/adr/0003-project-and-package-structure.md)).
  Not the same thing as a *vendor* config object, which a factory
  method per motor role returns fresh every call
  ([ADR 0004](docs/adr/0004-config-as-code.md)).
- **`Alert`** — `org.wpilib.util.Alert`, this project's designated
  fault surface. Its `id` is mandatory and `(group, id)` is a
  project-wide namespace the framework is already in
  ([ADR 0004](docs/adr/0004-config-as-code.md), which also owns the
  rule that an alert never blocks enabling and that the active set is
  logged). Alerts reach the *Driver Station's* NetworkTables, never the
  robot's ([ADR 0003](docs/adr/0003-project-and-package-structure.md)).
- **Driver Station** — the FIRST application the drive team runs on a
  laptop. It is what enables the robot, and it is where the opmode is
  selected — nothing in robot code chooses one
  ([ADR 0001](docs/adr/0001-opmoderobot-and-commands-v3.md)).
- **Opmode** — one selectable behaviour, as a class annotated
  `@Autonomous`, `@Teleop` or `@Utility`. The operator picks it on the
  Driver Station; it is constructed on select and closed on deselect,
  and it holds bindings and nothing else
  ([ADR 0001](docs/adr/0001-opmoderobot-and-commands-v3.md)).
- **`@Utility` opmode** — an opmode for bring-up, checks and
  calibration rather than a match. It never runs unattended
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md), amended by
  [ADR 0009](docs/adr/0009-characterisation-and-tuning.md)).
- **Bring-up** — the first bench session in which a mechanism is
  powered, driven and made to work at all.
- **Command** — a lambda taking a `Coroutine`, built by a factory
  method on the mechanism it controls and named `Mechanism.Action`.
  There is no `Command` class to subclass
  ([`docs/commands-v3-house-style.md`](docs/commands-v3-house-style.md)).
- **Coroutine** — the object a command body is handed. It can suspend
  the body mid-line and resume it there next loop.
- **Yield** — `coroutine.yield()`: hand control back to the scheduler
  for this loop and resume on the next line next loop. A loop in a
  command body that never yields hangs the robot.
- **Binding** — a trigger wired to a command, e.g.
  `driver.faceUp().onTrue(intake.intake())`.
- **Binding scope** — the lifetime a binding is torn down with.
  Bindings made in an opmode constructor are opmode-scoped and unbind
  when that opmode ends, which is the mechanical reason we run
  `OpModeRobot` ([ADR 0001](docs/adr/0001-opmoderobot-and-commands-v3.md)).
- **Scheduler** — the object that runs commands. Every mechanism takes
  one as a constructor parameter rather than reaching for the
  process-wide singleton
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)).
- **Default command** — the command a mechanism runs when nothing else
  has it ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)).
- **Loop period** — how often the robot loop runs: **5 ms**, 200 Hz.
  `Constants.LOOP_PERIOD` is the one place that number is written
  ([ADR 0002](docs/adr/0002-loop-rate-and-jvm.md)).
- **Watchdog** — the framework timer that reports work overrunning the
  loop period. It sees slow work *inside* a loop and not a missed
  deadline, which is what wake-to-wake delta is for
  ([ADR 0002](docs/adr/0002-loop-rate-and-jvm.md)).
- **The compile-time net** — the javac plugin checks that turn
  Commands v3 mistakes into build errors. All of it hangs off one line
  in `build.gradle`, and dropping that line loses it silently
  ([ADR 0001](docs/adr/0001-opmoderobot-and-commands-v3.md)).
- **Sideload** — recurring work registered with the *scheduler* rather
  than written as a command. Not used here
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)), and not to be
  confused with `OpModeRobot.addPeriodic`, the framework's timed
  callback, which is
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **Seam** — the line a design draws so that one side can be replaced
  without the other noticing. Where possible it is stated as a type
  rule you can check by reading imports: no vendor type crosses into
  `first.robot.sim` ([ADR 0010](docs/adr/0010-simulation-architecture.md)),
  and vision reaches the drive base through one method
  ([ADR 0012](docs/adr/0012-pose-estimation-and-vision.md)). The **IO
  seam** the ADRs say we do *not* have is a different thing: a
  per-mechanism hardware interface with a real implementation and a
  simulated one
  ([ADR 0003](docs/adr/0003-project-and-package-structure.md)).

## The log

- **Signal** — one named value written to the log over time, under a
  PascalCase path like `/Drive/Modules/FrontLeft/SteerAngle`. Its unit
  lives in the entry's metadata, not in its name
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **WPILOG** — the `.wpilog` file on the robot's USB stick, and the
  artifact of record. NetworkTables carries the same signals so a
  student can watch them live
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **Backend** — where a `TelemetryTable`'s signals are actually
  written. Anything not routed through telemetry reaches no backend,
  and so reaches no file
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **Struct** — the packed binary form a type like `Pose2d` is logged
  in. The log carries the schema, so a reader can decode one without
  knowing the type.
- **Wake-to-wake delta** — `/Robot/LoopDelta`, the time between one
  loop waking and the next. Logged because nothing else in the
  framework can see a swallowed iteration
  ([ADR 0002](docs/adr/0002-loop-rate-and-jvm.md)).
- **Epoch** — two senses. A *timestamp* epoch is which clock a time is
  measured against; vision measurements must use
  `Timer.getMonotonicTimestamp()`, and the wrong epoch is a silent
  total failure
  ([ADR 0012](docs/adr/0012-pose-estimation-and-vision.md)). A
  *watchdog* epoch is one named segment of a loop's work in the
  framework's timing dump.
- **AdvantageScope** — the desktop viewer for a WPILOG or a live
  NetworkTables connection. It is how a signal gets looked at.
- **Replay** — re-running new code against a recorded input stream to
  answer a question you did not log. **We do not have it**, which is
  why a signal we did not log is a question nobody can answer
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **Tunable** — a value changeable at runtime through
  `org.wpilib.tunable` instead of a redeploy. A tunable is logged
  ([ADR 0004](docs/adr/0004-config-as-code.md)).
- **Status Logger, `.revlog`** — REVLib's own on-disk record of every
  REV device, in a proprietary format, left on
  ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md)). A *sink*
  rather than a source, so it changes no frame rate
  ([ADR 0007](docs/adr/0007-can-topology-and-frames.md)), and unreadable
  by `logtool`
  ([ADR 0014](docs/adr/0014-ai-log-analysis-contract.md)).
- **`logtool` / `/analyze-match`** — the CLI that reads any WPILOG and
  knows nothing about our robot, and the skill that supplies every
  fact about it. Neither is built yet
  ([ADR 0014](docs/adr/0014-ai-log-analysis-contract.md)).
- **Question class** — one of the five things `/analyze-match` knows
  how to ask of a log. A sweep reports all five, each with a finding
  or an explicit *clear*
  ([ADR 0014](docs/adr/0014-ai-log-analysis-contract.md)).

## Driving

- **Odometry** — working out where the robot is by adding up how far
  the wheels have rolled and which way the gyro says it is facing.
  Cheap, always available, and drifts.
- **Pose** — where the robot is on the field: a translation and a
  rotation. `PoseEstimator` is a plain class beside `Drive`, not
  inside it ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **Kinematics** — the arithmetic that converts between one chassis
  velocity and four module velocities.
- **Module angle** — where a module is pointing. Because steer closes
  against the analog absolute encoder, the angle odometry consumes is
  the analog one ([ADR 0008](docs/adr/0008-closed-loop-on-the-spark.md)).
- **Module position** — how far that module's wheel has rolled,
  together with its module angle. It is what
  `Drive.getModulePositions()` hands the pose estimator, and it is a
  different quantity from module angle rather than a longer name for
  it.
- **Field-relative** — velocities expressed against the field, so
  *forward* means the same direction whichever way the robot is
  facing. **Robot-relative** is against the chassis. The follower
  speaks field-relative, and nothing in the types enforces that
  ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **Along-track error** — how far ahead of or behind the plan the
  robot is, measured along the path. Running late looks like this.
- **Cross-track error** — how far to the side of the path the robot
  is. Steering and heading problems look like this. The two together
  are the pose error rotated into the path's own heading frame, and
  they are logged in place of raw x and y error, which cannot tell the
  two apart ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **Choreo** — the desktop tool paths are drawn in. It writes a
  `.chor` project and a `.traj` per path, both committed; nothing from
  ChoreoLib ships on the robot
  ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **Eager cache** — a cache filled at startup rather than on first
  use. A cache that exists to keep work off a transition and is filled
  lazily pays the whole cost at the moment it was built to protect, so
  every cache with that purpose is filled in `Robot`'s constructor
  ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **The flip** — turning a trajectory authored for one alliance into
  the other alliance's version of it, applied once when the follower
  is constructed
  ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **AprilTag** — the printed square markers around the field that a
  camera locates itself against.
- **The gate** — a season's vision code deciding whether to accept one
  measurement. Every gate worth applying needs camera data, so none of
  them live in this repo; the one thing the drive base contributes is
  `maxAbsYawRate`
  ([ADR 0012](docs/adr/0012-pose-estimation-and-vision.md)).
- **Fails closed / fails open** — what a check does when it cannot
  tell. `maxAbsYawRate` returns empty rather than a guess and the
  caller rejects the frame; `sampleAt` clamps and answers anyway,
  which is why it is not a gate
  ([ADR 0012](docs/adr/0012-pose-estimation-and-vision.md)).
- **Standard deviations, σ** — how far a measurement is trusted, one
  number per axis, larger meaning trust it less. `σ = ∞` gives an axis
  a gain of exactly zero, which is how a measurement is rejected
  ([ADR 0012](docs/adr/0012-pose-estimation-and-vision.md)).
- **True pose** — `/Sim/TruePose`, where the simulation says the robot
  actually is. Plotted against the odometry-only pose it makes drift
  visible rather than inferred
  ([ADR 0010](docs/adr/0010-simulation-architecture.md)).

## Control

- **Setpoint** — what a controller has been asked for, as against the
  **measurement**, what it reads. Every mechanism logs both
  ([ADR 0005](docs/adr/0005-telemetry-and-log-schema.md)).
- **Feedforward** — the part of a controller's output computed from a
  model of the machine rather than from the error. `kS` is the voltage
  it takes to break away from stiction, `kV` the voltage per unit of
  speed, and `kA` the voltage per unit of acceleration. Each term has
  exactly one home
  ([ADR 0008](docs/adr/0008-closed-loop-on-the-spark.md)).
- **`arbFeedforward`** — a voltage the robot hands a SPARK to add to
  its closed-loop output. It carries `kA · a` on the path-following
  path and nothing else; `kS` and `kV` live on the controller itself,
  in `FeedForwardConfig`
  ([ADR 0011](docs/adr/0011-autonomous-and-choreo.md)).
- **`kP` / `kD`** — the feedback gains: output proportional to the
  error, and to how fast the error is changing.
- **Position wrapping** — telling a position loop that the sensor runs
  in a circle, so a target 10° away is reached by turning 10° rather
  than 350°. Steer closes on a sensor that jumps from 1 back to 0
  every revolution, so a loop on it must wrap
  ([ADR 0008](docs/adr/0008-closed-loop-on-the-spark.md)).
- **Module zero offset** — the per-module constant that turns a raw
  steer sensor reading into a module angle. It lives in this repo and
  is added into the setpoint, because the device cannot hold one.
- **Backlash** — the slack in a gearbox: the band the output shaft can
  sit anywhere inside while the motor does not move. It is why steer
  closes on the module's own shaft rather than on the motor.
- **Stiction** — the friction that has to be beaten before anything
  moves at all. `kS` measures it, and it is a property of the whole
  robot, which is why characterisation runs on the ground rather than
  on blocks ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md)).
- **On blocks** — the robot up on supports with its wheels off the
  ground.
- **X-lock** — pointing the four modules inwards so the robot resists
  being pushed. Deliberately not our idle state
  ([ADR 0006](docs/adr/0006-commands-v3-house-style.md)).
- **Characterisation** — driving a mechanism with a known voltage,
  recording what it does, and fitting a model to get feedforward
  gains. **Tuning** is the shorter loop after it: change a number at a
  bench, watch, change it again
  ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md)).
- **Sim gains** — the second set of gains, branched on
  `isSimulation()`, chosen so the simulation model *tracks*. They are
  not a prediction of the real robot's, which is the whole distinction
  ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md),
  [ADR 0010](docs/adr/0010-simulation-architecture.md)).
- **SysId** — the WPILib tooling that does the above. A
  **quasistatic** test ramps the voltage slowly and a **dynamic** test
  steps it, each forward and reverse; a desktop analyser fits the
  gains from the resulting WPILOG
  ([ADR 0009](docs/adr/0009-characterisation-and-tuning.md)).

## Hardware and the bus

- **SystemCore** — the 2027 control system, replacing the roboRIO.
- **MRC API** — the version number the SystemCore image and the WPILib
  build must agree on. A mismatch is a crash loop, not a message, so the
  bench stays on the image the checkout expects
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **The bench Pi** — the single SystemCore-image Raspberry Pi at
  `192.168.1.202` that measurements and hardware CI run on. There is
  one of it, which is why it can never gate anything
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **SPARK** — the REV motor controller. We run eight SPARK **Flex**es,
  four drive and four steer, each closing its own loop at 1 kHz
  ([ADR 0008](docs/adr/0008-closed-loop-on-the-spark.md)).
- **Pigeon2** — the CTRE gyro. It supplies heading and yaw rate.
- **Frame** — one message on the CAN bus. What runs out on a bus is
  **frames per second**, not bytes, so every periodic signal a device
  publishes is a cost
  ([ADR 0007](docs/adr/0007-can-topology-and-frames.md)).
- **Status frame group** — the numbered bundle (Status0, Status2,
  Status3 …) a SPARK publishes several signals in, at one period. The
  period is shared, so the fastest consumer sets it for the whole
  group, and you budget the group rather than the signal.
- **Brownout** — the battery sagging far enough that devices reset.
  A SPARK that browns out comes back configured but at default frame
  rates, silently.
- **Fat jar** — the single deployed jar with every dependency inside
  it, which is what the stock template already builds.

## Testing and CI

- **HAL** — the native layer between our Java and the hardware. There
  are two of them, and which one a build links is not a flag: a robot
  build links the real HAL and a simulation links the sim HAL
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **`linuxsystemcore` / `linuxarm64`** — the two native builds
  published for the Pi's architecture: the real HAL, and the sim HAL.
  Deploying the second to the bench Pi is what `sim-hitl` is.
- **Headless** — running with no display and, in Tier 2's case, no
  real Driver Station either.
- **Tier 1** — plain JUnit: no vendor jars, no `RobotBase`, and no HAL
  the test initialises or asks anything of — though scheduling a
  command loads it. It is where every numeric assertion in the project
  goes, because it is deterministic and fast
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **Tier 2** — the real `Robot` constructed in process and headless.
  It owns the **wiring** — opmode registration, bindings, the
  scheduler, telemetry — and asserts almost nothing, deliberately
  ([ADR 0013](docs/adr/0013-ci-and-test-strategy.md)).
- **The gated workflow** — the one CI workflow that is a required
  check: lint, compile and test, on pull requests targeting `main` and
  on pushes to any branch.
- **The bench workflow** — the hardware jobs on the bench Pi. It is
  never required and blocks nothing; an unreachable bench is a skip,
  not a red.
- **`sim-hitl`** — the bench job that runs a `linuxarm64` sim build on
  the Pi, so the robot drives around in simulation on real hardware.
  `real-hal-boot` is its sibling, which deploys the actual fat jar
  against an empty CAN bus.
