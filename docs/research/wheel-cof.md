# Wheel coefficient of friction, and what it does to the traction argument

**Research for:** [Drew-Robotics/2027beta#142](https://github.com/Drew-Robotics/2027beta/issues/142)
**Date:** 2026-09-19

## Source and trust level

- **[field]** — ramp-test data from another team's published wheel comparison, on FRC carpet.
- **[source]** — read from a named file in this repo, cited by path and line.
- **[unverified]** — derived, assumed, or not checked.

> **Attribution gap.** The dataset was supplied by the driving dev as another team's published
> comparison. Two rows name *"88's Design"*, so team 88 is the likely publisher, but **the
> originating publication has not been linked and the attribution is `[unverified]`.** Add the
> link before anything here is cited in an ADR.

## The data

Ramp test on FRC carpet. `CoF = tan(angle)` reproduces every row to two decimals, so the method
is an inclined plane taken to the slip point. **[field]**

| Wheel | CoF | avg angle | vs MK5N | vs Grip V2 |
|---|---:|---:|---:|---:|
| **MK5N spikey tread** | **2.255** | 66.08° | 100.00 % | 117.69 % |
| SLS printed (88's design) | 2.106 | 64.60° | 93.39 % | 109.92 % |
| **Vex Grip V2** | **1.916** | 62.44° | 84.97 % | 100.00 % |
| Black nitrile | 1.542 | 57.04° | 68.38 % | 80.48 % |
| 3D printed spikes, no suspension (88's design) | 1.480 | 55.96° | 65.63 % | 77.24 % |
| Slick neoprene | 0.978 | 44.36° | 43.37 % | 51.04 % |
| Colsons | 0.899 | 41.96° | 39.87 % | 46.92 % |

Five tests per wheel. MK5N's spread is the widest of the top three — 59.8° to 69.6°, which is
2.04 to 2.65 in CoF, a ±13 % band around its own mean. **[field]** Any single-value use of 2.255
is using the mean of a noisy measurement.

**The two that matter here:** this project specifies the **MK5N**; the robot whose match logs
[#153](https://github.com/Drew-Robotics/2027beta/issues/153) mines ran **Vex Grip V2**.

## What it does to the traction argument

ADR 0010's *Open* and [#103](https://github.com/Drew-Robotics/2027beta/issues/103) argue *"a
traction limit is not the lever"* from μ between 0.9 and 1.2, seeded from maple-sim's
`COTS.ofMAXSwerve()` presets. The measured number is roughly double the top of that range.

Constants, all **[source]** from `src/main/java/first/robot/DriveConstants.java`: mass 125 lb
(`:101`), wheel radius 2 in (`:91`), drive reduction `(54/14)·(25/32)·(30/15)` = 6.0268 (`:83`),
drive current limit 60 A (`:97`), NEO Vortex. Reproducing ADR 0010's own figures as a check:
**121.4 N per wheel** and `4·I·Kt·G/(m·r)` = **8.568 m/s²**, against its stated 121 N and
8.57 m/s². The model is the right one.

| μ | where it comes from | grip/wheel | margin over 121.4 N | slip needs |
|---:|---|---:|---:|---:|
| 0.9 – 1.2 | maple-sim preset, what the map inherited | 125 – 167 N | 1.03 – 1.37× | 62 – 82 A |
| 1.916 | Vex Grip V2 — the logged robot | 266.3 N | 2.19× | 132 A |
| **2.255** | **MK5N — this project** | **313.5 N** | **2.58×** | **155 A** |

**ADR 0010's "slip becomes reachable only above about 69 A" is wrong by more than a factor of
two.** With the tread this project actually specifies it is **~155 A**, against a NEO Vortex
stall of 211 A. In steady straight-line driving, longitudinal slip is not merely unreachable —
it is unreachable by a wide margin, and the conclusion the map inherited is *strengthened*, not
weakened, by measuring the number it guessed.

## But weight transfer makes slip reachable anyway

Everything above assumes each wheel carries a static `m·g/4` = 139.0 N. Under the 8.568 m/s² the
current limit allows, it does not. Quasi-static longitudinal transfer over the 23.5 in module
spacing (`DriveConstants.java:94-95` **[source]**, wheelbase 59.7 cm) moves `m·a·h/(2L)` off each
wheel of the unloading axle:

| CG height | N, unloaded wheel | grip @ 2.255 | margin | grip @ 1.916 | margin |
|---:|---:|---:|---:|---:|---:|
| 10 cm | 98.3 N | 221.7 N | 1.83× | 188.4 N | 1.55× |
| 15 cm | 78.0 N | 175.9 N | 1.45× | 149.4 N | 1.23× |
| **20 cm** | **57.7 N** | **130.0 N** | **1.07×** | 110.5 N | **0.91×** |
| 25 cm | 37.3 N | 84.1 N | 0.69× | 71.5 N | 0.59× |
| 30 cm | 17.0 N | 38.3 N | 0.32× | 32.5 N | 0.27× |

Solving for the crossing: **the unloaded wheel breaks traction above a CG height of 20.9 cm on
MK5N, or 18.6 cm on Grip V2.** **[unverified — derived]**

This is the mechanism ADR 0010 and [#103](https://github.com/Drew-Robotics/2027beta/issues/103)
do not consider, and it changes what a friction model is for:

- **Whole-robot, steady state:** slip unreachable at 60 A, by 2.58×. The inherited claim holds.
- **Per-wheel, during a hard launch or stop:** slip reachable at 60 A for any plausible CG
  height, with **no raised current limit required**. A friction model has a job here.

**This project's CG height is not recorded anywhere** — `DriveConstants` carries module spacing
but no frame dimension, no CG and no chassis MOI. A bare drive base sits low enough to be safe;
nothing stays a bare drive base.

## Caveats that constrain how these numbers may be used

1. **A ramp test measures *static* CoF.** Sliding CoF is lower, typically well below the static
   value. The table says where slip *begins*; it says nothing about behaviour once a wheel is
   sliding, which is exactly the regime a friction model has to simulate. **[unverified]**
2. **μ > 2 is not Coulomb friction.** Spiked tread into carpet pile is partly mechanical
   interlock. A friction-circle model assumes `F = μN` linear in normal load; interlock is not,
   so extrapolating 2.255 to a normal load far from the test's is unsound in an unknown
   direction. **[unverified]**
3. **Width confounds the compound comparison.** The MK5N is 2.25 in wide against 1.5 in for the
   MK4 running Grip V2. At equal normal load that is **two-thirds the contact pressure**, and
   rubber μ falls as pressure rises — so an unknown share of the 17.69 % advantage is width
   rather than compound. The ratio transfers only if per-wheel loading resembles the test rig's,
   and **the test's normal load is not recorded here**. **[unverified]**
4. **Direction.** A ramp test loads the wheel one way. A swerve module's lateral grip — the
   `F_lat = −k·v_lat` term — is not obviously the same number, least of all for directional
   tread. **[unverified]**
5. **The ±13 % spread** on MK5N's five tests is larger than several of the distinctions the
   ratio column draws.

## What this changes

- [#150](https://github.com/Drew-Robotics/2027beta/issues/150) — the friction model has a
  justified purpose, and it is transients on the unloading axle, not the steady-state clamp the
  map assumed would sit inert.
- [#153](https://github.com/Drew-Robotics/2027beta/issues/153) — the logged robot's threshold is
  18.6 cm and it carried an intake, kicker, shooter and spindexer, so its CG was very likely
  above it. Slip events are something to go and find, not merely to check for.
- [#142](https://github.com/Drew-Robotics/2027beta/issues/142) — the map's traction bullet is
  corrected: right conclusion for steady state, wrong numbers, and incomplete as an account of
  when slip happens.
