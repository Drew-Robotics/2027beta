# ADR 0007 — CAN bus topology and frame allocation

## Status

Accepted — 2026-08-26.

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. REVLib
`[source]` claims were read in `REVLib-java 2027.0.0-alpha-6`, the
version `vendordeps/REVLib.json` pins, from its sources jar; paths are
given as `com/revrobotics/...`. Phoenix 6 `[source]` claims were read in
`wpiapi-java-26.50.0-alpha-1-sources.jar` from
`maven.ctr-electronics.com`, the version the
`Phoenix6-26.50.0-alpha-1.json` vendordep pins; paths are given as
`com/ctre/phoenix6/...`. An unqualified path is a file in this repo.

**Every frames-per-second figure in this document is arithmetic over
documented frame periods.** None has been read off a bus — no CAN
hardware has ever been attached to the bench — so they carry
**[unverified]** rather than `[measured]`, and only the facts actually
measured on the Pi keep `[measured]`. The arithmetic is checkable; the
periods it runs on are `[source]`. See Open.

⚠️ **Three rows of #28's frame table do not survive a re-read of the
REVLib and Phoenix sources, and are corrected in the table below rather
than narrated around it.** The corrections move the standing total from
~3680 to ~3920 and change no decision. What each one was, and why it
was wrong, is in Rejected under *#28's frame table as written*.

## Context

Eight SPARK Flexes and one Pigeon2 have to reach SystemCore. SystemCore
offers five native CAN buses, `can_s0` through `can_s4`
(`wpilibj/src/main/java/org/wpilib/hardware/bus/CANBus.java:12-62`)
**[source]**, so the wiring is a choice rather than a constraint.

A CAN bus is not a pipe you pour bytes into. It carries discrete
frames, and what runs out first is **frames per second**, not bits per
second. Every periodic signal a device publishes is a frame arriving on
a fixed period, and every setpoint we write is a frame going the other
way. Raise a period and you spend frames; run out of frames and the bus
drops them, which on a drive base means odometry that silently stops
updating.

