# ADR 0013 — CI and test strategy

## Status

Accepted — 2026-08-27. Amended the same day: Tier 1 has been run, and
it loads the HAL — see *Tier 1 owns every number*. Resolves #25's ruling
that Tier 2 and `sim-hitl` stay dormant: that dormancy was named against `SparkSim`, and ADR 0010
put the onboard loop in our own model and stopped loading the class at
all. Both tiers are live. Amended 2026-08-30: Tier 2 now actually runs.
It was dormant in fact if not in principle until ADR 0015's shim let a
SPARK be constructed; `WiringTest` is green, and the test task forks a
JVM per class because the HAL, the alert table and the data log are all
process-wide and a Tier 2 class claims the data log for the rest of the
JVM. Corrects 2026-09-06 by #121: *The year-gate edit, and what inverts
it* named its own revert condition and the condition fired. GradleRIO
alpha-7 moved the project-wide year to `2027_alpha7`, so
`CommandsV3.json` is back to upstream's value and the three third-party
JSONs are the edited side. The section and its table are rewritten to
the state that actually holds; nothing about the decision changed.
Amended 2026-09-06 by #100: *A pre-flight ABI probe* stays rejected as a
CI assertion and has been built on the deploy path, where the audience
is different. The entry says which is which, and names the one thing it
moves — the nightly's designed red now lands on the deploy step.
Amended 2026-09-07 by #101: the bench workflow exists, and both its
jobs are executed rather than designed. `real-hal-boot` is green on the
bench. `sim-hitl` runs a `linuxarm64` build there, which closes the
first open item — the Pi does run the sim HAL. Two more close with it:
Xvfb does run the Driver Station, and opmode selection needs no click
targets. One opens, and it is the reason the loop assertion moved: the
Driver Station will not attach to a simulation on another machine, so
the loop measured is the disabled one. The sections below say so where
they said otherwise.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. GradleRIO
`[source]` claims were read in `GradleRIO-2027.0.0-alpha-7`, the version
`build.gradle:4` pins, from the plugin jar by `javap`; paths are given
as class names. An unqualified path is a file in this repo.

## Context

CI is the only thing that makes a *fully sim capable* claim continuously
true rather than true once. Everything else in this set — ADR 0010's
seam, ADR 0011's autonomous loop, ADR 0005's log — is a design that can
rot silently between the day it is written and the day someone drives
the robot. A test that runs on every push is what stops that.

The audience is students. Feedback has to be fast, has to land where
they are already looking, and has to say what to do next without a
mentor translating. A CI that takes ten minutes, or that reports
`expected displacement > 1.0, was 0.03` and stops there, has not helped
anybody.

Three constraints shape the answer and none of them are ours to choose:

- **The project builds against a moving target.** There is no released
  WPILib we can use. `frcmaven/release` carries no 2027 artifacts at
  all, and the newest tag, `v2027.0.0-alpha-6`, has no `telemetry`
  module, no `tunables` module and no `mrclib`/SystemCore HAL — so it
  cannot compile ADR 0005's logging and cannot deploy to the Pi.
  **[source, via #19]**
- **The vendordep year gate is out of step with somebody, always.**
  GradleRIO's project-wide requirement is `2027_alpha7` and allwpilib's
  own first-party JSONs match it, but no third-party vendor has
  published against alpha-7, so every one of theirs still declares
  `2027_alpha5`. It was the other way round when this ADR was written.
  Whichever way it sits, some JSON in `vendordeps/` is wrong for the
  plugin that reads it. **[source]**
- **There is exactly one bench Pi.** Anything that runs on hardware
  queues on a single physical box that may be unplugged.

The first two make CI go red for reasons nobody in this repo caused. The
third makes a hardware check unable to gate anything. Both facts are
designed around rather than papered over.

## Decision

### One gated workflow, and it is the only required check

A single workflow on `ubuntu-latest`, JDK 25 Temurin, with Gradle
caching and a concurrency group that cancels superseded runs.
**[decided]** One workflow, not several, because #18 gates the PR review
agent on `workflow_run` with `conclusion == success` — the reviewer needs
one name whose success means *lint and unit tests passed*. **[decided,
via #18]**

Target is **under four minutes**. Past roughly six the answer is to
split the job, not to trim assertions. **[decided]**

### Order is lint → compile → test, run with `--continue`

Analyzers first, then compile, then tests, in one Gradle invocation with
`--continue` so **one push produces one complete report**. **[decided]**

A lint failure must not hide a test failure. Making a student pay a
whole CI cycle to discover the second problem is the exact experience
this ADR exists to prevent, and `--continue` costs one flag.

### Triggers, and branch protection that includes administrators

Pull requests targeting `main`, plus pushes to any branch. Branch
protection on `main` requires the check **including administrators**.
**[decided]**

That is only tolerable because the escape hatch is a *branch, not a
person*: **during an event we push to a per-event branch with no
restrictions and merge afterwards.** An admin override is a habit that
gets used on a Tuesday; a branch is a thing you have to mean.

This is new. Today `main` has no protection at all and all six
collaborators hold `admin`, so CI could inform but not gate, and anyone
can push straight to `main`. **[executed — GitHub API, 2026-08-26]**

### Tier 1 owns every number

Plain JUnit. No vendor jars, no `RobotBase`, and nothing the test asks
of a HAL. It holds ADR 0010's physics tests — terminal velocity under a constant voltage, pure
rotation producing zero translation, over-command producing skid — and
ADR 0011's closed autonomous loop: follower → `ChassisVelocities` →
kinematics → module voltages → `SwerveDriveSim` → pose → back into the
follower. **[decided]**

**Every numeric assertion lives here**, because this tier is
deterministic given a fixed `dt` and can therefore be tight. It is fast,
it parallelises, and it does not queue on anything.

This tier exists at all because ADR 0010 kept vendor types out of
`first.robot.sim` by construction. That rule was insurance, and this is
where it pays.

**It does not run without a HAL, and that is not a choice we get to
make.** `Scheduler.schedule()` calls `BindingScope.createNarrowestScope`
(`Scheduler.java:558`), which asks `OpModeFetcher` for the current
opmode id (`BindingScope.java:29`); the default fetcher reads
`RobotState.getOpModeId()` (`OpModeFetcher.java:29-39`), and
`DriverStationBackend`'s static initialiser calls `HAL.initialize()` and
builds a NetworkTables match-data sender
(`DriverStationBackend.java:738-755`). **[source]** `libwpiHal`,
`libwpiHaljni`, `libntcore` and `libwpiutil` are absent from the test
JVM before the `schedule()` call and present after it. **[executed —
`/proc/self/maps`, read either side of the call, via #56]**

Nothing changes in practice: GradleRIO's `configureTestTasks` already
puts the desktop natives on the test JVM's library path **[source]**,
the load is one-off, and no Tier 1 test asks the HAL a question or
initialises it on purpose. The escape — a hand-copied `CommandTestBase`
in a split `org.wpilib.command3` package — is under *Rejected*. The
sentence to keep is the narrow one: **a Tier 1 test still needs the
desktop natives to be downloadable**, so a native-resolution failure in
CI is a Tier 1 failure and not only a Tier 2 one.

The clock is the test's. `Coroutine.wait` and every `SchedulerEvent`
timestamp read `RobotController.getTime()`, whose default source is
`getMonotonicTime` — a JNI call (`RobotController.java:26`).
**[source]** A Tier 1 test redirects it with
`RobotController.setTimeSource` at a counter it advances by
`Constants.LOOP_PERIOD` per `Scheduler.run()`, which is also what makes
ADR 0002's *assert in time, never in ticks* writable here.

### Tier 2 owns the wiring, and asserts almost nothing

The real `Robot` — a real `OpModeRobot` — constructed in process,
headless.

It needs **no Driver Station and no X server**. `DriverStationSim`
carries `setOpMode(long)` (`DriverStationSim.java:288`),
`getOpModeOptions()` (`:310`), `setEnabled` (`:52`), `setDsAttached`
(`:176`) and `notifyNewData` (`:315`), and upstream's own
`OpModeRobotTest` drives a real `OpModeRobot` through `HAL.initialize()`
+ `DriverStationSim` + `SimHooks`
(`wpilibj/src/test/java/org/wpilib/framework/OpModeRobotTest.java:20-21,
97-105, 144, 244-278`). **[source]** The JUnit extension that wires it
is auto-detected by `junit.jupiter.extensions.autodetection.enabled`,
which the template already sets (`build.gradle:116`). **[source]** All of
#22's uinput, Xvfb and spacebar apparatus exists to drive the *real* DS,
which this tier does not have and does not want.

It needs no extra build configuration either.
`WPIJavaExtension.configureTestTasks` calls `configureExecutableNatives`,
which sets `LD_LIBRARY_PATH` and `java.library.path` from the extracted
desktop natives **[source]**, fed by the template's unconditional
`nativeRelease wpi.java.deps.wpilibJniRelease(wpi.platforms.desktop)`
(`build.gradle:97`). **[source]**

It drives **ADR 0010's scripted `@Utility` drive-a-path opmode** — one
artifact, two audiences. A test-only opmode would be a second thing to
keep working, and its passing would prove nothing about the opmode
students actually run.

Assertions are deliberately loose, and that is the point:

- the opmode is discoverable by name in `getOpModeOptions()`;
- enabling it and stepping `SimHooks` for a fixed number of periods
  displaces the sim pose past a threshold;
- nothing throws;
- no `Alert` is active at `HIGH`.

**[decided]** Numbers stay in Tier 1. This test is the only thing in the
project that checks the **wiring** — opmode registration, bindings, the
scheduler, telemetry, and ADR 0012's requirement that odometry update
*before* the scheduler runs. A tight assertion here would buy nothing
Tier 1 does not already have, and would go red every time the physics
changed.

### Both tiers are live, and the blocker that held them is gone

#19 and #23 both recorded their headless tiers as dormant, and #25
confirmed the blocker was not the year gate but `SparkSim`, whose
`MovingAverageFilterSim` field imports a `Pair` that moved out from
under it. **[source, via #25]**

ADR 0010 removed it. Because the loops close on the SPARK (ADR 0008),
`Drive.updateSim()` **models** the onboard loop rather than calling
`SparkSim.iterate()`, so the class is never named and — class
resolution being lazy — never loaded. **[decided, via #29]** Tier 2 and
`sim-hitl` are buildable today, and neither waits on REVLib republishing
anything.

### The `--add-opens` departure — the stock template cannot run a v3 test

ADR 0003 locked *the stock template, unmodified*. That cannot hold, and
this is the first named departure from it.

`WPIJavaExtension.configureTestTasks(Test)` does exactly two things:
`configureExecutableNatives(...)` and `testLogging(...)`. It adds no JVM
arguments at all. **[source]** The same plugin adds
`--add-opens java.base/jdk.internal.vm=ALL-UNNAMED`,
`--add-opens java.base/java.lang=ALL-UNNAMED` and
`--enable-native-access=ALL-UNNAMED` in
`WPIJavaExtension.configureSimulationTask` and again in
`org.wpilib.gradlerio.deploy.systemcore.WPILibJavaArtifact`.
**[source]** The `test` task gets none of them.

So the departure is a `test { jvmArgs '--add-opens', … }` block, and it
carries a comment saying why, under `CLAUDE.md`'s ordinary rule — *a
workaround for someone else's bug* — and **not** the upstream-defect
exemption, since we have filed nothing against GradleRIO and therefore
have no link to cite. **[decided]** The comment names the reflective
accesses rather than the symptom, so that a later GradleRIO supplying
the flags itself makes the block deletable rather than mysterious:
Commands v3 opens `jdk.internal.vm.Continuation`,
`jdk.internal.vm.ContinuationScope` and `java.lang.Thread` through
`MethodHandles.privateLookupIn` (`Continuation.java:55, 84`,
`ContinuationScope.java:27`). **[source]** Removing
`java.base/jdk.internal.vm` and re-running kills a real Tier 1 test in
`ContinuationScope`'s static initialiser before any assertion.
**[executed, via #56]**

*Unmodified* becomes a default that **named, justified departures**
leave, each commented at its own edit exactly as above. Three are
expected: this block, the analyzer wiring, and the WPILib version line.

### Analyzers: allwpilib's configs minus two rules, `src/main` only

Run **checkstyle, PMD and spotbugs**, seeded from allwpilib's own
`styleguide/` — 647 lines across `checkstyle.xml`, `pmd-ruleset.xml` and
`spotbugs-exclude.xml`, including 62 PMD rule exclusions and 197 lines
of spotbugs exclusions. **[source]** Those exclusions encode real
experience with WPILib-shaped Java and are worth inheriting whole.

**Minus `JavadocMethod` and `MissingJavadocMethod`**
(`styleguide/checkstyle.xml:255-270`). `MissingJavadocMethod` is
`scope=public`, `minLineCount=2` **[source]** — it fails any public
method over two lines without Javadoc, which contradicts ADR 0003's
*public methods get no Javadoc* on the first file we write. That is a
known contradiction, not something to discover empirically.

Two conflicts that were expected turn out not to exist: there is no
`m_` member-prefix rule, and PMD's `PublicFieldNamingConvention` only
demands lowerCamelCase, which ADR 0003's public mechanism fields on
`Robot` already satisfy. **[source, via #19]**

**Static analysis runs on `src/main` only.** On test code it is
high-noise and low-value, and the first thing it would flag is ADR
0006's hand-copied `CommandTestBase` — code taken from upstream
deliberately and not to be edited. **[decided]** Spotless formatting
covers both `main` and `test`.

### Spotless is checked, never applied

`spotlessCheck` with `googleJavaFormat()`. CI never pushes a commit to a
student's branch. **[decided]**

This is the one place a non-correctness failure may block, and it earns
that because the fix is mechanical and total: `./gradlew spotlessApply`.
The Step Summary names that command every time.

### A failing sim test uploads its WPILOG

On failure only, as a workflow artifact. **[decided]**

This is the highest-leverage line in the ADR. `expected displacement >
1.0, was 0.03` tells a student almost nothing; the same failure with a
downloadable WPILOG is something they open in AdvantageScope or hand to
#16's CLI, which was built to answer *did it go where it was told*.
That CLI is deliberately generic, so a CI log is just another WPILOG to
it — no new machinery.

It also makes CI the **first consumer of ADR 0005's log design**, which
is a real check that the design works rather than a claim that it does.

ADR 0005's *`DataLogManager` pauses without a DS* trap does not fire
here, because Tier 2 calls `setDsAttached(true)`.

### Failure reporting

JUnit XML → **inline PR annotations at the failing line**, plus a GitHub
Step Summary that always names the local reproduction command.
**[decided]**

Annotations land on the diff the student is already reading, and *you
can run this on your laptop* is the single most valuable thing CI
teaches. Fork PRs would get a read-only token and lose annotations, but
every collaborator has push access, so PRs come from in-repo branches.
**[executed — GitHub API, 2026-08-26]**

### Dependencies float `2027.+`, and pin at alpha-7

Resolve against `frcmaven/development` with a floating `2027.+`.
**[decided]**

Pinning an exact development build is not durable: the repository is a
rolling window about nine versions and four days deep, so a build that
resolves today is evicted within the week, leaving CI red on a
dependency-resolution error with no earlier version to fall back to.
**[source, via #19]** Floating trades that for occasional breakage
caused by upstream — which is a cost we accept, because the alternative
is a red we cannot fix at all.

**Pinned to alpha-7 since 2026-09-06**, which is what `build.gradle:4`
names. The prediction that made this a decision — six vendordep JSONs
on allwpilib `main` already declaring `2027_alpha7` — is spent.
**[source]**

A daily scheduled run of `main` on the gated workflow was considered —
it would cheaply separate *WPILib broke us* from *your PR broke us* —
and dropped as machinery for a condition that is about to end. The
hardware nightly, which exists for a different reason, stays.

### The year-gate edit, and what inverts it

The gate is `WPIVendorDepsExtension.validateDependencies()` in
`org.wpilib:native-utils`, and it compares each `vendordeps/*.json`'s
`wpilibYear` against one project-wide value whose convention GradleRIO
sets, as of alpha-7, to the string `2027_alpha7`
(`org.wpilib.gradlerio.wpi.WPIExtension`). **[source]**

| vendordep | declares | vs `2027_alpha7` |
|---|---|---|
| REVLib `2027.0.0-alpha-6` | `2027_alpha5` upstream | **rejected**, so edited |
| Phoenix 6 `26.50.0-alpha-1` | `2027_alpha5` upstream | **rejected**, so edited |
| photonlib `v2027.0.0-alpha-2` | `2027_alpha5` upstream | **rejected**, so edited |
| `CommandsV3.json` from allwpilib `main` | `2027_alpha7` | passes |

**[source — `vendordeps/*.json`, `~/dev/allwpilib/commandsv3/CommandsV3.json`]**

So the gate no longer blocks Commands v3 — every field in that file is
upstream's again, the importer's indentation aside. It blocks all three
third-party vendordeps instead, and the fix is one word in each:
**`REVLib.json`, `Phoenix6-26.50.0-alpha-1.json` and `photonlib.json`
carry `2027_alpha7`**, edited up from the `2027_alpha5` their vendors
publish. The edits are checked in, `git diff` shows them, and the `jar`
task already copies `vendordeps/` into the deployed jar under
`backup/vendordeps` (`build.gradle:154`). **[source]**

Note what the edit now asserts. Editing `CommandsV3.json` down claimed
nothing: the artifact it names is built from the same checkout GradleRIO
was, so the year was the only thing out of step. Editing a third-party
JSON up claims that a binary compiled against alpha-5 links against
alpha-7, and that is a compatibility the tests have to carry rather than
a formality — ADR 0015 exists because one of those three did not.

**The revert condition is still the string itself, and it fires in both
directions.** Each vendor that republishes against alpha-7 takes its own
file back to untouched; the next GradleRIO bump puts every file that has
not moved back out of step. No separate note anywhere — the gate throws
at plugin-apply time with a message naming the year, from
`WPIExtension`'s own constructor, before any build script line can reach
`wpi.wpilibYear` and before any task runs **[source]**, so there is
nothing to bypass and CI catches an overwritten JSON loudly and
immediately. A second copy of a fact `git diff` already shows is a
second copy that can drift.

### The bench Pi is a second workflow, and it is never a required check

**Separate workflow. Never required. Blocks nothing, ever.**
**[decided]**

A hardware job inside the gated workflow would make merging to `main` —
and #18's reviewer running at all — conditional on one Raspberry Pi
being powered on.

**Bench unreachable → skip, neutral, not red.** A job that reddens
because a Pi is off teaches students to ignore CI, and that lesson does
not stay confined to the job that taught it. Only a *reached* box that
fails an assertion goes red.

Triggers: push to `main`, nightly, and manual dispatch. Public repo plus
self-hosted runner means the hardware jobs gate on trusted-branch pushes
only, never `pull_request` from a fork, **and** the repo setting
requiring approval for outside-contributor runs — both, not either.
**[decided]**

Reporting is **Step Summary only**, naming the failing assertion and the
remedy sentence, plus `journalctl -u robot -n 500` uploaded as an
artifact on failure. There is no PR to annotate, and a job that cannot
block anything has not earned a second delivery mechanism.

### Job 1 — `real-hal-boot`, and it is four assertions

Deploys the **real artifact** (`linuxsystemcore`) against an **empty CAN
bus**, waits 30 s, and asserts:

1. **`systemctl show robot -p NRestarts` unchanged.** Since the MRC ABI
   abort is a `SIGABRT` under `Restart=always`, `RestartSec=3`
   **[source — `docs/research/systemcore-deploy.md:571`]**, one integer
   catches ABI mismatch and every startup exception, with no log
   parsing. The property is supported on the BusyBox image and read
   `NRestarts=0` after 20 hours up. **[executed, via #10]**
2. **`ActiveState=active`** — catches a clean exit.
3. **Journal contains `Robot program startup complete`**
   **[executed — `docs/research/systemcore-smoketest.md:164`]** — catches
   a hang before the loop starts.
4. **Journal contains no `MRC API version mismatch`.** Redundant with
   (1), kept anyway: it is a `grep` for one literal string, and it is
   the whole difference between a student reading `status=134/n/a` and
   reading *the image and the library disagree*.

It is the real jar rather than a probe program on purpose. A probe
proves the image and the JVM; the real jar additionally proves fat-jar
packaging, REVLib and Phoenix aarch64 native loading, Java 25, the
`--add-opens` flags on the deploy path, and opmode scanning — the layer
with no other coverage anywhere in this set.

Four numbers go into the Step Summary and **gate nothing**:

| metric | source | #10 baseline |
|---|---|---|
| RSS | `/proc/<pid>/status` | 83 MB |
| Thread count | `/proc/<pid>/status` | 36 |
| `SCHED_RR` threads and priorities | `chrt` | exactly two — 50 (CAN), 40 (Notifier) |
| boot → `startup complete` | journal timestamps | — |

**[measured — `docs/research/systemcore-smoketest.md:212, 228-232`]** RSS
creep, or a third `SCHED_RR` thread appearing, is exactly the kind of
regression worth a human eye and not worth a threshold.

### The nightly is designed to redden on its own, and that is the detection

The build floats `2027.+`; the bench is pinned to whatever image was
last flashed. Six MRC API revisions landed in two months. **[source, via
#10 and #23]** So within days of any `mrclib` bump the floating build
stops matching the flashed image and the nightly goes red. The remedy is
to walk over and reflash.

**That red is the whole reason this trigger was chosen.** The ABI breaks
when *the image* changes, not when our code does, and a push trigger
structurally cannot catch that. It is designed behaviour, not flakiness,
and it is harmless precisely because the job blocks nothing.

Pinning the hardware job's WPILib to match the image was considered and
rejected below: it would make the job assert that two things we pinned
together are still pinned together.

### Job 2 — `sim-hitl`, a `linuxarm64` sim build on the same Pi

The wanted end state — the robot drives around in sim on real hardware —
is reachable, but not by forcing a flag.

**`isSimulation()` cannot be overridden on a deployed program.**
`RobotBase.isSimulation()` → `getRuntimeType()` →
`HALUtil.getHALRuntimeType()` (`RobotBase.java:307-317`), a native call.
The setter, `SimulatorJNI.setRuntimeType(int)`, is implemented only in
`hal/src/main/native/cpp/jni/simulation/SimulatorJNI.cpp:130` — sim HAL
only. **[source]** On the real HAL the symbol does not exist, and even
if the value were forced, the vendor sim state objects write into
sim-HAL structures the real HAL does not have. It is not a flag, it is a
different `.so`.

The route that works is to deploy a **`linuxarm64` sim build** to the
Pi. `hal-cpp` publishes `linuxsystemcore` (real HAL + mrclib) **and**
`linuxarm64` (sim HAL) side by side, and
`halsim_ds_socket-…-linuxarm64.zip` is published beside them; the sim
plugins are excluded from SystemCore builds by an explicit guard
(`~/dev/allwpilib/simulation/halsim_ds_socket/build.gradle:1`), which is
what makes `linuxarm64` the *sim* artifact rather than a cross-compiled
real one. **[source, via #23 — artifact listing at
`2027.0.0-alpha-6-370-gb448d64f3`; the guard read locally]**

So the Pi runs the real deploy artifact as a simulation: real aarch64,
real PREEMPT_RT kernel, real JDK 25, real Notifier scheduling under real
contention, physics loop closed, nothing plugged in — driven by the real
Driver Station from a second box, with #22's Xvfb, uinput keyboard,
spacebar gate and `xwininfo`-derived click targets.

It is a second job rather than a bigger first one because the sim build
links **no `libMrcLib`, no real HAL and no vendor CAN natives** — none of
what Job 1 exists for. And Job 1 can never enable anything. Neither
subsumes the other.

What Job 2 gets that Job 1 cannot have is a run that is not over in ten
seconds. **The loop-time regression assertion belongs here**, as
**deltas against a stored baseline, never absolute milliseconds** — a
regression detector, not a budget check, which stays correct if real
SystemCore silicon replaces the Pi later. **[decided]**

It runs. The build stages from `simHitlStage`, ships as a tarball, and
runs in the foreground of an open ssh; `halsim_ds_socket` loads, the
program reaches `Robot program startup complete`, and 45 s of
`/Telemetry/Robot/LoopDelta` comes back at 200 Hz — p50 5.000 ms, p95
5.026 ms, p99 5.059 ms across two runs, which is what
`.github/bench/sim-hitl-baseline.env` now carries. **[executed, via
#101]**

Two halves of the paragraph above were wrong, and running it is what
said so. The WPILOG does **not** need a DS to survive: those 44 s were
recorded with none attached. And the **enabled** loop is not measurable
at all today, because the Driver Station will not attach to a simulation
on another machine — the next section. So the assertion is against the
**disabled** loop, through the same baseline mechanism, and `sim-hitl`
switches to the enabled numbers on its own the day a DS can attach.

### The Driver Station only drives a local simulation

The Driver Station finds a *simulated* robot only on the box it is
running on. **[executed, via #101]** Against the real robot it discovers
the Pi and connects over TCP 1740, having first touched 6810 and 5810;
against a simulation on its own machine it reports `robotIp 127.0.0.1`
and drives it; against the bench simulation it reports `robotIp 0.0.0.0`
and opens nothing at all — with the sim owning 6810 and 5810 on the LAN,
1740/1741 proxied onto it, the sim's loopback UDP doorbell bridged in
both directions, and all four ports tunnelled onto the runner's own
loopback.

Two facts about that path are read rather than guessed at.
`halsim_ds_socket` no longer implements the DS protocol itself: it calls
`MRC_SimSystemServer_Initialize` and `ForceDsInstance(GetMrcLibDs())`
(`simulation/halsim_ds_socket/src/main/native/cpp/main.cpp:195-216`) and
hands the link to mrclib, which binds loopback. And nothing on that path
gives the simulation an identity: `MRC_SimSystemServer_SetTeam` exists
in `mrclib/SimSystemServer.h` and the extension never calls it, while
the DS holds `TeamNumberRequired`. **[source]** Which of the two the
Driver Station is actually refusing on is **[unverified]** — what is
measured is that it refuses.

So `sim-hitl` asserts on the disabled loop and the harness sits behind
`DS=on`, built and unused. What is missing is upstream's: either a
Driver Station that can be pointed at an address, or an X server on the
Pi so the arm64 Driver Station runs beside the sim. Neither is ours to
add, and neither is worth a workaround that pretends the DS is there.

### Two jobs, one physical Pi, and they cannot run concurrently

Job 1 deploys a real-HAL jar and Job 2 deploys a sim jar to the same
box. Serialise them. **[decided]** This is a scheduling constraint the
second map inherits, and it is the reason the bench workflow's
concurrency group is the *runner*, not the branch.

### The reviewer keys off this workflow

#18's PR review agent triggers on `workflow_run` against the gated
workflow, gated on `conclusion == success`, so it does not spend review
on defects a build catches in seconds. `workflow_run` was chosen over a
label gate for security: it executes the workflow definition from
`main`, so a student with write access cannot edit the reviewer's
workflow in their own PR and print the API key. **[decided, via #18]**

Its remit is **what CI structurally cannot check** — the 2027 hazards
that compile clean and fail on the field: `Rotation2d`'s `[-0.5, 0.5]`
against REV's `[0, 1)`, the one-argument `toWheelAccelerations()`,
cleanup in a `finally` block instead of `whenCanceled()`, `for(;;)` in a
coroutine body — plus the comment rule. It **reads `CLAUDE.md`** for
those rather than carrying them in its prompt, so it does not become a
second knowledge store that drifts.

Explicitly not its job: formatting (spotless owns it), anything a test
covers, or general code-quality opinion.

## Consequences

- **`main` gets branch protection for the first time, and every
  collaborator loses the ability to push to it.** That is a real change
  in how six people work, and the per-event branch is the thing that
  makes it survivable. If the escape hatch is ever used as a person
  rather than a branch, the protection has failed.

- **CI will sometimes be red through no fault of anyone in this repo.**
  Floating `2027.+` guarantees it. The mitigation is not machinery, it
  is the Step Summary saying which of the two kinds of red this is — a
  dependency-resolution failure names itself.

- **ADR 0003's *unmodified template* becomes *unmodified except where
  named*.** Three departures are already known. Each is named at its
  own edit, and `git diff` against the generator's output is the list.

- **ADR 0005's log becomes a tested artifact rather than a described
  one.** The first thing that reads a WPILOG written by this project is
  CI, and #16's CLI is what reads it. A schema nobody consumes is a
  schema nobody has checked.

- **ADR 0010's vendor-free seam is what makes Tier 1 possible at all.**
  Without it there is no tier that can hold a tight number, because
  every assertion would need a HAL under it.

- **The analyzer set is inherited, not designed, and the bill comes due
  on the first real PR.** 647 lines of someone else's rules against zero
  lines of our Java is a bet. The triage moment is named under *Open*
  rather than pretended away.

- **A hardware failure now has a defined blast radius: nothing.** The
  bench workflow cannot block a merge, cannot block the reviewer, and
  cannot make a student wait. That is what buys the right to run a
  nightly that is expected to go red.

- **Two of the four things Job 1 could have asserted are unassertable,
  and both for the same reason.** No DS attached means no alerts and
  ~10 s of log. The job is smaller than the ticket that proposed it, and
  the shrinkage is evidence-driven rather than a scope cut.

- **The second map inherits a rig, not a design.** Provisioning the
  runner, the second box, the workflow YAML and the input harness is
  execution. This ADR decides what runs and what it asserts.

## Traps

- **A Tier 2 test must call `HAL.initialize()` before it touches
  `SimHooks`.** The timing hooks lock a mutex the HAL creates, so
  `SimHooks.pauseTiming()` in a JVM that has not initialised it
  **segfaults rather than throwing** — `SimulatorJNI.pauseTiming` on
  `pthread_mutex_lock`. **[executed]** It hides easily: a suite where
  some earlier class happened to load the HAL passes, and the same test
  run alone takes the JVM down with SIGSEGV. Run every new Tier 2 test
  on its own once, before trusting a green suite.

- **Selecting an opmode on the simulated Driver Station takes two calls,
  and using one silently selects nothing.** `DriverStationSim` keeps the
  robot mode and the opmode hash as **separate fields**, and the control
  word is assembled from both (`ControlWord.setOpModeId`, masking
  `ROBOT_MODE_MASK | OPMODE_HASH_MASK`). **[source]** Calling
  `setOpMode(option.id)` alone leaves the mode bits clear, so
  `0x03FFFFFFBEE3CD99` goes in and `0x00FFFFFFBEE3CD99` comes back — an
  id that was never registered. **[executed]** `OpModeRobot` reports the
  miss through `DriverStationErrors.reportError(..., false)`, which a
  headless test never sees, so the robot simply enables with no opmode
  and the only symptom is a pose that never moves. Always pair it:

  ```java
  DriverStationSim.setRobotMode(option.getMode());
  DriverStationSim.setOpMode(option.id);
  ```

- **A SPARK claims its CAN id for the life of the JVM.**
  `SparkLowLevel`'s constructor calls `c_Spark_RegisterId` and throws
  `IllegalStateException: A CANSparkMax instance has already been
  created with this device ID` on the second instance
  (`SparkLowLevel.java:261-267`). **[source]** So a `@BeforeEach` that
  constructs a mechanism holding SPARKs passes its first test and dies
  on every one after it. **[executed]** Build the mechanism once for the
  class and reset the scheduler between tests instead. That reset is not
  total, and the gap is worth knowing: `cancelAll()` drains the queued
  and running commands and `getDefaultEventLoop().clear()` drops the
  trigger bindings, but a default command registered on a mechanism
  lives in a map neither touches (`Scheduler.java:109`) and is
  rescheduled on the next `run()` (`Scheduler.java:1194`). **[source]**
  A test that registers one leaves it running for the rest of the class,
  so it had better be `LOWEST_PRIORITY`.

- **The HAL, the alert table and the data log are process-wide and
  start once.** A Tier 2 class that constructs a real `Robot` claims
  `DataLogManager` for the rest of the JVM, and the next class wanting
  its own log gets the first one's directory. The `test` task therefore
  sets `forkEvery = 1`. **[executed]** A new global-state dependency is
  a reason to check that the fork is still there, not a reason to
  reorder tests.

- **Neither the stock template nor GradleRIO's test-task configuration
  adds `--add-opens`, so every Commands v3 test dies before its first
  assertion.** `WPIJavaExtension.configureTestTasks(Test)` calls
  `configureExecutableNatives` and `testLogging`, and adds no JVM
  arguments. **[source]** `ContinuationScope`'s static initialiser calls
  `MethodHandles.privateLookupIn`, which fails without the module open:

  ```
  java.lang.IllegalAccessException: module java.base does not open
      jdk.internal.vm to unnamed module
      at java.lang.invoke.MethodHandles.privateLookupIn(MethodHandles.java:268)
  ```

  **[executed, via #19]** Every v3 test is a mechanism test, so this is
  *every* test in ADR 0006's style. The same plugin adds the flags in
  `configureSimulationTask` and in
  `org.wpilib.gradlerio.deploy.systemcore.WPILibJavaArtifact`
  **[source]**, which is what makes this read as an oversight rather
  than a decision — and is also why it may quietly disappear in a later
  GradleRIO, taking the justification for our departure with it.

- **With no Driver Station attached the robot's WPILOG stops after about
  ten seconds.** `DataLogManager`'s thread waits on the DS data event
  with a 0.25 s timeout and calls `m_log.pause()` once
  `timeoutCount > 40` — the comment says *"pause logging after being
  disconnected for 10 seconds"*
  (`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java:371-386`).
  **[source]** ADR 0005 wraps `DataLogManager.getLog()`, so this fires
  for us. Boot fits inside that window. *Survive* does not, and neither
  does anything Job 1 might have wanted to measure over time.

- **`Alert` is invisible on the bench, for the same reason, and that is
  why the hardware job asserts no alerts at all.** `Alert` is
  JNI-backed — `WPIUtilJNI.createAlert` / `setAlertActive` /
  `setAlertText`, with levels from `AlertDataJNI`
  (`wpiutil/src/main/java/org/wpilib/util/Alert.java:41-55, 97-135`)
  **[source]** — and the path out of the process is DS comm. With no DS
  attached, alerts reach nothing off-robot. ADR 0004's designated fault
  surface simply does not exist in Job 1. There is a second, independent
  reason as well: with an empty CAN bus, ADR 0004's retry-then-alert
  path throws on every run *by design*, so any alert assertion would sit
  permanently red. Two reasons, one conclusion — do not add one back
  because the other looks fixable.

- **`isSimulation()` cannot be overridden, so there is no shortcut to
  `sim-hitl`.** `SimulatorJNI.setRuntimeType(int)` is implemented in
  `hal/src/main/native/cpp/jni/simulation/SimulatorJNI.cpp:130` — sim
  HAL only **[source]** — and on the real HAL the symbol is absent. The
  reason `sim-hitl` is possible at all is unrelated to the flag:
  `hal-cpp` publishes `linuxarm64` sim natives beside
  `linuxsystemcore`, and the sim plugins are guarded out of SystemCore
  builds by `if (project.hasProperty('onlylinuxsystemcore')) { return; }`
  (`~/dev/allwpilib/simulation/halsim_ds_socket/build.gradle:1`).
  **[source]** Anyone who tries to make the deployed real-HAL jar
  "simulate" is chasing a `.so`, not a boolean.

- **An enabled opmode's `periodic()` runs outside the watchdog, so
  nothing off-robot observes the loop we care about.**
  `OpModeRobot.startCurrentOpMode` registers `m_currentOpMode::periodic`
  into the same callback queue as `loopFunc` (`OpModeRobot.java:738`
  against `:530`), while `m_watchdog` is reset at the top of `loopFunc`
  (`:610`) and disabled at its end (`:719`). **[source]** An overrun in
  opmode code produces no alert, no epoch dump and no signal anywhere.
  Combined with the DS being blind to robot timing, timing regression
  detection is not *read a number the platform publishes* — it requires
  ADR 0005's own signals. That is why the loop-time assertion is Job 2's
  and not Job 1's.

- **`busybox` is setuid on the SystemCore image, so `nohup` and
  `setsid` silently drop `LD_PRELOAD`.** `/bin/busybox` is
  `-rwsr-xr-x root root` and both are applets of it, so the loader
  strips `LD_PRELOAD` and `LD_LIBRARY_PATH` from their environment and
  from everything they exec. **[executed, via #101]** Backgrounding a
  robot program that way takes ADR 0015's REVLib shim with it, and the
  failure is a symbol-lookup abort a long way from the cause. `sim-hitl`
  runs the program in the foreground of an ssh that stays open instead.

- **The sim wants three of the image's own services out of the way.**
  `mrccomm.service` holds UDP 1110, and `limelight_diagnosticsprocess`
  holds the system NetworkTables server on 6810 that
  `MRC_SimSystemServer_Initialize` wants to be. **[executed, via #101]**
  Leaving them up costs more than a warning: a program still registered
  on 6810 makes the next one abort with `Multiple user programs
  detected` and `terminate called without an active exception`. Stop all
  three, and start all three again however the job ends — **one at a
  time, and not before the last program is gone**. Starting them
  together puts `robot.service` into exactly that abort, at
  `RestartSec=3`, which is a bench left worse than the job found it.
  **[executed, via #101]** `sim-hitl` reads `systemctl is-active robot`
  after its own cleanup for that reason, and says so in the summary.

- **Writing to the DS's NetworkTables server corrupts every other reader
  on it.** Port `6767` is a one-way mirror: writes are accepted, stored,
  shown to other clients, and never propagate to the DS or the robot —
  including to AdvantageScope and any CI reader.
  **[executed — `docs/research/ds-headless-control.md:23-24, 73-120`]**
  Any harness connects **read-only** and never publishes. Control is
  synthetic input; NT is observation.

- **The DS rotates its own wpilog mid-session.** One 22-minute run
  produced nine overlapping files.
  **[executed — `docs/research/ds-headless-control.md:430`]** They live
  at `~/.local/share/FIRSTDriverStation/Logs/`, not the `~/.firstds` the
  published docs name. **[executed — `:63, :403`]** A harness that opens
  *the* DS log has already picked the wrong one.

- **The DS boots to an undocumented spacebar gate that never times out
  and blocks enable.** *"The spacebar is your Emergency Stop. Please
  press it to verify functionality."* — in none of the published docs.
  **[executed — `docs/research/ds-headless-control.md:34, 188`]** Any
  `sim-hitl` harness sends Space first, before anything else.

- **Re-importing a *third-party* vendordep from its vendor URL breaks
  the build.** It reverts `wpilibYear` to `2027_alpha5` and the gate
  refuses to configure the project. `CommandsV3.json` is the one file
  this is now safe on: upstream's copy declares `2027_alpha7` and names
  the artifact `commandsv3-java`, which is what resolves. Both halves of
  this trap used to point at that file and both are spent — the artifact
  was `commands3-java` through alpha-6. **[source — diff against
  `~/dev/allwpilib/commandsv3/CommandsV3.json`; the resolved artifact in
  the Gradle cache]** The gate failure is loud and names the year. Take
  the checked-in files as the source of truth.

- **`def includeDesktopSupport` gates nothing.** It is declared at
  `build.gradle:78` and referenced exactly nowhere in the file.
  **[source]** Flipping it to fix a desktop-natives problem changes no
  behaviour at all; the natives come from the unconditional
  `nativeRelease` lines.

## Open

- **The `linuxarm64` sim build runs on the image; the Driver Station
  will not drive it.** Both halves are executed now rather than read:
  `linuxarm64`'s `libwpiHal.so` exports 460 `HALSIM_` symbols and links
  no `libMrcLib`, the program reaches its loop on the bench and logs at
  200 Hz, and the Driver Station attaches to no simulation but the one
  on its own machine. Job 2 exists; its enabled half does not.
  **[executed, via #101]** What is still open is upstream's: a Driver
  Station that can be pointed at an address, or an X server on the Pi so
  the arm64 build runs beside the sim. *Unblocked by* neither of ours,
  and not worth faking in the meantime.

- **Xvfb runs the Driver Station, and the click-target question is
  gone.** The DS comes up headless under `Xvfb :99` with software
  rendering — window, HTTP server and all — and reads a uinput keyboard
  the harness creates for it. Selecting an opmode needs no clicking:
  the id is `(mode << 56) | (name.hashCode() & 0x00FFFFFFFFFFFFFF)`,
  which `.github/bench/ds-harness.py` computes and writes into the DS's
  settings file, checked against a value the DS itself had stored.
  **[executed, via #101]** Both were open here and in #22; neither is.

- **The analyzer set has never been run against our Java, because there
  is none.** Inheriting 647 lines of rules is a bet that WPILib's
  exclusions match our code. **[unverified]** *Unblocked by* the first
  PR with substantial code, which **triages the report, and records
  every rule removed there with a reason.** Naming the moment is the
  substitute for running it now.

- **The four-minute budget is a target, not a measurement.** Nothing has
  been timed: no Gradle run, no native download, no test suite.
  **[unverified]** *Unblocked by* the first green run. Past roughly six
  minutes the response is to split the job.

- **What `sim-hitl` asserts beyond loop time is not settled.** Job 2 has
  a DS, a surviving log and an enabled robot, which is strictly more
  than any other tier — and the temptation is to move Tier 1's numbers
  onto it. They should not move: it queues on one Pi. What *only* it can
  check, past the timing deltas, is open. **[unverified]** *Unblocked by*
  the job existing.

- **Whether Job 2 should be driven by randomised input is open, and it
  is fog rather than a rejection.** Input-driven testing was ruled out
  while it needed a rig of its own; Job 2 removes that premise — the
  same Pi, the same DS, #22's same uinput gamepads, and a simulated
  chassis that cannot be damaged. What is unsettled is **what to
  randomise, and what a failure would mean**; neither can sharpen before
  there are mechanisms to exercise. **[unverified]** *Unblocked by* the
  first hardware workflow actually running. The **hardware** version —
  randomised input against a real chassis — stays out of scope.

## Rejected

### A hardware stage inside the gated workflow

It would make merging to `main` — and #18's reviewer running at all —
conditional on one Raspberry Pi being powered on. *Do not re-raise*
while the hardware is a single box.

### Making the hardware workflow required, or reddening it when the bench is unreachable

A job that goes red because a Pi is off teaches students to ignore CI,
and that lesson does not stay confined to the job that taught it.
Unreachable is a skip.

### Pinning the hardware job's WPILib to match the flashed image

It would make the job assert that two things we pinned together are
still pinned together — which is true by construction and tells us
nothing. The floating build going red against a stale image *is* the ABI
detector.

### A hand-copied `CommandTestBase` to keep Tier 1 free of the HAL

Upstream's own suite escapes the HAL by overriding `OpModeFetcher`,
which is package-private (`OpModeFetcher.java:15`) **[source]** — so
copying that escape here means a split `org.wpilib.command3` package
under `src/test`, outside ADR 0003's layout, carrying a file taken from
upstream that has to be kept in sync. Declined: it buys no assertion we
cannot already write, and the natives it avoids are already on the test
JVM's path. *Re-raise only* if the HAL being up makes a Tier 1 test slow
or flaky — which is a measurement, not an opinion.

### A pre-flight ABI probe *in CI*

Reading the image's ceiling over ssh with `ctypes` on
`MRC_CheckApiVersion` was offered and declined — *"don't over engineer a
system that is going stable shortly."* Observation plus the literal
`grep` in assertion (4) gets the same sentence in front of a student,
and assertion (1) already catches the abort without parsing anything.

**Still rejected here, and built elsewhere.** #100 put the probe on the
*deploy* path instead, as `mrcApiPreflight` in `build.gradle`: CI is not
where this hazard is met, because a student clicking the VSCode deploy
button never sees a workflow. The decision above is unchanged, but one
consequence of it is. Job 1 deploys through the same `deploy` task, so
**the nightly's designed red now lands on the Gradle step rather than on
assertion (1)**. That is the better failure of the two — the exception
names both version numbers and the remedy, where `NRestarts=1` named
neither, and the Step Summary rule is satisfied by the exception text
itself. Assertion (1) keeps its other half, every startup exception that
is not an ABI mismatch, and assertion (4) keeps being the cheap literal
`grep` for the case where an image somehow gets past the preflight.

### Putting a SPARK and a Pigeon2 on the bench to make ADR 0004's config path assertable

Declined. CI does not get to depend on parts staying plugged in.

### Loop-time regression in Job 1

Three reasons compound: nothing is enabled, so the only measurable loop
is the disabled one; nothing off-robot observes the enabled loop anyway;
and the robot's own WPILOG stops after ten seconds with no DS. The
assertion moves to Job 2, where all three conditions invert.

### An assertion that the journal holds no stack trace

With an empty bus, ADR 0004's retry-then-alert path throws on every run
by design. That assertion would sit permanently red, which is worse than
absent.

### `ForceDsInstance`

Reachable — it is exported from the SystemCore `libwpiHal.so` — but it
replaces the DS *inside* the robot process, so it tests strictly less of
the stack than the path that already works. **[source, via #22]**

### NT writes to `/Dscomm/Control/ControlData` as the control path

This was the assumed route and it does not work. Port `6767` accepts
writes, propagates nothing, and silently corrupts the view for every
other reader. Control is synthetic input; NT is read-only observation.
*Do not re-raise* — this is verified, not inferred.

### An issue-opening bot or notifications for the bench workflow

A job that cannot block anything has not earned a second delivery
mechanism. Step Summary, plus a journal artifact on failure.

### Declaring REVLib's coordinates as a plain Gradle `implementation` dependency

The option #25 was written around, and it is strictly worse than it
looks. It does skip the year gate, which lives in the vendordep loader
rather than in maven. But the template wires vendor natives through
`wpi.java.vendor.jniRelease(...)` (`build.gradle:91, 98`) **[source]**, and
REVLib ships **three** — `REVLib-driver`, `RevLibBackendDriver`,
`RevLibWpiBackendDriver`, each valid for `linuxsystemcore` as well as
the desktop platforms (`vendordeps/REVLib.json`, `jniDependencies`).
**[source]** A plain maven dependency gets the Java classes and none of
the natives: `UnsatisfiedLinkError` on the device *and* on the desktop,
not merely broken simulation. Upstream's own gate message
says as much — *"Attempting to modify an existing dependency will break
at runtime, and will result in loss of support from the WPILib team."*
*Do not re-raise* without new evidence about the natives.

### Setting `wpi { wpilibYear = ... }` to move the gate

Half of this option — editing the third-party vendordeps up — is the
decision now, and it arrived by upstream's hand rather than ours. The
half still rejected is overriding the project-wide year from
`build.gradle`, and it is rejected harder than #25 rejected it.
`wpilibYear` feeds `wpilibHome`, so an override repoints the
install-folder path at a directory that does not exist **[source, via
#25]** — and, on alpha-7, it does not reach the gate at all:
`WPIExtension`'s constructor sets the year, loads the vendordeps and
validates them before any line of `build.gradle` can assign to it.
**[source]**

### Pinning an exact `frcmaven/development` build

The window is roughly nine versions and four days deep. A pinned build
is evicted within the week, and there is no earlier version to fall back
to. **[source, via #19]**

### A daily scheduled run of `main` on the gated workflow

It would cheaply separate *WPILib broke us* from *your PR broke us*, and
it was machinery for a condition that ended when alpha-7 was tagged and
pinned. The bench nightly stays, because it detects something a push
trigger structurally cannot.

### A build matrix

It is Java. Parallel jobs each re-pay the JDK, Gradle and
native-download cost with nothing to parallelise across.

### A javadoc check

ADR 0003 made *no Javadoc* the rule, so checking it would police a rule
we do not have. The two checkstyle modules that enforce it are removed
for the same reason.

### Spotless applied rather than checked

CI never pushes commits to a student's branch. The fix is one documented
command they run themselves, and running it is the thing worth learning.

### Static analysis on `src/test`

High-noise, low-value, and the first thing it would flag is ADR 0006's
hand-copied `CommandTestBase` — code taken from upstream deliberately
and not to be edited. Spotless still covers `src/test`.

### A test-only opmode for Tier 2

A second artifact to keep working, whose passing would prove nothing
about the opmode students actually run. Tier 2 drives ADR 0010's
scripted `@Utility` opmode.

### Tight numeric assertions in Tier 2

They would duplicate Tier 1 at a hundred times the cost and go red
whenever the physics changed. Tier 2 asserts existence, absence of
throw, and a displacement threshold.

### A `Pair` shim in our own tree to unbreak `SparkSim`

Built, proven to work, and rejected — and now moot, since ADR 0010 never
loads the class. *Do not re-raise*: reintroducing `SparkSim` would
reopen ADR 0010's three defects, not just this one import.

## Source

Decided in
[#19](https://github.com/Drew-Robotics/2027beta/issues/19), which
carries the workflow shape, the trigger and gating rules, the two tiers,
the analyzer set, the failure-reporting design and the dependency
ruling; in
[#23](https://github.com/Drew-Robotics/2027beta/issues/23), which
carries the bench workflow, both jobs and the never-required rule; and
in [#25](https://github.com/Drew-Robotics/2027beta/issues/25), which
carries the year gate, the `CommandsV3.json` edit and the rejection of
the plain-maven route. The reviewer's trigger and remit are
[#18](https://github.com/Drew-Robotics/2027beta/issues/18); the bench
baseline is [#10](https://github.com/Drew-Robotics/2027beta/issues/10);
the DS control path is
[#22](https://github.com/Drew-Robotics/2027beta/issues/22).

#25's Decision 2 — that simulation waits and `Drive.updateSim()` is not
written — is superseded by ADR 0010 as decided on
[#29](https://github.com/Drew-Robotics/2027beta/issues/29). The blocker
it named was `SparkSim`, and the onboard loop is now modelled rather
than called, so the class is never loaded. Tier 2 and `sim-hitl` are
live. Nothing else in #25 changes.

The template rule this ADR departs from is ADR 0003; the log it consumes
is ADR 0005; the command style its tests are written in is ADR 0006; the
config path Job 1 cannot assert is ADR 0004; the tiers' subject matter
is ADR 0010, ADR 0011 and ADR 0012.

Research read for this ADR:
[`docs/research/ds-headless-control.md`](../research/ds-headless-control.md),
[`docs/research/systemcore-deploy.md`](../research/systemcore-deploy.md),
[`docs/research/systemcore-smoketest.md`](../research/systemcore-smoketest.md),
[`docs/research/vendordeps.md`](../research/vendordeps.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java`,
`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java`,
`wpilibj/src/main/java/org/wpilib/simulation/DriverStationSim.java`,
`wpilibj/src/test/java/org/wpilib/framework/OpModeRobotTest.java`,
`wpiutil/src/main/java/org/wpilib/util/Alert.java`,
`hal/src/main/native/cpp/jni/simulation/SimulatorJNI.cpp`,
`simulation/halsim_ds_socket/build.gradle`,
`styleguide/checkstyle.xml`, `styleguide/pmd-ruleset.xml`,
`styleguide/spotbugs-exclude.xml`,
`commandsv3/CommandsV3.json`.

In `GradleRIO-2027.0.0-alpha-6` (plugin jar, read by `javap`):
`org.wpilib.gradlerio.wpi.WPIExtension`,
`org.wpilib.gradlerio.wpi.java.WPIJavaExtension`,
`org.wpilib.gradlerio.deploy.systemcore.WPILibJavaArtifact`.
