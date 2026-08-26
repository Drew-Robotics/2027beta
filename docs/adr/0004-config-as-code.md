# ADR 0004 — Config-as-code

## Status

Accepted — 2026-08-26.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Vendor
`[source]` claims were read in the versions that `vendordeps/` pins:
REVLib `2027.0.0-alpha-6` (sources jar, paths below given as
`com/revrobotics/...`) and Phoenix 6 `26.50.0-alpha-1`. An unqualified
path is a file in this repo.

## Context

Every motor controller and sensor on the robot carries settings that
decide what it does: which way is forward, how much current it may draw,
whether it coasts or brakes when disabled, what the PID gains are. Those
settings live in the device's own memory, and there are two ways to get
them there. You can plug a laptop in and type them into a vendor GUI —
REV Hardware Client, Tuner X — or you can write them in the robot code
and have the code push them at boot.

The difference shows up on the day you swap a controller in the pit
between matches. A GUI-configured robot needs someone to remember the
settings, find the laptop and retype them under time pressure. A
code-configured robot needs a new controller with the right CAN ID on
it, and the settings arrive by themselves the next time the code runs.

So config lives in code. The part that needed deciding is the part that
turns out to be expensive: **how you know it worked**, on a bus where
asking the device is not free.

## Decision

### Configuration is expressed in code, and the GUI is never the source of truth

Every device with settings gets them from this repository. The REV
Hardware Client and Tuner X stay in the pit for firmware flashing and
diagnostics — they are not where a value is *decided*, and a value typed
into either is a value that will be overwritten at the next boot.
**[decided]**

That last part is deliberate, and it comes from the reset mode below: we
configure with `kResetSafeParameters`, which returns the device to
defaults before writing our values. A SPARK someone hand-edited last
season comes back to a known state without anyone remembering that they
edited it.

### It is applied once, in the mechanism constructor

A mechanism configures its own hardware, in its constructor, which runs
inside `Robot`'s constructor (ADR 0003). Once per boot. Never in a
periodic loop, and never re-applied on reconnect. On a SPARK that is
`configure(config, ResetMode.kResetSafeParameters,
PersistMode.kPersistParameters)`.

**`kPersistParameters`** writes the settings to the SPARK's non-volatile
memory, which is what makes a controller that browns out and reboots
mid-match come back configured instead of blank. That is REV's own
stated reason for it. **[source, via #5 — REVLib docs, *Configuring a
SPARK*]** Persisting blocks communication with the device while it
happens, and firmware refuses to do it while the robot is enabled — a
distinct `kCannotPersistParametersWhileEnabled` error exists for exactly
that **[source — `com/revrobotics/spark/SparkBase.java:317-319`]**. At
boot we are disabled, so it is safe there and only there.

**`kResetSafeParameters`** gives the known baseline described above.
Note what it does not reset — see Traps.

### Success is the write. There is no readback

We do not read parameters back and compare them. The config write
reporting success *is* the verification.

The ground is cost. A SPARK has no bulk read and no `getConfig()`:
`configAccessor` issues **one blocking CAN round-trip per parameter**,
and with 84 readable parameters on each of eight SPARKs a full verify is
roughly **670 sequential round-trips on the boot path** **[source —
`docs/research/vendordeps.md` §1.2, §1.3]**. Nobody publishes what one
of those costs, and we cannot measure it: the SystemCore Pi has no CAN
hardware attached. Building the boot path on an unknown that might be
5 ms or might be 100 ms, to catch failures the write already reports, is
a bad trade. The alternative is recorded under Rejected.

**What "the write succeeded" means is vendor-specific, and the REV
answer is sharp.**

| Vendor | Call | Success is |
|---|---|---|
| REV | `spark.configure(...)` | **no exception thrown *and* `kOk` returned** |
| CTRE | `configurator.apply(...)` | `StatusCode.isOK()` **[source — Phoenix 6 `com.ctre.phoenix6.StatusCode`]** |

