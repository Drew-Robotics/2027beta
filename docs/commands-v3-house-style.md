# Commands v3, and how we write it

This is how team 8852 writes robot code for the 2027 season. It teaches
Commands v3 as the only command framework there is, because on this robot
it is. Read it once before you write your first command, and come back to
it when something does not behave.

The worked example throughout is **our own drive base** — the code in
`src/main/java/first/robot/mechanisms/Drive.java` and the opmodes that
use it. There is no toy mechanism here, because a toy mechanism goes
stale the day the real one changes.

The decisions this document teaches, with the source that verifies each
one, are in [ADR 0006](adr/0006-commands-v3-house-style.md). If you want
to know *why*, read that. If you want to know *how*, you are in the right
file.

---

## 1. A command is a function that pauses

A command is not a class. It is a lambda that receives a `Coroutine` and
runs until it decides to hand control back:

```java
coroutine -> {
  while (true) {
    setModuleVelocities(target);
    coroutine.yield();
  }
}
```

`coroutine.yield()` is the whole idea. It suspends the function exactly
where it is, lets the scheduler run everything else for that loop, and
then resumes on the next line the next time around. Local variables
survive. The `while (true)` above runs once every 5 ms and never blocks
anything.

That is why there is no `initialize()` / `execute()` / `end()` split.
Everything before the first `yield()` is initialisation. Everything in
the loop is execution. Everything after the loop is the end.

**A command must yield.** A loop that does not is a robot that stops
responding, so the compiler enforces it — with one gap you have to know
about, in §3.

## 2. Commands are methods on the mechanism

Every command is a public method that returns `Command`. It lives on the
mechanism it controls.

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

Three parts, always in this order:

- **`run(...)`** — a `Mechanism` method. It means *this command needs
  exclusive use of this mechanism*. While it runs, no other command may
  touch the drive.
- **the lambda** — the body, from §1.
- **`.named(...)`** — the name. It is not optional; `named` is the only
  method that turns the builder into a `Command`, so the code does not
  compile without one.

There are **no command classes and no `commands/` package.** There is
nothing to subclass — the framework has no `Command` base class. If you
find yourself wanting one, you want a method.

### A routine that spans two mechanisms

Some routines are not owned by any one mechanism — an autonomous that
drives, then shoots. Those start as a **private method on the opmode that
uses them**:

```java
private Command sweepAndScore(Robot robot) {
  return Command.noRequirements(coroutine -> {
        coroutine.await(robot.drive.followPath("nz-sweep-left-trench"));
        coroutine.await(robot.shooter.shootAtHub(range));
      })
      .named("Auto.SweepAndScore");
}
```

When a *second* opmode needs the same routine, it moves to a method on
`Robot`. Not before. We do not have a `routines/` package waiting for
routines.

## 3. Loops in a coroutine body are always `while`

This is a hard rule, and it is the one rule in this document that will
cost you an afternoon if you break it.

The compiler ships a check that fails the build if a `while` loop inside
a coroutine body has no `yield()` in it. It is a real compile error and
you cannot suppress it. It is also the only loop it checks — there is no
check for `for`, for enhanced `for`, or for `do/while`.

So this compiles cleanly and hangs the robot the moment it is scheduled:

```java
for (;;) {              // never write this
  setVelocities(target);
}
```

and this does not compile at all:

```java
while (true) {          // compile error: no yield
  setVelocities(target);
}
```

Write `while`. Then the compiler is checking your work.

## 4. Composing: `noRequirements` and `await`

`coroutine.await(command)` schedules a command, waits for it to finish,
and then continues. That is how routines are built:

```java
Command.noRequirements(coroutine -> {
      coroutine.await(drive.followPath("nz-sweep-left-trench"));
      coroutine.await(drive.aimAt(hub));
      coroutine.await(shooter.shootAtHub(range));
    })
    .named("Auto.SweepAndScore");
```

**`noRequirements` is the default, not `requiring`.** A composition built
with `Command.requiring(drive, shooter)` — or with the built-in sequence
and parallel groups — holds *every* mechanism it names for the *entire*
composition, including the moments between one child ending and the next
starting. During those gaps the mechanism is owned by a command that is
not writing to it, and its default command cannot resume.

On a drive base that means the modules sit held and unwritten between
path segments. With `noRequirements`, each mechanism is released the
instant its own child command ends.

Use `requiring` only when holding a mechanism through the gaps is the
actual goal — and leave a comment saying which gap and why.

`await` yields internally while it waits. Do not add your own `yield()`
after it.

## 5. What is a mechanism

**A class is a `Mechanism` if and only if commands need exclusive
ownership of it.**

`Mechanism` is a lock, and nothing else. It does not register anything,
it has no `periodic()` method, and it is not a base class — it is an
interface you implement.

