# REVLib 2027 alpha-7 — native units and what position wrapping wraps over

**Research for:** [Drew-Robotics/2027beta#130](https://github.com/Drew-Robotics/2027beta/issues/130)
**Date:** 2026-09-18

## Source and trust level

- **[V] Verified** — I read it myself: vendor jar bytecode disassembled with `javap`, the
  vendor javadoc jar fetched from `maven.revrobotics.com`, or the native driver's machine code
  disassembled with `otool` and its constants read out of the file.
- **[C] Claimed** — a primary source *says* so but I did not execute it.
- **[?] Unknown** — no primary source answers it.

### Artifacts under test

| Thing | Coordinate |
| --- | --- |
| REVLib Java | `com.revrobotics.frc:REVLib-java:2027.0.0-alpha-7` |
| REVLib javadoc | `REVLib-java-2027.0.0-alpha-7-javadoc.jar`, same coordinate |
| REVLib native | `com.revrobotics.frc:REVLib-driver:2027.0.0-alpha-7:osxuniversal`, x86_64 slice |
| Previous | `…:2027.0.0-alpha-6` for both, for the diff |

The docs site (`docs.revrobotics.com`) is still the 2026 documentation throughout and answers
none of this — its *Units* page still lists conversion factors in the "Affected by" column.

---

## 1. Native units, from the alpha-7 javadoc **[V]**

Conversion factors are gone from `EncoderConfig` and `AnalogSensorConfig`, which now carry
`countsPerRevolution`/`quadratureAverageDepth`/`quadratureMeasurementPeriod`/`inverted` and
`inverted` respectively. What the devices report instead, quoted from the javadoc jar:

| Signal | Javadoc |
| --- | --- |
| `RelativeEncoder.getPosition()` | *"This returns the native units of 'rotations'"* — of the **motor** |
| `RelativeEncoder.getVelocity()` | *"This returns the native units of 'RPM'"* |
| `SparkAnalogSensor.getPosition()` | *"Returns value in the native unit of 'volt'"* |
| `SparkAnalogSensor.getVelocity()` | *"Returns value in the native units of 'volts per second'"* |

So the analog sensor's position and its voltage are now the same number, and every conversion,
setpoint and gain that used to be written in converted units moves into Java.

## 2. `positionWrappingEnabled` wraps over exactly 1.0 native unit **[V for the driver, [?] for the firmware]**

`ClosedLoopConfig.positionWrappingInputRange`, `positionWrappingMinInput` and
`positionWrappingMaxInput` are gone, and `SparkParameters` lost `kPositionPIDMinInput` and
`kPositionPIDMaxInput` with them — they were `150` and `151` in alpha-6 and the fields do not
resolve in alpha-7. `kPositionPIDWrapEnable` is still `149` and
`positionWrappingEnabled(boolean)` still writes it. The alpha-7 javadoc for that method says only
*"Enable or disable PID wrapping for position closed loop control"* and names no range.

The driver's own parameter-description table does name one, and it was rewritten for this release:

| Version | String in `libREVLibDriver.dylib` |
| --- | --- |
| alpha-6 | *"Position PID Wrap Enable: Sets whether the position value used in PID wraps between the ranges of 'Position PID Min Input' and 'Position PID Max Input'."* |
| alpha-7 | *"Position PID Wrap Enable: Sets whether the position value used in PID wraps between the ranges of [0, 1)."* |

That is a description rather than the behaviour, and other strings in the same table are stale
(several still describe conversion factors). So the arithmetic itself was read out of
`_c_SIM_Spark_CalculatePID`, the driver's implementation of the loop the firmware runs. The error
is formed, and then, under a single boolean guard — `cmpl $0x0, 0x27c(%rbx)`, which nothing else
in the function reads — the fold happens:

```
error  = setpoint - measurement
error -= truncf(error + C1)      ; movss 0x1a47e(%rip) ; cvttps2dq / cvtdq2ps
error -= truncf(error + C2)      ; movss 0x1ab81(%rip)
```

Reading the two constants out of the file — vmaddr `0x7aa98` and `0x7b1b4`, x86_64 slice at fat
offset 4096 — gives `0x3f000000` and `0xbf000000`: **+0.5 and −0.5**. The pair folds the error
into (−0.5, 0.5], a range of exactly **1.0**, with no sensor full-scale anywhere in it. Neither
constant is read from a parameter and neither depends on which feedback sensor is selected.

**So the wrap range is one native unit of whatever sensor the loop closes on.** For a duty-cycle
absolute encoder, one native unit is one rotation and the behaviour is unchanged. For our steer
sensor it is **one volt**, which the Thrifty analog covers in a fifth of a module turn
(`STEER_SENSOR_SPAN` is 5 V). For a primary encoder it is one **motor** rotation, which is
`1/STEER_REDUCTION` of a module turn.

Two steps in that are inference rather than reading, and both are worth naming:

- **The driver is not the firmware.** `_c_SIM_Spark_CalculatePID` is REVLib's simulation of the
  loop. That the device behaves the same way is **[unverified]** — it is corroborated by the
  parameter description and by the removal of the two bound parameters, and nothing contradicts
  it, but only a bench SPARK settles it.
- **That the analog feeds the loop in volts** is taken from the *getter's* javadoc, not from the
  feedback path. It is the natural reading with conversion factors gone, and it is still
  **[unverified]** for what the closed loop consumes.

**Neither step is load-bearing for the decision #130 takes.** If the firmware differed, or if the
analog reached the loop already normalised, the encoder path would still be correct: one motor
rotation is `1/26` of a module turn under every reading of the above, so the device's wrap is no
use on the sensor we chose either way, and the shortest path has to come from the setpoint. What
the uncertainty *would* change is whether the analog path remained available — that is, whether
this was forced or merely preferred.

## 3. What this costs, and what it does not **[V]**

- ADR 0008's Open item — *"`positionWrappingEnabled` has never been run against `kAnalogSensor`
  on this hardware"* — is resolved against us, and by reading rather than by the deployment it
  expected. The fallback that item names, closing steer on the primary encoder seeded from the
  analog, is the one taken in #130.
- The same item rejects folding shortest-path into the setpoint *on an analog sensor*, because
  with no accumulator each boundary crossing leaves a ≤5 ms window where the stale setpoint reads
  as a ~1-rotation error. That objection is specific to a sensor that wraps. **A primary encoder
  accumulates and has no boundary**, so the offset the setpoint carries cannot go stale that way,
  and the arithmetic the item rejects is sound in the frame #130 puts it in.
- `SparkBase.getBusId()` is renamed `SparkLowLevel.getCanPort()`, returning
  `org.wpilib.hardware.bus.CANPort`. `SparkSim` builds its `SimDevice` name from
  `getCanPort().value`, which is the number `SparkOutputSim` needs.

## 4. Still unknown **[?]**

- **Backlash.** ADR 0008 rejected the primary-encoder design on it: the analog sits on the module
  output shaft and the encoder counts the motor, so the two differ by the reduction's backlash and
  the module can settle anywhere inside that band. Nobody has measured the band on an Mk5i. The
  log carries `SteerAbsolute` beside `SteerAngle` for exactly this.
- **Whether the firmware agrees with the driver.** `_c_SIM_Spark_CalculatePID` is REVLib's
  simulation of the loop, not the firmware binary. It is the closest primary source available
  without hardware, and it agrees with the parameter description; a bench SPARK would settle it.