The rule is **confirm the write**, not *never read*. Where a vendor
gives us whole-struct readback for one round-trip we use it: the
Pigeon2's `Pigeon2Configurator` has matched `apply()`/`refresh()` pairs
that fill a config object from the device and return a `StatusCode`
**[source — Phoenix 6 `26.50.0-alpha-1`;
`docs/research/vendordeps.md` §1.7]**, and `MountPoseConfigs` — the one
that matters for us — round-trips completely. What is rejected is the
per-parameter SPARK verify, not reading as such.

REVLib splits its failures across two channels. `kTimeout` and
`kCannotPersistParametersWhileEnabled` are *returned*; every other error
— `kInvalidCANId`, `kCANDisconnected`, `kDuplicateCANId`,
`kParamInvalidValue`, `kFollowConfigMismatch` — is *thrown* as an
unchecked `IllegalStateException`
(`com/revrobotics/spark/SparkBase.java:305-326`). **[source]** Checking
only the return value, or only wrapping in `try`, catches half the
failures and misses the other half. See Traps.

### The firmware version is checked at boot

A successful write says the device accepted our settings. It says
nothing about whether that device's firmware interprets them the way we
expect — and a controller flashed to the wrong build in the pit accepts
config perfectly.

So each device's firmware version is read once at boot and compared to
the version this project expects, and a mismatch raises the same alert a
failed write does. **One read per device**, which is what makes it
affordable when reading every parameter is not.

The expected versions are the ones REV pins us to; there are no new 2027
device firmwares, so these are 2026 builds: **SPARK MAX v26.1.5, SPARK
Flex v26.1.6** **[source, via #5 — `SystemcoreTesting/REV.md`; not
re-read for this ADR]**. `SparkLowLevel.getFirmwareVersion()` returns
the packed integer and `getFirmwareString()` formats it as `vMAJOR.MINOR.BUILD`
**[source — `com/revrobotics/spark/SparkLowLevel.java:322-352`]**. On the
Pigeon2 the same check is `getVersion()`, a `StatusSignal<Integer>`
**[source — `docs/research/vendordeps.md` §8.2]**.

The firmware read inherits the sharp edge every SPARK read has: it
returns a bare value with no error code. See Traps.

### Bounded retry: five attempts, on timeout only

A `kTimeout` on a busy bus at boot is transient and worth retrying. A
`kInvalidCANId` is not — it will be just as invalid the fifth time. So:

- **REV** — retry only `kTimeout`, up to five attempts. An exception is
  never retried.
- **CTRE** — retry a non-OK `StatusCode`, up to five attempts. This
  follows CTRE's own pattern: their swerve template loops `apply()` up
  to five times **[source, via #13 — CTRE's swerve template; not re-read
  for this ADR]**.

Five is not a tuned number, it is the vendors'. The reason a retry
exists at all is social rather than technical: a transient timeout
raising a red alert every morning teaches students that alerts are
noise, and that costs more than the loop does.

### One small helper owns try/catch, retry and the alert

Nine hand-copied try/catch blocks would be the *more* complicated
outcome, not the simpler one. A mechanism constructor should read as one
legible line per device.

`Hardware` is a static utility class in `first.robot`, next to
`Constants`. It has no state, no inheritance and no registry — two
methods, one per vendor result type, called once per device:

```java
static void configureSpark(String name, Supplier<REVLibError> apply)
static void configurePhoenix(String name, Supplier<StatusCode> apply)

Hardware.configureSpark("SwerveFrontLeftDrive", () -> spark.configure(cfg, kResetSafeParameters, kPersistParameters));
```

Two names rather than two overloads, because both would erase to the
same signature — `configure(String, Supplier)`.

The REV body is where the retry rule and the two failure channels meet,
which is the part prose keeps getting wrong:

