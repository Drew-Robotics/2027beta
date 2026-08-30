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
JVM.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. GradleRIO
`[source]` claims were read in `GradleRIO-2027.0.0-alpha-6`, the version
`build.gradle:3` pins, from the plugin jar by `javap`; paths are given
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
- **The vendordep year gate is out of step with itself.** GradleRIO's
  project-wide requirement is `2027_alpha5` while allwpilib's own
  first-party JSONs already declare `2027_alpha7`. **[source]**
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
which the template already sets (`build.gradle:84`). **[source]** All of
#22's uinput, Xvfb and spacebar apparatus exists to drive the *real* DS,
which this tier does not have and does not want.

It needs no extra build configuration either.
`WPIJavaExtension.configureTestTasks` calls `configureExecutableNatives`,
which sets `LD_LIBRARY_PATH` and `java.library.path` from the extracted
desktop natives **[source]**, fed by the template's unconditional
`nativeRelease wpi.java.deps.wpilibJniRelease(wpi.platforms.desktop)`
(`build.gradle:74`). **[source]**

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

**Pin to alpha-7 the moment it is tagged.** It is visibly imminent: six
vendordep JSONs on allwpilib `main` already declare `2027_alpha7`.
**[source]**

A daily scheduled run of `main` on the gated workflow was considered —
it would cheaply separate *WPILib broke us* from *your PR broke us* —
and dropped as machinery for a condition that is about to end. The
hardware nightly, which exists for a different reason, stays.

### The year-gate edit, and what inverts it

The gate is `WPIVendorDepsExtension.validateDependencies()` in
`wpilibsuite/native-utils`, and it compares each `vendordeps/*.json`'s
`wpilibYear` against one project-wide value whose convention GradleRIO
sets to the string `2027_alpha5`
(`org.wpilib.gradlerio.wpi.WPIExtension`). **[source]**

| vendordep | declares | vs `2027_alpha5` |
|---|---|---|
| REVLib `2027.0.0-alpha-6` | `2027_alpha5` | passes |
| Phoenix 6 `26.50.0-alpha-1` | `2027_alpha5` | passes |
| photonlib `v2027.0.0-alpha-2` | `2027_alpha5` | passes |
| `CommandsV3.json` from allwpilib `main` | `2027_alpha7` | **rejected** |

**[source — `vendordeps/*.json`, `~/dev/allwpilib/commandsv3/CommandsV3.json`]**

So the gate never blocked REVLib. It blocks Commands v3, and the fix is
one word: **`vendordeps/CommandsV3.json` carries `2027_alpha5`**, edited
down from upstream's `2027_alpha7`. It is checked in, `git diff` shows
it, and `shadowJar` already copies `vendordeps/` into the deployed jar
under `backup/vendordeps` (`build.gradle:97`). **[source]**

**The revert condition is the string itself.** When GradleRIO publishes
an alpha-7 its convention flips to `2027_alpha7`, and the edit inverts:
`CommandsV3.json` goes back untouched and REVLib becomes the file we
edit, until REV republishes. No separate note anywhere — the gate throws
at configuration time with a message naming the year, so CI catches an
overwritten JSON loudly and immediately, and a second copy of a fact
`git diff` already shows is a second copy that can drift.

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

Deploys the **real fat jar** (`linuxsystemcore`) against an **empty CAN
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

So the Pi runs the real fat jar as a simulation: real aarch64, real
PREEMPT_RT kernel, real JDK 25, real Notifier scheduling under real
contention, physics loop closed, nothing plugged in — driven by the real
Driver Station from a second box, with #22's Xvfb, uinput keyboard,
spacebar gate and `xwininfo`-derived click targets.

It is a second job rather than a bigger first one because the sim build
links **no `libMrcLib`, no real HAL and no vendor CAN natives** — none of
what Job 1 exists for. And Job 1 can never enable anything. Neither
subsumes the other.

