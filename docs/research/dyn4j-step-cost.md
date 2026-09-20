# What a dyn4j step costs against the loop budget

**Research for:** [Drew-Robotics/2027beta#144](https://github.com/Drew-Robotics/2027beta/issues/144)
**Date:** 2026-09-19

## Source and trust level

- **[measured]** — I ran it and read the number off the run. The harness is in
  §8 and the commands that produced every table are in §7.
- **[source]** — read from a named file, cited by path and line.
- **[unverified]** — not checked. Arithmetic, assumption, or something I could
  not reach.

### What was under test

| Thing | Coordinate |
| --- | --- |
| dyn4j | `org.dyn4j:dyn4j:6.0.0`, `sha256 204ca8dd55626ad3727b82dacb37df1800c5eff0d6bb6e782b6e5af5162b2b3e` **[measured]** |
| JDK | Temurin 25.0.3+9 LTS, OpenJDK 64-Bit Server VM **[measured]** |
| CPU | Apple M5 Pro, 18 cores, macOS (Darwin 25.6.0), `aarch64` **[measured]** |

The jar carries **no native code** — zero `.so`/`.dll`/`.dylib` entries — and its
classes are class-file version 50 (Java 6), so it runs on `linuxarm64` unmodified
**[measured]**.

---

## 0. Bottom line

**dyn4j fits, with room to spare, at the rates ADR 0010 already decided.**

One chassis-sized body against a static field boundary, stepped five times per
5 ms period at 1 ms a step, costs **2.48 µs per 5 ms robot period — 0.050 % of
the period** on this workstation **[measured]**. Ten dynamic bodies cost
**9.73 µs — 0.195 %** **[measured]**. A robot held in hard contact with a wall is
the expensive case and costs **6.35 µs — 0.127 %** **[measured]**.

So the *time* is a non-issue by three orders of magnitude on this box, and the
body-count constraint from the map (#142) does not bite: the step is still under
a fifth of a percent at ten bodies.

**The number worth arguing about is allocation, not time.** dyn4j allocates
**686 B per step** with one body, which at 1000 steps/s is **0.65 MB/s** — the
robot program's *entire* measured allocation rate today is 1.05 MB/s
**[source — `docs/research/jvm-tuning.md:51`]**. Ten bodies is **4.13 MB/s**,
four times the whole program. The DIY model it replaces allocates **zero**
**[measured]**. ADR 0002 closed the collector question on the grounds that
"nothing was giving the collector work"
**[source — `docs/adr/0002-loop-rate-and-jvm.md:117-121`]**; dyn4j is the first
thing in this project that would give the collector work, and ADR 0002 names
exactly that as the condition for re-opening
**[source — `docs/adr/0002-loop-rate-and-jvm.md:371-373`]**.

**No Pi number was measured by this ticket.** The bench Pi at `192.168.1.202`
did not answer ssh or ICMP from this workstation **[measured]**, and §7.4
records the exact recipe for someone on the bench LAN to re-run this.
[#155](https://github.com/Drew-Robotics/2027beta/issues/155) has since followed
that recipe — **[`dyn4j-step-cost-pi.md`](dyn4j-step-cost-pi.md)**, and §6
below carries the summary and the two corrections it forced.

---

## 1. What the rates are, and what they are measured against

| | |
| --- | --- |
| Robot period | **5 ms**, 200 Hz **[source — `docs/adr/0002-loop-rate-and-jvm.md:37`]** |
| Sub-step | **1 ms**, five to the period, command held constant across them **[source — `docs/adr/0010-simulation-architecture.md:334-340`]** |
| Step rate on the wire | 1000 dyn4j steps per second of sim time |

Two normalisations appear in every table below, because the project has two in
circulation and mixing them is how this gets misread:

- **`µs / 5 ms period` and `% of 5 ms`** — the real budget. This is the one that
  decides whether it fits.
- **`µs / 20 ms wall` and `% of 20 ms`** — 5 ms periods × 4, so it is directly
  comparable with §6.2 of `physics-sim.md` and with ADR 0010's own "~6.4 µs per
  20 ms of wall clock" **[source — `docs/adr/0010-simulation-architecture.md:346-352`]**.
  Note the percentages in the two normalisations are identical by construction;
  only the µs figure changes.

The thing being replaced: the DIY free-space model, benchmarked at **1.592 µs
per 20 ms robot period at 5 sub-ticks, 0.008 % of the budget**, on an AMD Ryzen
7 5800X3D **[source — `docs/research/physics-sim.md:511-524`]**.

### 1.1 The world under test

| Property | Value | Trust |
| --- | --- | --- |
| Robot mass | 56.699 kg (125 lb competition weight) | **[source — `src/main/java/first/robot/DriveConstants.java:101`]** |
| Module spacing | 23.5 in track width and wheelbase | **[source — `src/main/java/first/robot/DriveConstants.java:94-95`]** |
| Bumper footprint | 0.851 m square (33.5 in) | **[unverified]** — this repo records *no* frame or bumper dimension, only module-centre spacing. Assumed from a 23.5 in spacing. The step cost is not sensitive to it. |
| Chassis MOI | 6.844 kg·m² | **[unverified]** — computed as a uniform rectangle, `m(w²+h²)/12`. `DriveConstants` carries only `STEER_INERTIA` **[source — `:104`]**, not a chassis MOI. |
| Field | 16.541 × 8.211 m, four static walls | **[unverified]** — no 2027 field dimension exists in this repo. Affects broadphase extent only. |
| Gravity | zero — a top-down field, nothing falls | **[decided by the harness]** |
| At-rest detection | **off** | A driven robot never sleeps. Leaving dyn4j's sleep heuristic on would have let idle bodies fall out of the solver and flattered every number below. |

### 1.2 The harness is stepping real physics, not a no-op

Verified before any timing was believed **[measured]**:

```
after 1.000 s: x=1.5015 m  v=3.0000 m/s (expect ~1.5 m, ~3.0 m/s)
after wall:    x=9.5795 m  v=-0.0000 m/s  contacts=1
```

Constant 3 m/s² for exactly 1000 steps of 1 ms gives 3.0000 m/s and 1.5015 m —
semi-implicit Euler, to the digit. Driven into a wall whose face is at
x = 10.0 m, a 0.851 m body settles at 9.5795 m: 5 mm of penetration, which is
dyn4j's linear slop, and holds one contact constraint. The engine is doing the
work the numbers are attributed to.

---

## 2. The headline — one chassis, four static walls, 5 × 1 ms

**[measured]**

| Scenario | µs / step | **µs / 5 ms period** | **% of 5 ms** | µs / 20 ms wall | % of 20 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| **1 robot, driving, free space** | 0.496 | **2.479** | **0.0496 %** | 9.92 | 0.0496 % |
| 1 robot, pressed into a wall | 1.270 | **6.352** | **0.1270 %** | 25.41 | 0.1270 % |
| 0 dynamic bodies (fixed overhead) | 0.292 | 1.460 | 0.0292 % | 5.84 | 0.0292 % |
| **DIY model, same CPU, same rates** | **0.060** | **0.298** | **0.0060 %** | **1.19** | **0.0060 %** |

Two full runs of the whole suite agreed within 3 % on every row; the figures
above are the second run, each cell the **minimum of five timed trials** of
200 000 robot periods after a 200 000-period warmup.

Read this way:

- **dyn4j free-space is 8.3× the DIY model** per step on the same CPU. In
  contact it is **21×**.
- **The fixed overhead is most of the cost at one body.** An empty world with
  only four static walls already costs 1.46 µs per period — **59 % of the
  one-body figure**. Broadphase maintenance, `TimeStep` bookkeeping and the
  solver's per-step scaffolding are paid whether or not anything is in the
  world. This is why the body-count curve in §4 is so flat at the low end, and
  it means "one body" is not a cheap special case worth designing for.
- **The DIY model re-measured on this CPU** is 1.19 µs / 20 ms against §6.2's
  1.592 µs / 20 ms on a 5800X3D — this workstation is about **1.33× faster** on
  that workload, which is the factor to divide out when comparing anything here
  with §6.2 or §8 of `physics-sim.md` **[measured]**.

**dyn4j does not replace the whole DIY model.** Steps 1–6 of
`physics-sim.md` §6.1 — steer slew, module ground velocity, the motor force, the
lateral tire force and the friction circle — still have to compute the per-module
forces that get handed to dyn4j. dyn4j replaces steps 7–8 (accumulate and
integrate) and adds collisions. So the real cost is the dyn4j step *plus* most of
the DIY model's 0.06 µs/step, not instead of it.

---

## 3. Scaling with sub-step count

One robot, free space, the 5 ms period held fixed and divided differently.
**[measured]**

| Sub-steps | dt | µs / step | **µs / 5 ms period** | **% of 5 ms** | B / step | MB/s alloc |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 5.00 ms | 0.758 | 0.758 | 0.0152 % | 1701 | 0.32 |
| 2 | 2.50 ms | 0.619 | 1.238 | 0.0248 % | 1159 | 0.44 |
| **5 (decided)** | **1.00 ms** | **0.510** | **2.551** | **0.0510 %** | **686** | **0.65** |
| 10 | 0.50 ms | 0.501 | 5.010 | 0.1002 % | 582 | 1.11 |
| 20 | 0.25 ms | 0.497 | 9.949 | 0.1990 % | 575 | 2.19 |

**Per-period cost is linear in sub-step count at and below 1 ms, and the per-step
cost is flat there** — 0.510, 0.501, 0.497 µs at 1 ms, 0.5 ms and 0.25 ms. The
two coarse rows are the interesting ones: a *single* 5 ms step costs 0.758 µs,
**53 % more than a 1 ms step**, because a bigger dt moves bodies further per
step and puts more work through narrowphase and the time-of-impact path.

Consequence for the stepping ticket: **there is no cost argument for coarsening
the sub-step.** Going from 5 × 1 ms to 1 × 5 ms saves 1.79 µs per period —
0.036 % of the budget — and buys a materially worse integration. Going the other
way to 10 or 20 sub-steps costs proportionally and stays under 0.2 %, so
sub-stepping finer than ADR 0010 decided is affordable if fidelity ever wants it.

---

## 4. Scaling with body count

Five sub-steps of 1 ms. Bodies are laid out on a 2.2 m grid in the field and all
driven, so they contact each other and the walls as the run goes on.
**[measured]**

| Dynamic bodies | µs / step | **µs / 5 ms period** | **% of 5 ms** | Marginal µs/period per body |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 0.292 | 1.460 | 0.0292 % | — |
| **1** | **0.512** | **2.559** | **0.0512 %** | 1.10 |
| 2 | 0.630 | 3.151 | 0.0630 % | 0.59 |
| 5 | 0.920 | 4.602 | 0.0920 % | 0.48 |
| 10 | 1.947 | 9.734 | **0.1947 %** | 1.03 |

**Ten bodies is 3.8× one body, not 10×** — the fixed overhead of §2 is being
amortised. The marginal cost per body dips to ~0.5 µs/period in the middle of
the range and climbs back to ~1.0 µs/period at ten, which is where bodies on a
2.2 m grid start colliding with each other rather than only with walls. That
climb is the real shape of the curve: the cost is driven by **contacts**, not by
body count.

**This answers the map's binding constraint.** Adding game pieces and a second
chassis later does not put the step anywhere near the budget: ten dynamic bodies
is under 0.2 % of a 5 ms period on this box. A design sized for one body and a
design sized for ten differ by 7 µs per period here.

For calibration against the existing numbers, `physics-sim.md` §8 has dyn4j
**5.0.2** at 6.0 µs / 20 ms for one robot and walls
**[source — `docs/research/physics-sim.md:594-600`]**. This measurement is
9.9 µs / 20 ms for the same shape on a faster CPU at a finer sub-step (1 ms here
against that table's 4 ms) — consistent with §3's finding that per-period cost
rises with sub-step count, so the two are not in conflict.

---

## 5. Allocation — the finding that actually matters

Each configuration measured in **its own JVM**, via
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on the stepping thread,
over 200 000 periods after a 20 000-period warmup. **[measured]**

| Configuration | B / step | **MB/s at 200 Hz × 5 sub-steps** | vs. the whole robot program (1.05 MB/s) |
| --- | ---: | ---: | ---: |
| DIY model, 4 modules | **0** | **0.00** | — |
| dyn4j, 0 dynamic bodies | 184 | 0.18 | +17 % |
| **dyn4j, 1 robot, free space** | **686** | **0.65** | **+62 %** |
| dyn4j, 2 robots | 717 | 0.68 | +65 % |
| dyn4j, 5 robots | 1330 | 1.27 | +121 % |
| dyn4j, 10 robots | 4328 | 4.13 | **+393 %** |
| dyn4j, 1 robot pressed into a wall | 2649 | 2.53 | +241 % |

Cross-checked against the collector: the one-body run allocated 654.6 MB over
1 000 000 steps and, under `-XX:+UseSerialGC -Xmn32m`, drove 28 young collections
of a 32 MB nursery across the whole process — ~900 MB including warmup and JIT,
which brackets the counter **[measured]**.

Three things follow.

**dyn4j allocates on every step, unconditionally.** Even an empty world with four
static bodies allocates 184 B/step. There is no "stepping helper that does not
allocate" to reach for; the allocation is inside `AbstractPhysicsWorld.step`.

**The per-step figure is JIT-sensitive, so treat it as a floor.** Running all
configurations in one JVM, the 0-body case measured 624 B/step rather than 184 —
escape analysis scalar-replaces far more when only one world shape is ever
stepped. The isolated-JVM figures above are the ones closest to a robot program
(one configuration, running for a long time), and they are the *optimistic* end
of the range. The mixed-JVM run put 1 body at 686 and 10 bodies at 4577, both
within ~7 % of the isolated figures, so the effect is confined to the trivial
case.

**This is the number that reopens ADR 0002's JVM question, and nothing else
here does.** ADR 0002 keeps `gcType` at GradleRIO's `ZGC` default, untuned,
explicitly because six collectors were indistinguishable at a 1.05 MB/s
allocation rate and `Epsilon` — never collecting at all — was within 0.035 ms of
`G1_Base`'s steady-state tail
**[source — `docs/adr/0002-loop-rate-and-jvm.md:106-120`]**. It then names the
condition for revisiting: *"New evidence means an allocation rate materially
above 1.05 MB/s"* **[source — `docs/adr/0002-loop-rate-and-jvm.md:371-373`]**.
A simulated field with ten bodies is four times that rate.

Two mitigations that cost nothing to note: **the allocation is on the simulation
thread, not the robot loop**, if the stepping ticket puts it there; and
**simulation does not run on the robot** — ADR 0010 ships the sim behind
`isSimulation()` **[source — `docs/adr/0010-simulation-architecture.md:353-358`]**,
so this rate is only ever paid on a desktop or on the `sim-hitl` job, never in a
match. The GC question is therefore a *bench* question, which is precisely why
the Pi number in §6 is the one that was wanted.

---

## 6. The bench Pi — not measured here; measured since

> **Answered by [#155](https://github.com/Drew-Robotics/2027beta/issues/155) on
> 2026-09-20 — see [`dyn4j-step-cost-pi.md`](dyn4j-step-cost-pi.md).** The rest
> of this section records what *this* ticket could and could not do, and is kept
> because the recipe in §7.4 is what #155 followed. **Two things in the last
> paragraph below are now known to be wrong, and are struck through rather than
> deleted: the Pi has 8 GB, not 4, and the 5–10× extrapolation was too
> pessimistic for dyn4j.** The headline results, for anyone reading only this
> far: one chassis at 5 × 1 ms costs **10.00 µs per 5 ms period, 0.200 % of the
> budget**, ten bodies **49.5 µs, 0.990 %**, and the per-step *byte* counts are
> bit-identical to the workstation's — allocation turns out to be a property of
> the bytecode, not the silicon, so there was no different Pi number to find.
> The context around it does differ: the robot deploys with `-XX:+UseZGC`, ZGC
> ergonomically disables compressed oops, and every allocation figure in §5
> below therefore **understates the deployed configuration by 20–39 %**.

**The bench Pi was unreachable from this workstation. [measured]**

```
$ ping -c 2 192.168.1.202
2 packets transmitted, 0 packets received, 100.0% packet loss

$ ssh -o BatchMode=yes -o ConnectTimeout=8 systemcore@192.168.1.202 uname -a
ssh: connect to host 192.168.1.202 port 22: Operation timed out
```

That is the same box `jvm-tuning.md` measured on
**[source — `docs/research/jvm-tuning.md:2-3`]** and the same address
`.github/bench/sim-hitl.sh:16` defaults to **[source]**. It is reachable only
from the bench LAN — `docs/bench-runner.md` says the runner is "a Linux box on
the same LAN as the bench Pi", and this workstation is not on it
**[source — `docs/bench-runner.md`]**. §7.4 is the recipe.

**No Pi figure is given by this ticket, and none should be inferred from the
paragraph below.** For what it was worth and tagged accordingly at the time:
`physics-sim.md` §8 extrapolates a conservative 5–10× per-core slowdown from a
desktop to a Cortex-A76 **[source — `docs/research/physics-sim.md:612-620`]**,
which would put the one-body step somewhere in 12–25 µs per 5 ms period,
0.25–0.5 % of the budget **[unverified — arithmetic on a number measured on
different silicon; not a measurement]**. ~~The headroom is large enough that the
time answer is unlikely to flip. **The allocation answer is the one that
could**, because the Pi has 4 GB and a different collector profile, and a
4 MB/s allocation rate is a different proposition there than it is here.~~

**Corrected by [#155](https://github.com/Drew-Robotics/2027beta/issues/155):**

- **The Pi has 8056 MB, not 4 GB. [measured — #155]** That figure was never
  measured; it came from `physics-sim.md:609` naming a Compute Module 5 variant,
  and [`jvm-tuning.md:4`](jvm-tuning.md) had already recorded *"4 CPUs, 8 GB"*
  on this same box since 2026-08-25 **[source]**. A bare `java` there gets a
  **2016 MB** heap and G1; the robot gets ZGC. Nothing about this box's memory
  makes 4 MB/s a different proposition than it is on a workstation.
- **The time answer did not flip, and the extrapolation was too pessimistic.**
  Measured 10.00 µs per 5 ms period for one body — a flat **3.8–5.1×** this
  workstation, below the 5–10× band, and under the low end of the 12–25 µs it
  predicted. **[measured — #155]**
- **The allocation answer did not flip either, because it could not.** The
  per-step byte counts are bit-identical on both machines (184.0 against 184,
  717.4 against 717, 4350.5 against 4328) — same bytecode, same object graph,
  same compressed oops under G1. **[measured — #155]** What #155 found instead
  is that the *deployed* collector is ZGC, that ZGC switches compressed oops off
  and so allocates **20–39 % more** than every figure in §5, and that the only
  configuration which runs on Pi hardware — `sim-hitl`'s disabled loop with the
  chassis at rest — sits on **184.0 B/step, the empty-world floor exactly**.
  ADR 0002's collector question is declined rather than reopened.

---

## 7. How to re-run this

### 7.1 Get the jar

```bash
mkdir -p /tmp/dyn4jbench && cd /tmp/dyn4jbench
curl -sSL -o dyn4j-6.0.0.jar \
  https://repo1.maven.org/maven2/org/dyn4j/dyn4j/6.0.0/dyn4j-6.0.0.jar
shasum -a 256 dyn4j-6.0.0.jar
# 204ca8dd55626ad3727b82dacb37df1800c5eff0d6bb6e782b6e5af5162b2b3e
```

Nothing in this benchmark touches `build.gradle`, `vendordeps/` or `src/`. It is
three standalone files compiled against that one jar.

### 7.2 Timing and scaling — §2, §3, §4

```bash
javac -cp dyn4j-6.0.0.jar -d . Dyn4jBench.java
java -cp dyn4j-6.0.0.jar:. Dyn4jBench
```

### 7.3 Allocation — §5

```bash
javac -cp dyn4j-6.0.0.jar:. -d . AllocCheck.java
for n in 0 1 2 5 10; do java -cp dyn4j-6.0.0.jar:. AllocCheck $n; done
java -cp dyn4j-6.0.0.jar:. AllocCheck 1 wall
for s in 1 2 10 20; do java -cp dyn4j-6.0.0.jar:. AllocCheck 1 free $s; done
# collector cross-check
java -XX:+UseSerialGC -Xmn32m -Xlog:gc -cp dyn4j-6.0.0.jar:. AllocCheck 1
```

### 7.4 On the bench Pi

Everything above is pure Java with no native code and no WPILib, so the Pi needs
nothing staged — not `simHitlStage`, not a vendordep, not a deploy. From a box on
the bench LAN, with the ssh key `docs/bench-runner.md` requires:

```bash
BENCH=${BENCH:-systemcore@192.168.1.202}
ssh -o BatchMode=yes "$BENCH" 'mkdir -p ~/dyn4jbench'
scp docs/research/dyn4j-step-cost.md "$BENCH:~/dyn4jbench/"   # extract §8 there, or scp the .java files
ssh "$BENCH" 'cd ~/dyn4jbench && \
  curl -sSL -o dyn4j-6.0.0.jar https://repo1.maven.org/maven2/org/dyn4j/dyn4j/6.0.0/dyn4j-6.0.0.jar && \
  javac -cp dyn4j-6.0.0.jar -d . Dyn4jBench.java DiyBench.java && \
  javac -cp dyn4j-6.0.0.jar:. -d . AllocCheck.java && \
  java -cp dyn4j-6.0.0.jar:. Dyn4jBench && java -cp . DiyBench'
```

Two things to do differently on the Pi, or the numbers will be noise:

- **Do not stop `robot.service` for this.** `sim-hitl.sh` has to, because it
  binds the sim's ports; this benchmark binds nothing. Leaving the box in its
  normal state is the more honest measurement.
- **Pin the thread and check the governor.** `jvm-tuning.md` ran its loop thread
  at `SCHED_RR` 30 **[source — `docs/research/jvm-tuning.md:19`]**; a
  free-running benchmark on a 4-core Pi is at the mercy of whatever else is on
  it. `chrt -r 30 java ...` and a `performance` governor make the run
  comparable with that document.
- **Run `DiyBench` too, on the same box.** The ratio between the two on one CPU
  is the durable finding; the absolute µs is not.

---

## 8. The harness

### 8.1 `Dyn4jBench.java` — §2, §3, §4

Min-of-five over 200 000-period trials, after an equal warmup. The command is
applied once per robot period and held across the sub-steps, which is what
ADR 0010 decided the model does
**[source — `docs/adr/0010-simulation-architecture.md:336-345`]**.

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

public final class Dyn4jBench {

  // DriveConstants: 125 lb competition weight, 23.5 in module spacing.
  static final double ROBOT_MASS = 56.699;
  static final double BUMPER = 0.851; // 33.5 in bumper-to-bumper square
  static final double ROBOT_MOI = ROBOT_MASS * (BUMPER * BUMPER + BUMPER * BUMPER) / 12.0;

  static final double FIELD_X = 16.541;
  static final double FIELD_Y = 8.211;

  static final double PERIOD = 0.005; // ADR 0002
  static final int SUBSTEPS = 5; // ADR 0010

  record Scene(World<Body> world, Body[] robots) {}

  static Scene scene(int bodies, boolean pressedIntoWall) {
    World<Body> world = new World<>();
    world.setGravity(0, 0); // top-down field; nothing falls

    double t = 0.5;
    addWall(world, FIELD_X / 2, -t / 2, FIELD_X + 2 * t, t);
    addWall(world, FIELD_X / 2, FIELD_Y + t / 2, FIELD_X + 2 * t, t);
    addWall(world, -t / 2, FIELD_Y / 2, t, FIELD_Y + 2 * t);
    addWall(world, FIELD_X + t / 2, FIELD_Y / 2, t, FIELD_Y + 2 * t);

    Body[] robots = new Body[bodies];
    for (int i = 0; i < bodies; i++) {
      Body b = new Body();
      BodyFixture f = b.addFixture(Geometry.createRectangle(BUMPER, BUMPER));
      f.setFriction(0.8);
      f.setRestitution(0.1);
      b.setMass(new Mass(new Vector2(), ROBOT_MASS, ROBOT_MOI));
      b.setLinearDamping(0.1);
      b.setAngularDamping(0.1);
      b.setAtRestDetectionEnabled(false); // a driven robot never sleeps; do not flatter the number
      if (pressedIntoWall) {
        b.translate(BUMPER / 2 + 0.001, 1.0 + i * 1.2);
      } else {
        b.translate(2.0 + (i % 5) * 2.2, 1.5 + (i / 5) * 2.5);
      }
      world.addBody(b);
      robots[i] = b;
    }
    return new Scene(world, robots);
  }

  static void addWall(World<Body> w, double cx, double cy, double sx, double sy) {
    Body wall = new Body();
    wall.addFixture(Geometry.createRectangle(sx, sy));
    wall.setMass(MassType.INFINITE);
    wall.translate(cx, cy);
    w.addBody(wall);
  }

  /** One robot period: hold the command across the sub-steps, as ADR 0010 says to. */
  static void period(Scene s, int substeps, double dt, int n, boolean pressedIntoWall) {
    double a = pressedIntoWall ? 4.0 : 3.0;
    for (int i = 0; i < s.robots.length; i++) {
      Body b = s.robots[i];
      double th = pressedIntoWall ? Math.PI : (n * 0.004 + i * 1.3);
      b.applyForce(new Vector2(Math.cos(th) * ROBOT_MASS * a, Math.sin(th) * ROBOT_MASS * a));
      b.applyTorque(pressedIntoWall ? 0.0 : 8.0 * Math.sin(n * 0.002 + i));
    }
    for (int k = 0; k < substeps; k++) {
      s.world.step(1);
    }
  }

  record Result(double usPerPeriod, double bytesPerStep) {}

  static java.util.function.Consumer<Settings> LEVER = s -> {};

  static Result run(int bodies, int substeps, boolean pressedIntoWall, int periods, int trials) {
    double dt = PERIOD / substeps;
    Settings settings = new Settings();
    settings.setStepFrequency(dt);
    LEVER.accept(settings);

    Scene s = scene(bodies, pressedIntoWall);
    s.world().setSettings(settings);

    int n = 0;
    for (int i = 0; i < periods; i++) { // warmup
      period(s, substeps, dt, n++, pressedIntoWall);
    }

    double best = Double.MAX_VALUE;
    for (int t = 0; t < trials; t++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < periods; i++) {
        period(s, substeps, dt, n++, pressedIntoWall);
      }
      long t1 = System.nanoTime();
      best = Math.min(best, (t1 - t0) / 1e3 / periods);
    }

    ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long a0 = mx.getThreadAllocatedBytes(id);
    for (int i = 0; i < periods; i++) {
      period(s, substeps, dt, n++, pressedIntoWall);
    }
    long a1 = mx.getThreadAllocatedBytes(id);
    double bytesPerStep = (double) (a1 - a0) / periods / substeps;

    return new Result(best, bytesPerStep);
  }

  static void row(String label, int bodies, int substeps, boolean wall, int periods, int trials) {
    Result r = run(bodies, substeps, wall, periods, trials);
    double perStep = r.usPerPeriod() / substeps;
    System.out.printf(
        "%-42s  %8.3f us/step  %9.3f us/5ms-period  %7.4f%% of 5ms  %9.3f us/20ms-wall  %7.4f%% of 20ms  %8.1f B/step%n",
        label,
        perStep,
        r.usPerPeriod(),
        r.usPerPeriod() / 5000.0 * 100.0,
        r.usPerPeriod() * 4.0,
        r.usPerPeriod() * 4.0 / 20000.0 * 100.0,
        r.bytesPerStep());
  }

  public static void main(String[] args) {
    System.out.println("java.version=" + System.getProperty("java.version")
        + "  vm=" + System.getProperty("java.vm.name")
        + "  os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    System.out.println("dyn4j 6.0.0; gravity 0; at-rest detection off; "
        + "robot " + BUMPER + " m square, " + ROBOT_MASS + " kg, MOI "
        + String.format("%.3f", ROBOT_MOI));
    System.out.println();

    int periods = 200_000;
    int trials = 5;

    System.out.println("--- 1. headline: 1 robot + 4 static walls, ADR 0010 rates (5 x 1 ms) ---");
    row("1 robot, driving, free space", 1, 5, false, periods, trials);
    row("1 robot, pressed into a wall", 1, 5, true, periods, trials);
    System.out.println();

    System.out.println("--- 2. sub-step scaling, 1 robot, 5 ms period held fixed ---");
    for (int ss : new int[] {1, 2, 5, 10, 20}) {
      row(ss + " sub-step(s) of " + String.format("%.2f", PERIOD / ss * 1000) + " ms",
          1, ss, false, periods, trials);
    }
    System.out.println();

    System.out.println("--- 3. body-count scaling, 5 x 1 ms ---");
    for (int nb : new int[] {1, 2, 5, 10}) {
      row(nb + " dynamic bod" + (nb == 1 ? "y" : "ies"),
          nb, 5, false, nb >= 5 ? periods / 4 : periods, trials);
    }
    System.out.println();

    System.out.println("--- 4. tuning levers, 1 robot pressed into a wall, 5 x 1 ms ---");
    record Lever(String name, java.util.function.Consumer<Settings> f) {}
    Lever[] levers = {
      new Lever("stock settings", s -> {}),
      new Lever("continuous detection OFF",
          s -> s.setContinuousDetectionMode(org.dyn4j.dynamics.ContinuousDetectionMode.NONE)),
      new Lever("velocity iters 6->3, position 2->1", s -> {
        s.setVelocityConstraintSolverIterations(3);
        s.setPositionConstraintSolverIterations(1);
      }),
      new Lever("warm starting OFF", s -> s.setWarmStartingEnabled(false)),
      new Lever("CCD off + iters 3/1", s -> {
        s.setContinuousDetectionMode(org.dyn4j.dynamics.ContinuousDetectionMode.NONE);
        s.setVelocityConstraintSolverIterations(3);
        s.setPositionConstraintSolverIterations(1);
      }),
    };
    for (Lever l : levers) {
      LEVER = l.f();
      row(l.name(), 1, 5, true, periods, trials);
    }
    LEVER = s -> {};
    System.out.println();

    System.out.println("--- 5. empty world floor (4 static walls, 0 dynamic bodies) ---");
    row("0 dynamic bodies", 0, 5, false, periods, trials);
  }
}
```

### 8.2 `AllocCheck.java` — §5

One configuration per JVM, so escape analysis sees a single world shape, which
is the closer analogue to a robot program.

```java
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

public class AllocCheck {
  public static void main(String[] a) {
    int bodies = Integer.parseInt(a[0]);
    boolean wall = a.length > 1 && a[1].equals("wall");
    int sub = a.length > 2 ? Integer.parseInt(a[2]) : 5;
    int periods = 200_000;
    var s = Dyn4jBench.scene(bodies, wall);
    var set = new org.dyn4j.dynamics.Settings();
    set.setStepFrequency(0.005 / sub);
    s.world().setSettings(set);
    int n = 0;
    for (int i = 0; i < 20_000; i++) Dyn4jBench.period(s, sub, 0.005 / sub, n++, wall);
    ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long b0 = mx.getThreadAllocatedBytes(id);
    for (int i = 0; i < periods; i++) Dyn4jBench.period(s, sub, 0.005 / sub, n++, wall);
    long b1 = mx.getThreadAllocatedBytes(id);
    long steps = (long) periods * sub;
    System.out.printf(
        "%d bodies%s, %d sub-steps: %d steps, %.1f MB allocated, %.1f B/step, %.2f MB/s of sim time%n",
        bodies, wall ? " (wall contact)" : "", sub, steps, (b1 - b0) / 1048576.0,
        (double) (b1 - b0) / steps,
        (double) (b1 - b0) / steps * sub * 200 / 1048576.0);
  }
}
```

### 8.3 `DiyBench.java` — the §2 baseline

`physics-sim.md` §6.1's eight steps, four modules, re-implemented only so §6.2's
number can be re-measured on the same CPU as dyn4j. It is not a proposal for
production code, and its constants are plausible rather than calibrated. The
command sweeps so that both sides of the friction-circle branch execute.

```java
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

public final class DiyBench {
  static final double M = 56.699, J = 6.844, G = 9.80665, MU = 1.2;
  static final double KLAT = 4000.0;                      // lateral tire stiffness, N per m/s
  static final double KT = 0.0194, KV = 594.4, R = 0.039; // NEO Vortex, SI
  static final double GEAR = 5.9, RW = 0.0508;
  static final double[] RX = {0.29845, 0.29845, -0.29845, -0.29845};
  static final double[] RY = {0.29845, -0.29845, 0.29845, -0.29845};

  static double x, y, th, vx, vy, w;
  static final double[] az = new double[4], ws = new double[4];

  static void subTick(double dt, double[] dv, double[] sv) {
    double fx = 0, fy = 0, tz = 0;
    for (int i = 0; i < 4; i++) {
      az[i] += (sv[i] * 2.0 - az[i] * 8.0) * dt;          // first-order steer slew
      double mx = vx - w * RY[i], my = vy + w * RX[i];    // v_chassis + w x r
      double h = th + az[i];
      double ch = Math.cos(h), sh = Math.sin(h);
      double vlong = mx * ch + my * sh, vlat = -mx * sh + my * ch;
      double wm = ws[i] / RW * GEAR;
      double cur = (dv[i] - wm / KV) / R;
      double flong = KT * cur * GEAR / RW;
      double flat = -KLAT * vlat;
      double lim = MU * M * G / 4.0;
      double mag = Math.hypot(flong, flat);
      if (mag > lim) {                                    // friction circle: the slip branch
        double s = lim / mag;
        flong *= s; flat *= s;
        ws[i] += (wm / GEAR * RW - ws[i]) * 20.0 * dt;
      } else {
        ws[i] = vlong;
      }
      double wfx = flong * ch - flat * sh, wfy = flong * sh + flat * ch;
      fx += wfx; fy += wfy;
      tz += RX[i] * wfy - RY[i] * wfx;
    }
    vx += fx / M * dt; vy += fy / M * dt; w += tz / J * dt;  // semi-implicit Euler
    x += vx * dt; y += vy * dt; th += w * dt;
    if (th > Math.PI) th -= 2 * Math.PI; else if (th < -Math.PI) th += 2 * Math.PI;
  }

  /** A command that actually turns the robot and crosses the traction limit, so both branches run. */
  static void cmd(double[] dv, double[] sv, int n) {
    double t = n * 0.0002;
    for (int i = 0; i < 4; i++) {
      dv[i] = 9.0 * Math.sin(t + i * 0.7);
      sv[i] = 0.9 * Math.cos(t * 1.3 + i);
    }
  }

  public static void main(String[] a) {
    double[] dv = new double[4], sv = new double[4];
    int periods = 2_000_000, sub = 5;
    double dt = 0.005 / sub;

    for (int i = 0; i < periods; i++) { cmd(dv, sv, i); for (int k = 0; k < sub; k++) subTick(dt, dv, sv); }

    double best = Double.MAX_VALUE;
    for (int t = 0; t < 5; t++) {
      long t0 = System.nanoTime();
      for (int i = 0; i < periods; i++) { cmd(dv, sv, i); for (int k = 0; k < sub; k++) subTick(dt, dv, sv); }
      best = Math.min(best, (System.nanoTime() - t0) / 1e3 / periods);
    }
    ThreadMXBean mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long id = Thread.currentThread().threadId();
    long b0 = mx.getThreadAllocatedBytes(id);
    for (int i = 0; i < periods; i++) { cmd(dv, sv, i); for (int k = 0; k < sub; k++) subTick(dt, dv, sv); }
    long b1 = mx.getThreadAllocatedBytes(id);

    System.out.printf("DIY 4-module model, %d sub-steps of %.2f ms%n", sub, dt * 1000);
    System.out.printf("  %.4f us/step   %.3f us/5ms-period  %.4f%% of 5ms   %.3f us/20ms-wall"
        + "  %.4f%% of 20ms   %.1f B/step%n",
        best / sub, best, best / 5000 * 100, best * 4, best * 4 / 20000 * 100,
        (double) (b1 - b0) / periods / sub);
    System.out.printf("  (state sink so nothing is elided: x=%.3f y=%.3f th=%.3f)%n", x, y, th);
  }
}
```

---

## 9. Levers, if the stepping ticket ever needs them

One robot pressed into a wall — the expensive case — at 5 × 1 ms. **[measured]**

| Settings | µs / 5 ms period | Δ | B / step |
| --- | ---: | ---: | ---: |
| stock | 6.452 | — | 3595 |
| warm starting off | 6.473 | +0.3 % | 3595 |
| velocity iterations 6→3, position 2→1 | 5.980 | −7 % | 3503 |
| continuous detection off | 5.656 | −12 % | 3295 |
| both of the above | 5.142 | **−20 %** | 3106 |

Turning continuous collision detection off is the biggest single lever and is
defensible at a 1 ms sub-step: a 4.5 m/s robot moves 4.5 mm per sub-step against
a 0.851 m body, so there is nothing to tunnel through. Cutting solver iterations
is the cheaper-looking one and is the one to be careful with — it buys 7 % and
spends contact accuracy, which is the entire reason dyn4j is on the table.

**None of these is needed at the budget measured here.** They are recorded so the
stepping ticket does not have to re-derive them if the Pi number lands worse than
expected.

---

## 10. What this does not answer

- **The Pi.** §6. Unmeasured, and the one that matters.
- **The cost of the seam around dyn4j.** This measures `world.step()` and nothing
  else. Reading four module poses out per period, writing forces in, converting
  to and from `Pose2d`/`ChassisVelocities`, and the `StructArrayPublisher`
  telemetry ADR 0005 would want are all on top, and none of it is measured here.
  `physics-sim.md` §8 calls maple-sim's own Java on top of dyn4j "small by
  comparison" **[source — `docs/research/physics-sim.md:601-603`]** — that is a
  claim about a different codebase and is **[unverified]** for ours.
- **Fidelity.** Nothing here says dyn4j's contacts are *good*, only what they
  cost. Whether a 1 ms sub-step with stock solver iterations produces a
  believable push match is a separate question and not a budget one.
- **Whether dyn4j 6.0.0 is the right coordinate.** It is the one the map names,
  it resolves from Maven Central, and it is pure Java. Its licence, its release
  cadence and whether it wants a vendordep or a plain `implementation` line are
  `vendordeps.md` questions, not this one.