```java
for (int attempt = 1; attempt <= 5; attempt++) {
  REVLibError status;
  try {
    status = apply.get();
  } catch (RuntimeException e) {
    alert(name, e.getMessage());        // thrown: never retried
    return;
  }
  if (status == REVLibError.kOk) {
    return;
  }
  if (status != REVLibError.kTimeout) {
    alert(name, status.name());         // returned, not a timeout: never retried
    return;
  }
}
alert(name, "timed out after 5 attempts");
```

Four things about that:

- **Every device is guarded individually**, so one dead SPARK cannot
  take out the seven that are fine.
- **The catch is `RuntimeException`, not `IllegalStateException`.** The
  narrower type is what REVLib documents itself as throwing, and the
  helper exists so that *nothing* a device does takes out `Robot`'s
  constructor.
- **The alert is constructed only on failure, and never kept.**
  `alert(name, detail)` above is
  `new Alert(name, "Config failed: " + detail, Level.HIGH).set(true)`. An
  `Alert` is a JNI handle that only `close()` destroys — nothing
  finalizes it (`Alert.java:148-153`) **[source]** — so a fire-and-forget
  alert stays live for the rest of the program. Constructing it once, on
  the way out, is also what keeps its id unique. See Traps.
- **`Level.HIGH`**, which is what `Alert` reserves for "problems which
  will seriously affect the robot's functionality and thus require
  immediate attention" (`AlertDataJNI.java:9-13`). **[source]**

### The failure path alerts. It never blocks enabling

There is no health flag gating enable, and no device failure prevents
the robot from being driven. A robot that refuses to enable tells a
student nothing; an alert naming `SwerveFrontLeftDrive` tells them
exactly where to look. **[decided]** Gating enable behind a
hardware-healthy check is the kind of enforcement machinery this project
does not build.

### Config is per-mechanism records, with a factory method per motor role

ADR 0003 owns the record shape; this ADR confirms it survives contact
with the apply path, and adds one rule about the vendor config objects.

Shared defaults compose through **a factory method per motor role** in
`Constants`, taking only what varies, and returning a **fresh** vendor
config object every call — `driveMotorConfig(boolean inverted)`
returning a fresh `SparkMaxConfig`.

The signature is the documentation of what is shared and what varies.
Returning a fresh object is not fussiness: a REVLib config object is a
mutable sparse map **[source — `docs/research/vendordeps.md` §1.5]**, so
reusing one across four `configure()` calls aliases silently. REVLib's
own config-merging is a third mechanism to learn for no gain.

**Current limits are a safety parameter** and are never left out of a
config for convenience. **[decided]**

### Simulation runs the same path, unguarded

Config is applied in simulation exactly as on hardware. There is no
`isSimulation()` branch anywhere near it. CTRE's config values genuinely
affect sim behaviour and REV's conversion factors are read by `SparkSim`
**[source — `docs/research/vendordeps.md` §4.1, §4.2]**, so config is not
decorative there. With readback dropped there is nothing left that would
have wanted a sim guard.

### Phoenix's auto-starting defaults stay on

Constructing a `Pigeon2` starts about ten background threads, a
diagnostic HTTP server costing a constant 0–5% CAN utilisation, and —
uniquely on SystemCore — `.hoot` binary logging to disk within 1–5 s of
every boot, one file per CAN bus **[source —
`docs/research/vendordeps.md` §8.4]**. Both are overridable, and we
override neither.

Turning the diagnostic server off would cost more than the CAN it saves:
**Tuner X cannot deploy a temporary diagnostic server to SystemCore**, so
using Tuner X at all requires a deployed robot program with a Phoenix
device initialised and the server running **[source —
`docs/research/vendordeps.md` §6.5]**. That is our only path to flashing
Pigeon2 firmware in the pit. Hoot logging duplicating our own telemetry
is a real objection but a theoretical one, and the library ships its own
disk-pressure handling **[source — `docs/research/vendordeps.md`
§8.4]**. Revisit if disk or CAN actually complain.

### One project constant per CAN bus

