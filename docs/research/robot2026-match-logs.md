# Robot2026's playoff logs, read against the code that wrote them

**Research for:** [Drew-Robotics/2027beta#153](https://github.com/Drew-Robotics/2027beta/issues/153)
**Date:** 2026-09-19

Three real match WPILOGs from another team's 2026 robot, read against that
robot's own declared constants. The deliverable is the **residual between what
its code predicted and what its log did** — dimensionless where the constants
are not. Nothing absolute in here is ours.

> **Order of work.** §3 (drag), §5 (the 8.36 provenance), §6, §7 and §8 were
> measured before [`wheel-cof.md`](wheel-cof.md) landed on `main` at `f026d3b`.
> **§4 was rewritten afterwards** to test the specific prediction that file
> makes about these logs — slip confined to the unloading axle. The raw
> measurements did not change; the hypothesis being tested against them did.

## Source and trust level

This repo's claim tags, as ADR 0014 defines them:

- **[measured]** — I measured it from the log. The method is recorded beside it.
- **[source]** — read from a named file, cited by path and line.
- **[executed]** — I ran it and read the output.
- **[unverified]** — inferred. **Everything extrapolated across the two robots is
  `[unverified]` no matter how reasonable**, because Robot2026 is a
  CTRE/Kraken/MK4i-class swerve and this project is REV MAXSwerve on SPARKs.

### Inputs

| Thing | Where | Note |
| --- | --- | --- |
| `FRC_20260416_175209_NYTR_P10.wpilog` | `~/Downloads/drive-download-20260919T210154Z-1-001/` | 43 MB, 606 s |
| `FRC_20260416_184940_NYTR_P15.wpilog` | same | 50 MB, 586 s |
| `FRC_20260416_194254_NYTR_P20.wpilog` | same | 43 MB, 514 s |
| Code | `~/dev/Robot2026` @ `6e130c43cfe5426673bafd5031a31cdde7238359` | `frc2053/Robot2026` |

**The logs are not in this repo and must not be.** `logs/` is gitignored and
these are 136 MB of someone else's match data. Everything below is reproducible
from the parser in the appendix against those paths.

#### The checkout is one commit later than the build that ran — and it does not matter **[executed]**

All three logs carry the same build metadata:

```
/Metadata/Build Date      2026-04-16 09:07:14 EDT
/Metadata/Git SHA         8ea8901aaf766996c8e7d0f9c25a591aa6e538f1
/Metadata/Git Date        2026-04-15 19:13:26 EDT
/Metadata/WPILib Version  2026.2.1
/Metadata/Runtime Type    kRoboRIO2
```

`8ea8901` is not in the supplied checkout (it is on the upstream fork). The
supplied `6e130c4` is a merge whose last real commit is `69b4508` *"kyles random
rpm change at comp"*, 2026-04-16 18:27 EDT, and that commit touches **only**
`ShooterConstants.shootingDataPoints` — five RPM numbers. `TunerConstants.java`
was last changed at `77e7d91`, **2026-03-27**, three weeks before the matches.

**So every drivetrain constant used below is provably the one that produced these
logs**, even though the exact build SHA is not resolvable locally. **[executed]**

FMS metadata is empty (`EventName` blank, `MatchNumber` 0), so the match
identity rests on the filenames alone. **[measured]**

---

## 0. Read this first

1. **The drag ratio is 1.00, and it is a lower bound.** Robot2026's fastest
   wheel in three whole playoff matches was **4.692 m/s**, against a declared
   free speed of 4.58 m/s and a Kraken X60 nameplate at its own reduction of
   4.783 m/s — **1.02 and 0.98**. It was *still accelerating* when it ran out of
   field. **There is no measurable drag deficit at the top end.** §3.
2. **The wheels slipped, on every hard launch and every hard stop — but not on
   the unloading axle.** `wheel-cof.md` predicts slip confined to the axle that
   weight transfer unloads. Robot2026 slips far harder than that: its per-wheel
   commanded force at its own current limit is **304.6 N against 255.6 N of
   Grip V2 grip, a margin of 0.84 — below 1 at *zero* CG height.** All four
   wheels break together, and the axle-confined signature is absent (47 of 81
   episodes in the predicted direction, binomial p = 0.18). **Right about
   whether; wrong about where, for this robot.** Our margin is 2.58×, the
   opposite side of 1, so the signature should be visible on ours. §4.
3. **The 8.36 m/s² figure came from a sim log. The `[measured]` tag is wrong.**
   And the real-robot version of that measurement *cannot be taken at all*,
   because the manoeuvre it names measures a wheel, not a chassis. §5.
4. **A match log is not a characterisation run.** Across three matches and 420 s
   of enabled driving there are **0.34 s** above 4.3 m/s, **~10 s per match** of
   clean straight-line driving, **three** standing starts, and **zero** windows
   suitable for a wheel-radius measurement. §2, §6.
5. **The battery model's 7.2 V floor is not obviously wrong.** A real pack at
   this event sat below 7.8 V one per cent of its enabled time. §8.

---

## 1. The timestamp base, settled three ways **[executed]**

`CLAUDE.md` warns that the WPILOG *file* stores **microseconds** while the Java
`DataLog`/`DataLogRecord` API divides and multiplies so it is nanoseconds in both
directions, and that **only a hand-rolled parser sees the microseconds**. I
hand-rolled a parser (appendix), so I am in the microseconds case.

**I parsed the record timestamps as microseconds.** Confirmed independently three
times, because getting it wrong scales every derived acceleration by 10⁶ and
still plots:

| Check | As microseconds | As nanoseconds |
| --- | --- | --- |
| Whole-file span, P10 | 627.0 s — a plausible DS session | 0.000627 s |
| Teleop enable windows, all three | 141.4 / 139.9 / 140.0 s — an FRC teleop | 0.14 ms |
| `systemTime` payload (epoch µs) | `1776361929930046` → 2026-04-16 17:52:09 UTC, matching the filename `FRC_20260416_175209`; its own record timestamp is 28.1 s into the file | — |

The third check is the decisive one: the `systemTime` *payload* is epoch
microseconds and it agrees with the filename, while the *record header*
timestamp on the same record reads 28 138 059 — the same base, 28 s in.

---

## 2. What is actually in these logs **[executed]**

543 entries in P10. The drivetrain telemetry is CTRE's `SwerveDriveState`,
published by `Telemetry.telemeterize`
(`src/main/java/frc/robot/Telemetry.java:109-135`) **[source]**:

| Entry | Type | Rate | What it is |
| --- | --- | --- | --- |
| `NT:/DriveState/ModuleStates` | `SwerveModuleState[]` | **250 Hz** | per-module wheel speed (m/s) and azimuth |
| `NT:/DriveState/ModulePositions` | `SwerveModulePosition[]` | 250 Hz | per-module distance and azimuth |
| `NT:/DriveState/ModuleTargets` | `SwerveModuleState[]` | 250 Hz | the commanded state |
| `NT:/DriveState/Speeds` | `ChassisSpeeds` | 250 Hz | **kinematics of the above — not independent** |
| `NT:/DriveState/Pose` | `Pose2d` | 250 Hz | pose estimator, wheels + Pigeon + vision |
| `NT:/SmartDashboard/Power Distribution/Chan0..23` | `double` | **~3 Hz** | per-channel PDH current |
| `NT:/SmartDashboard/Power Distribution/{Voltage,TotalCurrent}` | `double` | ~10 Hz / ~3 Hz | rail and total |
| `NT:/Vision/UpperPortCameraPoseEstimation` | `Pose2d` | ~8 Hz, intermittent | the only non-wheel position signal |
| `DS:enabled`, `DS:autonomous` | `boolean` | on change | phase |

Two absences shape everything that follows:

- **No drive-motor stator current, applied voltage or torque current.** The
  shooter, intake, spindexer and kicker each log stator/supply/voltage, but the
  drivetrain logs none — `Telemetry` writes only the `SwerveDriveState`, and the
  Phoenix `SignalLogger` output goes to `./logs/canivore.hoot`
  (`TunerConstants.java:73`) **[source]**, which was not supplied.
- **No independent ground-speed reference.** `Speeds` is the least-squares
  kinematics of the four module states, so it is the wheels again. The Pigeon is
  logged only through the fused pose's rotation. **Vision is the only
  non-wheel signal, and it is far too noisy** (§6).

The angle inside `SwerveModuleState` is the **unwrapped** azimuth in radians —
values of 20.96, 31.4, −36.0 rad appear — so it differentiates cleanly without
unwrapping, which is what made the azimuth-rate filter below possible.
**[measured]**

### Robot2026's own declared constants **[source]**

| Constant | Value | Where |
| --- | --- | --- |
| `kDriveGearRatio` | 6.746031746031747 | `generated/TunerConstants.java:84` |
| `kWheelRadius` | 2 in = 0.0508 m | `generated/TunerConstants.java:86` |
| `kSpeedAt12Volts` | 4.58 m/s | `generated/TunerConstants.java:78` |
| `kSlipCurrent` | 120 A stator | `generated/TunerConstants.java:54` |
| module offsets | ±10.625 in | `generated/TunerConstants.java:137-138` |
| `kRobotMassKg` | 54.4 (*"~120 lbs with bumpers and battery"* — declared, not weighed) | `Constants.java:67` |
| `kRobotMOIKgM2` | 6.0 (*"estimate, tune via SysId"*) | `Constants.java:68` |
| drive request | **`DriveRequestType.OpenLoopVoltage`** | `RobotContainer.java:54-55` |

**That last row is why this exercise transfers at all.** Robot2026 drove teleop
**open-loop on voltage**: the module is handed `12 V × (commanded speed /
kSpeedAt12Volts)`, supply-compensated by the TalonFX. That is structurally the
same scheme as our `SwerveModule.openLoopVolts`, which maps a stick position to a
fraction of 12 V *through* `MAX_VELOCITY`
(`src/main/java/first/robot/mechanisms/SwerveModule.java:322-326`). The two robots'
stick-to-volts curves are calibrated against their respective free-speed
constants in the same way, so a deficit in one is the kind of thing that could
appear in the other. **[unverified]** that it does.

### Segmentation **[measured]**

From `DS:enabled` and `DS:autonomous`:

| Log | auto | teleop |
| --- | --- | --- |
| P10 | 338.5 → 359.2 (20.7 s) | 363.3 → 504.6 (141.4 s) |
| P15 | 186.5 → 195.2 (8.7 s) | 211.3 → 351.3 (139.9 s) |
| P20 | 198.9 → 219.5 (20.7 s) | 223.6 → 363.6 (140.0 s) |

Everything below is measured **inside those windows only**. Disabled time —
roughly 70 % of each file — is excluded.

### How much of that driving is usable **[measured]**

Per teleop segment, the fraction of 250 Hz samples passing each filter:

| Filter | P10 | P15 | P20 |
| --- | --- | --- | --- |
| moving (\|v\| > 1 m/s) | 43.0 % | 48.4 % | 55.8 % |
| four wheels in family (residual < 0.5 m/s) | 74.1 % | 69.2 % | 62.5 % |
| azimuths steady (< 1.5 rad/s over 40 ms) | 50.2 % | 40.5 % | 45.1 % |
| not spinning (\|ω\| < 1 rad/s) | 76.6 % | 75.1 % | 70.9 % |
| **all four at once** | **5.9 % (8 s)** | **7.4 % (10 s)** | **8.4 % (11 s)** |

**Roughly ten seconds per match.** The median azimuth rate in teleop is
1.5–2.4 rad/s: the driver is turning the modules essentially all the time. This
is the single most important practical finding about match logs as a data
source, and it is why several of the priorities below return null.

---

## 3. Priority 1 — the drag ratio

### Method

Reported module speed is motor RPS ÷ gear ratio × 2π r with the **nominal** r, so
it is a pure function of motor RPM. The nameplate free speed is computed with
**the same** r and gear ratio, so **a wheel-radius error cancels between the two**
and the ratio is a clean measure of drag *in the motor-speed domain* — which is
exactly the domain ADR 0010's missing-drag note is about.

I looked for every episode where **all four** wheel speeds exceeded 4.30 m/s,
and reported the rigid-body residual and azimuth rate beside each so a scrubbing
or colliding sample can be discarded.

### Result **[measured]**

In three whole playoff matches there are **three such episodes, totalling
0.34 s**:

| Log | t | dur | min wheel | max wheel | residual | azimuth rate | d\|v\|/dt | rail |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P20 | 276.550 | 0.088 s | 4.305 | **4.692** | 0.265 | 1.19 rad/s | **+2.23** | 11.39 V |
| P20 | 277.086 | 0.064 s | 4.315 | 4.565 | 0.048 | 1.61 rad/s | **+2.49** | 11.29 V |
| P10 | 458.164 | 0.184 s | 4.318 | 4.560 | 0.107 | 4.37 rad/s | **+0.26** | 10.99 V |

Read raw, the P20 276.55 episode is textbook: all four modules climbing
4.48 → 4.69 m/s in lockstep over 80 ms, azimuths flat to 0.01 rad, residual
0.03 m/s. The robot then crossed something — the four wheels dip in
front-pair-then-rear-pair sequence 130 ms apart, which at 4.6 m/s is 0.6 m, the
wheelbase — and the run ended.

**Every one of the three was still accelerating.** So 4.692 m/s is a **lower
bound** on the asymptote, not the asymptote.

### The ratio

Free speeds computed at G = 6.746, r = 0.0508 m, with the motor constants read
out of the local `allwpilib` checkout
(`wpimath/src/main/java/org/wpilib/math/system/DCMotor.java:258-309`)
**[source]**:

| Denominator | Value | measured / nameplate |
| --- | --- | --- |
| **declared `kSpeedAt12Volts`** | 4.580 m/s | **1.024** |
| Kraken X60 FOC, 5785 rpm | 4.562 m/s | 1.029 |
| **Kraken X60, 6065 rpm** | 4.783 m/s | **0.981** |
| Falcon 500 FOC, 6080 rpm | 4.795 m/s | 0.979 |
| Falcon 500, 6380 rpm | 5.031 m/s | 0.933 |

Which TalonFX is fitted is stated nowhere in the tree or the log, so the motor is
`[unverified]`. It is very likely a Kraken X60: 4.692 m/s is **5950 motor rpm**,
and the declared 4.58 m/s is what CTRE's published 5800 rpm FOC free speed gives
at this exact reduction (4.574 m/s) — Tuner X's own default. The two Kraken
variants bracket the answer at **0.98 – 1.03**.

> **The drag ratio is 1.00 ± 0.03, and it is a lower bound. [measured]**

### What this costs ADR 0010

ADR 0010 says the free-space sim *"reaches `MAX_VELOCITY` exactly … a real one
tops out lower once rolling resistance and drivetrain losses are in"*, and that
the sim is *"optimistic about top speed by a knowable ratio"*
(`docs/adr/0010-simulation-architecture.md:657-684`) **[source]**.

For **this** robot that premise is not supported. At 12 V open loop the drive
reached its nameplate free speed to within the spread between two plausible motor
nameplates. Whatever drag exists costs **less than the uncertainty in the
nameplate itself**. **[measured]**

**[unverified] for us.** Three reasons it may not transfer:

- Robot2026's nameplate is 4.6–4.7 m/s; our `MAX_VELOCITY` is **5.99 m/s**
  (`DriveConstants.java:147`). Rolling and aerodynamic losses grow with speed, so
  a ratio measured at 4.7 m/s does not bound one at 6.0 m/s.
- Different modules (MK4i-class vs MAXSwerve), different wheels, possibly
  different carpet.
- The ratio is measured in the motor-speed domain. A real deficit in **ground**
  speed from a wrong effective wheel radius would be invisible to it, and §6
  shows these logs cannot measure that.

**What does transfer** is the shape of the answer: *if* our chassis behaves like
this one, then `MAX_VELOCITY` as a nameplate free speed is not the 10–20 %
optimistic figure the ADR assumes, and a drag term added to the sim to close a
top-speed gap would be closing a gap that is not there. That is a reason to
**measure ours before modelling it**, which is what #151 asks.

---

## 4. Priority 2 — did the wheels slip, and was it on the unloading axle

> Rewritten after [`wheel-cof.md`](wheel-cof.md) landed. That file measures
> **Vex Grip V2 at μ = 1.916 [field]** — the tread Robot2026 ran — and predicts
> that weight transfer makes slip reachable on the **unloading axle** above a CG
> height of 18.6 cm, which a robot carrying an intake, kicker, shooter and
> spindexer almost certainly exceeds. That is a specific, falsifiable prediction
> about these logs, and it is what §4.3 tests.

### 4.0 What Robot2026's own constants predict, with the measured μ

| Quantity | Value | From |
| --- | --- | --- |
| commanded force per wheel at `kSlipCurrent` = 120 A | **304.6 N** | `I · Kt · G / r`, Kt = 7.157/374.4 |
| commanded chassis acceleration | **22.40 m/s² (2.28 g)** | `4 · I · Kt · G / (m · r)` |
| **static** grip per wheel at μ = 1.916 | **255.6 N** | `μ · mg/4`, m = 54.4 kg |
| **static margin** | **0.84×** | |
| whole-robot traction limit | 18.80 m/s² (1.92 g) | `μg` |

> **The static margin is below 1.** At its own current limit this robot exceeds
> Grip V2 grip **on every wheel at zero CG height**, before any weight transfer
> at all. **[measured]**

Adding transfer over the 0.5397 m module spacing with equal torque to four
wheels, the no-slip-anywhere acceleration is `a = μg / (1 + 2μh/L)`:

| CG height h | no-slip limit | commanded / limit |
| --- | --- | --- |
| 0.10 m | 10.99 m/s² | 2.04× |
| 0.15 m | 9.10 | 2.46× |
| **0.186 m** (wheel-cof.md's Grip V2 threshold) | 8.10 | 2.77× |
| 0.20 m | 7.77 | 2.88× |
| 0.25 m | 6.77 | 3.31× |
| 0.30 m | 6.01 | 3.73× |

**Robot2026 is not near a slip threshold. It is two to four times past one, at
every plausible CG height.** `wheel-cof.md` was right that slip is in these
logs, and understated by how much. **[measured]**

### The two detectors, and what each is blind to

**(a) Differential slip** — a least-squares rigid-body fit of (vₓ, v_y, ω) to the
four module velocity vectors, then the per-module residual. Eight equations,
three unknowns, five degrees of freedom of residual. Valid **only while the
azimuths are steady**, because CTRE reports the **rolling component only**: a
module that is steering fast reports a wheel speed that is no longer the chassis
velocity projected on it, and the fit sees that as a huge residual. (This bit me:
before the azimuth-rate filter the logs appeared to show routine 4 g chassis
accelerations. They were steering transients.)

**(b) Common-mode slip** — all four wheels wrong together. **The kinematic
residual is blind to this by construction**, because the fit is made *from* those
four wheels. The only handle left is the physical bound: a chassis cannot change
speed faster than μg, which for Grip V2 on carpet is **18.80 m/s², 1.92 g**
(§4.0) — a bound the measurements below clear by a factor of two to five.

### 4.1 Result (a): differential residual **[measured]**

Measured only while azimuths are steady (< 0.5 rad/s over 40 ms) and |v| > 1 m/s
— 16 s of the 216 s spent above 1 m/s, pooled over three matches:

| p50 | p90 | p99 | max |
| --- | --- | --- | --- |
| 0.276 | 1.141 | 2.712 | 3.051 m/s |

Above 0.5 m/s for 34 % of that time, above 1.5 m/s for 5 %. **33 distinct
episodes** of ≥ 20 ms with a residual over 0.8 m/s.

This number **mixes slip with scrub, tyre compliance, azimuth quantisation and
contact**, and the log carries nothing that separates them. I will not call it a
slip rate. What it establishes is that **the four wheels of a real match robot
are out of kinematic family by more than 0.5 m/s about a third of the time it is
driving straight** — which is a useful calibration for anyone planning to detect
slip from a residual threshold.

### 4.2 Result (b): the physical bound — slip is confirmed **[measured]**

With 12 V applied to a stalled Kraken the drive would draw 374 A; the 120 A
stator limit caps it; four wheels at 120 A give 22.40 m/s² against a μg of
18.80 m/s² (§4.0). A full-throttle launch is commanded above the traction limit
by construction. And it shows:

**Three true standing starts** in three matches (all four wheels under 0.30 m/s
for 0.30 s, then a saturated command):

| Log | t | peak wheel accel over 60 ms |
| --- | --- | --- |
| P10 | 363.484 | **51.40 m/s² (5.2 g)** |
| P20 | 257.358 | **46.33 m/s² (4.7 g)** |
| P10 (auto) | 347.289 | **22.75 m/s² (2.3 g)** |

**45 distinct full-speed wheel reversals** (above 2.5 m/s in both directions);
peak rate of change of signed mean wheel speed through the reversal:

| p50 | p90 | max |
| --- | --- | --- |
| **37.2 m/s² (3.8 g)** | 55.5 | 90.0 |

**Rotation slips too.** Peak |dω/dt| reaches 68.9 rad/s². A traction-bounded
pivot at μ = 1.0 gives `4 · μmg/4 · R / J` = 33.9 rad/s² with the declared MOI;
the current-limited figure is 77.5 rad/s². The measurement sits at twice the
traction bound and just under the current bound — the same signature.

**Contact events are visible and unmistakable, and were segmented out.** At P20
t ≈ 330.09 and P10 t ≈ 363.84, all four wheels lose 2–3 m/s in 40–60 ms with
azimuths static and recover over the next 100 ms. No carpet chassis does ±5 g.
Those are collisions in which the wheels skidded and re-gripped. They are
excluded from §4.3 by the residual and azimuth-rate filters; the two named above
are the largest, and a further 33 episodes of ≥ 20 ms carry a residual over
0.8 m/s with the azimuths steady.

### 4.3 The unloading-axle test — the specific prediction **[measured]**

**Method.** Quasi-static transfer moves load off the axle that lies *in the
direction of the acceleration* (accelerate forward, the nose lifts, the front
unloads; brake, the nose dives, the rear unloads). So for each 250 Hz sample:

- fit the rigid body to all four module velocity vectors;
- `e_i` = the **signed** departure of module *i*'s own wheel speed from that fit,
  measured along module *i*'s own wheel direction (+ = running ahead);
- `ξ_i = r_i · â`, the module's station along the acceleration axis. **ξ > 0 is
  the unloading side.** This handles diagonal manoeuvres too, where the
  unloading region is a corner rather than an axle;
- `k` = the least-squares slope of `e_i` against `ξ_i` across the four modules;
- **`S = k · sign(a_long)`**. Under launch a slipping unloading wheel runs ahead
  (k > 0, a > 0); under braking a slipping unloading wheel is over-braked and
  runs behind (k < 0, a < 0). **Either way `S > 0` is the signature.**

**The detector was validated against injected slip before being believed**
(scratch script `c3_validate.py`, reproduced in method below):

| Injected | k | S | |
| --- | --- | --- | --- |
| launch, unloading pair 0.40 m/s ahead | +0.741 | **+0.741** | detected |
| launch, loading pair 0.40 m/s ahead | −0.741 | −0.741 | correctly rejected |
| brake, unloading pair 0.40 m/s behind | −0.741 | **+0.741** | detected |
| brake, loading pair 0.40 m/s behind | +0.741 | −0.741 | correctly rejected |
| no slip | 0.000 | 0.000 | null |

Noise floor: per-wheel speed noise of 0.10 m/s gives k of sd 0.184. **A 0.40 m/s
unloading-pair slip sits four times above that. The detector has ample power.**

**Result.** Filters: near-pure longitudinal (|a_lat| < 0.4·|a_long|), acceleration
direction stable to < 0.10 over 60 ms, azimuth rate < 0.6 rad/s, |ω| < 0.5 rad/s,
|v| > 1.0 m/s. **Episode level** — one observation per manoeuvre, peaks ≥ 0.5 s
apart — because consecutive 4 ms samples are not independent:

| \|a_long\| band | episodes | S > 0 | frac | exact binomial p | S median |
| --- | --- | --- | --- | --- | --- |
| 2–5 m/s² | 55 | 33 | 60.0 % | 0.177 | +0.022 |
| 5–8 | 35 | 19 | 54.3 % | 0.736 | +0.018 |
| **8–12** | 26 | 13 | **50.0 %** | 1.000 | −0.002 |
| 12–18 | 19 | 13 | 68.4 % | 0.167 | +0.020 |
| 18–40 | 8 | 6 | 75.0 % | 0.289 | +0.382 |
| **8–18** (where transfer should decide it) | **35** | **20** | **57.1 %** | **0.500** | **+0.003** |
| 2–40 (all) | 81 | 47 | 58.0 % | 0.182 | +0.016 |

Split by direction over 8–18 m/s²: launches 12/22 (p = 0.83), braking 8/13
(p = 0.58).

**The per-wheel departures are large but are not organised along the unloading
axis.** In those same 8–18 m/s² episodes the worst-module |e| has median
**0.319 m/s**, p90 0.883, max 1.344 m/s — well above the noise floor, and of the
order the injected test used. Yet median S is **+0.003**, against **+0.741** for
a clean unloading-pair slip.

> **Verdict: the unloading-axle signature is absent.** A consistent but
> **non-significant** tendency in the predicted direction — 58 % of 81 episodes,
> p = 0.18 — and nothing at all in the band where the transfer model says it
> should be sharpest. **[measured]**

**Why the null does not put the transfer model in doubt.** The model predicts an
*axle-confined* slip only in the window where the per-wheel demand lies **between**
the unloaded-wheel grip and the loaded-wheel grip. For Robot2026 that window
barely exists, because its static margin is already 0.84 (§4.0):

| a (m/s²) | demand m·a/4 | unloaded-wheel grip, h = 0.15 / 0.20 / 0.25 m |
| --- | --- | --- |
| 6 | 81.6 N | 168.7 / 139.8 / 110.8 N |
| 8 | 108.8 | 139.8 / **101.1** / 62.5 |
| 10 | 136.0 | 110.8 / 62.5 / 14.2 |
| 12 | 163.2 | 81.8 / 23.9 / −34.0 |
| **22.4** (its current limit) | **304.6** | **−68.8 / −176.9 / −285.1** |

At its commanded limit the unloaded wheel has **negative** residual grip, and the
loaded wheel's 255.6 N static share is itself under the 304.6 N demand. **All
four break together**, which is exactly the mode a four-module kinematic residual
cannot see (§4, detector (b)). The hypothesis was right about *whether* this
robot slips and wrong about *where the signature would be*, because this robot is
further past its limit than the worked example that generated the prediction.

**One observation I cannot explain and will not invent an explanation for.**
Reading the strongest episodes out per module, the residual pattern is frequently
**diagonal** — opposite corners agreeing, e.g. `e = (+0.77, −0.94, −0.90, +0.81)`
for modules FL, FR, BL, BR at P15 t = 280.255 — rather than fore/aft. Chassis
twist, a per-module calibration difference, lateral scrub that a zero-lateral-slip
fit cannot represent, and an uneven floor all produce something like this, and
**none of them is separable with the signals this robot logs.** **[measured,
unexplained]**

### Verdict for #150

> **Yes, routinely — on all four wheels at once, not on the unloading axle, and
> only the wheels are visible, not the ground. [measured]**

- At **full-throttle launches and reversals**, Robot2026's wheels depart from its
  ground speed every time, by margins of 2–9 g of wheel acceleration (§4.2).
- **The mechanism is not weight transfer for this robot; it is gross overload.**
  Its commanded 304.6 N per wheel against 255.6 N of Grip V2 static grip is a
  margin of 0.84 **before** transfer. Transfer makes it worse but is not what
  causes it. **[measured]**
- **Our margin is on the other side of 1, and that changes what our friction
  model has to do.** From `wheel-cof.md`: 121.4 N per wheel at our 60 A limit
  against **313.5 N** of MK5N static grip, a margin of **2.58×**, so steady-state
  slip is unreachable and transfer is the only route to it. **That is exactly
  the regime in which the axle-confined signature exists** — and this robot's
  failure to show it is a consequence of being *past* that regime, not evidence
  against it. **[unverified]** — arithmetic across two robots.
- **Do not scale Robot2026's numbers to ours by the 117.69 % CoF ratio.** The
  MK5N is 2.25 in wide against the Grip V2's 1.5 in, two-thirds the contact
  pressure at equal load, and rubber μ falls as pressure rises, so an unknown
  share of that ratio is width rather than compound
  (`docs/research/wheel-cof.md`, caveat 3) **[source]**. Nothing in this document
  is scaled that way.
- **What is contradicted** is any plan to *detect* slip from a four-module
  kinematic residual alone. That residual cannot see common-mode slip, and
  common-mode is what happens under throttle on a robot past its limit. It also
  failed to isolate the axle-confined mode here. A slip detector needs a signal
  off the wheels: an IMU accelerometer, or vision good enough to differentiate.
  **[measured]**
- **What #150 should instrument** is per-module wheel speed against a non-wheel
  ground-speed reference, logged at odometry rate, so the axle-confined mode our
  margin actually permits can be seen when our chassis exists.

---

## 5. Priority 3 — the 8.36 m/s² figure

### Part one: provenance. It is a sim log, and the tag is wrong. **[source, executed]**

The figure entered at `6617571` (#96, 2026-08-30). Three things settle it:

1. **The commit message names its own source.** `6617571`'s body opens *"Three
   things a **desktop drive session** found"*, and the paragraph that introduces
   the number reads:

   > *"…the rail latches at the 7.2 V floor during exactly the manoeuvre the
   > volts were braking. **The last driving log has the pack's 1st percentile at
   > 7.31 V with a median of 12.** … What is left bounding a hard stop is the
   > drive current limit, which is arithmetic: 4 \* I \* Kt \* G / (m \* r) is
   > 8.6 m/s^2 at 60 A. **The log** measured a full-speed wheel reversal at
   > 8.36 m/s^2 three times running, to within a percent of each other."*

   *"The log"* in the second sentence is *"the last driving log"* of the first.
   A pack whose median is exactly 12 V and whose floor is exactly 7.2 V is
   `SwerveDriveSim`'s battery model, not a battery.

2. **[#109](https://github.com/Drew-Robotics/2027beta/issues/109) records that
   there was no other candidate.** *"Every local log is a sim log"*, and *"the
   robot has written exactly one log … ten `/Telemetry/Metadata/*` signals and
   nothing else, because the Driver Station never connected."*

3. **The assertion is in `SwerveDriveSimTest`.**
   `aHardReversalBrakesAtTheCurrentLimitWithoutSaggingThePack`
   (`src/test/java/first/robot/sim/SwerveDriveSimTest.java:247`) computes
   `4·I·Kt·G/(m·r)` and asserts `assertEquals(limit, decel, limit * 0.1)`.

> **Verdict: 8.36 m/s² is the simulation reproducing its own current-limit
> arithmetic.** Predicted 8.6, observed 8.36, agreeing to 3 % — which a model
> with no slip term and no drag term is obliged to do. It is `[executed]`, not
> `[measured]`, and it is not evidence about real traction. ADR 0010 line 273 and
> line 675, and #103, carry the wrong tag.

This does **not** invalidate the conclusion it supports, but
[`wheel-cof.md`](wheel-cof.md) has already changed that conclusion under it.
*"A traction limit is not the lever"* rested on 121 N of wheel force against
125–167 N of grip at a guessed μ of 0.9–1.2; the measured MK5N figure makes it
**313.5 N, a margin of 2.58×**, so the steady-state half is *strengthened*. What
that file adds — and what this one confirms is real on a robot — is that the
transient half is different: transfer unloads an axle and slip becomes reachable
there with no raised current limit. 8.36 loses its corroboration either way.

### Part two: the real-robot number. It cannot be taken. **[measured]**

This is the part that matters, and it is a null result with teeth.

Robot2026's own prediction, from its own constants:

| I | `4·I·Kt·G/(m·r)` |
| --- | --- |
| 40 A | 7.47 m/s² (0.76 g) |
| 60 A | 11.20 m/s² (1.14 g) |
| 80 A | 14.93 m/s² (1.52 g) |
| **120 A = its `kSlipCurrent`** | **22.40 m/s² (2.28 g)** |

Measured, on the *exact manoeuvre the claim names* — a full-speed wheel reversal,
45 of them:

| | measured | predicted | **ratio** |
| --- | --- | --- | --- |
| median peak rate | 37.2 m/s² | 22.40 | **1.66** |
| p90 | 55.5 | 22.40 | 2.48 |
| max | 90.0 | 22.40 | 4.02 |

**A ratio above 1 is the finding.** `4·I·Kt·G/(m·r)` is a bound on a *chassis*.
The measurement exceeds it by 1.6–4×, which proves the thing being measured is
not the chassis. It is the wheel, spinning up and reversing against a contact
patch it has already broken.

> **A "full-speed wheel reversal" measured off wheel telemetry is not a chassis
> deceleration, on any robot that commands more torque than it has grip.**
> In simulation the two coincide, because the sim has no slip. On a real robot
> they differ by a factor of 1.6 to 4. **[measured]**

This is the strongest transferable result in the whole exercise and it is
methodological, not numeric: **the 8.36 m/s² claim could not have been validated
by a real log even if a real log had existed**, because the manoeuvre measures
the wrong thing. To get a real braking acceleration you need a signal that is not
the wheels.

The best honest chassis figure these logs support: over the three standing
starts, wheel-derived chassis speed reaches a median 2.11 m/s at 0.20 s
(10.5 m/s²) but only 1.19 m/s at 0.40 s — it goes up and comes back down, because
the wheels were spinning and then re-gripped. **Even the 0.20 s figure is
wheel-derived and therefore inflated.** Ratio to the 22.40 m/s² prediction:
0.47, and that is an upper bound on the truth, not an estimate of it.

---

## 6. Priority 4 — effective wheel radius: **null** **[measured]**

`NT:/Vision/UpperPortCameraPoseEstimation` is the only non-wheel position signal.
Measured against the fused `DriveState/Pose` at the same instants:

| Log | fixes while enabled | median disagreement | p90 | p99 | max |
| --- | --- | --- | --- | --- | --- |
| P10 | 618 | 0.225 m | 1.682 | 5.599 | 7.248 |
| P15 | 610 | 0.206 m | 1.060 | 3.023 | 15.649 |
| P20 | 267 | 0.620 m | 2.936 | 5.476 | 7.252 |

These are single-camera PhotonVision estimates of hub tags at range. **A metre of
noise cannot resolve a few per cent of wheel radius.**

Worse, the geometry never arrives. Searching for windows of ≥ 8 fixes spanning
≥ 1.5 s where the robot travelled > 2 m **and** the path was straight enough for
displacement to equal path length (chord ≥ 0.93 × path):

| Log | candidate windows | with > 2 m travel | straight enough | **both** |
| --- | --- | --- | --- | --- |
| P10 | 20 | 7 | 6 | **0** |
| P15 | 19 | 8 | 1 | **0** |
| P20 | 6 | 2 | 3 | **0** |

**Zero, in three matches.** The robot only sees hub tags while it is aiming at
the hub, and while it is aiming at the hub it is turning. ADR 0009's effective
wheel radius still wants a chassis and a tape measure.

---

## 7. Priority 5 — rotational behaviour: **null for MOI** **[measured]**

Yaw rates reached (from the rigid-body fit, residual < 0.35 m/s):

| Log | phase | \|ω\| p50 | p99 | max | \|dω/dt\| p99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| P10 | auto | 2.84 | 11.08 | 11.14 rad/s | 22.74 | 37.26 rad/s² |
| P10 | teleop | 0.23 | 4.29 | 8.06 | 24.19 | 48.82 |
| P15 | teleop | 0.33 | 4.02 | 5.34 | 21.87 | 68.86 |
| P20 | teleop | 0.33 | 4.49 | 7.30 | 27.04 | 53.09 |

**No MOI can be extracted.** The torque side of `J = τ/α` needs per-module drive
current, and the drivetrain logs none at all (§2); the PDH channels that carry it
sample at ~3 Hz, two orders of magnitude too slow for a 100 ms pivot transient.

The slab estimate cannot even be checked against its own author's number:
`kRobotMOIKgM2 = 6.0` sits **between** `m(w²+l²)/12` at the frame perimeter
(26.94 in → 4.25 kg·m²) and at the bumper perimeter (33.876 in → 6.71 kg·m²), and
which of those they meant is not recorded. Ratios of 1.41 and 0.89. Useless.

The one thing the yaw data does show is §4's rotational slip signature: peak
|dω/dt| of 68.9 rad/s² against a 33.9 rad/s² traction bound and a 77.5 rad/s²
current bound.

---

## 8. Priority 6 — the battery under load **[measured]**

Regressing PDH bus voltage on PDH total current over the enabled windows:

| Log | open-circuit intercept | pack + wiring R | resid sd | V p1 | V p5 | V p50 | I p50 | I p99 | I max |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P10 | 11.62 V | 14.4 mΩ | 0.97 V | 7.55 | 8.04 | 10.22 | 90 A | 230 | 256 |
| P15 | 10.83 V | 11.9 mΩ | 1.20 V | 6.86 | 7.24 | 9.44 | 113 A | 250 | 286 |
| P20 | 12.01 V | 15.0 mΩ | 1.03 V | 7.80 | 8.37 | 10.48 | 94 A | 245 | 298 |

**Does the pack sag across the match?** Yes, and at unchanged load:

| Log | median V, first half | second half | median I, first / second |
| --- | --- | --- | --- |
| P10 | 10.48 V | 9.95 V | 90 / 92 A |
| P15 | 9.83 V | 9.09 V | 114 / 110 A |
| P20 | 11.02 V | 10.13 V | 86 / 96 A |

**0.5–0.9 V of drift** across ~150 s of enabled time. P15 started from a visibly
worse pack (10.83 V open-circuit) and spent its whole match around 9 V.

**Did the top-speed ratio drift with pack state?** **Cannot be answered.**
All three top-speed episodes (§3) happen to sit at 11.0–11.4 V, and there are
only three of them. A first-order fit of the drivetrain dynamics across the
whole rail range (α·U − β·v − γ) gave R² = 0.31 on 6 s of qualifying data and
extrapolated wildly; I am not reporting its numbers. **[measured — null]**

**Which channels are the drive motors** — identified by correlating each PDH
channel against wheel speed, consistent across P10 and P20 **[measured]**:

| Channels | mean | max | corr with \|v\| |
| --- | --- | --- | --- |
| **0, 8, 10, 18 — drive** | 11–17 A | 65–82 A | +0.40 … +0.50 |
| **1, 9, 11, 19 — steer** | 2.5–3.2 A | 37–45 A | +0.15 … +0.20 |

The drive draws only ~45–70 A of a 90–113 A median total; the shooter and intake
dominate this robot's current budget. **At ~3 Hz those maxima are lower bounds**
and the real launch transient certainly reached the 120 A limit.

### For #149

The sim's 7.2 V floor was written up as a defect. **A real pack at a real event
spent one per cent of its enabled time below 7.8 V, and P15 below 6.9 V.** A
7.2 V floor is not an absurd number for a pack under a 250–300 A draw.

**[unverified] for us**, emphatically: different pack, different age, and a
completely different load profile. Robot2026's 250 A peaks are mostly shooter and
intake. A drive-only MAXSwerve robot at a 60 A-per-module limit has a different
current envelope entirely. What transfers is the *shape*: 12–15 mΩ of pack plus
wiring, and a 0.5–0.9 V drift across a match at constant load.

---

## 9. What does not transfer

Stated plainly, because the ticket asks for it:

- **Absolute top speed.** 4.69 m/s is Robot2026's, at 6.746:1 on a 2 in wheel.
- **Absolute μ.** Never measured here. The 1.916 used throughout is
  `wheel-cof.md`'s ramp figure for Grip V2 **[field]**, applied to Robot2026
  because that is the tread it ran. Nothing in these logs measures friction.
- **The CoF ratio, as a scaling factor.** The tempting move is to scale
  Robot2026's results to ours by 117.69 %. **This document does not**, and
  neither should anything citing it: the MK5N is 2.25 in wide against the
  Grip V2's 1.5 in — two-thirds the contact pressure at equal load — and rubber
  μ falls as pressure rises, so an unknown share of that ratio is width rather
  than compound (`docs/research/wheel-cof.md`, caveat 3) **[source]**.
- **Absolute MOI.** Not measurable (§7).
- **Absolute accelerations.** Every acceleration in this document is a *wheel*
  acceleration. The chassis figures are wheel-derived and inflated by exactly the
  slip the document demonstrates.
- **Current limits.** 120 A stator on a Kraken is not 60 A on a Vortex.
- **The battery envelope.** Different load profile entirely (§8).

What comes back is dimensionless:

| Quantity | Value | Tag |
| --- | --- | --- |
| drag ratio, measured top speed / nameplate free speed | **1.00 ± 0.03, lower bound** | [measured] for Robot2026; [unverified] for us |
| commanded force / static grip, at that robot's limit | 304.6 N / 255.6 N = **1.19** | [measured] |
| commanded accel / its no-slip-anywhere limit with transfer | **2.0 – 3.7** (CG 0.10 – 0.30 m) | [measured] |
| unloading-axle slip signature, episode sign test | **47 / 81, p = 0.18 — absent** | [measured] |
| measured wheel-reversal rate / `4·I·Kt·G/(m·r)` | **1.66 median, up to 4.02** | [measured] |
| usable clean straight driving per match | **~10 s of ~140 s** | [measured] |
| pack + wiring resistance | 11.9 – 15.0 mΩ | [measured] for that pack |
| pack drift across a match at constant load | 0.5 – 0.9 V | [measured] for that pack |

---

## 10. The parser

Hand-rolled, streaming, `mmap`-backed. **Record timestamps are read as
microseconds** — see §1. Drop both files in a scratch directory and run.

```python
# wpilog.py -- minimal streaming WPILOG reader.
#
# Timestamps in the FILE are MICROSECONDS.  The Java DataLog API divides
# nanoseconds on write and multiplies them back on read, so only a hand-rolled
# parser like this one sees the microseconds on disk.
import struct
import mmap

TS_PER_SEC = 1_000_000.0  # file base: microseconds


def _u(b):
    return int.from_bytes(b, 'little', signed=False)


class Reader:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.mm = mmap.mmap(self.f.fileno(), 0, access=mmap.ACCESS_READ)
        if self.mm[0:6] != b'WPILOG':
            raise ValueError('not a wpilog')
        self.version = struct.unpack_from('<H', self.mm, 6)[0]
        xlen = struct.unpack_from('<I', self.mm, 8)[0]
        self.extra = self.mm[12:12 + xlen].decode('utf-8', 'replace')
        self.pos = 12 + xlen
        self.entries = {}   # id -> (name, type, metadata)

    def records(self):
        """Yield (entry_id, timestamp_us, payload).  Control records (id 0) are
        applied to self.entries as they stream past, then yielded too."""
        mm, n = self.mm, len(self.mm)
        p = self.pos
        while p < n:
            hb = mm[p]
            p += 1
            el = (hb & 0x3) + 1
            sl = ((hb >> 2) & 0x3) + 1
            tl = ((hb >> 4) & 0x7) + 1
            eid = _u(mm[p:p + el]); p += el
            size = _u(mm[p:p + sl]); p += sl
            ts = _u(mm[p:p + tl]); p += tl
            payload = mm[p:p + size]; p += size
            if eid == 0:
                self._control(payload)
            yield eid, ts, payload

    def _control(self, pl):
        if not pl or pl[0] != 0:      # 0 = Start
            return
        o = 1
        eid = struct.unpack_from('<I', pl, o)[0]; o += 4

        def s(o):
            ln = struct.unpack_from('<I', pl, o)[0]; o += 4
            return pl[o:o + ln].decode('utf-8', 'replace'), o + ln
        name, o = s(o)
        typ, o = s(o)
        meta, o = s(o)
        self.entries[eid] = (name, typ, meta)


DEC = {
    'double':   lambda pl: struct.unpack('<d', pl)[0],
    'float':    lambda pl: struct.unpack('<f', pl)[0],
    'int64':    lambda pl: struct.unpack('<q', pl)[0],
    'boolean':  lambda pl: pl[0] != 0,
    'double[]': lambda pl: list(struct.unpack('<%dd' % (len(pl) // 8), pl)),
    'float[]':  lambda pl: list(struct.unpack('<%df' % (len(pl) // 4), pl)),
    'int64[]':  lambda pl: list(struct.unpack('<%dq' % (len(pl) // 8), pl)),
    'string':   lambda pl: pl.decode('utf-8', 'replace'),
}


def decode(typ, pl):
    f = DEC.get(typ)
    return f(pl) if f else None
```

Struct payloads are flat little-endian doubles and decode positionally:
`SwerveModuleState` = (speed, angle_rad) per module, `SwerveModulePosition` =
(distance, angle_rad), `Pose2d` = (x, y, theta), `ChassisSpeeds` = (vx, vy,
omega). **`SwerveModuleState.angle` is not wrapped** — CTRE reports the
accumulated azimuth, which is what makes the azimuth-rate filter possible.

One extraction pass per log, 45 MB streamed, only the needed series retained:

```python
# extract.py <log.wpilog> <out.npz>   -- one streaming pass, aggregates only.
import sys, struct
import numpy as np
import wpilog

WANT = {
    'NT:/DriveState/ModuleStates': 'modstates',
    'NT:/DriveState/ModuleTargets': 'modtargets',
    'NT:/DriveState/ModulePositions': 'modpos',
    'NT:/DriveState/Speeds': 'speeds',
    'NT:/DriveState/Pose': 'pose',
    'NT:/SmartDashboard/Voltage': 'battv',
    'NT:/SmartDashboard/Power Distribution/Voltage': 'pdhv',
    'NT:/SmartDashboard/Power Distribution/TotalCurrent': 'pdhcur',
    'NT:/Vision/UpperPortCameraPoseEstimation': 'vpose_u',
    'NT:/Vision/BottomPortCameraPoseEstimation': 'vpose_b',
    'DS:enabled': 'enabled',
    'DS:autonomous': 'auto',
}
for c in range(24):
    WANT['NT:/SmartDashboard/Power Distribution/Chan%d' % c] = 'pdh%d' % c


def dec(kind, pl):
    if kind in ('modstates', 'modtargets', 'modpos'):
        return struct.unpack('<%dd' % (len(pl) // 8), pl)   # (v0,a0,v1,a1,...)
    if kind in ('speeds', 'pose', 'vpose_u', 'vpose_b'):
        return struct.unpack('<3d', pl)
    if kind in ('enabled', 'auto'):
        return (float(pl[0] != 0),)
    return (struct.unpack('<d', pl)[0],)


r = wpilog.Reader(sys.argv[1])
ids, acc = {}, {v: ([], []) for v in WANT.values()}
for eid, ts, pl in r.records():
    if eid == 0:
        for i, (nm, ty, _m) in r.entries.items():
            if nm in WANT and i not in ids:
                ids[i] = WANT[nm]
        continue
    k = ids.get(eid)
    if k is None:
        continue
    try:
        v = dec(k, pl)
    except Exception:
        continue
    acc[k][0].append(ts / wpilog.TS_PER_SEC)     # -> seconds
    acc[k][1].append(v)

out = {}
for k, (t, v) in acc.items():
    if t:
        out[k + '_t'] = np.array(t, float)
        try:
            out[k + '_v'] = np.array(v, float)
        except ValueError:
            pass
np.savez_compressed(sys.argv[2], **out)
```

The analysis on top of it, in the form every measurement above used:

```python
# common.py -- Robot2026's own declared constants, and the rigid-body fit.
import numpy as np

GEAR = 6.746031746031747   # TunerConstants.java:84
R_WHEEL = 0.0508           # TunerConstants.java:86  (2 in)
SPEED_AT_12V = 4.58        # TunerConstants.java:78
SLIP_CURRENT = 120.0       # TunerConstants.java:54
MASS = 54.4                # Constants.java:67
MOI_DECL = 6.0             # Constants.java:68
HALF = 0.269875            # TunerConstants.java:137-138 (10.625 in)
MODXY = np.array([[+HALF, +HALF], [+HALF, -HALF], [-HALF, +HALF], [-HALF, -HALF]])


def step(t_src, v_src, t_q):
    """Zero-order hold: NT entries are logged on change."""
    i = np.clip(np.searchsorted(t_src, t_q, side='right') - 1, 0, len(t_src) - 1)
    return v_src[i]


def mod_vectors(ms):
    """(N,8) of (speed, angle_rad) x4 -> vx, vy, speed, each (N,4)."""
    s, a = ms[:, 0::2], ms[:, 1::2]
    return s * np.cos(a), s * np.sin(a), s


def fit_chassis(vx, vy):
    """Least-squares rigid body: v_i = [Vx - w*y_i, Vy + w*x_i].
    Returns Vx, Vy, w and the per-module residual speed in m/s."""
    x, y = MODXY[:, 0], MODXY[:, 1]
    Vx, Vy = vx.mean(axis=1), vy.mean(axis=1)
    denom = np.sum(x * x + y * y)
    w = (np.sum(-y * (vx - Vx[:, None]), axis=1)
         + np.sum(x * (vy - Vy[:, None]), axis=1)) / denom
    px = Vx[:, None] - w[:, None] * y[None, :]
    py = Vy[:, None] + w[:, None] * x[None, :]
    return Vx, Vy, w, np.hypot(vx - px, vy - py)


def segments(d):
    """(t0, t1, 'auto'|'teleop') per enabled window, from DS:enabled/DS:autonomous."""
    et, ev = d['enabled_t'], d['enabled_v'][:, 0]
    at, av = d['auto_t'], d['auto_v'][:, 0]
    out = []
    for i in range(len(et)):
        if ev[i] < 0.5:
            continue
        t0 = et[i]
        t1 = et[i + 1] if i + 1 < len(et) else d['pose_t'][-1]
        kind = 'auto' if step(at, av, np.array([t0 + 0.05]))[0] > 0.5 else 'teleop'
        out.append((t0, t1, kind))
    return out
```

The unloading-axle detector of §4.3, with the self-test that validated it:

```python
# axle.py -- per-module slip against the weight-transfer prediction.
import numpy as np
from common import MODXY, fit_chassis


def detect(s, ang, ahat):
    """s, ang: (N,4) wheel speed and unwrapped azimuth.  ahat: (N,2) unit
    acceleration direction in the ROBOT frame.  Returns (k, e).

      e_i = signed departure of wheel i from the rigid-body fit, along its own
            wheel direction (+ = running ahead of the fit)
      xi_i = r_i . ahat, the module's station along the acceleration axis;
             xi > 0 is the UNLOADING side
      k    = least-squares slope of e against xi across the four modules

    S = k * sign(a_long) > 0 is the signature of slip on the unloading side,
    for launches and for braking alike."""
    vx, vy = s * np.cos(ang), s * np.sin(ang)
    Vx, Vy, w, _ = fit_chassis(vx, vy)
    x, y = MODXY[:, 0], MODXY[:, 1]
    px = Vx[:, None] - w[:, None] * y[None, :]
    py = Vy[:, None] + w[:, None] * x[None, :]
    e = s - (px * np.cos(ang) + py * np.sin(ang))
    xi = ahat[:, 0:1] * MODXY[None, :, 0] + ahat[:, 1:2] * MODXY[None, :, 1]
    xim = xi - xi.mean(axis=1, keepdims=True)
    em = e - e.mean(axis=1, keepdims=True)
    return (xim * em).sum(axis=1) / (xim * xim).sum(axis=1), e


# --- self-test: inject a known slip and check the sign and the power ---
if __name__ == '__main__':
    N = 400
    ang = np.zeros((N, 4))                       # all wheels pointing +x
    fwd = np.tile([1.0, 0.0], (N, 1))            # accelerating forward
    back = np.tile([-1.0, 0.0], (N, 1))          # braking
    for lbl, slip, mods, sgn, ah in (
            ('launch, UNLOADING pair 0.40 ahead', +0.40, [0, 1], +1, fwd),
            ('launch, loading   pair 0.40 ahead', +0.40, [2, 3], +1, fwd),
            ('brake,  UNLOADING pair 0.40 behind', -0.40, [2, 3], -1, back),
            ('brake,  loading   pair 0.40 behind', -0.40, [0, 1], -1, back),
            ('no slip', 0.0, [], +1, fwd)):
        s = np.full((N, 4), 3.0)
        for m in mods:
            s[:, m] += slip
        k, _ = detect(s, ang, ah)
        print('%-36s k=%+.3f  S=%+.3f' % (lbl, k[0], k[0] * sgn))

    rng = np.random.default_rng(0)
    for sd in (0.02, 0.05, 0.10):
        s = np.full((5000, 4), 3.0) + rng.normal(0, sd, (5000, 4))
        k, _ = detect(s, np.zeros((5000, 4)), np.tile([1.0, 0.0], (5000, 1)))
        print('noise sd %.2f m/s -> k noise sd %.3f' % (sd, k.std()))
```

Module order is CTRE's: FL (+x, +y), FR (+x, −y), BL (−x, +y), BR (−x, −y), which
is the order `TunerConstants.createDrivetrain()` passes them in
(`generated/TunerConstants.java:201`) **[source]**.

### Three traps this parser walked into, recorded so the next one does not

1. **A least-squares slope from `cumsum(t*t)` at t ≈ 420 s loses all its
   precision.** `m·Σt² − (Σt)²` is a difference of numbers near 1.6 × 10⁸ whose
   true value is ~1, so float64 returns noise of order 1. It produced confident
   accelerations of 100 m/s². Subtract a time origin, or resample onto a uniform
   grid and use a Savitzky–Golay derivative, which is what every number above
   does (21 samples at 250 Hz = 84 ms, quadratic).
2. **The reported module speed is the rolling component only.** A module steering
   at 10 rad/s reports a wheel speed that has nothing to do with the chassis
   velocity along it. Without an azimuth-rate filter the logs appear to show
   routine 4 g chassis accelerations; they are steering transients.
3. **The azimuth is quantised at about 0.003 rad**, so a per-sample rate at
   250 Hz is ±0.75 rad/s of pure noise. Take the rate over 40 ms.
