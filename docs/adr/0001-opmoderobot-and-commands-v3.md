# ADR 0001 — `OpModeRobot` and Commands v3

## Status

Accepted — 2026-08-26.

Claim tags are defined in the index. `[source]` claims here were read at
`~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Every path and
bare filename cited below is in that checkout unless it is `build.gradle`
or a `docs/` path, which are files in this repo; the *Source* section
lists each cited file in full.

## Context

The robot base class and the command framework look like two decisions.
In 2027 they are one.

Commands v3 is a rewrite: `Command` is an interface with a single
`run(Coroutine)` method, `Subsystem` is replaced by a `Mechanism`
interface with no base class, and command bodies are coroutines rather
than four lifecycle callbacks. Both v3 templates and the one complete v3
robot upstream — `rebuiltcmdv3`, a swerve base — extend `OpModeRobot`;
no template pairs v3 with `TimedRobot`, and `RobotContainer` has been
deleted from the v3 templates because opmodes replace it.

That alone would only be a style argument. The binding it creates is
mechanical: v3's binding-scope lifecycle keys off an opmode id that only
`OpModeRobot` ever sets, so under `TimedRobot` the feature is inert while
still compiling and running. Picking the base class picks whether a
quarter of the command framework does anything.

## Decision

### `Robot extends OpModeRobot`

`Robot` extends `OpModeRobot`, not `TimedRobot`. **[decided]**

`OpModeRobot` extends `RobotBase` directly (`OpModeRobot.java:63`) — it
is a *sibling* of `IterativeRobotBase` and `TimedRobot`, not a subclass,
and it reimplements the main loop (`startCompetition()`,
`OpModeRobot.java:770-786`). **[source]** Its overridable hooks are
`driverStationConnected()`, `robotPeriodic()`, `disabledInit()`,
`disabledPeriodic()`, `disabledExit()`, `nonePeriodic()`,
`simulationInit()` and `simulationPeriodic()` — declared at
`OpModeRobot.java:567-591`. There is no `autonomousInit`, no
`teleopPeriodic`. **[source]**

`Robot` holds the mechanisms, the controllers and the shared triggers as
fields, sets the default commands, and runs the scheduler:

```java
public class Robot extends OpModeRobot {
  public final Drive drive = new Drive(...);
  public final PoseEstimator poseEstimator = new PoseEstimator();
  public final CommandGamepad driver = new CommandGamepad(DRIVER_PORT);

  public Robot() {
    super(LOOP_PERIOD);
    drive.setDefaultCommand(drive.idle());
  }