`Constants` holds one constant per physical bus, converted at each call
site: `CANBus.CAN_S0.value` for REVLib's `int busId`,
`CANBus.systemcore(0)` for CTRE's object. The two vendors' numbering
agrees for the same index **[source — `docs/research/vendordeps.md`
§3.3]**. **Never `new CANBus()`** — see Traps.

### `Robot` logs the active alert set at ~4 Hz

`Alert` never touches NetworkTables and never touches
`org.wpilib.telemetry`. It is pure JNI, and on hardware every alert is
routed into mrclib by `MRC_Alert_CreateAlert`
(`hal/src/main/native/cpp/mrclib/MrcLibAlert.cpp:133-147`). **[source]**
So nothing logs alerts for us, and unless we do it ourselves an alert
never reaches the log file at all.

They are readable, though, and on hardware, not only in simulation.
`AlertDataJNI.getAlerts()` returns `AlertInfo[]` carrying `group`, `id`,
`text`, `activeStartTime` and `level`, all plain Java types
(`AlertDataJNI.java:30-61`), and `MrcLibGetAlerts` is fully implemented
in the mrclib backend and wired in alongside the setters
(`MrcLibAlert.cpp:271-322`). **[source]**

So `Robot` polls the alert set and logs it:

```java
addPeriodic(this::logAlerts, 0.25);
```

One call, covering config failures and every alert this codebase ever
adds — no per-device boolean, no second path to keep in sync with the
alerts themselves. `activeStartTime` gives *when* an alert fired, which
is what a match analysis actually wants.

Two notes on the rate. **4 Hz is a deliberate exception to the
every-signal-every-loop rule** ADR 0005 carries. **[decided]** `getAlerts()` is an allocating JNI call
that returns every alert on the robot; alerts change on human
timescales, so polling at 200 Hz would buy precision nobody will read.
And it is written as a **period, not a loop count** — `0.25` seconds
through `OpModeRobot.addPeriodic` (`OpModeRobot.java:548-550`)
**[source]** — because ADR 0002 rules that nothing in this project is
expressed in cycles.

Robot code calls `AlertDataJNI` directly. The friendlier wrapper,
`AlertSim`, lives in `org.wpilib.simulation` **[source]**, and a
hardware path does not import a simulation class.

### A dirty deploy is an alert at `Level.LOW`, and never a block

Deploying uncommitted code is normal at a competition, and a build step
that fights it gets deleted by the second match. So the deploy is never
blocked; a dirty build raises one alert, and it reaches the log for free
through the alert-set logging above. **[decided]**

`LOW` is deliberate over `MEDIUM`: at an event this alert is up most of
the weekend, and a permanent yellow warning is how you train students to
ignore alerts — the same failure the bounded retry above exists to
avoid. `LOW` is what `Alert` reserves for "any other alerts which do not
fall under the other categories" (`AlertDataJNI.java:22-27`).
**[source]** The build-time flag it reads is part of ADR 0005's
`/Metadata` block.

### Which config values are logged: the tunable rule

**Fixed config values are not logged.** They are recoverable from the
git SHA, which the log already carries.

The rule that covers the rest: **if a value changes often enough to
matter, it is a `Tunable` — and tunables get logged.** That is one
construction rule with one mechanism, rather than a category somebody
has to police. The requirement behind it is that *the log must record
everything the SHA does not pin*: a dirty tree, a runtime-changeable
value, and robot identity are the same failure — a log you cannot
attribute to a configuration.

This commits the project to using `org.wpilib.tunable`; which values
become tunable is ADR 0005's scope question. It also hands ADR 0005 a
requirement rather than a choice: `RobotBase` wires tunables to
NetworkTables under `/Tunables`
(`wpilibj/.../framework/RobotBase.java:237`) **[source]** and #11 turns
NetworkTables capture off, so **tunables must be routed through
telemetry explicitly** or they produce exactly the unattributable log
this rule exists to prevent.

## Consequences

