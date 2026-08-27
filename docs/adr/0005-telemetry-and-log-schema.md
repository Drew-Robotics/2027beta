# ADR 0005 — Telemetry and the log

## Status

Accepted — 2026-08-26. Superseded in part by ADR 0011, which owns the
`/Drive/Following` and `/Auto` signals. The *Open* item asking whether
an alert is visible during a match is answered by #22 and now sits
under *Consequences*.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. `[measured]`
claims are the Pi runs recorded in `docs/research/loop-rate.md` and
`docs/research/jvm-tuning.md`. An unqualified path is a file in this
repo.

## Context

We rejected AdvantageKit, which means there is **no replay**. A signal
we did not log is gone — not recoverable by re-running the match against
a recording, because there is no recording. Every question anybody asks
after a match has to be answerable from the values that were written to
disk while it happened.

That makes it worth deciding *what* we log. It does not make it worth
building machinery that polices the decision. This ADR is a signal list
and four habits: no schema document, no logging base class, no lint
rule, no compliance test. The list is the starting set; the habits are
what generate the next entry. The ticket that produced it opened by
proposing exactly that machinery and then withdrew it, and the
withdrawal is the shape of this document.

The second thing that needs deciding is transport. 2027 splits logging
into a `org.wpilib.telemetry` module — a table-and-backend architecture
with nothing to do with Epilogue — and **nothing in it writes a file
unless you ask**. What reaches the `.wpilog` is entirely a function of
how the backend is wired, and that wiring is four lines that are easy to
get subtly wrong.

## Decision

### The WPILOG is the record. NetworkTables is the dashboard

The `.wpilog` on the robot's USB stick is the artifact of record.
NetworkTables carries the same signals so a student can watch them live
in AdvantageScope, and it carries them because they are already being
written, not because anything was published there on purpose.
**[decided]**

There is **no separate debug tier** — no `DEBUG`-only subtree, no
verbose flag, no comp build that logs less. Everything we care about
goes through telemetry, and nothing important is `System.out.println`-only.
A tier is a decision at every new signal, and the measured cost of not
having one is 13.1 MB per match (see Consequences).

### Transport: one explicit backend, wired inline in `Robot`'s constructor

```java
public Robot() {
  super(Constants.LOOP_PERIOD.in(Seconds));

  DataLogManager.logNetworkTables(false);
  var log = DataLogManager.getLog();
  DriverStation.startDataLog(log, true);

  TelemetryRegistry.registerBackend(
      "",
      new MultiTelemetryBackend(
          new NetworkTablesTelemetryBackend(NetworkTableInstance.getDefault(), "/Telemetry"),
          new DataLogTelemetryBackend(log, "/Telemetry")));

  // ... mechanism fields, each handed its own table
}
```

Five things are load-bearing in those four lines.

**`registerBackend("", ...)` replaces the backend `RobotBase` already
installed.** `RobotBase`'s constructor registers a
`NetworkTablesTelemetryBackend` at the root prefix and nothing else
(`RobotBase.java:228`) **[source]**; registering at the same prefix puts
ours in its place and closes the old one
(`TelemetryRegistry.java:191-231`) **[source]**. So our
`MultiTelemetryBackend` has to construct its own NT backend — dropping
it does not "leave NT alone", it turns NetworkTables off.

**`logNetworkTables(false)` comes before `getLog()`**, and that ordering
is not cosmetic. See Traps.

**The prefix is `/Telemetry` on both backends**, matching what
`RobotBase` used, so a signal has one path and it is the same path in
the file and on the wire.

**`DriverStation.startDataLog(log, true)`** puts DS, match and joystick
context in the file (`DriverStation.java:82`) **[source]**. It writes
`DS:`-prefixed entries straight to the `DataLog`, not through telemetry
(`DriverStationBackend.java:285-287, 389-396`) **[source]**, so it is
unaffected by everything above.

**Console output stays captured.** `DataLogManager` logs it by default
(`DataLogManager.java:63`) **[source]** and we do not turn it off:
`journalctl` only exists on the device, so for a log read on a laptop
the captured console is the only place a stack trace lives.

It is written **inline in the constructor, not behind a
`configureTelemetry()` helper**. ADR 0003 gives the reason: anything not
routed through telemetry never reaches the log file, and that is the one
fact a reader has to be able to see without following a call.

### The signal list

