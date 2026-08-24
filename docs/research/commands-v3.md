# Commands v3 (`org.wpilib.command3`) — API surface and idioms

**Research for:** [Drew-Robotics/2027beta#3](https://github.com/Drew-Robotics/2027beta/issues/3)
**Date:** 2026-08-24

## Source and trust level

Everything below marked **[V]** was verified by reading source in a local built checkout of
`allwpilib` at `v2027.0.0-alpha-6-366-gcafb0cc79` (effectively alpha-7). Items marked **[I]** are
inference — reasonable, but not stated anywhere upstream. `docs.wpilib.org` was deliberately *not*
used; it lags this tree badly.

Paths below are relative to that checkout root (`~/dev/allwpilib`).

| What | Path |
| --- | --- |
| Authors' rationale | `design-docs/commands-v3.md` (488 lines) |
| State machine rationale | `design-docs/commands-v3-state-machines.md` (273 lines) |
| Library | `commandsv3/src/main/java/org/wpilib/command3/` (~7.5k lines, 31 files) |
| Tests | `commandsv3/src/test/java/org/wpilib/command3/` |
| Compile-time checks | `javacPlugin/src/main/java/org/wpilib/javacplugin/` |
| Official template | `wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/` |
| Skeleton template | `wpilibjExamples/src/main/java/org/wpilib/templates/commandv3skeleton/` |
| **TimedRobot** v3 example | `wpilibjExamples/src/main/java/org/wpilib/examples/hatchbotcmdv3/` |
| Full swerve v3 example | `wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/` |

---

## 1. The headline: what v3 actually is

The single sentence from the design doc that explains every other decision **[V]**
(`design-docs/commands-v3.md`, "Goals"):

> **The primary goal of the new framework is to allow for commands to be written as normal methods
> by taking advantage of this mount/unmount feature.** Everything else is focused on quality of life
> improvements to the framework.

The problem statement is explicitly pedagogical **[V]**:

> This API share has a steep learning curve, since new students learn looping by writing their own
> `for` or `while` loops - not by using a framework that does the looping for them. Programmers
> unfamiliar with the command framework commonly run into a problem where they write a while loop
> inside their command's `execute()` function, thus stalling the scheduler [...]

So v3 collapses `initialize()/execute()/isFinished()/end()` into one method **[V]**
(`Command.java` class javadoc):

```java
coroutine -> {
  initialize();
  while (!isFinished()) {
    execute();
    coroutine.yield(); // be sure to yield at the end of the loop
  }
  end();
}
```

### Core type map, v2 → v3

| v2 | v3 | Notes |
| --- | --- | --- |
| `Subsystem` / `SubsystemBase` | **`Mechanism`** (interface, no base class) | see §7 |
| `Command` (abstract class, 4 lifecycle methods) | `Command` (interface, one `run(Coroutine)`) | **[V]** |
| `CommandScheduler.getInstance()` | `Scheduler.getDefault()` | **[V]** |
| — | `Coroutine` | new **[V]** |
| — | `StateMachine` | new, see §8 **[V]** |
| decorators returning new commands | staged builders (`NeedsExecutionBuilderStage` → `NeedsNameBuilderStage` → `Command`) | **[V]** |

---

## 2. The coroutine model — how it actually works

### Mechanism (the machinery) **[V]**

`Coroutine` is a thin wrapper over `jdk.internal.vm.Continuation`, accessed **entirely by
reflection** through `MethodHandles.privateLookupIn` (`commandsv3/.../Continuation.java`). The
scheduler holds a single `ContinuationScope`; each *run* of a command gets a fresh `Continuation`
and a fresh `Coroutine` bound to it.

The scheduler's `run()` loop mounts a command's continuation (pushes its saved stack onto the
current stack), lets it execute until it calls `coroutine.yield()` or returns, then unmounts it
(freezes stack + registers) and moves to the next command **[V]** (`design-docs/commands-v3.md`,
"Coroutines and Continuations").

`Continuation.java` carries a blunt warning **[V]** — worth reading to students verbatim:

> **ONLY USE CONTINUATIONS IN A SINGLE THREADED CONTEXT.** [...] Failure to use this API safely can
> result in JIT compilers to issue invalid code causing buggy behavior and JVM crashes at any time,
> up to and including on a field during an official match.

Cancellation is *not* an exception unwind: a cancelled command's continuation is simply never
remounted and is left for GC. Because the body never resumes, cleanup cannot live in a `finally` —
it goes in a separate `onCancel()` hook, set via the builder's `whenCanceled(Runnable)` **[V]**.

### Build requirement — non-negotiable **[V]**

Reflective access to `jdk.internal.vm` must be opened. Every Gradle project in the tree that runs
v3 code adds these JVM args (`commandsv3/build.gradle`, `wpilibjExamples/build.gradle`,
`developerRobot/build.gradle`):

```groovy
jvmArgs += [
    '--add-opens', 'java.base/jdk.internal.vm=ALL-UNNAMED',
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
]
```

The deployed robot command line in `developerRobot/build.gradle` includes them too:

```
/usr/bin/java -XX:+UseG1GC ... --add-opens java.base/jdk.internal.vm=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED ...
```

**This applies to our `test` task as well as `run`/`deploy`.** Unit tests that touch a `Scheduler`
will fail at class-init without these flags. (See §6.)

### Scheduler `run()` cycle — exact order **[V]** (`design-docs/commands-v3.md`)

1. Cancel commands bound to inactive scopes
2. Cancel triggers bound to inactive scopes
3. Run periodic sideload functions
4. Poll the event loop for triggers (may queue or cancel commands)
5. Schedule default commands for the next iteration
6. Promote scheduled commands to running (cancelling conflicting running commands first)
7. Iterate running commands: mount → run to yield → unmount → evict if finished (cancelling any
   still-running inner commands)

### `Coroutine` API **[V]** (`commandsv3/.../Coroutine.java`)

```java
public boolean yield();                                  // always returns true
public void park();                                      // yield forever; never returns
public ForkResult fork(Command... commands);             // start children, don't wait
public ForkResult fork(Collection<? extends Command> commands);
public ForkResult await(Command command);                // start + wait for completion
public ForkResult awaitAll(Command... commands);
public ForkResult awaitAny(Command... commands);
public void wait(Time duration);
public WaitResult waitUntil(BooleanSupplier condition);
public WaitResult waitUntil(BooleanSupplier condition, Time timeout);  // WaitResult.TIMED_OUT
public void requestCancellation();
public void setCancelOnForkFailure(boolean b);           // default true for commands
public boolean isCancelOnForkFailure();
public Scheduler scheduler();                            // "Advanced users only"
```

Two things students trip on **[V]**:

- `await()` **yields internally**, so you must *not* add your own `yield()` after it. The upstream
  example says so explicitly (`rebuiltcmdv3/mechanisms/Intake.java`):
  ```java
  while (true) {
    // await() will yield internally until the wrist has moved to the desired position,
    // so we don't need to explicitly yield here
    coroutine.await(wrist.moveToAngle(WRIST_AGITATE_UP));
    coroutine.await(wrist.moveToAngle(WRIST_AGITATE_DOWN));
  }
  ```
- `await(cmd)` does **not** call `cmd.run(coroutine)` directly — it schedules the child through the
  scheduler so it stays visible in telemetry and still participates in requirement mutexing **[V]**
  (`design-docs/commands-v3.md`).

`fork()` can *fail* — if a child shares a requirement with a higher-priority command. By default a
command cancels itself on fork failure (`setCancelOnForkFailure` defaults to `true` for commands,
`false` for sideloads) **[V]**.

### Everything nested is implicitly "proxied" **[V]**

The doc's own emphasis:

> **Effectively, all child commands in v3 are "proxied"**, using the v2 framework's definition,
> unless using the built-in ParallelGroup and Sequence compositions or explicitly adding child
> command requirements to the parent. However, child commands _cannot_ interrupt their parent, even
> if they share requirements, unlike proxy commands in v2.

This is the single most important behavioural difference from v2, and it's a genuine fork in the
road for how we write autos. The template shows both sides **[V]** (`hatchbotcmdv3/commands/Autos.java`):

```java
// NOTE: requirement behavior.
// To require each mechanism for while it's active, replace `requiring` with `noRequirements`.
return Command.requiring(driveMechanism, hatchMechanism)
    .executing(coro -> {
      coro.await(driveMechanism.driveDistance(...));
      coro.await(hatchMechanism.releaseHatchCommand());
      coro.await(driveMechanism.driveDistance(...));
    })
    .named("Complex Auto");
```

- `Command.requiring(a, b)` — owns both for the whole sequence (v2 parity; safe; leaves mechanisms
  *uncommanded* while unused).
- `Command.noRequirements(...)` — owns each mechanism only while its child runs, so default commands
  resume in the gaps. This is the "advanced" form and what `rebuiltcmdv3` uses throughout.

### Scoping — triggers/defaults/scheduled commands are scope-bound **[V]**

`BindingScope` is a sealed interface with three impls: `Global`, `OpMode`, `Command`. A trigger
binding, a `setDefaultCommand` call, or a manual `schedule()` made inside a command or opmode is
**automatically torn down when that scope exits** (bindings unbound, defaults reverted to the
enclosing scope's, scheduled commands cancelled). The opmode identity comes from
`RobotState.getOpModeId()` via a package-private `OpModeFetcher` **[V]**.

The upstream TimedRobot example claims this gives free auto-cancellation
(`hatchbotcmdv3/Robot.java`):

```java
// Because we schedule this command in the autonomous mode, it will be automatically canceled
// when the autonomous mode ends.
if (autonomousCommand != null) {
  Scheduler.getDefault().schedule(autonomousCommand);
}
```

**⚠ That comment appears to be wrong for a plain `TimedRobot` program.** See §11 — under
`TimedRobot`, opmode scoping never engages, so this command is Global-scoped and is *never*
auto-cancelled. This is the single most consequential finding in this document for our stack.

---

## 3. Compile-time checks for unsafe coroutine usage

These are **not** ErrorProne. They live in a first-party javac plugin,
`javacPlugin/src/main/java/org/wpilib/javacplugin/`, registered via
`META-INF/services/com.sun.source.util.Plugin` with `autoStart() == true` — so it runs with no
`-Xplugin` flag **[V]** (`WPILibJavacPlugin.java`). The plugin's own README states the design rule:

> This plugin should only be used for static analysis, not to enhance the Java language with syntax
> features. Adding additional syntax features is outside the scope of this plugin and can be
> confusing to beginners.

The plugin registers eight listeners; three are coroutine-specific, plus two general ones that
matter to v3.

### 3a. `CoroutineYieldInLoopDetector` — the big one **[V]**

Class javadoc:

> Checks for `while` loops inside methods or lambda functions that accept coroutine arguments. If a
> loop does not call `yield()` on one of the most local coroutine objects, a compiler error will be
> emitted for that loop element. **This check cannot be silenced.**

- Severity: `Diagnostic.Kind.ERROR` — a hard compile failure, not a warning.
- Message format (exact, from `CoroutineInLoopListenerTest`):
  `Missing call to \`coroutine.yield()\` inside loop`
  With multiple coroutines in scope: ``Missing call to `a.yield()`, `b.yield()`, or `c.yield()` inside loop``
- Nested loops are each checked independently and errors are reported outermost-first, deliberately
  (source comment: inner-first "would appear out of order, which is confusing"). A test with 7
  nested loops expects 7 errors.
- Why it can't be suppressed (source comment):
  ```java
  // Note: cannot be silenced because annotations cannot be placed on loops.
  // This is not legal Java:
  //   @SuppressWarnings("UnsafeCoroutineUsage")
  //   while (true) { ... }
  ```

**⚠ Gap worth teaching around [V]:** the scanner only overrides `visitWhileLoop`. There is **no**
`visitForLoop`, `visitEnhancedForLoop`, or `visitDoWhileLoop`. A `for (;;) { }` or
`do { } while (…)` with no yield compiles clean and hangs the robot. Students must be told the
check covers `while` only.

### 3b. `IncorrectCoroutineUseDetector` **[V]**

Two errors, both `Kind.ERROR`, both suppressible:

1. **Wrong coroutine in scope** — suppression key `CoroutineMayNotBeInScope`.
   Message: ``Coroutine `outerCoroutine` may not be in scope. Consider using `innerCoroutine` ``
   Triggered by calling a method on, *or passing as an argument*, a coroutine from an enclosing
   lambda when a more local one exists:
   ```java
   mech.run(outerCoroutine -> {
     mech.run(innerCoroutine -> {
       outerCoroutine.yield(); // ERROR
     })
   })
   ```
2. **Captured coroutine stored in a field** — suppression key `CoroutineCapture`.
   Message: `Captured coroutines may not be stored in fields`
   ```java
   private Coroutine coroutineField;
   mech.run(coroutine -> coroutineField = coroutine); // ERROR
   ```
   This is the compile-time counterpart to the runtime `IllegalStateException` the design doc
   describes under "Unbounded use of coroutines".

### 3c. `CodeAfterCoroutineParkDetector` **[V]**

Suppression key `CodeAfterCoroutinePark`. Any statement following `coroutine.park()` in the same
block is an error:

> `Unreachable statement: \`coroutine.park()\` will never exit`

Empty statements (stray `;`) are skipped. Only the first offending statement is reported per block.

### 3d. Relevant non-coroutine checks in the same plugin **[V]**

- `ReturnValueUsedListener` + `@NoDiscard` (from `wpiannotations`). `Command` itself is annotated
  `@NoDiscard("Commands must be used! Did you mean to fork it or bind it to a trigger?")`, as are
  the builder stage interfaces. This is what makes "forgot to name the command" and "built a command
  and dropped it" compile errors.
- `PostConstructionInitializerListener` / `@PostConstructionInitializer` — enforces that
  `StateMachine.setInitialState(...)` is called (§8).
- Also present: `OpModeAnnotationValidator`, `MaxLengthDetector`, `IntegerDivisionDetector`.

Suppression works through ordinary `@SuppressWarnings("<key>")` on the element or any enclosing
element; `"all"` also works **[V]** (`Suppressions.java`).

---

## 4. Builders, naming, and command construction

Naming is **structurally forced**, not documented-and-hoped **[V]**. The builder is split into
stage *types*; you cannot get a `Command` without passing through `named(String)` or
`withAutomaticName()`:

```java
// StagedCommandBuilder
public NeedsExecutionBuilderStage noRequirements();
public NeedsExecutionBuilderStage requiring(Mechanism requirement, Mechanism... extra);
public NeedsExecutionBuilderStage requiring(Collection<Mechanism> requirements);

// NeedsExecutionBuilderStage  (@NoDiscard)
NeedsExecutionBuilderStage requiring(Mechanism requirement);
NeedsNameBuilderStage       executing(Consumer<Coroutine> impl);

// NeedsNameBuilderStage  (@NoDiscard)
NeedsNameBuilderStage whenCanceled(Runnable onCancel);
NeedsNameBuilderStage withPriority(int priority);
NeedsNameBuilderStage until(BooleanSupplier endCondition);
Command               named(String name);
```

Static entry points on `Command` **[V]**:

```java
static NeedsNameBuilderStage      noRequirements(Consumer<Coroutine> body);
static NeedsExecutionBuilderStage requiring(Mechanism requirement, Mechanism... rest);
static NeedsExecutionBuilderStage requiring(Collection<Mechanism> requirements);
static ParallelGroupBuilder       parallel(Command... commands);   // ends when ALL finish
static ParallelGroupBuilder       race(Command... commands);       // ends when ANY finishes
static SequentialGroupBuilder     sequence(Command... commands);
static NeedsNameBuilderStage      waitUntil(BooleanSupplier condition);
static NeedsNameBuilderStage      waitFor(Time duration);
```

Instance composition methods on `Command` **[V]** — note they return *builders*, not commands:

```java
default Command                withTimeout(Time timeout);       // returns a Command
default ParallelGroupBuilder   until(BooleanSupplier endCondition);
default SequentialGroupBuilder andThen(Command next);
default ParallelGroupBuilder   alongWith(Command... parallel);
default ParallelGroupBuilder   raceWith(Command... parallel);
```

Group builders finish with `named(String)` or `withAutomaticName()`. Automatic names are
`"A -> B -> C"` for sequences and `"(A & B)"`-style pipe/ampersand joins for parallel groups **[V]**.

Priority levels replace v2's binary interrupt behaviour **[V]**: `Command.LOWEST_PRIORITY`
(`Integer.MIN_VALUE`), `DEFAULT_PRIORITY` (0), `HIGHEST_PRIORITY` (`Integer.MAX_VALUE`). A scheduled
command interrupts a running one only if its priority is **equal or higher**.

---

## 5. Mechanisms, requirements, scheduling, and triggers

### `Mechanism` is an interface with defaults **[V]** (`commandsv3/.../Mechanism.java`)

```java
public interface Mechanism {
  default Scheduler getRegisteredScheduler();          // Scheduler.getDefault()
  default String getName();                            // getClass().getSimpleName()
  default void setDefaultCommand(Command defaultCommand);
  default Command getDefaultCommand();
  default NeedsNameBuilderStage run(Consumer<Coroutine> commandBody);
  default NeedsNameBuilderStage runRepeatedly(Runnable loopBody);
  default Command idle();
  default Command idleFor(Time duration);
  default List<Command> getRunningCommands();
}
```

There is **no `MechanismBase`** — you write `class Elevator implements Mechanism` and get everything
from default methods. Nothing to register; nothing to call `super()` on.

### `Mechanism.idle()` — what it's actually for **[V]**

```java
default Command idle() {
  return run(Coroutine::park).withPriority(Command.LOWEST_PRIORITY).named(getName() + "[IDLE]");
}
```

Javadoc:

> Returns a command that idles this mechanism until another command claims it. The idle command has
> the lowest priority and can be interrupted by any other command.
>
> The default command for every mechanism is an idle command unless a different default command has
> been configured.

So `idle()` is the *implicit* default command: it holds ownership and does nothing, at
`LOWEST_PRIORITY` so anything can take over. Two idioms follow:

- **Override `idle()` to make it safe, not passive.** `rebuiltcmdv3`'s swerve does exactly this **[V]**:
  ```java
  @Override
  public Command idle() {
    return runRepeatedly(() -> { frontLeft.stop(); frontRight.stop();
                                 rearLeft.stop();  rearRight.stop(); })
        .named("Drive.Idle");
  }
  ```
  and `Intake` aliases `idle()` to its `stop()` command.
- **Set it explicitly anyway** so it's visible in code review **[V]** (`rebuiltcmdv3/Robot.java`):
  ```java
  swerveDrive.setDefaultCommand(swerveDrive.idle());
  shooter.setDefaultCommand(shooter.idle());
  intake.setDefaultCommand(intake.idle());
  ```

**Also note:** the javadoc on `Mechanism.getRegisteredScheduler()` still says *"Returns the scheduler
under which this **subsystem** and its default commands are registered"* — a stray leftover from the
rename. **[V]**

### Composite mechanisms **[V]**

`rebuiltcmdv3.Intake` is a `Mechanism` that privately owns two other `Mechanism`s
(`IntakeWrist`, `IntakeRoller`) and exposes commands that require only *itself* while awaiting the
children:

```java
public Command intake() {
  return run(coroutine -> { coroutine.awaitAll(wrist.down(), roller.intake()); })
      .named("Intake.Intake");
}
```

This is the v3 answer to "coordinating two subsystems" — no proxying, no requirement plumbing.

### Scheduling — what replaces `Command.schedule()` **[V]**

There is no `Command.schedule()` in v3 at all. Entry points:

```java
Scheduler.getDefault().schedule(command);      // returns ScheduleResult
coroutine.fork(command); coroutine.await(command);   // from inside a command
trigger.onTrue(command);                       // binding
mechanism.setDefaultCommand(command);
```

`schedule()` and `isSchedulable()` return a sealed `ScheduleResult` **[V]** — a real improvement for
debugging:

```java
sealed interface ScheduleResult {
  Command command(); boolean successful();
  record Success(Command command) implements Successful {}
  record AlreadyRunning(Command command) implements Successful {}
  record LowerPriorityThanRunningCommand(Command command, Command alreadyRunning) implements Failure {}
  record LowerPriorityThanQueuedCommand(Command command, Command queuedCommand) implements Failure {}
}
```

Scheduling from the top level queues the command for the next `run()`. Scheduling an **inner**
command bypasses the queue and starts immediately, to avoid loop-time delays in deep nesting **[V]**.

Other useful `Scheduler` members **[V]**:

```java
static Scheduler getDefault();
static Scheduler createIndependentScheduler();   // for unit tests — see §6
void run(); void cancel(Command); void cancelAll();
boolean isRunning(Command); boolean isScheduled(Command); boolean isScheduledOrRunning(Command);
Collection<Command> getRunningCommands(); Collection<Command> getQueuedCommands();
List<Command> getRunningCommandsFor(Mechanism); Command getParentOf(Command);
Command currentCommand();
void sideload(Consumer<Coroutine> callback);
void addPeriodic(Runnable callback);
double lastCommandRuntimeMs(Command); double totalRuntimeMs(Command); double lastRuntimeMs();
int runId(Command);
void addEventListener(Consumer<? super SchedulerEvent> listener);
EventLoop getDefaultEventLoop();
public static final SchedulerProto proto;
```

`sideload` / `addPeriodic` are the v3 home for "run this every loop but don't own a mechanism" —
LED buffer updates, telemetry pushes, sim updates. `addPeriodic(r)` is exactly
`sideload(co -> { while (true) { r.run(); co.yield(); } })` **[V]**.

### Triggers **[V]** (`commandsv3/.../Trigger.java`)

Binding methods:

```java
Trigger onTrue(Command);          Trigger onFalse(Command);
Trigger whileTrue(Command);       Trigger whileFalse(Command);
Trigger retryWhileTrue(Command);  Trigger retryWhileFalse(Command);   // NEW
Trigger toggleOnTrue(Command);    Trigger toggleOnFalse(Command);
```

`retryWhileTrue` vs `whileTrue` — the distinction is stated in javadoc **[V]**:

> Unlike `whileTrue(Command)`, the command is restarted if it ends while the condition is still
> `true`. If the command stopped because it was interrupted, restarting it will immediately
> interrupt the would-be interrupting command (if they have the same priority).

Combinators and edge factories **[V]**:

```java
Trigger and(BooleanSupplier); Trigger or(BooleanSupplier); Trigger negate();
Trigger debounce(Time); Trigger debounce(Time, Debouncer.DebounceType);
Trigger risingEdge();                    // NEW
Trigger fallingEdge();                   // NEW
Trigger multiPress(int pressCount, Time duration);   // NEW — double/triple tap
void unbind();
```

`risingEdge()` / `fallingEdge()` produce triggers that are true for **exactly one scheduler cycle**.
The javadoc warns about the obvious footgun **[V]**:

> The resulting trigger will only be active for that single cycle before going inactive again;
> therefore, `onTrue(Command)` should be used instead of `whileTrue(Command)`, as commands bound
> using the latter method will be immediately canceled after a single scheduler cycle.

**[I]** These exist mainly so you can take an *arbitrary* boolean signal (a sensor, a pose
predicate) and get true edge semantics out of it, rather than relying on `onTrue`'s built-in
edge detection when you need the edge as a composable value.

Trigger constructors take an optional explicit `Scheduler` and `EventLoop` **[V]** — this is the
seam for unit-testing triggers against an independent scheduler:

```java
public Trigger(BooleanSupplier condition);
public Trigger(Scheduler scheduler, BooleanSupplier condition);
public Trigger(Scheduler scheduler, EventLoop loop, BooleanSupplier condition);
```

Controller wrappers live in `org.wpilib.command3.button`: `CommandGamepad`, `CommandGenericHID`,
`CommandJoystick`, plus generated per-controller classes (`CommandXboxController`,
`CommandDualSenseController`, `CommandSwitchProController`, …) and
`RobotModeTriggers` **[V]**. Note the templates use the **abstract** `CommandGamepad` with
layout-neutral names (`faceRight()`, `faceLeft()`, `faceUp()`, `faceDown()`, `rightBumper()`,
`rightStick()`) rather than `CommandXboxController.a()/b()` **[V]**.

---

## 6. Testing a v3 command without hardware

**Headline: commands v3 is hardware-free by construction.** `grep -rn "HAL\.\|DriverStationSim\|SimHooks"`
over `commandsv3/src/` returns **zero hits** in main *and* test sources **[V]**. There is no
`HAL.initialize()`, no `SimHooks.pauseTiming()`, no `DriverStationSim` anywhere in the v3 test
suite. That is deliberate — `OpModeFetcher`'s javadoc says so **[V]**:

> This is a package-private class so tests for this library don't need to hook into driverstation
> simulation and the HAL.

This is a meaningfully better story than v2, where `IntakeTest` in `wpilibjExamples` opens with
`assert HAL.initialize(); // initialize the HAL, crash if failed`.

### The three moves **[V]** (`commandsv3/src/test/java/org/wpilib/command3/CommandTestBase.java`)

```java
@BeforeEach
void initScheduler() {
  RobotController.setTimeSource(() -> System.nanoTime() / 1000L);
  m_scheduler = Scheduler.createIndependentScheduler();
  m_events = new ArrayList<>();
  m_scheduler.addEventListener(m_events::add);
}
```

1. **`RobotController.setTimeSource(LongSupplier)`** — public, takes **microseconds**. The default
   is `RobotController::getMonotonicTime` → `HALUtil.getMonotonicTime()`, a native call. *This one
   line is what makes v3 tests hardware-free.* Everything time-related routes through it:
   `Scheduler.run()` timing, every `SchedulerEvent` timestamp, `Coroutine.wait`,
   `Coroutine.waitUntil(cond, timeout)`, `Timer.getTimestamp()`.
2. **`Scheduler.createIndependentScheduler()`** — public static factory whose javadoc explicitly
   blesses this use: *"new scheduler instances can be useful for unit tests."*
3. **`Scheduler.addEventListener(Consumer<? super SchedulerEvent>)`** — public; gives you the event
   stream, which is how you distinguish *finished* from *cancelled*.

### The tick

`m_scheduler.run()`. One call = one scheduler cycle. **There is no `step(dt)`** — time and ticks are
decoupled, and *time alone does not advance a command*. `Coroutine.wait` is literally
`while (!timer.hasElapsed(...)) this.yield();`, so you must set the clock **and** tick **[V]**.

```java
// SchedulerTimingTests.java — verified
AtomicLong time = new AtomicLong(0);
RobotController.setTimeSource(time::get);
...
m_scheduler.schedule(command);
m_scheduler.run();

time.set((long) Milliseconds.of(0.5).in(Microseconds));
m_scheduler.run();
assertFalse(completedWait.get(), "Command should still be waiting for 1 ms to elapse");

time.set((long) Milliseconds.of(1).in(Microseconds));
m_scheduler.run();
assertTrue(completedWait.get());
```

### Assertion surface **[V]**

There is **no "command finished" boolean.** The uniform idiom across the whole suite is:

```java
m_scheduler.schedule(command);
m_scheduler.run();
assertTrue(m_scheduler.isRunning(command));
// ... drive it ...
assertFalse(m_scheduler.isRunning(command));   // == "finished or cancelled"
```

plus a side-effect flag (`AtomicBoolean` / `AtomicInteger`) set inside the command body.

To distinguish *finished* from *cancelled*, use the event stream. `SchedulerEvent` is a **public
sealed interface** with seven public records **[V]** — this is real, team-usable API:

```java
record Scheduled(Command command, long timestampMicros)
record Mounted(Command command, long timestampMicros)
record Yielded(Command command, long timestampMicros)
record Completed(Command command, long timestampMicros)
record CompletedWithError(Command command, Throwable error, long timestampMicros)
record Canceled(Command command, long timestampMicros)
record Interrupted(Command command, Command interrupter, long timestampMicros)
```

`CommandTestBase` wraps it in the suite's only assertion helper **[V]**:

```java
<E extends SchedulerEvent> void assertSchedulerEvent(
    Class<E> eventClass, Predicate<E> tester, String message) {
  if (m_events.stream().filter(eventClass::isInstance).map(eventClass::cast).anyMatch(tester)) {
    return;
  }
  fail(message);
}
```

Other useful facts **[V]**:

- Exceptions from a command body **propagate out of `Scheduler.run()`**:
  `assertThrows(RuntimeException.class, m_scheduler::run)`. v3 injects a synthetic
  `"=== Command Binding Trace ==="` frame into the stack trace to show where the command was bound.
- Misusing a captured coroutine throws
  `IllegalStateException("Coroutines can only be used by the command bound to them")` — surfacing
  out of `run()`.
- `Coroutine.WaitResult` is a public enum (`CONDITION_MET` / `TIMED_OUT`) with `boolean timedOut()`.
- `Scheduler.proto.pack(...)` lets you snapshot whole-scheduler state (command tree, parent ids,
  priorities, requirements) for a structural assertion.
- `CommandState` is package-private and referenced by zero tests. Don't reach for it.

### Testing a command that needs a Mechanism **[V]**

The trick is one override — point the mechanism at your isolated scheduler:

```java
/** A dummy mechanism that allows inline scheduler and name specification, for use in unit tests. */
class DummyMechanism implements Mechanism {
  private final String m_name;
  private final Scheduler m_scheduler;

  DummyMechanism(String name, Scheduler scheduler) { m_name = name; m_scheduler = scheduler; }

  @Override public String getName() { return m_name; }
  @Override public Scheduler getRegisteredScheduler() { return m_scheduler; }
}
```

`getRegisteredScheduler()` and `getName()` are **public default methods** on the `Mechanism`
interface, so a team can write this verbatim. Fake state via anonymous subclass **[V]**
(`SchedulerTest.java`):

```java
var example = new DummyMechanism("Counting", m_scheduler) { int m_x = 0; };
Command countToTen = example.run(coroutine -> {
      example.m_x = 0;
      for (int i = 0; i < 10; i++) { coroutine.yield(); example.m_x++; }
    }).named("Count To Ten");
```

**Design consequence for us:** our mechanisms must take a `Scheduler` (or override
`getRegisteredScheduler()`) if we want to unit-test them in isolation. The official
`ExampleMechanism` does **not**, so it binds to `Scheduler.getDefault()` and is not isolatable
**[V]**. Same for `Trigger`: use `new Trigger(scheduler, condition)`, not `new Trigger(condition)`.
Triggers are polled *inside* `Scheduler.run()`, so no separate poll call is needed — a difference
from v2 test patterns **[V]**.

Mockito is on the v3 test classpath, but there is **no mock or spy of a `Mechanism` anywhere** in
the suite; hand-written dummies are the idiom **[V]**.

### `MockHardwareExtension` — misleading name, don't copy it **[V]**

Despite the name it touches no hardware. It is 27 lines that stub `OpModeFetcher` so no test hits
the DS, auto-registered by SPI (`src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
plus `junit.jupiter.extensions.autodetection.enabled=true`).

**Teams can't reuse it and don't need to.** `OpModeFetcher` and its `setFetcher` are
package-private, and none of `commandsv3`'s test classes are published (only `jar`, `sourcesJar`,
`javadocJar` from `sourceSets.main`) **[V]**. `OpModeFetcher` is only consulted when a
`BindingScope` is constructed — i.e. only if you test opmode-scoped bindings or defaults. Plain
command/scheduler tests never touch it.

> Note: `wpilibj/src/test/java/org/wpilib/MockHardwareExtension.java` is a **completely different
> class with the same name** that really does `HAL.initialize()` + `DriverStationSim`. Don't confuse
> them. **[V]**

### Build config — the sharp edge **[V] / [I]**

```groovy
test {
    jvmArgs += ['--add-opens', 'java.base/jdk.internal.vm=ALL-UNNAMED',
                '--add-opens', 'java.base/java.lang=ALL-UNNAMED']
}
```

Without these, loading `Continuation` fails in its static initializer with
`ExceptionInInitializerError` **[V]**. Note `wpilibjExamples/build.gradle` applies the flags via
`tasks.withType(JavaExec)` — which does **not** cover Gradle `Test` tasks, since `Test` is not a
`JavaExec`. **[I]** So we will very likely have to add the `test { jvmArgs ... }` block explicitly
in our GradleRIO project; copying the examples' build config alone won't be enough.

### ⚠ There is no shipped v3 test example **[V]**

`wpilibjExamples/src/test/` contains five test classes, none v3-related. The only reference
implementation of v3 testing is the library's own (unpublished) suite. **We will be writing the
first-party pattern ourselves**, hand-copying `CommandTestBase.java`.

---

## 7. `mechanisms/` vs `subsystems/` — is the rename deliberate?

**Yes, deliberate and consistently applied — but the *rationale* is written down nowhere.** **[V]**

Evidence it's deliberate:

- The library type is `org.wpilib.command3.Mechanism`. There is **no `Subsystem` and no
  `SubsystemBase` in `commandsv3` at all.** It's a type rename at the API level, not just a folder
  convention.
- Every forward-looking sentence in `design-docs/commands-v3.md` says "mechanism". "Subsystem"
  appears 5 times, and every one is **past-tense narration of v2 behaviour** ("The v2 framework
  worked around this problem with so-called proxy commands… removed the requirement for their used
  *subsystems*…").
- Applied across templates (`templates/commandv3/mechanisms/`), examples
  (`hatchbotcmdv3/mechanisms/DriveMechanism.java` vs v2's `hatchbottraditional/subsystems/DriveSubsystem.java`),
  and `rebuiltcmdv3/mechanisms/` which drops the suffix entirely (`Intake`, `Shooter`, `SwerveDrive`).

Evidence it was a mechanical find-and-replace over v2-derived text — **two stale lines survive in
`Mechanism.java` itself** **[V]**:

> Returns the scheduler under which this **subsystem** and its default commands are registered. The
> scheduler is also used to fetch running commands for the **subsystem**.

The only commentary on the name anywhere justifies the word's *scope*, not the rename
(`Mechanism.java` class javadoc) **[V]**:

> Even though this interface is named "Mechanism", it may be used to represent other physical
> hardware on a robot that should be controlled with commands - for example, an LED strip or a
> vision processor that can switch between different pipelines could be represented as mechanisms.

**[I] Best available reading of *why*:** in v2, a `Subsystem` was a top-level, singular,
scheduler-registered resource — you registered it, and it had a `periodic()`. In v3 a `Mechanism` is
just "a thing a command can hold exclusive ownership of": it's an interface with no registration, no
`periodic()`, and it **nests**. `rebuiltcmdv3.Intake` is a Mechanism that privately owns two other
Mechanisms. "Subsystem" carried an architectural claim ("basic unit of robot organization" — v2's
own javadoc) that the new type deliberately does not make. The word "mechanism" is smaller and more
literal, which fits a resource-ownership token better.

**Teaching note:** this is a *good* rename to lean into. It removes the v2 confusion of "is my
elevator's follower motor a subsystem?" — a Mechanism is simply the granularity at which you need
mutual exclusion.

---

## 8. State machines

Full API and rationale in `design-docs/commands-v3-state-machines.md` + `StateMachine.java` (594
lines — the entire feature is one file). **[V]**

### Motivation (verbatim)

> Coroutines are a powerful way to express low- to high-complexity behaviors. However, they become
> unwieldy at representing highly complex behaviors where phases may be repeated or skipped to at
> any point in the sequence. State machines excel at this by providing ways to transition from any
> arbitrary state to any other arbitrary state, flattening the declarative structure of a coroutine
> into a linear sequence of states and transitions.

The motivating case is an auto where *"If the robot is moved away from the scoring location, the
scoring portion of the sequence should stop and the robot should move back into position, and then
resume the scoring sequence."* — interrupt-and-resume-from-anywhere.

### API **[V]**

**It is not generic over an enum.** States are opaque `StateMachine.State` identities minted by
`addState(Command)`. No `StateMachine<E extends Enum<E>>`, no enum- or string-keyed lookup.

```java
public final class StateMachine implements Command {
  public StateMachine(String name);
  @Override public String name();
  @Override public Set<Mechanism> requirements();          // ALWAYS Set.of()
  @NoDiscard public State addState(Command command);
  public TransitionNeedsTargetStage switchFromAny(State... states);  // no-args = all states so far
  @PostConstructionInitializer public void setInitialState(State initialState);
  @Override public void run(Coroutine coroutine);
}

public static final class State {
  public void onEnter(Runnable callback);
  public void onExit(Runnable callback);
  public TransitionNeedsConditionStage switchTo(State to);
  public TransitionNeedsConditionStage switchTo(Supplier<State> dynamic);
  public TransitionNeedsConditionStage exitStateMachine();
}

// @NoDiscard("Use .when() or .whenComplete() to specify the transition condition")
public static final class TransitionNeedsConditionStage {
  public void when(BooleanSupplier condition);            // rising-edge, polled while state runs
  public void whenComplete();                             // default next state
  public void whenCompleteAnd(BooleanSupplier condition); // takes precedence over whenComplete()
}
```

`setInitialState` is enforced **at compile time** by the same javac plugin, via
`@PostConstructionInitializer` — its javadoc: *"creating a state machine and neglecting to call this
method will result in a compilation error"* **[V]**.

### Integration **[V]**

- **A `StateMachine` *is* a `Command`.** Schedule it, compose it, cancel it, return it from an auto
  factory.
- `requirements()` returns `Set.of()` **unconditionally**. State commands are started with
  `coroutine.fork(...)` as children, so their requirements are inherited only while active.
- **⚠ Silent-death hazard:** source comment — *"The state machine will exit if the child command
  fails to be forked. This happens if the command shares requirements with a higher-priority
  command."* Priority contention terminates the whole machine.
- `onEnter`/`onExit` callbacks that schedule commands scope those commands to the **entire machine's**
  lifetime, not the state's.
- One `yield()` per state per iteration; a transition skips the yield so the next state's command
  starts in the *same* scheduler run.

### Worked example **[V]** (`StateMachineTest.java`)

```java
StateMachine stateMachine = new StateMachine("State Machine");

var idleState    = stateMachine.addState(leds.idleAnimation());
var infoState    = stateMachine.addState(leds.infoAnimation());
var warningState = stateMachine.addState(leds.warningAnimation());

stateMachine.setInitialState(idleState);

idleState.switchTo(infoState).when(normalPriorityEvent.and(highPriorityEvent.negate()));
idleState.switchTo(warningState).when(highPriorityEvent);

warningState.switchTo(infoState).whenCompleteAnd(normalPriorityEvent);
infoState.switchTo(warningState).whenCompleteAnd(highPriorityEvent);

stateMachine.switchFromAny().to(warningState).when(highPriorityEvent);
stateMachine.switchFromAny().to(idleState).whenComplete();
```

`Trigger` drops straight into `when(...)` because `Trigger implements BooleanSupplier`.

### When to use it

**There is no "when to use which" section in the design doc.** It shows the same auto written both
ways and leaves the comparison to the reader — and the coroutine version is notably *shorter*
**[V]**:

```java
Command autoWithCoroutines() {
  return Command.noRequirements(coroutine -> {
    atScoringLocation.whileTrue(
        turret.aimAtGoal()
            .andThen(shooter.fireOnce().repeatWhile(hopper::hasBall))
            .andThen(leds.celebrate())
            .withAutomaticName());
    atScoringLocation.onFalse(drivetrain.driveToScoringLocation());
    coroutine.await(drivetrain.driveToScoringLocation());
    coroutine.park();
  }).named("Auto With Coroutines");
}
```

Hard constraints that *do* appear **[V]**:

> One-shot commands should use completion transitions to continue the flow; conditional transitions
> cannot trigger for them because the commands exit before conditional transitions can be checked.

> If multiple transitions are configured with the same condition on the same state, only the first
> will ever trigger in a given loop iteration.

**[I] Our operating test:** reach for `StateMachine` only when behaviour has *arbitrary re-entry* —
phases that can be skipped to, repeated, or interrupted-and-resumed from any point. Anything linear
or simply-nested stays cheaper as `andThen`/`alongWith`/`race`, and the doc's own counter-example
shows that inner triggers + `park()` covers a lot of the "interruptible sequence" space.

**Adoption signal [V]:** `grep -rn "StateMachine" wpilibjExamples/src` → **no matches**. Zero usage
outside the library's own tests. `commandsv3/src/dev/DevMain.java` just prints platform info. Every
shipped v3 example uses plain coroutine composition. **[I]** This reads as a specialist escape
hatch, not a recommended default.

### Doc/source discrepancies found **[V]**

1. Design doc says the entry point is `org.wpilib.commands3.StateMachine`; the real package is
   `org.wpilib.command3` (singular). The *Gradle project* is `commandsv3`, likely the source of the
   slip.
2. Doc omits the dynamic-target overloads (`switchTo(Supplier<State>)`), which exist and are tested.
3. Doc omits `whenCompleteAnd` semantics entirely (it appears in an example but is never explained).
4. Doc describes only the runtime exception for a missing `setInitialState`, not the compiler
   enforcement.
5. `when(BooleanSupplier)` javadoc has a typo: *"NOTE: this **had** no effect if the originating
   state is a one-shot command without a yield."*

---

## 9. v2 idioms with no v3 equivalent

**v2 and v3 are mutually exclusive vendordeps** **[V]** (`commandsv3/CommandsV3.json`):

```json
"conflictsWith": [{
  "errorMessage": "Users can not have both Commands v2 and Commands v3 vendordeps in their robot program.",
  "offlineFileName": "CommandsV2.json"
}]
```

v3 is **Java-only** — `"cppDependencies": []`. Version string: `"wpilibYear": "2027_alpha7"`.

**There is no migration guide.** Searched `design-docs/`, `docs/` (Doxygen config only),
`commandsv3/` (no README at all). The closest things are `CONTRIBUTING.md`'s removal rationale, the
comparative sections of `commands-v3.md`, and the side-by-side v2 `hatchbottraditional` vs v3
`hatchbotcmdv3` examples. **[V]**

### Removed outright from *both* v2 and v3 in 2027 **[V]**

`RamseteCommand`, `HolonomicDriveController`, `SwerveControllerCommand`, `MecanumControllerCommand`,
`PIDCommand`/`PIDSubsystem`, `ProfiledPIDCommand`/`ProfiledPIDSubsystem`,
`TrapezoidProfileCommand`/`TrapezoidProfileSubsystem` — zero hits repo-wide.

`CONTRIBUTING.md` states the philosophy directly:

> Avoid opaque black-boxes of functionality. Classes like RamseteCommand or HolonomicDriveController
> (both removed in 2027) are good examples of this. While they look like a good abstraction that
> helps beginners, the black-box nature means they are difficult to debug and it's impossible to
> instrument the internals […] SwerveControllerCommand construction was a huge pile of opaque
> arguments glued together. **Composition is strongly preferred**, with strong documentation and
> examples describing how to do that composition.

The same file also states the rule that explains v3's whole builder design:

> **Error at compile time, not runtime.** […] Use language features to make invalid code impossible
> to build.

### v2 → v3 mapping **[V]** unless noted

| v2 | v3 |
| --- | --- |
| `Subsystem` / `SubsystemBase` | `Mechanism` (interface, no base class, no `periodic()`) |
| `Subsystem.periodic()` | `Scheduler.addPeriodic(Runnable)` / `sideload(Consumer<Coroutine>)`, or a default command |
| `CommandScheduler.getInstance()` | `Scheduler.getDefault()` (+ `createIndependentScheduler()`) |
| `Command.schedule()` | gone in both. `Scheduler.getDefault().schedule(c)`, or `coroutine.fork/await` inside a command |
| `InstantCommand` | `Command.noRequirements(_ -> action).named(…)` / `mech.run(co -> action).named(…)` |
| `RunCommand` | `Mechanism.runRepeatedly(Runnable)` |
| `StartEndCommand`, `FunctionalCommand` | the coroutine body *is* init/execute/end; cleanup → `whenCanceled(Runnable)` |
| `WaitCommand` / `WaitUntilCommand` | `Command.waitFor(Time)` / `Command.waitUntil(cond)`; in-body `coroutine.wait/waitUntil/park` |
| `RepeatCommand` / `.repeatedly()` | `while (true) { …; coroutine.yield(); }` |
| `ConditionalCommand` / `Commands.either` | a plain `if` inside the coroutine |
| `SelectCommand` | plain `switch`/`Map` in the body, or `StateMachine` |
| `ProxyCommand` / `.asProxy()` | obsolete — all nested commands are implicitly proxied |
| `DeferredCommand` / `Commands.defer` | unnecessary — bodies construct children at run time **[I]** |
| `ScheduleCommand` | **no analog, stated explicitly** in `Command.java` javadoc |
| `PrintCommand` | `System.out.println` in a body |
| `NotifierCommand` | `Scheduler.addPeriodic` / `PeriodicOpMode.addPeriodic` **[I]** |
| `WrapperCommand` | staged builders instead of wrapper objects |
| `SendableChooser` | `org.wpilib.tunable.Selectable<T>` + `Tunables.publish(name, sel)` |
| `.withInterruptBehavior()` | integer priorities + `withPriority(int)` |
| `.handleInterrupt()` / `.finallyDo()` | `whenCanceled(Runnable)` / `Command.onCancel()` |
| `.withName()` | `.named()` — now mandatory |
| `SysIdRoutine` | **v2 only; no v3 counterpart exists.** Unaddressed anywhere. |

**Decorators gone with no equivalent [V]:** `.repeatedly()`, `.unless()`, `.onlyIf()`,
`.onlyWhile()`, `.beforeStarting()`, `.withDeadline()`, `.deadlineFor()`, `.asProxy()`,
`.ignoringDisable()`.

Note the semantic change: `.andThen()`, `.alongWith()`, `.raceWith()`, `.until()` all return
**builders**, so every chain must terminate in `.named(String)` or `.withAutomaticName()`. Only
`.withTimeout(Time)` returns a `Command` directly. **[V]**

---

## 10. Key takeaways for this project

1. **`--add-opens` is a hard build requirement, in *three* places.** Deploy JVM args, `run`, and —
   easy to miss — the Gradle `test` task. `tasks.withType(JavaExec)` does **not** cover `Test`
   tasks. Get this into `build.gradle` before writing the first command. **[V] / [I]**

2. **Prefer `Command.noRequirements(...)` + `coroutine.await(...)` over `.andThen()` chains for
   autos.** The built-in sequence/parallel groups own every mechanism for the whole composition and
   leave them uncommanded in the gaps. The coroutine form releases each mechanism as soon as its
   child finishes, so default commands resume. `rebuiltcmdv3` uses this form throughout; the
   `hatchbotcmdv3` template calls out the choice in a comment. **[V]**

3. **Override `Mechanism.idle()` to be actively safe, and set default commands explicitly.** The
   built-in `idle()` is `run(Coroutine::park)` — it holds the mechanism and does *nothing*, so a
   gravity-loaded arm sags. Upstream's swerve overrides `idle()` to command all four modules to
   stop. Do the same for our elevator/arm/anything with a load. **[V]**

4. **Design mechanisms for test isolation from day one.** Give every mechanism a constructor path
   that overrides `getRegisteredScheduler()`, and construct triggers with
   `new Trigger(scheduler, condition)`. The official `ExampleMechanism` does neither and therefore
   cannot be unit-tested in isolation. This is a decision to make *now*, not after we have 8
   mechanisms. **[V] / [I]**

5. **Teach the `while`-loop-only limitation of the yield check explicitly.** The compiler catches a
   missing `yield()` in a `while` loop as a hard, unsuppressible error — genuinely excellent for
   students. But `for (;;)` and `do/while` are **not** checked and will hang the robot silently.
   That asymmetry is exactly the kind of thing a student will hit. **[V]**

6. **Naming is enforced by the type system, so telemetry is good by default.** Every command has a
   name, groups auto-name as `"A -> B"` / `"(A & B)"`, and the scheduler publishes a full command
   *tree* (id, parent_id, name, priority, requirements, last/total time) over protobuf. This pairs
   well with the explicit-logging decision — `Scheduler` is Epilogue-loggable via `@Logged`, and
   `Telemetry.log("Drivetrain", robotDrive)` / `TelemetryLoggable.logTo(TelemetryTable)` is the
   manual path shown in `hatchbotcmdv3`. **[V]**

7. **Budget teaching time for three genuinely hard concepts:** (a) `await()` yields internally, so
   don't double-yield; (b) implicit proxying — a child command's requirements are only held while it
   runs; (c) scope-bound bindings — a trigger bound inside a command silently disappears when that
   command ends. None of these have v2 analogues. **[I]**

8. **`StateMachine` is an escape hatch, not a default.** Zero usage in any shipped example. Don't
   introduce it in week one. **[I]**

9. **Under TimedRobot, opmode scoping never engages — cancel the auto command explicitly.**
   `BindingScope` falls back to Global scope when `getOpModeId() == 0`, and `TimedRobot` never
   registers an opmode. See §11; this contradicts a comment in the official TimedRobot v3 example.
   **[V] code path / [I] runtime consequence — verify on hardware.**

10. **v2 and v3 cannot coexist** as vendordeps — no incremental migration path, and no migration
   guide exists. **[V]**

11. **`SysIdRoutine` has no v3 port.** If we plan to characterize the drivetrain, that's an
    unplanned decision (see §11). **[V]**

---

## 11. Things that touch a locked decision

### 🔴 TimedRobot silently disables opmode scoping — verify before relying on it

This is the most consequential finding for our locked stack, and it contradicts a comment in the
official TimedRobot v3 example.

`BindingScope.createNarrowestScope` is the whole scoping mechanism **[V]**
(`commandsv3/.../BindingScope.java`):

```java
static BindingScope createNarrowestScope(Scheduler scheduler) {
  Command currentCommand = scheduler.currentCommand();
  long currentOpMode = OpModeFetcher.getFetcher().getOpModeId();

  if (currentCommand != null) {
    return new ForCommand(scheduler, currentCommand);
  } else if (currentOpMode != 0) {
    return new ForOpmode(currentOpMode);
  } else {
    return Global.INSTANCE;      // always active; never torn down
  }
}
```

So opmode scoping engages **only when `RobotState.getOpModeId() != 0`**. And **[V]**:

- Only `OpModeRobot` ever calls `addOpMode(...)`. `grep -n "opMode\|OpMode"` over
  `wpilibj/.../framework/TimedRobot.java` returns **zero matches** — `TimedRobot` never registers an
  opmode with the driver station.
- `RobotState.getOpModeId()` returns *"the unique ID provided by the addOpMode() function; may
  return 0 or a unique ID not added."*

**[I] Therefore**, in a plain `TimedRobot` program with no opmodes registered, `getOpModeId()` is
expected to be `0`, `createNarrowestScope` falls through to `Global.INSTANCE`, and:

- A command scheduled in `autonomousInit()` is **Global-scoped and never auto-cancelled** when auto
  ends. The comment in `hatchbotcmdv3/Robot.java` promising the opposite looks incorrect for that
  very file's own robot base class.
- `setDefaultCommand` calls never revert on a mode change (there's no opmode scope to exit).
- Only the *command* scope (`ForCommand`) still works — inner triggers bound inside a running
  command are still cleaned up correctly, since that path doesn't consult `OpModeFetcher`.

**Action items:**
1. **Do not rely on auto-cancellation of the autonomous command.** Store it and cancel it explicitly
   in `teleopInit()`, exactly as v2 required. Cheap insurance either way.
2. **Verify on the bench**, then on a real DS: log `RobotState.getOpModeId()` in `robotPeriodic()`
   across disabled → auto → teleop transitions. If it's always 0, the above holds. The javadoc's
   hedge means a real field DS *might* report non-zero even with nothing registered.
3. If we later want real opmode scoping, that means adopting `OpModeRobot` — a change to a locked
   decision, and worth a separate ticket rather than a drive-by.

**[I] Interpretation:** the scoping feature is designed around `OpModeRobot`. Choosing `TimedRobot`
means opting out of one of v3's four headline features. That's a defensible trade (TimedRobot is far
easier to teach and matches every tutorial students will find), but it should be a recorded decision,
not an accident.

### ⚠ TimedRobot vs. OpModeRobot — the locked decision holds, but we're off the template path

Our stack is locked to **TimedRobot + Commands v3**. Both official v3 templates
(`commandv3`, `commandv3skeleton`) extend **`OpModeRobot`**, not `TimedRobot` **[V]**:

```java
public class Robot extends OpModeRobot {
  ...
  @Override public void robotPeriodic() { Scheduler.getDefault().run(); }
}
```

`org.wpilib.framework.OpModeRobot` auto-registers classes annotated `@Autonomous` / `@Teleop` /
`@Utility` in the same package or subpackages, constructing them when selected on the driver
station. Bindings and default commands set in an OpMode constructor are scoped to that OpMode.
`rebuiltcmdv3` — the fullest v3 example, with swerve — also uses `OpModeRobot` with an
`opmodes/auto/` + `opmodes/teleop/` layout **[V]**.

**TimedRobot + v3 is fully supported and officially demonstrated** — `hatchbotcmdv3/Robot.java` is a
complete, current `extends TimedRobot` v3 example using `Selectable<Command>` + `Tunables.publish`
for auto selection and scheduling in `autonomousInit()` **[V]**. Command scoping still works: the
auto command is auto-cancelled when the mode ends, because scope identity comes from
`RobotState.getOpModeId()`, not from `OpModeRobot` **[V]**.

**So: nothing invalidates the locked decision.** But it should be a conscious choice rather than a
default, because:
- We will be following `hatchbotcmdv3` rather than the template, so "just use the WPILib template"
  is not available to us.
- `templates/commandv3/` has **no `RobotContainer`** — the `Robot` class *is* the container, holding
  mechanisms as fields. `hatchbotcmdv3` does the same. **[V]** If we were planning a `RobotContainer`
  out of v2 habit, upstream v3 has dropped it in both robot base classes.
- The v3 *idiom* for organizing teleop/auto binding is the OpMode class. Under TimedRobot we get a
  `configureButtonBindings()` method instead — closer to v2, arguably easier to teach, but it means
  all bindings are global-scope and the "inner triggers" feature is less naturally reachable.

**Not a blocker; flagging as a decision to record explicitly.**

### ⚠ Unplanned decision: no `RobotContainer`

Neither v3 template nor either v3 example has one. Mechanisms live as fields on `Robot`. We should
decide deliberately whether to keep a `RobotContainer` (familiar to returning students, diverges
from upstream) or fold it into `Robot` (matches upstream). **[V]**

### ⚠ Unplanned decision: `SysIdRoutine` has no v3 equivalent

It exists only in `commandsv2/sysid/`. Since v2 and v3 can't coexist, drivetrain characterization
needs a plan. **[V]**

### Note on the swerve decision — 2027 wpimath renames **[V]**

`rebuiltcmdv3/mechanisms/SwerveDrive.java` uses `ChassisVelocities` (not `ChassisSpeeds`),
`SwerveModuleVelocity` (not `SwerveModuleState`), and `kinematics.toSwerveModuleVelocities(...)`.
Also `org.wpilib.hardware.imu.OnboardIMU`. Any swerve code or tutorial written against 2025/2026
names will not compile. Worth confirming against our Pigeon2 + SPARK plan.

### Note on telemetry **[V]**

Both paths are demonstrated and neither needs AdvantageKit:
- Epilogue: `@Logged` on `Robot`/mechanisms + `Epilogue.update(this)` in `robotPeriodic()`.
  `Scheduler` is Epilogue-loggable (there's a compile-time test asserting it).
- Manual: `implements TelemetryLoggable` + `logTo(TelemetryTable)`, pushed with
  `Telemetry.log("Name", mechanism)`.
- Scheduler state ships over protobuf automatically as a command *tree*.

---

## 12. Open questions / unknowns

1. **Does GradleRIO's 2027 project template add the `--add-opens` flags to the `test` task?**
   Unknown — `allwpilib` is not GradleRIO. Must be checked against an actual generated 2027 project.
   **[I]** that we'll need to add them ourselves.
2. **What is the actual `Scheduler.run()` cost per loop with ~6 mechanisms and nested commands?**
   No benchmark exists in-tree. Continuation mount/unmount is cheap in principle but unmeasured for
   FRC loop budgets. The scheduler exposes `lastRuntimeMs()` — we should log it from day one.
3. ~~How does a suspended command get resumed?~~ **Resolved: Suspend/Resume is not implemented in
   alpha-7.** The design doc has a whole "Suspend/Resume" goal section, but `grep -i "suspend"` over
   `Scheduler.java` returns **0 hits**, and there is no `suspend()`/`resume()` anywhere in the v3
   public API. The only matches in the package are incidental prose in `Continuation.java`. **[V]**
   Do not design around this feature; it is a stated goal, not a shipped capability.
4. **Does the `@NoDiscard` / javac plugin actually run in a GradleRIO robot project?** It's
   `autoStart()`, so it should apply wherever the plugin jar is on the annotation processor path —
   but that depends on GradleRIO wiring `javac-plugin` in. If it doesn't, we lose *all* the
   compile-time coroutine safety, which is most of the pedagogical value. **High priority to verify.**
5. **Is there a REV SPARK / CTRE Pigeon2 vendordep built against 2027 alpha-7 `org.wpilib`?**
   Out of scope here, but v3 is irrelevant if the vendordeps aren't ported.
6. **Simulation:** how do v3 mechanisms integrate with physics sim, given no `periodic()`? Presumably
   `Scheduler.addPeriodic(...)` for `simulationPeriodic`-style updates, but no v3 example
   demonstrates sim. **Unverified.**
7. **`StateMachine` maturity.** No `@Beta`/`@Experimental` markers, but zero adoption in examples and
   several doc/source discrepancies (§8). Treat the API as liable to move between alphas. **[I]**
8. **Does the DS ever report a non-zero opmode id to a `TimedRobot` that registered none?**
   `RobotState.getOpModeId()`'s javadoc hedges: *"may return 0 or a unique ID not added, so callers
   should be prepared to handle that case."* If a real field DS reports non-zero, scoping behaviour
   under TimedRobot could differ from the bench. **Must be verified on real hardware** — see §11.