ADR 0002 puts the robot loop at 5 ms. That decides nothing about CAN by
itself — REVLib publishes status frames on the device's own schedule,
and polling a getter at 200 Hz against a 20 ms frame returns the same
cached value four times **[source, via #28]**. Getting 200 Hz odometry
means paying for 200 Hz frames, explicitly.

ADR 0008 puts both module loops on the SPARK and closes steer against
the analog absolute encoder. That is what decides *which* frames we
buy, because module angle then arrives on a different frame group than
it would have otherwise.

This ADR decides how many buses we run, which frames ride them at which
periods, and what a tuning session is allowed to do to that budget.

## Decision

### One bus carries everything

All eight SPARKs and the Pigeon2 sit on a single SystemCore bus.
**[decided]**

The reason is the failure mode, not the bandwidth. Splitting the drive
base across two buses buys headroom we do not need and buys with it a
*partial* failure: the gyro survives and four modules do not, or two
modules answer and two do not. A drive base in that state is worse than
one that is plainly down, because it will still accept a command and
still try to execute it. **We would rather drive only if one bus is
working than own a half-working drive base.**

The accepted cost is that the SPARKs are CAN 2.0 devices, so the bus
runs classic framing and `can_s0`'s CAN FD capability — mtu 72 on the
Pi's `can_s*` buses **[measured — #28]** — goes unused. That cost is
paid knowingly and is not a reason to revisit the topology.

### The frame allocation

At roughly 131 bits per extended frame, 1 Mbit gives about **6400–7600
frames/s** **[unverified — arithmetic; `docs/research/loop-rate.md`]**.
That is the ceiling every number below is measured against.

| Source | Frames | Period | Frames/s |
|---|---|---|---|
| **Status2** — drive primary encoder position + velocity × 4 | 4 | 5 ms | 800 |
| **Status3** — steer analog position + velocity + voltage × 4 | 4 | 5 ms | 800 |
| **Setpoint writes** × 8 | 8 | 5 ms | 1600 |
| **Pigeon2** — the quaternion frame and the yaw-rate frame | 2 | 5 ms | 400 |
| **Status1** — faults, warnings × 8 | 8 | 50 ms | 160 |
| **Status0** — applied output, bus voltage, current, temperature, limits × 8 | 8 | 100 ms | 80 |
| **Status8** — setpoint readback × 8 | 8 | 100 ms | 80 |
| **Status5** — absolute encoder: never requested, never enabled | 0 | — | 0 |
| **Status2 on steer** — relative encoder, diagnostic: never requested | 0 | — | 0 |
| | | **total** | **~3920** |

**~3920 frames/s is 52–61% of the ceiling** **[unverified —
arithmetic]**, plus the Pigeon's 0–5% diagnostic floor **[source, via
#5 — CTRE's `canbus-utilization` migration doc]**. The two 5 ms encoder
rows plus the setpoint writes are 82% of the traffic and are
irreducible at 200 Hz.

#28 and #29 record this budget as **~3680 frames/s, 48–57%**, and #29's
revision of #28 is what splits the eight controllers across Status2 and
Status3. Three rows have been corrected since — the Pigeon2 row, the
Status5 row and steer's Status2 row — for the reasons in Rejected. The
difference is 240 frames/s and no decision turns on it.

**One row is conditional and worth knowing before you spend it.**
Steer's Status2 costs nothing while nobody reads it, because *"status
frames are only enabled when a signal is requested via its respective
getter method"* (`com/revrobotics/spark/config/SignalsConfig.java:276-279`)
**[source]**. Log the steer relative encoder as a diagnostic — which is
worth doing when someone is chasing backlash — and it enables at its
20 ms default for **+200 frames/s, ~4120 total, 54–64%**. That is
affordable and it is not free.

The setter names for those periods are not guessable, so:
`primaryEncoderPositionPeriodMs` and `primaryEncoderVelocityPeriodMs`
are Status2 (`SignalsConfig.java:303-304, 267-268`);
`analogPositionPeriodMs`, `analogVelocityPeriodMs` and
`analogVoltagePeriodMs` are Status3 (`:411-412, 375-376, 339-340`);
`faultsPeriodMs` and `warningsPeriodMs` are Status1 (`:192-193,
230-231`); the five Status0 signals are `:95-96, 115-116, 135-136,
155-156, 175-176`; `setpointPeriodMs` is Status8 (`:708-709`).
**[source]**

### Module angle rides Status3, and that is ADR 0008's doing

This is the one line of the table a reader will not predict, and it is
worth stating as a consequence rather than a coincidence.

With steer closing on the SPARK against the analog absolute encoder,
the angle odometry needs is the **analog** position — Status3 — not the
primary encoder's — Status2. So the eight controllers split: **four
drive SPARKs publish Status2 at 5 ms, four steer SPARKs publish Status3
at 5 ms.** **[decided]**

**Steer's Status2 drops to its default and the steer relative encoder
becomes diagnostic.** Nothing in the control path or the odometry path
reads it. It is worth logging when someone is chasing a mechanical
problem — motor position against module angle is exactly the
measurement that shows backlash — and it is worth nothing at 5 ms.

The net is that ADR 0008's arrangement costs the bus **nothing**
relative to the naive one: four frames moved from one group to another
at the same rate. Closing steer on SystemCore instead would have needed
Status3 at 5 ms **in addition to** Status2 on all four steer
controllers — **+800 frames/s to ~4720, 62–74%** **[unverified —
arithmetic; #28 and #29]**.

### PID telemetry is the baseline plus one readback

A loop that runs on the controller is a loop whose error nobody can
see, and the instinct is to buy the frames back. Most of that instinct
is misplaced: the **setpoint** is a value we wrote, so it costs no bus;
the **measurement** is already there for odometry; **error** is those
two subtracted. **[decided]**

What we buy is exactly one always-on frame beyond what control and
odometry need: **Status8's `setpoint`, at 100 ms**, for **80
frames/s**, 2% of the bus. It is the Status8 row of the table.

It earns its 2% by being the only signal that separates two failures
that otherwise look identical. Large persistent error on a module means
either *the SPARK never received the setpoint* — it browned out and
came back at default frame rates, silently, since frame rates do not
survive a power cycle **[source, via #28 — REV documents this]** — or
*the SPARK has the setpoint and cannot reach it*, which is a mechanical
bind. Reading the controller's own copy of the setpoint tells you
which, and no amount of robot-side logging can.

This is not the blocking-round-trip readback ADR 0004 rejected. That
argument was about `configAccessor` getters, which are individual
blocking CAN transactions **[source, via #5]**. A periodic status frame
costs its period and nothing else.

### Tuning raises three frames on one named SPARK

Full-rate PID internals are affordable. They are not affordable eight
times over.

A tuning opmode raises **Status0 + Status7 + Status8 to loop rate on a
single SPARK** — applied output, `iAccumulation`
(`SignalsConfig.java:671-672`) and setpoint **[source]** — for **+580
frames/s to ~4500, 59–70%**. **[unverified — arithmetic]** Which
controller is instrumented is a constant in `Constants` naming one
module and one motor role, so it is a redeploy rather than a dashboard
setting, and the raised budget shows up in the diff.

One controller is enough because the modules are identical and ADR 0004
shares gains through a factory method per motor role: you tune one and
apply the winner to four. Status9 is MAXMotion (`:819-820, 856-857`)
**[source]** and neither loop takes a profile, so it stays off.

**59–70% is the largest number in this document, and it is deliberate.**
A tuning session is the one time the bus is allowed to run near its
ceiling, because it happens on a bench with nobody on the field and it
ends when the gains are written into the repo.

### One project constant per physical bus, converted at each call site

The two vendors take the bus as different types and there is no
overload that hides it:

```java
new SparkFlex(busId, deviceId, MotorType.kBrushless)   // int, first argument
new Pigeon2(deviceId, CANBus.systemcore(0))            // a CTRE CANBus object
```

**[source, via #5]**

So `Constants` holds **one constant per physical bus**, typed as
WPILib's `org.wpilib.hardware.bus.CANBus`, and each call site converts:
`.value` for REVLib, `CANBus.systemcore(n)` for Phoenix. **[decided]**
The numbering agrees exactly across the two vendors — `CAN_S0(0)`
through `CAN_S4(4)`, and CTRE's `systemcore(int)` validates 0–4
(`com/ctre/phoenix6/CANBus.java:129-132`) **[source]** — so the
conversion is mechanical and there is no off-by-five between our SPARKs
and our gyro.

The constant exists so that *"which bus is the drive base on"* has one
answer in the repository rather than nine literals. It is deliberately
not a wrapper type over the two vendor types, because the thing most
worth seeing at a call site is that CTRE's `CANBus` and WPILib's
`CANBus` are different types that share a simple name — and a wrapper
is exactly what would let somebody stop noticing that.

## Consequences

- **Tuning all eight SPARKs at loop rate does not fit.** Status6+7+8 at
  5 ms on eight controllers is +4720 frames/s, taking the total to
  **8640 — past the 6400–7600 ceiling.** **[unverified — arithmetic;
  #29 computes 8480 from the uncorrected baseline]** Not "tight", past
  it. The same three frames at 50 ms on all eight fits (+400, ~4320,
  57–68%) and is the fallback if one controller ever turns out not to
  be enough. The named-single-SPARK rule is what keeps a tuning session
  from being a bus failure.

- **REVLib's Status Logger is a sink, not a source, and cannot help
  here.** `com/revrobotics/util/StatusLogger` writes REV device data to
  a `.revlog` on disk and *"logging begins automatically on the first
  call to any REVLib function"* (`StatusLogger.java:33-48`)
  **[source]**. It records what the bus already carries. It cannot
  raise a signal's rate, cannot recover a frame that was never
  published, and cannot dodge one frame of this budget — the only thing
  it can change is disk. Anyone reaching for it to solve an
  observability problem is solving the wrong problem; the lever is
  `SignalsConfig`. Whether we keep its auto-start at all is ADR 0004's
  question, alongside Phoenix's `.hoot` logging.

- **Odometry is bounded by the slowest frame in the chain, and the
  Pigeon is not automatically in it.** Phoenix defaults are per-signal
  and lower than our loop: the quaternion signals `getRotation3d()`
  goes through default to **50 Hz** on CAN 2.0
  (`com/ctre/phoenix6/hardware/core/CorePigeon2.java:667-680`), and
  `getAngularVelocityZWorld()` defaults to **10 Hz** (`:1625-1639`).
  **[source]** ADR 0012 raises both to loop rate; this ADR budgets the
  two frames that costs. Because our bus runs classic framing, the CAN
  2.0 column is the one that applies — the FD column's 100 Hz is not
  available to us and never will be while SPARKs share the bus.

- **Frame rates are lost on a brownout and nothing says so.** A SPARK
  that resets comes back at default periods with no fault and no log
  entry **[source, via #28]**, so odometry from that module degrades
  from 5 ms to 20 ms silently. Ruled **not worth handling** — a SPARK
  rebooting mid-match is a larger problem than its frame rates — but it
  is the reason the Status8 readback is always-on rather than
  tuning-only.

- **Bus headroom is not a per-bus independent budget, if we ever
  split.** SystemCore's buses share SPI controllers pairwise, and the
  limit is a frames-per-second ceiling per SPI bus rather than
  bandwidth: CTRE measured FD 8-byte frames dropping from **43%** on
  S3+S4 together against 85% on one bus alone **[source —
  `docs/research/vendordeps.md`, quoting SystemcoreTesting #342]**.
  Irrelevant at 3920 frames/s on one bus. It becomes the first thing to
  read if the multi-bus question under Open is ever answered.

- **Phoenix's diagnostic server is a standing CAN cost we chose not to
  pay for.** It auto-starts on constructing any CTRE device and adds a
  *"constant 0-5% total CAN bus utilization"* **[source, via #5]**.
  That is the 0–5% floor quoted alongside every total here. Killing it
  is `Unmanaged.setPhoenixDiagnosticsStartTime(-1)`, at the cost of
  Tuner X; ADR 0004 owns that call.

## Traps

- **`SignalsConfig` period setters keep the minimum, so the fastest
  consumer sets the whole frame group.** `setPeriodMsCore` does
  `putParameter(parameterId, Math.min(currentPeriodMs, periodMs))`
  (`SignalsConfig.java:39-47`) **[source]**, and several signals share
  each group. Motor temperature cannot be slow while applied output is
  fast — they are both Status0. The practical consequence is that a
  tuning session's bus cost is always larger than the one signal
  someone asked for: raising applied output drags bus voltage, output
  current, motor temperature and limit switches up with it, four
  signals nobody wanted. Budget the *group*, never the signal.

  The `AlwaysOn` setters compose the other way — `currentValue ||
  enabled` (`:50-64`) **[source]** — so one `true` anywhere pins the
  group on. Between the two, a `SignalsConfig` assembled from several
  places is monotone: periods only ever get faster and frames only ever
  get more enabled, and nothing later can walk either back.

- **Never write `new CANBus()`.** CTRE's no-argument constructor is
  documented as *"Creates a new CAN bus using the default for the
  system: `can_s1` on Systemcore"* (`CANBus.java:53-61`) **[source]** —
  **`can_s1`, not `can_s0`.** CTRE's own SystemcoreTesting markdown
  documents this wrongly, telling readers the default is `can_s0`, and
  misspells the factory as `systemCore` **[source, via #5]**, so the
  documentation a student is most likely to find is the documentation
  that is wrong. Always pass the bus. The failure mode is a device that
  constructs cleanly and never answers, on a bus with nothing on it.

- **`CANBus` is two different types with the same simple name.**
  `org.wpilib.hardware.bus.CANBus` is an enum carrying an `int value`;
  `com.ctre.phoenix6.CANBus` is a class with `systemcore(int)` and
  `motioncore(int)` factories. **[source]** A file cannot import both,
  and the one that compiles is not necessarily the one that was meant —
  `CANBus.CAN_S0.value` and `CANBus.systemcore(0)` are both valid
  expressions in their own file.

- **`CANBus.motioncore(n)` compiles, validates and does not work.**
  CTRE's known-issue list: *"Motioncore CAN buses are not supported.
  Only the Systemcore native CAN buses and CANivores are currently
  functional."* **[source, via #5]** Irrelevant while we are on
  `can_s*`, and a live trap the moment somebody reads WPILib's
  `CAN_D0`–`CAN_D19` enum constants and assumes they are usable.

- **The published REV frame table is stale, and two groups have
  swapped.** REVLib 2027 ships **ten** frame groups where
  docs.revrobotics.com documents seven, and faults and temperature have
  changed frames between them **[source —
  `docs/research/loop-rate.md` has the comparison]**. The parameter
  enum shows the history: `kStatus0Period` through `kStatus7Period` are
  ids 158–165, contiguous, while `kStatus8Period` is **199** and
  `kStatus9Period` is **224** (`SparkParameters.java:146-153, 187,
  212`) **[source]** — the two new groups were bolted on later, which
  is why no published table has them. Read the setter, not the doc.

- **Frames are lazily enabled, so a budget row can be zero until
  somebody logs something.** *"Status frames are only enabled when a
  signal is requested via its respective getter method"*
  (`SignalsConfig.java:276-279`) **[source]**. This cuts both ways: it
  is why Status5 costs nothing, and it is why adding one diagnostic log
  line can add 200 frames/s without any config change. The
  corresponding `AlwaysOn` setters exist to pin a frame on regardless,
  at the cost of paying for it always.

- **Phoenix has the same fastest-consumer rule and states it in
  reverse.** `optimizeBusUtilizationForAll` slows every signal *"that
  has not been explicitly given an update frequency"*, but *"if other
  status signals in the same status frame have been given an update
  frequency, the update frequency will be honored for the entire
  frame"* (`com/ctre/phoenix6/hardware/ParentDevice.java:388-412`)
  **[source]**. So optimising the Pigeon will not slow the frames ADR
  0012 raised, and a reader expecting it to will mis-budget in the safe
  direction. Note also it *"will wait up to 0.100 seconds (100ms) for
  each status frame"* — a real startup cost on a device with seventy
  signals.

- **Phoenix warnings and errors may not reach the Driver Station on
  SystemCore** — they go to stderr **[source, via #5]**. A bus problem
  on the Pigeon side can therefore be invisible to the operator.
  Anything operator-facing routes through `org.wpilib.util.Alert`
  ourselves; ADR 0004 owns that path.

- **Upstream defect: CAN receive timestamps are not hardware
  timestamps.** SystemcoreTesting #122, still open — the `mcp251xfd`
  driver substitutes `ktime_get_raw()` at driver-receive time, giving
  *"nondeterministic latency between the actual reception time and the
  reported timestamp"* **[source — `docs/research/vendordeps.md`]**. It
  is recorded here because it is a property of this bus rather than of
  any one signal; what it means for telemetry is ADR 0005's. Delete
  this note when #122 closes.

## Open

- **The budget has never been read off a bus.** **[unverified]** Every
  frames-per-second figure here is arithmetic over documented frame
  periods, against a lazily-enabled device, on hardware that has never
  been assembled. The arithmetic is checkable and the periods are
  `[source]`; what is unverified is that the devices behave as
  documented and that nothing else is talking. Two specific things sit
  inside that:

  **Eight real `getPosition()` calls per loop are not in ADR 0002's
  timings** **[measured — #28 records this as its one caveat]**. The
  1.3% duty cycle at 5 ms was measured with no CAN hardware present,
  and a 5 ms budget absorbs a surprise there four times less
  comfortably than a 20 ms one.

  **The `configAccessor` per-read latency is unmeasured** **[source,
  via #5]** — ADR 0004's open risk rather than this one's, but it lands
  on the same bus at the same boot.

  *Unblocked by* a bus with the real device count on it and `candump`
  running. At that point all of it is worth recording in
  `docs/research/` whichever way it falls, and the three corrected rows
  in particular are worth confirming on the wire.

- **Multi-bus device allocation is fog.** The *convention* is settled
  and is in the Decision: one project constant per physical bus, never
  `new CANBus()`. **Which** devices sit on which bus, and whether a
  drive base ever needs more than one, is not — and cannot be until
  there are mechanisms beyond the drive base to allocate. Today's
  answer is *one bus, everything on it*, and that is a decision about
  failure modes rather than a measurement of headroom, so more headroom
  would not change it. *Unblocked by* a robot with a second mechanism
  on it, at which point the SPI pairing note in Consequences is the
  first thing to read.

## Rejected

### #28's frame table as written

Three rows were carried into the Decision and corrected there. They are
recorded here rather than in Open because each one is settled by
reading a source, not by waiting for a measurement.

**#35's rule that a conflict "goes back to the map as a new ticket
rather than being resolved in the document" is scoped to a conflict
between two closed tickets. These are conflicts between a ticket and
vendor source, so the rule does not apply** — and #35's own review bar
requires the opposite, that claims be checked against source while
writing rather than asserted. The size of the delta is not what makes
them resolvable here.

- **Status5 at "its 200 ms default" for 40 frames/s → 0.** Two
  independent errors. The period is wrong: REVLib 2027 documents the
  default for `absoluteEncoderPositionPeriodMs` and
  `absoluteEncoderVelocityPeriodMs` as **20 ms**
  (`SignalsConfig.java:557, 565-566, 529-530`) **[source]**; 200 ms is
  the stale 2025 SPARK MAX table. And the row's justification is
  already dead: #28 kept Status5 because *"steer reads the absolute
  encoder once at boot to seed the relative encoder"*, which is exactly
  the boot-seeding arrangement #29 rejected on backlash. Status5
  carries the **duty-cycle** absolute encoder and ADR 0008 uses the
  **analog** sensor, so we never construct a `SparkAbsoluteEncoder`,
  nothing requests the signal, and the frame is never enabled.

- **Steer's Status2 at 0 unconditionally → 0, or 200 if logged.** #29
  says steer's Status2 "stays at its default" and does not price it.
  Under lazy enabling it costs nothing while unread, and 4 × 50 = 200
  frames/s at its 20 ms default the moment the diagnostic is logged.
  Priced in the Decision as a conditional row.

- **"Pigeon2 yaw, 5 ms, ~200" → two frames, 400.** #28 wrote that
  before pose estimation was decided. ADR 0012 reads `getRotation3d()`,
  which refreshes the four quaternion signals, **and**
  `getAngularVelocityZWorld()`, and raises both to loop rate. Those are
  two different frames — their CAN 2.0 defaults differ, 50 Hz against
  10 Hz (`CorePigeon2.java:667-680, 1625-1639`) **[source]** — so two
  frames at 200 Hz. 0012 chooses the signals and says the cost is *"one
  line in ADR 0007's budget"*; this is that line. 400 is a floor: the
  device's other default-rate frames keep publishing unless
  `optimizeBusUtilization` is called, which is ADR 0004's call.

`docs/research/loop-rate.md` carries the same three rows and has been
annotated rather than rewritten — the measured numbers in it stand, and
the frame table in it now points here.

### Splitting the drive base across two buses

Real benefit: roughly half the utilisation per bus, and the SPI-pairing
measurements say two buses at ~26% each drop nothing.

Rejected on the failure mode, as the Decision sets out — the gain is
headroom we do not need at 52–61%, and the cost is a drive base that
can come up half-working and still accept commands.

*Do not re-raise* on utilisation grounds alone. It re-opens when there
are enough mechanisms that the allocation question exists on its own
terms — see Open — and the argument then has to be about what fails
together, not about percentages.

### Putting the Pigeon2 on its own bus to get CAN FD

`can_s0`–`can_s4` are mtu 72 and CAN FD is live at 5 and 8 Mbps
**[measured — #28; source — `docs/research/vendordeps.md`]**, and a
Pigeon alone on an FD bus would run its signals at the FD column's
rates rather than the CAN 2.0 column's.

Rejected: it is the split above wearing a smaller hat, and it buys the
one device whose signals we already raise by hand. The FD capability
going unused is a cost this ADR accepts by name.

### Selective fast sampling — high rates on odometry frames only

This is what the table already does; naming it as rejected is about the
*generalisation*. ADR 0002 decided one global rate rather than
per-signal rates on the robot side, and the same discipline holds on
the bus: Status2 and Status3 at 5 ms because odometry needs them,
everything else as slow as it can usefully be. The rejected version is
the one where individual signals acquire their own periods over a
season until nobody can say what the bus carries.

The `Math.min` rule in Traps is what makes the drift real rather than
theoretical: a period set in one place silently becomes the period
everywhere in that group.

### REVLib's Status Logger as the observability answer

A disk sink fed by frames that already exist, as Consequences sets out.
It cannot raise a rate and changes no number in this document. Rejected
as a category error rather than as a trade-off.

## Source

Decided in
[#28](https://github.com/Drew-Robotics/2027beta/issues/28), which
carries the one-bus topology, the frame table and the utilisation
totals; revised by
[#29](https://github.com/Drew-Robotics/2027beta/issues/29), which moves
module angle from Status2 to Status3 as a consequence of closing steer
on the analog encoder, and which decides the Status8 readback and the
one-SPARK tuning allowance. Bus naming, the `new CANBus()` default and
Phoenix's background costs are
[#5](https://github.com/Drew-Robotics/2027beta/issues/5). The three
corrected rows are departures from #28 and #29 and are commented there.

The loop rate this budget is built for is #28 and ADR 0002. The loop
placement that decides which frames we buy is #29 and ADR 0008. The
Pigeon2 signals that ride this bus, and the rates they are raised to,
are ADR 0012. Configuration, alerts and the vendor auto-logging
question are ADR 0004; what CAN timestamps mean for telemetry is ADR
0005; gains and the tuning procedure that spends the raised budget are
ADR 0009.

Research: [`docs/research/loop-rate.md`](../research/loop-rate.md) for
the frame-group comparison, the measured duty cycle and the
frames-per-second ceiling;
[`docs/research/vendordeps.md`](../research/vendordeps.md) for the bus
naming, the SPI-pairing measurements, Phoenix's diagnostic server and
the CAN timestamp defect.

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79`
(alpha-7): `wpilibj/src/main/java/org/wpilib/hardware/bus/CANBus.java`.

In REVLib `2027.0.0-alpha-6` (sources jar):
`com/revrobotics/spark/config/SignalsConfig.java`,
`com/revrobotics/spark/config/SparkParameters.java`,
`com/revrobotics/util/StatusLogger.java`.

In Phoenix 6 `26.50.0-alpha-1` (sources jar):
`com/ctre/phoenix6/CANBus.java`,
`com/ctre/phoenix6/hardware/ParentDevice.java`,
`com/ctre/phoenix6/hardware/core/CorePigeon2.java`.