- **The "two places to keep in sync" problem disappears.** #5's reading
  of `SparkBaseConfig` — write-only, no getters,
  `BaseConfig.getParameter` is `protected` **[source —
  `docs/research/vendordeps.md` §1.5]** — meant a verify list could not
  be derived from the config object, so we would have had to maintain a
  separate desired-value table feeding both the setter chain and the
  comparison. That constraint existed *only* to serve readback. With
  readback gone, the record is simply the config. One place.

- **The boot path is nine config writes and nine firmware reads, not
  ~670 parameter reads.** What a `configAccessor` read costs is not a
  number this design depends on, so nothing has to be measured at
  bring-up on its account.

- **ADR 0003's layout gains one file**, `first.robot.Hardware`.

- **ADR 0005 inherits two things**: the alert set as a logged signal at
  4 Hz, and the requirement to route `/Tunables` through telemetry
  explicitly.

- **ADR 0007 inherits the apply path.** Frame periods are set through
  `SignalsConfig`, part of the same config object **[source —
  `docs/research/vendordeps.md` §1.1]**, so the frame allocation 0007
  decides is written and confirmed exactly like everything else here.
  **[decided]**

- **ADR 0008 inherits a config-shaped constraint.** The steer loop's
  gains and its feedback-sensor wiring are config values applied at
  construction like everything else; the module zero offset is not, and
  0008 says why.

- **ADR 0013 inherits alerts as the assertion surface.** A test that
  constructs a mechanism runs this same path, so a config failure in a
  test surfaces as an active alert rather than as a thrown exception. A
  test that wants to assert config behaviour asserts on the alert set.

- **Alert ids are a project-wide namespace, and the framework is already
  in it.** `OpModeRobot` constructs `"opmode-loop-overrun"` in the
  default `"Alerts"` group (`OpModeRobot.java:522-526`) and `RobotBase`
  creates alerts in a `"Tunables"` group
  (`RobotBase.java:101`). **[source]** Ours have to be unique against
  those too.

## Traps

- **`configure()` throws — and returns.** Success is **no exception
  *and* `kOk`**. `kTimeout` and `kCannotPersistParametersWhileEnabled`
  are returned; everything else throws `IllegalStateException`
  (`com/revrobotics/spark/SparkBase.java:317-323`). **[source]** Code
  that checks the return value and does not catch, or catches and
  ignores the return value, misses half the failure modes. This is the
  single highest-value implementation note in this document.

- **One unplugged SPARK in an unguarded loop kills `robotInit` and
  raises no alert at all.** The `IllegalStateException` above propagates
  out of the loop, the remaining modules are never configured, and the
  robot never reaches a state where anything could report the problem —
  the exact failure this whole design exists to prevent. **[source]**
  Guard every device individually.

- **`kResetSafeParameters` does not reset Idle Mode.** The `configure()`
  javadoc lists what survives a reset: **CAN ID, Motor Type, Idle Mode,
  PWM Input Deadband and Duty Cycle Offset**
  (`com/revrobotics/spark/SparkBase.java:291-296`). **[source]** So
  brake/coast is sticky across a reset-and-reconfigure — a controller
  left in coast by last season's code stays in coast — and it must be
  set explicitly in every config.

- **`SparkMax.configure()` can throw *after* the write succeeded.** The
  override calls `super.configure()` first, then re-validates data-port
  usage and throws `IllegalStateException` on an alternate-encoder,
  absolute-encoder or limit-switch conflict
  (`com/revrobotics/spark/SparkMax.java:93-123`). **[source]** So an
  exception does not mean the config was not applied — a second reason
  exceptions are never retried. The checks only fire once the matching
  accessor object exists, and there is no analog-sensor check, so our
  steer path does not trip it.