A starting set, not a closed one. Mechanisms will be added and the list
grows with them; what is fixed is the per-mechanism template and the
habits below.

**Robot-level, always on**

| Signal | Source |
|---|---|
| `/Robot/LoopDelta` | wake-to-wake delta, ours to compute — ADR 0002 |
| `/Robot/BatteryVoltage` | `RobotController.getBatteryVoltage()` (`:117`) |
| `/Robot/BrownedOut` | `RobotController.isBrownedOut()` (`:145`) |
| `/Robot/CommsDisableCount` | `RobotController.getCommsDisableCount()` (`:155`) |
| `/Robot/CpuTemp` | `RobotController.getCPUTemp()` (`:296`) |
| `/Robot/Can/Bus0/{Utilization,ReceiveErrors,TransmitErrors,BusOff,TxFull}` | `RobotController.getCANStatus(CANBus)` (`:315`), fields on `CANStatus` (`:10-22`) |
| `/Robot/InputVoltage` | `RobotController.getInputVoltage()` (`:182`) |
| `/Robot/SysActive` | `RobotController.isSysActive()` (`:136`) |
| `/Robot/Rail3V3/{Voltage,Current,FaultCount}` | `RobotController.getVoltage3V3()` (`:200`), `getCurrent3V3()` (`:218`), `getFaultCount3V3()` (`:255`) |
| `/Robot/Pdh/{Current,Voltage,TotalCurrent,SwitchableChannel}` | `PowerDistribution.logTo` (`PowerDistribution.java:248-254`) |
| `/Robot/Pdh/{Temperature,TotalEnergy}` | `getTemperature()` (`:108`), `getTotalEnergy()` (`:158`) |
| `/Robot/Radio/{Connected,Status}` | the radio's own HTTP status page, at 0.2 Hz |
| `/Robot/Alerts` | the active alert set, at 4 Hz — ADR 0004 |
| `/Match/TimeRemaining` | `MatchState.getMatchTime()` (`:32`) |
| `/Match/{Alliance,Station,FmsAttached,EventName,MatchType,MatchNumber,ReplayNumber,GameData}` | `MatchState` (`:43-101`), `RobotState.isFMSAttached()` — every loop, never once |

**[source]** for the accessors, all in
`wpilibj/src/main/java/org/wpilib/system/RobotController.java` and
`hal/src/main/java/org/wpilib/hardware/hal/can/CANStatus.java`.

`LoopDelta` is the one signal in that table nothing in the framework
provides. `Tracer` publishes nothing — it has no telemetry and no
NetworkTables reference anywhere in `system/Tracer.java` **[source]** —
and ADR 0002 makes the wake-to-wake delta a logged signal precisely
because the watchdog cannot see a missed deadline.

There is **no `LoopOverruns` counter**, despite #11 listing one.
`OpModeRobot` already raises an `opmode-loop-overrun` alert
(`OpModeRobot.java:522-526`) **[source]**, and ADR 0004 logs the whole
alert set with its `activeStartTime`. A counter beside it would be a
second path to the same fact, carrying less. **[decided]**

**Drive base**

```
/Drive/Chassis/{DesiredVelocities,MeasuredVelocities}
/Drive/Modules/{DesiredStates,MeasuredStates}
/Drive/Modules/FrontLeft/{DriveOutput,DriveCurrent,SteerSetpoint,SteerAngle,SteerCurrent,Temp,Faults}
/Drive/Odometry/{EstimatedPose,OdometryOnlyPose,GyroHeading,GyroRate}
/Drive/Following/{Setpoint,AlongTrackError,CrossTrackError,HeadingError,TimedOut}
/Auto/{RoutineName,PlannedPath,TimeElapsed}
```

`OdometryOnlyPose` sits beside `EstimatedPose` on purpose: with both
present, vision divergence is visible as the gap between two lines on
one plot. With only the estimate, a vision update that dragged the pose
across the field and a wheel that slipped look identical.

The `Following` and `Auto` subtrees are ADR 0011's, including why the
error is decomposed rather than logged as x and y, and why `Setpoint`
is one struct rather than a pose and a velocity.

**Per mechanism**, for every mechanism we ever add: setpoint,
measurement, applied output, current, temperature, faults, current
command. Seven names, the same seven every time, so a student who has
read one mechanism's subtable can read the next one.

