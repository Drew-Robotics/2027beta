# ADR 0003 — Project and package structure

## Status

Accepted — 2026-08-26. The `SparkSim` breakage that stood under *Open*
is resolved by ADR 0010 and now sits under *Consequences*.

Claim tags are defined in the index. `[source]` claims here were read
at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`. No alpha-7 has been tagged; the checkout is called
alpha-7 because its vendordeps say `2027_alpha7`. An unqualified path is
a file in this repo.

## Context

The 2027 robot is greenfield: nothing is ported from the 2026 offseason
code. Every other decision in this set — where config is applied, how
telemetry is named, where sim lives, what the tests can reach — assumes
a layout, so the layout is settled first.

Two upstream references exist for a Commands v3 robot on `OpModeRobot`,
and they disagree with each other on the one point that matters most
here (field visibility on `Robot`). This ADR picks between them and
records why.

## Decision

### The stock template, unmodified

We use the project the 2027 VSCode/GradleRIO generator produces, as it
produces it. Team **8852**, which the generator writes into
`.wpilib/wpilib_preferences.json`. **[source]**

Our decisions layer on top of that project. We do not rename its
packages, move its files, or hand-edit its `build.gradle`. When a
departure from stock becomes necessary, it carries a one-line comment
at the edit saying why, and `git diff` against the generator's output is
the list of everything that is not stock.

### Top-level layout

| Path | Holds |
|---|---|
| `src/main/java/first/` | robot code |
| `src/main/deploy/` | Choreo project and `.traj` files — the template deploys this tree to `/home/systemcore/deploy` |
| `src/test/java/first/` | tests, mirroring main |
| `docs/adr/` | these documents |
| `docs/research/` | research output, with its measurements and methods |
| `CONTEXT.md` | the glossary |
| `vendordeps/` | vendor JSONs, committed |

### Package structure

The root package is **`first.robot`** — the package the generator puts
`Robot.java` in. It is the scan root, which is the reason it is not a
cosmetic choice: `OpModeRobot` automatically registers classes annotated
`@Autonomous`, `@Teleop` and `@Utility` that live *in the same package
as your `Robot` subclass, or below it* (`OpModeRobot.java:52-54`,
scanning at `OpModeRobot.java:533`). **[source]** An opmode outside that
subtree is invisible to the Driver Station.

```
first/
  Main.java             the generator's launcher; hand it Robot.class and forget it
  robot/
    Robot.java          extends OpModeRobot; mechanisms as public final fields
    Constants.java      constructs the per-mechanism config records
    mechanisms/         one file per mechanism, named for the physical thing
    opmode/             all opmodes, flat
```

`opmode` is singular because that is the name the generator writes.

**Opmodes are flat.** All of them live directly in `first.robot.opmode`
— no `opmode/auto/`, no `opmode/teleop/`. They are told apart by their
annotation and by `@Teleop(group = "...")`. The Driver Station already
reads `group()`, `name()`, `description()` and `textColor()` and lays
opmodes out by them, so a package tree mirroring those groups is a
second copy of the same structure, with two places to get out of sync.
**[decided]**

### The hardware boundary: there is not one

**One class per mechanism, owning both its real and its simulated
hardware.** No IO interface, no per-mechanism abstraction layer.

The vendor sim classes are already the seam.
`SparkSim(SparkBase spark, DCMotor motor)` wraps a live `SparkMax` and
drives that object's signals **[source —
`docs/research/vendordeps.md` §4.1]**, so the same `SparkMax` a mechanism
holds behaves as hardware on the robot and as a model on a laptop. There
is nothing left for an interface to abstract over: it would have exactly
one production implementation.

That seam is real in the API and currently unusable against our pinned
checkout — see Open.

This follows upstream, where `SwerveDrive` constructs its own motors
from constants (`rebuiltcmdv3/mechanisms/SwerveDrive.java:35-50`) and
hands them to `SwerveModule` through a package-private constructor
(`SwerveModule.java:25`). **[source]** Mechanisms own their hardware;
sub-components receive it.

How sim is actually driven inside a mechanism belongs to ADR 0010.

### Constants and configuration

Each mechanism takes a **config record**, and all the records are
constructed in one `Constants` class:

```java
record SwerveModuleConfig(int driveId, int turnId, Angle encoderOffset) {}
```

A record makes "what does this mechanism need" a single readable
signature, and a practice-bot variant becomes a second record rather
than a find-and-replace across scattered `public static final` fields.
**[decided]**

*How* a config is applied to hardware and verified is ADR 0004. This ADR
owns only its shape.

### Telemetry: a `TelemetryTable` is passed in

Each mechanism is handed the table it logs to:

```java
public class SwerveModule {
  private final TelemetryTable log;

