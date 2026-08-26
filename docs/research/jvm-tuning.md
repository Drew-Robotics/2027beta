# JVM tuning on SystemCore

Measured 2026-08-25 on the SystemCore Pi (`192.168.1.202`,
limelightosr-2027.0.0-beta14, MRC API 11, Temurin 25.0.2+10, 4 CPUs, 8 GB)
against WPILib 2027 alpha-7. Harness:
[`loop-bench/LoopBenchGc.java`](loop-bench/LoopBenchGc.java) driven by
[`loop-bench/runbench.sh`](loop-bench/runbench.sh), deployed via
`./gradlew :developerRobot:deployJava` from `~/dev/allwpilib`.

This follows [`loop-rate.md`](loop-rate.md), which settled the period at 5 ms
and left the tail underneath it open.

## What was measured

The same swerve-shaped workload as the loop-rate bench — 4-module kinematics →
`discretize` → `desaturateWheelVelocities` → per-module `optimize` →
`SwerveDrivePoseEstimator3d.updateWithTime`, plus ~50 telemetry signals to both
NetworkTables and a WPILOG — but as **one continuous 150 s phase** rather than
three short ones, so GC and JIT tails have somewhere to land. 30 000 samples at
5 ms, loop thread at `SCHED_RR` 30 throughout. `wake` is wake-to-wake delta.

The previous bench registered its WPILOG backend part-way through the run;
this one wires everything in the constructor, so nothing changes mid-measurement.

## Collectors

| collector | wake p99 | wake max | **max after first 10 s** | collections in 150 s | GC time |
|---|---|---|---|---|---|
| `G1_Base` | 5.026 ms | 47.98 ms @ t=0 | **5.196 ms** | 3 young | 13 ms |
| `ZGC` | 5.030 ms | 48.70 ms @ t=0 | 5.527 ms | 1 major cycle | 25 ms |
| `Shenandoah` | 5.024 ms | 56.75 ms @ t=0 | — | **0** | 0 ms |
| `Parallel` | 5.025 ms | 49.68 ms @ t=0 | 5.168 ms | 5 scavenge | 20 ms |
| `Serial` | 5.025 ms | 47.66 ms @ t=0 | 6.617 ms | 5 copy | 16 ms |
| `Epsilon` | 5.025 ms | 51.45 ms @ t=0 | **5.161 ms** | **never collects** | — |
| `G1` (pause-goal preset) | 5.026 ms | 55.73 ms @ t=0 | 6.367 ms | **24 young** | 34 ms |
| `G1_Base` + `AlwaysPreTouch`, `-Xms=-Xmx=512m` | 5.026 ms | 53.17 ms @ t=0 | 5.227 ms | 2 young | 7 ms |
| `G1_Base` + `-XX:AOTCache` | 5.023 ms | **19.76 ms** @ t=0 | 5.148 ms | 2 young | 6 ms |

Each run used the collector it names: the GC MXBean names differ per run
(`G1 Young Generation`, `ZGC Major Cycles`, `PS Scavenge`, `Copy`,
`Epsilon Heap`), which is reported by the collector actually installed, not by
the flag string.

### Findings

**Garbage collection is not the lever, and Epsilon proves it.** A collector that
provably never runs produced a steady-state tail of 5.161 ms and a startup spike
of 51.4 ms — indistinguishable from G1's 5.196 / 47.98. If GC caused either
number, removing GC entirely would have changed it.

**No collector was given work to do.** Allocation is **1.05 MB/s, 5.5 KB per
loop, 158 MB per 150 s match**, so a 128 MB nursery fills a handful of times in
a whole match. This says *the collector is not the variable at this allocation
rate* — not that these collectors are equivalent under load.

**Steady-state Java holds the period.** Away from transitions, worst-case wake
is 5.16–5.23 ms at a 5 ms period — roughly **200 µs** of jitter, the same order
as C++ at `SCHED_RR` (37 µs) and nothing like the 12–18 ms
[`loop-rate.md`](loop-rate.md) recorded. That earlier tail was very likely its
own phase transitions — registering a second telemetry backend mid-run — rather
than a standing GC problem.

**`AlwaysPreTouch` and a fixed heap change nothing**, so the startup spike is not
first-touch page faults either. Between that and Epsilon, the ~50 ms at t=0 is
cornered as class loading and JIT.

**Allocation reduction beats every collector.** Logging the same fifty signals as
`Measure` objects instead of raw doubles:

| | alloc/loop | rate | per match | collections | GC time | steady max | wakes >1 ms over |
|---|---|---|---|---|---|---|---|
| raw doubles | 5.5 KB | 1.05 MB/s | 158 MB | 3 | 13 ms | 5.196 ms | 2 |
| `Measure` per sample | **21.8 KB** | **4.17 MB/s** | **625 MB** | 9 | 46 ms | **7.621 ms** | 5 |

Per-thread attribution puts effectively all of it on the loop thread
(`main=626.6MB`); the NetworkTables and DataLog writer threads allocate nothing
measurable. This is the only change measured here that visibly worsened the tail.