**Deliberately excluded.** Raw encoder ticks — we log the derived value
in real units, and a tick count is only interpretable with a conversion
factor the git SHA already pins. A per-loop echo of config values —
ADR 0004 rules that fixed config is recoverable from the SHA and only
tunables are logged. Vision internals — ADR 0012.

### Names are PascalCase, and units are never in the name

PascalCase segments, no spaces, no abbreviations that need expanding,
and **no unit suffix**. `/Drive/Modules/FrontLeft/SteerAngle`, never
`SteerAngleRad`.

The unit lives in the entry's metadata, where `Measure` puts it for free
(below), and the name stays true when the unit changes. A name that
carries a unit is a name that lies the day somebody logs the same
quantity in degrees. **[decided]** #11's own draft list contained
`LoopMs` and `BatteryVolts`; both are renamed here for exactly this
reason, which is a fair illustration of how easily the habit slips.

### Modules are logged twice, on purpose

Every module appears in two places, and the duplication is the decision,
not an oversight:

- `/Drive/Modules/{DesiredStates,MeasuredStates}` as a
  `SwerveModuleVelocity[]`, because that is what AdvantageScope's swerve
  visualiser consumes. `SwerveModuleVelocity` is struct-serializable —
  `public static final SwerveModuleVelocityStruct struct`
  (`SwerveModuleVelocity.java:36`) **[source]** — so this costs one
  `log(name, array, struct)` call.
- `/Drive/Modules/FrontLeft/...` and its three siblings, **named**, for
  a human reading the file.

Names over indices at the readable layer, because a corner/index
mismatch is a classic swerve bug and an array index does not tell you
which corner it is. The array form keeps the index because the
visualiser requires it. **[decided]**

### `Measure` everywhere, structs for complex types

Log `Measure` values rather than bare doubles, and let structs handle
`Pose2d`, `ChassisVelocities`, `SwerveModuleVelocity` and friends.
`RobotBase` registers a type handler that logs a `Measure` in its base
unit and attaches the unit symbol as an entry property
(`RobotBase.java:229-234`, `UnitTelemetry.java:74-76`) **[source]**, so
the log is self-describing without anybody maintaining a units table.

The per-sample cost of that property write is smaller than it reads.
`UnitTelemetry.log` calls `setProperty` on every sample, but unit
symbols are cached in an identity map (`UnitTelemetry.java:18, 33-40`)
and **both backends short-circuit an unchanged property** before
touching an NT topic or `DataLogEntry.setMetadata`
(`NetworkTablesTelemetryBackend.java:266`,
`DataLogTelemetryBackend.java:163-167`). **[source]** What remains is a
`synchronized` block and a map lookup, not a republish.

The real cost is allocating the `Measure` in the loop, and it has been
measured. See Consequences: the number is large, the rule stands, and
the escape hatch is named.

### One rate: the loop period

Every signal, every loop. No tiering, no fast-sampled subset, no second
periodic callback at a slower rate for the cheap stuff. **[decided]**

That period is **5 ms**, not the 20 ms #11 assumed — the rule is
unchanged, ADR 0002 raised the rate underneath it. ADR 0002 measured the
combined cost on the Pi at 65 µs of work per loop, a 1.3% duty cycle,
and 13.1 MB per match with ~50 signals attached **[measured]**, which is
what makes "log everything at the loop rate" affordable rather than
merely tidy.

**The radio is not a WPILib signal.** Nothing in WPILib reports on it —
the roboRIO's radio accessors went with the roboRIO. The radio serves
its own status page at `10.TE.AM.1/status`, so `/Robot/Radio` is an
HTTP request rather than a getter, fired at 0.2 Hz and **never waited
on**: the request is started on one callback and read on a later one,
so a radio that has gone away costs the loop thread nothing rather than
its timeout. The client is warmed in `Robot`'s constructor, because the
first `sendAsync` costs ~8 ms while it starts its machinery **[measured]**
— longer than the whole loop period, and exactly the cold-start ADR
0002 rules is paid at `robotInit`.

**Match state is read every loop, and reading it once is a bug.** The
alliance arrives from the FMS some time *after* the Driver Station
attaches, so anything that samples it once samples it too early.
`OpModeRobot.driverStationConnected()` is not the exception it looks
like: it fires on the control word's DS-attached bit
(`OpModeRobot.java:617-619`) **[source]**, which has nothing to do with
the alliance station, and it fires exactly once. So `/Match` is written
at the loop rate like everything else, duplicate suppression flattens
the constants, and the alliance transition lands in the file with the
timestamp it actually happened at. **[decided]**

