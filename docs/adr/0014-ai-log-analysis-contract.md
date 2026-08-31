# ADR 0014 — The AI log-analysis contract

## Status

Accepted — 2026-08-27. Corrects #16's ground for vendoring the WPILOG
parser: `robotpy-wpilog` **does** exist for 2027 and works. The decision
is unchanged and the reason is now coupling rather than absence — see
*Rejected* and *Source*. Amended 2026-08-30 by #107: a class-1 anchor
the platform could not source is reported as *unavailable*, never as
*clear*.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. `[executed]`
claims were run on 2026-08-27, on the bench Pi or on this workstation.
An unqualified path is a file in this repo.

## Context

ADR 0005 rejected AdvantageKit, so there is no replay. That single fact
sets the terms for everything here: **the `.wpilog` is the agent's
entire view of a match**, there is no second pass, and a question that
the file cannot answer is a question nobody can answer.

So the tooling that reads that file is not a convenience. It is the only
path from *the robot did something odd* to *here is why*, and it has to
survive a season in which our signal names, our mechanisms and our
conventions all change underneath it.

There are two ways to build it, and they are not close. A tool that
knows our robot — that pairs `…Setpoint` with `…Measurement`, that knows
`FrontLeft` is a swerve corner — can report *the front-left steer PID is
lagging* for free. It is also a **second place our conventions live**,
and the day someone renames a signal it starts lying without failing.

The alternative is to put every fact about our robot in one place and
every fact about WPILOG in another. That is the decision.

## Decision

### This ADR specifies. It does not build

`tools/logtool` does not exist, `/analyze-match` does not exist, and
neither is written here. **This document fixes the contract; the code is
the second map's**, like the rest of the project. **[decided]**

What that buys is a spec an implementer can build against without
reopening any of the arguments below, and a set of claims that were
checked *now*, while the sources were open, rather than rediscovered
against a half-written tool.

**Where the skill lives, how it reaches students and how `AGENTS.md`
references it is #18's**, not this ADR's. This document decides only
what the skill *contains*. **[decided]** Said here so that its absence
reads as scope rather than omission.

### The CLI knows nothing about our robot; the skill knows everything

`tools/logtool` works on **any** WPILOG, forever. It hardcodes no signal
name, no path shape, no naming convention, no unit and no mechanism.
Every fact about *our* robot lives in the `/analyze-match` skill, which
supplies the meaning the tool refuses to. **[decided]**

That split is the whole answer. It is what stops this tooling rotting
against our own naming, and it is why the tool needs no maintenance when
ADR 0005's signal list grows.

The tool answers **what**. The agent answers **why**. A tool that
answers *why* is an anomaly detector, and we are not building one.

### Five subcommands, and resist a sixth

| | |
|---|---|
| `pull` | scp from the Pi, skipping files already local |
| `list` | every signal: path, type, unit, sample count, first and last timestamp |
| `stats` | min, max, mean, stddev and count per signal over an optional window; all signals by default |
| `query` | values for named signals over a window |
| `console` | the captured `WPILIB_UserProgram.log` text |

**[decided]** `list` is the agent's entry point and is a few KB against
ADR 0005's ~50 signals, so it fits in a context window whole and tells
the agent what *this* log actually contains before it asks for anything.

**There is no `compare`, no `summarise` and no bulk export.**
Comparison is the agent running `stats` twice and reading both — which
is what *the agent derives* means, and it avoids us guessing which
comparison matters. A bulk export to JSON, Parquet or CSV is the same
mistake in a different shape: it moves a multi-megabyte file into a
different multi-megabyte file, and the agent still has to decide what to
look at.

**The standing test for a sixth subcommand is whether the agent could
have composed it from `list` plus `query`.** If it could, it is not a
subcommand. **[decided]**

### `query` buckets. It does not decimate

A 150 s match at ADR 0002's 5 ms loop period is **30,000 samples per
signal**. Every-Nth-sample decimation deletes precisely the events worth
finding: a brownout dip, one loop overrun, a one-frame CAN dropout each
live in a *single* sample, and ADR 0005's `LoopDelta` exists specifically
to make a single swallowed iteration visible.