On our drive base:

| Class | Mechanism? | Why |
|---|---|---|
| `Drive` | yes | commands take exclusive ownership of the drivetrain |
| `SwerveModule` | no | `Drive` owns all four; nothing commands one alone |
| `PoseEstimator` | no | several commands read the pose at once |

A class can own other mechanisms — an intake owning a wrist and a roller
— but only when each child can be commanded on its own, at the same time
as its sibling. Nothing on a drive base works like that.

If you are about to ask whether your follower motor is a mechanism: it is
not. It has no command of its own.

## 6. Every mechanism takes a `Scheduler`

```java
public class Drive implements Mechanism {
  private final SwerveModule[] modules;
  private final TelemetryTable log;
  private final Scheduler scheduler;

  public Drive(DriveConfig config, TelemetryTable log, Scheduler scheduler) {
    this.log = log;
    this.scheduler = scheduler;
    // ...
  }

  @Override
  public Scheduler getRegisteredScheduler() {
    return scheduler;
  }
}
```

The `Scheduler` is always the **last** constructor parameter, and
`getRegisteredScheduler()` is always overridden to return it. `Robot`
passes `Scheduler.getDefault()`; a test passes its own.

If you skip it, the mechanism silently uses one process-wide scheduler
instead — which means a test cannot give it a clean one, and a test that
cannot do that cannot check what your command actually did. One field and
one override buy every mechanism a test.

**This is where we differ from the WPILib examples.** Upstream's
`ExampleMechanism` does not take a scheduler, and upstream's own test
suite does not use upstream's mechanism shape. Copy from this document,
not from `ExampleMechanism`.

The same rule reaches triggers built inside a mechanism: write
`new Trigger(scheduler, condition)`. The one-argument `new Trigger(...)`
quietly binds to the global scheduler and undoes the whole thing.

## 7. Names

Command names are `Mechanism.Action`, PascalCase, with the parameter in
square brackets when there is one:

```
Drive.Idle
Drive.DriveFieldRelative
Drive.FollowPath[nz-sweep-left-trench]
```

These names are not decoration. Every command name lands in the log as
the command tree, which is what you read after a match to find out what
was running when the robot did the strange thing. A command named
`"drive command 2"` costs someone an evening in six weeks.

`withAutomaticName()` is allowed for an inline group composition, where
the generated `"A -> B"` is better than anything you would invent. It
does not exist anywhere else, so you will not reach for it by accident.

## 8. Idle is a real command, and it must be safe

When nothing else is running on a mechanism, its **default command**
runs. Unless you say otherwise, that is `idle()` — and the built-in
`idle()` holds the mechanism and does *nothing at all*. It is an infinite
loop of `yield()`. On an arm, that means gravity wins while a command
named "idle" is happily running.

So **every mechanism overrides `idle()`** to be actively safe:

```java
@Override
public Command idle() {
  return runRepeatedly(this::stopModules)
      .withPriority(Command.LOWEST_PRIORITY)
      .named("Drive.Idle");
}
```

`withPriority(Command.LOWEST_PRIORITY)` is not decoration. The built-in
`idle()` sets it, and overriding the method throws it away — so without
that line your idle command sits at normal priority, and a stop command
cannot interrupt it. Copy the whole chain, not just the body.

For the drive, safe is **stop the modules — not an X-lock.** A robot a
driver cannot push out of a corner is a worse thing to be stuck with than
a robot that coasts.

`Robot`'s constructor then sets every default command explicitly, even
though `idle()` would already be the default:

```java
public Robot() {
  drive.setDefaultCommand(drive.idle());
  // ... one line per mechanism
}
```

The point is that the safe state of the whole robot is one block you can
read in a review, rather than something you have to know.

## 9. Priorities: leave them alone

Use the default priority. There is exactly one exception, and it is the
`LOWEST_PRIORITY` on an idle or stop command from §8 — the priority that
lets anything at all interrupt it. That one you write by hand, because
overriding `idle()` drops the built-in's.

If you write `withPriority(...)`, put a comment next to it naming the
command it is meant to beat. If you cannot name one, you do not want a
priority — you want to look again at why two commands are fighting over
the same mechanism.

Priorities are not a tuning knob. Losing a priority contest is the
failure in §12 that takes a whole routine down without an exception.

## 10. Opmodes hold bindings, and nothing else

An opmode is a class the Driver Station constructs when the operator
selects it, and closes when they select something else. Its constructor
contains only:

- trigger bindings,
- default-command overrides for this mode,
- the enabled-trigger, for an autonomous.