  @Override
  public void robotPeriodic() {
    poseEstimator.odometryUpdate(
        drive.getGyroHeading(), drive.getModulePositions());
    Scheduler.getDefault().run();
  }
}
```

The loop period is ADR 0002's decision and is passed up through
`super(...)`; the `OpModeRobot()` no-arg constructor would take
`DEFAULT_PERIOD`, which is 20 ms (`OpModeRobot.java:499`). **[source]**

### An opmode is one selectable behaviour, with a real lifetime

A class annotated `@Autonomous`, `@Teleop` or `@Utility` at or below
`Robot`'s package is registered automatically by a classpath scan run
from the `OpModeRobot` constructor (`OpModeRobot.java:533`, behaviour
documented at `:52-54`). **[source]** ADR 0003 owns where those classes
live; this ADR owns what happens to them.

The lifecycle, from `OpMode.java:19-30` and the code that implements it:

| When | What runs |
|---|---|
| Operator selects it on the DS, robot disabled | constructed (`OpModeRobot.java:635`) |
| While selected and disabled | `disabledPeriodic()` (`:642`, `:690`) |
| Disabled → enabled | `start()`, once (`:740`) |
| While enabled | `periodic()`, at `getPeriod()` (`:738`) |
| Disable, or a different opmode selected | `end()` then `close()` (`:749`, `:763`) |

**The object is never reused** (`OpMode.java:28-30`). **[source]** A
deselect and reselect constructs a new one.

That teardown point is the whole reason the framework exists for us. A
`SendableChooser` branch has no teardown at all.

### Opmodes `implement OpMode`; they do not extend `PeriodicOpMode`

Both are legal. The generator's `MyAuto` and `MyTeleop` extend
`PeriodicOpMode`; the Commands v3 template's `ExampleTeleop` and
`ExampleAuto` implement `OpMode` and contain nothing but a constructor
(`templates/commandv3/ExampleTeleop.java`). **[source]** We follow the
v3 template. **[decided]**

Two reasons, and the second is the stronger one:

1. There is no per-opmode periodic work to do. The scheduler does the
   looping, and it runs in `robotPeriodic()`.
2. An opmode's `periodic()` runs outside the loop watchdog, while
   `robotPeriodic()` runs inside it — so an overrun in `periodic()`
   raises no alert and is logged nowhere, and the same overrun in
   `robotPeriodic()` is caught and reported. Work placed in an opmode's
   `periodic()` is work whose overruns nobody can see. Traps has the
   mechanism.

`PeriodicOpMode`'s `addPeriodic()` — the reason to extend it at all —
carries a second problem on top of that one. It feeds the set
`OpMode.getCallbacks()` returns (`PeriodicOpMode.java:55-57`, `:84`),
and `OpModeRobot` registers that set the moment the opmode is
constructed (`OpModeRobot.java:638-639`), which is while the robot is
still *disabled*. Those callbacks then run disabled unless each one
opens with a hand-written `RobotState.isEnabled()` check — which the
javadoc asks for and nothing enforces (`OpMode.java:78-82`).
**[source]**

ADR 0006 rejects sideloads on the same ground: periodic work that is not
a command lives in a `Robot` hook you can read top to bottom.

What an opmode constructor *does* contain is ADR 0006's rule: bindings,
default-command overrides, and the enabled-trigger for autonomous.

### `Robot` is injected into the opmode's constructor

`OpModeRobot` looks for a public constructor taking your `Robot`
subclass and falls back to a no-arg one
(`findOpModeConstructor`, `OpModeRobot.java:98-110`). **[source]** So an
opmode reaches hardware only through the `Robot` it was handed:

```java
@Teleop
public class DefaultTeleop implements OpMode {
  public DefaultTeleop(Robot robot) {
    robot.drive.setDefaultCommand(robot.drive.driverControl(robot.driver));
    robot.driver.faceUp().onTrue(robot.intake.intake());
  }
}
```

There is no `RobotContainer`, and no `Subsystem` type to register
anything with.

### Auto selection is the Driver Station's opmode selector

One `@Autonomous` class per routine, plus a `DoNothingAuto` as the safe
pick (ADR 0006). The operator picks it from the DS; nothing in robot
code chooses. **[decided]**

`SendableChooser` is not an alternative we declined — `SendableChooser`,
`Sendable` and `SendableBuilder` do not exist anywhere in the checkout.
**[source]**

If the shipping 2027 Driver Station turns out not to support opmode
selection natively, the HAL degrades to a chooser widget without us
doing anything: `MrcLibDs.cpp` branches on a capability bit, and the
fallback publishes chooser-shaped NetworkTables entries at
`/SmartDashboard/Auto OpMode`, `.../Teleop OpMode` and
`.../Utility OpMode` (`hal/src/main/native/cpp/DashboardOpMode.cpp:99-101`).
**[source]** The workflow differs; the code does not.

### Bindings are scoped to the opmode that made them

This is the mechanism that makes the pairing one decision rather than
two.

`BindingScope.createNarrowestScope` returns a command scope if a command
is running, an **opmode** scope if `RobotState.getOpModeId()` is
non-zero, and the global scope otherwise
(`commandsv3/.../BindingScope.java:27-37`). **[source]** It is consulted from
`Trigger`'s constructor, from every `onTrue`/`whileTrue` binding, from
`Scheduler.setDefaultCommand`, and from `Scheduler.schedule`
(`Trigger.java:112`, `:565`; `Scheduler.java:219`, `:558`). **[source]**
The scheduler then tears stale scopes down every cycle, in four separate
passes — stale bindings cancelled, stale triggers unbound, stale
sideloads dropped, stale default commands reverted
(`Scheduler.java:825`, `:836`, `:871`, `:1068`). **[source]**

So a trigger binding, a scheduled command or a default command created
in an opmode constructor is cancelled and unbound when that opmode ends.
The bug class it removes is the one that bites mentor-plus-students
teams every year: a teleop binding that stays live and fires during
auto, or last year's test-routine default command that never got
cleared.

Only `OpModeRobot` registers opmodes with the driver station. Under
`TimedRobot`, `getOpModeId()` is 0, every binding is global-scoped, and
none of those four passes ever finds anything. **[source, and
`docs/research/commands-v3.md` §11]**

### Coroutines are continuations, and the JVM has to be opened for them

A `Coroutine` is a thin wrapper over `jdk.internal.vm.Continuation`,
reached by reflection through `MethodHandles.privateLookupIn`
(`commandsv3/.../Continuation.java:52-55`). **[source]** The scheduler
mounts a command's continuation, runs it until it yields, and unmounts
it.

That access must be opened at the JVM level:

```
--add-opens java.base/jdk.internal.vm=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
```

Every Gradle project in the checkout that runs v3 code passes both
(`commandsv3/build.gradle:43-49`, `wpilibjExamples/build.gradle:56-61`).
**[source]** Getting them onto the Gradle `test` task is ADR 0013's
departure, and is the reason a first v3 test dies in `ContinuationScope`
rather than failing an assertion.

`Continuation.java:20-24` carries a warning worth reading to students
verbatim: using continuations off a single thread "can result in JIT
compilers to issue invalid code causing buggy behavior and JVM crashes at
any time, up to and including on a field during an official match."
**[source]** The
framework is single-threaded — one HAL notifier and a min-heap of
periodic callbacks (`wpilibj/.../internal/PeriodicPriorityQueue.java`) —
so nothing we write should ever get near that, but nothing we write
should start a thread near a command either.

## Consequences

- **Mechanisms are fields on `Robot`, and they are `public final`.**
  Opmodes are the only things that need them, they reach them through
  the injected `Robot`, and ADR 0003's flat `opmode/` subpackage is what
  forces `public` over package-private.

- **Opmodes are rebuilt on every reselect.** An operator scrolling the DS
  dropdown constructs and closes one opmode per stop. Anything expensive
  in an opmode constructor is paid again on every reselect, which is why
  trajectories cache on `Robot` (ADR 0006, ADR 0011) and why hardware is
  never constructed in an opmode.

- **`Robot` is the only long-lived object.** State that must survive a
  reselect belongs to `Robot` or to a mechanism, never to an opmode.

- **Migration from a `TimedRobot` habit is a rewrite of `Robot`, not a
  superclass swap.** Mechanisms, constants and commands are unaffected;
  only `Robot` and the mode-entry classes differ.

- **Utility opmodes replace test mode.** Per-mechanism bring-up gets
  first-class classes that never sit in the teleop code path. ADR 0006
  owns the rules they inherit.

- **ADR 0006 inherits the opmode-as-binding-scope fact.** "Opmode
  constructors hold bindings" is only safe because the scope tears them
  down; the two documents are one decision seen from two sides.

- **ADR 0013 inherits `--add-opens`.** Not an optimisation — without it
  no command runs at all.

- **The threading model students already have is unchanged.** One
  thread, one notifier, one period. Coroutines are not threads.

- **Opmode transitions are visible in `journalctl` for free.** The
  framework prints on create, start, end and close
  (`OpModeRobot.java:634`, `:735`, `:747`, `:762`). **[source]** These
  are transitions, not periodic code, so ADR 0003's no-`println` rule is
  not in tension with them.

## Traps

- **An enabled opmode's `periodic()` runs outside the watchdog, so an
  overrun there produces no signal anywhere.** The watchdog is reset at
  the top of `loopFunc` and disabled at the bottom
  (`OpModeRobot.java:610`, `:719`), and every epoch it records is inside
  that window — including `opMode.disabledPeriodic()`, `opMode.start()`
  and `opMode.end()`. The enabled `periodic()` is not: it is registered
  as its own entry in the callback queue
  (`m_callbacks.add(m_currentOpMode::periodic, …)`, `:738`).
  `PeriodicPriorityQueue` holds no watchdog, no `Alert` and no tracer,
  and an overrunning callback simply has its expiration advanced by
  whole periods (`PeriodicPriorityQueue.java:183-185`) — it silently
  skips the cycles it missed. **[source]** So an overrun in an opmode's
  `periodic()` fires no `opmode-loop-overrun` alert
  (`OpModeRobot.java:522-527`), prints no epochs, and drops iterations
  with nothing logged. The same overrun in `robotPeriodic()` is caught,
  alerted and epoch-printed. This is the reason our opmodes hold no
  `periodic()` at all.

- **Cancellation is not an exception unwind, so a `finally` block never
  runs.** On cancellation the scheduler drops the command from its
  running set and calls `onCancel()`; the continuation is never
  remounted and is left for GC (`Scheduler.java:748-772`). **[source,
  and `docs/research/commands-v3.md` §2]** Cleanup goes in the builder's
  `whenCanceled(Runnable)` (`NeedsNameBuilderStage.java:27`).
  **[source]** Anyone carrying a v2 habit will write the `finally`, it
  will compile, and it will never execute.

- **`for(;;)` compiles clean and hangs the robot.** The missing-`yield`
  check is a hard, unsuppressible compile error
  (`CoroutineYieldInLoopDetector.java:219`, `Diagnostic.Kind.ERROR`),
  but its scanner overrides `visitWhileLoop` only (`:175`) — there is no
  `visitForLoop`, `visitEnhancedForLoop` or `visitDoWhileLoop`.
  **[source]** A `for (;;)` or `do/while` in a coroutine body emits no
  diagnostic, never yields, and stalls the scheduler thread. ADR 0006's
  construction rule — loops in a coroutine body are always `while` —
  exists to route every loop we write into the check that does fire.

- **The whole compile-time net is opt-in at the Gradle level, and is
  lost silently if one line is dropped.**
  `javacPlugin/.../WPILibJavacPlugin.java` declares `autoStart()`
  (`:33-34`), so no `-Xplugin` flag is needed — but only
  if the plugin jar is on the annotation processor path, and the single
  thing that puts it there is `build.gradle:60`:

  ```groovy
  annotationProcessor wpi.java.deps.wpilibAnnotations()
  ```

  Remove it and eight listeners stop being registered
  (`javacPlugin/.../WPILibJavacPlugin.java:22-29`) **[source]**, five of
  which are load-bearing here:

  | Listener | What stops being caught |
  |---|---|
  | `CoroutineYieldInLoopDetector` | a `while` loop with no `yield()` |
  | `IncorrectCoroutineUseDetector` | an outer lambda's coroutine used inside an inner one |
  | `CodeAfterCoroutineParkDetector` | unreachable statements after `coroutine.park()` |
  | `ReturnValueUsedListener` | a built-and-dropped `Command` — `Command` is `@NoDiscard` |
  | `OpModeAnnotationValidator` | over-length `@Teleop`/`@Autonomous` annotation strings |

  Nothing warns. The build stays green and simply stops catching the
  things that hang a robot. ADR 0003 records the same line from the
  build-structure side; this is what is actually behind it.
  **[source; the GradleRIO end of the wiring — `WPIJavaDepsExtension.java:84-85`
  — is from #12 and was not re-read, GradleRIO not being in the local
  checkout]**

- **`@Utility` annotation strings are validated by nothing.**
  `OpModeAnnotationValidator` matches `org.wpilib.opmode.Autonomous`,
  `org.wpilib.opmode.Teleop` and `org.wpilib.opmode.TestOpMode`
  (`:84-86`) and returns early for anything else (`:88`). **[source]**
  `TestOpMode` was renamed to `Utility` and the validator was not
  updated; there is no `org.wpilib.opmode.TestOpMode` left in the tree.
  **[source]** So `@Teleop` and `@Autonomous` get the 32 / 12 / 64
  character limits on `name`, `group` and `description` (`:41-47`,
  `:114-116`) and `@Utility` gets none. What an over-length string does
  once it reaches the Driver Station is **[unverified]** — the limits
  exist, and nobody has watched one be exceeded.

- **`OpMode.end()` is documented as asynchronous and is not.**
  `OpMode.java:59-61` says it "is called asynchronously"; it is called
  inline from `endCurrentOpMode()` (`OpModeRobot.java:749`), which
  `loopFunc` calls directly (`:625`, `:663`). **[source]** Blocking work
  in `end()` blocks the robot loop. It is at least inside the watchdog,
  so it is the visible kind of stall.

- **The javadoc still teaches `TimedRobot`.** `Scheduler`'s class-level
  example opens `public class Robot extends TimedRobot`
  (`Scheduler.java:44`), and `PeriodicOpMode.addPeriodic`'s javadoc says
  it is "scheduled on TimedRobot's Notifier" (`PeriodicOpMode.java:75-76`).
  **[source]** Stale prose over correct code — but a student reading
  carefully is told to do the thing this ADR reverses.

## Open

- **Whether the shipping 2027 Driver Station supports opmode selection
  natively.** Not answerable from this checkout. Both paths exist
  in-tree, so the decision is safe either way; only the operator
  workflow differs — a DS dropdown versus a dashboard widget.
  **[unverified]** *Unblocked by* the first release Driver Station.

- **Classpath scanning is the untested half of `OpModeRobot`.**
  `addOpMode(...)` has twelve usages across `OpModeRobotTest`;
  `addAnnotatedOpModeClasses` has **zero** test references anywhere in
  `wpilibj/src/test/`. **[source]** The scanner's `jar:` branch is
  source-verified to exist (ADR 0003) and executed by nothing. If it
  misbehaves under the deploy jar, the fallback is explicit
  `addOpMode(RobotMode, String, Class)` registration, which is the
  well-tested path. *Unblocked by* a deploy smoke test that confirms
  every opmode appears on the Driver Station.

- **The OpMode ↔ Commands v3 integration rationale is unpublished.**
  `design-docs/opmodes.md` points at `opmodes-commandbased.md`, which
  does not exist in the tree. **[source]** The code is coherent and the
  behaviour is verified; the design intent behind it is not written down
  anywhere, so a future change to it will arrive without a rationale to
  read. *Unblocked by* upstream publishing the document.

- **There is no default opmode.** An operator who enables without
  selecting one gets nothing at all, and the simulation Driver Station
  refuses to enable on a mismatch **[source —
  `docs/research/opmodes.md` §5]**. That is safer than running the wrong
  auto, and it is a new pre-match checklist item for the drive team.
  Upstream's own "Unresolved Questions" still lists a default-opmode
  mechanism as open. **[source]** *Unblocked by* upstream deciding, or
  by us finding out on a practice field which way it bites.

## Rejected

### `TimedRobot` + Commands v3 — do not re-raise without new evidence

The provisional choice this ADR reverses, taken on the premise that
OpMode was new and undocumented while v3 was the safe path. The premise
does not survive the source tree.

1. **It is not the conservative option.** No *template* pairs
   `TimedRobot` with v3: `commandv3`, `commandv3skeleton` and the
   `rebuiltcmdv3` swerve example all extend `OpModeRobot`. **[source]**
   One example does pair them — `hatchbotcmdv3` is a complete, current
   `extends TimedRobot` v3 robot **[source]** — and it is the same file
   whose comment about auto-cancellation is wrong (point 3). So the
   combination is demonstrated, by exactly one example that documents
   the consequence incorrectly.
2. **It silently disables opmode-scoped bindings.** Covered under
   *Bindings are scoped to the opmode that made them*. We would pay v3's
   full learning cost and collect three quarters of the benefit.
3. **The upstream `TimedRobot` v3 example's own comment is wrong about
   this.** `hatchbotcmdv3/Robot.java` says its autonomous command "will
   be automatically canceled when the autonomous mode ends"; under
   `TimedRobot` that command is global-scoped and is never cancelled.
   **[source — `docs/research/commands-v3.md` §11]**
4. **It is worse for students.** One file per selectable behaviour, its
   name appearing in a dropdown, is a tractable assignment.
   "Add a case to the auto chooser" is not.

**Do not re-raise this without new evidence.** New evidence means an
upstream `TimedRobot` + v3 template appearing, or `BindingScope` gaining
a non-opmode teardown path — not a fresh worry about OpMode's maturity.
Maturity was the original ground and it was measured: `OpModeRobot` is
805 lines with 843 lines of Java lifecycle tests, a C++ mirror, Python
bindings, HAL and DS-protocol support, a javac validator, three
templates and five examples **[source — `docs/research/opmodes.md`
§4]**.

### Commands v2

Not on the table, and not available alongside v3: the vendordeps declare
each other in `conflictsWith`, so there is no incremental migration path
**[source — `docs/research/commands-v3.md` §9]**. v2 is also where the
removed black boxes live — `SwerveControllerCommand`,
`HolonomicDriveController` and `RamseteCommand` are gone from both
frameworks in 2027, on the grounds `CONTRIBUTING.md` gives: an opaque
abstraction is hard to debug and impossible to instrument.
**[source — `docs/research/commands-v3.md` §9]**

### `RobotContainer`

Familiar to returning students, and deleted from both v3 templates.
`grep -rn RobotContainer` over `commandsv3/` and both v3 templates
returns zero hits **[source — `docs/research/opmodes.md` §3]**. Its job
was global binding configuration with no teardown; an opmode constructor
is the same job with a scope around it.

### `PeriodicOpMode` as our opmode base class

What the project generator writes. Rejected under *Opmodes implement
`OpMode`*: it exists to give an opmode its own periodic callbacks, we
have no periodic work outside the scheduler, and its callbacks run
outside the watchdog and start while the robot is still disabled. We
delete the generator's `periodic()` overrides rather than fill them in.

### `Selectable<Command>` + `Tunables.publish` for auto selection

v3's replacement for `SendableChooser`, and what `hatchbotcmdv3` uses
under `TimedRobot` **[source — `docs/research/commands-v3.md` §9, §11]**.
It is a chooser: it selects a value, it has no
lifecycle, and it puts auto selection in robot code rather than in the
Driver Station. Adopting `OpModeRobot` is precisely adopting the other
answer.

### Registering opmode names from a `TimedRobot`

The genuine middle path: `RobotState.addOpMode(...)` and
`publishOpModes()` are static and reachable from any base class, so a
`TimedRobot` could register opmode *names*, get a non-zero opmode id and
therefore get `ForOpmode` binding scoping, without opmode objects ever
being constructed or destroyed **[source — `docs/research/opmodes.md`
§3]**. It works, and nobody upstream tests it. A bespoke hybrid is a
worse place to be than either end.

### `TimesliceRobot`

A `TimedRobot` subclass (`TimesliceRobot.java:74`) **[source]**, so it
inherits everything rejected above; and its reason
for existing — deterministic sub-loop scheduling — is answered by
ADR 0002's loop rate. **[decided]**

### AdvantageKit's `LoggedRobot`

Not a WPILib class; `LoggedRobot` does not exist anywhere in the
checkout **[source]**. AdvantageKit itself was ruled out earlier on the
design map in favour of explicit `org.wpilib.telemetry` logging
(ADR 0005). Its 2027 notes stating it has no `OpModeRobot` support yet
**[source — as reported in #7; AdvantageKit's notes were not read for
this ADR]** is a fact about AdvantageKit, not about `OpModeRobot`.

## Source

Decided in [#7](https://github.com/Drew-Robotics/2027beta/issues/7) —
which reverses the provisional `TimedRobot` choice — and
[#3](https://github.com/Drew-Robotics/2027beta/issues/3), which supplies
the v3 API surface, the coroutine model and the binding-scope finding.
The package layout that follows from this decision is
[#12](https://github.com/Drew-Robotics/2027beta/issues/12) and
ADR 0003; the house style for writing commands and opmodes is
[#17](https://github.com/Drew-Robotics/2027beta/issues/17) and ADR 0006.

Research: [`docs/research/opmodes.md`](../research/opmodes.md),
[`docs/research/commands-v3.md`](../research/commands-v3.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/opmode/OpMode.java`,
`wpilibj/src/main/java/org/wpilib/opmode/PeriodicOpMode.java`,
`wpilibj/src/main/java/org/wpilib/framework/TimesliceRobot.java`,
`wpilibj/src/main/java/org/wpilib/internal/PeriodicPriorityQueue.java`,
`wpilibj/src/test/java/org/wpilib/framework/OpModeRobotTest.java`,
`wpilibj/src/test/java/org/wpilib/framework/OpModeLifecycleTest.java`;
`commandsv3/src/main/java/org/wpilib/command3/` — `BindingScope.java`,
`Continuation.java`, `Scheduler.java`, `Trigger.java`,
`NeedsNameBuilderStage.java`; `commandsv3/build.gradle`;
`javacPlugin/src/main/java/org/wpilib/javacplugin/` —
`WPILibJavacPlugin.java`, `CoroutineYieldInLoopDetector.java`,
`OpModeAnnotationValidator.java`;
`hal/src/main/native/cpp/DashboardOpMode.cpp`;
`wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/`;
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`;
`wpilibjExamples/src/main/java/org/wpilib/examples/hatchbotcmdv3/`.