So the default is **~200 buckets of min, max and mean** — about 750 ms
and 150 samples per bucket over a full match. A spike survives as a
bucket whose max diverges from its mean; the agent sees *something at
t ≈ 8.2 s* and re-queries that window with `--raw`. **[decided]**

Two passes over a bucketed signal find a one-sample event. One pass over
a decimated signal never does, and reports nothing rather than reporting
that it looked.

### The parser is vendored, not written

`datalog/examples/printlog/datalog.py` is a **354-line pure-Python
reference parser** under the WPILib BSD licence this repo already ships
as `WPILib-License.md`. **[source]** Vendor it into `tools/logtool/`.
**[decided]**

Its single third-party import, `msgpack` (`:10`), serves
`DataLogRecord.getMsgPack()` (`:134`) and the example's own `main`
(`:334`). We need neither, so the import goes with them and the vendored
reader has **no dependencies at all**.

Writing a parser was never on the table: WPILib ships three, and the
right one is the least famous.

### Structs get the scalar-and-nesting subset, and it degrades loudly

A fully generic struct decoder is **~1,700 lines** — `struct/parser/`
(429 lines across a real lexer and a recursive-descent parser),
`DynamicStruct.java` (658) and the descriptor classes (629).
**[source]** The schema grammar is not `type name;type name`:
`ParsedDeclaration` carries **enum values, an array size and a bit
width** (`ParsedDeclaration.java:12-24`). **[source]**

So implement the **scalar-and-nesting subset**, roughly 150 lines:
`double`, `float`, `int8`…`int64`, `bool`, plus nested-struct-by-name.
It is still fully schema-driven — it hardcodes no type name — it simply
does not implement enums, arrays or bitfields. **[decided]**

**On a schema it cannot parse it reports the signal as opaque and prints
the schema text.** That is the necessary half of the decision.
Silence would be indistinguishable from a signal that was not logged,
and there is exactly one such schema in our own file — see *Traps*.

The subset covers every type this project logs, because the geometry and
kinematics types are scalars all the way down:

```
SwerveModuleVelocity   double velocity;Rotation2d angle
Pose2d                 Translation2d translation;Rotation2d rotation
Translation2d          double x;double y
Rotation2d             double value
```

**[source — `SwerveModuleVelocityStruct.java:30`, `Pose2dStruct.java:31`,
`Translation2dStruct.java:29`, `Rotation2dStruct.java:29`]**

### The command timeline is decoded with the `protobuf` package

`commandsv3/src/main/proto/protobuf_commands.proto` is 57 lines and
three messages — `ProtobufMechanism`, `ProtobufCommand`,
`ProtobufScheduler` — of strings, `uint32`, `int32`, `double`,
`repeated` and `optional`. **[source]**

WPILib writes the full `FileDescriptorProto` into the log itself:
`DataLog.addSchema(Protobuf, long)` walks every descriptor and writes
each under type `proto:FileDescriptorProto`
(`DataLog.java:148-154`), and `ProtobufLogEntry`'s constructor calls it
on creation (`ProtobufLogEntry.java:25`). **[source]** So a dynamic
message builds at runtime from the file, with no generated code and no
`protoc` in the toolchain:

```python
pool = descriptor_pool.DescriptorPool()
pool.Add(fdp)                       # fdp parsed from the log's schema entry
Msg = message_factory.GetMessageClass(pool.FindMessageTypeByName(name))
```

Verified end to end against `protobuf` 7.36.0 — hand-built
`FileDescriptorProto` in, class out, message round-tripped.
**[executed]**

**Take the dependency.** A hand-rolled wire walker was costed at ~150
lines and is rejected: it is real work to avoid a package that cannot
fail to be ready for a season.

### The rule this settles

**Dependencies are fine. FRC-coupled dependencies are what rot.**
**[decided]**

`protobuf` has no idea FIRST exists and ships for every Python we will
ever run. An FRC-season package is pinned to a season by construction,
and its readiness is somebody else's schedule. That is the distinction —
not *few dependencies*, and not *no dependencies*.

### The log is self-describing, which is what makes a generic tool viable

Three facts, none of them obvious going in, and together they are the
reason nothing about our signal names needs to be hardcoded anywhere in
the CLI:

- **Struct schemas are in the log.** `addSchema` writes them under
  `/.schema/<name>` (`DataLog.java:78`) and `addSchemaImpl` **recurses
  into nested types** through `struct.getNested()` before writing its
  own (`:446-458`). **[source]** A reader resolves `Rotation2d` inside
  `SwerveModuleVelocity` from the file, and `StructLogEntry` /
  `StructArrayLogEntry` call it on creation
  (`StructLogEntry.java:23`, `StructArrayLogEntry.java:26`).
  **[source]**
- **Units are in the log.** ADR 0005 logs `Measure` values, whose unit
  symbol becomes a telemetry property; `DataLogTelemetryBackend`
  serialises the property map to JSON and sets it as the entry's
  metadata (`DataLogTelemetryBackend.java:140-146, 150-171`).
  **[source]** ADR 0005's *units in metadata, never in the name* rule
  therefore survives the round trip, and `list` reads the unit rather
  than guessing it.
- **The console is in the log.** `DataLogManager` pipes
  `/home/systemcore/WPILIB_UserProgram.log` into the same file under the
  entry `console` (`DataLogManager.java:300`). **[source]** Stack traces
  and startup text arrive alongside the signals, in one artifact —
  which matters because `journalctl` exists only on the device.

### Logs come off the robot with `pull`, and it is deliberately not part of deploy

You want logs after a *match*, not after a code push, and coupling them
means a deploy at an event stalls on a 20 MB transfer. **[decided]**

`pull` scps from the robot's log directory and skips files already
local. That directory is `/u/logs` when a USB drive is mounted and
`/home/systemcore/logs` otherwise (`DataLogManager.java:246-270`).
**[source]** Neither exists on the bench Pi today, because nothing has
written a log yet. **[executed]**

Two facts in *Traps* make pulling a **habit rather than a
convenience**: the robot deletes old logs on its own, and the filename
is the only place match identity exists.

### `/analyze-match` — fixed opening, then branch

Three steps every run, in order:

1. `pull`, if the log is not already local.
2. `list`, to see what *this* log contains.
3. `stats` over everything, to see what moved.

**[decided]** The opening earns its place by stopping the agent querying
signals this log does not have — a bench log and a match log are not the
same file, and neither is a CI log.

Then one in-file section per question class, naming the signals to reach
for and how to read them. **The skill asks what you are after.** Given a
specific question it scopes to that; absent one it sweeps all five.

### Five question classes, and nothing is a sixth

1. **Did the robot stay healthy?** — brownouts, CAN utilisation, loop
   overruns, active alerts, config failures, device dropouts.
   Anchors: `/Robot/BatteryVoltage`, `/Robot/BrownedOut`,
   `/Robot/LoopDelta`, `/Robot/Can/Bus0/*`, `/Robot/Alerts`.
2. **Did it go where we told it?** — ADR 0011's along-track /
   cross-track decomposition and module setpoint-versus-measurement
   error. Anchors: `/Drive/Following/*`, `/Drive/Odometry/*`,
   `/Drive/Modules/*`.
3. **What happened, in order?** — enable, disable, opmode and which
   commands ran when. Anchors: `/Commands/Scheduler` (the proto
   snapshot) and `/Commands/Events` (the one-shot-visible listener),
   plus `DS:opMode`.
4. **What was this robot?** — `/Metadata/*`, so a log is attributable to
   a SHA, a dirty flag and a set of versions.
5. **What changed since last time?** — the same numbers from two logs,
   side by side.