## The transition, not the loop

The 5 ms steady state is uneventful; the cost is **cold code at a transition**,
and autonomous enable is the transition that matters — a robot loads its
trajectory and builds its follower there, seconds after boot.

Measured by loading a **273 KB, 1000-sample** `HolonomicTrajectory` on the loop
thread 30 s into a warm run, then constructing three `PIDController`s and
following the first sample:

| trajectory handling at enable | pause | AOT |
|---|---|---|
| cold `loadFromFile` at enable | **63.32 ms** | 46.45 ms |
| classes warm, file re-read and re-parsed | 26.98 ms | 11.65 ms |
| **cached object reused (no I/O, no parse)** | **0.135 ms** | 0.100 ms |

At 5 ms, 63 ms is **twelve iterations swallowed in one gulp** — larger than the
JVM startup spike in the same run (54 ms), and unlike startup it lands inside
autonomous. The samples either side show a single hole, not a smear:
`[-1] 4.99ms → [0] 5.00ms → [1] 63.32ms → [2] 1.80ms`.

With the cached trajectory reused the transition is **invisible**
(`5.01, 5.00, 4.99, 5.00, 4.99`), and the 69 ms moves to `robotInit` where
nothing is timing anything. `HolonomicTrajectory.loadFromFile` goes through
`Jsonb.instance()`, so the first call boots the whole avaje-jsonb machinery —
which is why the cold number is so much larger than a 273 KB read suggests.

**The cache has to be eager.** A trajectory cache populated lazily on first use
pays the full 63 ms inside autonomous; populated at `robotInit`, it pays 0.135 ms.

## Trap: the AOT cache silently runs stale code

JDK 25's AOT cache (`-XX:AOTCacheOutput` to train, `-XX:AOTCache` to use) is the
only thing measured here that moved the startup spike — **47.98 ms → 19.76 ms** —
and it is not safe to ship.

**Verified by execution.** One line was changed, the jar redeployed, and the same
jar run twice:

```
with -XX:AOTCache      (no output)                  <- ran the OLD code
without the cache      LOOPBENCH_GC BUILD=VERSION_TWO   <- ran the new code
```

`-Xlog:class+load` confirms it: **2321 of 2365 classes** came from
`source: shared objects file`, the changed application class among them.

There is no loud-failure option:

- **`-XX:AOTMode=on`**, the strict mode, still ran the stale code.
- **`-Xlog:aot=info`** reported no mismatch, no staleness, no validation failure.
- Nothing is printed at any level tried.

So a deploy that appears to succeed can leave the robot running the previous
build, with the bug still present and nothing anywhere saying why. The mechanism
was not chased down — a wildcard classpath (`-cp "dir/*"`) is the obvious
suspect — but the behaviour is reproducible on this exact deploy path.

The training run itself works fine: `-XX:AOTCacheOutput` forks a child JVM and
assembles a **23.7 MB** cache in a few seconds.

## Where JVM flags live

Not in `robotCommand` by hand. `WPILibJavaArtifact` (GradleRIO 2027.0.0-alpha-6)
carries a first-class `gcType` property and a `jvmArgs` list:

```gradle
deploy { targets { systemcore { artifacts { wpilibJava {
    gcType = ...          // GarbageCollectorType
    jvmArgs.add("...")
} } } } }
```

**The default is `GarbageCollectorType.ZGC`** — verified in the plugin bytecode
(`getstatic GarbageCollectorType.ZGC; putfield gcType`). allwpilib's own
`developerRobot/build.gradle` hardcodes `-XX:+UseG1GC` into the robotCommand
echo, but that is not a GradleRIO project and is not what a template deploy runs.

| enum | flags |
|---|---|
| `G1` | `-XX:+UseG1GC -XX:MaxGCPauseMillis=1 -XX:GCTimeRatio=1` |
| `G1_LongPause` | `-XX:+UseG1GC -XX:MaxGCPauseMillis=5 -XX:GCTimeRatio=1` |
| `G1_Base` | `-XX:+UseG1GC` |
| **`ZGC` (default)** | `-XX:+UseZGC` |
| `Serial` / `Parallel` | plain |
| `Serial_PauseGoal` / `Parallel_PauseGoal` | plus `-XX:MaxGCPauseMillis=5` |
| `Other` | none — supply your own via `jvmArgs` |

Shenandoah and Epsilon are absent from the enum; either would need `Other` plus
hand-rolled args.

⚠️ **`G1` is a trap by name.** Its 1 ms pause goal makes G1 collect **eight times
more often** (24 young collections vs 3) for a **worse** tail than `G1_Base`. The
preset that sounds tailor-made for robot code is the worst G1 configuration
measured.

## Caveats

No CAN hardware and no Driver Station were attached, so eight real
`getPosition()` calls per loop are absent from every number here — including the
allocation rate that makes the collector choice moot. The enable-transition
figures use a synthetic 1000-sample trajectory and a three-PID follower standing
in for a real one; the shape of the finding (cold parse dominates, a cached
object erases it) does not depend on those details, but the absolute
milliseconds will move.