```java
@Autonomous(group = "Competition")
public class SweepAuto implements OpMode {
  private final Trigger enabled = new Trigger(RobotState::isEnabled);

  public SweepAuto(Robot robot) {
    enabled.onTrue(sweepAndScore(robot));
  }

  private Command sweepAndScore(Robot robot) { /* ... */ }
}
```

**Never construct hardware in an opmode.** Hardware lives on `Robot` and
is built once.

**Never load a trajectory in an opmode.** The operator scrolling past
your auto on the Driver Station constructs and closes it every time;
loading a trajectory there reloads it every time. Trajectories are
cached on `Robot`.

Opmodes are named `<What>Teleop`, `<What>Auto` or `<What>Check`, and they
are grouped by their annotation's `group()`, not by what package they are
in. A `DoNothingAuto` always exists, so that "safe" is a thing the
operator can select.

**`@Utility` opmodes follow every rule above, with no exemptions.** They
are the ones written in a hurry the night before an event, which is
exactly why. One extra hard rule: a `@Utility` opmode never runs
unattended, and must not need a field, a driver, or a game piece. If it
needs any of those, it is a teleop.

"Never runs unattended" means somebody is watching it who can disable
it. It used to read "safe on blocks", which was a proxy for the same
thing until characterisation needed the robot on the ground — a
feedforward gain measured off the ground is a measurement of a motor,
not of a drive base. Characterisation is the one named case that runs
supervised on the ground; anything else wanting that has to be argued.

## 11. Triggers and bindings

Controllers, and any robot-state trigger more than one opmode cares
about, are `public final` fields on `Robot`. A trigger only one opmode
cares about is built in that opmode.

That is safe even though the `Robot` field outlives the opmode: a
trigger's *binding* — the `onTrue(...)` call — records the scope it was
made in, so a binding made inside an opmode constructor is torn down when
that opmode exits, even though the trigger itself lives on forever.

The one-argument `new Trigger(...)` is right here and wrong in a
mechanism (§6). An opmode is only ever constructed by the robot, against
the robot's scheduler; a mechanism is also constructed by tests, against
theirs.

Use **`CommandGamepad`**, never `CommandXboxController`. It names buttons
by position (`faceUp()`, `rightBumper()`) rather than by the letters
printed on one brand of controller, so the code reads the same after
someone brings a DualSense to a competition. Ports go in `Constants`.

`onTrue` already detects the edge for you. `risingEdge()` and
`fallingEdge()` exist for the other case — when you need an edge as a
*value*, from a sensor or a pose predicate. An edge trigger is true for
exactly one scheduler cycle, so bind it with `onTrue`; `whileTrue` on an
edge cancels the command one cycle later.

## 12. Three ways this bites

**Cleanup in `finally` never runs.** Cancelling a command does not throw
anything through it. The scheduler simply stops resuming the function and
lets it be collected — the body never reaches its own next line, let
alone a `finally`. Put cleanup in `whenCanceled(...)`:

```java
return run(coroutine -> { /* ... */ })
    .whenCanceled(this::stopModules)
    .named("Drive.FollowPath[" + pathName + "]");
```

This one fails silently. The robot keeps driving.

**A lost priority contest cancels the routine that forked it.** When
`await` or `fork` cannot schedule a child — because something with a
higher priority already owns a mechanism it needs — the default behaviour
is for the *parent* to cancel itself. Your code does not get to see the
failure. Inside a `StateMachine` it is worse: the whole machine exits.

**`for(;;)` hangs the robot and compiles clean.** §3.

## 13. `StateMachine` is an escape hatch

`StateMachine` exists. Nothing in WPILib's own examples uses it, and the
coroutine version of the same autonomous routine is shorter.

Reach for it only when behaviour has **arbitrary re-entry** — phases that
can be skipped to, repeated, or interrupted and resumed from any point.
Anything linear, and anything nested, stays coroutine composition.

Nothing on the drive base qualifies.

## 14. What we do not use

- **`addPeriodic` and `sideload`.** They run a function every loop
  outside the command timeline, which means a timing bug caused by one is
  invisible in the log. Our two recurring non-command jobs — odometry and
  the sim tick — are explicit calls in `Robot`'s own periodic hooks,
  where you can read them top to bottom.
- **`Command.requiring(...)` as a default.** §4.
- **Command classes.** §2.

## 15. If you are stuck

- The command does not run → something with a higher priority owns the
  mechanism, or the binding was made in an opmode that is no longer
  selected.
- The robot stopped responding → a loop in a coroutine body with no
  `yield()`. Look for `for` first.
- The mechanism kept moving after a cancel → cleanup was in a `finally`.
- The command name in the log is wrong → it is the string you passed to
  `.named(...)`, and nothing else generates it.

The full API surface, with everything this document leaves out, is in
[`docs/research/commands-v3.md`](research/commands-v3.md).