What Job 2 gets that Job 1 cannot have: a DS is attached, so
`DataLogManager` never pauses and the full WPILOG survives; and the
enabled loop can be measured. **The loop-time regression assertion
belongs here**, against #10's baseline, and as **deltas against a stored
baseline, never absolute milliseconds** — a regression detector, not a
budget check, which stays correct if real SystemCore silicon replaces
the Pi later. **[decided]**

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

- **Re-importing `CommandsV3.json` from allwpilib breaks the build
  twice.** Upstream's copy declares `2027_alpha7`, which the year gate
  rejects, *and* names the artifact `commandsv3-java` where the artifact
  that actually resolves is `commands3-java`. **[source — diff against
  `~/dev/allwpilib/commandsv3/CommandsV3.json`; the resolved artifact in
  the Gradle cache]** The gate failure is loud and names the year. The
  artifact-id failure is a resolution error that names neither. Take the
  checked-in file as the source of truth.

- **`def includeDesktopSupport` gates nothing.** It is declared at
  `build.gradle:55` and referenced exactly nowhere in the file.
  **[source]** Flipping it to fix a desktop-natives problem changes no
  behaviour at all; the natives come from the unconditional
  `nativeRelease` lines.

## Open

- **Nobody has run a `linuxarm64` sim build on the SystemCore image.**
  That `hal-cpp` and `halsim_ds_socket` publish `linuxarm64`, and that
  the guard makes it a genuine sim artifact, is verified by reading and
  by artifact listings — not by execution. That the resulting program
  runs on the image, and that the real DS drives it from a second box,
  is **[unverified]**. *Unblocked by* running it: it is the first thing
  the second map should prove, before any YAML is written. If it fails,
  Job 2 does not exist and Job 1 is the whole hardware story.

- **Xvfb has never been run against the Driver Station.** The DS is
  Avalonia/X11 and fails with `XOpenDisplay failed` when `DISPLAY` is
  unset; Xvfb is the expected answer and software rendering is known to
  work, but Xvfb is not installed on the WSL box and the step was never
  executed. **[unverified — `docs/research/ds-headless-control.md:174-179`]**
  *Unblocked by* one hour on that box. It is #22's one unproven step and
  it lands squarely on Job 2.

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

### A pre-flight ABI probe

Reading the image's ceiling over ssh with `ctypes` on
`MRC_CheckApiVersion` was offered and declined — *"don't over engineer a
system that is going stable shortly."* Observation plus the literal
`grep` in assertion (4) gets the same sentence in front of a student.

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
`wpi.java.vendor.jniRelease(...)` (`build.gradle:68, 75`) **[source]**, and
REVLib ships **three** — `REVLib-driver`, `RevLibBackendDriver`,
`RevLibWpiBackendDriver`, each valid for `linuxsystemcore` as well as
the desktop platforms (`vendordeps/REVLib.json`, `jniDependencies`).
**[source]** A plain maven dependency gets the Java classes and none of
the natives: `UnsatisfiedLinkError` on the device *and* on the desktop,
not merely broken simulation. Upstream's own gate message
says as much — *"Attempting to modify an existing dependency will break
at runtime, and will result in loss of support from the WPILib team."*
*Do not re-raise* without new evidence about the natives.

### Setting `wpi { wpilibYear = "2027_alpha7" }` and editing the vendordeps up

Two edits — REVLib and Phoenix — instead of one, and `wpilibYear` also
feeds `wpilibHome`, repointing the install-folder path at a directory
that does not exist. **[source, via #25]**

### Pinning an exact `frcmaven/development` build

The window is roughly nine versions and four days deep. A pinned build
is evicted within the week, and there is no earlier version to fall back
to. **[source, via #19]**

### A daily scheduled run of `main` on the gated workflow

It would cheaply separate *WPILib broke us* from *your PR broke us*, and
it is machinery for a condition that ends when alpha-7 is tagged. The
bench nightly stays, because it detects something a push trigger
structurally cannot.

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
