# Choreo on WPILib 2027 / SystemCore

Research for [Drew-Robotics/2027beta#6](https://github.com/Drew-Robotics/2027beta/issues/6).

**Date of research:** 2026-08-24, **re-checked 2026-08-28** for
[#78](https://github.com/Drew-Robotics/2027beta/issues/78) — see
[Re-check 2026-08-28](#re-check-2026-08-28) for what moved and what did not.
**Local reference build:** `~/dev/allwpilib` at `v2027.0.0-alpha-6-366-gcafb0cc79`
(`WPILibVersion.Version = "2027.424242.0.0-alpha-6-20260824110254-366-gcafb0cc79"`).
On 2026-08-28 a `git fetch` showed the checkout **12 commits behind `origin/main`** and, more
importantly, **10 commits behind the newly created `v2027.0.0-alpha-7` tag**. Nothing was pulled;
every file:line below is still the 2026-08-24 tree.

> ⚠️ **Read this before trusting any version number below.**
> On 2026-08-24 this read *"there is no released WPILib 2027 alpha-7"*; **on 2026-08-28 the tag
> exists but the release still does not.** `wpilibsuite/allwpilib` now has **seven** 2027 tags —
> `v2027.0.0-alpha-7` is an annotated tag by Peter Johnson dated **2026-08-26**, pointing at
> `dc7ad0eb3` — but `GET /repos/wpilibsuite/allwpilib/releases` still returns
> **v2027.0.0-alpha-6 (2026-05-08)** as the newest GitHub Release, and the *"2027 Alpha 7"*
> milestone is still `state=open` (0 open / 12 closed issues). Tagged, not published. [V]
>
> Our checkout is **366 commits past alpha-6 on `main`**. Much of what makes 2027 interesting —
> **the entire Tunables/Telemetry API including `Selectable`** — landed in
> [allwpilib PR #7773](https://github.com/wpilibsuite/allwpilib/pull/7773) (merged **2026-08-21**,
> milestone *2027 Alpha 7*) and is therefore **on `main` but in no installable release**.
> Alpha-6 still shipped `SmartDashboard`.
>
> So: sections below marked *"present in the tree"* mean *present on `main`*. Anything a team
> could actually install today behaves like alpha-6.

Legend: **[V]** = verified against a primary source I read directly. **[U]** = uncertain /
inferred / subject to change.

---

## Re-check 2026-08-28

Re-verified for [#78](https://github.com/Drew-Robotics/2027beta/issues/78). Everything here was
followed back to the source that owns it on **2026-08-28**; anything not listed as *unchanged* or
*changed* below was **not re-checked** and still carries its 2026-08-24 reading.

### What changed

- **WPILib alpha-7 is tagged.** [V] `v2027.0.0-alpha-7`, annotated 2026-08-26 by Peter Johnson at
  `dc7ad0eb3` (`gh api repos/wpilibsuite/allwpilib/git/tags/e4013ac718…`). **Still no GitHub
  Release** and the *2027 Alpha 7* milestone is still open. Our local checkout `cafb0cc79`
  (2026-08-24) is **10 commits before** that tag.
- **The Choreo maintainer gave a date range, and it is gated on alpha-7.** [V] ChiefDelphi
  [#522484 post #4](https://www.chiefdelphi.com/t/official-choreo-systemcore-offseason-use/522484/4),
  Amicus1, **2026-08-27T03:52Z**, verbatim: *"WPILib alpha 7 is working its way through the release
  process and has a bunch of breakages and fixes that affect ChoreoLib, so there's not much point in
  releasing something incompatible with alpha 7. I estimate an alpha 7 compatible vendordep within
  the next 2 weeks, hopefully significantly sooner."* This **supersedes** §1's *"one more evening of
  CI"* as the live estimate, and it explains the branch's silence: the transitional release is now
  waiting on alpha-7, not on CI. Prompted by post #3 (noto501, 2026-08-27T03:18Z) asking for exactly
  this.
- **A correction to §1, not a change in the world:** this document said Choreo
  [PR #1481](https://github.com/SleipnirGroup/Choreo/pull/1481) *"flips to field-center (0,0)
  coordinates"*. That is **true of the runtime flipper and false of everything around it** — see the
  corrected paragraph in [§1](#the-in-flight-work-v) and the full reading in
  [§6.4](#64-the-coordinate-origin-and-what-our-flip-must-be-today-v).

### What is unchanged since 2026-08-24 (re-verified, not assumed)

| Claim | Re-checked how | State |
|---|---|---|
| No 2027 ChoreoLib release | `gh api repos/SleipnirGroup/Choreo/releases` | newest still **v2026.0.3, 2026-04-06** [V] |
| `ChoreoLib2027.json` | `curl` | still **404** [V] |
| `ChoreoLib2027Alpha.json` | `curl` | still **2027.0.0-alpha-1**, `frcYear: "2027_alpha1"`, `edu.wpi.first`-era, gson 2.11.0 [V] |
| Maven metadata | `frcmaven.wpi.edu/.../ChoreoLib-java/maven-metadata.xml` | `latest`/`release` = **2026.0.3**, `lastUpdated 20260406023046`; only 2027 version is `alpha-1` [V] |
| `systemcore-transitional-release` tip | `gh api .../commits?sha=…` | still **`05c2779` "Add opmode to run AutoChooser", 2026-08-17**; **no new commits in 11 days**; ahead 2 / behind 30 of `main` [V] |
| Choreo `main` tip | same | still **`764ff38c`, 2026-08-09** — TrajOptLib/Sleipnir maintenance only [V] |
| Choreo branch list | `gh api .../branches` | `add-home-page`, `gh-pages`, `main`, `systemcore-alpha`, `systemcore-transitional-release` — **there is no `2027` branch** [V] |
| PR #1481 | `gh api .../pulls/1481` | still **open, `mergeable_state=dirty`, last touched 2026-07-12**, head `Daniel1464/Choreo:2027-update` [V] |
| gh-pages / choreo.autos | `gh api .../commits?sha=gh-pages` | still **`a7d73101`, 2026-08-09**, "Deployed 764ff38" — no 2027 banner [V] |
| `.traj` schema version | `TrajSchemaVersion.java` and `traj_schema_version.rs` on **both** `main` and `systemcore-transitional-release` | still **3** on all four files [V] |
| Vendor compatibility matrix | `wpilibsuite/SystemcoreTesting` README (`pushed_at` 2026-08-24) | ChoreoLib still **:x:** for alpha-5/6; no alpha-7 column yet [V] |
| `vendor-json-repo` | `gh api .../contents` | still only `2027_alpha1/` and `2027_alpha5/`; **no `2027_alpha6` or `_alpha7` directory**, no ChoreoLib in either; last commit **2026-08-18** [V] |
| `OriginPosition` | `~/dev/allwpilib/fields/src/main/java/org/wpilib/fields/Field.java:43-48` | still exactly two members, `BLUE_ALLIANCE_WALL_RIGHT_SIDE` and `RED_ALLIANCE_WALL_RIGHT_SIDE`. **No field-centre member.** [V] |
| `Field.java` origin doc | `Field.java:32-33` | still *"NWU with the origin at the bottom-right corner of the blue alliance wall"* [V] |
| 2027 field / AprilTag map | `git ls-files fields` on the local tree | newest FRC layout is **`2026-rebuilt-welded.json` / `-andymark.json`**; `fields/src/generate/fields.json` still has `"defaultField": "FRC_2026_REBUILT_WELDED"`. **No 2027 layout in the tree, tagged or untagged.** [V] |
| Origin work upstream | `git log HEAD..origin/main` (12 commits, 2026-08-24→27) and issue search | **nothing coordinate- or origin-related.** The 12 are ntcore/wpigui/telemetry/HID/`TwoDeadWheelOdometry`/`CANBus`→`CANPort`. The only `fields`-adjacent diff in the range is a two-line `TrapezoidProfile.java` change. [V] |
| WPILib 2027 docs | `curl https://docs.wpilib.org/en/2027/…` | **302 → `/en/latest/`**, which is 2026 content. There is no published 2027 docs branch to read a coordinate statement out of. [V] |

### Not checked

C++/Python transitional variants, Elastic PR #366, AdvantageScope's `Selectable` support, the DS
opmode selector's runtime behaviour, and everything in §§2–5, §7 and §8 were **not re-checked**;
they stand as of 2026-08-24.

---

## TL;DR

1. **There is no usable 2027 ChoreoLib today.** [V] Newest real release is **v2026.0.3
   (2026-04-06)**, targeting WPILib 2026.1.1. A `ChoreoLib2027Alpha.json` vendordep resolves, but
   it serves `2027.0.0-alpha-1` — a **June 2025** port built against WPILib `2027.0.0-alpha-1`,
   `edu.wpi.first` group IDs and **Java 17**. It will not link against current alphas.
2. **A "transitional" SystemCore release is in flight and is Commands v2 only.** [V] Work lives on
   the `systemcore-transitional-release` branch (last commit 2026-08-17), migrated to
   `wpilibVersion = '2027.0.0-alpha-6'`, Java 25, `org.wpilib` group IDs, and it depends on
   `org.wpilib.commandsv2:commandsv2-java`. Maintainer said on 2026-08-17 it "just needs one more
   evening of updating the CI setup." Not published as of 2026-08-24.
3. **The maintainer explicitly tells us not to build 2027 robot code on it.** [V] Verbatim: *"the
   library we release for this transition will not represent our planned ChoreoLib API for 2027.
   **Do not invest time in preparing 2027 robot code templates using this version.**"*
4. **The real 2027 ChoreoLib is blocked on WPILib standardizing a Trajectory/Sample class — and
   WPILib has now shipped it, explicitly modelled on ChoreoLib's own.** [V]
   `org.wpilib.math.trajectory.HolonomicTrajectory` / `HolonomicSample` are on `main`,
   JSON-serializable, and carry `ChassisAccelerations`. allwpilib issue #8160 states the intent
   verbatim: *"we could upstream ChoreoLib's `Trajectory` class and `TrajectorySample`
   interface/concept… We could also upstream ChoreoLib's differential drive and swerve drive sample
   classes (without the input members)."* WPILib **also vendors Sleipnir, Choreo's optimizer, with
   Java bindings** (`org.wpilib.math.optimization`).
5. **ChoreoLib is ❌ in WPILib's official vendor compatibility matrix for alpha-5/6.** [V] Its
   vendordep is hard-pinned `"frcYear": "2027_alpha1"`. PathPlanner ✅ (alpha-3) and **REVLib ✅** are
   both there. 2027 is a full **32-bit → 64-bit ARM ABI break** (`EM_ARM` → `EM_AARCH64`,
   `linuxathena` → `linuxsystemcore`), so every vendor rebuilds from scratch.
6. **Answer to "does 2027 use `ChassisAccelerations` for feedforward": on the WPILib side,
   emphatically yes.** [V] But note the bigger surprise: **`ChassisSpeeds` no longer exists.** It is
   renamed **`ChassisVelocities`**, and `SwerveModuleState` → `SwerveModuleVelocity`.
7. **ChoreoLib is Commands v2 only on *both* branches.** [V] `commands3`/`wpilibj3` → 0 results
   across the repo. The 2027 branch is a namespace migration to `org.wpilib.command2`, not a v3
   port. But `choreo.trajectory` (loading + sampling) has **no command-framework dependency** and
   works under any scheduler — that split is our way in.
8. **ChoreoLib does *not* use `ChassisAccelerations`.** [V] Zero occurrences. It exposes `ax`, `ay`,
   `alpha` as loose public `double` fields on `SwerveSample`. The only 2027 type change in its API is
   `getChassisSpeeds()` returning `ChassisVelocities` on the systemcore branch.
9. **Auto selection is no longer a dashboard concern — and cannot be.** [V] `SendableChooser`,
   `SmartDashboard` and `Shuffleboard` are all deleted. **AdvantageScope cannot write string NT
   values**, so it cannot drive the replacement chooser at all; Elastic's support is an open,
   conflicted PR. The intended 2027 path is the **Driver Station's own opmode selector**. This pulls
   against our locked `TimedRobot` decision. See [§8](#8-selecting-an-auto-at-match-time).

---

## 1. Is there a 2027-compatible ChoreoLib?

### Release history [V]

`gh api repos/SleipnirGroup/Choreo/releases`:

| Tag | Published | Prerelease |
|---|---|---|
| v2026.0.3 | 2026-04-06 | no |
| v2026.0.2 | 2026-03-02 | no |
| v2026.0.1 | 2026-01-12 | no |
| v2026.0.0 | 2026-01-11 | no |
| v2026.0.0-beta-1 | 2025-11-20 | yes |
| v2025.0.3 | 2025-02-07 | no |

No GitHub Release mentions 2027. A bare git tag `v2027.0.0-alpha-1` exists (commit 2025-06-28,
[PR #1274](https://github.com/SleipnirGroup/Choreo/pull/1274)) with no Release attached.

`main` is in maintenance mode — the last ~40 commits (2026-03 → 2026-08-09) are Sleipnir/TrajOptLib
and toolchain bumps. **No 2027 work has landed on `main`.**

### Vendordep URLs [V]

| URL | Status | Contents |
|---|---|---|
| `https://choreo.autos/lib/ChoreoLib2026.json` | 200 | 2026.0.3, `frcYear: "2026"`, maven `frcmaven.wpi.edu/artifactory/sleipnirgroup-mvn-release/` |
| `https://choreo.autos/lib/ChoreoLib2027.json` | 404 | — |
| **`https://choreo.autos/lib/ChoreoLib2027Alpha.json`** | **200** | **2027.0.0-alpha-1**, `frcYear: "2027_alpha1"`, `linuxsystemcore` in `binaryPlatforms` |
| `https://lib.choreo.autos/dep/ChoreoLib2025.json` | 200 | 2025.0.3 (legacy host) |
| `https://lib.choreo.autos/dep/ChoreoLib2026.json` | 404 | host moved |

[Maven metadata](https://frcmaven.wpi.edu/artifactory/sleipnirgroup-mvn-release/choreo/ChoreoLib-java/maven-metadata.xml)
lists only `2027.0.0-alpha-1` for 2027; `latest`/`release` are `2026.0.3` (lastUpdated 2026-04-06).

**Do not use `ChoreoLib2027Alpha.json` as published.** It is a port of ChoreoLib 2025.0.3 from the
`systemcore-alpha` branch (tip 2025-07-04), built against `wpilibVersion = "2027.0.0-alpha-1"`,
GradleRIO 2027.0.0-alpha-1, `edu.wpi.first` group IDs, Java 17. WPILib has since moved to
`org.wpilib` and Java 25.

Corroborating: [wpilibsuite/vendor-json-repo](https://github.com/wpilibsuite/vendor-json-repo) has
`2027_alpha1/` and `2027_alpha5/` directories. **ChoreoLib appears in neither.** PathPlanner,
REVLib, Phoenix6, ReduxLib, ThriftyLib, AdvantageKit, DogLog and photonlib do. ChoreoLib *is*
present for 2026.

### The in-flight work [V]

Branch `systemcore-transitional-release` @ `05c2779` (2026-08-17) — **still the tip on
2026-08-28, eleven days with no commit**:

```gradle
ext.wpilibVersion = '2027.0.0-alpha-6'
def javaVersion = 25
id 'org.wpilib.NativeUtils' version '2027.13.1'
implementation "org.wpilib.commandsv2:commandsv2-java:$wpilibVersion"
```

`config.gradle` uses `nativeUtils.withCrossSystemCore()`. Its
`choreolib/vendor_jsons/ChoreoLib2027Alpha.json` is bumped to `2027.0.0-alpha-6` (`frcYear` still
`"2027_alpha5"` — [U] intentional or oversight). **Not published to maven or gh-pages.** The branch
is ahead 2 / behind 30 vs `main`.

The alpha-6 migration commit touched 50 files: `edu.wpi.first` → `org.wpilib`, Java 17 → 25,
`WPILibNewCommands.json` → `CommandsV2.json`, `withCrossRoboRIO` → `withCrossSystemCore`,
Gradle 8.14.3 → 9.4.1.

Also open: [PR #1481 "[choreolib] Full Java and C++ upgrade to WPILib 2027"](https://github.com/SleipnirGroup/Choreo/pull/1481)
— created 2026-05-09, last touched 2026-07-12 (**unchanged on 2026-08-28**), **conflicted**
(`mergeable_state=dirty`), 51 commits, +872/−2386, multiple `CHANGES_REQUESTED` from calcmogul, head
`Daniel1464/Choreo:2027-update`. It **removes `AutoRoutine` and `AutoChooser`** in favour of opmodes.

On 2026-08-24 this said the PR *"flips to field-center (0,0) coordinates"*. The 2026-08-28 re-check
narrows that: **the PR's flipper is field-centre, and nothing else in the PR is.** Read at head
`40b826ee`, `choreolib/src/main/java/choreo/util/ChoreoAllianceFlipUtil.java` drops the
`(fieldLength, fieldWidth)` constructor arguments entirely and `MirroredX.flipX` becomes
`return -x;` — against `main`, where `MirroredX(double fieldLength, double fieldWidth)` and
`Flipper.FRC_CURRENT = rotatedAround(FIELD_LENGTH, FIELD_WIDTH)` do the corner-origin arithmetic.
But on the same head, `choreolib/src/main/java/choreo/util/FieldDimensions.java` still reads
`FIELD_LENGTH = 16.541 / FIELD_WIDTH = 8.0692`, the PR's only edit to
`choreolib/src/main/native/include/choreo/util/FieldDimensions.hpp` is `units::` → `wpi::units::`
on those same two constants, and `SwerveSample`/`DifferentialSample` still document *"The X position
of the sample relative to the blue alliance wall origin."* So the PR is a half-migrated, stale,
conflicted branch — **not evidence that Choreo has moved its origin**, and it changes nothing about
what the GUI emits. [V]

[PR #1502 "2026 offseason systemcore release"](https://github.com/SleipnirGroup/Choreo/pull/1502)
was closed unmerged on 2026-08-17: *"This became a huge mess… Closing and redoing work as
`systemcore-transitional-release`."*

### Stated roadmap [V]

ChiefDelphi [*"[Official Choreo] Systemcore Offseason Use"* #522484](https://www.chiefdelphi.com/t/official-choreo-systemcore-offseason-use/522484),
by Amicus1 (Jeremiah Shue, Choreo maintainer).

Post #1, 2026-07-11 — verbatim points:

- "ChoreoLib 2026 for the SystemCore is coming, separately from our 2027 alpha releases."
- "the library we release for this transition will not represent our planned ChoreoLib API for
  2027. **Do not invest time in preparing 2027 robot code templates using this version.**" — the
  stated blocker is waiting on a WPILib-standardized Trajectory/Sample class.
- "we will prioritize a **commands v2** compatible library for the offseason"; for full 2027 "we
  intend to offer **two separate Java vendordeps**" (v2 and v3).
- Full 2027: "**opmodes and the Driver Station autonomous opmode selector will replace AutoChooser
  and AutoRoutine**"; the transitional release keeps AutoChooser/AutoRoutine.
- "we will release a new Choreo GUI version which is the same as 2026.0.3 except for the import
  paths used in Java code generation."
- "The WPILib coordinate system is not officially changing to field-center origin until the 2027
  field AprilTag map is released. This transitional release will still use coordinates with the
  blue alliance corner as the origin."

Post #2, 2026-08-17:

- "The work for the Java variant of this release is nearly done. It just needs one more evening of
  updating the CI setup."
- Known limitation: "the dashboard alerts functionality of ChoreoLib is not fully usable when using
  WPILib 2027 alpha 6, because they are no longer NetworkTables-based, but sent to the Driver
  Station. Simgui does not show alerts and simulation does not work with the real 2027 Driver
  Station currently."

Post #3, 2026-08-27T03:18Z — noto501, a team asking the same question we are: *"Hi was wondering if
any updates have been made since this last thread and if there are workarounds if not available? Our
team is currently making a branch to support systemcore without choreo at the moment…"* — i.e.
another team has independently arrived at our answer, owning the follower rather than waiting. [V]

Post #4, 2026-08-27T03:52Z — Amicus1, **the newest word on the record and the only date range that
has ever been given**:

- *"WPILib alpha 7 is working its way through the release process and has a bunch of breakages and
  fixes that affect ChoreoLib, so there's not much point in releasing something incompatible with
  alpha 7. I estimate an alpha 7 compatible vendordep within the next 2 weeks, hopefully
  significantly sooner."*

Two things follow. The transitional release is now **blocked on alpha-7 publishing**, not on CI —
which is why `systemcore-transitional-release` has not moved since 2026-08-17. And when it lands it
will target **alpha-7**, not alpha-6, so the branch's current `wpilibVersion = '2027.0.0-alpha-6'`
is already stale. Nothing in post #4 touches the Commands-v2 constraint or the origin. [V]

### Timeline [U]

As of 2026-08-24 there was **no date commitment anywhere**; as of 2026-08-28 there is a soft one.
"One more evening of CI" (2026-08-17) has been overtaken by post #4 (2026-08-27): *"I estimate an
alpha 7 compatible vendordep within the next 2 weeks, hopefully significantly sooner"* — and it is
now explicitly downstream of WPILib publishing alpha-7, which is **tagged (2026-08-26) but not
released**. Two weeks from 2026-08-27 is roughly **2026-09-10**, and it is an estimate for a
**Commands v2** vendordep, which is not a thing we can use. Nothing indicates when the *real* 2027
ChoreoLib (PR #1481's successor, v3-native) lands; it is gated on the 2027 field AprilTag map for
the coordinate-origin flip, which realistically means kickoff-adjacent.

Docs site [choreo.autos](https://choreo.autos/) shows **no 2027 banner or version selector**; the
getting-started page still instructs `ChoreoLib2026.json`. gh-pages last deployed 2026-08-09.

---

## 2. The WPILib 2027 types Choreo must interoperate with

This is the part that is **verified hard fact** today, and it is where the ticket's premise needs
correcting.

### 2.1 `ChassisSpeeds` is gone — it is `ChassisVelocities` [V]

`~/dev/allwpilib/wpimath/src/main/java/org/wpilib/math/kinematics/` contains:

```
ChassisAccelerations.java        SwerveModuleAcceleration.java
ChassisVelocities.java           SwerveModulePosition.java
SwerveDriveKinematics.java       SwerveModuleVelocity.java
```

There is **no `ChassisSpeeds.java` and no `SwerveModuleState.java`**. The renames are:

| 2026 | 2027 |
|---|---|
| `ChassisSpeeds` | `ChassisVelocities` |
| `SwerveModuleState` | `SwerveModuleVelocity` |
| *(new)* | `ChassisAccelerations` |
| *(new)* | `SwerveModuleAcceleration` |

`ChassisVelocities` (`org.wpilib.math.kinematics.ChassisVelocities`):

```java
public double vx;      // m/s,   fwd +
public double vy;      // m/s,   left +
public double omega;   // rad/s, CCW +

public ChassisVelocities(double vx, double vy, double omega)
public ChassisVelocities(LinearVelocity vx, LinearVelocity vy, AngularVelocity omega)
public ChassisVelocities toRobotRelative(Rotation2d robotAngle)
public ChassisVelocities toFieldRelative(Rotation2d robotAngle)
public ChassisVelocities discretize(double dt)
public Twist2d toTwist2d(double dt)
public ChassisVelocities plus/minus/times/div/unaryMinus/interpolate(...)
public static final ChassisVelocitiesStruct struct;
public static final ChassisVelocitiesProto proto;
```

Note `toRobotRelative` / `toFieldRelative` replace the 2025-era static
`fromFieldRelativeSpeeds` factories.

### 2.2 `ChassisAccelerations` [V]

`org.wpilib.math.kinematics.ChassisAccelerations`:

```java
public class ChassisAccelerations
    implements ProtobufSerializable, StructSerializable, Interpolatable<ChassisAccelerations> {

  public double ax;     // m/s²,   fwd +
  public double ay;     // m/s²,   left +
  public double alpha;  // rad/s², CCW +

  public ChassisAccelerations()
  public ChassisAccelerations(double ax, double ay, double alpha)
  public ChassisAccelerations(LinearAcceleration ax, LinearAcceleration ay,
                              AngularAcceleration alpha)

  public ChassisAccelerations toRobotRelative(Rotation2d robotAngle)
  public ChassisAccelerations toFieldRelative(Rotation2d robotAngle)
  public ChassisAccelerations plus/minus/unaryMinus/times/div(...)

  public static final ChassisAccelerationsStruct struct;
  public static final ChassisAccelerationsProto proto;
}
```

### 2.3 Second-order swerve kinematics [V]

`SwerveDriveKinematics` gained a full second-order path:

```java
// velocity (first order)
public SwerveModuleVelocity[] toSwerveModuleVelocities(ChassisVelocities chassisVelocities)
public SwerveModuleVelocity[] toWheelVelocities(ChassisVelocities chassisVelocities)
public ChassisVelocities      toChassisVelocities(SwerveModuleVelocity... moduleVelocities)

// acceleration (second order) — NEW
public SwerveModuleAcceleration[] toSwerveModuleAccelerations(
        ChassisAccelerations chassisAccelerations,
        double angularVelocity,
        Translation2d centerOfRotation)
public SwerveModuleAcceleration[] toSwerveModuleAccelerations(
        ChassisAccelerations chassisAccelerations, double angularVelocity)
public SwerveModuleAcceleration[] toWheelAccelerations(ChassisAccelerations chassisAccelerations)
public ChassisAccelerations       toChassisAccelerations(
        SwerveModuleAcceleration... moduleAccelerations)

public static SwerveModuleVelocity[] desaturateWheelVelocities(...)  // 4 overloads
```

The acceleration inverse kinematics needs the **current angular velocity** as a second argument
(centripetal term). That is a real requirement on our drive base: an accel feedforward path must
supply `omega`, not just the accel vector.

### 2.4 First-party trajectory types — the likely Choreo interchange format [V]

`org.wpilib.math.trajectory` in alpha-6/7:

```
Trajectory.java                              (abstract, generic over TrajectorySample)
TrajectorySample.java
HolonomicSample.java      HolonomicTrajectory.java
DifferentialSample.java   DifferentialTrajectory.java
DrivetrainSplineSample.java  DrivetrainSplineTrajectory.java
DrivetrainSplineTrajectoryGenerator.java  DrivetrainSplineTrajectoryParameterizer.java
```

`HolonomicSample`:

```java
@Json
public class HolonomicSample extends TrajectorySample
    implements StructSerializable, ProtobufSerializable {

  public double time;                     // inherited from TrajectorySample
  @Json.Property("pose")         public Pose2d pose;                        // field-relative
  @Json.Property("velocity")     public ChassisVelocities velocity;         // field-relative
  @Json.Property("acceleration") public ChassisAccelerations acceleration;  // field-relative

  @Json.Creator
  public HolonomicSample(double time, Pose2d pose,
                         ChassisVelocities velocity, ChassisAccelerations acceleration)
  public HolonomicSample(Time time, Pose2d pose,
                         ChassisVelocities velocity, ChassisAccelerations acceleration)

  public static HolonomicSample kinematicInterpolate(
          HolonomicSample start, HolonomicSample end, double t)
  public HolonomicSample transform(Transform2d transform)
  public HolonomicSample relativeTo(Pose2d other)

  public static final HolonomicSampleStruct struct;  // schema:
  //   "double time;Pose2d pose;ChassisVelocities velocity;ChassisAccelerations acceleration"
  public static final HolonomicSampleProto proto;
}
```

`HolonomicTrajectory`:

```java
@Json
public class HolonomicTrajectory extends Trajectory<HolonomicSample> {
  @Json.Creator public HolonomicTrajectory(List<HolonomicSample> samples)
  public HolonomicTrajectory(HolonomicSample[] samples)

  public static HolonomicTrajectory loadFromStream(InputStream stream) throws IOException
  public static HolonomicTrajectory loadFromFile(File file)     throws IOException
  public static HolonomicTrajectory loadFromFile(String filename) throws IOException

  public HolonomicTrajectory transformBy(Transform2d transform)
  public HolonomicTrajectory relativeTo(Pose2d other)
  public HolonomicTrajectory concatenate(Trajectory<HolonomicSample> other)
}
```

Inherited from `Trajectory<SampleType>`:

```java
public final double duration;
public List<SampleType> getSamples();
public SampleType start();
public SampleType end();
public SampleType sampleAt(double time);
public SampleType sampleAt(Time time);
```

Interpolation is **constant-acceleration kinematic**, not linear — `kinematicInterpolate` integrates
`v = v₀ + aΔt` and `x = x₀ + v₀Δt + ½a(Δt)²`. That is exactly the behaviour a Choreo-style optimizer
output wants.

JSON is via `io.avaje.jsonb` (`Jsonb.instance()`), verified in
`wpimath/src/test/java/org/wpilib/math/trajectory/SampleJsonTest.java`.

**Why this matters — and this is now VERIFIED, not inference.** [V]

`grep -ri choreo` over the allwpilib tree returns nothing, so I initially treated the connection as
a guess. It is not. The origin ticket says it outright:

[**allwpilib issue #8160, "[wpimath] Refactor Trajectory class"**](https://github.com/wpilibsuite/allwpilib/issues/8160)
— calcmogul (Tyler Veness), 2025-08-05:

> "Since different trajectory generators often need different data in their samples, **we could
> upstream ChoreoLib's `Trajectory` class and `TrajectorySample` interface/concept.** WPILib can do
> the trajectory interpolation, and vendors can provide their own sample classes. **We could also
> upstream ChoreoLib's differential drive and swerve drive sample classes (without the input
> members)** for a reasonable default."

He links ChoreoLib's actual `SwerveSample.java` / `DifferentialSample.h` as the model. The
alternative he considered: *"We could just remove the trajectory generation API entirely. Pretty
much everyone uses PathPlanner or Choreo instead at this point."*

The design doc ([PR #8161](https://github.com/wpilibsuite/allwpilib/pull/8161), `design-docs/trajectories.md`
— **not merged**; it is absent from our checkout) states the goals verbatim:

> "**Goals:** Define a trajectory API that can represent a wide variety of trajectories, **including
> those generated by Choreo and PathPlanner**."
> "**Non-Goals:** Replace tools like Choreo and PathPlanner. The new Trajectory API is intended to be
> **used by** those tools, not to replace them. **These tools should have their `Trajectory` classes
> extend `Trajectory`** as detailed below."

Implementation PRs, all merged:

| PR | Author | Merged | What |
|---|---|---|---|
| [#8185](https://github.com/wpilibsuite/allwpilib/pull/8185) | zachwaffle4 | 2025-12-09 | `ChassisAccelerations` + per-drivetrain wheel-acceleration types; `Kinematics` gains `toChassisAccelerations`/`toWheelAccelerations` |
| [#8479](https://github.com/wpilibsuite/allwpilib/pull/8479) | calcmogul | 2026-03-06 | "Replace Speeds with Velocities" → `ChassisSpeeds`→`ChassisVelocities`, `SwerveModuleState`→`SwerveModuleVelocity` |
| [#8172](https://github.com/wpilibsuite/allwpilib/pull/8172) | zachwaffle4 | **2026-07-06** | "Rewrite Trajectory API" — sample classes, interpolation, JSON + protobuf |
| [#9078](https://github.com/wpilibsuite/allwpilib/pull/9078) | calcmogul | 2026-07-10 | "Make TrajectorySample only contain time" |

`HolonomicSample` is therefore **ChoreoLib's `SwerveSample` minus the module-force inputs**, exactly
as proposed. This is the strongest possible signal about what to build against.

**Bonus, strategically significant [V]:** WPILib 2027 **vendors Sleipnir — Choreo's own optimizer —
into wpimath** (`wpimath/src/main/native/thirdparty/sleipnir`, `ThirdPartyNotices.txt`,
`upstream_utils/sleipnir.py`), and [PR #8236](https://github.com/wpilibsuite/allwpilib/pull/8236)
(calcmogul, merged 2026-03-30) **exposes it to Java**. Our checkout has
`org.wpilib.math.optimization.{Problem, OCP, Constraints, EqualityConstraints,
InequalityConstraints, SimulatedAnnealing, ProblemJNI}` plus `ocp/` and `solver/` packages.

So WPILib now ships both halves of Choreo's stack — the trajectory container **and** the optimizer
primitives. WPILib is not replacing Choreo; it is standardizing the layer Choreo plugs into.

### 2.5 What was removed that we might have expected [V]

- **`HolonomicDriveController` is gone.** `org.wpilib.math.controller` in alpha-6/7 contains
  `PIDController`, `ProfiledPIDController`, `LTVUnicycleController`, `LTVDifferentialDriveController`,
  `AntiTipping`, the feedforward classes — but **no holonomic/swerve trajectory controller**. We
  write the three-axis controller ourselves (see §6).
- `SmartDashboard`, `Shuffleboard`, `SendableChooser` — all absent. The package
  `org.wpilib.smartdashboard` survives but contains only `Field2d`, `FieldObject2d`, `Mechanism2d`
  and friends.
- `DriverStation.getAlliance()` moved: it is now
  `org.wpilib.driverstation.MatchState.getAlliance()` returning `Optional<Alliance>`, where
  `org.wpilib.driverstation.Alliance` is `enum { RED, BLUE }`.

---

## 3. Commands v3 compatibility

### 3.1 Package and shape [V]

Commands v3 is `org.wpilib.command3` (maven `org.wpilib:commandsv3-java`). It is **coroutine-based**
and shares no types with v2:

```java
@NoDiscard("Commands must be used! Did you mean to fork it or bind it to a trigger?")
public interface Command {
  int DEFAULT_PRIORITY  = 0;
  int LOWEST_PRIORITY   = Integer.MIN_VALUE;
  int HIGHEST_PRIORITY  = Integer.MAX_VALUE;

  void run(Coroutine coroutine);          // the only abstract method
  default void onCancel() {}
  default int priority() { return DEFAULT_PRIORITY; }
  default boolean requires(Mechanism mechanism)
  default boolean conflictsWith(Command other)
  default Command withTimeout(Time timeout)

  static NeedsNameBuilderStage      noRequirements(Consumer<Coroutine> body)
  static NeedsExecutionBuilderStage requiring(Mechanism requirement, Mechanism... rest)
  static ParallelGroupBuilder       parallel(Command... commands)
  static ParallelGroupBuilder       race(Command... commands)
  static SequentialGroupBuilder     sequence(Command... commands)
  static NeedsNameBuilderStage      waitUntil(BooleanSupplier condition)
  static NeedsNameBuilderStage      waitFor(Time duration)
}
```

There is **no `Subsystem`**. The requirement type is `org.wpilib.command3.Mechanism`:

```java
public interface Mechanism {
  default Scheduler getRegisteredScheduler()          // Scheduler.getDefault()
  default String getName()                             // class simple name
  default void setDefaultCommand(Command defaultCommand)
  default Command getDefaultCommand()
  default NeedsNameBuilderStage run(Consumer<Coroutine> commandBody)
  default NeedsNameBuilderStage runRepeatedly(Runnable loopBody)
  default Command idle()
  default Command idleFor(Time duration)
  default List<Command> getRunningCommands()
}
```

`Coroutine` (the cooperative-yield handle) provides `yield()`, `park()`, `wait(Time)`,
`waitUntil(BooleanSupplier[, Time])`, `fork(Command...)`, `await(Command)`, `awaitAll(...)`,
`awaitAny(...)`, `scheduler()`, and a `ForkResult` with `successful()`/`failed()`/`awaitCompletion()`.

### 3.2 Are there v3-native Choreo factories? [V]

**No — on either branch.** Four independent confirmations:

- GitHub code search across the whole Choreo repo: `commands3` → **0 results**; `wpilibj3` → **0
  results**.
- `main` imports `edu.wpi.first.wpilibj2.command.{Command, Commands, Subsystem, CommandScheduler,
  FunctionalCommand, ScheduleCommand}` and `…wpilibj2.command.button.Trigger`; the vendordep pulls
  `edu.wpi.first.wpilibNewCommands`.
- `systemcore-transitional-release` imports `org.wpilib.command2.{Command, Commands, Subsystem}`
  and `org.wpilib.command2.button.Trigger`, with
  `implementation "org.wpilib.commandsv2:commandsv2-java:2027.0.0-alpha-6"`.
  **The 2027 work is a namespace migration, not a Commands v3 port.**
- The maintainer's stated plan is *"we will prioritize a **commands v2** compatible library for the
  offseason"* and *"we intend to offer **two separate Java vendordeps**"* for 2027. The v3 vendordep
  does not exist.

**The important nuance:** `AutoFactory` / `AutoRoutine` / `AutoTrajectory` are unusable from
Commands v3. But the **`choreo.trajectory` package has zero command-framework dependencies** —
`Choreo.loadTrajectory`, `Trajectory.sampleAt`, `SwerveSample` all work under any scheduler. That
split is the whole basis of the plan in §5.

### 3.3 Can we just wrap the v2 commands? — a hazard [V]

Commands v2 still ships in 2027 as `org.wpilib.command2` (maven `org.wpilib.commandsv2:commandsv2-java`),
so ChoreoLib-transitional will compile. **But there is no bridge between the two frameworks.**
`grep -rn "command3" commandsv2/src/main/java` → nothing; `grep -rn "command2" commandsv3/src/main/java`
→ nothing.

Consequence: a v2 `Subsystem` requirement and a v3 `Mechanism` requirement **do not interlock**.
If ChoreoLib's v2 `AutoFactory` drives our swerve through a v2 `Subsystem` while our teleop
commands own the same hardware as a v3 `Mechanism`, the two schedulers will happily run
conflicting commands against the same motors with **no mutual exclusion**. Running both
`CommandScheduler.getInstance().run()` and `Scheduler.getDefault().run()` in `robotPeriodic()` is
technically possible but the resource-ownership guarantee — the whole point of command-based — is
lost across the boundary.

**Therefore: do not adopt ChoreoLib's v2 command factories.** Use only its trajectory loading and
sampling, and drive the robot from our own v3 command. See §4.

### 3.4 The v3-native seam WPILib itself shows [V]

`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/` is WPILib's flagship 2027 example
(`OpModeRobot` + Commands v3 + swerve). Its `SwerveDrive` mechanism contains exactly the seam we
need, against a deliberately-stubbed path follower:

```java
// wpilibjExamples/.../rebuiltcmdv3/mechanisms/SwerveDrive.java
public Command driveFieldRelative(Supplier<ChassisVelocities> velocities) {
  return runRepeatedly(() -> {
        ChassisVelocities velocity = velocities.get().toRobotRelative(getGyroHeading());
        SwerveModuleVelocity[] swerveModuleVelocities =
            kinematics.toSwerveModuleVelocities(velocity);
        frontLeft.setTargetVelocity(swerveModuleVelocities[0]);
        frontRight.setTargetVelocity(swerveModuleVelocities[1]);
        rearLeft.setTargetVelocity(swerveModuleVelocities[2]);
        rearRight.setTargetVelocity(swerveModuleVelocities[3]);
      })
      .named("Drive.DriveSpeeds");
}

public Command followPath(String pathName) {
  return run(coroutine -> {
        PathFollower follower = PathFollower.load(pathName);   // third-party
        coroutine.fork(driveFieldRelative(follower::next));    // background
        coroutine.waitUntil(follower::isDone);                 // exit condition
      })
      .named("Drive.FollowPath[" + pathName + "]");
}
```

And the stub interface, whose javadoc is the clearest statement WPILib makes on this subject:

```java
// wpilibjExamples/.../rebuiltcmdv3/stubs/PathFollower.java
/**
 * A stub interface for an API that could plausibly follow a path. No WPILib code exists for this;
 * if you want to follow paths on a real robot, use a third-party library provided by the FRC
 * programming community.
 */
public interface PathFollower {
  ChassisVelocities next();
  boolean isDone();
  static PathFollower load(String pathName);
}
```

**This two-method interface is our interim seam.** It is the smallest possible surface, it is
WPILib-blessed, and both Choreo and PathPlanner can be made to satisfy it.

---

## 4. The actual ChoreoLib integration contract [V]

Read from source on both branches. I diffed `main` (`764ff38`, 2026-08-09) against
`systemcore-transitional-release` (`05c2779`, 2026-08-17): **the public API shape is identical**
apart from namespace/enum renames (`edu.wpi.first` → `org.wpilib`, `wpilibj2.command` → `command2`,
`Alliance.Red` → `Alliance.RED`, `DriverStation` → `MatchState`/`RobotState`, `HAL.report` →
`HAL.reportUsage`, `AlertType.kError/kWarning` → `HIGH/MEDIUM`).

### 4.1 `AutoFactory` — the real constructors

`choreolib/src/main/java/choreo/auto/AutoFactory.java`:

```java
public <SampleType extends TrajectorySample<SampleType>> AutoFactory(
    Supplier<Pose2d>        poseSupplier,
    Consumer<Pose2d>        resetOdometry,
    Consumer<SampleType>    controller,
    boolean                 useAllianceFlipping,
    Subsystem               driveSubsystem,
    TrajectoryLogger<SampleType> trajectoryLogger)

public <ST extends TrajectorySample<ST>> AutoFactory(
    Supplier<Pose2d>  poseSupplier,
    Consumer<Pose2d>  resetOdometry,
    Consumer<ST>      controller,
    boolean           useAllianceFlipping,
    Subsystem         driveSubsystem)   // logger defaults to (sample, isStart) -> {}
```

**Three corrections to the assumptions in the issue:**

1. **There is no alliance-flip `BooleanSupplier`.** It is a plain `boolean useAllianceFlipping`.
   The alliance itself is read internally and is **not injectable**:
   ```java
   this.allianceCtx = new AllianceContext(useAllianceFlipping, DriverStation::getAlliance);
   ```
   (2026) / `MatchState::getAlliance` (2027 branch). We cannot substitute our own predicate.
2. **The controller is `Consumer<SampleType>`, not a `ChassisSpeeds`/`ChassisVelocities`
   consumer.** ChoreoLib hands us the raw `SwerveSample` and does no control at all. Converting to
   chassis velocities and applying feedback is **entirely our code**.
3. **There is no `AutoBindings` constructor parameter** (that was the ≤2025 signature).
   `AutoBindings` is package-private now; bindings are added with `factory.bind(name, cmd)`.

Non-null is enforced on `poseSupplier`, `resetOdometry`, `controller`, `driveSubsystem`,
`useAllianceFlipping`. `trajectoryLogger` is **not** null-checked.

So the minimum a drive subsystem must supply is **four method references plus itself**:

| # | Parameter | Type | Our method |
|---|---|---|---|
| 1 | pose supplier | `Supplier<Pose2d>` | `drive::getPose` |
| 2 | odometry reset | `Consumer<Pose2d>` | `drive::resetPose` |
| 3 | sample follower | `Consumer<SwerveSample>` | `drive::followTrajectory` |
| 4 | alliance flipping | `boolean` | `true` |
| 5 | requirement | `Subsystem` (v2) | `drive` |

### 4.2 `TrajectoryLogger`

```java
public interface TrajectoryLogger<ST extends TrajectorySample<ST>>
    extends BiConsumer<Trajectory<ST>, Boolean> {}
```

Called as `logger.accept(trajectory, starting)` — `true` on `initialize`, `false` on `end`.
**Undocumented on choreo.autos; source only.** This is the hook for publishing the planned path to
telemetry — there is no built-in NetworkTables trajectory publishing.

### 4.3 `AutoFactory` public methods

```java
public AutoRoutine newRoutine(String name)
public Command warmupCmd()
public Command trajectoryCmd(String trajectoryName)
public Command trajectoryCmd(String trajectoryName, int splitIndex)
public Command trajectoryCmd(String trajectoryName, Function<AutoTrajectory, AutoTrajectory> transform)
public Command trajectoryCmd(String trajectoryName, int splitIndex,
                             Function<AutoTrajectory, AutoTrajectory> transform)
public <ST extends TrajectorySample<ST>> Command trajectoryCmd(Trajectory<ST> trajectory)
public Command resetOdometry(String trajectoryName)
public Command resetOdometry(String trajectoryName, int splitIndex)
public <ST extends TrajectorySample<ST>> Command resetOdometry(Trajectory<ST> trajectory)
public Command resetOdometry(Supplier<Optional<Pose2d>> pose)
public Command resetOdometry(Optional<Pose2d> pose, boolean doFlipForAlliance)
public AutoFactory bind(String name, Command cmd)
public TrajectoryCache cache()
```

The `trajectory(...)` overloads are **package-private**; user code reaches them via
`AutoRoutine.trajectory(...)`. `newRoutine` clears the trajectory cache under
`RobotBase.isSimulation()`, giving trajectory hot-reload in sim.

### 4.4 `AutoRoutine` / `AutoTrajectory`

Both have package-private constructors — obtain via `factory.newRoutine(...)` and
`routine.trajectory(...)`.

```java
// AutoRoutine
public Trigger active(); public Trigger idle(); public void poll(); public EventLoop loop();
public Trigger observe(BooleanSupplier condition); public void reset(); public void kill();
public AutoTrajectory trajectory(String trajectoryName)
public AutoTrajectory trajectory(String trajectoryName, int splitIndex)
public <S extends TrajectorySample<S>> AutoTrajectory trajectory(Trajectory<S> trajectory)
public Trigger anyDone(AutoTrajectory t, AutoTrajectory... rest)
public Trigger anyDoneDelayed(int cyclesToDelay, AutoTrajectory t, AutoTrajectory... rest)
public Trigger anyActive(...); public Trigger allInactive(...);
public Command cmd(); public Command cmd(BooleanSupplier finishCondition);

// AutoTrajectory
public Command cmd(); public Command spawnCmd(); public Command resetOdometry();
public <S extends TrajectorySample<S>> Trajectory<S> getRawTrajectory()
public AutoTrajectory mirrorX(); public AutoTrajectory mirrorY(); public AutoTrajectory rotateAround();
public Optional<Pose2d> getInitialPose(); public Optional<Pose2d> getFinalPose();
public Trigger active(); public Trigger inactive();
public Trigger done(); public Trigger doneDelayed(double seconds);
public Trigger doneFor(double seconds); public Trigger recentlyDone();
public void chain(AutoTrajectory otherTrajectory)
public Trigger atTime(double timeSinceStart); public Trigger atTimeBeforeEnd(double timeBeforeEnd);
public Trigger atTime(String eventName)
public Trigger atPose(Pose2d pose, double toleranceMeters, double toleranceRadians)
public Trigger atPose(String eventName, double toleranceMeters, double toleranceRadians)
public Trigger atTranslation(Translation2d translation, double toleranceMeters)
public Trigger atTranslation(String eventName, double toleranceMeters)
public double[] collectEventTimes(String eventName)
public Pose2d[] collectEventPoses(String eventName)
```

⚠ `doneDelayed`/`doneFor` take **`double seconds`** despite the docs saying `int` cycles — the docs
are stale. `AutoRoutine.anyDoneDelayed` genuinely takes `int cyclesToDelay`.

`AutoTrajectory.cmd()` is a v2 `FunctionalCommand` requiring the drive subsystem, named
`"Trajectory_" + name`. Its `isFinished` is
`activeTimer.get() > trajectory.getTotalTime() || !routine.active() || !allianceCtx.allianceKnownOrIgnored()`.
On a non-interrupted end it applies the **final sample once more** to the controller.

`EventMarker` is `public final double timestamp; public final String event;` plus `offsetBy(double)`.

### 4.5 `Choreo.loadTrajectory`

```java
public static <SampleType extends TrajectorySample<SampleType>>
    Optional<Trajectory<SampleType>> loadTrajectory(String trajectoryName)
public static String[] availableTrajectories()
```

- **Returns `Optional` and throws nothing** for a missing or malformed file. It catches
  `FileNotFoundException`, `JsonSyntaxException` and generic `Exception`, raises a WPILib Alert, and
  returns `Optional.empty()`. Only a null name throws (`NullPointerException`).
- The `.traj` extension is stripped if supplied.
- ⚠ **The cast is unchecked — the sample type is not validated.** Loading a differential file into a
  `Trajectory<SwerveSample>` `ClassCastException`s later, at an arbitrary point. A silent
  `Optional.empty()` plus an unchecked cast is a bad failure mode for a match; our loader wrapper
  should turn both into a loud, early failure.

`Trajectory<SampleType>` public API: `name()`, `samples()`, `splits()`, `events()`,
`getInitialSample(boolean)`, `getFinalSample(boolean)`, `sampleAt(double timestamp, boolean
mirrorForRedAlliance)`, `getInitialPose(boolean)`, `getFinalPose(boolean)`, `getTotalTime()`,
`getPoses()`, `flipped()`, `mirrorX()`, `mirrorY()`, `rotateAround()`, `getEvents(String)`,
`getSplit(int)`.

### 4.6 `SwerveSample` — and the answer on `ChassisAccelerations`

```java
public class SwerveSample implements TrajectorySample<SwerveSample> {
  public final double t, x, y, heading;   // s, m, m, rad
  public final double vx, vy, omega;      // m/s, m/s, rad/s
  public final double ax, ay, alpha;      // m/s², m/s², rad/s²
  private final double[] fx, fy;          // module forces, N — PRIVATE

  public SwerveSample(double t, double x, double y, double heading,
                      double vx, double vy, double omega,
                      double ax, double ay, double alpha,
                      double[] moduleForcesX, double[] moduleForcesY)

  public double[] moduleForcesX();   // null-safe; {0,0,0,0} if unset. Order [FL, FR, BL, BR]
  public double[] moduleForcesY();
  public double getTimestamp();
  public Pose2d getPose();
  public ChassisSpeeds getChassisSpeeds();      // main
  public ChassisVelocities getChassisSpeeds();  // systemcore-transitional-release (name unchanged!)
  public SwerveSample interpolate(SwerveSample endValue, double t);   // quadratic, uses ax/ay/alpha
  public SwerveSample offsetBy(double); flipped(); mirrorX(); mirrorY(); rotateAround();
  public static final Struct<SwerveSample> struct;  // 18 doubles
}
```

Note the constructor *parameters* are named `moduleForcesX`/`moduleForcesY` but store into private
`fx`/`fy` — there are **no public `moduleForcesX`/`moduleForcesY` fields**, only accessors.

**Q3 answered — does ChoreoLib 2027 use `ChassisAccelerations`? NO.** [V]
GitHub code search `repo:SleipnirGroup/Choreo ChassisAccelerations` → **0 results** on either
branch. ChoreoLib exposes acceleration as **three loose public `double` fields (`ax`, `ay`,
`alpha`)** and has no aggregate acceleration type. The only 2027-related type change in its API is
`getChassisSpeeds()`'s **return type** shifting from `ChassisSpeeds` to `ChassisVelocities` on the
systemcore branch — the method name is unchanged.

So: **the accelerations are available for feedforward, but ChoreoLib will not hand them to us as a
`ChassisAccelerations`, and it does not consume one either.** Adapting `ax/ay/alpha` →
`new ChassisAccelerations(ax, ay, alpha)` is a one-line conversion on our side, and that conversion
is exactly the seam that makes our drive base version-independent.

`DifferentialSample` public final fields: `t, x, y, heading, vl, vr, omega, al, ar, alpha, fl, fr`.
[U] Its `struct.getSize()` returns `kSizeDouble * 10` while it packs 12 doubles — looks like a latent
serialization bug. Irrelevant to us (we are swerve) but worth knowing.

### 4.7 The official follower example — verbatim from the docs

From [choreo.autos/choreolib/getting-started](https://choreo.autos/choreolib/getting-started/):

```java title="Drive.java"
public class Drive extends SubsystemBase {
    private final PIDController xController = new PIDController(10.0, 0.0, 0.0);
    private final PIDController yController = new PIDController(10.0, 0.0, 0.0);
    private final PIDController headingController = new PIDController(7.5, 0.0, 0.0);

    public Drive() {
        headingController.enableContinuousInput(-Math.PI, Math.PI);
    }

    public void followTrajectory(SwerveSample sample) {
        Pose2d pose = getPose();
        ChassisSpeeds speeds = new ChassisSpeeds(
            sample.vx + xController.calculate(pose.getX(), sample.x),
            sample.vy + yController.calculate(pose.getY(), sample.y),
            sample.omega + headingController.calculate(pose.getRotation().getRadians(), sample.heading)
        );
        driveFieldRelative(speeds);
    }
}
```

From [choreo.autos/choreolib/auto-factory](https://choreo.autos/choreolib/auto-factory/):

```java title="Robot.java"
autoFactory = new AutoFactory(
    driveSubsystem::getPose,           // pose supplier
    driveSubsystem::resetOdometry,     // odometry reset
    driveSubsystem::followTrajectory,  // the sample follower
    true,                              // alliance flipping
    driveSubsystem                     // the drive subsystem
);
```

Bindings: `autoFactory.bind("intake", intake.intake()).bind("score", scoring.score());`
Warmup: `CommandScheduler.getInstance().schedule(autoFactory.warmupCmd());`

**Note the official swerve example ignores `ax`/`ay`/`alpha` and the module forces entirely.** The
docs explicitly punt wheel-force feedforward to the user, pointing at CTRE's
`SwerveRequest.ApplyFieldSpeeds.withWheelForceFeedforwardsX/Y` and `CommandSwerveDrivetrain.java`.
Since we are on **REV SPARK, not CTRE**, there is no vendor-provided wheel-force path for us — the
module-force feedforward would be ours to write from scratch. Treat it as a stretch goal, not a v1
requirement.

The published Javadoc at <https://choreo.autos/api/choreolib/java/> is live and matches `main`
(packages `choreo`, `choreo.auto`, `choreo.trajectory`, `choreo.util`), i.e. it currently serves the
**2026** API.

---

## 5. What our swerve must provide — the durable contract

Because there is no 2027 ChoreoLib to bind against, and because the one that ships first is
Commands v2 (which we must not bind to — §3.3), the contract below is stated in **WPILib 2027
types**, sized so a ChoreoLib `AutoFactory` can be adapted onto it later without redesigning the
drive base. Everything referenced here is **[V]** present in alpha-6/7.

The mapping is direct: ChoreoLib's five constructor arguments (§4.1) are items 1, 2, 3+the follower
loop, and the requirement token below. The only piece we *cannot* satisfy through ChoreoLib is the
alliance-flip predicate, which it hard-wires — one more reason to own the follower ourselves.

### 5.1 Required surface on the drive mechanism

```java
public class SwerveDrive implements Mechanism {

  // --- localisation (read) -------------------------------------------------
  Pose2d                  getPose();                 // field-relative, from pose estimator
  Rotation2d              getGyroHeading();          // Pigeon2, CCW+
  SwerveModulePosition[]  getModulePositions();      // odometry input
  SwerveModuleVelocity[]  getModuleVelocities();     // telemetry / measured velocity
  ChassisVelocities       getMeasuredVelocities();   // kinematics.toChassisVelocities(...)

  // --- localisation (write) ------------------------------------------------
  void resetPose(Pose2d pose);                       // seed odometry at trajectory start

  // --- actuation -----------------------------------------------------------
  void driveRobotRelative(ChassisVelocities robotRelative);
  void driveRobotRelative(ChassisVelocities robotRelative,
                          ChassisAccelerations robotRelativeAccel);   // FF path

  // --- geometry ------------------------------------------------------------
  SwerveDriveKinematics getKinematics();
  double getMaxLinearVelocity();   // m/s   — for desaturateWheelVelocities
  double getMaxAngularVelocity();  // rad/s
}
```

As supplier types, which is the shape every path library actually asks for:

| Role | Java type |
|---|---|
| Pose supplier | `Supplier<Pose2d>` |
| Measured-velocity supplier | `Supplier<ChassisVelocities>` |
| Robot-relative velocity consumer | `Consumer<ChassisVelocities>` |
| Velocity + accel consumer (FF) | `BiConsumer<ChassisVelocities, ChassisAccelerations>` |
| Pose reset | `Consumer<Pose2d>` |
| Alliance-flip predicate | `BooleanSupplier` |
| Requirement token | `Mechanism` (the subsystem itself) |

The alliance-flip predicate in 2027 terms:

```java
BooleanSupplier shouldFlip =
    () -> MatchState.getAlliance().orElse(Alliance.BLUE) == Alliance.RED;
```

⚠ **[V]** For the transitional release, coordinates remain **blue-corner origin**, so a flip is
still required. The maintainer states the switch to **field-center origin** happens "when the 2027
field AprilTag map is released" — at which point the flip becomes a 180° rotation about the origin
rather than a mirror, and `shouldFlip` semantics change. Keep the predicate behind one method.

### 5.2 The follower loop (what we write once)

```java
// Three PID controllers: position error (m, rad) -> velocity correction (m/s, rad/s).
// Matches the shape WPILib's own rebuiltcmdv3 SwerveDrive uses.
private final PIDController xController       = new PIDController(kPx, 0, 0);
private final PIDController yController       = new PIDController(kPy, 0, 0);
private final PIDController headingController = new PIDController(kPt, 0, 0);
// headingController.enableContinuousInput(-Math.PI, Math.PI);

/** Feedforward from the sample + feedback on pose error. Returns FIELD-relative velocities. */
ChassisVelocities followSample(HolonomicSample sample, Pose2d currentPose) {
  return new ChassisVelocities(
      sample.velocity.vx + xController.calculate(currentPose.getX(), sample.pose.getX()),
      sample.velocity.vy + yController.calculate(currentPose.getY(), sample.pose.getY()),
      sample.velocity.omega
          + headingController.calculate(currentPose.getRotation().getRadians(),
                                        sample.pose.getRotation().getRadians()));
}
```

The accel term is separate and optional:

```java
// sample.acceleration is FIELD-relative; convert before handing to kinematics
ChassisAccelerations robotAccel = sample.acceleration.toRobotRelative(currentPose.getRotation());
SwerveModuleAcceleration[] moduleAccels =
    kinematics.toSwerveModuleAccelerations(robotAccel, measuredOmega);
// -> torque/voltage feedforward per module
```

### 5.3 Trajectory-following command, v3-native

```java
// org.wpilib.system.Timer; org.wpilib.math.trajectory.{HolonomicTrajectory, HolonomicSample}
public Command followTrajectory(String name) {
  return run(coroutine -> {
        HolonomicTrajectory traj = TrajectoryLoader.load(name);   // ours; see §5
        Timer timer = new Timer();
        timer.start();
        while (timer.get() < traj.duration) {
          HolonomicSample sample = traj.sampleAt(timer.get());
          ChassisVelocities fieldRelative = followSample(sample, getPose());
          driveRobotRelative(fieldRelative.toRobotRelative(getGyroHeading()));
          coroutine.yield();            // MANDATORY — v3 is cooperative
        }
        driveRobotRelative(new ChassisVelocities());
      })
      .named("Drive.Follow[" + name + "]");
}
```

⚠ `coroutine.yield()` in every loop is not optional — omitting it starves the entire robot program.
`Command.run(Coroutine)`'s javadoc says so explicitly.

### 5.4 What is NOT determined yet [U]

The ChoreoLib-2027 `AutoFactory` constructor signature. §4.1 gives the current one exactly, and it
is stable across `main` and the systemcore branch — but PR #1481 **removes `AutoRoutine` and
`AutoChooser` entirely**, the requirement type must move from `Subsystem` (v2) to `Mechanism` (v3),
and `SwerveSample` may be replaced by `HolonomicSample`. **Do not hard-code against the 2026
signature.** The supplier/consumer set in §5.1 is what survives across all plausible versions.

---

## 6. Authoring, storing and loading trajectories

### 6.1 Today (2026 ChoreoLib) [V]

**Location is hard-coded in `Choreo.java`:**

```java
private static File CHOREO_DIR = new File(Filesystem.getDeployDirectory(), "choreo");
private static final String TRAJECTORY_FILE_EXTENSION = ".traj";
```

So Java/C++ read from **`src/main/deploy/choreo/<name>.traj`**. Per
[choreo.autos/usage/saving](https://choreo.autos/usage/saving/): *"Choreo has 2 different kinds of
files: a `.chor` file which stores general configs for your project, and multiple `.traj` files which
store individual path information… Choreo generates and updates `.traj` files in the same directory
that your `.chor` file is stored in."* Their worked example is
`…/src/main/deploy/choreo/ChoreoProject.chor`. **Both file kinds live in the same directory.**

**Schema versions [V]:** `.traj` → `TRAJ_SCHEMA_VERSION = 3` (`choreo/util/TrajSchemaVersion.java`,
`src-core/src/spec/traj_schema_version.rs`). `.chor` → `PROJECT_SCHEMA_VERSION = 2`.
`Choreo.loadTrajectoryString` **hard-fails with a `RuntimeException` if `version != 3`** — so a
schema bump is a loud failure, unlike a missing file.

**`.traj` top-level JSON keys** (`src-core/src/spec/trajectory.rs`, `TrajectoryFile`): `name`,
`version`, `snapshot`, `params`, `trajectory`, `events`. Inside `trajectory`: `config`, `sampleType`
(`"Swerve"` | `"Differential"`), `waypoints`, `samples`, `splits`.

**Swerve sample JSON keys:** `t, x, y, heading, vx, vy, omega, ax, ay, alpha, fx[4], fy[4]` — values
rounded to 5 decimal places (good: diffs stay small and reviewable).

**Event markers** deserialize from `{ name, from: { targetTimestamp, offset: { val } } }`;
effective timestamp = `targetTimestamp + offset`. Markers with a negative timestamp or an empty name
are silently dropped at load.

**Code generation:** if enabled, trajectory names must be valid Java identifiers (letters, digits,
`_`, no leading digit). It emits `ChoreoVars.java` / `ChoreoTraj.java` with
`StationToReef4.asAutoTraj(routine)`, `initialPoseBlue()`, `endPoseBlue()`, `totalTimeSecs()` and an
`ALL_TRAJECTORIES` map. **These generated files do not depend on ChoreoLib** — which makes code
generation a genuinely useful interim option for us (see takeaway 10).

Maintainer confirms the transitional GUI is "the same as 2026.0.3 except for the import paths used
in Java code generation" — **the on-disk format is unchanged for the transitional release.**

### 6.2 What changes for 2027 [U, but well-founded]

The trajectory JSON will very likely become the WPILib `HolonomicTrajectory` shape — a list of
`{time, pose, velocity{vx,vy,omega}, acceleration{ax,ay,alpha}}` objects — since (a) the maintainer
said the API is blocked on a standardized Sample class and (b) that class now exists with an
avaje-jsonb round-trip and a `loadFromFile(String)` static.

### 6.3 Implication for our project layout — config-as-code

Put the seam in *our* code, not in ChoreoLib's:

```
src/main/deploy/
  trajectories/            # committed .traj / .json — reviewable diffs
    <name>.traj
  choreo/
    <project>.chor         # the GUI project file, also committed
```

```java
public final class TrajectoryLoader {
  private static final Path DIR =
      Filesystem.getDeployDirectory().toPath().resolve("trajectories");

  public static HolonomicTrajectory load(String name) { /* cache + loadFromFile */ }
}
```

`org.wpilib.util.Filesystem.getDeployDirectory()` is **[V]** present in 2027. One class to change
when ChoreoLib ships; nothing else in the robot code names Choreo.

**Config-as-code note:** `.traj` files are generated artifacts of the `.chor`. Commit both, and
treat the `.chor` as the source of truth — a regenerated `.traj` should be a reviewable diff, so
the optimizer's output changing is visible in PR review rather than silent.

### 6.4 The coordinate origin, and what our flip must be today [V]

Added by the 2026-08-28 re-check, because it is the half of
[#78](https://github.com/Drew-Robotics/2027beta/issues/78) that has a robot on the other end of it.

**Field-centre is where this is going, and nothing has shipped in it yet.** Every artifact that
exists today — WPILib's field module, the field layouts, Choreo's editor, the `.traj` schema — still
says blue-corner. The confirmation that it moves is quoted further down and is not on the public
record.

- **WPILib.** `~/dev/allwpilib/fields/src/main/java/org/wpilib/fields/Field.java:32-33` — *"Pose3ds
  in the JSON are measured using the normal FRC coordinate system, NWU with the origin at the
  bottom-right corner of the blue alliance wall."* `OriginPosition` (`:43-48`) still offers exactly
  `BLUE_ALLIANCE_WALL_RIGHT_SIDE` and `RED_ALLIANCE_WALL_RIGHT_SIDE`; there is no field-centre
  member and no third enumerator. Twelve commits landed upstream between 2026-08-24 and 2026-08-27
  and **none of them touch this**.
- **The field map.** `git ls-files fields` lists FRC layouts through `2026-rebuilt-welded.json`;
  `fields/src/generate/fields.json` names `"defaultField": "FRC_2026_REBUILT_WELDED"`. **There is no
  2027 field or AprilTag layout in the tree.** That is the exact gate the Choreo maintainer named.
- **The Choreo GUI.** `src/components/field/svg/fields/FieldDimensions.tsx` on **both** `main` and
  `systemcore-transitional-release` is `FIELD_LENGTH = 16.541 / FIELD_WIDTH = 8.0692`, and
  `JSONFieldImage.tsx`'s `defaultFieldJSON` declares **`"origin-fraction": [0, 0]`** against
  `field-size: [FIELD_LENGTH, FIELD_WIDTH]`. The editor's canvas runs `0 → L` by `0 → W` with the
  origin nailed to a corner.
- **The `.traj` schema.** `TRAJ_SCHEMA_VERSION` is still **3** in all four places that hold it
  (`choreolib/src/main/java/choreo/util/TrajSchemaVersion.java` and
  `src-core/src/spec/traj_schema_version.rs`, on `main` and on the transitional branch). Our
  `TrajectoryLoader` hard-fail on `version != 3` is therefore **still the tripwire it was built to
  be, and it has not tripped**.

There is one encouraging detail: `origin-fraction` is already a *field* of the GUI's field JSON, not
a constant. A field-centre field ships as `"origin-fraction": [0.5, 0.5]`, so on Choreo's side the
change is data, not code — which is consistent with it being gated on the map rather than on
engineering. [U] on whether that is the mechanism they will actually use.

**Field-centre is confirmed — and it is confirmed off the public record.** WPILib Discord
`#systemcore`, **2026-04-11**, from a screenshot Drew supplied on 2026-08-28:

> **crueter | 4028A/6032M:** Wait, field-center-origin is becoming a thing?
> **Peter | WPILib:** yes

Peter Johnson is WPILib's lead and tagged `v2027.0.0-alpha-7` himself, so this is the answer from
the party that owns it. **[source]**

Everything above and in the [re-check](#re-check-2026-08-28) that reports *no WPILib-side tracking*
therefore describes the **public** record only: there is no issue, no PR, no milestone entry and no
docs branch, and on this evidence there need not be one before the change lands. Anyone re-running
that search will find silence and **must not read it as doubt**.

Two messages earlier in the same exchange, the part that is **not** settled:

> **JS | 6995A | Choreo:** Will previous fields be retroactively converted to field-center-origin?
> **Peter | WPILib:** hmm. probably a good idea? I think?

Four months old, with nothing since. So the destination is fixed and **the fate of the 2026 layouts
is open**, which is what decides how the conversion below is written. [U]

**So the flip and the paths are in different frames, and the paths are the half that is behind.** `FieldConstants.flip(Pose2d)` negates x and
y — a 180° rotation about a **field-centre** origin — while `StraightAhead.traj` (x 2.0–5.0,
y 4.0) and `SweepLeft.traj` (x 2.0–5.5, y 2.0–5.5) are drawn in Choreo's **blue-corner** frame, as
their `params.waypoints` confirm (`{"x": {"exp": "2.0 m", "val": 2.0}, "y": {"exp": "2.0 m", …}}`).
On red the flip sends both off the field into the third quadrant.

**Regenerating cannot fix this, and that is the deciding fact.** The waypoints live in each
`.traj`'s own `params` block, not in `2027beta.chor` — the `.chor` holds only `config` (drivetrain,
mass, bumpers) and `variables`. Regenerating re-optimises the same corner-frame waypoints and emits
corner-frame samples. Moving to field-centre by hand means subtracting `(L/2, W/2)` from every
waypoint *and* every sample, which puts the paths at negative coordinates — **outside the GUI's
`0 → L` canvas**, so the `.chor` project stops being editable in Choreo. The frames have to be
reconciled where the file is **read**, not where it is authored.

**Recommendation: go field-centre now, and convert at the two boundaries.**

An earlier draft of this section recommended inverting the flip to a corner-origin rotation,
reasoning that the public record said blue-corner and nothing else confirmed the move. The Discord
quote retires that reasoning. The destination is settled, `FieldConstants` and ADR 0011 already
target it, and the cheaper change is to move the **inputs** into the frame the robot already thinks
in rather than to move the robot into the frame its inputs happen to arrive in.

- **Paths.** `TrajectoryLoader` subtracts `(L / 2, W / 2)` from each sample pose as it reshapes a
  `.traj`. It is already the only class that names Choreo and already the one that hard-fails on a
  schema version. Velocities and accelerations are untouched — a pure translation does not rotate a
  vector.
- **Tags, when vision lands.**
  `Field.setOrigin(new Pose3d(new Translation3d(L / 2, W / 2, 0), Rotation3d.kZero))`.
  `getTagPose` returns `tag.getPose().relativeTo(m_origin)`
  (`fields/src/main/java/org/wpilib/fields/Field.java:394`), so the stock layout needs no fork and
  no hand-edited tag poses; `OriginPosition`'s two members are named presets over that same setter
  (`:349-357`). Nothing in `src/` reads a layout today. **[V]**
- **`FieldConstants.flip` does not change.** ADR 0011's rigid transform was right, and stays a
  rotation about the origin.
- **At kickoff both conversions delete**, and until then the robot lives in the frame it will ship
  in — which is where the field-centre traps are found on a bench in September rather than in
  week 1.

The cost is that every tool in the loop is corner-frame: Choreo's canvas runs `0 → L`, and a robot
at the blue wall logs `x = -8.27`. Whether AdvantageScope's field view can be told about a centre
origin is **not established here** and is worth ten minutes with a log before committing. [U]

**Neither conversion may be applied twice, and nothing upstream will say if it is.** The layout JSON
declares no origin at all — its keys are `name`, `season`, `game`, `field-image`, `field-dimensions`,
`program`, `field-tags` — so a retroactive conversion of the 2026 layout moves every tag by 8.27 m
with no schema change and no error. `.traj` at least has the version-3 tripwire; the layout has
nothing. The discriminator is one line and belongs wherever the layout is read: **in a corner-frame
layout every tag has x >= 0; in a centre-frame one roughly half are negative.** Assert it and fail
loudly rather than adapt silently. **[V]** for the absence of an origin key, [U] for whether the
conversion happens.

**Where the numbers come from.** Each conversion uses the dimensions of the field **its own data was
drawn against**, not "the current field": the paths use Choreo's `FieldDimensions`
(`L = 16.541`, `W = 8.0692`), the tags use `field-dimensions` from the layout JSON they came from
(`16.541 x 8.069` for `2026-rebuilt-welded`). The two agree to rounding but they are two sources,
and reading each from its own is what keeps them right when only one of them moves. **No 2027 field
dimension is published anywhere** — the tree has no 2027 layout and there is no 2027 docs branch —
so these are 2026 numbers standing in until the 2027 map ships. [V]

**ADR 0011's corner-origin row is wrong, and correcting it still matters.** The ADR and #78 both
write the corner flip as *"`x -> length - x`, heading `-> pi - heading`"* and conclude it costs a
hand-written per-sample remap. That is a **mirror**, which is the right flip only for a
mirror-symmetric field, and it is not what ChoreoLib does: on `main`,
`ChoreoAllianceFlipUtil.Flipper.FRC_CURRENT = rotatedAround(FIELD_LENGTH, FIELD_WIDTH)`, composed
from `MirroredX` and `MirroredY`. We are not taking the corner path, but that row is part of why
field-centre looked strictly simpler than it is, and a future reader reaching for the corner form
should get the rotation rather than the mirror:

| | field-centre (the code, and the destination) | blue-corner, stated correctly |
|---|---|---|
| x | `-x` | `L - x` |
| y | `-y` | `W - y` |
| heading | `theta + pi` | `theta + pi` — **unchanged** |
| vx, vy, omega | `-vx, -vy, omega` | **unchanged** |
| ax, ay, alpha | `-ax, -ay, alpha` | **unchanged** |

Both forms are their own inverse (`L - (L - x) = x`), so `flipAndMirrorIfNeeded`'s comment holds either way.

---

## 7. Simulation

### Verified

- WPILib 2027 keeps full desktop simulation; `TimedRobot`/`OpModeRobot` both run under `simulateJava`.
- `org.wpilib.smartdashboard.Field2d` survives and is a `TelemetryLoggable`, and
  `FieldObject2d.setTrajectory(Trajectory<SampleType>)` accepts any
  `Trajectory<? extends HolonomicSample>` **[V]**. That is the visualization path for a planned
  trajectory in AdvantageScope/Elastic.
- `HolonomicSample` has a struct schema
  (`"double time;Pose2d pose;ChassisVelocities velocity;ChassisAccelerations acceleration"`), so a
  sampled setpoint can be logged directly with
  `Telemetry.log("Auto/Setpoint", sample, HolonomicSample.struct)` and diffed against measured pose
  in AdvantageScope. This fits our explicit-telemetry decision with no extra machinery.

### What ChoreoLib itself offers in sim [V]

The docs say **almost nothing**. Grepping all of `docs/**/*.md` for "simulat"/"AdvantageScope"
returns exactly one hit, in `getting-started.md`, and it is about alerts, not paths:

> "Choreo primarily uses the WPILib Alerts API to provide users with internal warnings, errors or
> information. These alerts can be found under the `SmartDashboard/ChoreoAlert` section within
> networktables. To visualize these alerts in a dashboard such as AdvantageScope simply drag the
> `ChoreoAlert` group outwards onto the 'discrete fields' section…"

⚠ That path is **`SmartDashboard/ChoreoAlert` in NetworkTables — and 2027 moved alerts off
NetworkTables to the Driver Station**, which is precisely the breakage the maintainer flagged.

Undocumented but present in source:

- `AutoFactory.newRoutine(String)` clears the trajectory cache when `RobotBase.isSimulation()` — a
  form of trajectory **hot-reloading** in sim. Genuinely useful; worth reimplementing in our loader.
- `AutoChooser.selectedCommand()` regenerates in sim while the selection is still the do-nothing
  default.
- **There is no built-in NetworkTables trajectory publishing.** Feeding AdvantageScope a path is
  what `TrajectoryLogger<ST>` is for — you publish `Trajectory.getPoses()` (a `Pose2d[]`) yourself.
  `SwerveSample`/`DifferentialSample` are `StructSerializable` with a `.struct` for NT logging.

Since we are doing explicit telemetry anyway, this is not a loss: we publish the planned
`Pose2d[]` and the per-cycle setpoint ourselves, which is strictly more visible than what ChoreoLib
would do for us.

### The blocker [V]

From the maintainer, 2026-08-17: *"Simgui does not show alerts and **simulation does not work with
the real 2027 Driver Station currently**."* So sim in the current alpha is usable for math/pose
verification but the DS-integrated parts (including opmode selection, §8) are not fully exercisable
in sim yet.

**Practical answer:** yes, we can validate autonomous routines without hardware — but validate the
*follower math* (pose vs. setpoint traces), not the *match-time selection flow*, until the DS/sim
story settles.

---

## 8. Selecting an auto at match time

SmartDashboard, Shuffleboard, PathWeaver, RobotBuilder **and `SendableChooser`** are all deleted.
`find . -name "SendableChooser.java"` on `main` → **no results**. There are three first-party
replacements, and they imply different robot base classes.

### 8.1 Dashboard support matrix — the deciding fact [V]

| Dashboard | Reads `/Tunables` `Selectable` | Can write the **string** selection | Shipped? |
|---|---|---|---|
| **Glass / SimGUI** | **Yes** — `NTSelectableModel`, `.type == "Selectable"` | **Yes** — publishes `selected/tune`, retained | On `main`; ships with alpha-7 |
| **Elastic** | Implemented in [PR #366](https://github.com/Gold872/elastic_dashboard/pull/366) | Yes (in that PR) | ❌ **No** — PR open **and conflicted** (opened 2026-05-07, last touched 2026-07-17). Latest release is **v2027.0.0-alpha8, 2026-05-08** — three months *before* the Tunables merge |
| **AdvantageScope** | ❌ No | ❌ **No** | v27.0.0-alpha-6 (2026-08-23) is current, but its tuner is **number/boolean only** |
| **FIRST Driver Station** | N/A — has its own opmode selector | Yes, natively | Ships with alpha-5+ |

⚠ **AdvantageScope cannot select an auto in 2027.** `src/hub/dataSources/LiveDataTuner.ts` declares
`publish(key: string, value: number | boolean): void`, and `nt4/NT4Tuner.ts` gates on
`LoggableType.Number || LoggableType.Boolean` — and returns `false` from `hasTunableFields()` for
`NT4Mode.Systemcore` and `NT4Mode.DriverStation` outright. A `Selectable`'s `selected/tune` is a
**string**. There is no `Selectable` implementation in the repo. AdvantageScope has also never had a
chooser widget — that was always Shuffleboard/Elastic/Glass territory.

**This settles the recommendation.** Of the two dashboards that survive into 2027, one *cannot*
drive a chooser and the other *has not shipped* the support. Option A is not viable at match time
today.

### Option A — `Selectable<Command>` published as a Tunable (dashboard-driven) [V]

`org.wpilib.tunable.Selectable<V>` is the direct `SendableChooser` replacement:

```java
public final class Selectable<V> implements ComplexTunable {
  public Selectable()
  public void add(String name, V object)
  public void addDefault(String name, V object)
  public void setDefault(String name)
  public void remove(String name)
  public void clear()
  public V getSelected()          // falls back to default, else null
}
```

Plus `public void onChange(Consumer<V> listener)` (single listener; a second call replaces it).

The NT wire format, verified from `Selectable.publishTunable` and `RobotBase`:

```java
// Selectable.java
@Override public void publishTunable(TunableTable table) {
  table.publish("default",  m_defaultChoice);   // String,   IMMUTABLE
  table.publish("options",  m_options);         // String[], IMMUTABLE
  table.publish("selected", m_selected);        // String,   ROBUST + mutable  <- dashboard writes this
}
@Override public String getTunableType() { return "Selectable"; }

// RobotBase.java:237 — the root NT prefix
TunableRegistry.registerBackend("", new NetworkTablesTunableBackend(inst, "/Tunables"));
```

⚠ **`selected` is `ROBUST`, which splits it into two topics.** From `tunables/doc/tunables.md`:
*"It publishes non-robust tunables to `/Tunables/<path>` and **robust tunables as separate
`/Tunables/<path>/value` and `/Tunables/<path>/tune` topics**."* So `Tunables.publish("Autonomous",
chooser)` actually occupies:

| NT topic | Type | Direction |
|---|---|---|
| `/Tunables/Autonomous/default` | `string` | robot → dashboard (read-only) |
| `/Tunables/Autonomous/options` | `string[]` | robot → dashboard (read-only) |
| **`/Tunables/Autonomous/selected/tune`** | `string` | **dashboard writes here** (retained) |
| `/Tunables/Autonomous/selected/value` | `string` | robot echoes the applied selection |

with `.type == "Selectable"` for widget discovery.

Verified against the reference consumer, `glass/src/libnt/native/cpp/NTSelectable.cpp`, which
subscribes `{path}/selected/value` and publishes `{path}/selected/tune` with `{"retained": true}`.

⚠ **This is a completely new schema.** The old `SendableChooser` published to
`/SmartDashboard/<name>` with type `"String Chooser"`. Every dashboard needs **new explicit
support**. Current state — see §8.1 — is that **only Glass has it**.

WPILib's own `hatchbotcmdv3` example (`TimedRobot` + Commands v3) uses exactly this:

```java
private final Selectable<Command> autonomousChooser = new Selectable<>();
...
autonomousChooser.addDefault("Simple Auto", Autos.simpleAuto(robotDrive));
autonomousChooser.add("Complex Auto", Autos.complexAuto(robotDrive, hatchMechanism));
Tunables.publish("Autonomous", autonomousChooser);
...
@Override public void autonomousInit() {
  Command autonomousCommand = autonomousChooser.getSelected();
  if (autonomousCommand != null) {
    Scheduler.getDefault().schedule(autonomousCommand);
  }
}
```

**Compatible with our locked `TimedRobot` + Commands v3 decision.** Requires a dashboard (Elastic or
AdvantageScope) that can *write* the `selected` NT entry.

### Option B — opmodes, selected on the Driver Station itself [V]

WPILib 2027 adds an FTC-style opmode system (`design-docs/opmodes.md`). Autonomous routines are
annotated classes, auto-registered, and **the selection list appears in the Driver Station
application — no dashboard involved**:

```java
@Autonomous(name = "My Auto", group = "Group 1")
public class MyAuto extends PeriodicOpMode {
  public MyAuto(Robot robot) { ... }
  @Override public void periodic() { ... }
}
```

```java
package org.wpilib.opmode;
public @interface Autonomous {
  String name()            default "";   // must be unique; shown in the DS
  String group()           default "";
  String description()     default "";
  String textColor()       default "";
  String backgroundColor() default "";
}
```

Lifecycle (from `OpMode`'s javadoc): constructed when selected on the DS → `disabledPeriodic()`
while disabled → `start()` on enable → `periodic()` at `OpModeRobot.getPeriod()` → `end()` then
`close()` on disable or reselection; **the object is never reused.**

`design-docs/opmodes.md` is explicit that this is the **intended** path for autos:

> "**Selection of autonomous opmodes is integrated into the DS instead of being performed by the
> dashboard.**"
> "DS provides drop-down selector(s) for the user-defined opmodes… For match mode or when
> FMS-attached, **two drop downs are provided** (one for auto opmode selection and one for teleop
> opmode selection). The drop-down selector provides grouped categories as specified by the robot
> program."

Protocol [V]: `HAL_ControlWord` becomes 64-bit, and the selected opmode travels as a **56-bit hash
of the opmode name in every UDP DS packet**, sent even while disabled. The opmode list goes
robot → DS over the tagged TCP link. So selection is **live before enable, with no dashboard and no
NetworkTables involved** — strictly more robust at a match than any dashboard widget.

This is what the flagship `rebuiltcmdv3` example uses, and it is what Choreo's roadmap explicitly
targets: *"opmodes and the Driver Station autonomous opmode selector will replace AutoChooser and
AutoRoutine."*

An open design question is still recorded in that doc: *"FRC `SendableChooser` has a 'default'
option set by robot code. Do we want something similar here or should it be 100% DS driven?"*

**A third mechanism also exists** [V]: `org.wpilib.driverstation.DSGamepadChooser`
([PR #9048](https://github.com/wpilibsuite/allwpilib/pull/9048), ThadHouse, merged 2026-07-07) —
D-pad up/down picks a selectable, left/right changes the option, rendered over
`DriverStationDisplay`. Built for FTC, but usable in FRC and a decent no-dashboard fallback. There
is a `dsgamepadchooser` example in `wpilibjExamples`.

**Catch:** `OpModeRobot extends RobotBase` — it is a **sibling** of `TimedRobot`
(`TimedRobot extends IterativeRobotBase extends RobotBase`), not a subclass. Choosing opmodes as
the primary structure means **not** extending `TimedRobot`.

### Option C — the hybrid, and my recommendation [V]

The DS opmode registration API is **static on `RobotState`**, not private to `OpModeRobot`:

```java
package org.wpilib.driverstation;
public static long   RobotState.addOpMode(RobotMode mode, String name)
public static long   RobotState.addOpMode(RobotMode mode, String name, String group)
public static long   RobotState.addOpMode(RobotMode mode, String name, String group,
                                          String description)
public static long   RobotState.addOpMode(RobotMode mode, String name, String group,
                                          String description, Color textColor,
                                          Color backgroundColor)
public static long   RobotState.removeOpMode(RobotMode mode, String name)
public static void   RobotState.publishOpModes()
public static void   RobotState.clearOpModes()
public static long   RobotState.getOpModeId()
public static String RobotState.getOpMode()
```

So a **`TimedRobot` can publish its auto routine names to the Driver Station selector and read the
operator's choice**, without adopting `OpModeRobot`:

`RobotMode` is `org.wpilib.hardware.hal.RobotMode` — `{ UNKNOWN, AUTONOMOUS, TELEOPERATED, UTILITY }`.

```java
// in the Robot constructor
autos.keySet().forEach(name -> RobotState.addOpMode(RobotMode.AUTONOMOUS, name, "Auto"));
RobotState.publishOpModes();

// in autonomousInit()
Command selected = autos.get(RobotState.getOpMode());
if (selected != null) Scheduler.getDefault().schedule(selected);
```

This keeps `TimedRobot` (locked), keeps Commands v3 (locked), gets DS-native selection that works
with no dashboard at all, and is trivially swappable for `Selectable` if the DS path disappoints.
[U] I have not verified this works end-to-end at runtime — the API is present and public, but the
DS-side rendering when the publisher is not an `OpModeRobot` is untested here.

---

## 9. What we would lose by choosing PathPlanner instead

On the record, not to reopen the decision.

### 9.1 The official 2027 vendor compatibility matrix [V]

From [`wpilibsuite/SystemcoreTesting`](https://github.com/wpilibsuite/SystemcoreTesting)'s README
(last pushed **2026-08-24** — i.e. actively maintained as of today):

| Library | WPILib alpha-2 | **WPILib alpha-5/6** |
|---|---|---|
| CTRE Phoenix 6 | 25.90.0-alpha-1/-2 | 26.50.0-alpha-1 |
| **REVLib** (ours) | 2027.0.0-alpha-1 | **2027.0.0-alpha-2** (repo has through alpha-6) |
| ReduxLib | 2027.0.0-alpha-2 | 2027.0.0-alpha-6 |
| **PathPlannerLib** | 2027.0.0-alpha-2 | **2027.0.0-alpha-3** ✅ |
| **ChoreoLib** | 2027.0.0-alpha-1 | **❌ none** |
| AdvantageKit | 27.0.0-alpha-3 | 27.0.0-alpha-4 |
| ThriftyLib | ❌ | 2027.0.0-alpha-1 |

The reason ChoreoLib shows ❌ is visible in its own vendordep:
`ChoreoLib2027Alpha.json` declares **`"frcYear": "2027_alpha1"`** — hard-pinned to alpha-1.
PathPlanner's `PathplannerLibSystemCoreAlpha.json` declares `"wpilibYear": "2027_alpha5"` with
`linuxsystemcore` binaries. Alpha-6 release notes: *"It is also necessary to import vendor libraries
again, since older vendor libraries must be updated to be compatible with 2027 Alpha 5/6 projects."*

**Good news for us: REVLib is ready.** Our SPARK dependency is not a blocker.

### 9.2 Why every vendor must rebuild — the ABI break [V]

Not a recompile; a different architecture.

- Platform renamed **`linuxathena` → `linuxsystemcore`** (`wpilibsuite/native-utils`,
  `NativePlatforms.java`). Confirmed locally: our build outputs contain
  `*_CLS-linuxsystemcore.zip` classifiers and `shared/config.gradle` references
  `nativeUtils.platformConfigs.linuxsystemcore`.
- Toolchain is `aarch64-systemcore2027-linux-gnu`, `architecture = "arm64"`.
- `vendor-json-repo/check.py` validates it explicitly: **athena requires `EM_ARM` + soft-float;
  systemcore requires `EM_AARCH64`.** This is 32-bit ARM → 64-bit ARM — **zero binary reuse**.
- `grep` for "athena" across allwpilib returns **no code hits**. The roboRIO target is gone.
- ThadHouse, 2026-08-21: *"the old DS and roboRIO will not be supported in 2027. The 2027 season
  will require the new DS and Systemcore for competition use."*

ChoreoLib does ship native binaries (`ChoreoLib-cpp-…-linuxsystemcore.zip` exists for alpha-1), so
this is real work for them, not just a version bump.

### 9.3 The trade-off itself

| | Choreo | PathPlanner |
|---|---|---|
| Generation model | **Time-optimal nonlinear optimization** (Sleipnir/TrajOptLib) — *"ensures each trajectory takes full advantage of the drivetrain's performance, while obeying its dynamics constraints"* | GUI-authored **Bezier splines** — waypoints with control points, constraint zones, rotation targets, point-towards zones; optional genetic-algorithm pass |
| Runtime generation | **No** — loads pre-generated `.traj`; *"the task of implementing how to follow a trajectory is left up to the user"* | **Yes** — build a `PathPlannerPath` in code and follow it on the fly |
| Pathfinding | **None** | **Yes — AD\***, with `navgrid.json` in `deploy/pathplanner`. Caveat from their own docs: start/end **heading is not controllable** and it is poor for precision alignment — they recommend chaining a normal path for final positioning |
| Event handling | No markers; **trigger-based** composition (`atTime`, `atPose`, `atTranslation`, `done`) | **Point and zoned event markers**; zoned markers have start/end positions |
| Auto composition | `AutoFactory`/`AutoRoutine`/`AutoChooser` — **being deleted for 2027** in favour of opmodes | `AutoBuilder` + named commands; stable across seasons |
| Commands v3 | **Explicitly planned** — two vendordeps, one per framework | **Not supported.** [Issue #1177](https://github.com/mjansen4857/pathplanner/issues/1177) (2026-08-01) is open with **zero maintainer replies** |
| Alignment with WPILib 2027 | Its data model **is** what WPILib upstreamed | Independent `PathPlannerTrajectory`; no public statement about adopting `org.wpilib.math.trajectory.Trajectory` |
| 2027 runtime readiness **today** | ❌ nothing for alpha-5/6 | ✅ working alpha-3 vendordep |
| 2027 roadmap clarity | **Detailed public roadmap** (CD thread) | **None.** [Issue #1173 "WPILib 2027 alpha support"](https://github.com/mjansen4857/pathplanner/issues/1173) (2026-06-13) has **zero comments**; the `2027` branch's last commit is **2026-07-02** |

### 9.4 The honest read

**What we'd give up by not choosing PathPlanner:** on-the-fly path generation and AD\* pathfinding.
These matter if the game rewards dynamic re-routing (defense, shifting game pieces). Nothing in
Choreo replaces them.

**What we'd give up by choosing PathPlanner:** genuinely time-optimal trajectories from a modeled
drivetrain — and, more subtly, alignment with where WPILib is going. WPILib upstreamed *Choreo's*
data model and vendored *Choreo's* optimizer. PathPlanner has said nothing about following.

**On 2027 readiness the two are less far apart than the vendor matrix suggests.** PathPlanner has a
working vendordep but **no stated 2027 plan, no commands-v3 support, an unanswered support issue, and
a branch stalled since 2026-07-02**. Choreo has no working build but a **detailed public roadmap
that explicitly commits to a Commands v3 vendordep**. For a project locked to Commands v3, Choreo is
the only one of the two that has *said* it will support us.

**Conclusion: the locked decision holds**, and the `PathFollower` seam (§3.4) keeps PathPlanner
available as a drop-in if ChoreoLib slips past our integration window.

---

## Key takeaways for this project

1. **No ChoreoLib work is possible against the current alpha.** The published `2027Alpha` vendordep
   is a stale Java-17 / `edu.wpi.first` artifact. Do not add it to `vendordeps/`.
2. **Do not design the drive base around a ChoreoLib API.** The maintainer said in writing not to.
   PR #1481 deletes `AutoRoutine` and `AutoChooser`, so any 2026-shaped integration is
   throwaway work.
3. **Design against WPILib 2027 types instead**, using the two-method `PathFollower` seam from
   `rebuiltcmdv3` (§3.4) and the supplier/consumer set in §5.1. That surface is stable regardless of
   whether Choreo, PathPlanner, or a hand-rolled follower ends up behind it.
4. **`ChassisSpeeds` does not exist — the type is `ChassisVelocities`.** Every ticket, doc and
   design note that says `ChassisSpeeds` needs correcting. Same for `SwerveModuleState` →
   `SwerveModuleVelocity`.
5. **`ChassisAccelerations` is real and first-class in WPILib — but ChoreoLib does not use it.**
   ChoreoLib has **zero** occurrences of the type; it exposes `ax`/`ay`/`alpha` as loose public
   doubles on `SwerveSample`. Our drive base should still expose an accel-aware consumer
   (`BiConsumer<ChassisVelocities, ChassisAccelerations>`) — the adapter is one line
   (`new ChassisAccelerations(s.ax, s.ay, s.alpha)`), and `SwerveDriveKinematics`'s second-order
   inverse kinematics also needs the current `omega`.
   ⚠ Note the official ChoreoLib swerve example **ignores `ax`/`ay`/`alpha` and module forces
   entirely**, and punts wheel-force feedforward to CTRE-specific APIs. **We are on REV SPARK**, so
   there is no vendor path for module-force feedforward. Treat it as a stretch goal.
6. **`HolonomicTrajectory`/`HolonomicSample` are the format to build on — and this is now a
   documented intent, not a guess.** allwpilib issue #8160 says outright that WPILib would *"upstream
   ChoreoLib's `Trajectory` class and `TrajectorySample` interface"* and its swerve sample class
   *"without the input members"*; the design doc's stated non-goal is *"Replace tools like Choreo and
   PathPlanner… These tools should have their `Trajectory` classes extend `Trajectory`."* WPILib also
   **vendors Sleipnir (Choreo's optimizer) with Java bindings** at
   `org.wpilib.math.optimization`. Wrap loading in one `TrajectoryLoader` so the Choreo dependency is
   a single import when it ships.
7. **Do not mix ChoreoLib's Commands v2 factories with our v3 mechanisms.** There is no bridge and
   no cross-framework resource locking (§3.3). Use only trajectory loading/sampling from any v2
   library.
8. **`HolonomicDriveController` is gone.** We write the three-PID follower ourselves. WPILib's own
   example does the same, so this is expected, not a workaround.
9. **`SendableChooser` is gone, and the dashboard replacement is not usable at a match today.**
   **AdvantageScope cannot write string NT values at all**, so it cannot drive a chooser; Elastic's
   support is an **open, conflicted PR** whose latest release predates the API by three months. Only
   Glass has it. **Use DS-native opmode selection** (§8 Option C) — it needs no dashboard and the
   selection rides in every UDP packet.
10. **Do not install `ChoreoLib2027Alpha.json`** — it declares `"frcYear": "2027_alpha1"` and is
    hard-pinned to alpha-1. **REVLib is 2027-ready**, so our SPARK dependency is not a blocker.
    2027 is a full **32-bit ARM → 64-bit ARM (`EM_ARM` → `EM_AARCH64`) ABI break**; every vendor must
    rebuild from scratch, which is why the ecosystem is lagging.
10. **Interim seam — concrete and cheap.** Build the follower against `HolonomicTrajectory` now,
    author paths with the **Choreo 2026 GUI** (unchanged format for the transitional release), and
    write a ~40-line converter from Choreo's `.traj` schema v3 JSON to `HolonomicSample[]`:

    | `.traj` key | `HolonomicSample` |
    |---|---|
    | `t` | `time` |
    | `x`, `y`, `heading` | `new Pose2d(x, y, new Rotation2d(heading))` |
    | `vx`, `vy`, `omega` | `new ChassisVelocities(vx, vy, omega)` |
    | `ax`, `ay`, `alpha` | `new ChassisAccelerations(ax, ay, alpha)` |
    | `fx[4]`, `fy[4]` | (ignored in v1) |

    This is a **1:1 field mapping with no math** — the two formats are the same data. It unblocks
    autonomous development entirely with **zero ChoreoLib on the classpath**, sidesteps the
    Commands v2/v3 hazard completely, and the converter is deleted the day ChoreoLib 2027 ships.
    Choreo's own **code generation** is a bonus here: the emitted `ChoreoTraj.java` /
    `ChoreoVars.java` have no ChoreoLib dependency, so we can use them for path names and
    initial/end poses regardless.
11. **Harden the loader.** `Choreo.loadTrajectory` returns `Optional.empty()` on *any* failure and
    performs an unchecked sample-type cast. Our `TrajectoryLoader` should validate at startup and
    fail loudly — a silently-empty auto in a match is the worst possible outcome. Also copy
    ChoreoLib's sim-only cache invalidation for trajectory hot-reload.

---

## Open questions / unknowns

- **When does the transitional ChoreoLib actually publish?** As of 2026-08-24, *"one more evening of
  CI"* with **no date commitment**; as of 2026-08-28 there is a soft one — *"within the next 2
  weeks, hopefully significantly sooner"* (2026-08-27), now gated on **WPILib alpha-7 publishing**,
  which is tagged (2026-08-26) but not released. It is still a **Commands v2** vendordep either
  way, so the date does not change our answer. [U]
- **When does the *real* v3-native ChoreoLib land?** Gated on PR #1481's successor and on the 2027
  field AprilTag map (for the coordinate-origin flip). Realistically kickoff-adjacent. [U]
- **Will ChoreoLib 2027 emit `HolonomicTrajectory` JSON?** The *intent* is now documented (issue
  #8160, the design doc's non-goals), and the maintainer says he is waiting on exactly this class.
  What is still unconfirmed is whether ChoreoLib's `Trajectory` will literally **extend**
  `org.wpilib.math.trajectory.Trajectory`, or merely borrow the sample shape. Affects whether our
  converter (takeaway 10) is throwaway or permanent. [U]
- **Will PathPlanner adopt the new WPILib `Trajectory` types?** The design doc anticipates it;
  nothing public from PathPlanner says so. Affects how clean the fallback swap is. [U]
- ~~**Coordinate origin.**~~ **Answered on 2026-08-28: field-centre is confirmed as the
  destination, blue-corner is what every shipped artifact still emits.** Peter Johnson (WPILib),
  Discord `#systemcore` 2026-04-11: *"field-center-origin is becoming a thing?" / "yes"*.
  **[source]** The public tracker records none of it — see
  [§6.4](#64-the-coordinate-origin-and-what-our-flip-must-be-today-v). [V]
  It is **blue-corner** in every source that owns a piece of it — `Field.java:32-33`,
  `OriginPosition` (`:43-48`), Choreo's `FieldDimensions.tsx`, and `JSONFieldImage.tsx`'s
  `"origin-fraction": [0, 0]`. See [§6.4](#64-the-coordinate-origin-and-what-our-flip-must-be-today-v).
  What remains open is **when** it moves and **by what mechanism** — most likely a new field JSON
  with `origin-fraction: [0.5, 0.5]` rather than a code change, but that is [U].
- **Does the DS opmode selector render options published by a non-`OpModeRobot`?** The
  `RobotState.addOpMode`/`publishOpModes` API is public and static, but I verified only its
  existence, not its runtime behaviour from a `TimedRobot`. **This is the single most important
  bench test to run** — the whole §8 Option C recommendation rests on it. Requires a SystemCore and
  the 2027 DS (image ≥ 10). [U]
- **Does the DS opmode selector honour a robot-code-set default?** `design-docs/opmodes.md` records
  this as still-open: *"FRC SendableChooser has a 'default' option set by robot code. Do we want
  something similar here or should it be 100% DS driven?"* Matters for a safe fallback if nobody
  touches the DS. [U]
- **Will Elastic PR #366 land before kickoff?** Open and conflicted since 2026-05-07. If it does,
  Option A becomes viable as a secondary path. [U]
- **Can Elastic or AdvantageScope render `/Tunables/<name>` with type `"Selectable"` and write back
  to `selected`?** This is a brand-new schema, unrelated to the old
  `/SmartDashboard/<name>` + `"String Chooser"` layout that every 2026 dashboard implements.
  Unconfirmed for both dashboards, and it is the single point of failure for §8 Option A. **This is
  why Option C (DS-native opmode selection) is the safer default** — it needs no dashboard at
  all. [U]
- **`frcYear` mismatch** in the branch vendordep (`"2027_alpha5"` vs version `2027.0.0-alpha-6`) —
  intentional or a bug? Affects whether GradleRIO accepts it. [U]
- **C++/Python transitional variants** — the maintainer mentioned only "the Java variant". [U]
- **Alliance flipping is not injectable in ChoreoLib.** `AutoFactory` takes a `boolean`, not a
  `BooleanSupplier`, and hard-wires `MatchState::getAlliance` internally. If we ever want a
  different flip rule (e.g. practice-field testing on a mirrored half), we cannot supply one through
  ChoreoLib — another reason to own the follower. [V]
- **Will the 2027 `.traj` schema stay at version 3?** `loadTrajectoryString` throws on any other
  version. A coordinate-origin change to field-center would almost certainly bump it, invalidating
  every committed `.traj`. Plan on regenerating all paths once the 2027 field map lands. [U]
  Re-checked 2026-08-28: **still 3** on `main` *and* on `systemcore-transitional-release`, in both
  the Java and the Rust copy. The tripwire has not tripped. [V]
- **ChoreoLib alerts are broken on alpha-6** (DS-based alerts, not NetworkTables). Minor, but it
  means the library's own diagnostics are unavailable during bring-up. [V]
- **Has the 2027 field AprilTag map shipped?** No. `git ls-files fields` in the local tree stops at
  `2026-rebuilt-welded.json` and `fields/src/generate/fields.json` still defaults to
  `FRC_2026_REBUILT_WELDED`. Since 2027 kickoff is January 2027, expect this no earlier than then —
  and with it the origin change, the `.traj` schema bump, and a redraw of every path. [V] for the
  absence, [U] for the timing.
- **Will the 2026 field layouts be retroactively converted to field-centre?** Asked in Discord
  `#systemcore` on 2026-04-11 and answered *"hmm. probably a good idea? I think?"*, with nothing
  since. It matters more than it sounds: the layout JSON declares no origin, so a retroactive
  conversion is a silent 8.27 m move with no schema bump behind it. [U]
- **When the transitional release lands it will target alpha-7, not alpha-6.** The branch's
  `wpilibVersion = '2027.0.0-alpha-6'` and its `frcYear: "2027_alpha5"` vendordep are both already
  behind the maintainer's stated target. Nothing on the branch reflects alpha-7 yet. [V]

---

## Sources

- `~/dev/allwpilib` @ `v2027.0.0-alpha-6-366-gcafb0cc79` — read directly:
  `wpimath/src/main/java/org/wpilib/math/kinematics/{ChassisVelocities,ChassisAccelerations,SwerveDriveKinematics}.java`,
  `wpimath/src/main/java/org/wpilib/math/trajectory/{Trajectory,HolonomicSample,HolonomicTrajectory}.java`,
  `wpimath/src/test/java/org/wpilib/math/trajectory/SampleJsonTest.java`,
  `commandsv3/src/main/java/org/wpilib/command3/{Command,Mechanism,Coroutine}.java`,
  `tunables/src/main/java/org/wpilib/tunable/Selectable.java`,
  `wpilibj/src/main/java/org/wpilib/opmode/{OpMode,Autonomous}.java`,
  `wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`,
  `wpilibj/src/main/java/org/wpilib/driverstation/{RobotState,MatchState,Alliance}.java`,
  `wpilibjExamples/src/main/java/org/wpilib/examples/{hatchbotcmdv3,rebuiltcmdv3}/**`,
  `design-docs/opmodes.md`
- [SleipnirGroup/Choreo releases](https://github.com/SleipnirGroup/Choreo/releases)
- [Choreo PR #1481 — full Java/C++ upgrade to WPILib 2027](https://github.com/SleipnirGroup/Choreo/pull/1481)
- [Choreo PR #1502 — 2026 offseason systemcore release (closed)](https://github.com/SleipnirGroup/Choreo/pull/1502)
- [Choreo branch `systemcore-transitional-release`](https://github.com/SleipnirGroup/Choreo/tree/systemcore-transitional-release)
- [ChiefDelphi — "[Official Choreo] Systemcore Offseason Use"](https://www.chiefdelphi.com/t/official-choreo-systemcore-offseason-use/522484)
  — read via `curl -A 'Mozilla/5.0' …/522484.json` (WebFetch 403s); 4 posts as of 2026-08-28, newest
  [post #4](https://www.chiefdelphi.com/t/official-choreo-systemcore-offseason-use/522484/4)
  2026-08-27T03:52Z
- [wpilibsuite/vendor-json-repo](https://github.com/wpilibsuite/vendor-json-repo)
- [ChoreoLib-java maven metadata](https://frcmaven.wpi.edu/artifactory/sleipnirgroup-mvn-release/choreo/ChoreoLib-java/maven-metadata.xml)
- WPILib Discord `#systemcore`, 2026-04-11 — Peter Johnson on field-centre origin, and the
  unanswered question of retroactive conversion. Supplied by Drew as a screenshot on 2026-08-28;
  not publicly linkable.
- Re-check 2026-08-28, read directly:
  `choreolib/src/main/java/choreo/util/{ChoreoAllianceFlipUtil,FieldDimensions,TrajSchemaVersion}.java`
  and `src-core/src/spec/traj_schema_version.rs` on `main` **and** `systemcore-transitional-release`;
  `src/components/field/svg/fields/{FieldDimensions.tsx,JSONFieldImage.tsx}` (the GUI's
  `"origin-fraction": [0, 0]`); `update_field_dimensions.py`;
  [`Daniel1464/Choreo@40b826ee`](https://github.com/Daniel1464/Choreo/tree/40b826ee4d33da5909ba845da17ca144a7b3ebbc)
  (PR #1481 head) `choreolib/src/main/java/choreo/util/{ChoreoAllianceFlipUtil,FieldDimensions}.java`;
  `~/dev/allwpilib` `fields/src/main/java/org/wpilib/fields/Field.java`,
  `fields/src/generate/fields.json`,
  `fields/src/main/native/resources/org/wpilib/fields/frc/2026-rebuilt-welded.json`;
  `git log HEAD..origin/main` and `gh api repos/wpilibsuite/allwpilib/git/tags/e4013ac718…`;
  this repo's `src/main/deploy/choreo/{2027beta.chor,StraightAhead.traj,SweepLeft.traj}`
- ChoreoLib Java source, read directly on both branches:
  `choreolib/src/main/java/choreo/Choreo.java`,
  `choreolib/src/main/java/choreo/auto/{AutoFactory,AutoRoutine,AutoTrajectory}.java`,
  `choreolib/src/main/java/choreo/trajectory/{Trajectory,TrajectorySample,SwerveSample,DifferentialSample,EventMarker}.java`,
  `choreolib/src/main/java/choreo/util/TrajSchemaVersion.java`,
  `src-core/src/spec/trajectory.rs`, `src-core/src/spec/traj_schema_version.rs`
- [ChoreoLib Java Javadoc](https://choreo.autos/api/choreolib/java/) (currently serves the 2026 API)
- [choreo.autos docs](https://choreo.autos/) —
  [getting-started](https://choreo.autos/choreolib/getting-started/),
  [auto-factory](https://choreo.autos/choreolib/auto-factory/),
  [saving](https://choreo.autos/usage/saving/),
  [code-generation](https://choreo.autos/usage/code-generation/)
- **WPILib 2027 / SystemCore:**
  [allwpilib releases](https://github.com/wpilibsuite/allwpilib/releases) ·
  [SystemcoreTesting (vendor compatibility matrix)](https://github.com/wpilibsuite/SystemcoreTesting) ·
  [issue #8160 — Refactor Trajectory class](https://github.com/wpilibsuite/allwpilib/issues/8160) ·
  [PR #8161 — trajectory design doc (unmerged)](https://github.com/wpilibsuite/allwpilib/pull/8161) ·
  [PR #8172](https://github.com/wpilibsuite/allwpilib/pull/8172) ·
  [PR #8185](https://github.com/wpilibsuite/allwpilib/pull/8185) ·
  [PR #8479](https://github.com/wpilibsuite/allwpilib/pull/8479) ·
  [PR #9078](https://github.com/wpilibsuite/allwpilib/pull/9078) ·
  [PR #8236 — Sleipnir Java bindings](https://github.com/wpilibsuite/allwpilib/pull/8236) ·
  [PR #7773 — Tunables/Telemetry API](https://github.com/wpilibsuite/allwpilib/pull/7773) ·
  [PR #9048 — DSGamepadChooser](https://github.com/wpilibsuite/allwpilib/pull/9048) ·
  [2027 changelog](https://docs.wpilib.org/en/2027/docs/yearly-overview/yearly-changelog.html)
- **Dashboards:**
  [Elastic PR #366 — 2027 Telemetry API (open)](https://github.com/Gold872/elastic_dashboard/pull/366) ·
  [AdvantageScope releases](https://github.com/Mechanical-Advantage/AdvantageScope/releases)
- **PathPlanner:**
  [issue #1173 — WPILib 2027 alpha support (no replies)](https://github.com/mjansen4857/pathplanner/issues/1173) ·
  [issue #1177 — Commands v3 Support (no replies)](https://github.com/mjansen4857/pathplanner/issues/1177) ·
  [pathfinding docs](https://pathplanner.dev/pplib-pathfinding.html) ·
  [path editing docs](https://pathplanner.dev/gui-editing-paths-and-autos.html)