The same fact is a fault surface: a Driver Station that is attached and
has not said which alliance it is means every alliance-dependent
decision is about to be made against a guess. That raises a `HIGH`
alert, at the level ADR 0011 sets, cleared as soon as the alliance
turns up. Nothing alerts when no DS is attached at all, so a bench sits
quiet. **[executed]**

**Driver Station data is already in the file and is not logged again.**
`DriverStation.startDataLog(log, true)` writes `DS:controlWord` — a
`ControlWord` struct carrying enabled, mode, opmode id, e-stop,
FMS-attached and DS-attached — plus `DS:opMode` and per-joystick
`DS:joystickN/{buttons,axes,povs}`, straight to the `DataLog`
(`DriverStationBackend.java:270-287, 380-400`). **[source]** Re-logging
any of it through telemetry would be a second copy under a second name.
What `DS:` does *not* carry is match identity, which is why `/Match`
exists above. **[decided]**

There is **one exception**, and it is ADR 0004's: the alert set is
polled and logged at 4 Hz, through
`OpModeRobot.addPeriodic(this::logAlerts, 0.25)`
(`OpModeRobot.java:548-550`) **[source]**. `getAlerts()` is an
allocating JNI call returning every alert on the robot, and alerts
change on human timescales. See Traps for why that call is not the
`addPeriodic` ADR 0006 rejects.

### `keepDuplicates`: a short named list

Duplicate suppression is on by default in both backends
(`NetworkTablesTelemetryBackend.java:136-145`,
`DataLogTelemetryBackend.java:131-133`) **[source]**, so a signal that
holds a constant value writes one sample and then nothing. Without
replay, "held steady at 0 for the whole match" and "stopped being
logged after the first loop" are the same bytes on disk.

Opt in, via `table.keepDuplicates(name)` (`TelemetryTable.java:334-338`)
**[source]**, for:

- **`/Commands/Events`** — mandatory. Repeated command names are the
  normal case, and suppression would swallow them silently.
- **`/Robot/BrownedOut`** and the enabled/mode signals.
- **`Faults`**, on every mechanism.

Everything else stays deduplicated. **[decided]**

### Command telemetry uses both mechanisms

Commands v3 ships two telemetry surfaces and no wiring, and they cover
different holes, so we use both.

**`/Commands/Scheduler`** — the protobuf snapshot, logged once per loop
via `Scheduler.proto`. It gives the running command tree with per-command
timing: `CommandProto.pack` emits `id`, `parent_id`, `name`, `priority`,
`requirements`, `last_time_ms` and `total_time_ms`
(`CommandProto.java:47-62`) **[source]**, and the tree reconstructs from
the ids. It is **blind to one-shots** — see Traps.

**`/Commands/Events`** — a listener registered with
`Scheduler.addEventListener`. `SchedulerEvent` is a sealed interface over
`Scheduled`, `Mounted`, `Yielded`, `Completed`, `CompletedWithError`,
`Canceled` and `Interrupted`, each carrying `timestampMicros`
(`SchedulerEvent.java:33-88`) **[source]**. It sees every lifecycle
event including one-shots, and carries no tree.

`CompletedWithError(Command, Throwable, long)` is the **only** place a
command exception surfaces. It is logged *and* raises an `Alert`, so a
failure is visible at the driver's station during the match and in the
file afterwards. **[decided]**

### `/Metadata`, stamped once, from a Gradle task

Nothing in WPILib generates build metadata — there is no
`BuildConstants` and no git-SHA generation anywhere in the tree
**[source]**. A ~20-line Gradle task writes one generated class, and
`Robot`'s constructor logs it once:

```
/Metadata/{GitSha,GitDirty,Branch,BuildTime,WpilibVersion,RevLibVersion,PhoenixVersion,Serial,TeamNumber}
```

`WpilibVersion` is `org.wpilib.system.WPILibVersion.Version`, itself
generated **[source]**; the vendor versions are read out of
`vendordeps/*.json` at build time; `Serial` is
`RobotController.getSerialNumber()` (`:37`) **[source]**.

**`GitDirty` is the field that earns the block.** Every other entry
restates something the SHA already pins; the dirty flag is what says the
SHA does not pin this build. ADR 0004 reads the same flag to raise its
`LOW`-level dirty-deploy alert.

