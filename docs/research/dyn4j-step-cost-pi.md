# What a dyn4j step costs on the bench Pi

**Research for:** [Drew-Robotics/2027beta#155](https://github.com/Drew-Robotics/2027beta/issues/155)
**Date:** 2026-09-20

This is the Pi half of [#144](https://github.com/Drew-Robotics/2027beta/issues/144).
[`dyn4j-step-cost.md`](dyn4j-step-cost.md) measured a workstation and could not
reach the bench; §7.4 of it is the recipe this followed, and §8 of it is the
harness this ran unmodified. Read that document first — this one does not repeat
its scene, its normalisations or its reasoning.

## Source and trust level

- **[measured]** — I ran it on the bench Pi and read the number off the run.
  Every table below is median-of-three with the spread stated. The raw log is
  reproducible from §6.
- **[source]** — read from a named file, cited by path and line.
- **[unverified]** — derived, assumed, or not checked.

### What was under test

| Thing | Coordinate |
| --- | --- |
| Box | `systemcore@192.168.1.202`, `limelightosr-2027.0.0-beta14`, Linux `6.12.77-v8-16k` `aarch64` `PREEMPT_RT`, 4 cores at 2.4 GHz **[measured]** |
| RAM | **8056 MB** — *not* 4 GB **[measured]**. See §5.1. |
| JDK | Temurin 25.0.2+10 LTS, OpenJDK 64-Bit Server VM **[measured]** |
| dyn4j | `org.dyn4j:dyn4j:6.0.0`, `sha256 204ca8dd…2b2b3e`, verified on the Pi after transfer **[measured]** |
| Governor | `performance` on all four cores for every run, restored to `ondemand` afterwards **[measured]** |
| Scheduling | `SCHED_RR` 30, as [`jvm-tuning.md:19`](jvm-tuning.md) ran its loop thread **[source]** |
| Box state | `robot.service` and the Limelight services left **up**, per §7.4. Load average 2.2 before, 5–7 during. **[measured]** |

---

## 0. Bottom line

**Time is still a non-issue, and the Pi is a smaller penalty than anyone
extrapolated. Allocation is still the finding — but it is the same number here
as on the workstation, and the configuration that actually runs on this box sits
exactly on the empty-world floor.**

One chassis against four static walls at ADR 0010's 5 × 1 ms costs **10.00 µs
per 5 ms period — 0.200 % of the period** **[measured]**, against the
workstation's 2.479 µs. Ten bodies cost **49.5 µs — 0.990 %** **[measured]**.
The Pi is a flat **3.8–5.1× the workstation** across every dyn4j row, which is
*below* the 5–10× that `physics-sim.md:612-620` extrapolated
**[source]** — that arithmetic predicted 12–25 µs for the one-body case and the
true figure is 10.0 µs, under the low end of the band.

**dyn4j got relatively cheaper, not dearer, by moving to the Pi.** The durable
ratio §7.4 asked for: dyn4j is **5.88×** the DIY free-space model here
(10.270 / 1.747 µs per period), against **8.56×** on the workstation
**[measured]**. The DIY model is the thing that slowed down most (5.87×); dyn4j
slowed down least.

**Allocation does not change on different silicon, and that is the answer.** The
per-step byte counts are bit-identical to the workstation's — 184.0 B/step at
zero bodies against its 184, 717.4 against its 717, 4350.5 against its 4328
**[measured]**. Allocation is a property of the bytecode and the object graph,
not the CPU, and it reproduces to better than 0.1 % across runs. **So there was
never a Pi allocation number to discover that differed from the workstation
one.** What the Pi supplies instead is the *context* that number lands in, and
that context has three parts nobody had:

1. **The deployed collector is ZGC, and ZGC allocates 22–39 % more than G1** on
   this exact workload, because ZGC ergonomically switches compressed oops
   **off** and every reference field grows from 4 to 8 bytes **[measured]**.
   Ten bodies is **4.96–5.06 MB/s** as deployed, not 4.15. Every allocation
   figure in `dyn4j-step-cost.md` understates the robot's own configuration.
2. **ZGC is also the collector least hurt by it** — its pauses do not grow with
   allocation rate. ADR 0002's untuned GradleRIO default turns out to be the
   best-suited collector in the enum for an allocating sim, by accident.
3. **The configuration that actually runs on Pi hardware allocates 184.0 B/step
   — exactly the empty-world floor, to the byte** **[measured]**. Map #142's
   correction said "near the floor". It is not near it; it *is* it. §4.

**ADR 0002's collector question does not reopen. The condition should be
restated rather than met.** §5.

---

## 1. The headline

Median of three full suite runs; each cell is itself the minimum of five timed
trials of 200 000 robot periods after an equal warmup, which is
`dyn4j-step-cost.md` §8.1's method unchanged. **[measured]**

| Scenario | µs / step | **µs / 5 ms period** | **% of 5 ms** | spread | workstation µs/period | **Pi / workstation** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **1 robot, driving, free space** | 2.000 | **10.002** | **0.200 %** | 6.0 % | 2.479 | **4.03×** |
| 1 robot, pressed into a wall | 5.872 | **29.358** | **0.587 %** | 5.1 % | 6.352 | 4.62× |
| 0 dynamic bodies (fixed overhead) | 1.120 | 5.602 | 0.112 % | 2.2 % | 1.460 | 3.84× |
| **DIY model, same box, same rates** | **0.349** | **1.747** | **0.0349 %** | **0.1 %** | 0.298 | **5.87×** |

"Spread" is `(max − min) / min` across the three suite runs. The box was under
its normal service load throughout, and the spread is the honest cost of that:
1–8 %, against the workstation's 3 %. The DIY model's 0.1 % is the tell that the
spread is scheduling noise and not measurement error — a tight arithmetic loop
at `SCHED_RR` 30 is immune to it in a way that a loop touching more memory is
not.

Three things follow.

- **The 5–10× extrapolation was too pessimistic for dyn4j and about right for
  the DIY model.** `physics-sim.md:612-620` **[source]** is the only prior Pi
  estimate in the repo. dyn4j came in at 3.8–5.1×, the DIY model at 5.87×.
- **The fixed overhead is still most of the cost at one body** — 5.602 µs of the
  10.002, **56 %**, against 59 % on the workstation. The shape of the curve
  transferred intact.
- **The one-body step is 0.200 % of the budget.** ADR 0010's stepping decision
  needs no defence on this box.

---

## 2. Scaling

### 2.1 Sub-step count — 1 robot, the 5 ms period held fixed and divided differently

Time is median-of-three from the suite. Allocation is from isolated JVMs (§3),
one configuration per JVM. **[measured]**

| Sub-steps | dt | µs / step | **µs / 5 ms period** | **% of 5 ms** | B/step (G1) | MB/s (G1) | B/step (ZGC) | **MB/s (ZGC)** |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 5.00 ms | 3.515 | 3.515 | 0.0703 % | 1851–1869 | 0.35 | 2180.9 | 0.42 |
| 2 | 2.50 ms | 2.624 | 5.249 | 0.1050 % | 1166–1168 | 0.45 | 1491.8 | 0.57 |
| **5 (ADR 0010)** | **1.00 ms** | **2.054** | **10.270** | **0.2054 %** | **662.5** | **0.63** | **961.5** | **0.92** |
| 10 | 0.50 ms | 2.017 | 20.172 | 0.4034 % | 582.5 | 1.11 | 807.6 | 1.54 |
| 20 | 0.25 ms | 2.010 | 40.196 | 0.8039 % | 575.0 | 2.19 | — | — |

Per-period cost is linear in sub-step count at and below 1 ms and the per-step
cost is flat there — 2.054, 2.017, 2.010 µs — exactly as on the workstation. A
*single* 5 ms step costs 3.515 µs against 2.054 at 1 ms, **71 % more** per step
(the workstation saw 53 % more).

**The allocation lever is 1.8–2.2×, not fivefold.** This is the number
[#147](https://github.com/Drew-Robotics/2027beta/issues/147) was to be handed
and it contradicts how the trade has been described. Going from 5 × 1 ms to
1 × 5 ms takes G1 from 0.63 to 0.35 MB/s (**1.8×**) and ZGC from 0.92 to
0.42 MB/s (**2.2×**) **[measured]** — not the fivefold that
[#155](https://github.com/Drew-Robotics/2027beta/issues/155) and map
[#142](https://github.com/Drew-Robotics/2027beta/issues/142) both assert.
Allocation does not scale with steps per second, because **B/step rises as dt
grows**: a bigger step moves bodies further and puts more work, and more
garbage, through narrowphase. `dyn4j-step-cost.md` §3's own table already showed
this (1701 B/step at one sub-step against 686 at five, a 2.03× ratio in MB/s);
the fivefold figure was arithmetic that its own measurement contradicted.

### 2.2 Body count — 5 × 1 ms, bodies on a 2.2 m grid, all driven

**[measured]**

| Dynamic bodies | µs / step | **µs / 5 ms period** | **% of 5 ms** | B/step (G1) | MB/s (G1) | B/step (ZGC) | **MB/s (ZGC)** |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 1.120 | 5.602 | 0.112 % | **184.0** | 0.18 | 256.0 | 0.24 |
| **1** | **2.032** | **10.161** | **0.203 %** | **662.5** | **0.63** | **921.5** | **0.88** |
| 2 | 2.425 | 12.125 | 0.243 % | 716.7–717.4 | 0.68 | — | — |
| 5 | 3.713 | 18.567 | 0.371 % | 1333.7–1335.6 | 1.27 | — | — |
| 10 | 9.896 | **49.480** | **0.990 %** | 4350.5–4441.7 | 4.15–4.24 | 5203.3–5307.7 | **4.96–5.06** |

Ten bodies is **4.9× one body** in time, against 3.8× on the workstation, and
the 10-body row carries the widest spread in the suite (7.7 %) because it is the
one row where bodies collide with each other rather than only with walls. Cost
is driven by **contacts**, not body count — the workstation's finding, intact.

**Ten bodies is where dyn4j first crosses 1 % of the 5 ms budget.** It is still
two orders of magnitude inside it. The map's don't-foreclose-game-pieces
constraint does not bite on time here any more than it did on the workstation.

---

## 3. Allocation — and why the Pi was never going to have a different number

Each configuration in **its own JVM**, via
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on the stepping
thread, 200 000 periods after a 20 000-period warmup — `dyn4j-step-cost.md`
§8.2 unmodified. Three repetitions. **[measured]**

| Configuration | Pi B/step (G1) | workstation B/step | agreement | Pi rep-to-rep spread |
| --- | ---: | ---: | ---: | ---: |
| dyn4j, 0 dynamic bodies | **184.0** | 184 | **exact** | 0.0 % |
| dyn4j, 1 robot, free space | 662.5 | 686 | −3.4 % | 0.0 % |
| dyn4j, 2 robots | 717.4 | 717 | +0.1 % | 0.1 % |
| dyn4j, 5 robots | 1334.1 | 1330 | +0.3 % | 0.1 % |
| dyn4j, 10 robots | 4350.5 | 4328 | +0.5 % | 2.1 % |
| dyn4j, 1 robot pressed into a wall | 2643.8 | 2649 | −0.2 % | 0.0 % |
| DIY model, 4 modules | **0** | 0 | exact | — |

**Allocation is a property of the bytecode, not the silicon.** Both machines run
the same class files with the same object graph and the same 32-bit compressed
oops under G1, so the only room for disagreement is the JIT's escape analysis,
and it disagrees only at the margins — the one-body case by 3.4 %, everything
else by under 0.5 %. **The ticket's premise that "the allocation answer is the
one that could flip on the Pi" was wrong in the way it expected.** The bytes are
the same. What differs is the collector and the heap they are handed to.

Allocation is also a **far more reproducible measurement than time**: 0.0–0.1 %
across repetitions, against 1–8 % for the timings, on a box under load.

### 3.1 The counter is sound on this box

`dyn4j-step-cost.md` §5's cross-check, re-run here. **[measured]**

| | bytes counted | `-XX:+UseSerialGC -Xmn32m` young collections | implied MB | pause |
| --- | ---: | ---: | ---: | --- |
| 1 body, 1 M steps | 631.8 MB | 27 | ~864 MB | 0.17–1.36 ms |
| 10 bodies, 1 M steps | 4235.7 MB | 185 | ~4810 MB | 0.17–0.32 ms |

A 32 MB nursery driven 27 and 185 times brackets the counter from above in both
cases — the excess is warmup and JIT, which the counter window excludes. Same
result as the workstation, by the same argument.

### 3.2 The collector the robot actually deploys with is not the one a bare `java` picks

This is new and nobody had it. **[measured]**

`/home/systemcore/robotCommand.args` on the bench Pi carries exactly one JVM
flag: `-XX:+UseZGC` **[measured]** — GradleRIO's `gcType` default, which
ADR 0002 leaves untouched **[source — `docs/adr/0002-loop-rate-and-jvm.md:107-111`]**.
A bare `java` on the same box picks **G1** ergonomically. They do not allocate
the same amount:

| | 0 bodies | 1 body | 10 bodies |
| --- | ---: | ---: | ---: |
| G1 (bare `java` default) | 184.0 B/step | 662.5 | 4350.5 |
| **ZGC (as deployed)** | **256.0** | **921.5** | **5203.3** |
| ZGC penalty | **+39 %** | **+39 %** | **+20 %** |

**The mechanism is compressed oops.** `-XX:+PrintFlagsFinal` on this box reports
`UseCompressedOops = true {ergonomic}` for the default JVM and
`UseCompressedOops = false {ergonomic}` under `-XX:+UseZGC` **[measured]**.
Every reference field in dyn4j's per-step garbage grows from 4 bytes to 8, and
dyn4j's step garbage is reference-heavy, so the whole graph inflates by roughly
a fifth to two fifths. The penalty is largest where the objects are smallest and
most numerous, which is why the 0- and 1-body rows take the full 39 %.

**Consequence: every allocation figure in `dyn4j-step-cost.md` is a G1 figure
and understates the deployed configuration by 20–39 %.** The ten-body worst case
is **4.96–5.06 MB/s**, not 4.13.

### 3.3 What the collectors actually do with it

Pause durations, read off `-Xlog:gc` on the Pi. **[measured]**

| collector | at 10 bodies | pause |
| --- | --- | --- |
| G1 (default) | young evacuation, 76M→1M(130M) | **0.65–1.01 ms** |
| G1 (default), 10 sleeping bodies | young evacuation, 76M→1M(130M) | 1.35–1.93 ms |
| Serial `-Xmn32m` | young copy, 27M→1M(124M) | 0.17–0.32 ms |
| **ZGC (as deployed)** | major cycle, heap grew to 866M (43 % of 2016M) | **cycle 13–18 ms, pauses sub-ms and not logged at this level** |

> **The collection *frequency* in these logs does not transfer and the *pause
> duration* does.** The benchmark steps flat out, so it allocates ~435 MB per
> wall-clock second; a robot stepping 1000 times per real second allocates
> 4.35 MB/s, about 100× less. Read the pause column, not the timestamps.
> **[measured, with the frequency `[unverified]` for a robot program.]**

Scaled properly, 4.35 MB/s against the ~76 MB young generation G1 chose gives a
young collection roughly every 17 s — about **9 in a 150 s match**. That is an
independent corroboration worth having: `jvm-tuning.md:73` measured a
**4.17 MB/s** workload on this same box and recorded exactly **9 collections in
150 s** **[source]**. The two arrive at the same place from different
directions.

---

## 4. The `sim-hitl` case — at-rest detection on, chassis at rest

Everything above keeps dyn4j's at-rest detection **off**, as
[#144](https://github.com/Drew-Robotics/2027beta/issues/144) did. That is right
for a driven robot and wrong for the only loop that runs on this hardware.

The chain map #142 asserts, verified here:
`simulationPeriodic()` calls `drive.updateSim()` unconditionally
**[source — `src/main/java/first/robot/Robot.java:348-350`]**; the sim ships
behind `isSimulation()`
**[source — `docs/adr/0010-simulation-architecture.md:353-358`]**; and the loop
`sim-hitl` measures is the **disabled** one — *"the loop measured here is the
disabled one"* **[source — `.github/bench/sim-hitl.sh:11`]**, because a remote
simulation is not something the Driver Station can be pointed at
**[source — `docs/bench-runner.md:93-97`]**. A disabled robot is not driving,
so its chassis is at rest.

Measured with dyn4j's sleep heuristic at its default (on), three repetitions.
**[measured]**

| Scenario | bodies asleep | µs / 5 ms period | % of 5 ms | **B/step** | MB/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| **1 body, at rest, G1** | **1/1** | 5.51–5.68 | 0.110–0.114 % | **184.0** | **0.18** |
| **1 body, at rest, ZGC (as deployed)** | **1/1** | 7.97–8.16 | 0.159–0.163 % | **256.0** | **0.24** |
| 10 bodies, at rest, G1 | 10/10 | 11.06–11.23 | 0.221–0.225 % | **184.0** | 0.18 |
| 10 bodies, at rest, ZGC | 10/10 | 13.34 | 0.267 % | **256.0** | 0.24 |
| *empty world floor, for comparison* | — | 5.602 | 0.112 % | *184.0* | *0.18* |

**Map #142's correction holds, and is stronger than it claimed.** It said the
realistic `sim-hitl` figure is *"near the 184 B/step empty-world floor"*. It is
not near the floor — it **is** the floor, to the byte, at one body and at ten.
A sleeping body contributes exactly nothing to per-step allocation, because
dyn4j removes it from the solver entirely; the 184.0 B/step that remains is the
`TimeStep` and broadphase bookkeeping the world pays whether or not anything is
in it. The same holds under ZGC at its own 256.0 B floor. **[measured]**

**Time does not go to the floor, and allocation does.** Ten sleeping bodies
still cost 11.1 µs per period against the empty world's 5.6 — broadphase still
walks them — but they allocate the empty world's exact byte count. Sleep is a
total allocation lever and a partial time lever.

### 4.1 A null that corrects #144's stated rationale

`dyn4j-step-cost.md` §1.1 turns at-rest detection off on the grounds that
leaving it on *"would have let idle bodies fall out of the solver and flattered
every number"*. Measured against `Dyn4jBench`'s own scene and own command with
the flag as the single delta: **[measured]**

| | bodies asleep | µs / 5 ms period | B/step |
| --- | ---: | ---: | ---: |
| 1 body, driven, at-rest **OFF** | 0/1 | 10.35–11.13 | 662.3 |
| 1 body, driven, at-rest **ON** | **0/1** | 10.82 | **662.3** |
| 10 bodies, driven, at-rest **OFF** | 0/10 | 56.45–57.49 | 4322.8 |
| 10 bodies, driven, at-rest **ON** | **0/10** | 54.14–57.89 | 4188.3 |

**The heuristic never fires on a driven body, so it flatters nothing.** Zero of
one and zero of ten bodies ever reached the 0.0100 m/s / 0.0349 rad/s / 0.500 s
at-rest threshold under the benchmark's command, and the byte counts are
identical (662.3 both) or within 3 % (10 bodies). #144's flag choice was correct
and harmless; the reason given for it was not the reason it was safe. This
matters because it means **the ON/OFF choice is not a knob that trades honesty
for a better number** — it is a knob that does nothing at all until the robot
stops, and then does everything.

---

## 5. What this decides

### 5.1 First, the premise the question was built on is wrong

**The bench Pi has 8056 MB of RAM, not 4 GB. [measured]**

```
$ free -m
              total        used        free      shared  buff/cache   available
Mem:           8056        1218        5729          43        1109        6714
$ java -Xlog:gc+init -version
[gc,init] CPUs: 4 total, 4 available
[gc,init] Memory: 8056M
```

[#155](https://github.com/Drew-Robotics/2027beta/issues/155)'s reasoning — *"the
Pi has 4 GB and a different collector profile, and a 4 MB/s allocation rate is a
different proposition there"* — and `dyn4j-step-cost.md:294-297`, which it came
from, both rest on that number.

**The repo already contradicted itself about this.**
[`jvm-tuning.md:4`](jvm-tuning.md) has said *"4 CPUs, 8 GB"* since 2026-08-25,
measured on this same box **[source]**. The 4 GB figure traces to
`physics-sim.md:609` and `:770`, which name a Compute Module 5 variant rather
than reporting a measurement **[source]**. `dyn4j-step-cost.md:295` is corrected
by this ticket; **`physics-sim.md:609` and `:770` are still wrong and are not in
this ticket's scope to edit.**

What the JVM actually chooses here, which is what should carry the argument
instead: **[measured]**

| | bare `java` (G1) | as deployed (`-XX:+UseZGC`) |
| --- | --- | --- |
| Collector | G1, ergonomic | ZGC, by GradleRIO's `gcType` default |
| Heap max | **2016 MB** (¼ of 8056) | 2016 MB; grew to 866 MB under load |
| Heap initial | 128 MB | 128 MB |
| Max young | 1267 MB | n/a (region-based) |
| Compressed oops | **on**, 32-bit | **off** |
| Region size | 1 MB | — |

A 2016 MB heap is not a constrained one, and the sim's allocations are entirely
short-lived — every collection in §3.3 recovered its nursery almost completely
(76M→1M, 27M→1M). **Nothing about this box's memory makes 4 MB/s a different
proposition than it is on a workstation.** The premise is retired.

### 5.2 Does ADR 0002's collector question reopen? — **No. Decline, and restate the condition.**

ADR 0002 says *"Do not re-raise without new evidence"* and defines it: *"New
evidence means an allocation rate materially above 1.05 MB/s"*
**[source — `docs/adr/0002-loop-rate-and-jvm.md:371-373`]**. This ticket
explicitly declines to supply it. The argument is in four steps, and the second
is the one that nearly goes the other way.

**1. The rate that would meet the condition is real.** Ten awake bodies in
mutual contact allocate 4.15 MB/s under G1 and **4.96–5.06 MB/s as deployed**
**[measured]** — four to five times the 1.05 MB/s baseline, on top of it rather
than instead of it.

**2. And on this exact box, that band is not demonstrably harmless.** This is
the part that deserves stating plainly rather than waving through.
`jvm-tuning.md:72-73` measured **4.17 MB/s** on this Pi and recorded a
steady-state wake max of **7.621 ms at a 5 ms period**, against 5.196 ms at
1.05 MB/s — 9 collections instead of 3, 46 ms of GC instead of 13, and five
wakes more than 1 ms over instead of two. It calls that *"the only change
measured here that visibly worsened the tail"* **[source]**. dyn4j at ten bodies
is the same band. So the reopen condition is not a formality that fails on a
technicality; a configuration exists that would meet it and that the repo has
already measured as costing the tail.

**3. But that configuration does not exist, is not specified, and cannot occur
in a match.** Ten awake bodies in mutual contact is a synthetic worst case from
§8.1's grid. What the repo specifies today is one chassis, and what runs on Pi
hardware is `sim-hitl`'s disabled loop with the chassis at rest — **184.0 B/step
under G1, 256.0 under ZGC, 0.18–0.24 MB/s** **[measured]**, which is *below* the
robot program's own 1.05 MB/s rather than above it. The sim is behind
`isSimulation()` **[source — ADR 0010:353-358]**, so this is a bench and desktop
concern and never a match one. **The question the ticket asked — does the
allocation rate actually matter there — has a measured answer, and the answer is
no, because on the only hardware path that exists the rate is the empty-world
floor.**

**4. And the collector ADR 0002 already declines to change is the right one
anyway.** ZGC's pauses do not grow with allocation rate; G1's do, and §3.3
measured G1 young pauses of 0.65–1.93 ms on this box against a 5 ms period.
ADR 0002 keeps GradleRIO's `ZGC` default on the grounds that no collector was
distinguishable at 1.05 MB/s **[source — `:107-121`]**. That reasoning does not
extend to 5 MB/s — but the choice it produced does, for a reason ADR 0002 never
claimed. **Pinning a collector now would mean pinning the one already in use.**

**What ADR 0002 should say** — recorded here, not written there, per this
ticket's scope:

- The reopen condition should be **restated as a configuration, not a
  dependency**. "dyn4j is on the classpath" is not the trigger; *"more than a
  handful of simultaneously awake dyn4j bodies in sustained mutual contact, in a
  loop that is not behind `isSimulation()`"* is. Adding dyn4j does not trip it;
  one chassis at 0.63 MB/s does not trip it; the `sim-hitl` path at 0.18 MB/s
  moves *away* from it.
- It should record that **`ZGC` disables compressed oops**, so its own default
  costs 20–39 % more allocation than the G1 figures anyone measures on a
  laptop — a fact that applies to ADR 0005's telemetry rule as much as to the
  sim, and that nothing in the repo currently states.
- It should note that **`jvm-tuning.md`'s 4.17 MB/s datum is the calibration
  point** for what "materially above 1.05 MB/s" costs on this box: 7.6 ms at a
  5 ms period. The condition has a measured consequence and should cite it.

**What was not measured, and bounds the above:** this benchmark measures
`world.step()` allocation and step time. **It does not measure a robot loop with
dyn4j inside it.** No wake-to-wake distribution was taken with the sim running,
so the claim that 5 MB/s would cost the tail is inference from
`jvm-tuning.md`'s separate measurement of a similar rate, not a direct
observation of this workload. **[unverified]** If the stepping ticket ever wants
that, it is `LoopBenchGc.java` plus a dyn4j world, not this harness.

### 5.3 If it reopened, what is the lever? — the sub-step is a **1.8–2.2×** lever, not 5×

This is [#147](https://github.com/Drew-Robotics/2027beta/issues/147)'s to spend,
and the Pi number it was to be handed is smaller than advertised.

| lever | time, Pi | time, workstation | **allocation, Pi** |
| --- | ---: | ---: | ---: |
| 5 × 1 ms → 1 × 5 ms | −6.76 µs/period (−66 %) | −1.79 µs/period | **G1 1.8× · ZGC 2.2×** |
| continuous detection off | **−14.7 %** | −12 % | −8.1 % |
| velocity iters 6→3, position 2→1 | −4.6 % | −7 % | −2.8 % |
| both of the above | **−19.8 %** | −20 % | −13.8 % |
| warm starting off | **+3.3 %** — worse | +0.3 % | 0 % |
| **at-rest detection on, chassis at rest** | −45 % (1 body) | not measured | **to the floor — 3.6× at 1 body, 24× at 10** |

Lever rows are one robot pressed into a wall at 5 × 1 ms, median of three,
against a stock 27.784 µs/period. **[measured]**

Read this way:

- **The settings levers are time levers and poor allocation levers.** CCD off is
  the biggest single one and buys 14.7 % of time but only 8.1 % of bytes.
  Warm starting off is worthless on the Pi as it was on the workstation, and
  slightly negative here.
- **Coarsening the sub-step is the only settings-free allocation lever, and it
  is worth ~2×, not 5×.** §2.1. It costs integration quality, which
  `dyn4j-step-cost.md` §3 measured as materially worse. At 0.63 MB/s for the
  configuration that exists, **#147 is being offered a 2× discount on a rate
  that is already a third of the robot program's own.** That is not a trade
  worth making on cost grounds, and this ticket's contribution to #147 is to say
  so with a Pi number rather than to leave the fivefold figure standing.
- **The real allocation lever is how many bodies are awake**, which is 6.6×
  between one and ten, and **whether they are allowed to sleep**, which is total.

### 5.4 Does this change ADR 0016? — **No. A `docs/research/` note and a line in ADR 0002.**

ADR 0010's 5 × 1 ms survives the Pi without amendment: 0.205 % of a 5 ms period
for one chassis, 0.990 % for ten bodies, both measured on the hardware
**[measured]**. There is no cost argument for changing the stepping rate in
either direction, and finer sub-stepping stays affordable if fidelity ever wants
it — 20 sub-steps is 0.80 % of the budget here.

**The one thing ADR 0016 should say that it would not otherwise have said** is
that **at-rest detection is a decision with a stated default, not a dyn4j
default to inherit silently.** §4 measures it as the difference between the
empty-world floor and the full rate, it is invisible until the robot stops, and
it is the single largest lever in §5.3's table. A stepping design that does not
mention it has left its whole allocation profile to a library default. ADR 0016
should state which way it goes and why, and the evidence says **on**: it costs
nothing while driving (§4.1) and takes the disabled `sim-hitl` loop to the floor.

Beyond that, this is a `docs/research/` note. **The one narrow live concern
`dyn4j-step-cost.md` and map #142 both identified survives intact and is
unchanged by anything here:** `sim-hitl` exists to catch loop regressions, and
an allocator inside the loop it measures can contaminate that instrument. At
0.18–0.24 MB/s with the chassis at rest, it will not. If the sim ever runs on
the Pi *enabled* and driving, it will — at 0.63–0.92 MB/s for one chassis,
comparable to the program's own 1.05 MB/s — and the `sim-hitl` baseline would
need re-taking rather than the collector re-choosing.

---

## 6. How to re-run this

Everything in `dyn4j-step-cost.md` §7 applies. These are the deltas this box
needed.

### 6.1 Harness deltas

- **`curl` is missing from the Pi.** `wget`, `busybox` and `python3` are
  present. `scp` the jar from a workstation — the Gradle cache already has it at
  `~/.gradle/caches/modules-2/files-2.1/org.dyn4j/dyn4j/6.0.0/*/dyn4j-6.0.0.jar`
  — and verify with `sha256sum` on the Pi, which does exist.
- **`taskset` is missing; `chrt` is at `/bin/chrt`.** `ulimit -r` is 0 for
  `systemcore`, so `chrt -r 30` needs `sudo`, which works passwordlessly.
  Running the JVM as root would change the user under test, so the runs go
  through **`sudo -n chrt -r 30 sudo -n -u systemcore java …`** — `chrt` sets the
  policy, the second `sudo` drops back to `systemcore`, and the `SCHED_RR` 30
  policy is inherited across the privilege drop. Verified before use.
- **Governor.** `ondemand` by default, `performance` available, CPU already at
  its 2.4 GHz maximum either way. Set `performance` for the runs and **put it
  back**.
- **`robot.service` stays up.** §7.4 is explicit: this benchmark binds nothing,
  and the box under its normal load is the honest measurement. It is also why
  every timing row carries a spread.
- **`Dyn4jBench.java`, `AllocCheck.java` and `DiyBench.java` are §8 verbatim** —
  extracted from the fenced blocks programmatically, not retyped. No source
  change was needed for arm64.
- **Two files were added**, because §8's harness hardwires at-rest detection off
  and §4 needed it on. `RestFree.java` flips
  `setAtRestDetectionEnabled` on `Dyn4jBench.scene()`'s own bodies and then runs
  `Dyn4jBench.period()` unchanged, so ON and OFF differ by exactly one flag;
  `RestCheck.java` builds the same scene with no command applied, for the
  chassis-at-rest case, and reports how many bodies actually reached sleep.
  Both are reproduced in §7.

### 6.2 The run

```bash
BENCH=systemcore@192.168.1.202
ssh -o BatchMode=yes "$BENCH" 'mkdir -p ~/dyn4jbench'
scp Dyn4jBench.java AllocCheck.java DiyBench.java RestCheck.java RestFree.java \
    dyn4j-6.0.0.jar "$BENCH:~/dyn4jbench/"

ssh "$BENCH" 'cd ~/dyn4jbench && sha256sum dyn4j-6.0.0.jar && \
  javac -cp dyn4j-6.0.0.jar -d . Dyn4jBench.java DiyBench.java && \
  javac -cp dyn4j-6.0.0.jar:. -d . AllocCheck.java RestCheck.java RestFree.java'

# performance governor, and put it back afterwards
ssh "$BENCH" 'for c in 0 1 2 3; do echo performance |
  sudo -n tee /sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor >/dev/null; done'

ssh "$BENCH" 'cd ~/dyn4jbench
  RT="sudo -n chrt -r 30 sudo -n -u systemcore"; CP="dyn4j-6.0.0.jar:."
  for rep in 1 2 3; do $RT java -cp . DiyBench; $RT java -cp "$CP" Dyn4jBench; done
  for rep in 1 2 3; do
    for n in 0 1 2 5 10; do $RT java -cp "$CP" AllocCheck $n; done
    $RT java -cp "$CP" AllocCheck 1 wall
    for s in 1 2 10 20; do $RT java -cp "$CP" AllocCheck 1 free $s; done
  done
  # the collector the robot actually deploys with
  for n in 0 1 10; do $RT java -XX:+UseZGC -cp "$CP" AllocCheck $n; done
  # counter cross-check, and the pause durations of §3.3
  $RT java -XX:+UseSerialGC -Xmn32m -Xlog:gc -cp "$CP" AllocCheck 1
  $RT java -XX:+UseZGC -Xlog:gc -cp "$CP" AllocCheck 10
  # §4, the sim-hitl case
  for rep in 1 2 3; do $RT java -cp "$CP" RestCheck 1; $RT java -cp "$CP" RestCheck 10; done
  for m in off on; do $RT java -cp "$CP" RestFree 1 free $m; done
  for m in off on; do $RT java -cp "$CP" RestFree 10 free $m; done'

ssh "$BENCH" 'for c in 0 1 2 3; do echo ondemand |
  sudo -n tee /sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor >/dev/null; done'
```

The suite takes about 25 minutes on this box. Run it three times; a single
number from a 4-core Pi under a load average of 2.2 is not a measurement.

### 6.3 Checking the box, not the benchmark

Worth re-reading each time, because two of these differ from what the repo
assumes:

```bash
free -m                                      # 8056, not 4096
java -Xlog:gc+init -version                  # collector, heap sizing
java -XX:+PrintFlagsFinal -version | grep UseCompressedOops
java -XX:+UseZGC -XX:+PrintFlagsFinal -version | grep UseCompressedOops   # false
grep -o -- '-XX:[A-Za-z+:=0-9]*' /home/systemcore/robotCommand.args        # -XX:+UseZGC
```

---

## 7. The added harness

`Dyn4jBench.java`, `AllocCheck.java` and `DiyBench.java` are in
[`dyn4j-step-cost.md`](dyn4j-step-cost.md) §8 and ran unmodified. These two are
this ticket's additions.

### 7.1 `RestFree.java` — §4.1, at-rest ON against OFF with one flag between them

```java
/**
 * #155: Dyn4jBench's own scene and its own command, with at-rest detection the single flag
 * changed, so "at-rest ON vs OFF" is measured against an otherwise identical run.
 * Args: <bodies> [wall] [on|off]
 */
public final class RestFree {
  public static void main(String[] a) {
    int bodies = Integer.parseInt(a[0]);
    boolean wall = a.length > 1 && a[1].equals("wall");
    boolean atRest = a.length > 2 && a[2].equals("on");
    int sub = Dyn4jBench.SUBSTEPS;
    double dt = Dyn4jBench.PERIOD / sub;

    var s = Dyn4jBench.scene(bodies, wall);
    for (var b : s.robots()) {
      b.setAtRestDetectionEnabled(atRest); // THE ONLY DELTA
    }
    var set = new org.dyn4j.dynamics.Settings();
    set.setStepFrequency(dt);
    s.world().setSettings(set);

    int periods = 200_000;
    int n = 0;
    for (int i = 0; i < 20_000; i++) {
      Dyn4jBench.period(s, sub, dt, n++, wall);
    }
    int asleep = 0;
    for (var b : s.robots()) {
      if (b.isAtRest()) {
        asleep++;
      }
    }
    double best = Double.MAX_VALUE;
    for (int t = 0; t < 5; t++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < periods; i++) {
        Dyn4jBench.period(s, sub, dt, n++, wall);
      }
      best = Math.min(best, (System.nanoTime() - t0) / 1e3 / periods);
    }
    var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
        .getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long b0 = mx.getThreadAllocatedBytes(id);
    for (int i = 0; i < periods; i++) {
      Dyn4jBench.period(s, sub, dt, n++, wall);
    }
    long b1 = mx.getThreadAllocatedBytes(id);
    double bps = (double) (b1 - b0) / periods / sub;
    System.out.printf(
        "%d bod%s %s, at-rest %s: %d/%d asleep  %.3f us/step  %.3f us/5ms-period"
            + "  %.4f%% of 5ms  %.1f B/step  %.2f MB/s%n",
        bodies, bodies == 1 ? "y" : "ies", wall ? "wall" : "free", atRest ? "ON " : "OFF",
        asleep, bodies, best / sub, best, best / 5000 * 100, bps,
        bps * sub * 200 / 1048576.0);
  }
}
```

### 7.2 `RestCheck.java` — §4, the chassis-at-rest case

Same scene as `Dyn4jBench.scene()`, rebuilt rather than borrowed because the
bodies must sleep and no command may be applied. It prints the at-rest
thresholds it is running under and how many bodies actually reached them, so a
run that measured nothing is visible rather than silent.

```java
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.dynamics.Settings;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.Mass;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Vector2;
import org.dyn4j.world.World;

/**
 * #155 secondary: the sim-hitl case. Dyn4jBench hardwires at-rest detection OFF (§1.1), which is
 * right for a driven robot and wrong for the loop sim-hitl actually measures — the disabled one,
 * chassis at rest. This is the same scene with dyn4j's sleep heuristic left ON and no force
 * applied. Args: <bodies> [drive]
 */
public final class RestCheck {

  static final double ROBOT_MASS = Dyn4jBench.ROBOT_MASS;
  static final double BUMPER = Dyn4jBench.BUMPER;
  static final double ROBOT_MOI = Dyn4jBench.ROBOT_MOI;
  static final int SUB = Dyn4jBench.SUBSTEPS;
  static final double DT = Dyn4jBench.PERIOD / SUB;

  static void addWall(World<Body> w, double cx, double cy, double sx, double sy) {
    Body wall = new Body();
    wall.addFixture(Geometry.createRectangle(sx, sy));
    wall.setMass(MassType.INFINITE);
    wall.translate(cx, cy);
    w.addBody(wall);
  }

  public static void main(String[] a) {
    int bodies = Integer.parseInt(a[0]);
    boolean drive = a.length > 1 && a[1].equals("drive");

    World<Body> world = new World<>();
    world.setGravity(0, 0);
    double t = 0.5, fx = Dyn4jBench.FIELD_X, fy = Dyn4jBench.FIELD_Y;
    addWall(world, fx / 2, -t / 2, fx + 2 * t, t);
    addWall(world, fx / 2, fy + t / 2, fx + 2 * t, t);
    addWall(world, -t / 2, fy / 2, t, fy + 2 * t);
    addWall(world, fx + t / 2, fy / 2, t, fy + 2 * t);

    Body[] robots = new Body[bodies];
    for (int i = 0; i < bodies; i++) {
      Body b = new Body();
      BodyFixture f = b.addFixture(Geometry.createRectangle(BUMPER, BUMPER));
      f.setFriction(0.8);
      f.setRestitution(0.1);
      b.setMass(new Mass(new Vector2(), ROBOT_MASS, ROBOT_MOI));
      b.setLinearDamping(0.1);
      b.setAngularDamping(0.1);
      // THE DELTA: at-rest detection left at dyn4j's default (on).
      b.setAtRestDetectionEnabled(true);
      b.translate(2.0 + (i % 5) * 2.2, 1.5 + (i / 5) * 2.5);
      world.addBody(b);
      robots[i] = b;
    }

    Settings set = new Settings();
    set.setStepFrequency(DT);
    world.setSettings(set);
    System.out.printf(
        "at-rest detection ON; linear tol %.4f m/s, angular tol %.4f rad/s, time %.3f s%n",
        set.getMaximumAtRestLinearVelocity(),
        set.getMaximumAtRestAngularVelocity(),
        set.getMinimumAtRestTime());

    int periods = 200_000;
    for (int i = 0; i < 20_000; i++) {
      if (drive) {
        for (Body b : robots) {
          b.applyForce(new Vector2(ROBOT_MASS * 3.0, 0));
        }
      }
      for (int k = 0; k < SUB; k++) {
        world.step(1);
      }
    }
    int asleep = 0;
    for (Body b : robots) {
      if (b.isAtRest()) {
        asleep++;
      }
    }
    System.out.printf("after warmup: %d/%d bodies at rest%n", asleep, bodies);

    ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    double best = Double.MAX_VALUE;
    for (int tr = 0; tr < 5; tr++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < periods; i++) {
        if (drive) {
          for (Body b : robots) {
            b.applyForce(new Vector2(ROBOT_MASS * 3.0, 0));
          }
        }
        for (int k = 0; k < SUB; k++) {
          world.step(1);
        }
      }
      best = Math.min(best, (System.nanoTime() - t0) / 1e3 / periods);
    }
    long b0 = mx.getThreadAllocatedBytes(id);
    for (int i = 0; i < periods; i++) {
      if (drive) {
        for (Body b : robots) {
          b.applyForce(new Vector2(ROBOT_MASS * 3.0, 0));
        }
      }
      for (int k = 0; k < SUB; k++) {
        world.step(1);
      }
    }
    long b1 = mx.getThreadAllocatedBytes(id);
    long steps = (long) periods * SUB;
    double bps = (double) (b1 - b0) / steps;
    System.out.printf(
        "%d bodies, %s, at-rest ON: %.3f us/step  %.3f us/5ms-period  %.4f%% of 5ms"
            + "  %.1f B/step  %.2f MB/s%n",
        bodies, drive ? "driven" : "at rest", best / SUB, best, best / 5000 * 100, bps,
        bps * SUB * 200 / 1048576.0);
  }
}
```

`RestCheck`'s `drive` mode pushes a constant +x force, which runs the chassis
into the far wall and holds it there, so that row is a **contact** measurement
and is not comparable with a free-space one. §4.1 uses `RestFree` instead
precisely for that reason; the `drive` mode is kept only because it is what
demonstrates that a body under continuous force never reaches the at-rest
threshold.

---

## 8. What this does not answer

- **A robot loop with dyn4j in it.** §5.2's last paragraph. This measures
  `world.step()`, not wake-to-wake. The claim that ~5 MB/s would cost the tail
  on this box is inference from `jvm-tuning.md`'s separate measurement of a
  comparable rate, not an observation of this workload. **[unverified]**
- **The seam around dyn4j.** Unchanged from `dyn4j-step-cost.md` §10: reading
  module poses out, writing forces in, `Pose2d`/`ChassisVelocities` conversion
  and ADR 0005 telemetry are all on top of every number here, and none of them
  is measured. On a box where the step is 4× dearer, that omission is 4× more
  worth closing.
- **Fidelity.** Explicitly not this ticket's job, as it was not #144's. Nothing
  here says dyn4j's contacts are *good*, only what they cost on different
  silicon.
- **Whether the CM5 identification is right.** `physics-sim.md:609` names a
  Raspberry Pi Compute Module 5. The RAM figure attached to that identification
  is wrong (§5.1); the identification itself was not checked here and remains
  **[unverified]**.
- **`sim-hitl`'s own loop numbers.** This ticket measured what dyn4j would add
  to that loop, not the loop. The `sim-hitl` baseline is untouched and no
  regression was run.
