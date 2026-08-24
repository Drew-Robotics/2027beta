# Research: The 2027 OpMode framework — what it offers, and whether we want it

Resolves [#7](https://github.com/Drew-Robotics/2027beta/issues/7).

**Date:** 2026-08-24
**Primary source:** local built checkout of WPILib at `~/dev/allwpilib`, version `v2027.0.0-alpha-6-366-gcafb0cc79` (alpha-7).
`docs.wpilib.org` was deliberately *not* used — its OpMode pages are still marked "in development".

Every claim below is tagged **[verified]** (read directly from source in the checkout) or **[inferred]**
(reasoning on top of verified facts). Paths are relative to `~/dev/allwpilib` unless absolute.

---

## TL;DR

**Recommendation: switch from `TimedRobot` to `OpModeRobot`.** Keep Commands v3, keep everything else.

The provisional choice was made on the premise that "OpMode is new and undocumented, Commands v3 is the
safe path". That premise does not survive contact with the source tree. In alpha-7, **Commands v3 and
OpMode are the same decision.** The official Commands v3 template's `Robot` extends `OpModeRobot`, not
`TimedRobot`; `ExampleTeleop.java` and `ExampleAuto.java` are literal `@Teleop`/`@Autonomous` OpMode
classes; and `RobotContainer` has been deleted from the v3 template because OpModes replace it. There is
**no** `TimedRobot` + Commands v3 template or example anywhere in the tree.

`TimedRobot` + Commands v3 is not the conservative option. It is an unsupported combination that no
upstream template, example, or test exercises, and it silently disables a core Commands v3 feature
(opmode-scoped binding lifetimes) that the scheduler is built around.

---

## 1. What problem does OpMode solve? (design rationale)

Source: `design-docs/opmodes.md` (509 lines). **[verified]**

### The stated motivation

> Operator selection of different code implementing unique top-level robot behavior–without
> recompilation of the robot program–is a very common need across most FTC and FRC teams, so it's
> desirable to have a standardized approach for cleanly structuring robot code to support this, along
> with integrated support for selection at the Driver Station.

The three named use cases:

> - Multiple autonomous routines (e.g. following different paths, performing different actions)
> - Different teleoperated behavior (e.g. tank vs arcade drive, different button mappings, operating
>   restrictions for robot demonstrations/guest drivers)
> - Testing (e.g. testing of the whole robot or a single subsystem, sensor, or motor)

So: OpMode is **`SendableChooser`, promoted to a first-class language-level construct, extended from
autonomous-only to teleop and test, and moved from the dashboard into the Driver Station.**

The doc is explicit that this replaces `SendableChooser` as the standard mechanism:

> `SendableChooser` is no longer required for selecting autonomous routines; instead this functionality
> is integrated into opmodes, as users can create/register multiple autonomous opmodes classes, and it's
> been extended to support multiple teleoperated and multiple utility opmodes

### The unification angle

`design-docs/opmodes.md` is a **joint FRC/FTC design document**. It analyses both programs' 2025
behaviour in detail and picks a middle path: FTC's annotated-opmode-class model, but with FRC's
safety-critical enable/disable, FRC's FMS-driven auto→teleop transition, and *without* FTC's XML
hardware map.

> There is no hardware map. Hardware objects are instead directly instantiated inside a top-level Robot
> class. This Robot class is provided as a parameter to the opmode constructor.

This is important context for our purposes: OpMode is not a whim, it is the WPILib/FTC convergence
vehicle, and it has HAL, DS-protocol, and simulation-GUI changes landed behind it. It is not going away.

### What it explicitly rejects

> Historically, WPILib offered a "simple" (later renamed to "sample") robot base class that had single
> overrideable functions for teleop, auto, and utility, with no outer periodic loop (the user was
> responsible for writing the loop). This was deprecated and removed circa 2016 as it was common to see
> teams writing autonomous loops or sleeps without proper exit condition checking [...] resulting in the
> robot code never exiting autonomous

Correspondingly:

> Opmodes are all periodic. While it may be possible to create linear-like behavior, there is no
> equivalent of the FTC SDK's `LinearOpMode` class.

Good. The FTC footgun that this design most obviously could have imported was deliberately left out.

---

## 2. How the lifecycle differs from `TimedRobot`

### `TimedRobot` (2025-style, still present in 2027)

`wpilibj/src/main/java/org/wpilib/framework/TimedRobot.java` (143 lines) extends
`IterativeRobotBase` (350 lines) extends `RobotBase`. **[verified]**

One `Robot` class owns everything. Nine overridable hooks
(`robotInit`/`robotPeriodic`, `{disabled,autonomous,teleop,test}{Init,Periodic,Exit}`). Selecting
between multiple autos is user-space work: a `SendableChooser` field, populated in init, read in
`autonomousInit`.

### `OpModeRobot`

`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java` (805 lines). **[verified]**

```
RobotBase
├── IterativeRobotBase
│   └── TimedRobot
│       └── TimesliceRobot
└── OpModeRobot          ← sibling of IterativeRobotBase, NOT a TimedRobot subclass
```

`OpModeRobot extends RobotBase` directly and reimplements its own loop. It has **no**
`autonomousInit`/`teleopPeriodic`. Its hooks are:

```java
public void driverStationConnected() {}   // once, when DS first connects
public void robotPeriodic() {}            // every loop, always
public void simulationInit() {}
public void simulationPeriodic() {}
public void disabledInit() {}
public void disabledPeriodic() {}
public void disabledExit() {}
public void nonePeriodic() {}             // periodically when NO opmode is selected
```

Per-mode behaviour moves out of `Robot` into separate annotated classes:

```java
public interface OpMode extends AutoCloseable {
  default void disabledPeriodic() {}   // while selected + disabled
  default void start() {}              // once, on disabled → enabled
  default void periodic() {}           // while enabled, at OpModeRobot#getPeriod()
  default void end() {}                // on disable or opmode switch
  @Override default void close() {}    // object is NOT reused
  default Set<PeriodicPriorityQueue.Callback> getCallbacks() { return Set.of(); }
}
```
(`wpilibj/src/main/java/org/wpilib/opmode/OpMode.java`, 89 lines — every method has a default no-op, so
`class X implements OpMode {}` is legal and is exactly what the Commands v3 template does.) **[verified]**

Registration is by annotation + classpath scan. From the `OpModeRobot(double period)` constructor:

```java
// Add LoopFunc as periodic callback (match C++)
addPeriodic(this::loopFunc, period);

// Scan for annotated opmode classes within the derived class's package and subpackages
addAnnotatedOpModeClasses(getClass().getPackage());
RobotState.publishOpModes();
```
**[verified]** — auto-registration is automatic; you do not call `publishOpModes()` yourself unless you
add opmodes later.

Annotations (`Autonomous`, `Teleop`, `Utility`, 56 lines each, identical shape): **[verified]**

```java
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Documented
public @interface Autonomous {
  String name() default "";            // defaults to class name
  String group() default "";           // DS drop-down grouping
  String description() default "";
  String textColor() default "";
  String backgroundColor() default "";
}
```

Constructor injection: `OpModeRobot` prefers a 1-arg constructor taking your `Robot` subclass, falling
back to no-arg (`OpModeRobot.java:98-110`). **[verified]**

### The key structural difference

**The unit of code organisation changes from "the mode" to "the routine."**

Under `TimedRobot`, "run auto #3" is a runtime branch inside one long-lived object. Under `OpModeRobot`,
it is a **distinct class with its own object lifetime** — constructed when the operator picks it on the
DS while still disabled, `close()`d and discarded when they pick something else. Shared hardware lives in
`Robot` and is injected.

The practical consequence, and this is the one that matters for us: **`OpModeRobot` gives selectable
routines a real lifecycle with an explicit teardown point.** `TimedRobot` + `SendableChooser` gives you a
branch and no teardown at all.

### Threading model **[verified]**

Not threads, not coroutines. One HAL notifier plus a min-heap of periodic callbacks
(`wpilibj/src/main/java/org/wpilib/internal/PeriodicPriorityQueue.java`, 322 lines). `startCompetition()`
is the whole main loop:

```java
@Override
public final void startCompetition() {
  System.out.println("********** Robot program startup complete **********");
  if (isSimulation()) { simulationInit(); }
  DriverStationBackend.observeUserProgramStarting();
  while (true) {
    if (!m_callbacks.runCallbacks(m_notifier)) { break; }
  }
}
```

The framework's own `loopFunc` is registered as just another callback. OpMode javadoc:

> All lifecycle callbacks and periodic callbacks run synchronously on the same thread that invokes them.
> Interactions between opmodes and the robot framework do not require additional synchronization.

This is the same single-threaded 20 ms mental model students already have from `TimedRobot`. No new
concurrency to teach.

---

## 3. Interaction with Commands v3 — the question the ticket actually asks

**Answer: `ExampleTeleop.java` and `ExampleAuto.java` in the Commands v3 template are OpMode classes.
This is not coincidental naming. The two frameworks are fused.** **[verified]**

### The template, verbatim and complete

`wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/Robot.java` (24 lines):

```java
package org.wpilib.templates.commandv3;

import org.wpilib.command3.Scheduler;
import org.wpilib.command3.button.CommandGamepad;
import org.wpilib.framework.OpModeRobot;
import org.wpilib.templates.commandv3.constants.DriverConstants;
import org.wpilib.templates.commandv3.mechanisms.ExampleMechanism;

public class Robot extends OpModeRobot {
  final ExampleMechanism exampleMechanism = new ExampleMechanism();
  final CommandGamepad exampleController =
      new CommandGamepad(DriverConstants.DRIVER_CONTROLLER_PORT);

  public Robot() {}

  @Override
  public void robotPeriodic() {
    Scheduler.getDefault().run();
  }
}
```

`.../commandv3/ExampleTeleop.java` (15 lines):

```java
package org.wpilib.templates.commandv3;

import org.wpilib.opmode.OpMode;
import org.wpilib.opmode.Teleop;

@Teleop
public class ExampleTeleop implements OpMode {
  public ExampleTeleop(Robot robot) {
    robot.exampleController.rightStick().whileTrue(robot.exampleMechanism.exampleCommand());
  }
}
```

`.../commandv3/ExampleAuto.java` (15 lines):

```java
@Autonomous
public class ExampleAuto implements OpMode {
  public ExampleAuto(Robot robot) {
    robot.exampleMechanism.exampleCondition.whileTrue(robot.exampleMechanism.exampleCommand());
  }
}
```

Note what is **absent**: there is no `RobotContainer.java` in `commandv3` or `commandv3skeleton`.
`grep -rn RobotContainer` over `commandsv3/` and both v3 templates returns zero hits. The `commandv2`
template still has one. **[verified]**

Also verified: **no template pairs `TimedRobot` with Commands v3.** `grep -rn "extends TimedRobot"` over
`templates/` hits only `timed`, `timedskeleton`, `commandv2`, `commandv2skeleton`, `romitimed`,
`xrptimed`, `romicommandv2`, `xrpcommandv2`. Every Commands v3 artefact — `commandv3`,
`commandv3skeleton`, and the `rebuiltcmdv3` example — extends `OpModeRobot`. **[verified]**

### Why they merged: opmode-scoped binding lifetimes

This is the deep integration, and it is the strongest technical argument in the whole investigation.

`commandsv3/src/main/java/org/wpilib/command3/BindingScope.java`: **[verified]**

```java
static BindingScope createNarrowestScope(Scheduler scheduler) {
  Command currentCommand = scheduler.currentCommand();
  long currentOpMode = OpModeFetcher.getFetcher().getOpModeId();

  if (currentCommand != null) {
    return new ForCommand(scheduler, currentCommand);
  } else if (currentOpMode != 0) {
    return new ForOpmode(currentOpMode);
  } else {
    return Global.INSTANCE;
  }
}

record ForOpmode(long opmodeId) implements BindingScope {
  @Override public boolean active() {
    return OpModeFetcher.getFetcher().getOpModeId() == opmodeId;
  }
}
```

`createNarrowestScope` is called from `Trigger`'s constructor (`Trigger.java:112`), from every
`onTrue`/`whileTrue` binding (`Trigger.java:565`), from `Scheduler.setDefaultCommand`
(`Scheduler.java:219`), from `Scheduler.schedule` (`Scheduler.java:558`), and from the periodic-sideload
registration (`Scheduler.java:291`). **[verified]**

The comment at the `schedule` site states the intent outright:

```java
// Get the narrowest binding scope.
// This prevents commands from outliving the opmodes that scheduled them, or from outliving
// their parents (eg if someone writes a command that manually calls schedule(Command) instead
// of using triggers to do so).
```

And the scheduler enforces it every loop — four separate cleanup paths, all verified in
`Scheduler.java`:

```java
private void cancelStaleBindings() {          // ~line 825
  ... if (binding.scope().active()) continue; cancel(binding.command()); iterator.remove(); ...
}
private void unbindStaleTriggers() {          // ~line 868
  ... if (!trigger.isScopeActive()) { trigger.unbind(); iterator.remove(); } ...
}
private void runPeriodicSideloads() {         // ~line 1071
  ... if (!callback.scope().active()) { iterator.remove(); continue; } ...
}
private void processDefaultCommands(...) {    // ~line 1075
  bindings.removeIf(b -> { if (!b.scope().active()) { cancel(b.command()); return true; } ... });
}
```

**So: in Commands v3, a `Trigger` binding, a scheduled command, a default command, or a periodic sideload
created inside an OpMode constructor is automatically cancelled/unbound when that OpMode ends.** That is
what killed `RobotContainer`. `RobotContainer.configureBindings()` was global-scope-only, ran once, and
had no teardown. An OpMode constructor *is* a scope.

### What happens if we use `TimedRobot` + Commands v3 anyway

`OpModeFetcher` reads `RobotState.getOpModeId()`, which comes from the DS control word — not from
`OpModeRobot`. **[verified]** With no opmodes registered, `getOpModeId()` returns 0, so
`createNarrowestScope` falls to `Global.INSTANCE` and every binding lives forever.

Consequence **[inferred, from verified mechanics]**: `TimedRobot` + Commands v3 compiles and runs, but
`ForOpmode` scoping is dead code for us. We get v2-style global bindings with a v3 API — the worst of
both. Every "we forgot to unbind the teleop trigger and it fired during auto" bug that v3 was built to
prevent comes back.

Note the layering is one-directional: `wpilibj`'s `opmode`/`framework` packages contain **zero** imports
of `command3`. **[verified]** `OpModeRobot` knows nothing about commands; Commands v3 knows about
opmodes. So OpMode without commands is fully supported (the `opmode` template), but commands-with-
opmode-scoping requires opmodes to be registered.

### Middle path (worth knowing, not recommended)

`RobotState.addOpMode(...)` / `publishOpModes()` are **static** and available from any base class,
including `TimedRobot` (`RobotBase` even re-exposes `getOpModeId()`/`getOpMode()` as statics at
`RobotBase.java:413,424`). **[verified]** So a `TimedRobot` could register opmode *names* with the DS and
get `ForOpmode` binding scoping, without `OpMode` objects being constructed/destroyed. **[inferred]**
This is a real escape hatch but it is a bespoke hybrid nobody upstream tests. Recorded for completeness;
not proposed.

---

## 4. Maturity in alpha-7

**Verdict: production-shaped and broadly wired through the stack, but with visible in-flight churn and a
genuine untested area. Not a skeleton.**

### Evidence it is real **[verified]**

| Layer | Evidence |
|---|---|
| Java impl | `OpModeRobot.java` 805 lines (vs `TimedRobot` 143 + `IterativeRobotBase` 350 = 493) |
| Java API | `OpMode` 89, `PeriodicOpMode` 86, 3 annotations × 56 |
| Java tests | `OpModeLifecycleTest` 558 lines / 7 tests, `OpModeRobotTest` 285 lines / 5 tests |
| C++ mirror | `OpModeRobot.{hpp,cpp}` 361+305, `OpMode.hpp` 88, `PeriodicOpMode.hpp` 128, tests 432+222 |
| Python | `wpilibc/src/main/python/semiwrap/{OpMode,OpModeRobot,PeriodicOpMode}.yml` bindings exist |
| HAL | `OpModeOption.java`, `ControlWord.getOpModeId()`, `RobotMode` enum, `DashboardOpMode.cpp` 161 lines |
| DS protocol | `MrcLibDs.cpp` reads `currentOpMode` from the control word with a capability bit |
| Sim GUI | `DriverStationGui.cpp` has a full grouped OpMode combo box (see §5) |
| Compiler | `javacPlugin/.../OpModeAnnotationValidator.java` — compile-time validation of annotation strings |
| Templates | `opmode`, `commandv3`, `commandv3skeleton` all shipped and listed in `templates.json` |
| Examples | 5 example projects incl. `rebuiltcmdv3` (18 files, 1277 lines, swerve + path following) |

A framework nobody intends to ship does not get a javac plugin, a DS wire-protocol capability bit, and a
C++/Python port.

Also notable: **zero** `TODO`, `FIXME`, `@Deprecated`, or `UnsupportedOperationException` markers inside
any OpMode-specific Java source. **[verified]**

### Evidence it is still moving **[verified]**

1. **`design-docs/opmodes.md` references a design document that does not exist.**
   > How opmodes work with the command-based framework is described in
   > [a separate design document](opmodes-commandbased.md).

   `design-docs/` contains only `commands-v3-state-machines.md`, `commands-v3.md`, `opmodes.md`,
   `real-time-thread-priorities.md`. `find . -name "opmodes-commandbased*"` → nothing. **The
   OpMode↔Commands integration rationale is unwritten**, even though the code exists. That is precisely
   the seam we most want documented.

2. **The design doc's `# Drawbacks` section is empty**, and `# Unresolved Questions` still lists three
   open items (a `SendableChooser`-style default opmode; multiple top-level `Robot` classes; Python
   decorator parity).

3. **The javac plugin validates an annotation that no longer exists.**
   `OpModeAnnotationValidator.java:86`:
   ```java
   boolean isTestOpMode = "org.wpilib.opmode.TestOpMode".contentEquals(qname);
   ```
   There is no `org.wpilib.opmode.TestOpMode` in the tree — it was renamed to `@Utility` and the
   validator was not updated. So `@Utility` name/group/description lengths are silently unvalidated, and
   the test suite passes only because it synthesises its own `TestOpMode` annotation source. Harmless,
   but a clear rename-leftover.

4. **Stale javadoc.** `OpMode.end()` is documented as called "asynchronously" but
   `endCurrentOpMode()` calls it synchronously inline. `PeriodicOpMode`'s javadoc references
   `TimedRobot`'s Notifier and calls `periodic()` "abstract" when it is a defaulted interface method.
   `Scheduler.java`'s class-level example still shows `public class Robot extends TimedRobot`.

5. **`endCompetition()` and `close()` both call `NotifierJNI.destroyNotifier(m_notifier)`** without
   guarding or nulling the handle — a latent double-destroy. Every test does exactly this and passes, so
   it is apparently benign in the HAL today.

6. **Test coverage is lopsided.** The lifecycle suite is genuinely good: it uses `SimHooks.pauseTiming()`
   and a real thread running `startCompetition()`, and covers opmode-change-while-enabled,
   opmode-change-while-disabled, enabled-on-first-DS-packet, DS disconnect, and callback teardown. But
   **`addAnnotatedOpModeClasses(Package)` — the headline classpath/JAR-scanning feature — has zero Java
   tests**, as do all five `checkOpModeClass` rejection paths, duplicate names, and exception isolation
   from user `start()`/`periodic()`. Roughly the registration/reflection half of `OpModeRobot`'s 805
   lines is untested in Java. **[inferred from the test files' contents]**

### On the AdvantageKit data point

The ticket notes AdvantageKit's 2027 notes say they have no `OpModeRobot` support yet. That is consistent
with what is in the tree, and it is also **irrelevant to us**: we have locked "explicit telemetry logging
via `org.wpilib.telemetry`, no AdvantageKit". Confirmed separately: **`LoggedRobot` does not exist
anywhere in this checkout** — it is an AdvantageKit class, not a WPILib one. **[verified]** The ticket's
question "how does `OpModeRobot` relate to `TimedRobot` and `LoggedRobot`" resolves to: sibling of
`TimedRobot` under `RobotBase`; unrelated to `LoggedRobot`, which is third-party and not in scope.

---

## 5. Simulation and telemetry

### Simulation: **yes, fully supported, and better instrumented than I expected** **[verified]**

`simulation/halsim_gui/src/main/native/cpp/DriverStationGui.cpp` has first-class OpMode support:

- `UpdateOpModes()` receives the published option list via `HALSIM_RegisterOpModeOptionsCallback`,
  buckets it into `gAutoOpModes` / `gTeleopOpModes` / `gUtilityOpModes`, and sorts within groups.
- The DS panel renders a real grouped combo box, filtered by the selected robot mode:
  ```cpp
  if (ImGui::BeginCombo("OpMode", name)) {
    for (auto&& [groupName, group] : modes->groups) {
      if (!groupName.empty()) { ImGui::TextDisabled("%s", groupName.c_str()); ImGui::Separator(); }
      for (auto&& mode : group) {
        if (ImGui::Selectable(mode.name.c_str(), mode.id == opMode)) {
          HALSIM_SetDriverStationOpMode(mode.id);
        }
      }
    }
    ImGui::EndCombo();
  }
  ```
- It shows a **GOOD / BAD / NONE** health indicator comparing the DS-selected opmode against the one the
  robot program reports, and **disables the Enable button** (`canEnable = false`) when they disagree or
  when opmodes exist but none is selected.

Java-side sim control exists too: `DriverStationSim.setOpMode(long)`, `getOpMode()`,
`getOpModeOptions()`, `registerOpModeCallback(...)`, `registerOpModeOptionsCallback(...)`. **[verified]**
So opmodes are drivable from unit tests, not just the GUI — which is exactly how
`OpModeLifecycleTest` works.

### The real Driver Station: there is a graceful fallback **[verified]**

This was my biggest risk concern going in — OpMode requires a 64-bit control word and DS protocol
changes, so what happens if the shipping 2027 DS doesn't do opmode selection? Answer:
`hal/src/main/native/cpp/mrclib/MrcLibDs.cpp` branches on a capability bit:

```cpp
if (mrclib::GetSupportsOpModes(controlData.controlFlags)) {
  controlWord = HAL_MakeControlWord(controlData.currentOpMode, ...);
} else {
  wpi::hal::EnableDashboardOpMode();
  controlWord = HAL_MakeControlWord(wpi::hal::GetDashboardSelectedOpMode(robotMode), ...);
}
```

`hal/src/main/native/cpp/DashboardOpMode.cpp` then publishes `SendableChooser`-shaped NetworkTables
entries — `.type` / `options` / `active` / `selected` — at:

```
/SmartDashboard/Auto OpMode
/SmartDashboard/Teleop OpMode
/SmartDashboard/Utility OpMode
```

`InitializeDashboardOpMode()` is called from `InitializeDriverStation()` in the **real**
`FIRSTDriverStation.cpp`, not just the sim path. **[verified]**

**So if the shipping DS doesn't support opmode selection, opmode selection transparently degrades to an
ordinary chooser widget on Elastic/Shuffleboard/AdvantageScope — exactly the workflow we have today.**
This removes the "we'd be betting on an unreleased Driver Station" risk almost entirely. **[inferred from
verified code paths]**

### Telemetry: no conflict, no advantage either way **[verified]**

`org.wpilib.telemetry` lives at `telemetry/src/main/java/org/wpilib/telemetry/` (11 files:
`Telemetry`, `TelemetryTable`, `TelemetryRegistry`, `TelemetryBackend`, `TelemetryEntry`,
`TelemetryLoggable`, `MockTelemetryBackend`, `MultiTelemetryBackend`, `DiscardTelemetryBackend`, …).
The API is static and framework-agnostic: `Telemetry.log(String name, ...)` with ~25 typed overloads
plus struct/protobuf variants.

Decisively: **telemetry is initialised in `RobotBase`, which both `TimedRobot` and `OpModeRobot`
extend.** `RobotBase.java:227-232`:

```java
TelemetryRegistry.setReportWarning(m_telemetryWarningReporter);
TelemetryRegistry.registerBackend("", new NetworkTablesTelemetryBackend(inst, "/Telemetry"));
```

Neither `OpModeRobot.java` nor anything in `org/wpilib/opmode/` imports `Telemetry` at all — it needs no
special integration because the setup happens a layer below both. **[verified]**

One useful side note: **Epilogue is built on top of `org.wpilib.telemetry`** —
`epilogue-runtime/build.gradle:14` reads `api(project(':telemetry'))`. **[verified]** The v3 templates
call `Epilogue.update(this)` in `robotPeriodic()`. Our locked "explicit `Telemetry.log` calls, no
AdvantageKit" decision remains valid and is unaffected by this choice; Epilogue is an optional
annotation-driven layer over the same backend, available if we ever want it, and orthogonal to
OpMode vs TimedRobot.

The design doc's own utility-opmode example uses `Telemetry.log("indicator", true)` directly, so this
pairing is the intended one.

---

## 6. What this looks like for a swerve base

The tree ships `wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/` — 18 files, 1277 lines
— which is essentially our project's shape: swerve drive, pose estimator, shooter, intake, path
following, Commands v3, OpMode. **[verified]** Worth reading in full before we write any code.

`rebuiltcmdv3/Robot.java`:

```java
@Logged
public class Robot extends OpModeRobot {
  public final SwerveDrive swerveDrive = new SwerveDrive();
  public final Shooter shooter = new Shooter();
  public final Intake intake = new Intake();
  public final PoseEstimator poseEstimator = new PoseEstimator();
  public final CommandGamepad controller = new CommandGamepad(1);

  public final Trigger inNeutralZone = new Trigger(() -> poseEstimator.inZone(NEUTRAL_ZONE));

  public Robot() {
    swerveDrive.setDefaultCommand(swerveDrive.idle());
    shooter.setDefaultCommand(shooter.idle());
    intake.setDefaultCommand(intake.idle());
  }

  @Override
  public void robotPeriodic() {
    poseEstimator.odometryUpdate(swerveDrive.getGyroHeading(), swerveDrive.getModulePositions());
    Scheduler.getDefault().run();
    Epilogue.update(this);
  }
}
```

`rebuiltcmdv3/opmodes/teleop/DefaultTeleop.java` — the entire teleop is a constructor:

```java
@Teleop
public class DefaultTeleop implements OpMode {
  public DefaultTeleop(Robot robot) {
    robot.swerveDrive.setDefaultCommand(robot.swerveDrive.driverControl(robot.controller));

    robot.controller.faceUp().onTrue(robot.intake.intake());
    robot.controller.faceDown().onTrue(robot.intake.stow());
    robot.controller.faceRight().whileTrue(robot.intake.agitate());

    robot.controller.rightStick().whileTrue(
        robot.swerveDrive.aimAssist(
            robot.controller,
            robot.poseEstimator::getEstimatedPose,
            () -> FieldConstants.targetHub().orElseGet(robot.poseEstimator::getEstimatedPose)));
  }
}
```

`rebuiltcmdv3/opmodes/auto/SweepAuto.java` — and note how directly this maps onto our Choreo plan:

```java
@Autonomous
public class SweepAuto implements OpMode {
  private final Trigger enabled = new Trigger(RobotState::isEnabled);

  public SweepAuto(Robot robot) {
    robot.intake.setDefaultCommand(robot.intake.stow());
    robot.inNeutralZone.onTrue(robot.intake.intake());
    enabled.onTrue(sweepAndScore(robot));
  }

  private Command sweepAndScore(Robot robot) {
    return Command.noRequirements(coroutine -> {
          coroutine.await(robot.swerveDrive.followPath("nz-sweep-left-trench"));
          FieldConstants.targetHub().ifPresent(hub -> coroutine.await(robot.shootAt(hub)));
        })
        .named("Sweep and Score");
  }
}
```

`robot.swerveDrive.followPath(String pathName)` is a `Mechanism` method wrapping a `PathFollower.load()`
stub — which is exactly the seam a Choreo trajectory loader drops into. **[verified]**
Each Choreo routine becomes one `@Autonomous` class. `DoNothingAuto` (22 lines) is the safe default.

---

## Key takeaways for this project

1. **Commands v3 and OpMode ship as one design.** Choosing v3 and rejecting OpMode is choosing an
   untemplated, untested combination. **[verified]**
2. **`RobotContainer` is gone from the v3 template.** Whatever we pick, our structure diverges from the
   2025 layout we know. **[verified]**
3. **OpMode-scoped binding lifetimes are the reason.** Triggers/commands/default-commands created in an
   OpMode constructor are cancelled and unbound automatically when the opmode ends. Under `TimedRobot`
   this feature is inert. **[verified]**
4. **The DS risk is mitigated in-tree.** Without DS opmode support, selection falls back to NT chooser
   widgets at `/SmartDashboard/{Auto,Teleop,Utility} OpMode`. **[verified]**
5. **Simulation support is complete and, in a nice touch, refuses to enable on an opmode mismatch.**
   **[verified]**
6. **Telemetry is a non-issue.** `RobotBase` initialises it for both. **[verified]**
7. **`OpModeRobot` is not a `TimedRobot` subclass.** It is a sibling under `RobotBase` with a different
   hook set. Migration is a rewrite of `Robot`, not a superclass swap. **[verified]**
8. **The threading model is unchanged** — single thread, one notifier, 20 ms default. Nothing new to
   teach students about concurrency. **[verified]**
9. **`SendableChooser` for autos is on its way out**, per the design doc's own migration section.
   **[verified]**
10. **There is no built-in swerve *drivetrain subsystem* in alpha-7.** `wpimath` has
    `SwerveDriveKinematics`, `SwerveModulePosition/Velocity/Acceleration`, `SwerveDriveOdometry`,
    `SwerveDrivePoseEstimator(3d)`; `commandsv3` has no swerve class; the only swerve *drivetrain* code
    is example code (`rebuiltcmdv3/mechanisms/SwerveDrive.java`, 242 lines and
    `SwerveModule.java`, 66 lines). See "Open questions". **[verified]**

---

## Recommendation

**Adopt `OpModeRobot` + Commands v3. Reverse the provisional `TimedRobot` choice.**

### Reasoning

**The premise of the provisional decision was wrong, and in a specific way.** It treated OpMode and
Commands v3 as separable, with `TimedRobot` as the low-risk leg. In alpha-7 they are not separable:
`OpModeRobot` is the only base class any Commands v3 template or example uses. Choosing `TimedRobot` +
v3 means we are the only ones running that combination, with no upstream template to compare against
and no upstream test exercising it. **That is more risk than adopting OpMode, not less.** If we truly
wanted the conservative path, it would be `TimedRobot` + Commands **v2** — and we have already ruled
that out.

**The feature we would be giving up is the one we most need.** Opmode-scoped bindings are a direct fix
for a bug class that bites mentor-plus-students teams every year: a trigger bound for teleop that stays
live and fires during auto, or a default command from last year's test routine that never got cleared.
Under `TimedRobot`, `createNarrowestScope` returns `Global.INSTANCE` and none of that cleanup runs. We
would be paying v3's learning cost and collecting only part of the benefit.

**It is better for the students, not worse.** "One file per thing the robot can do, with the file name
showing up in a dropdown on the Driver Station" is easier to teach than "one 400-line `Robot.java` with
a `SendableChooser` and a switch". It gives students small, isolated, ownable units of work —
`ShootFromLineAuto.java` is a tractable assignment in a way that "add a case to the auto chooser" is
not. And the constructor-only OpMode form in the v3 template is genuinely small: our teleop would be
one constructor of trigger bindings.

**The risk profile is acceptable and, importantly, bounded.**
- Real DS lacking opmode support → in-tree NT chooser fallback. **[verified]**
- Simulation → fully supported, with better safety interlocks than today. **[verified]**
- Telemetry → unaffected. **[verified]**
- The untested area is classpath scanning for annotated classes. If it misbehaves we can register
  explicitly with `addOpMode(RobotMode, String, Class)`, which *is* exercised by
  `OpModeRobotTest`. **[verified]** That is a cheap, known workaround.
- We are on alpha-7 and the season is a long way out. Churn is expected and we will be re-syncing
  regardless. Being on the sanctioned path means upstream fixes reach us; being off it means we own our
  divergence alone.

**The genuine cost is documentation.** `opmodes-commandbased.md` does not exist, `docs.wpilib.org` is
"in development", and the stale javadoc in `PeriodicOpMode` and `Scheduler` will mislead anyone reading
carefully. We are trading library-level risk for teaching-material risk. That is the right trade for a
team with an experienced mentor: `rebuiltcmdv3` is 1277 lines of working reference code that answers
most of what the missing prose would have, and the migration section of `design-docs/opmodes.md` covers
the rest.

### What changes in how we structure the project

- `Robot extends OpModeRobot`; it owns subsystems/mechanisms as **public final** fields (the examples do
  this deliberately — OpModes reach through `robot.swerveDrive`), sets safe idle default commands, and
  runs `Scheduler.getDefault().run()` in `robotPeriodic()`.
- **No `RobotContainer`.** Delete it from the plan.
- `opmodes/teleop/` — one or more `@Teleop implements OpMode` classes, constructor-only, doing trigger
  binding. `DefaultTeleop` plus e.g. a `DemoTeleop` with reduced speed limits for outreach.
- `opmodes/auto/` — one `@Autonomous implements OpMode` per Choreo routine, plus a `DoNothingAuto`.
  This replaces the `SendableChooser` in our plan.
- `opmodes/utility/` — `@Utility` classes for per-mechanism bring-up (swerve module zeroing, SPARK
  characterisation, Pigeon2 checks). This is a real win: we get first-class test routines that never
  ship in the teleop code path, and they are grouped in the DS dropdown via `group=`.
- Keep: Java 25, `org.wpilib`, REV SPARK + CTRE Pigeon2, `org.wpilib.telemetry`, Choreo. **None of these
  are affected.**

### If we want to hedge

The migration cost is small and mostly one-directional-friendly: `Mechanism` classes, constants, and
commands are identical under either base class. Only `Robot` and the mode-entry classes differ.
Building mechanisms first and deciding the base class later is viable — but the recommendation is to
just start on `OpModeRobot`, because the trigger-binding *location* differs between the two and that is
the part students will write most of.

---

## Open questions / unknowns

1. **Will the shipping 2027 Driver Station support native opmode selection?** Not answerable from this
   checkout. The capability bit and the NT fallback both exist, so we are safe either way, but the
   *workflow* differs (DS dropdown vs dashboard widget). **[unknown]**
2. **Is the OpMode↔Commands-v3 integration design settled?** `opmodes-commandbased.md` is referenced but
   absent. The code exists and is coherent, but the rationale is unpublished and could still move.
   **[verified absent; consequence unknown]**
3. **Will `addAnnotatedOpModeClasses` scanning work reliably under GradleRIO's deploy jar layout?** The
   JAR-scanning branch has zero Java tests. Worth an early smoke test on real hardware. Mitigation:
   explicit `addOpMode(..., Class)` registration. **[unknown]**
4. **What happens to a Choreo trajectory cache across opmode construction/destruction?** OpModes are
   constructed on DS selection while disabled — which is the design's stated reason for that timing
   ("expensive opmode-specific operations (e.g. computing autonomous paths)"). But repeated
   deselect/reselect reconstructs and reloads. If trajectory loading is slow, cache it in `Robot`, not in
   the OpMode. **[inferred]**
5. **`design-docs/opmodes.md` "Unresolved Questions" are still open** — notably whether a robot-code
   default opmode will exist. If it never does, an operator who forgets to pick an auto gets *nothing*
   (the sim GUI even refuses to enable). That is arguably safer than a wrong auto, but it is a new
   pre-match checklist item for the drive team. **[verified open]**
6. **Does `@Utility` opmode behaviour differ from the old Test mode in any way that matters?**
   `RobotMode.UTILITY` replaces `TEST`. Not investigated in depth. **[unknown]**
7. **How does opmode selection interact with FMS in a real match?** The design describes two dropdowns
   (auto + teleop) pre-selected before the match, with the DS auto-transitioning. Only the design intent
   is verified; the FMS-attached path is not exercisable here. **[unknown]**
8. **Locked-decision check — "WPILib built-in swerve".** There is no built-in swerve *drivetrain* class
   in alpha-7, only `wpimath` kinematics/odometry/pose-estimation plus example-code drivetrains. If that
   decision meant "a turnkey WPILib swerve subsystem", it needs revisiting: what exists is
   kinematics + `rebuiltcmdv3/mechanisms/SwerveDrive.java` as a template to copy. If it meant "WPILib
   kinematics rather than a vendor swerve library", it stands. **This is the one finding that may touch
   a locked decision, and it is independent of the OpMode question.** **[verified]**

---

## Source index

| Topic | Path (relative to `~/dev/allwpilib`) |
|---|---|
| Design rationale | `design-docs/opmodes.md` (509 lines) |
| Commands v3 design | `design-docs/commands-v3.md`, `design-docs/commands-v3-state-machines.md` |
| Missing integration doc | `design-docs/opmodes-commandbased.md` — **referenced, does not exist** |
| `OpMode` interface | `wpilibj/src/main/java/org/wpilib/opmode/OpMode.java` |
| `PeriodicOpMode` | `wpilibj/src/main/java/org/wpilib/opmode/PeriodicOpMode.java` |
| Annotations | `wpilibj/src/main/java/org/wpilib/opmode/{Autonomous,Teleop,Utility}.java` |
| `OpModeRobot` | `wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java` (805 lines) |
| `TimedRobot` | `wpilibj/src/main/java/org/wpilib/framework/TimedRobot.java` (143 lines) |
| `IterativeRobotBase` | `wpilibj/src/main/java/org/wpilib/framework/IterativeRobotBase.java` (350) |
| `RobotBase` (telemetry init, opmode statics) | `wpilibj/src/main/java/org/wpilib/framework/RobotBase.java` |
| Callback scheduler | `wpilibj/src/main/java/org/wpilib/internal/PeriodicPriorityQueue.java` |
| Opmode DS facade | `wpilibj/src/main/java/org/wpilib/driverstation/RobotState.java` |
| Sim control | `wpilibj/src/main/java/org/wpilib/simulation/DriverStationSim.java` |
| Lifecycle tests | `wpilibj/src/test/java/org/wpilib/framework/OpModeLifecycleTest.java` (558) |
| Registration tests | `wpilibj/src/test/java/org/wpilib/framework/OpModeRobotTest.java` (285) |
| Binding scopes | `commandsv3/src/main/java/org/wpilib/command3/BindingScope.java` |
| Opmode fetch | `commandsv3/src/main/java/org/wpilib/command3/OpModeFetcher.java` |
| Scope cleanup | `commandsv3/src/main/java/org/wpilib/command3/Scheduler.java`, `Trigger.java` |
| v3 template | `wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/` |
| v3 skeleton | `wpilibjExamples/src/main/java/org/wpilib/templates/commandv3skeleton/` |
| Plain opmode template | `wpilibjExamples/src/main/java/org/wpilib/templates/opmode/` |
| Swerve reference example | `wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/` (18 files, 1277 lines) |
| Template manifest | `wpilibjExamples/src/main/java/org/wpilib/templates/templates.json` |
| Telemetry module | `telemetry/src/main/java/org/wpilib/telemetry/` (11 files) |
| Epilogue → telemetry dep | `epilogue-runtime/build.gradle:14` |
| DS opmode fallback | `hal/src/main/native/cpp/DashboardOpMode.cpp`, `mrclib/MrcLibDs.cpp` |
| Real DS init | `hal/src/main/native/cpp/FIRSTDriverStation.cpp:292` |
| Sim GUI opmode UI | `simulation/halsim_gui/src/main/native/cpp/DriverStationGui.cpp` |
| Compile-time validation | `javacPlugin/src/main/java/org/wpilib/javacplugin/OpModeAnnotationValidator.java` |
| HAL opmode types | `hal/src/main/java/org/wpilib/hardware/hal/{OpModeOption,RobotMode,ControlWord}.java` |