**[decided, via #16]** ADR 0005 owns every one of those names.

***Why did auto miss?* is not a sixth class.** It is 2 and 3 read
together by the agent. That line is what keeps this from becoming an
anomaly-detection framework, and it is the same line as *the tool never
answers a why*.

### The unscoped sweep has an exhaustive completion criterion

**All five classes reported, each with a finding *or* an explicit
"clear".** The word "clear" must be stated, never omitted.
**[decided]**

Silence is indistinguishable from not having looked, and the realistic
failure is not a wrong diagnosis — it is finding one brownout, reporting
it, and never opening pose error at all.

**A missing anchor is reported as *unavailable*, never as *clear*.**
ADR 0005 lets the platform decide whether a HAL-sourced signal exists
at all, and class 1 is where that bites: on the SystemCore image ADR
0002 records, `/Robot/Can/Bus0/*` and `/Robot/BatteryVoltage` are both
absent, which is two of that class's five anchors. `list` is what makes
this legible — step 2 of every run exists to stop the agent querying
signals the log does not have — but a signal that was never recorded
and a signal that recorded no problem are the same nothing at query
time. Reporting "clear" for the first is reporting the absence of a
signal as the absence of a fault. `/Robot/Alerts` carries a
`hal-unimplemented` entry naming what the platform could not source, so
the distinction is in the file. **[decided]**

### The skill points at ADR 0005. It never restates it

One line: *the signal naming habits are in
[`docs/adr/0005-telemetry-and-log-schema.md`](0005-telemetry-and-log-schema.md);
read it before interpreting paths.* **[decided]**

ADR 0005 owns the names and the habits. Two copies means the one the
agent reads is the one that goes stale, and the agent is the reader who
cannot tell.

### Answers go in chat; a written report is a judgement call

A report under `docs/matches/` is worth writing **when comparing across
matches**, because it makes the *next* comparison cheap — the agent
reads last week's report instead of re-analysing last week's log.
Everywhere else it is a file nobody opens. Left to judgement with that
hint, not mandated. **[decided]**

### Packaging: `tools/logtool/`, a `uv run` script with PEP 723 metadata

Inline dependency metadata in the script header. No venv to create, no
`pip install` step, no README instruction a student can skip.
**[decided]**

It is additive to the stock template, so ADR 0003's *unmodified
template* is undisturbed — unlike ADR 0013's `--add-opens`, this is a
new directory rather than a departure.

## Consequences

- **Renaming a signal is a breaking change to `/analyze-match`.** ADR
  0005 already names this from its side. From this side it is sharper:
  the skill is the only place the names live, so a rename that misses it
  produces an agent that queries a path returning nothing and reports
  *clear*. The completion criterion makes that visible — the class is
  still reported — but *clear* and *absent* read alike to a human
  skimming. The habit is to grep the skill when renaming a signal.

- **REVLib's `.revlog` is invisible to this tool and to
  `/analyze-match`.** ADR 0009 keeps REVLib's Status Logger on by
  default as free insurance against under-logging. `logtool` reads
  WPILOG; converting a `.revlog` needs AdvantageScope or the npm package
  `@rev-robotics/revlog-converter`, a Node toolchain in a repo whose
  only tool is Python `uv run`. **Nothing in a `.revlog` reaches
  `/analyze-match`**, and nothing here plans to change that: it is a
  second, redundant record for a human with AdvantageScope open, not an
  input to this contract.

- **Anything not routed through telemetry is invisible by
  construction.** ADR 0005 sets `logNetworkTables(false)`, so the WPILOG
  holds only what we deliberately log. `/Tunables` is the live case,
  already fog in ADR 0005 — and whoever wires it now has a second reason
  to route it: an unrouted tunable is invisible to `/analyze-match` as
  well as unattributable in the log.

- **CI is the first consumer, before any student is.** ADR 0013 uploads
  a failing sim test's WPILOG as a workflow artifact precisely so it can
  be handed to this tool. A CI log is just another WPILOG to a generic
  reader — no new machinery — and it means the contract gets exercised
  every time a physics test fails rather than first at an event.

- **Characterisation needs no tooling of its own.** A sysid log *is* a
  WPILOG, so `logtool query` already pulls the columns ADR 0009's
  analyser wants, and turning columns into gains is domain knowledge —
  exactly the boundary this ADR draws. The caveat that makes that
  non-trivial is in *Traps*.

- **The second map inherits a spec, not a tool.** Roughly 500 lines of
  Python across a vendored reader, a subset struct decoder, a protobuf
  hookup and five subcommands, plus one skill. Every argument is settled
  here; none of it is written.

- **`protobuf` is the project's first non-FRC runtime dependency.** The
  rule that admits it came from #16 and is recorded here because this is
  where it was settled, not because this ADR owns dependency policy. A
  later argument that needs it will find it filed under log analysis,
  which is a poor address for it.

## Traps

- **`DataLogManager` deletes old logs on its own, so "pull it later"
  eventually means "it's gone".** When free space on the log device
  falls below `FREE_SPACE_THRESHOLD` — 50 MB
  (`DataLogManager.java:69`) — the startup path deletes `WPILIB_*.wpilog`
  oldest-to-newest until either the threshold is met or
  `FILE_COUNT_THRESHOLD` files remain — 10 (`:70`, loop at `:313-341`).
  **[source]** It also deletes **every** `WPILIB_TBD_*.wpilog` at
  startup unconditionally (`:114-125`), which is every log from a
  session where the Driver Station never connected. **[source]** The
  bench Pi has 4.9 GB free of 6.8 GB, 25% used **[executed]** — hundreds
  of matches at ADR 0005's 13.1 MB **[measured, via ADR 0005]** — so the
  threshold is remote
  on internal storage and **near on a USB stick**, which is where
  `DataLogManager` writes when one is mounted. Pulling is a habit.

- **The `_{event}_{match}` filename is the only place match identity
  exists.** A log starts as `WPILIB_TBD_{random}.wpilog`, is renamed
  `WPILIB_yyyyMMdd_HHmmss.wpilog` when the DS connects, and is renamed
  again to `WPILIB_yyyyMMdd_HHmmss_{event}_{match}.wpilog` **only** once
  the FMS has been attached for more than 5 s *and* `MatchState`
  reports a match type other than `NONE`
  (`DataLogManager.java:40-43, 415-443`). **[source]** Nothing inside
  the file carries the event or the match number. That name is the free
  join key for question class 5, and renaming a file by hand destroys
  it.

- **`DS:controlWord` is the one struct in our own log the subset decoder
  cannot read, and it is the one that carries enable/disable.**
  `DriverStation.startDataLog(log, true)` writes it as a struct
  (`DriverStationBackend.java:389`) **[source]**, and its schema is
  enum-and-bitfield throughout:

  ```
  uint64 opModeHash:56;
  enum{unknown=0,autonomous=1,teleoperated=2,utility=3} uint64 robotMode:2;
  bool enabled:1;bool eStop:1;bool fmsAttached:1;bool dsAttached:1;
  ```

  **[source — `ControlWordStruct.java:28-32`]** Every feature the subset
  omits appears in that one schema, so it is reported **opaque**, with
  the schema text printed — which is the degrade-loudly rule doing its
  job on day one. Question class 3 therefore reads the opmode from
  `DS:opMode`, a plain string (`DriverStationBackend.java:396`)
  **[source]**, and enable/disable from our own signals, not from
  `DS:controlWord`. See *Open* — those signals are named in ADR 0005's
  `keepDuplicates` list but absent from its signal table.

- **An agent that OLS's the raw sysid columns gets gains that look
  plausible and are wrong.** The columns are ordinary WPILOG entries —
  `sysid-test-state-<logName>` for the state string and
  `<field>-<motor>-<logName>` for the numerics — so `logtool query`
  returns them and nothing warns. But `tools/sysid` runs **446 lines of
  preprocessing** in front of an 88-line regression, and it is not
  optional **[source, via ADR 0009]**: quasistatic data is trimmed before break-away, the dynamic
  data is trimmed at both ends, and **acceleration is the analyser's own
  central-difference derivative, not the log's**. ADR 0009's *Traps*
  carries all five steps with the function that performs each. An agent
  reproducing the fit must either reproduce those steps or say plainly
  that it did not.

- **With no Driver Station attached, the log stops after about ten
  seconds.** ADR 0005 records the mechanism; the consequence for this
  tool is that `stats` over "the whole log" on a bench session is
  `stats` over a 10 s window, and it looks like a complete log because
  nothing marks the truncation. `list`'s last-timestamp column is what
  makes it visible, which is why that column is in `list` and not
  buried.

- **A signal that was never logged has no schema entry and no
  metadata.** Schemas and unit metadata are written when an entry is
  *created* (`StructLogEntry.java:23`,
  `DataLogTelemetryBackend.java:150-171`) **[source]**, so a mechanism
  that never ran contributes nothing to the file — not an empty signal,
  no signal. `list` showing 48 signals where ADR 0005 describes 50 is
  evidence about the match, not about the tool.

## Open

- **None of this has been run, because none of it exists.** No
  `logtool`, no `/analyze-match`, and no WPILOG has ever been written by
  this project — the bench Pi has no log directory at all. **[executed]**
  Every design claim above is reasoned from verified source rather than
  from a log it processed. **[unverified]** *Unblocked by* the first
  WPILOG off the first real run, which ADR 0013's CI will produce before
  any chassis does.

- **The ~200-bucket default is chosen, not measured.** It follows from
  30,000 samples and a context window, and nobody has checked whether an
  agent reliably spots a one-sample spike in a 150-sample bucket's
  max-versus-mean gap. **[unverified]** *Unblocked by* one real match log
  with a known brownout in it.

- **ADR 0005's enabled and mode signals are named in its
  `keepDuplicates` list but do not appear in its signal table.** With
  `DS:controlWord` opaque to the subset decoder, question class 3 has no
  decodable source for enable/disable unless those signals exist.
  **[unverified]** *Unblocked by* ADR 0005 naming them explicitly, which
  is a one-line edit to that document and not a decision this ADR gets
  to make.

## Rejected

### A convention-aware CLI

The free diagnosis Context weighs it up for, against a second home for
our conventions that drifts **silently** — a stale convention produces a
plausible answer rather than an error, so nothing fails and nobody
looks. *Do not re-raise* without a mechanism that keeps it in step with
ADR 0005 automatically.

### `compare` and `summarise` subcommands

Both bake in a guess — `compare` about which comparison matters,
`summarise` about what is interesting. Both guesses are the agent's job.

### A bulk export to JSON, Parquet or CSV

It leaves the context-window problem exactly where it was, in a
different file format.

### Anomaly detection

The tool never answers *why*. Everything that would grow into anomaly
detection starts as a helpful sixth subcommand, which is why the
resistance is written down rather than assumed.

### `robotpy-wpilog`

**It exists and it works** — `2027.0.0a6.post4`, published 2026-07-11,
with cp311 through cp314 wheels for linux x86_64 and aarch64, macOS and
Windows. Installed and imported here on Python 3.14:
`DataLogReader(filename)` is iterable and `DataLogRecord` exposes the
full typed getter set. **[executed]** It is a native semiwrap binding of
the same C++ code, in-tree in allwpilib at
`datalog/src/main/python/` **[source]** — the *right* dependency in
every respect except one.

That one is the rule. It is FRC-coupled: pinned to a season, pinned to
`robotpy-wpiutil==` at the same version, requiring
`--prerelease=allow` to resolve at all **[executed]**, and currently one
alpha behind the tree we build against. Its readiness is RobotPy's
schedule, not ours. Against that, the vendored 354-line reference parser
has no dependencies, no platform wheels and no season — and does the
same job.

*Do not re-raise* on the grounds that it exists. That was checked, it
does, and the decision is about coupling.

### Java `DataLogReader`

It would decode structs for free through the real `Struct` classes,
which is the ~1,700 lines we are declining to write. It also puts a
Gradle build between the agent and an answer, on every invocation, and
makes the tool depend on the robot project compiling.

### Writing a WPILOG parser

WPILib ships three. Writing a fourth is work in exchange for a defect
surface.

### A fully generic struct decoder

~1,700 lines to implement enums, arrays and bitfields **[source]** — for
which this project logs, through telemetry, exactly nothing. The subset
plus loud degradation covers everything and names what it cannot do.

`DS:controlWord` is the one schema that would justify revisiting, and
the cost is real rather than hypothetical: it is the only decodable home
the file currently gives the enable bit. It is still not enough. The
enable state is a signal ADR 0005 already means to log, so the cheap fix
is one line in ADR 0005's signal table rather than 1,700 lines here. If
that line never lands, this is the decision to reopen — see *Open*.

### Decoding no structs at all

The cheaper option, and it leans on ADR 0005's decision to log modules
twice — as a `SwerveModuleVelocity[]` *and* as named scalar subtables —
so module data survives without a decoder. It loses **pose**, which is
struct-only and is question class 2. Rescuing it would mean adding
scalar pose signals to ADR 0005 to work around a limitation of the tool,
which is the tail wagging the log.

### A hand-rolled protobuf wire walker

~150 lines to avoid a dependency we are happy to take, and it is
underestimated: the `optional` fields in `protobuf_commands.proto`
**[source]** are proto3 synthetic oneofs, so a hand walker meets
descriptor machinery it was written to avoid. *Do not re-raise* —
`protobuf` is the paradigm case of the dependency rule, not an exception
to it.

### Every-Nth-sample decimation

It drops the one-sample events silently, which is worse than dropping
them loudly.

### `pull` as part of the deploy tooling

It couples a retrieval you want after a match to an action you take
before one.

### Mandating a written report for every analysis

Outside the cross-match case, it is a file nobody opens, written every
time.

### AdvantageScope's export as the retrieval path

It is a GUI step in the middle of an agent's workflow. AdvantageScope
stays what it is — the thing a student opens to look at a plot — and it
is the answer for `.revlog`, which this tool cannot read at all.

## Source

Decided in
[#16](https://github.com/Drew-Robotics/2027beta/issues/16), which
carries the CLI-generic-skill-specific split, the five subcommands and
the instruction to resist a sixth, the bucketing rule, the parsing
decisions, the retrieval design, the five question classes and the
exhaustive completion criterion.

**Two of #16's claims are corrected here.**

`robotpy-wpilog` **does** exist for 2027. #16 recorded that it does not
and would not until RobotPy shipped a season release; PyPI carries eight
2027 releases going back to `2027.0.0a2` on 2025-07-24, and
`2027.0.0a6.post4` installs and reads logs on Python 3.14.
**[executed — 2026-08-27]** The decision to vendor the pure-Python
parser is unchanged, and its ground moves from *absence* to *coupling* —
which is the rule #16 itself set, applied to evidence #16 got wrong.

#16 sized a match at 7,500 samples per signal, at 20 ms. ADR 0002 raised
the loop to 5 ms, so the figure is **30,000**. The bucketing argument is
strengthened, not weakened, and the ~200-bucket default is unchanged.

#16 recorded 5.0 GB free at `/home/systemcore/logs`. The bench Pi has
4.9 GB of 6.8 GB free, 25% used, and `/home/systemcore/logs` does not
exist — nothing has written a log yet, so the figure is the root
filesystem's. **[executed — 2026-08-27]**

Line numbers carried from #16 that resolve differently in the tree at
`cafb0cc79`: the `proto:FileDescriptorProto` write is
`DataLog.java:148-154`, not `:150`; the log-deletion loop is
`DataLogManager.java:313-341`, not `:311-349`. The console pipe at
`DataLogManager.java:300` and the schema recursion via `getNested()` are
as #16 recorded them.

The names and habits this contract reads are ADR 0005; the loop rate
that sets the sample count is ADR 0002; the pose-error decomposition of
question class 2 is ADR 0011; the tunable fog is ADR 0005's *Open*; the
`.revlog` consequence and the sysid preprocessing steps are ADR 0009;
the CI artifact that makes this tool's first consumer a workflow is ADR
0013; the template rule its packaging leaves undisturbed is ADR 0003.

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7):
`datalog/examples/printlog/datalog.py`,
`datalog/src/main/java/org/wpilib/datalog/DataLog.java`,
`datalog/src/main/java/org/wpilib/datalog/StructLogEntry.java`,
`datalog/src/main/java/org/wpilib/datalog/StructArrayLogEntry.java`,
`datalog/src/main/java/org/wpilib/datalog/ProtobufLogEntry.java`,
`datalog/src/main/python/pyproject.toml`,
`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java`,
`wpilibj/src/main/java/org/wpilib/backend/DataLogTelemetryBackend.java`,
`wpilibj/src/main/java/org/wpilib/driverstation/internal/DriverStationBackend.java`,
`hal/src/main/java/org/wpilib/hardware/hal/struct/ControlWordStruct.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/DynamicStruct.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/StructDescriptor.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/StructFieldDescriptor.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/StructFieldType.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/StructDescriptorDatabase.java`,
`wpiutil/src/main/java/org/wpilib/util/struct/parser/`,
`wpimath/src/main/java/org/wpilib/math/kinematics/struct/SwerveModuleVelocityStruct.java`,
`wpimath/src/main/java/org/wpilib/math/geometry/struct/Pose2dStruct.java`,
`wpimath/src/main/java/org/wpilib/math/geometry/struct/Translation2dStruct.java`,
`wpimath/src/main/java/org/wpilib/math/geometry/struct/Rotation2dStruct.java`,
`commandsv3/src/main/proto/protobuf_commands.proto`.