Stamping `Serial` is **not** robot-identity switching. It records which
box ran; it does not select offsets or gains. That remains fog — see
Open, and ADR 0004's.

### The four habits, stated so they outlive this list

Everything above is a list, and a list goes stale the first time
somebody adds a mechanism. These four are the rules that generate the
next entry, and they are what actually has to be remembered.

**1. Setpoint and measurement are always logged together, never one
without the other.** A measurement with no setpoint beside it cannot be
diagnosed after the fact: a module reading 2 m/s is not a fact about
anything until you know what it was asked for. This is why the
per-mechanism template leads with both, and why `/Drive/Chassis` has
`DesiredVelocities` and `MeasuredVelocities` rather than a single
truth.

**2. If a value being unchanged is itself information, it needs
`keepDuplicates`.** That is the rule behind the named list above, stated
so it applies to signals nobody has written yet.

**3. The unit goes in the metadata, never in the name.** Restated here
because it is a habit and not just a naming convention: it is what makes
`Measure` the default rather than a choice.

**4. Log the derived value, in real units, at the loop rate.** Not the
raw count, not a sampled subset, not a debug tier.

Habits 1 and 2 are the two the team argued about and the two worth
quoting at a review. **[decided]**

### Destination: the USB stick

`DataLogManager` already prefers a mounted, writable USB at `/u/logs`
and falls back to `/home/systemcore/logs`
(`DataLogManager.java:251-255`) **[source]**. We use a USB stick and
write no path logic.

Retention is a non-issue there. Deletion runs only inside
`if (freeSpace < FREE_SPACE_THRESHOLD)` — 50 MB
(`DataLogManager.java:69, 315`) **[source]** — so on a stick with room
nothing is ever evicted, and the 10-file floor
(`:70, 328`) only applies once already under that threshold. A warning
fires below 100 MB (`:343-348`). At ~20 MB per match, a 32 GB stick
holds a season.

## Consequences

- **With `logNetworkTables(false)`, anything not routed through
  telemetry never reaches the file.** This is the price of the explicit
  backend and it is worth stating plainly: a value published straight to
  a `NetworkTableEntry`, a dashboard widget someone adds by hand, a
  vendor library's own NT topics — none of it is captured any more. The
  two things that still reach the file without going through telemetry
  do so because they are written to the `DataLog` directly: the `DS:`
  entries from `DriverStation.startDataLog` and the captured console
  output. Everything else is telemetry or it is gone. `/Tunables` is the
  live instance of this problem — see Open.

- **The `Measure`-per-sample rule costs 4× the garbage, and it stands.**
  Logging the same fifty signals as `Measure` objects rather than raw
  doubles measured **21.8 KB per loop against 5.5 KB, 4.17 MB/s against
  1.05, and 625 MB per match against 158** — with the worst steady-state
  wake rising from 5.196 ms to 7.621 ms, the only change ADR 0002
  measured that visibly worsened the tail. **[measured —
  `docs/research/jvm-tuning.md`]** The rule stands, because 7.6 ms of
  worst-case wake is far inside what a swerve base notices and
  self-describing units are worth real money on a log nobody can replay.
  It is, however, **the first lever to pull if the tail ever does
  matter** — before pinning a collector, which ADR 0002 measured as
  worthless, and before cutting signals. The escape hatch is under
  Rejected, costed and ready.

- **A match log is ~20 MB.** 13.1 MB was measured with ~50 signals of
  which about 18 were constants that duplicate suppression flattened, so
  budget nearer 20 MB with real mechanisms attached. **[measured —
  `docs/research/loop-rate.md`]** Nothing about disk is a constraint on
  any decision here.

- **ADR 0004's alert-set signal is the one documented exception to the
  one-rate rule**, and the exception is now recorded in the ADR that
  owns the rule rather than only in the one that needed it.

- **An alert is visible during a match, and it is still not in the file
  for free.** Alerts reach the *Driver Station's* NetworkTables, by a
  path ADR 0003 records — never this ADR's backend and never the
  robot's own NT. So it changes nothing here: ADR 0004's 4 Hz alert-set
  logging remains the only thing that puts an alert in the WPILOG.

- **ADR 0013 inherits an assertion surface.** ADR 0003 hands each
  mechanism its own `TelemetryTable`, so a test constructs one over a
  `MockTelemetryBackend` and asserts on `getLastValue(path, cls)` with
  no global state. Every signal in this document is therefore testable
  by name — and every name in it is a name a test can be written
  against.

