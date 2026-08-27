# SDS Mk5i — geometry and the steering encoder

Read 2026-08-27, off Swerve Drive Specialties' own product page and the
layout drawing and gear-ratio chart it links. Claim tags are defined in
[the ADR index](../adr/README.md).

## Why this was read

ADR 0008's entire steer decision rests on the module carrying a
**Thrifty analog** absolute encoder into the SPARK Flex data port at
pin 3. That claim is tagged `[field — #29]`, which is evidence about
what other teams do and not about what the Mk5i ships. If the module
shipped a CAN-based or duty-cycle encoder as standard, ADR 0008 and
ADR 0007's frame allocation would both be wrong.

## The module ships no steering encoder at all

SDS lists the encoder under *"the following supported components are
**not** included"*, alongside the drive and steering motors, and names
four options:

> Steering encoder: CTRE CANcoder, CTRE SRX Mag Encoder, **Thrifty
> Absolute Magnetic Encoder**, Redux HELIUM Canandmag, or equivalent.

**[source]** So there is no standard encoder for ADR 0008 to
contradict, and the Thrifty analog is a first-class supported choice.
ADR 0008 stands, and so does ADR 0007's decision to carry module angle
on Status3.

What this does change is that **the encoders are a purchase, not a
property of the module**. Four Thrifty Absolute Magnetic Encoders have
to be ordered, and each carries the ⌀.250" × 0.500" magnet that seats
in the module's main steering gear — the magnet is not in the module
kit either. **[source]** ADR 0008's `[field]` tag is the right tag: it
records what we intend to fit, and nothing about the module forces it.

## Ratios

**Steering: 26:1.** **[source]**

**Drive: three ratios, all three shipped**, changed by swapping the
first-stage pinion only, with no internal disassembly. **[source]**

| | First | Second | Third | Overall |
|---|---|---|---|---|
| R1 | 12 → 54 | 32 → 25 | 15 → 30 | **7.03 : 1** |
| R2 | 14 → 54 | 32 → 25 | 15 → 30 | **6.03 : 1** |
| R3 | 16 → 54 | 32 → 25 | 15 → 30 | **5.27 : 1** |

The second and third stages are common to all three, so the pinion
tooth count is the whole of the difference. `Constants` writes the
reduction as those tooth counts rather than as `6.03`, so changing
pinion is a one-number edit.

Free speeds SDS publishes, in ft/s:

| Motor | Free RPM | R1 | R2 | R3 |
|---|---|---|---|---|
| Kraken X60 (FOC) | 5800 | 14.4 | 16.8 | 19.2 |
| Kraken X60 | 6000 | 14.9 | 17.4 | 19.9 |
| NEO V1.0/V1.1 | 5820 | 14.4 | 16.9 | 19.3 |
| NEO Vortex | 6784 | 16.8 | 19.6 | 22.5 |

**[source]**

## Wheel diameter is 4 in, by arithmetic rather than by statement

The product page does not state it. It falls out of the table above:
a NEO Vortex at 6784 RPM through R2 turns the wheel at 1125 RPM, and
19.6 ft/s at that speed needs a circumference of 12.55 in — a diameter
of **3.99 in**. **[source, via arithmetic on the published free
speeds]**

`Constants` holds this as provisional anyway. Odometry wants the
rolling radius under load, which is smaller than the nominal and is a
measurement nobody has taken.

## Open

- **Which pinion this robot runs.** R2 is what `Constants` holds and it
  is a starting value, not a decision anybody has recorded.
  **[unverified]** *Unblocked by* somebody counting the teeth on an
  assembled module.
- **That the Thrifty is what actually gets fitted.** It is supported,
  it is ADR 0008's choice, and it has not been bought.
  **[unverified]**

## Source

Read for [#57](https://github.com/Drew-Robotics/2027beta/issues/57),
which carries the verification item this document answers.

- `https://www.swervedrivespecialties.com/products/mk5i-swerve-module`
  — component list, steering ratio, encoder options, the magnet note.
- The gear-ratio chart linked from that page — stage tooth counts,
  overall ratios and the free-speed table.
- `https://andymark.com/products/sds-mk5i-swerve-module` — the same
  component list and steering ratio, read as a second source.
