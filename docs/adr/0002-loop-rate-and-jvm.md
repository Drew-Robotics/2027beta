# ADR 0002 — 200 Hz loop, no JVM tuning, no loop-count assumptions

## Status

Accepted — 2026-08-26.

Claim tags are defined in the index. `[source]` claims here were read at
`~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. `[measured]`
claims were taken on the SystemCore Pi at `192.168.1.202`
(limelightosr-2027.0.0-beta14, MRC API 11, Temurin 25.0.2+10); the
numbers and their methods live in
[`docs/research/loop-rate.md`](../research/loop-rate.md) and
[`docs/research/jvm-tuning.md`](../research/jvm-tuning.md), and the
harnesses in [`docs/research/loop-bench/`](../research/loop-bench/). An
unqualified path is a file in this repo.

## Context

The loop period is not a preference. It is the number the rest of the
project divides by: every `PIDController`, every filter window, every CAN
frame period, and the size of a match log. Getting it from tradition —
20 ms, because that is what the roboRIO did — would leave every one of
those calibrated against a number nobody checked.

Underneath it sits a second question that only Java robots have. A 5 ms
period gives the JVM 5 ms to stay out of the way, and the received wisdom
is that it will not: garbage collection, JIT, and a class loader that
runs whenever it feels like it. That wisdom names a fix — pin a
collector, tune a pause goal, precompile — and each of those is
configuration a student would have to justify later.

Both were settled by measuring on the actual Pi rather than by arguing.

## Decision

### The loop period is 5 ms — 200 Hz — for everything

One rate, raised globally. There is no fast sampling for some signals and
slow for others, no second periodic callback at a different rate, and no
split-rate telemetry. **[decided]**

`Robot`'s constructor passes it up:

```java
public Robot() {
  super(Constants.LOOP_PERIOD.in(Seconds));
}
```

`OpModeRobot` takes a period in seconds as a `double`
(`OpModeRobot.java:513`), so the conversion happens at that one call.
**[source]**

What it costs, on the Pi, running a swerve-shaped workload plus ~50
telemetry signals to both NetworkTables and a WPILOG: **65 µs of work per
loop, a 1.3% duty cycle** (4.5% at p99). **[measured]** Compute was never
the constraint. The log costs **13.1 MB per 150 s match** on 5.0 GiB free
— budget nearer 20 MB once the signals are real — so ADR 0005's *log
every signal every loop* survives the rate change untouched. **[measured]**

Away from transitions the Pi holds the period: worst-case wake-to-wake is
**5.16–5.23 ms**, about 200 µs of jitter. **[measured]** That figure was
taken with the loop thread raised to `SCHED_RR` 30, which is not what we
ship — see Open.

Raising the rate does **not** by itself give higher-fidelity odometry.
REVLib sets status-frame periods per frame *group*, so polling a 20 ms
frame at 200 Hz returns the same cached value four times.
**[source — `docs/research/loop-rate.md`]** Getting fresh
encoder data 200 times a second is a CAN decision, and it is ADR 0007's.

### `Constants.LOOP_PERIOD` is the only place 5 ms is written

A `Time`. Every `period` or `dt` argument anywhere in the project reads
that field — never a literal, and never the short constructor overload
that defaults it. **[decided]**

Mechanisms do not hold a `Robot` reference — they are fields *on* it
(ADR 0001) — so the shared `Constants` field is the seam, not
`OpModeRobot.getPeriod()` (`OpModeRobot.java:557`). **[source]**

### No convention, constant or gain is expressed in loop counts

> Anything that waits, settles, debounces, filters or differentiates is
> written in time. Where an API accepts only a sample count —
> `LinearFilter.movingAverage`, `MedianFilter` — choose the window in
> time and divide at the call site.

The loop rate is a number that has already changed once, from 20 ms to
5 ms, quartering the meaning of every count in the codebase on the way
past. **[decided]**

This binds SystemCore-side code. It does **not** bind the gains that live
on the SPARK: that controller runs its own 1 kHz loop in firmware and
never sees our period at all (ADR 0008, ADR 0009).

There is one thing that must stay expressed in cycles, and it is not an
exception being smuggled: *never `whileTrue` on an edge trigger — it is
true for exactly one cycle, so the command is cancelled immediately*
(ADR 0006). That is a statement about scheduler mechanics, equally true
at any rate. Rewriting it as a duration would make it wrong. It is
recorded here so that the next person auditing for loop counts does not
"fix" it.

### Nothing about the JVM is tuned

`gcType` stays at GradleRIO's default, which is
`GarbageCollectorType.ZGC` — verified in the plugin bytecode, GradleRIO
not being in the local checkout **[source — `docs/research/jvm-tuning.md`]**.
Unset, unpinned and uncommented in `build.gradle`. No `jvmArgs`. No AOT
cache. **[decided]**

This is not an untested default. Six collectors were run for a full match
each, and the one that settles it is `Epsilon`, which never collects at
all: its steady-state tail is 5.161 ms against `G1_Base`'s 5.196 ms.
**[measured]** Deleting garbage collection entirely changed nothing,
because nothing was giving the collector work — allocation runs at
**1.05 MB/s**, so a nursery fills a handful of times in a whole match.
**[measured]**

There is no winner to pick here, and picking one would be configuration
nobody can justify.

### A cache that exists to keep work off a transition is populated eagerly

The 5 ms steady state is uneventful. The cost is **cold code at a
transition**, and autonomous enable is the transition that matters.

| trajectory handling at enable | pause |
|---|---|
| cold `loadFromFile` on the loop thread | **63.32 ms** |
| cached object reused | **0.135 ms** |

**[measured]** At 5 ms, 63 ms is twelve iterations swallowed in one gulp,
inside autonomous, and it is larger than JVM startup in the same run.
With the cache reused the transition is invisible and the cost moves to
`robotInit`, where nothing is timing anything.

ADR 0006 already rules that opmodes never load trajectories and that
trajectories are cached on `Robot`, for a correctness reason — opmodes are
rebuilt on every reselect. That rule is worth 63 ms of autonomous **only
if the cache is eager**. Populated lazily on first use, it pays the full
63 ms and has bought nothing. So the rule generalises: a cache that exists
to keep work off a transition is populated at `robotInit`, not on first
use. **[decided]**

### Wake-to-wake delta is logged every loop, with no `Alert`

One `long` subtraction, cost in the noise. It exists because the watchdog
cannot see a missed deadline — see Traps — so without it a swallowed
iteration leaves no trace anywhere.

No `Alert` and no threshold. A threshold is unjustifiable until real
mechanisms exist; until then the signal is for `/analyze-match` to read
after the fact (ADR 0014). **[decided]**

## Consequences

- **ADR 0007 inherits a nearly-full bus.** The two 5 ms encoder frame
  groups plus eight setpoint writes at 5 ms are **82% of all CAN
  traffic** — 3200 frames/s of a ~3920 total, **52–61% utilisation** —
  and irreducible at 200 Hz. **[unverified — arithmetic; ADR 0007 owns
  the budget]**

- **ADR 0005's logging rule is unchanged, and its `Measure`-per-sample
  habit now has a price on it.** Logging the same fifty signals as
  `Measure` objects rather than raw doubles is **4× the garbage**, and the
  only change measured that visibly worsened the tail. **[measured —
  `docs/research/jvm-tuning.md`]** The rule
  stands — 7.6 ms of worst-case wake is far inside what a swerve base
  notices — but it is the first lever to pull if the tail ever does
  matter, because less garbage beat every collector tried.

- **ADR 0011 inherits the eager trajectory cache** as the thing that makes
  autonomous enable free.

- **ADR 0009 inherits a reproducibility constraint.** Gains tuned at one
  period do not transfer to another unless the period reached the
  controller, so a tuning run that does not record `LOOP_PERIOD` alongside
  the gains is recording numbers nobody can reproduce. On-SPARK gains are
  exempt and say so at the decision, not by inheritance.

- **ADR 0013 inherits *assert in time, never in ticks*.** The natural way
  to test `wait(Seconds.of(1))` is to tick until it ends and assert on the
  count — which was 50 and is now 200. The test base's advance-and-tick
  helper takes its step from the same `LOOP_PERIOD` the robot uses.

- **ADR 0003's no-`println`-from-periodic-code rule gets sharper.** A
  single `println` measured ~25 ms and tripped the watchdog on a 20 ms
  loop; at 5 ms the same call is five iterations gone. **[measured]**

- **`build.gradle`'s deploy block stays empty of tuning.** The absence is
  the decision, so it needs no comment — but it does need this document,
  or someone will read the empty block as an oversight and fill it in.

- **ADR 0001's rejection of `TimesliceRobot` is completed here.** Its
  reason for existing is deterministic sub-loop scheduling, and at a
  1.3% duty cycle there is no loop to slice.

## Traps

- **Six constructors silently default to a 20 ms period.** Each short
  overload delegates to a longer one with a hardcoded literal, so the
  mistake is made by *omission* — there is no wrong argument to spot in
  review.

  | Constructor | Defaults to | Source |
  |---|---|---|
  | `PIDController(kp, ki, kd)` | `0.02` | `PIDController.java:78` |
  | `ProfiledPIDController(kp, ki, kd, constraints)` | `0.02` | `ProfiledPIDController.java:46` |
  | `SimpleMotorFeedforward(ks, kv, ka)` | `0.020` | `SimpleMotorFeedforward.java:68` |
  | `ElevatorFeedforward(ks, kg, kv, ka)` | `0.020` | `ElevatorFeedforward.java:72` |
  | `ArmFeedforward(ks, kg, kv, ka)` | `0.020` | `ArmFeedforward.java:81` |
  | `OpModeRobot()` | `DEFAULT_PERIOD`, `0.02` (`:499`) | `OpModeRobot.java:504` |

  **[source]** The period is not a nicety in these classes; it is a
  divisor.

  For `PIDController` at 5 ms with the defaulting constructor, **the I and
  D terms are wrong by 4× in opposite directions**:

  - **D is 4× too weak.** The true derivative is `Δe / 0.005`; the
    computed one is `(m_error - m_prevError) / m_period` with `m_period`
    still 0.02 (`:285`, `:431`). A `kD` that reads right on paper delivers
    a quarter of it.
  - **I is 4× too strong.** Each call accumulates `m_error * m_period`
    (`:437-441`) — 0.02 per call — and there are now 200 calls a second
    rather than 50, so the integral winds four times too fast. The
    `m_minimumIntegral`/`m_maximumIntegral` clamp is in output units, so
    it bounds the damage without correcting the rate.

  **P-only is unaffected, and that is the trap.** The steer loop will look
  correct until someone adds a D term to stop the modules hunting, and
  then the gain will not behave the way the number says. Nothing throws
  and nothing logs.

  For the feedforwards, `dt` is the discretisation step —
  `A_d = Math.exp(A * m_dt)` (`SimpleMotorFeedforward.java:188`)
  **[source]** — so `calculate(current, next)` solves for the wrong
  horizon. The two-argument `SimpleMotorFeedforward(ks, kv)` form
  (`:81`) has no `kA` and is unaffected. **[source]**

- **`LinearFilter.movingAverage` and `MedianFilter` are count-based, so
  their window *duration* shrank 4×.** `movingAverage(int taps)`
  (`LinearFilter.java:134`) and `MedianFilter(int size)`
  (`MedianFilter.java:27`) take a number of samples, not a time, and there
  is no time-based overload. **[source]** A 5-tap average that smoothed
  over 100 ms at 20 ms now smooths over 25 ms. These are the one place
  WPILib hands you a count, so the conversion is ours to do at the call
  site — and it is exactly the kind of non-obvious unit the comment rule
  asks for one line about.

- **The `period` arguments that cannot be got wrong by omission are got
  wrong by copy-paste.** `LinearFilter.singlePoleIIR(timeConstant,
  period)` (`:96`), `highPass` (`:117`), `finiteDifference` (`:164`) and
  `backwardFiniteDifference` (`:238`) all *require* a period. **[source]**
  Every snippet on the internet passes `0.02` — the javadoc's own example
  at `LinearFilter.java:229` does. **[source]** A copied literal is the
  same bug wearing a time costume.

- **The watchdog cannot see a missed deadline.** `m_watchdog` is reset at
  the top of `loopFunc` (`OpModeRobot.java:610`) and disabled at the
  bottom (`:719`), so every epoch it records is *work inside the
  callback*. **[source]** A late wake is outside that window entirely. A
  32.9 ms wake gap at a 5 ms period swallowed six iterations and
  produced no `opmode-loop-overrun` alert (`:522-527`) and no epoch dump.
  **[measured]** This is the same blind spot ADR 0001 records for an
  enabled opmode's `periodic()`, reached from a second direction: there
  the work is not watched, here the deadline is not. Wake-to-wake delta
  is a logged signal precisely because nothing else in the framework is
  looking.

- **`GarbageCollectorType.G1` is a trap by name.** GradleRIO's `G1` preset
  is not plain G1 — it adds `-XX:MaxGCPauseMillis=1 -XX:GCTimeRatio=1`
  **[source — `docs/research/jvm-tuning.md`]**, which makes G1 collect **eight times more often** (24 young collections
  against 3) for a **worse** tail (6.367 ms against `G1_Base`'s
  5.196 ms). **[measured]** The entry whose name sounds tailor-made for
  robot code is the worst G1 configuration measured. `G1_Base` is the one
  without the pause goal.

- **JDK 25's AOT cache silently runs stale code.** It is the only lever
  measured that moved anything — startup 47.98 ms → 19.76 ms — and it is
  not safe to ship. One line was changed, the jar redeployed, and the same
  jar run twice: with `-XX:AOTCache` it ran the **old** code and printed
  nothing about it; without the cache it ran the new code.
  **[executed]** `-Xlog:class+load` shows **2321 of 2365 classes** loaded
  from the shared archive, the changed application class among them. There
  is no loud-failure option: `-XX:AOTMode=on`, the strict mode, still ran
  the stale code, and `-Xlog:aot=info` reported no mismatch and no
  validation failure. **[executed]** A deploy that appears to succeed can
  leave the robot running the previous build, with the bug still present
  and nothing anywhere saying why.

### Verified safe — do not re-audit these

Time-based and rate-independent, checked once so nobody checks again:
`Debouncer` and `SlewRateLimiter` (both read
`MathSharedStore.getTimestamp()` rather than counting calls —
`Debouncer.java:57,61`, `SlewRateLimiter.java:55-56`),
`Trigger.debounce(Time)`, `Trigger.multiPress(int, Time)`,
`Coroutine.wait(Time)`, `Coroutine.waitUntil(cond, Time)` and
`TrapezoidProfile`. **[source]**

Worth stating plainly, because it is why the audit of our own conventions
came back clean: **Commands v3 offers no count-based waiting primitive at
all.** Every way it gives you to wait takes a `Time`. A settling counter
cannot be reached for by accident — it can only arrive hand-written, which
is what the standing rule above is for.

## Open

- **The transition budget.** The steady loop is not where a budget gets
  spent; transitions are, and one of them was measured at 63 ms. What a
  transition *may* cost, and what should happen when one overruns, cannot
  sharpen until real mechanisms exist. **[unverified]** *Unblocked by* the
  first robot with mechanisms on it and a match's worth of wake-delta log.

- **Whether the loop thread should be raised to `SCHED_RR`.** Not decided,
  and the omission matters: the 5.16–5.23 ms steady-state figure above was
  measured with the loop thread at `SCHED_RR` 30, which is not what we
  ship. The loop body runs on the main thread at `SCHED_OTHER` by default
  — only the CAN and notifier HAL threads are real-time. **[measured]**
  Raising it is one call, and it is worth far less in Java than in C++: in
  C++ it collapsed worst-case wake jitter roughly 20×, while in Java it
  moves p99 and leaves the worst case where it was. **[measured]** It also
  has a sharp edge — `Threads.setCurrentThreadPriority` is `@Deprecated`
  and **returns inverted success**, reporting failure when it succeeded
  (`hal/src/main/native/cpp/jni/ThreadsJNI.cpp`), so the resulting
  priority must be read back rather than trusted. **[executed]**
  *Unblocked by* a re-measurement at default priority with real CAN
  traffic, which is also what would show whether the difference matters.

## Rejected

### 20 ms

The inherited default, and what `OpModeRobot()` gives you for free. Every
argument for it was about cost, and the cost is 65 µs of a 5000 µs
budget. **[measured]**

### 1 kHz

Java holds a 1 ms period at p99 (1.021 ms with RT priority) and JIT-ed
math comes within ~17% of C++. **[measured]** It is not rejected for being
impossible. It is rejected because the fidelity it would buy is gated on
CAN frame rates we cannot raise that far (ADR 0007), the tail behaviour at
1 ms is where RT priority becomes necessary rather than optional,
and 200 Hz already spends 1.3% of the budget for everything we know we
need.

### Selective fast sampling, and split-rate telemetry

The shape most teams reach for: sample odometry fast, everything else
slow. Rejected on the map owner's stated preference for one rate, and
then confirmed by the numbers — a 1.3% duty cycle and 13.1 MB per match
mean there is nothing to save. **[measured]** It would also require a
second periodic callback, and ADR 0006 rejects `addPeriodic` and
`sideload` on independent grounds.

### Pinning a collector — any of them

`ZGC`, `G1_Base`, `Parallel`, `Serial` and `Shenandoah` were all measured
over a full match. `Epsilon` — which never collects — lands inside a 370 µs
band with `Parallel`, `G1_Base` and `ZGC`. `Serial` is the one outlier at
6.617 ms, and `Shenandoah` collected nothing at all in 150 s, so no
steady-state figure was recorded for it. **[measured]** Pinning one would be a
config line whose justification is "we measured that it does not matter",
which is an argument for deleting the line.

**Do not re-raise without new evidence.** New evidence means an allocation
rate materially above 1.05 MB/s — which is what reversing ADR 0005's raw-
doubles rule would produce — not a collector recommendation from a blog
post. Note also that the runs above were verified by GC MXBean name, not
by the flag string.

### `-XX:MaxGCPauseMillis` / the `G1` preset

Measured, and worse than doing nothing. See Traps.

### `AlwaysPreTouch` with a fixed `-Xms`/`-Xmx`

Aimed at the ~50 ms spike at t=0, on the theory that it is first-touch
page faults. It changed nothing. **[measured]** Between that result and
Epsilon's, the startup spike is cornered as class loading and JIT — and
it lands at boot, minutes before a match, where it costs nobody anything.

### The JDK 25 AOT cache

Measured, effective, and rejected for silently running stale code — the
full evidence is in Traps. Regenerating the cache on every deploy would
fix the staleness, but the failure mode when *that* wiring breaks is
silent-wrong-code rather than slow, and the payoff is a faster JVM boot
that nothing is waiting on. Real machinery, no payoff. Recorded as
measured-and-rejected rather than untried: the training run itself works
fine. **[executed]**

### A lint rule, a compiler check or a wrapper factory for `LOOP_PERIOD`

The obvious way to enforce "never pass a period by omission" is to ban the
short constructors mechanically. Rejected: a single `Constants` field that
every call site reads is a habit with one obvious right answer, and
wrapping five WPILib constructors to police it would put a layer between
students and the API every tutorial names. The trap table above is the
enforcement.

### An `Alert` on wake-to-wake overrun

The signal is logged; the alert is not. Any threshold today would be
invented, and a permanently-lit alert teaches the drive team to ignore
alerts. Revisit when the transition budget above closes.

### `OpModeRobot.getPeriod()` as the project's source of the period

It exists (`OpModeRobot.java:557`) **[source]** and it is authoritative,
which makes it tempting. But mechanisms are fields *on* `Robot` and hold
no reference back to it (ADR 0001), so reaching `getPeriod()` from a
mechanism means threading a `Robot` reference through constructors that
exist to take config records. `Constants.LOOP_PERIOD` is reachable from
everywhere and is the value `getPeriod()` returns.

## Source

Decided in [#28](https://github.com/Drew-Robotics/2027beta/issues/28) —
the loop rate, the telemetry volume and the wake-delta signal —
[#31](https://github.com/Drew-Robotics/2027beta/issues/31) — no JVM
tuning, the AOT rejection and the eager-cache rule — and
[#30](https://github.com/Drew-Robotics/2027beta/issues/30), which audited
the conventions for loop counts and found the hazard one layer down, in
the WPILib APIs those conventions call. The CAN consequences are
[#29](https://github.com/Drew-Robotics/2027beta/issues/29) and ADR 0007;
the logging rule this leaves unchanged is
[#11](https://github.com/Drew-Robotics/2027beta/issues/11) and ADR 0005;
the trajectory cache it generalises is
[#17](https://github.com/Drew-Robotics/2027beta/issues/17) and ADR 0006.

Research: [`docs/research/loop-rate.md`](../research/loop-rate.md),
[`docs/research/jvm-tuning.md`](../research/jvm-tuning.md), with the
harnesses in [`docs/research/loop-bench/`](../research/loop-bench/).

Source read for this ADR, in `~/dev/allwpilib` at `cafb0cc79` (alpha-7):
`wpimath/src/main/java/org/wpilib/math/controller/` — `PIDController.java`,
`ProfiledPIDController.java`, `SimpleMotorFeedforward.java`,
`ElevatorFeedforward.java`, `ArmFeedforward.java`;
`wpimath/src/main/java/org/wpilib/math/filter/` — `LinearFilter.java`,
`MedianFilter.java`, `Debouncer.java`, `SlewRateLimiter.java`;
`wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`;
`hal/src/main/native/cpp/jni/ThreadsJNI.cpp`.

### Departures from #30

#30 names the constant `Constants.kLoopPeriod`. This ADR calls it
`Constants.LOOP_PERIOD`, because ADR 0003 sets `ALL_CAPS` for constants
from upstream's own repo-wide rename
(`rebuiltcmdv3/constants/DriveConstants.java:15-20`). **[source]** #30's
decision is that the period is a `Time` written in exactly one place; the
spelling was incidental to it, and following the naming rule that already
stands is cheaper than carrying an exception. If the `k` prefix is wanted,
it is ADR 0003's rule to change, not this one's.

#30 lists five defaulting constructors; this ADR lists six, adding
`OpModeRobot()` (`:504`, via `DEFAULT_PERIOD` at `:499`). **[source]**
#30 names that constant in its own prose as "precisely the assumption the
five constructors bake in" without counting it as one of them. It belongs
in the table: it is the same failure — a period supplied by omission — and
it is the one that sets the period for everything else.