  SwerveModule(SparkMax driveSpark, SparkMax turnSpark, TelemetryTable log) {
    this.log = log;
    log.setProperty("Velocity", "unit", "m/s");  // once, not per sample
  }

  void setTarget(SwerveModuleVelocity target) {
    log.log("TargetVelocity", target, SwerveModuleVelocity.struct);
    log.log("Velocity", getVelocity(), SwerveModuleVelocity.struct);
  }
}
```

On the robot that is
`new SwerveModule(driveSpark, turnSpark, driveTable.getTable("FrontLeft"))`;
in a test it is
`new SwerveModule(driveSim, turnSim, new TelemetryTable(mockBackend))`.
Nesting costs one `getTable(...)` call, and a test needs no global
state. **[decided]**

The root `TelemetryTable` and its backend are built **inline in
`Robot`'s constructor, above the mechanism fields** — not hidden behind
a helper. Anything not routed through telemetry never reaches the log
file, and that is the one fact a reader has to see. The signal names and
the backend itself are ADR 0005.

### Naming and size

Match the **templates**, not `wpilibj`'s internals. Students read
templates; nobody reads library internals.

- `ALL_CAPS` for constants — upstream renamed these repo-wide
  (`rebuiltcmdv3/constants/DriveConstants.java:15-20`). **[source]**
- No `m_` prefix. Plain `camelCase` fields.
- A mechanism is *a thing that owns motors*. One file per mechanism in
  `mechanisms/`, named for the physical thing.

No hard file-size limit. At roughly 300 lines, ask whether sim belongs
in a sibling file — and accept the answer "no". `SwerveDrive` is
allowed to be the one big obvious class: upstream's is 242 lines
**[source]** and ours carries kinematics wiring *and* sim, so it will be
larger. Splitting a drivetrain across files to hit a line count makes it
harder to follow.

### Build, deploy and console

**Fat jar**, which is what the stock template already does: it applies
`com.gradleup.shadow` and points the deploy artifact at it
(`build.gradle:104`). Choosing the ordinary VSCode or Gradle deploy *is*
choosing the fat jar. The shadow config also stashes `src/`,
`vendordeps/` and `build.gradle` inside the jar under `backup/`
(`build.gradle:96-98`). **[source]**

Opmode discovery survives the fat jar: the scanner handles `jar:` URLs
as well as `file:` ones (`OpModeRobot.java:438-459` for `jar:`, `:460-466` for `file:`).
**[source]**

`vendordeps/` JSONs are committed, and committing them *is* the pin.
**[decided]** The vendor JSON repo publishes only coarse generations, so
a fetch-on-demand step would drift silently — one `wpilibYear` string
covers two WPILib alphas **[source — `docs/research/vendordeps.md`
§0.3]**. Commands v3, REVLib 2027 and
Phoenix 6; **never Commands v2** — v2 and v3 are mutually exclusive
vendordeps, so there is no incremental migration path **[source —
`docs/research/commands-v3.md` §9]**.

### Which WPILib we build against

While 2027 is in flux we build against the local checkout, with
`useWpilibMavenLocalDevelopment`; in season we take the published
release. Only the artifact source changes — the GradleRIO deploy path is
identical either way. **[decided]**

Three things move together: OS image build ↔ allwpilib version and
commit ↔ MRC API number. The discipline is a habit, not a tool: **read
the device's `MRC_CheckApiVersion` before bumping anything**, and keep
the bench on the image the checkout expects. A mismatch is not a build
error — the HAL calls `std::terminate()` at startup, and
`robot.service` is `Restart=always` with `RestartSec=3`
**[source — `docs/research/systemcore-deploy.md`]**, so it becomes a
crash loop rather than a message. A device on an older image was made to
crash-loop against a newer allwpilib exactly this way, and stopped when
the image was flashed forward. **[executed, via #10]**

**Never `println` from anything that runs periodically.** A single
`println` out of `nonePeriodic()` took ~25 ms and tripped the watchdog on
a 20 ms loop. **[measured — `docs/research/systemcore-deploy.md`]** Values
go to telemetry, faults go to `Alert`, and `println` is for constructors
and one-shot code. There is no riolog — `RioLogPlugin` is commented out
in GradleRIO's deploy plugin — so output is `journalctl -u robot -f`.
**[source — `docs/research/systemcore-deploy.md`]**

## Consequences

- **Mechanism fields on `Robot` are `public final`.** Opmodes sit in a
  subpackage, so package-private would not be visible to them. See Traps.
- **`Constants` is a single class that will grow.** That is the trade
  for one place to look; it is also the file a practice-bot variant edits.
- **ADR 0004 inherits a shape it must work with.** Config records are
  constructor-injected, so applying and verifying config happens at
  mechanism construction, not in a separate init pass.
- **ADR 0010 inherits "no seam."** With no IO interface, the sim/real
  split has to be a type rule inside the mechanism rather than a swapped
  implementation.
- **ADR 0013 inherits testability from injection, not from interfaces.**
  A mechanism is testable because its hardware and its telemetry table
  are constructor parameters.
- **"Stock template, unmodified" is checked by `git diff`, not by a
  list.** Every exception is a commented edit inside the tree the
  generator produced, so the claim stays verifiable with nothing to
  maintain.
- **The compile-time safety net is one line in `build.gradle`.**
  `annotationProcessor wpi.java.deps.wpilibAnnotations()`
  (`build.gradle:60`) is what supplies the Commands v3 checks. See Traps.

- **`SparkSim` does not run against the pinned checkout, and the
  no-seam decision does not depend on it.** `SparkSim` reaches
  `MovingAverageFilterSim`, which reaches `org.wpilib.math.util.Pair` —
  a class that moved to `org.wpilib.util` after alpha-6 — so `SparkSim`
  and its two subclasses throw `NoClassDefFoundError`, while every other
  class in `com.revrobotics.sim` is clean. The hardware path is
  untouched and class resolution is lazy, so only desktop sim would
  throw. **[source — `docs/research/vendordeps.md` §4.5, narrowed by
  #25]** ADR 0010 models the on-SPARK loop rather than calling
  `SparkSim`, so the class is never named and the breakage is off our
  critical path. What this ADR asserted — that the vendor sim classes
  are the seam — is narrowed there to the two sensor sims, which do
  load.

## Traps

- **The opmode subpackage forces `public` fields on `Robot`.** This is
  the exact split between the two upstream references:
  `templates/commandv3/Robot.java:14` keeps its mechanism field
  package-private *because* its opmodes are flat in the root package,
  while `examples/rebuiltcmdv3/Robot.java:24-28` makes all of its fields
  `public final` *because* its opmodes sit in `opmodes/auto/` and
  `opmodes/teleop/`. **[source]** Package depth decides visibility, and
  we take `public` as the price of one obvious place to find opmodes.

- **An opmode next to `Main.java` never registers.** The scan root is
  the package of the `Robot` subclass (`OpModeRobot.java:533`), which is
  `first.robot` — `first` is *above* it. A `@Teleop` class in `first`
  compiles clean and simply never appears on the Driver Station.
  **[source]**

- **Renaming `Main`'s package silently breaks the deploy.**
  `build.gradle:12` names the launcher as the *string* `"first.Main"`,
  and that string is what goes into the jar manifest
  (`build.gradle:99`). **[source]** Nothing checks it, so the build is
  clean and the robot fails to start. Renaming `Robot`'s package is
  safe by comparison — `Main.java` names it as a class literal, which
  the compiler does check.

- **A test that builds a mechanism without a `RobotBase` logs every
  `Measure` as its `toString()`.** The `Measure` type handler is
  registered by `RobotBase` itself
  (`wpilibj/.../framework/RobotBase.java:229-233`) **[source]**, and ADR
  0005 makes `Measure` the default value type for everything. The test
  passes, the log is garbage. This is a property of the framework, not of
  our injection choice — it would bite any telemetry approach.

- **Dropping `wpilibAnnotations()` disables the safety net with no
  warning.** That one line ships `javac-plugin-java`, which is what
  enforces `@NoDiscard`, the yield-in-loop check, opmode annotation
  validation, and the coroutine misuse detectors. A hand-rolled
  `build.gradle` that omits it still compiles — it just stops catching
  the things that hang a robot. **[source, via #12 — GradleRIO
  `WPIJavaDepsExtension.java:84-85`; GradleRIO is not in the local
  checkout, so this one was not re-read for this ADR]**

- **Alert visibility on a dashboard is unverified.** Nothing in-tree
  publishes `Alert` to NetworkTables. "Faults go to `Alert`" is a rule
  whose last mile nobody has watched work. **[unverified]**

## Open

- **Expansion Hub and Smart IO.** Whether a drive base needs them at all
  is unclear, so neither has a place in the layout above.
  *Unblocked by* a mechanism that actually needs a port they provide —
  at which point where their configuration lives becomes a real
  question rather than a hypothetical one.

## Rejected

### Epilogue (`@Logged`) — do not re-raise without new evidence

Epilogue is WPILib-native, writes through `org.wpilib.telemetry`, and is
what `rebuiltcmdv3` — the only complete v3 swerve robot upstream — uses
exclusively. It deserved a real look, and it got one: the generated
`SwerveDriveLogger.java` decided it.

1. **`private` does not protect you.** The default strategy is
   `Strategy.OPT_OUT` (`Logged.java:63`), so a bare `@Logged` on the
   class logs everything it can reach — and it reaches private fields by
   emitting `MethodHandles.privateLookupIn` and a `VarHandle` per field
   (`SwerveDriveLogger.java:29-36`). All three of `SwerveDrive`'s
   `PIDController`s are logged because they exist, not because anyone
   chose them. **[source]**
2. **Static values are re-serialized every loop.**
   `SwerveDriveLogger.java:49` writes `kinematics` as a protobuf on
   every single update — a value that never changes once constructed.
   **[source]**
3. **Names come out as raw code names.** The default is
   `Naming.USE_CODE_NAME` (`Logged.java:126`), which produces
   `getGyroHeading` and `xController` (`SwerveDriveLogger.java:54-66`).
   **[source]** Meeting ADR 0005's naming means `@Logged(name = "...")`
   on nearly every member: the same keystrokes as an explicit log call,
   and less obvious to a reader.

Its genuine wins — free nested tables, automatic unit metadata — are
things ADR 0005 specifies by hand anyway, and injection buys the nesting
for one line.

The honest framing: the real axis is **snapshot vs event**. Epilogue
logs state at the end of a loop; explicit logging logs at the moment of
action. Our log schema is a snapshot model, so Epilogue genuinely fits
the model. It loses on accidental logging and on naming, not on shape.

**Do not re-raise this without new evidence.** New evidence means the
generated code changing — an opt-in default, name derivation, or static
values hoisted out of the update path — not a fresh reading of the same
generator.

**We do not remove Epilogue from the build.** `wpilibAnnotations()`
bundles the Epilogue processor together with the Commands v3 javac
plugin, so declining it would mean replacing one line with an explicit
list. The processor generates nothing for unannotated classes. We simply
never write `@Logged`.

### Static `Telemetry.log(...)`

A held `TelemetryTable` caches its entries on the short name; the static
call keys its cache on the full concatenated path instead **[source —
`docs/research/telemetry-api.md` §10]**, and it forces every test
through a global registry reset. Injection is both the cheaper call and
the one with a seam.

### An IO interface per mechanism

The AdvantageKit shape, already rejected earlier on the design map. The
ground is under *The hardware boundary*: one production implementation
is not a seam. Note this survives the `SparkSim` breakage in Consequences — ADR 0010
needs no vendor fix, and an interface layer would have cost all season.

### Opmode subpackages by group

`opmode/auto/` and `opmode/teleop/` — the shape `rebuiltcmdv3` uses
(`examples/rebuiltcmdv3/opmodes/auto/`, `.../teleop/`). **[source]**
Rejected as a duplicate of the Driver Station's own `group()` namespace.
Note that taking this would *not* have bought back package-private
fields: any subpackage at all is what forces `public`, not its depth.

### `constants/` with `public static final` scatter

What both upstream references do. A mechanism's requirements end up
spread across a file instead of readable in one signature, and a
practice-bot variant becomes a find-and-replace.

### allwpilib's `developerRobot` deploy path

A mentor convenience with a different deploy layout
(`wpilib/allwpilibclasspath/`) and a different GC default. Not our
deploy path.

### Classpath (non-fat) deploy

Never on the table — the stock template's shadow jar is the deploy
artifact, and opmode discovery is verified to work inside it.

### `m_` field prefix

A C++ import that Java tooling does not need, and it does not appear in
the templates students read.

## Source

Decided in [#12](https://github.com/Drew-Robotics/2027beta/issues/12),
which also carries the telemetry routing from
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and
[#2](https://github.com/Drew-Robotics/2027beta/issues/2), the framework
reversal to `OpModeRobot` from
[#7](https://github.com/Drew-Robotics/2027beta/issues/7) and
[#3](https://github.com/Drew-Robotics/2027beta/issues/3), the build and
deploy facts from [#9](https://github.com/Drew-Robotics/2027beta/issues/9)
and [#10](https://github.com/Drew-Robotics/2027beta/issues/10), and the
vendordep pin from [#5](https://github.com/Drew-Robotics/2027beta/issues/5).

Research: [`docs/research/opmodes.md`](../research/opmodes.md),
[`docs/research/commands-v3.md`](../research/commands-v3.md),
[`docs/research/telemetry-api.md`](../research/telemetry-api.md),
[`docs/research/vendordeps.md`](../research/vendordeps.md),
[`docs/research/systemcore-deploy.md`](../research/systemcore-deploy.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java`,
`epilogue-runtime/src/main/java/org/wpilib/epilogue/Logged.java`,
`wpilibjExamples/src/main/java/org/wpilib/templates/commandv3/`,
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`, and
the generated
`wpilibjExamples/build/generated/sources/annotationProcessor/java/main/org/wpilib/examples/rebuiltcmdv3/mechanisms/SwerveDriveLogger.java`.

### Departure from #12

#12 names the root package `frc.robot` and the opmode package
`opmodes/`. The generator writes `first.robot` and `opmode/`
(`src/main/java/first/robot/opmode/`; `build.gradle:12`). **[source]**
This ADR follows the generator, on #12's own stronger commitment to the
stock template unmodified. Nothing else in #12 is affected: the root
package is the scan root whatever it is called, and opmodes are flat by
`group()` because that is our choice, not the generator's.
