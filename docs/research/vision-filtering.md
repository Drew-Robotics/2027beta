# How teams filter vision pose measurements, and when they reset from them

**Research for:** [Drew-Robotics/2027beta#26](https://github.com/Drew-Robotics/2027beta/issues/26)
**Date:** 2026-08-25

## Source and trust level

**[V]** = verified by reading the named source file directly (repo + season + path given).
**[U]** = inference or arithmetic on top of verified code; reasonable, but not stated anywhere
upstream.

Teams read: **6328** (Mechanical Advantage), **254** (The Cheesy Poofs), **1678** (Citrus
Circuits), **604** (Quixilver), **581** (Blazing Bulldogs) — their actual public repositories,
2023–2026 seasons, plus the AdvantageKit vision template. Libraries read: PhotonVision,
LimelightLib, YAGSL, and WPILib 2027 alpha-7 at `~/dev/allwpilib`. One Chief Delphi post is
used, labelled, as a team statement of rationale — everything else is code or vendor docs.

⚠️ **Almost every snippet below is 2024–2026 `edu.wpi.first` code.** Nothing here compiles as
written against 2027. Read them for structure and constants, never for imports.

---

## TL;DR

1. **Nobody gates on "this measurement disagrees with my current pose."** Across five teams and
   eleven team-seasons, exactly two such gates exist and both are scoped to autonomous. The
   lock-out failure mode the ticket worried about is avoided by **not building the gate**. This
   is unanimous and it is the single most decision-relevant finding.
2. **WPILib's own javadoc recommends the trap.** Both `PoseEstimator` and `PoseEstimator3d`
   advise "only adding vision measurements that are already within one meter or so of the
   current pose estimate", and neither implements it. No actively-maintained FRC library
   implements it either.
3. **The hard gates that everyone does build are absolute** — physical-impossibility and
   sensor-health checks that never take the current estimate as an input, so they cannot
   self-reinforce.
4. **Standard deviations are the universal soft knob.** Rejection is expressed as `σ → ∞`
   rather than an early return, by both vendors and every team.
5. **Gyro owns yaw. 5/5 teams, three different idioms, one consistent exception** (a multi-tag
   solve may correct heading).
6. **Vision-seeded reset happens only in the pre-match disabled window, if at all.** The team
   that shipped an in-match version removed it the following season.
7. **Every gate in the list requires camera/pipeline data that does not cross our seam.** See
   [Where this lands](#where-this-lands).

---

## 1. What teams actually gate on

Consolidated from the per-team sections below. "Relative" means the gate takes the current pose
estimate as an input.

| Gate | Who | Typical threshold | Relative? |
|---|---|---|---|
| Blank frame / `tagCount == 0` | everyone | — | no |
| Exact-origin pose sentinel | 581, 254, 1678 2026 | `(0,0)`; 254 use `‖p‖ < 1.0 m` | no |
| Stale or duplicate frame timestamp | 581, 254, 6328, WPILib | non-increasing; older than the buffer | no |
| Field bounds + margin | 6328, 1678 2024, AdvantageKit | ±0.5 m (AdvantageKit: zero margin) | no |
| Z height sanity | 6328, 254, AdvantageKit | `zMin −0.5`/`zMax 1.0`; `\|z\| > 0.2`; `\|z\| > 0.75` | no |
| Angular rate at capture | 254, 581, 1678 2024, Limelight docs | 5 rad/s; 100 °/s; 360 °/s | no |
| Linear speed at capture | 1678 2024 | `> 4.0 m/s` | no |
| Single-tag ambiguity | 254, 581, AdvantageKit, PhotonVision docs | **0.19 / 0.7 / 0.3 / 0.2** | no |
| Tag distance | 1678 2026, Limelight MT1 docs | 5.5 m; 3 m | no |
| Tag ID whitelist | 254, 604, 581, 6328 | task- or alliance-scoped | no |
| Mechanism occlusion | 1678 2026 | intake past a covering angle | no |
| Chassis tilt | 1678 2026 | pitch < 5°, 0.1 s debounce | no |
| **Disagreement with estimate — translation** | **1678 2024, 254 2023** | **2.0 m, auto only** | **YES** |
| **Disagreement with estimate — rotation** | **581 2026, 254 2025** | **45°; 5° single-tag close-up** | **YES** |

Two observations. First, the relative gates are the short rows — and both translation gates are
scoped to autonomous, which is what bounds them (see §5). Second, **the ambiguity constants
span 0.19 to 0.7 across four sources.** These are not the same quantity: PhotonVision's is
WPILib's `min(err1,err2)/max(err1,err2)` from `AprilTagPoseEstimate.getAmbiguity()`, Limelight
never documents its formula, and 6328's `0.4` is not an ambiguity scalar at all but a
*reprojection-error ratio* between two candidate solutions. **[V]** Copying a threshold between
pipelines is a bug.

### The gates nobody builds

- **No team rejects on tag count > 0.** Single tags are accepted everywhere; they are
  *downweighted*, and their heading is discarded, but not dropped. **[V]**
- **No team gates on latency beyond the estimator's own buffer window.** **[V]**
- **No library filters at all.** `PhotonPoseEstimator`'s entire gate is
  `timestamp >= 0 && hasTargets()`; `LimelightHelpers.validPoseEstimate` is a null/empty check;
  WPILib's only rejection is the 1.5 s staleness window. **[V]**

---

## 2. Standard deviations

### The shapes actually in use

| Source | Season | σ_xy | σ_θ |
|---|---|---|---|
| 6328 | 2024 | `0.005 · d² / n` | `0.01 · d² / n` |
| 6328 | 2025 | `0.01 · d^1.2 / n²` | `0.03 · d^1.2 / n²` |
| 6328 | 2026 | `0.01 · d² / n² · k_cam` | `0.03 · d² / n² · k_cam` |
| AdvantageKit template | — | `0.02 · d² / n` | `0.06 · d² / n` |
| 1678 | 2024 | `max(0.02, 0.1·(0.01·d_min² + 0.005·d_avg²) / n)` | none — translation-only filter |
| 1678 | 2025/26 | `0.3 · d` (0.1 aligning, 0.02 post-bump) | `99999 · d` |
| 581 | 2026 | `0.01 · d^0.8` | `999.0` |
| 581 | 2025 | `0.01 · d^1.2` | `Double.MAX_VALUE` |
| 254 | 2025 | Limelight's own `stddevs` array ÷ `quality` | `1e6` single-tag |
| 254 | 2024 | hand-tuned ladder: 0.2 / 0.5 / 1.0 / 1.2 / 2.0 m | 50° |
| PhotonVision example | — | `base · (1 + d²/30)`, base `4` | base `8` |

`d` is mean tag distance in metres, `n` is tag count. **[V]** throughout.

**There are two families, not one.** Family A — `k · d^p / n^q`, originating with 6328's 2024
code and propagated through the AdvantageKit template, the most-copied vision file in FRC.
Family B — PhotonVision's `base · (1 + d²/30)`, which YAGSL copies *verbatim including every
constant*, so it is two data points and not four. **[V]**

Family B's base values are `(4, 4, 8)` with the source comment **"(Fake values. Experiment and
determine estimation noise on an actual robot.)"** — PhotonVision explicitly disclaiming
them. **[V]** Family A's are the only constants any source claims to have measured.

**The constants do not transfer.** Within Family A the xy coefficient spans 0.005 → 0.02, a 4×
range — and 6328 changed their own by 2× and their distance exponent from 1.2 to 2.0 between
consecutive seasons. **[V]** 581 moved their exponent *down* from 1.2 to 0.8 over the same
period, trusting vision further out. Whatever we ship is a starting point to be tuned, not a
number to be believed.

Family A's coefficient does have a physical reading — it is normalised to 1 m and 1 tag, so `k`
*is* the expected std dev of a one-metre single-tag reading. **[U]** That makes it tunable by
measurement rather than by feel, which Family B's multiplier form is not.

### Rejection expressed as a standard deviation

Every source that wants to discard a component does it by inflating σ rather than by dropping
the measurement:

- 6328: `Double.POSITIVE_INFINITY` for σ_θ on every single-tag frame. **[V]**
- AdvantageKit: `angularStdDevMegatag2Factor = Double.POSITIVE_INFINITY`, commented
  *"No rotation data available"*. **[V]**
- PhotonVision: `Double.MAX_VALUE` for a single tag beyond 4 m — a hard reject routed through
  the gain instead of a `continue`. **[V]**
- Limelight docs: `9999999`. **[V]**
- 254: `kLargeVariance = 1e6`. **[V]**

**This works against WPILib 2027's actual arithmetic, verified locally.**
`PoseEstimator3d.setVisionMeasurementStdDevs` (`PoseEstimator3d.java:100`) computes
`r[i] = σ²` then `k[i] = q[i] / (q[i] + √(q[i]·r[i]))`, with a guard for `q[i] == 0`. **[V]**
With `σ = ∞`, `r = ∞` and the gain is exactly `0` — no NaN. `Double.MAX_VALUE` squares to
`Infinity` by overflow and behaves identically. **[U — arithmetic on verified code.]**

Two consequences for a `Matrix<N4,N1>` seam. The gain is **per-axis and decoupled** — there is
no covariance anywhere in the implementation — so four independent std devs lose nothing versus
a full covariance matrix. **[V]** And because rejection composes per-axis while a boolean does
not, a seam that accepts only std devs is *more* expressive than one with a separate reject
path, not less. **[U]**

Also verified: the correction is proportional to the residual at constant gain, with **no
saturation** — a measurement 10 m off moves the estimate ten times as far as one 1 m off.
**[V]** That is exactly why WPILib's javadoc reaches for the one-metre gate, and exactly why
one bad frame hurts.

---

## 3. Gyro versus vision yaw

**Unanimous: the gyro owns heading, vision corrects translation.** Three idioms, in increasing
order of how hard they are to get wrong:

**(a) Structural — the filter has no heading state.** 254's 2023 `RobotState` is a 2-state UKF
over the field→odom *translation* offset, and `getFieldToVehicle` returns
`odomToVehicle.getRotation()` verbatim; 1678's 2024 EKF is the same shape, `Nat.N2()` over
`(x, y)`. **[V]** Vision cannot rotate the robot, by construction. No constant to get wrong, no
future edit that quietly re-enables it.

**(b) A huge σ_θ.** 6328's `POSITIVE_INFINITY`, 1678's `99999`, 581's `999`, 254's `1e6`.
**[V]** Portable to any Kalman-style estimator, ours included.

**(c) Condition the solve on the gyro so the pose arrives already correct.** MegaTag2 via
`SetRobotOrientation` every frame; PhotonVision's `PNP_DISTANCE_TRIG_SOLVE` via `addHeadingData`
— range and bearing plus a known heading, ignoring the PnP rotation entirely and so immune to
tag flipping. **[V]** 581, 1678 2026 and 254 2024/25 all do this *and* (b).

**The exception is consistent across teams: a multi-tag solve is allowed to correct heading.**
6328 set `useVisionRotation = true` only in the multi-tag branch; 254's 2025 code accepts a
2-tag MegaTag1 heading with a real σ_θ; 581 take MT1 rotation inside 40 inches with
`σ_θ = 0.03·d^1.2`. **[V]** The rule is *heading from vision only when the geometry actually
determines it.*

⚠️ **One vendor claim did not verify.** "MegaTag2 solves translation only and passes yaw
through" is **not stated anywhere in Limelight's documentation**. What *is* documented is
"Unlike MT1, MT2 assumes that you know your robot's heading (yaw)". Two facts cut against the
stronger reading: `botpose_orb_wpiblue` still carries a full roll/pitch/yaw triple, and
`stddevs` publishes distinct `MT2yaw`. **[V that it is undocumented.]** Do not write it into an
ADR as vendor fact. The practical outcome is unchanged, because the recommended integration
discards the rotation by tuning anyway.

---

## 4. Vision-seeded reset

**Nobody resets from vision mid-match.** The pattern is: reset while disabled, blend while
enabled.

| Team | Vision-seeded reset? |
|---|---|
| 6328 | **Never.** `resetPose` is called only by auto routines with literal start poses, and by a two-button driver heading-zero that keeps the current translation. No vision data enters either. **[V]** |
| 254 | 2023 only, and only when **disabled and never enabled** this power cycle — it snaps the offset *and* forces the UKF's `xhat` so there is no filter transient. 2024's `teleopInit` is the **inverse**: a vision update within the last 3 s *suppresses* an otherwise-blind alliance-heading reset. **[V]** |
| 1678 | Heading only, 2024 only: at `autonomousInit` the Pigeon is zeroed to a 100-sample moving average of vision yaw accumulated while disabled — averaged in `[-180,180)` for blue and `[0,360)` for red so it never straddles a wrap. **[V]** |
| 581 | 2025 had one: a 5 s falling debounce on "any camera sees a tag" — after 5 s blind while enabled, the next accepted frame hard-reset pose *and* heading. **Removed for 2026.** **[V]** |
| 604 | Every loop, unconditionally — but that *is* their architecture (see §6), not a reset in our sense. **[V]** |

### The better mechanism: change the trust ratio, not the pose

254's answer is worth more than the resets. Their drivetrain state std devs are switched by
enable state: `(1.0, 1.0, 1.0)` disabled versus `(0.3, 0.3, 0.2)` enabled. **[V]** While the
robot sits on the wall, odometry is declared 3–5× less trustworthy, so vision dominates and the
pose converges — **with no discontinuity, no special case, and nothing to forget to clear**.
1678 reach for the same lever from the other side, dropping σ_xy to 0.02 right after driving
over the field bump so a fresh measurement snaps the pose. **[V]**

⚠️ **254's exact move is not available to us.** WPILib's `PoseEstimator3d` takes `stateStdDevs`
**only in the constructor** — there is no `setStateStdDevs` mutator anywhere in
`org.wpilib.math.estimator`. **[V]** That is a CTRE `SwerveDrivetrain` feature. The equivalent
we *do* have is free: scale the **vision** std devs down while disabled. Our seam takes them
per-call, the season's vision class is already computing them per-measurement, and the ratio
that actually drives the gain is identical.

### Reset mechanics in 2027, verified locally

- **`resetTranslation(Translation3d)` exists** (`PoseEstimator3d.java:157`) and preserves
  rotation — the exact primitive for a translation-only reset, should one ever be wanted.
  `resetRotation` is its mirror. **[V]**
- **Every reset variant clears both buffers** — `m_odometryPoseBuffer.clear()` and
  `m_visionUpdates.clear()`. **[V]** So a reset discards the 1.5 s history, and any in-flight
  measurement timestamped before it is then unmatched and **silently dropped**. A mid-match
  reset costs latency compensation for every frame still in the air. **[U — consequence of
  verified code.]**
- `resetTranslation` and `resetRotation` re-apply the last vision update to the axis they did
  not reset, so they are not simply "write the field and move on". **[V]**

---

## 5. The lock-out failure mode

The ticket's ⚠️: a 1 m distance gate rejects the very measurement that would fix a genuine 2 m
drift, permanently.

**The answer from every team is: don't build the gate.** 6328, 254 (2024/25), 1678 (2025/26),
604 and 581 have no translation-disagreement gate at all, so the failure mode cannot arise.
**[V]** The two that exist are both bounded:

- **1678 2024** — 2 m, and `if (isInAuto)` only. Three structural escape hatches: it is scoped
  to a 15-second period so any latched rejection self-clears at the buzzer; the first update
  after construction always passes; and the acceptor is rebuilt on every `RobotState.reset()`.
  **[V]** No *in-band* hatch, though — with vision continuously visible and a genuine >2 m
  drift, 2024 would ride the bad pose to the end of auto. **[U]**
- **254 2023** — 2 m, auto only, and crucially compared against the **previously accepted
  offset** rather than a fixed initial pose, so the filter can walk to an arbitrarily distant
  truth in ≤2 m steps. **[V]**

The teams that keep a *rotation* disagreement gate did think about the hatch. 254's 5° single-tag
yaw check applies only when `fiducialIds().length < 2` **and** `avgTagArea < 2.0` — a close-up
tag or a second tag bypasses it entirely, so a drifted heading can always be corrected by
driving closer. **[V]** 581's 45° MT2 gate has no such bypass, and they removed the automatic
blind-reacquire reset that used to serve as one; recovery in 2026 is the manual back-button or
the disabled-mode auto-start reset. **[U]** That is the one live lock-out trap found in any of
this code, and it is on rotation, not translation.

### Why absolute gates are safe and relative ones are not

Every gate in §1's long list tests the measurement against **fixed, externally-known truth** —
the field is 17.5 m long, the robot cannot be a metre in the air, a single tag cannot be trusted
when ambiguous, the robot was spinning at 8 rad/s when the shutter opened. None takes the
estimate as an input. So a drifted estimate cannot cause a correct measurement to be rejected,
and lock-out is structurally impossible. **[U — but it follows directly.]** Meanwhile the gates
still catch what matters: a tag flip puts the pose off the field or in the air.

6328's one estimate-referencing decision is instructive: single-tag disambiguation picks the
solution whose yaw is closest to the current heading — but it **selects rather than rejects**,
and the selected pose then contributes translation only. A wrong pick costs one bad translation
sample, not a permanent lock-out. **[V]**

### WPILib recommends the trap

> To promote stability of the pose estimate and make it robust to bad vision data, we recommend
> only adding vision measurements that are already within one meter or so of the current pose
> estimate.

That sentence is on **both** `addVisionMeasurement` overloads of `PoseEstimator` *and*
`PoseEstimator3d` — the class behind our seam. **[V]** WPILib does not implement it, and offers
no hysteresis, timeout or escape hatch alongside it. Taken literally it is self-defeating.

**Exactly one FRC library ever implemented it: YAGSL's `filterPose`** — a 1 m gate with the only
published escape-hatch design, a **consecutive-agreement counter**. If vision insists on the
same far-off answer 10 cycles in a row (~200 ms) it is believed and the gate opens; any
in-tolerance measurement resets the counter to zero, so noise cannot accumulate through. The
rationale is sound — *transient disagreement is noise, persistent disagreement is evidence the
estimate is wrong, not the measurement.* **[V]** Both caveats matter: it is
`@Deprecated(since = "2024", forRemoval = true)`, and `grep` confirms **it is never called**.
Its own comments contradict each other about whether the threshold is 1 m or 10 m; the code says
1 m. **[V]**

---

## 6. Per-team notes worth keeping

### 6328 — Mechanical Advantage

Hand-rolled `RobotState`, not `SwerveDrivePoseEstimator`: it looks up the odometry pose at the
measurement timestamp in a 2 s buffer, walks the estimate back by the odometry delta, applies a
closed-form scalar gain per axis, and walks forward — a single rigid transform rather than a
replay. **[V]** Odometry process noise is `(0.003, 0.003, 0.002)`: trusted very hard.

- **Two poses are maintained, not one** — `odometryPose` (never touched by vision) alongside
  `estimatedPose`, both logged. Disagreement is directly visible in a log, which is what makes
  std devs tunable in replay instead of by guess. **[V]** *(This matches what
  [#20](https://github.com/Drew-Robotics/2027beta/issues/20) already decided for us — two
  estimators, one told about vision.)*
- **Std devs are the only soft knob; every hard gate is a physical-impossibility check.** The
  most transferable structural idea here. **[V]**
- Selectable **partial tag layouts** (`OFFICIAL / NONE / HUB / …`) act as a filter, because any
  tag missing from the active layout drops the frame — including `NONE` to kill AprilTag
  localisation at an event with no code change. **[V]**
- 2026 ignores all vision for the first **2.0 s of autonomous**, so the known start pose is not
  immediately fought. Bounded by a timer that restarts outside auto. **[V]**
- 2025's answer to "the global pose is not accurate enough to score" is a **second, local,
  tag-relative estimate** blended in over 24–36 inches — not a tighter global filter and not a
  reset. **[V]**

### 254 — The Cheesy Poofs

Correction to a common belief: the "254 use their own `RobotState` fusion" description holds for
**2023 only**. In 2024/25 `RobotState` is a thread-safe interpolating buffer plus motion
history, and the fusion is CTRE's estimator via `addVisionMeasurement`. **[V]**

- **Query the pose *and the motion* at capture time, not now.**
  `getMaxAbsDriveYawAngularVelocityInRange(min, max)` returns the **max over a window ending at
  the capture instant** — so a 20 ms spin spike that corrupted a frame is caught even if the
  robot is stationary by the time the packet lands. Backed by a 250 Hz Pigeon signal. **[V]**
  This is the detail most re-implementations miss.
- **Fail closed**: `.orElse(Double.POSITIVE_INFINITY)` — no gyro history means reject. **[V]**
- **Two cameras are fused into one measurement before the estimator sees them**, by
  inverse-variance weighting, with the older estimate advanced by the odometry delta first and
  heading fused in cos/sin space. Sequential injection double-counts correlated error; this does
  not. **[V]**
- **Vision health gates the action, not just the pose**: the robot refuses to score in teleop
  unless vision produced an accepted update within 0.5 s *and* agrees with the estimate within
  0.2 m. Bypassed wholesale in auto, with the comment *"Just send it in auto."* **[V]**
- A global vision kill switch, used deliberately mid-auto when the robot is slamming into field
  elements, force-re-enabled in `disabledExit()` as a safety net. **[V]**
- Every gate logs its own boolean, so each rejection reason is separately replayable. **[V]**
- ⚠️ **Clock domains bit them.** 2024 passed the Limelight FPGA timestamp straight through; 2025
  wraps it in `Utils.fpgaToCurrentTime(...)` because CTRE's estimator runs its own monotonic
  clock. Decide one canonical time base and convert at exactly one boundary. **[V]**

### 1678 — Citrus Circuits

Three genuinely different architectures across four seasons; 2023 has no AprilTag fusion at all.
Their reliability comes from gyro-owned yaw, distance-scaled std devs and **mode-scoped trust
changes** — not from a rejection pipeline. The 2024 acceptor is 55 lines with three thresholds;
2025 has one gate. **[U]**

- **The mechanism-occlusion gate** is the most idiosyncratic thing found anywhere and the most
  worth stealing: if the intake is deployed past the angle where it covers the camera, reject
  everything. **[V]** A gate that maps to a physical fact rather than a statistic.
- **`BUMP_STD_DEVS`** — after driving over the field bump wrecks wheel odometry, drop σ_xy to
  0.02 and wait for a fresh in-view measurement to snap the pose. A mode-scoped confidence boost
  instead of a hard reset or a rejection band. **[V]**
- ⚠️ Do not inherit: their 2024 steady-state fusion files each measurement under the **previous**
  frame's timestamp; the frame queue drops the **newest** frame when six or more arrive;
  `resetPoseIfWithoutEstimate` has no condition left in its body; the 2025 post-reset vision
  blackout is written and never read; 2026's `getX() == 0 || getY() == 0` rejects poses lying on
  *either* axis. **[V]** Several gates are declared and never wired — read the config array, not
  the class list, to know what runs.

### 604 — Quixilver

**They do not use a Kalman pose estimator.** Whole-field localisation is a 1000-particle SIR
filter in Python **on the driver station laptop**, fed raw AprilTag **corner pixels** plus
intrinsics over NetworkTables; the robot replays odometry on top of the returned pose. **[V]**
The team's own code-release thread confirms the latency cost.

- On the robot there is essentially no gate — coherent, because no per-frame pose is ever
  formed, so there is nothing to sanity check. Rejection is the filter's job. **[V]**
- Escape hatch is **global relocalisation**: 11 consecutive rejected frames scatters 1000
  particles uniformly across the field and re-converges. **[V]**
- ⚠️ Their `pixelSigma` is `Math.max(100.0, 5 + 10·|v| + 20·|ω|)` — the bracket only exceeds 100
  above ~9.5 m/s, so **it is pinned at 100 and the speed-scaling is dead**. Looks like `Math.min`
  was meant. **[V]/[U]** Know this before copying the idea.
- **Not portable** to a stock `SwerveDrivePoseEstimator3d`: it produces a weighted particle mean
  with no covariance, and it *overwrites* the estimator rather than feeding it — fusing would
  double-count. It needs a laptop, raw corners and full per-camera intrinsics. **[V]/[U]** What
  *is* portable: the separate gyro-heading single-tag estimate kept out of the global filter, and
  the per-action tag whitelist.

### 581 — Blazing Bulldogs

Stock and entirely portable — Limelight MegaTag2 into the CTRE estimator, with all gates in one
shared `PoseEstimateValidator`. Cheap ideas worth lifting:

- **Duplicate-frame rejection keyed on the frame timestamp, tracked separately per source**
  (MT1 vs MT2) — with an in-code comment explaining that Limelights sometimes get stuck
  returning the same frame, and that sharing one field would let stale frames through. **[V]**
- **Timestamp clamped to `min(now, frameTime)`** so a bad clock cannot insert a future
  measurement into the history buffer. **[V]**
- **`TrustFactor`** — a scalar that grows with metres travelled × odometry σ (plus 2.0 on a
  detected collision) and is knocked down when vision agrees. It does **not** filter anything:
  it gates *actions*, and the robot refuses to transition to `SCORE` unless it is under 5 inches.
  **[V]** *Keep the estimator permissive and put the confidence check at the point of use* —
  the single best idea in this document.
- ⚠️ `TrustFactor`'s comment says `weight = 1 − std/total` but the code is `std/total`, which
  weights the **worst** camera most. **[U]** Real comment-vs-code discrepancy.

---

## Where this lands

### Filtering is a season concern, not a drive-base concern

The ticket asked whether a filter belongs in the drive base, and said that "it belongs to a
season" is an acceptable outcome. **That is the answer, and the evidence is decisive.**

Go back through §1's table and check what each gate needs as an input: `tagCount`,
`rawFiducials[].ambiguity`, `avgTagDist`, `avgTagArea`, `distToCamera`, MT1-vs-MT2 provenance,
the tag ID list, the active field layout, and — for the best gate anyone wrote — the position of
an intake. **Not one of these crosses the seam
[#20](https://github.com/Drew-Robotics/2027beta/issues/20) settled.** `visionUpdate(Pose3d,
timestamp, Matrix<N4,N1>)` hands over a pose, an instant and four numbers. By construction the
drive base cannot compute a single one of these gates, and giving it the ability to would mean
importing a camera type into the drive base — which is exactly what #20 decided against.

So the two placeholders hold, now with evidence behind them rather than as defaults:

- **The drive base rejects nothing.** Confirmed. It has no basis on which to reject.
- **Vision never resets the pose.** Confirmed, and strengthened: no team resets mid-match, the
  one team that shipped an in-match reset removed it, and WPILib's reset clears the 1.5 s buffer
  and silently drops every in-flight measurement.

The reference numbers in §1 and §2 exist so that a season's vision class starts from measured
constants instead of re-running this research — with §2's warning that they are a starting point
to tune, not a number to believe.

### Three things this surfaced that *are* drive-base concerns

1. **Motion at capture time is not reachable through the seam.** The angular-rate gate is the
   one gate on the list that is *not* camera-specific — 254, 581, 1678 and Limelight's own docs
   all have one — and 254's version needs a **buffered history of gyro rate, queried at the
   frame's capture timestamp**. Only the drive base has that. A season's vision class holding a
   `Pose3d` and a timestamp has no way to ask "was the robot spinning when this shutter opened?"
   and would have to duplicate a gyro-rate buffer to find out. **This is a real gap in the #20
   seam** and is now its own ticket.
2. **The disabled/enabled trust switch has to be done with vision std devs.** WPILib's estimator
   takes `stateStdDevs` only at construction (§4), so 254's move is unavailable directly. Scaling
   the vision σ while disabled is equivalent, free, and lands in the season class.
3. **`σ = ∞` is safe against WPILib 2027's gain math** (§2) — verified, no NaN. The single θ slot
   in `Matrix<N4,N1>` covers roll, pitch and yaw together, so one infinity expresses "gyro owns
   all three rotations", which is what we want anyway.

---

## Sources

Full per-file URLs, with commits and line regions, are in the per-team research notes captured
during this investigation. Roots:

**Teams** — `Mechanical-Advantage/RobotCode2024Public` · `RobotCode2025Public` ·
`RobotCode2026Public` · `AdvantageKit` (vision template, already on `org.wpilib`) ·
`Team254/FRC-2023-Public` · `FRC-2024-Public` · `FRC-2025-Public` · `frc1678/C2023-Public` ·
`C2024-Public` (including the in-repo `polaris/` Python coprocessor) · `C2025-Public` ·
`C2025-SystemCore` · `C2026-Public` · `frc604/2024-public` · `2025-public` (including
`offboard/quixpf/`) · `team581/offseason-2025` · `frc-2026`.

**Vendors** — `PhotonVision/photonvision` (`photon-lib`, `photon-targeting`,
`photonlib-java-examples/poseest`, `docs/source`) · `LimelightVision/limelightlib-wpijava` and
`docs.limelightvision.io` · `Yet-Another-Software-Suite/YAGSL-Example`.

**WPILib 2027 alpha-7**, local at `~/dev/allwpilib`:
`wpimath/src/main/java/org/wpilib/math/estimator/PoseEstimator3d.java` ·
`SwerveDrivePoseEstimator3d.java` · `PoseEstimator.java` ·
`apriltag/src/main/java/org/wpilib/vision/apriltag/AprilTagPoseEstimate.java` ·
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/PoseEstimator.java`.

**Team statement** (labelled, rationale only) — Chief Delphi, 604's 2025 code-release thread,
"Software → Localization".
