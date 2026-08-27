# ADR 0006 — Commands v3 house style

## Status

Accepted — 2026-08-26. Amended in part by ADR 0009: the `@Utility` rule
is a supervision requirement, not an on-blocks one. Amended 2026-08-27:
the payoff for injecting a `Scheduler` has been collected.

Claim tags are defined in the index. `[source]` claims here were read at
`~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Paths beginning
`commandsv3/`, `wpilibjExamples/` or `javacPlugin/` are in that checkout;
an unqualified path is a file in this repo.

The teaching document is
[`docs/commands-v3-house-style.md`](../commands-v3-house-style.md). This
ADR is the record of what was decided; that document teaches it, with the
drive base as its worked example. Anything a student needs *explained*
lives there and is not restated here.

## Context

Commands v3 is a rewrite, not a revision. There is no abstract `Command`
class to subclass, no `Subsystem`, no `RobotContainer` and no
`SendableChooser` to pick an auto with. A command is a lambda that takes
a `Coroutine` and yields; a `Mechanism` is an interface with default
methods and nothing else. Almost every v2 habit either has no v3 spelling
or has one that quietly means something different.

That leaves a real risk of each student inventing a personal dialect in
the first three weeks. Two things prevent it: upstream ships one complete
v3 robot — `rebuiltcmdv3`, 1,277 lines of swerve on `OpModeRobot` — and
the compiler ships a plugin that turns some of the worst mistakes into
build errors.

So the house style is **upstream's idiom, adopted wholesale, plus the
rules upstream does not state**. Where we diverge, it is to buy
testability or to close a hole in the compile-time net.

## Decision

### Commands are factory methods on the mechanism

A command is a `public` method on the mechanism that returns `Command`.
The body is a lambda; the chain ends in `.named(...)`.

```java
public Command driveFieldRelative(Supplier<ChassisVelocities> velocities) {
  return run(coroutine -> {
        while (true) {
          setVelocities(velocities.get());
          coroutine.yield();
        }
      })
      .named("Drive.DriveFieldRelative");
}
```

**There are no command classes and no `commands/` package.** **[decided]**
This is barely a choice: the v2 abstract `Command` was deleted, so there is
nothing to subclass. `Mechanism.run(...)` and `Command.noRequirements(...)` are
the only two entry points, and both return a builder.

A routine that needs **more than one mechanism** starts as a private
method on the opmode that uses it — exactly `SweepAuto.sweepAndScore`
(`wpilibjExamples/.../rebuiltcmdv3/opmodes/auto/SweepAuto.java:47-59`).
**[source]** It moves to a shared home — a method on `Robot`, as
upstream's `Robot.shootAt` is (`rebuiltcmdv3/Robot.java:57-74`)
**[source]** — only when a *second* opmode needs the same routine. There
is no speculative `routines/` package. **[decided]**

### `noRequirements` + `await` is the default composition

Compose with a coroutine, not with the built-in groups. **[decided]**

```java
Command.noRequirements(coroutine -> {
      coroutine.await(drive.followPath("nz-sweep-left-trench"));
      coroutine.await(shooter.shootAtHub(range));
    })
    .named("Auto.SweepAndScore");