- **ADR 0014 inherits these names as its contract.** `/analyze-match`
  reads signal paths; the unprefixed `/Drive/Modules/FrontLeft/SteerAngle`
  is what it reads, and it is a better contract than the
  `NT:/Telemetry/...`-prefixed form NT capture would have produced.
  Renaming a signal is a breaking change to that tooling.

- **`build.gradle` gains one task**, and `Robot` gains one generated
  class to import. That is the whole cost of `/Metadata`.

- **Vendor objects are not loggable for free.** `Sendable` is deleted
  from WPILib entirely **[source — #2]**, and a type that implements
  neither `TelemetryLoggable` nor `StructSerializable` falls through to
  `toString()`. Logging a SPARK or a Pigeon2 usefully means writing the
  seven per-mechanism names ourselves out of individual getters, which
  is what the per-mechanism template already assumes.

## Traps

- **Nothing writes a WPILOG by default.** `RobotBase` registers only the
  NetworkTables backend (`RobotBase.java:228`) **[source]**;
  `DataLogTelemetryBackend` exists and is never registered by anything
  outside tests. A robot that logs beautifully to a dashboard and writes
  no file at all is the default state, not a failure state, and it looks
  identical from the driver's station.

- **`DataLogManager` pauses logging ~10 s after the Driver Station
  disappears.** The writer thread counts 0.25 s timeouts and calls
  `m_log.pause()` past 40 of them — its own comment says "pause logging
  after being disconnected for 10 seconds" — resuming when the DS comes
  back (`DataLogManager.java:371-391`). **[source]** So a **bench
  session with no DS attached produces a log that silently stops** ten
  seconds in, with no error and a plausible-looking file. This is the
  single most likely way to lose a morning at bring-up. Attach a DS, or
  do not trust the file.

- **A mechanism built in a test without a `RobotBase` logs every
  `Measure` as its `toString()`.** The type handler that gives a
  `Measure` its base-unit value and its unit property is registered by
  `RobotBase`'s constructor and nowhere else
  (`RobotBase.java:229-234`). **[source]** Nothing warns; the value lands as a string, the unit
  property is never set, and an assertion on a double fails for a reason
  that has nothing to do with the code under test. ADR 0013's fixtures
  either construct a `RobotBase` or register the handler themselves.

- **`logNetworkTables(false)` must come before `getLog()`.**
  `getLog()` calls `start()` if the manager has not started
  (`DataLogManager.java:183-188`), and NT capture defaults to enabled
  (`:60`) **[source]** — so building the `DataLogTelemetryBackend` first
  starts the manager with capture on, and every telemetry signal is
  written twice for the window before the flag flips. Called first,
  `logNetworkTables(false)` sets the flag and *then* starts the manager
  (`:208-215`) **[source]**, which is the ordering in the Decision.

- **Registering at `""` closes the backend it displaces.**
  `TelemetryRegistry.registerBackend` puts the new backend in the map
  and closes the old one (`TelemetryRegistry.java:191-231`)
  **[source]**. Ours is correct — the `MultiTelemetryBackend` builds a
  fresh NT backend — but a future edit that "simplifies" it to just the
  DataLog backend silently kills every dashboard on the robot, and the
  robot code will not notice.

- **The `Scheduler` proto snapshot cannot see one-shot commands.** Its
  own javadoc says so: commands that never call `Coroutine#yield()` are
  invisible (`Scheduler.java:92-95`, and again at `:1370`).
  **[source]** An instant command that fires and completes inside one
  `run()` leaves no trace in `/Commands/Scheduler` at all. This is the
  entire reason `/Commands/Events` exists beside it.

- **`CompletedWithError` is the only place a command exception
  surfaces** (`SchedulerEvent.java:66`). **[source]** A command that
  throws does not propagate out of `run()`, does not stop the robot, and
  does not print anything you will find later. If the listener does not
  log the `Throwable`, the failure is invisible.

- **There are two unrelated `addPeriodic` methods, and ADR 0006 rejects
  only one of them.** `Scheduler.addPeriodic(Runnable)` is a sideload —
  it wraps the callback in a coroutine that loops forever
  (`Scheduler.java:329-335`) **[source]** — and that is what ADR 0006
  rules out, because a sideload is invisible in the command timeline.
  `OpModeRobot.addPeriodic(Runnable, double)` is the framework's timed
  callback (`OpModeRobot.java:548-550`) **[source]**, is not a
  coroutine, and is what ADR 0004's alert logging uses. Reading the
  rejection as covering both is an easy and expensive mistake.

- **`setProperty` values must be valid JSON.** `"\"m/s\""`, not
  `"m/s"`; both backends silently reject a malformed value **[source —
  `docs/research/telemetry-api.md` §9]**. The `Measure` path handles
  this for us, which is one more reason the default is `Measure` rather
  than a hand-set property.

- **Logging a `Collection` without an element type fails two different
  ways.** `TelemetryTable.log(name, Collection)` throws a *checked*
  `CollectionElementTypeRequiredException`
  (`TelemetryTable.java:696`) **[source]**; the generic
  `log(name, T value)` path takes the collection as an object and warns
  and drops it. Pass an array with its struct, or the element type.

- **Enums and records land as `toString()`.** Neither is special-cased
  in the dispatch order **[source — `docs/research/telemetry-api.md`
  §9]**. A record of doubles logged directly is one string entry, not a
  subtable — which is a silent downgrade, since a string is a perfectly
  valid log entry and nothing complains.

## Open

- **Which values become tunables, and how `/Tunables` reaches the
  file.** ADR 0004 commits us to `org.wpilib.tunable` and rules that
  tunables are logged; *which* values are tunable is undecided, and
  nobody has used the module. **[unverified]** It drags a hard
  requirement with it: `RobotBase` wires tunables to a
  `NetworkTablesTunableBackend` under `/Tunables`
  (`RobotBase.java:237`) **[source]**, and this ADR turns NT capture
  off — so **`/Tunables` will not reach the WPILOG unless it is routed
  through telemetry explicitly**. A tunable that is changed at an event
  and not logged produces exactly the unattributable log ADR 0004's rule
  exists to prevent. *Unblocked by* someone deciding the tunable set and
  reading `TunableRegistry` for whether a second backend or an explicit
  mirror is the cheaper route.

- **The 13.1 MB and 65 µs figures were measured with ~50 synthetic
  signals**, not with this list attached to real mechanisms
  **[measured — `docs/research/loop-rate.md`]**. The margins are large
  enough that this is bookkeeping rather than risk, but the numbers are
  not a measurement of the robot we are building. *Unblocked by* a match
  worth of log off the first real chassis.

- **How logs get off the robot, and in what form anything reads them**,
  is #16 and ADR 0014. This ADR fixes the names and the file; it decides
  nothing about retrieval or analysis.

## Rejected

### AdvantageKit

Rejected on the design map, and this ADR is downstream of that. The
consequence is worth restating where it bites: **there is no replay**.
Nothing can be re-derived after the fact by running new code against a
recorded input stream, because the inputs are not recorded as inputs —
they are recorded as whatever signals we chose to log. A signal we did
not think to log is a question we cannot answer, and the only remedy is
to log deliberately, which is what the signal list and the
setpoint-and-measurement habit are for.

*Do not re-raise* without new evidence about the 2027 port's cost. The
grounds were the framework's weight and its structural demands on every
mechanism, not a doubt that replay is useful.

### NT capture alone — `DataLogManager.start()` and nothing else

One line, and it captures non-telemetry NetworkTables traffic as a
bonus. Rejected because every path arrives prefixed `NT:/Telemetry/...`,
and ADR 0014's tooling reads signal names — `/Drive/Modules/FrontLeft/SteerAngle`
is a better contract than the prefixed form. It also ties the on-disk
record to NT connectivity and NT's own dedup behaviour, which is exactly
the coupling a no-replay project should not accept.

### Both — NT capture *and* the explicit DataLog backend

Nothing is lost and everything is written twice, under two different
names. Rejected on the cost of explaining two copies of every signal to
a student looking at the file for the first time.

### A separate debug tier

A `DEBUG` subtree routed to `DiscardTelemetryBackend` in comp builds.
`DiscardTelemetryBackend` is genuinely near-free — `isDiscard()` is
checked before any serialization **[source — `docs/research/telemetry-api.md`
§5]** — so this would work. It is rejected because it imposes a
tier decision at every new signal, for a saving of a fraction of 13.1 MB
and 1.3% of the loop. Revisit only if a measurement complains.

### Split-rate telemetry

ADR 0002 owns this rejection and measured it: a 1.3% duty cycle and
13.1 MB per match mean there is nothing to save. **[measured]** Restated
here only because "log the important things fast" is the shape everyone
reaches for.

### A log schema, a logging base class, a lint rule, a compliance test

The original proposal on #11, withdrawn there before it reached this
document. A `LoggableSubsystem` base class with abstract `logSetpoint`
and `logMeasurement`, a naming lint rule, a test that fails the build
when a mechanism logs a measurement with no setpoint. Rejected as
enforcement machinery in place of a habit: it would cost more to
maintain than the mistakes it catches, and the mistakes it catches are
visible the first time anyone opens the log.

### Unit suffixes in signal names

`SteerAngleRad`, `BatteryVolts`. Rejected at the Decision: the unit is
in the metadata already, and a name carrying a unit becomes a lie the
day the unit changes.

### Raw doubles with the unit property set once at construction

The named escape hatch, and it genuinely works: set the property once —
the handler's format is just the JSON-encoded unit symbol, `"m/s"` —
and log a primitive `double` in the loop. **Identical file, zero
per-sample allocation** — it is what ADR 0003's `SwerveModule` sketch
already does for `Velocity`. Rejected *as the default* because
`Measure` is harder to get wrong and self-describes without anyone
remembering the property line. It is deliberately kept costed and
documented as the first lever, per the Consequences above.

### A `LoopOverruns` counter

Covered at the Decision. `OpModeRobot` already raises an overrun alert
and ADR 0004 logs the alert set with timestamps; a counter is a second
path to the same fact carrying less information.

### Logging raw encoder ticks

A tick count needs a conversion factor to mean anything, and the SHA
already pins the conversion factor. We log the derived value in real
units.

## Source

Decided in
[#11](https://github.com/Drew-Robotics/2027beta/issues/11), which
carries the signal list, the naming habit, the `keepDuplicates` list and
the transport decision, and which withdrew its own opening proposal of a
schema framework. The API facts underneath it are
[#2](https://github.com/Drew-Robotics/2027beta/issues/2). The rate rule
and its measurements are
[#28](https://github.com/Drew-Robotics/2027beta/issues/28) and ADR 0002;
the injected `TelemetryTable` and the inline backend wiring are
[#12](https://github.com/Drew-Robotics/2027beta/issues/12) and
ADR 0003; the alert-set signal, the `GitDirty` flag and the tunable
rule are [#13](https://github.com/Drew-Robotics/2027beta/issues/13) and
ADR 0004.

Research: [`docs/research/telemetry-api.md`](../research/telemetry-api.md),
[`docs/research/loop-rate.md`](../research/loop-rate.md),
[`docs/research/jvm-tuning.md`](../research/jvm-tuning.md).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`telemetry/src/main/java/org/wpilib/telemetry/TelemetryTable.java`,
`telemetry/src/main/java/org/wpilib/telemetry/TelemetryRegistry.java`,
`telemetry/src/main/java/org/wpilib/telemetry/TelemetryEntry.java`,
`telemetry/src/main/java/org/wpilib/telemetry/MultiTelemetryBackend.java`,
`wpilibj/src/main/java/org/wpilib/internal/UnitTelemetry.java`,
`wpilibj/src/main/java/org/wpilib/backend/NetworkTablesTelemetryBackend.java`,
`wpilibj/src/main/java/org/wpilib/backend/DataLogTelemetryBackend.java`,
`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java`,
`wpilibj/src/main/java/org/wpilib/system/RobotController.java`,
`wpilibj/src/main/java/org/wpilib/system/Tracer.java`,
`wpilibj/src/main/java/org/wpilib/framework/RobotBase.java`,
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
`wpilibj/src/main/java/org/wpilib/driverstation/DriverStation.java`,
`wpilibj/src/main/java/org/wpilib/driverstation/internal/DriverStationBackend.java`,
`wpilibj/build/generated/java/org/wpilib/system/WPILibVersion.java`,
`hal/src/main/java/org/wpilib/hardware/hal/can/CANStatus.java`,
`wpimath/src/main/java/org/wpilib/math/kinematics/SwerveModuleVelocity.java`,
`commandsv3/src/main/java/org/wpilib/command3/Scheduler.java`,
`commandsv3/src/main/java/org/wpilib/command3/SchedulerEvent.java`,
`commandsv3/src/main/java/org/wpilib/command3/proto/CommandProto.java`.