- **A SPARK read reports no error, so a dead device reads as a
  plausible value.** `getFirmwareVersion()` is a bare JNI call returning
  an `int` with no `REVLibError`
  (`com/revrobotics/spark/SparkLowLevel.java:322-352`) **[source]**, and the
  same is true of every `configAccessor` getter. Errors arrive through
  `SparkBase.getLastError()`, which is tracked **per thread, across all
  devices on that thread** **[source — `docs/research/vendordeps.md`
  §1.4]**. Check it immediately after the read, on the same thread, or a
  disconnected controller reports a firmware mismatch of `0` and the
  alert text blames the wrong thing.

- **A duplicate alert throws, and the key includes the level.** The
  default backend rejects a `(group, id, level)` that already exists
  (`wpiutil/src/main/native/cpp/Alert.cpp:110-136`), and the JNI turns
  that into an `AlertException` — "Alert already allocated"
  (`wpiutil/src/main/native/cpp/jni/WPIUtilJNI.cpp:45-61`). **[source]**
  On hardware the mrclib backend forwards the same already-allocated
  status (`MrcLibAlert.cpp:141-147`) **[source]**; whether *its* key
  includes the level is not visible from this tree **[unverified]**, so
  do not rely on two levels making one id distinct. Derive ids from
  mechanism and role — `"SwerveFrontLeftDrive"` — never from a loop
  counter, and construct each one exactly once.

- **`getAlerts()` returns alerts that are not active.** Every
  constructed `Alert` appears in the array whether or not anything ever
  set it; `activeStartTime == 0` is what "inactive" means
  (`AlertSim.java:52-54`). **[source]** Logging the raw array logs
  alerts that have never fired.

- **`ResetMode` and `PersistMode` are top-level `com.revrobotics` types
  in 2027.** They are no longer nested under `SparkBase`; the nested
  versions were deprecated in 2026 and are gone **[source —
  `docs/research/vendordeps.md` §2.3]**. Every 2025/2026 sample and
  every model completion will have the old nested import.

- **`new CANBus()` defaults to `can_s1`, not `can_s0`** — contrary to
  CTRE's own prose docs, and confirmed in the shipped javadoc **[source
  — `docs/research/vendordeps.md` §3.3]**. Always pass the bus
  explicitly.

- **Phoenix warnings and errors may not reach the Driver Station on
  SystemCore** — they go to stderr **[source —
  `docs/research/vendordeps.md` §6.5]**. This is why every fault of ours
  goes through `org.wpilib.util.Alert` rather than relying on vendor
  surfacing.

## Open

- **Comp-vs-practice robot identity.** The sim-versus-real half of "which
  robot am I" is settled and compile-time. The comp-versus-practice half
  is not: a practice base with different offsets, different gains or a
  different module type needs the code to know which chassis it is
  running on, and **nobody has researched how a SystemCore identifies
  itself** — the roboRIO's answer does not carry over. **[unverified]**
  *Unblocked by* a verified identity source on SystemCore: a readable
  device serial, or a file placed in the deploy directory per robot. It
  drags one requirement with it, which holds whatever the source turns
  out to be — **identity must reach the log**, by the rule above that
  the log records everything the SHA does not pin. A log that cannot be
  attributed to a chassis is as unusable as one that cannot be
  attributed to a commit.