```

`Command.requiring(a, b)` and the sequence/parallel groups take the union
of every child's requirements and hold all of them for the whole
composition — `SequentialGroup` accumulates them into one set at
construction (`commandsv3/.../SequentialGroup.java:44-48`). **[source]**
The mechanisms are therefore *held but uncommanded* in the gaps between
children, and their default commands cannot resume. The coroutine form
releases each mechanism the moment its child ends.

On a drive base that is concrete: a `requiring(drive)` auto leaves the
modules held and unwritten between path segments.

**Written exception:** if a routine genuinely must keep holding a
mechanism across the gaps, use `requiring` — and say why in a comment.

### Command names are `Mechanism.Action`

PascalCase, dot-separated, with a `[parameter]` suffix when the command
is parameterised: `Drive.Idle`, `Drive.FollowPath[nz-sweep-left-trench]`.
Upstream's own scheme (`rebuiltcmdv3/mechanisms/SwerveDrive.java:95,230`)
**[source]**, and consistent with ADR 0005's PascalCase habit.

Names are **structurally mandatory**: `named(String)` is the only method
on the builder's last stage that returns a `Command`
(`commandsv3/.../NeedsNameBuilderStage.java:55`). **[source]** Since
those names land in the WPILOG command tree, this *is* the command-logging
convention — there is no second one.

`withAutomaticName()` is allowed only for inline group compositions,
where `"A -> B"` beats a name we would have invented. It exists only on
the two group builders (`SequentialGroupBuilder.java:108`,
`ParallelGroupBuilder.java:127`) **[source]**, so the rule is close to
self-enforcing.

### What earns the `Mechanism` type

**A class is a `Mechanism` if and only if commands need exclusive
ownership of it.** **[decided]**

`Mechanism` is a mutual-exclusion token and nothing else. There is no
registration, no `periodic()`, no base class — the whole interface is
default methods over `getRegisteredScheduler()`
(`commandsv3/.../Mechanism.java`). **[source]** So:

| Class | Mechanism? | Why |
|---|---|---|
| `Drive` | yes | commands take exclusive ownership of it |
| `SwerveModule` | no | owned by `Drive`; never independently commanded |
| `PoseEstimator` | no | read by many commands at once |

Upstream agrees on both negatives: `SwerveModule` and `PoseEstimator` are
plain classes (`rebuiltcmdv3/mechanisms/SwerveModule.java:14`,
`rebuiltcmdv3/PoseEstimator.java:16`). **[source]**

Nested mechanisms are allowed — upstream's `Intake` owns `IntakeWrist`
and `IntakeRoller`, both mechanisms in their own right
(`rebuiltcmdv3/mechanisms/`) **[source]** — but only when the children
are **independently commandable**. Nothing on a drive base is.

This rule also retires the perennial v2 question, *"is my follower motor
a subsystem?"*

### Every mechanism takes a `Scheduler` — our one divergence from upstream

**Every mechanism takes a `Scheduler` as its last constructor parameter
and overrides `getRegisteredScheduler()` to return it.** `Robot` passes
`Scheduler.getDefault()`. Triggers built inside a mechanism use
`new Trigger(scheduler, condition)`, never the one-argument form.

```java
public class Drive implements Mechanism {
  private final Scheduler scheduler;

  public Drive(/* ... */ Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Override
  public Scheduler getRegisteredScheduler() {
    return scheduler;
  }
}
```

`Mechanism`'s default `getRegisteredScheduler()` returns the process-wide
singleton (`Mechanism.java:28-30`), and `setDefaultCommand`,
`getDefaultCommand` and `getRunningCommands` all route through it
(`Mechanism.java:55, 65, 130`). **[source]** A mechanism that does not
take a `Scheduler` is therefore welded to the singleton for the life of
the class. Upstream's `ExampleMechanism` does exactly that, and
consequently cannot be unit-tested in isolation, while the library's own
suite builds `Scheduler.createIndependentScheduler()`
(`Scheduler.java:181`) for every test
(`commandsv3/src/test/java/org/wpilib/command3/CommandTestBase.java:25`).
**[source]**

This is a **day-one decision, not a later refactor** — retrofitting it
across eight mechanisms is eight constructor signatures and every call
site. It costs one field and one override, and it is the same shape as
ADR 0003's injected `TelemetryTable`.

It is a deliberate departure from upstream: neither the template nor
`rebuiltcmdv3` does this. We take it because upstream's shape is
untestable and ours is not. **[decided]**

### `idle()` is actively safe, and defaults are set where you can see them

**Every mechanism overrides `idle()` to be actively safe.** **[decided]**
The built-in is `run(Coroutine::park)` (`Mechanism.java:106-108`), and
`park()` is an infinite yield loop that does nothing at all
(`Coroutine.java:136-143`).
**[source]** It holds the mechanism and writes nothing, so a
gravity-loaded arm sags while its "idle" command runs.

For the drive, safe means **stop all four modules — not an X-lock**. A
brake a student cannot drive out of is a worse failure mode than
coasting. Upstream's swerve makes the same call
(`rebuiltcmdv3/mechanisms/SwerveDrive.java:86-96`). **[source]**

**`Robot`'s constructor sets every default command explicitly**, even
though `idle()` is already the implicit default, so that the safe state
of every mechanism is one readable block in review
(`rebuiltcmdv3/Robot.java:33-36`). **[source]**

An override has to carry its own priority — see *Priorities*, and Traps.

### Priorities: default everywhere

**Use the default priority.** **[decided]** The only sanctioned non-default
is `LOWEST_PRIORITY` on idle and stop commands, and **an overridden `idle()`
re-applies it by hand** with `.withPriority(Command.LOWEST_PRIORITY)`. The
built-in `idle()` attaches that priority for you (`Mechanism.java:106-108`)
**[source]**; overriding the method drops it — see Traps.

Any other `withPriority` call **needs a comment naming what it is meant
to beat.** If you cannot name it, you do not want a priority. Priorities
are load-bearing in a way that is easy to miss — see Traps.

### `StateMachine` is an escape hatch, and the drive base does not use it

`StateMachine` exists (`commandsv3/.../StateMachine.java`) and has zero
usages anywhere in `wpilibjExamples`. **[source]** The design doc's own
side-by-side shows the coroutine version of the same autonomous routine
is shorter **[source — `docs/research/commands-v3.md` §8]**.

One written test for reaching for it **[decided]**: **only when behaviour
has arbitrary re-entry** — phases that can be skipped to, repeated, or
interrupted-and-resumed from any point. Anything linear or nested stays
coroutine composition.

### `addPeriodic` and `sideload`: not used

v3's replacement for `Subsystem.periodic()` (`Scheduler.java:284, 329`)
**[source]** is not adopted. **[decided]** Both of our recurring non-command
jobs already have an explicit home: odometry updates in `robotPeriodic()`
before the scheduler runs, sim ticks in `simulationPeriodic()`.

The reason is worth keeping: **a sideload is invisible in the command
timeline and dies with its scope**, so it can only ever make a timing bug
harder to find. Periodic work that is not a command lives in a `Robot`
hook you can read top to bottom.

### Opmodes hold bindings, and nothing else

An opmode constructor contains **only** bindings, default-command
overrides, and the enabled-trigger for autonomous. Never hardware
construction. **Never trajectory loading** — an opmode is constructed
when the operator selects it on the Driver Station and `close()`d when
they select something else **[source — `docs/research/opmodes.md`
§2]**, so a deselect/reselect reloads every trajectory. Trajectories
cache on `Robot`.

Naming is `<What>Teleop` / `<What>Auto` / `<What>Check`, with `group()`
doing the grouping (ADR 0003). **A `DoNothingAuto` exists as the safe
pick.** **[decided]**

**`@Utility` opmodes inherit every rule above with no exemptions** —
they are the ones most likely to be written in a hurry the night before
an event. They are named `<Thing>Check` or `<Thing>Calibration`, and they
carry one hard rule: **a `@Utility` opmode never runs unattended, and
must not require a field, a driver, or a game piece.** **[decided]** If
it needs any of those, it is a teleop.

The rule used to read *safe to run with the robot on blocks*. On blocks
was a proxy for supervision, and ADR 0009 broke it: characterisation has
to run on the ground, because on-blocks feedforward gains measure a
motor rather than a drive base. The proxy is replaced by what it stood
for rather than given an exception beside it, because a rule with an
exception beside it is a rule with two meanings. **Characterisation is
the named supervised-on-ground case**; anything else claiming that
ground needs its own entry here.

### Triggers and bindings

Controllers and shared robot-state triggers are `public final` fields on
`Robot`; a trigger only one opmode cares about is constructed in that
opmode.

That is safe, and the reason is not obvious: a `Trigger`'s *creation*
scope is fixed in its constructor (`Trigger.java:112`, read back at
`:518`), but each **binding computes its own scope at `onTrue()` time**
(`Trigger.java:564-565` calls `BindingScope.createNarrowestScope`).
**[source]** So a trigger held as a `Robot` field is global-scoped and
never unbound, while a binding made on it inside an opmode constructor is
opmode-scoped and is still torn down when the opmode exits.

**`CommandGamepad`, not `CommandXboxController`.** Both upstream
references use the layout-neutral form — `faceUp()`, `rightBumper()`
(`commandsv3/.../button/CommandGamepad.java`) **[source]** — which
survives a controller swap and reads the same on a DualSense. Ports live
in `Constants`.

**Edge factories are for composing an edge as a value.** `onTrue` already
edge-detects; `risingEdge()` and `fallingEdge()` exist so that an
arbitrary boolean — a sensor, a pose predicate — can *be* an edge.

### Loops in a coroutine body are always `while`

Not a style preference. It is the construction rule that closes a hole in
the compile-time net **[decided]** — see Traps.

## Consequences

- **Mechanisms are unit-testable without a `RobotBase`, and it has been
  done.** **[executed —
  `src/test/java/first/robot/InjectedSchedulerTest.java`]** A plain
  JUnit test builds a mechanism in the shape above against
  `Scheduler.createIndependentScheduler()` and runs one command to
  completion, asserting on elapsed time rather than on cycles.

  The injection is what the second test turns on, and it had to be
  written to. `Mechanism.run(...)` never touches a scheduler
  (`Mechanism.java:74`), so a test that only calls
  `scheduler.schedule(command)` would pass against an un-injected
  mechanism and prove nothing. `setDefaultCommand` and
  `getRunningCommands` are the methods that route through
  `getRegisteredScheduler()` (`Mechanism.java:55, 130`), so the test
  registers a default command *on the mechanism* and asserts it runs on
  the test's scheduler and not on the singleton. With the override
  deleted it fails. **[executed]** The divergence pays.

  What it costs to start a v3 test at all — the `--add-opens` block, the
  redirected clock, and the HAL that scheduling loads — is ADR 0013's,
  and is recorded there.

- **Every mechanism constructor grows one parameter**, and every mechanism
  carries one override. That is the whole cost of the divergence.
- **New students will read upstream and find a shape we do not use.**
  `ExampleMechanism` implements `Mechanism` with no scheduler at all. The
  teaching document exists partly to get ahead of that.
- **The command log's naming is decided here, not in ADR 0005.** Names
  are mandatory at construction, so the convention has to live where
  commands are written.
- **ADR 0005 inherits a command tree it does not name.** It owns signal
  names; command names come out of `Mechanism.Action`.
- **ADR 0011 inherits the trajectory-cache rule.** "Opmode constructors
  never load trajectories" is only survivable if something else caches
  them, and that is `Robot`.
- **No `commands/` package means mechanism files get long.** That is the
  trade ADR 0003 already accepted for `Drive`.

## Traps

- **A lost `fork()` priority contest cancels the parent, silently.**
  `Coroutine`'s `m_cancelOnForkFailure` defaults to `true`
  (`Coroutine.java:30`); when a forked child cannot be scheduled, the
  coroutine records the failure and yields, and the scheduler cancels it
  — with the comment *"no coroutine or user code gets to run to handle
  the failure result"* (`Coroutine.java:257-262`). **[source]** This is
  what makes an unexplained `withPriority` dangerous. It is worst inside
  a `StateMachine`, which forks each state's command and whose own source
  notes *"the state machine will exit if the child command fails to be
  forked"* (`StateMachine.java:165-167`) **[source]** — one priority
  collision takes the entire machine down with no exception and no log
  line beyond the cancellation event.

- **`for(;;)` compiles clean and hangs the robot.** The javac plugin's
  missing-`yield` check is a hard, unsuppressible compile *error*
  (`javacPlugin/.../CoroutineYieldInLoopDetector.java:219`,
  `Diagnostic.Kind.ERROR`), but its scanner overrides `visitWhileLoop`
  only (`:175`). There is no `visitForLoop`, `visitEnhancedForLoop` or
  `visitDoWhileLoop`. **[source]** A `for (;;)` or a `do/while` in a
  coroutine body compiles with no diagnostic and never yields, which
  hangs the scheduler thread. The house rule — *loops in a coroutine body
  are always `while`* — routes every loop we write into the check WPILib
  already ships. We do not write a second, weaker checker of our own; the
  compiler does the enforcing.

- **Cancellation is not an exception unwind, so `finally` never runs.**
  On cancellation the scheduler drops the command from its running set
  and calls `onCancel()` (`Scheduler.java:748-772`); the continuation is
  never remounted and is left for GC. **[source, and
  `docs/research/commands-v3.md` §2]** Cleanup goes in the builder's
  `whenCanceled(Runnable)`
  (`NeedsNameBuilderStage.java:27`). **[source]** This is the idiom most
  likely to be gotten wrong by anyone carrying v2 habits, and it fails
  silently.

- **Overriding `idle()` drops its `LOWEST_PRIORITY`.** The built-in
  attaches the priority in the same expression that builds the command
  (`Mechanism.java:106-108`), so an override that does not repeat
  `.withPriority(Command.LOWEST_PRIORITY)` leaves the mechanism idling at
  `DEFAULT_PRIORITY`. Upstream's own override does exactly that
  (`rebuiltcmdv3/mechanisms/SwerveDrive.java:86-96`). **[source]** Nothing
  catches it: `Scheduler.setDefaultCommand` validates the requirements and
  not the priority (`Scheduler.java:207-217`), and only a *strictly* lower
  priority is refused scheduling (`Scheduler.java:512-526`). **[source]**
  The mechanism's own javadoc asks for a sub-default priority here
  (`Mechanism.java:43-47`). **[source]** The visible symptom is a stop
  command — the other command we sanction at `LOWEST_PRIORITY` — silently
  failing to interrupt an idle.

- **`await()` yields internally.** It loops on
  `isScheduledOrRunning` and yields inside that loop
  (`Coroutine.java:443-447`). **[source]** An added `coroutine.yield()`
  after an `await` costs a scheduler cycle for nothing.

- **`whileTrue` on an edge trigger cancels immediately.** A
  `risingEdge()` trigger is active for exactly one scheduler cycle
  (`Trigger.java:312-315`, and its javadoc says so at `:302-311`).
  **[source]** Bind edges with `onTrue`.

- **A `Trigger` built in a mechanism with the one-argument constructor
  binds to the singleton.** `new Trigger(condition)` takes
  `Scheduler.getDefault()` (`Trigger.java:96`) **[source]**, which
  reintroduces exactly the coupling the injected `Scheduler` was taken to
  remove — and it does it in a class that otherwise looks correct.

- **Names are mandatory, so an unnamed command is a compile error, not a
  bad log.** This is a trap only in the sense that it surprises: the
  builder stages are the enforcement, and there is no way to opt out for
  a quick test.

## Rejected

### Command classes and a `commands/` package

The v2 shape. Not available: there is no `Command` class to extend, only
an interface whose implementations the builders produce. Writing one by
hand would mean reimplementing `BuilderBackedCommand`
(`StagedCommandBuilder.java:172`) to get a worse version of a factory
method. **[source]**

### `Command.requiring(...)` and the built-in groups as the default

Rejected as the *default*, not removed from the toolbox. The ground is
under *`noRequirements` + `await`*: a group holds the union of its
children's requirements for the whole composition, so mechanisms sit held
and unwritten in the gaps. Keep it for the case where holding through the
gaps is the point, with a comment saying so.

### A lint rule or CI check for `for(;;)` in coroutine bodies

A second, weaker copy of a check WPILib already ships and already
enforces as a compile error for `while`. It would need its own
maintenance and would still be advisory. The construction rule is
cheaper and routes into the stronger check. Same shape as ADR 0004's
tunables rule.

### `StateMachine` for autonomous routines

Zero upstream usage, a longer expression of the same routine, and a
cancellation failure mode that takes the whole machine down (see Traps).
Kept as a documented escape hatch for arbitrary re-entry only.

### `addPeriodic` / `sideload` for odometry and sim

The v3 replacement for `Subsystem.periodic()`. Rejected because a
sideload is invisible in the command timeline and dies with its scope,
which can only make a timing bug harder to find. Both jobs have explicit
`Robot` hooks instead.

### `CommandXboxController`

Layout-specific where `CommandGamepad` is not, and neither upstream
reference uses it. A controller swap mid-event should not be a code
change.

### An X-lock as the drive's idle state

Rejected on the failure mode, not the physics: a brake a student cannot
drive out of is worse on a field than coasting. A deliberate X-lock is a
command someone asks for, not the state the robot falls into.

### Following upstream and omitting the `Scheduler` parameter

The path of least surprise, and it is what both upstream references do.
Rejected because it makes every mechanism permanently untestable in
isolation — upstream's own test suite does not use its own mechanism
shape. **Do not re-raise this without new evidence.** New evidence means
`Mechanism` gaining a scheduler-independent path for default commands and
running-command queries, not a fresh reading of `ExampleMechanism`.

## Source

Decided in [#17](https://github.com/Drew-Robotics/2027beta/issues/17),
which builds on [#12](https://github.com/Drew-Robotics/2027beta/issues/12)
for opmode layout, [#11](https://github.com/Drew-Robotics/2027beta/issues/11)
for the naming habit, and
[#15](https://github.com/Drew-Robotics/2027beta/issues/15) and
[#14](https://github.com/Drew-Robotics/2027beta/issues/14) for the two
recurring jobs that would otherwise want `addPeriodic`. The test pattern
#17 specifies belongs to ADR 0013 and is not restated here.

Research: [`docs/research/commands-v3.md`](../research/commands-v3.md),
[`docs/research/opmodes.md`](../research/opmodes.md).

Teaching document:
[`docs/commands-v3-house-style.md`](../commands-v3-house-style.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`commandsv3/src/main/java/org/wpilib/command3/` — `Mechanism.java`,
`Command.java`, `Coroutine.java`, `Scheduler.java`, `Trigger.java`,
`StateMachine.java`, `SequentialGroup.java`, `StagedCommandBuilder.java`,
`NeedsNameBuilderStage.java`, `button/CommandGamepad.java`;
`commandsv3/src/test/java/org/wpilib/command3/CommandTestBase.java`;
`javacPlugin/src/main/java/org/wpilib/javacplugin/CoroutineYieldInLoopDetector.java`;
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`;
`wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/`.

### Departure from #17

#17 states that `run`, `runRepeatedly`, `idle` and `setDefaultCommand`
all route through `getRegisteredScheduler()`. Only `setDefaultCommand`,
`getDefaultCommand` and `getRunningCommands` do (`Mechanism.java:55, 65,
130`); `run`, `runRepeatedly` and `idle` build through
`StagedCommandBuilder` and never touch a scheduler (`Mechanism.java:74,
86, 106`). **[source]** The decision is unaffected — default-command
registration and running-command queries are enough to weld an
un-injected mechanism to the singleton — so this ADR states the narrower
and accurate version.