- **The firmware versions have never been read off a device.** SPARK MAX
  v26.1.5 and SPARK Flex v26.1.6 come from REV's own guidance, not from
  a controller we own **[source, via #5]**, and no `getFirmwareVersion()`
  call has executed against hardware here. *Unblocked by* the first
  bench session: read all eight and confirm the pin before trusting the
  alert.

## Rejected

### Per-parameter readback verification

Apply, read every parameter back, compare, alert on mismatch. Fully
supported by the API — 84 of 87 SPARK parameters are readable **[source
— `docs/research/vendordeps.md` §1.2]** — and rejected on cost: no bulk
read, one blocking round-trip per parameter, ~670 of them on the boot
path, with a per-read latency nobody publishes and we cannot currently
measure.

Three sharp edges made it worse than the round-trip count suggests: a
failed read is indistinguishable from a legitimately-zero value; errors
are per-thread, so a loop that checks `getLastError()` once at the end
sees only the last one; and comparisons need a float tolerance, because
values traverse `float32` natives and the ramp-rate getters apply a
reciprocal on read **[source — `docs/research/vendordeps.md` §1.4–1.6]**.

*Re-open it* if REVLib ships a bulk read or a `getConfig()`, or if
somebody measures a per-parameter read on real hardware and it is cheap.
What is rejected is the per-parameter verify; the Pigeon2's
whole-struct `refresh()` is used, and the decision says so.

### A GUI as the source of truth

The REV Hardware Client or Tuner X holding the real values, with code
reading or ignoring them. It is the workflow this ADR exists to replace:
settings that no version control sees, no review catches, and no pit
swap reproduces.

### `configureAsync()`

Returns `kOk` immediately and never throws, which sounds like it solves
the exception problem. It surfaces errors only on the Driver Station and
gives us nothing to check synchronously **[source —
`docs/research/vendordeps.md` §2.2]**, so it trades a failure we can
alert on for one we cannot see.

### Re-applying config on reconnect

Detect a device coming back on the bus and push its config again. Ruled
out on the map, and `kPersistParameters` is why: a rebooted controller
already comes back configured, so the machinery would exist to handle a
case the firmware handles.

### A health flag gating enable

Covered at the decision. A robot that will not enable is a robot nobody
can diagnose from the driver's station, and it is enforcement machinery
in place of a message.

### A per-device boolean in telemetry instead of the alert set

Logging `frontLeftDriveConfigured = true` per device. It is a second
path to keep in sync with the alerts, it grows with every device, and it
carries no timestamp. `getAlerts()` gives all of it in one call,
including alerts written by code that does not exist yet.

### Disabling Phoenix's diagnostic server and hoot logging

Covered at the decision — kept until they are demonstrably a problem,
because turning the diagnostic server off is what costs us Tuner X on
SystemCore.

### Logging fixed config values

Every gain and conversion factor written to the log at boot. The git SHA
already pins them, and the tunable rule covers everything the SHA does
not. Logging them anyway is bytes spent to restate a commit.

### A config-verification framework

An abstraction over "desired config" with per-vendor adapters, a
registry of devices and a verification pass. This was the shape readback
would have needed. Without readback there is nothing left for it to do
that two static methods do not, and a framework whose whole job is
try/catch is the enforcement machinery this project keeps deleting.

## Source

Decided in [#13](https://github.com/Drew-Robotics/2027beta/issues/13),
which amends the design map's config-as-code row (apply + **verify** +
alert becomes apply + confirm-the-write + check firmware) and carries
the retry policy, the helper shape, the alert-set logging and the
tunable rule. The vendor facts underneath it are
[#5](https://github.com/Drew-Robotics/2027beta/issues/5); the config
record shape is [#12](https://github.com/Drew-Robotics/2027beta/issues/12)
and ADR 0003; the telemetry routing it hands off to is
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and ADR 0005.

Research: [`docs/research/vendordeps.md`](../research/vendordeps.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`wpiutil/src/main/java/org/wpilib/util/Alert.java`,
`wpiutil/src/main/java/org/wpilib/util/AlertDataJNI.java`,
`wpiutil/src/main/native/cpp/Alert.cpp`,
`wpiutil/src/main/native/cpp/jni/WPIUtilJNI.cpp`,
`hal/src/main/native/cpp/mrclib/MrcLibAlert.cpp`,
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java`,
`wpilibj/src/main/java/org/wpilib/simulation/AlertSim.java`.

In REVLib `2027.0.0-alpha-6` (sources jar):
`com/revrobotics/spark/SparkBase.java`,
`com/revrobotics/spark/SparkMax.java`,
`com/revrobotics/spark/SparkLowLevel.java`.

In Phoenix 6 `26.50.0-alpha-1`: `com.ctre.phoenix6.StatusCode`,
`com.ctre.phoenix6.configs.Pigeon2Configurator`.
