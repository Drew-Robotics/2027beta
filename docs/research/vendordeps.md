# REVLib 2027 and Phoenix 6 2027 — SPARK, Pigeon2, config readback, sim

**Research for:** [Drew-Robotics/2027beta#5](https://github.com/Drew-Robotics/2027beta/issues/5)
**Date:** 2026-08-24

## Source and trust level

- **[V] Verified** — I read it myself: raw vendordep JSON / maven metadata fetched over HTTP,
  vendor jar bytecode disassembled with `javap`, or source read in the local `allwpilib` checkout.
- **[C] Claimed** — a primary source *says* so (vendor docs, changelog, a vendor employee's post),
  but I did not execute it.
- **[?] Unknown** — no primary source answers it. These are the ones that can still hurt us.

Vendor alphas move fast. Everything here is a snapshot of **2026-08-24**.

### Artifacts under test

| Thing | Coordinate | Published |
| --- | --- | --- |
| REVLib 2027 | `com.revrobotics.frc:REVLib-java:2027.0.0-alpha-6` | 2026-07-28 **[V]** |
| Phoenix 6 2027 | `com.ctre.phoenix6:wpiapi-java:26.50.0-alpha-1` | 2026-06-12 **[V]** |
| Local WPILib | `2027.424242.0.0-alpha-6-20260824110254-366-gcafb0cc79` | built 2026-08-24 **[V]** |

Jars were downloaded and disassembled; local WPILib jars come from
`~/dev/allwpilib/*/build/libs/`.

---

## 0. Read this first — the findings that change the plan

### 0.1 Alpha-7 is not released yet — but `main` is already stamped for it **[V]**

The issue and our working notes call the local checkout "alpha-7". Strictly it is not. The
generated `WPILibVersion.Version` in the local build reads:

```
2027.424242.0.0-alpha-6-20260824110254-366-gcafb0cc79
```

That is **main, 366 commits past the `v2027.0.0-alpha-6` tag**, built today. No alpha-7 has been
tagged or released: `api.github.com/repos/wpilibsuite/allwpilib/git/ref/tags/v2027.0.0-alpha-7`
→ 404, the newest tag of any kind is `v2027.0.0-alpha-6` (released 2026-05-08), and the alpha-7
installer URL 404s.

**But WPILib's own first-party vendordeps on `main` already declare
`"wpilibYear": "2027_alpha7"`** — all six of them:

```
apriltag/AprilTag.json:6:        "wpilibYear": "2027_alpha7",
cameraserver/CameraServer.json:6:"wpilibYear": "2027_alpha7",
commandsv2/CommandsV2.json:6:    "wpilibYear": "2027_alpha7",
commandsv3/CommandsV3.json:6:    "wpilibYear": "2027_alpha7",
romiVendordep/RomiVendordep.json:6:"wpilibYear": "2027_alpha7",
xrpVendordep/XRPVendordep.json:6:"wpilibYear": "2027_alpha7",
```

So alpha-7 is in preparation and **it will be a new install-folder generation, not a drop-in over
alpha-5/6** (unlike alpha-6, which installed straight over alpha-5 and kept the `2027_alpha5`
string). See §0.3 for why that single string is decisive.

### 0.1b The headline answer: **no, the current vendordeps will not work with alpha-7** **[V]**

REVLib 2027.0.0-alpha-6 declares `"wpilibYear": "2027_alpha5"`. WPILib `main` — the alpha-7 line —
expects `2027_alpha7`. **The vendordep is rejected by the year gate before any code is
compiled.** REV must republish for alpha-7; there is nothing we can do on our side.

Independently, the class-resolution check below shows real API drift has *already* happened on
main, so the year gate is not merely bureaucratic — it is protecting us from a genuine break.

The practical question is therefore **do they work against released alpha-6?** — yes — and **what
breaks on main?** — two specific things, below.

### 0.2 Both vendordeps break against current `main`, in narrow but real places **[V]**

I ran `jdeps` on each vendor jar with the locally-built WPILib jars on the classpath. This is a
direct class-resolution check, not a docs claim.

**REVLib 2027.0.0-alpha-6** — exactly one unresolved package:

```
com.revrobotics.sim  ->  org.wpilib.math.util   not found
```

`org.wpilib.math.util.Pair` moved to `org.wpilib.util.Pair` (wpimath → wpiutil) on main after
alpha-6. The only consumer is `com.revrobotics.sim.MovingAverageFilterSim` — **which
`com.revrobotics.spark.SparkSim` references in 8 places.** Net effect: **SPARK simulation throws
`NoClassDefFoundError` against current main.** The hardware path is untouched — every other
WPILib symbol REVLib calls resolves cleanly (list in §6.1).

**Phoenix 6 26.50.0-alpha-1** — exactly one unresolved package:

```
com.ctre.phoenix6  ->  org.wpilib.epilogue.logging   not found
```

`org.wpilib.epilogue.logging.EpilogueBackend` and `NestedBackend` were removed from main (the
Epilogue backend layer was replaced by the new `org.wpilib.telemetry` module — see §7.2). The
only consumer is `com.ctre.phoenix6.HootEpilogueBackend`, and **nothing else in the jar
references it [V]** — it is an opt-in Hoot/Epilogue bridge. Since we are not using Epilogue, this
is inert: it only fails if something touches that class.

**Consequence:** pin WPILib to the released **v2027.0.0-alpha-6** for now. If we track `main`,
SPARK sim is broken until REV republishes.

### 0.3 The `wpilibYear` gate is a hard version lock **[V]**

`REVLib-2027.json` does not carry `frcYear` like previous years. It carries:

```json
"wpilibYear": "2027_alpha5"
```

That string is the **WPILib install-folder name**, and GradleRIO refuses the vendordep if it does
not match. Per the SystemcoreTesting README **[C]**:

> The year and version on the desktop icons and WPILib folder might not match the WPILib version
> from the installer. As of this writing, the latest WPILib is 2027.0.0-alpha-6, which installs to
> a **2027_alpha5** folder and shortcuts. The reason for this is complicated, but it is intended
> behavior. Alpha 6 will install straight over an Alpha 5 installation.

So `2027_alpha5` covers WPILib alpha-5 **and** alpha-6. A project created under 2026 or 2027
alpha-1/alpha-2 must be re-imported, **and the vendordeps re-imported too** — from the alpha-6
release notes **[C]**:

> It is also necessary to import vendor libraries again, since older vendor libraries must be
> updated to be compatible with 2027 Alpha 5/6 projects.

ReduxLib's 2027 vendordep uses the same `"wpilibYear": "2027_alpha5"` string, so this is a
WPILib-wide convention, not a REV quirk.

**And this is exactly the mechanism that will lock us out of alpha-7** (§0.1b): WPILib `main`'s
own vendordeps have moved to `2027_alpha7`, while REVLib still says `2027_alpha5`. Every vendor
has to republish at each install-folder generation. Watch
`https://software-metadata.revrobotics.com/REVLib-2027.json` for the `wpilibYear` string to
change — that single field is the readiness signal for moving to alpha-7.

Corroborated from a second direction **[V]**: WPILib's own vendordep marketplace
(`wpilibsuite/vendor-json-repo`) is organised into per-generation directories, and it contains only
**`2027_alpha1` and `2027_alpha5`** — no `2027_alpha7` bundle exists yet. Phoenix 6's 2027 JSON
lives under `2027_alpha5/`, the same generation as REVLib's.

---

## 1. Config readback and verification — the headline answer

**Yes. Apply-then-verify-then-alert is fully supported on SPARK, and near-total in coverage.**
Our config-as-code decision survives. But there are four sharp edges that shape the implementation.

### 1.1 `configAccessor` is a public final field, not a method **[V]**

```java
// com.revrobotics.spark.SparkMax
public final com.revrobotics.spark.config.SparkMaxConfigAccessor configAccessor;

// com.revrobotics.spark.SparkFlex
public final com.revrobotics.spark.config.SparkFlexConfigAccessor configAccessor;
```

`SparkMaxConfigAccessor extends SparkBaseConfigAccessor` (adds `alternateEncoder`);
`SparkFlexConfigAccessor extends SparkBaseConfigAccessor` (adds `externalEncoder`).

The accessor mirrors the config tree **[V]** (`SparkBaseConfigAccessor`):

```java
public final AbsoluteEncoderConfigAccessor absoluteEncoder;
public final AnalogSensorConfigAccessor    analogSensor;
public final EncoderConfigAccessor         encoder;
public final LimitSwitchConfigAccessor     limitSwitch;
public final ClosedLoopConfigAccessor      closedLoop;   // .maxMotion, .feedForward nested
public final SoftLimitConfigAccessor       softLimit;
public final SignalsConfigAccessor         signals;
```

Top-level getters, verbatim **[V]**:

```java
public SparkBaseConfig.IdleMode getIdleMode()
public boolean getInverted()
public int     getSmartCurrentLimit()
public int     getSmartCurrentFreeLimit()
public int     getSmartCurrentRPMLimit()
public double  getSecondaryCurrentLimit()
public int     getSecondaryCurrentLimitChopCycles()
public double  getAdvanceCommutation()
public double  getOpenLoopRampRate()
public double  getClosedLoopRampRate()
public double  getVoltageCompensation()
public boolean getVoltageCompensationEnabled()
public int     getFollowerModeLeaderId()
public boolean getFollowerModeInverted()
```

Sub-accessors we will actually use **[V]**:

| Accessor | Getters |
| --- | --- |
| `ClosedLoopConfigAccessor` | `getP/getI/getD/getDFilter/getIZone/getMinOutput/getMaxOutput/getMaxIAccumulation` — each with a `(ClosedLoopSlot)` overload — plus `getAllowedClosedLoopError(ClosedLoopSlot)`, `getPositionWrappingEnabled()`, `getPositionWrappingMinInput()`, `getPositionWrappingMaxInput()`, `getFeedbackSensor()` |
| `.feedForward` | `getkS/getkV/getkA/getkG/getkCos/getkCosRatio`, each with a `(ClosedLoopSlot)` overload |
| `.maxMotion` | `getCruiseVelocity()`, `getMaxAcceleration()`, `getAllowedProfileError()`, `getPositionMode()`, each with a `(ClosedLoopSlot)` overload |
| `EncoderConfigAccessor` | `getCountsPerRevolution()`, `getInverted()`, `getPositionConversionFactor()`, `getVelocityConversionFactor()`, `getQuadratureAverageDepth()`, `getQuadratureMeasurementPeriod()`, `getUvwAverageDepth()`, `getUvwMeasurementPeriod()` |
| `AbsoluteEncoderConfigAccessor` | `getInverted()`, `getPositionConversionFactor()`, `getVelocityConversionFactor()`, `getZeroOffset()`, `getAverageDepth()`, `getStartPulseUs()`, `getEndPulseUs()`, `isZeroCentered()`, `getRangeOffset()` *(new in 2027)* |
| `SoftLimitConfigAccessor` | `getForwardSoftLimitEnabled()`, `getForwardSoftLimit()`, `getReverseSoftLimitEnabled()`, `getReverseSoftLimit()` |
| `SignalsConfigAccessor` | ~58 getters — a `getXPeriodMs()` + `getXAlwaysOn()` pair for every status signal |

### 1.2 Coverage: 84 of 87 settable parameters are readable **[V]**

Determined by diffing the `SparkParameters.k*` ids referenced in `*Config.java` (87 settable)
against those in `*Accessor.java` (84 readable).

- **Write-only, not verifiable:** `kCompatibilityPortConfig`, `kDetachedEncoderDeviceID`,
  `kLimitSwitchPositionSensor` (set via `LimitSwitchConfig.limitSwitchPositionSensor(FeedbackSensor)`).
- **Read-only:** none.
- Everything else round-trips.

### 1.3 Cost: one blocking CAN round-trip per parameter, no batching **[V]**

Every getter is a direct JNI call with a parameter id. Bytecode of `getIdleMode()`:

```
getfield      sparkHandle:J
getstatic     SparkParameters.kIdleMode
getfield      SparkParameters.value:I
invokestatic  com/revrobotics/jni/CANSparkJNI.c_Spark_GetParameterUint32:(JI)I
invokestatic  SparkBaseConfig$IdleMode.fromId:(I)LSparkBaseConfig$IdleMode;
```

Natives are `c_Spark_GetParameter{Float32,Int32,Uint32,Bool}(long handle, int paramId)`. **There
is no bulk read, no `getConfig()`, no `refresh()`** — I enumerated every class in the jar and
grepped all signatures **[V]**. A full-config verify of ~84 params is ~84 separate round-trips.

REVLib's own javadoc, repeated on every accessor field **[C]**:

> NOTE: This uses calls that are blocking to retrieve parameters and should be used infrequently.

**[?] Nobody publishes a per-call latency number, and it is not clear whether the native layer
caches.** Two hints that *some* caching exists: the 2026.0.0 changelog lists "[SPARK] Java/C++:
Fixes bug causing stale parameter reads", and 2026.0.2 added "Fetches status periods from device
on object creation". Timeouts are tunable via `SparkBase.setCANTimeout(int ms)` and
`setCANMaxRetries(int)`. **This is the one number that could still invalidate the design — measure
it on real hardware before we build on it.**

### 1.4 Sharp edge: reads return no error, and errors are per-thread **[V]**

The getters return the raw value with **no `REVLibError`**. A timed-out read returns whatever the
native layer produces (likely `0`/`false`) — **indistinguishable from a legitimately-zero
parameter.** You must call:

```java
public REVLibError SparkBase.getLastError()
```

whose javadoc says it is "meant to be called immediately following another call that has the
possibility of returning an error", and that errors are tracked **per-thread, across all devices
on that thread**. A verify loop that reads 84 params and checks `getLastError()` once at the end
only ever sees the last error. **Check it after every read, on one thread.**

### 1.5 Sharp edge: the desired config object cannot be read back **[V]**

```java
// com.revrobotics.config.BaseConfig
protected java.lang.Object getParameter(int);
protected void putParameter(int, int);
public java.lang.String flatten();
```

`getParameter` is `protected`; `SparkBaseConfig` has zero getters, only fluent setters. Config
objects are a write-only sparse map. `flatten()` is public but returns an undocumented native wire
format — do not build on it.

**Design consequence, and it is a real constraint:** we cannot iterate a `SparkMaxConfig` to
auto-derive the verify list. Our own desired-value structure has to be the source of truth,
feeding both the setter chain and the comparison. Two places to keep in sync, by construction.

REV frames config objects as intentionally partial **[C]**
([Configuring Devices](https://docs.revrobotics.com/revlib/configuring-devices)):

> All parameters in a configuration are optional... configuration classes are not intended to
> represent the device's complete configuration.

Which is fine for us: "desired config" is exactly the subset we set, and exactly the subset to verify.

### 1.6 Sharp edge: use a float tolerance, not `==` **[V]**

Values traverse `float32` natives (`c_Spark_GetParameterFloat32`) into Java `double`. Exact
equality on conversion factors and PID gains will produce phantom mismatch alerts. Worse,
`getOpenLoopRampRate()` / `getClosedLoopRampRate()` apply a `1.0/value` reciprocal on read, which
compounds the error.

### 1.7 Pigeon2 has readback too, and it is nicer **[V]**

CTRE solved this differently — a whole-struct refresh instead of per-field getters:

```java
// com.ctre.phoenix6.configs.Pigeon2Configurator
public final StatusCode refresh(Pigeon2Configuration configs);
public final StatusCode refresh(Pigeon2Configuration configs, double timeoutSeconds);
public final StatusCode refresh(MountPoseConfigs configs);          // + timeout overload
public final StatusCode refresh(GyroTrimConfigs configs);           // + timeout overload
public final StatusCode refresh(Pigeon2FeaturesConfigs configs);    // + timeout overload
public final StatusCode refresh(CustomParamsConfigs configs);       // + timeout overload

public final StatusCode apply(Pigeon2Configuration configs);        // + timeout overload
```

`refresh()` fills a config object *from the device* and returns a `StatusCode`, so a Pigeon2
verify is: build desired → `apply()` → `refresh()` into a fresh object → compare fields →
alert. One round-trip for the whole config, and the error is reported properly. This is strictly
better ergonomics than the SPARK path; our verification abstraction should not assume the SPARK
shape is the only shape.

---

## 2. `configure()` semantics — and a trap

### 2.1 Signatures **[V]**

```java
// com.revrobotics.spark.SparkBase
public REVLibError configure(SparkBaseConfig config, ResetMode resetMode, PersistMode persistMode);
public REVLibError configureAsync(SparkBaseConfig config, ResetMode resetMode, PersistMode persistMode);
```

`SparkMax` overrides `configure()` to re-validate data-port usage afterwards, throwing
`IllegalStateException` on alternate-encoder / absolute-encoder / limit-switch conflicts.

### 2.2 `configure()` THROWS — it does not just return an error **[V]**

Verbatim from `SparkBase.java`:

```java
REVLibError status = REVLibError.fromInt(CANSparkJNI.c_Spark_Configure(
    this.sparkHandle, config.flatten(),
    resetMode == ResetMode.kResetSafeParameters,
    persistMode == PersistMode.kPersistParameters));

if (status != REVLibError.kOk) {
  // Check if fatal error
  if (status == REVLibError.kTimeout
      || status == REVLibError.kCannotPersistParametersWhileEnabled) {
    return status;
  }
  throw new IllegalStateException(REVLibJNI.c_REVLib_ErrorFromCode(status.value));
}
return status;
```

**Only `kTimeout` and `kCannotPersistParametersWhileEnabled` are returned.** Everything else —
`kInvalidCANId`, `kParamInvalidValue`, `kFollowConfigMismatch`, `kCANDisconnected`,
`kDuplicateCANId` — throws an unchecked `IllegalStateException`.

This is the most architecture-relevant finding after the accessor itself. If our boot path
configures N modules in a loop, **one unplugged SPARK aborts the whole loop and takes down
`robotInit`** — which defeats the entire point of "alert, don't crash". Every `configure()` call
must be individually wrapped in try/catch, with the catch feeding the same alert path as a verify
mismatch.

`configureAsync()` returns `kOk` immediately and does not throw, but surfaces errors only on the
Driver Station and gives us nothing to verify against synchronously. Not useful for boot config.

### 2.3 `ResetMode` / `PersistMode` moved to top level in 2027 **[V]**

```java
public enum com.revrobotics.ResetMode   { kNoResetSafeParameters(0), kResetSafeParameters(1); public final int value; }
public enum com.revrobotics.PersistMode { kNoPersistParameters(0),   kPersistParameters(1);   public final int value; }
```

They are **no longer** nested as `SparkBase.ResetMode` / `SparkBase.PersistMode` — those were
deprecated `forRemoval` in 2026 and are gone in 2027. Every 2025/2026 code sample and every LLM's
training data will have the old nested import. Expect this to bite repeatedly.

Documented semantics, from the `configure()` javadoc **[V]**:

> If `resetMode` is `kResetSafeParameters`, this method will reset safe writable parameters to
> their default values before setting the given configuration. **The following parameters will not
> be reset by this action: CAN ID, Motor Type, Idle Mode, PWM Input Deadband, and Duty Cycle
> Offset.**
>
> If `persistMode` is `kPersistParameters`, this method will save all parameters to the SPARK's
> non-volatile memory after setting the given configuration.

Note that **Idle Mode is not reset** by `kResetSafeParameters` — so brake/coast is sticky across
a reset-and-reconfigure, and must be set explicitly. Worth verifying explicitly for the same reason.

### 2.4 REV's recommended boot pattern **[C]**

[Configuring a SPARK](https://docs.revrobotics.com/revlib/spark/configuring-a-spark):

```java
spark.configure(config, ResetMode.kResetSafeParameters, PersistMode.kPersistParameters);
```

> It is recommended to persist parameters during the initial configuration of the device at the
> start of your program to ensure that the controller retains its configuration in the event of a
> power cycle during operation e.g. due to a breaker trip or a brownout.

> When making updates to the configuration mid-operation, it is generally recommend to not persist
> the applied configuration changes to avoid blocking the program.

And on resetting ([Configuring Devices](https://docs.revrobotics.com/revlib/configuring-devices)):

> Resetting parameters before applying a new configuration ensures the device starts in a known,
> good state... This approach is especially valuable when performing a drop-in replacement for a
> device, as the replacement may be in an unknown state.

This aligns exactly with our config-as-code stance — reset gives the known baseline that makes
verification meaningful, and persist survives a brownout. REV's caveat is "don't reset if the REV
Hardware Client is your primary configuration tool", which is precisely the GUI workflow we rejected.

### 2.5 Persisting cost **[C]** / **[?]**

> Persisting parameters involves saving them to the SPARK controller's memory, which is
> **time-intensive and blocks communication with the device**.

**[?] REV publishes no millisecond figure and no flash write-cycle endurance rating** for the
SPARK's non-volatile memory in any primary source I could find. What is verified: a distinct
`REVLibError.kCannotPersistParametersWhileEnabled` exists, so firmware actively refuses to persist
while enabled — strong evidence it is disruptive. Practical rule the sources do support: persist
once at boot, never in a periodic loop.

---

## 3. The `busId` constructor parameter

### 3.1 SPARK: `busId` is first, and there is no 2-arg overload **[V]**

```java
// 2027.0.0-alpha-6
public SparkMax(int busId, int deviceId, SparkLowLevel.MotorType type);
public SparkFlex(int busId, int deviceId, SparkLowLevel.MotorType type);
public SparkBase(int busId, int deviceId, SparkLowLevel.MotorType type, SparkLowLevel.SparkModel model);
```

Parameter order verified from bytecode, not javadoc — in `SparkLowLevel.<init>`:

```
30: aload_0
31: iload_1
32: putfield  busId:I      // first int  -> busId
35: aload_0
36: iload_2
37: putfield  deviceId:I   // second int -> deviceId
```

For comparison **[V]**: 2025.0.3 and 2026.0.5 both had `SparkMax(int deviceId, MotorType type)`.
**No 2-arg overload survives in 2027** — this is a hard breaking change at every construction site.

New accessors **[V]**: `SparkLowLevel.getBusId()` and `getDeviceId()`.

Duplicate detection is now per `(busId, deviceId)` pair — `CANSparkJNI.c_Spark_RegisterId(int
busId, int deviceId)` returns `kDuplicateCANId` **[V]**. So the same device ID on two different
buses is legal, as intended for multi-bus SystemCore.

### 3.2 Valid `busId` values — answered by WPILib, not REV **[V]**

REVLib does **no** Java-side validation; `busId` goes straight to `c_Spark_Create`. REV's docs do
not document `busId` at all yet (docs.revrobotics.com is still entirely on the 2026 line). But
WPILib 2027 defines the numbering, in
`~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/hardware/bus/CANBus.java`:

```java
/**
 * CAN bus mapping.
 *
 * <p>S0-S4 are Systemcore CAN buses. D0-D19 are Motioncore CAN buses.
 */
public enum CANBus {
  CAN_S0(0), CAN_S1(1), CAN_S2(2), CAN_S3(3), CAN_S4(4),
  CAN_D0(5), CAN_D1(6), /* ... */ CAN_D19(24);
  public final int value;
}
```

**So: SystemCore's own buses are `busId` 0–4 (`CANBus.CAN_S0..CAN_S4.value`); 5–24 are Motioncore
expansion buses.** Our SPARKs will be on `CANBus.CAN_S0.value` (or whichever S-bus we wire).

**[I]** Pass `CANBus.CAN_S0.value` rather than a bare `0` — REVLib takes an `int`, but WPILib owns
the meaning, and the enum documents intent at the call site.

### 3.3 Phoenix 6: a `CANBus` object, not an int **[V]**

```java
// com.ctre.phoenix6.hardware.Pigeon2 — the ONLY constructor
public Pigeon2(int deviceId, com.ctre.phoenix6.CANBus canbus);

// com.ctre.phoenix6.CANBus
public CANBus();
public CANBus(String name);
public CANBus(String name, String canivoreDeviceName);
public static CANBus systemcore(int busIndex);
public static CANBus systemcore(int busIndex, String name);
public static CANBus motioncore(int busIndex);
public static CANBus motioncore(int busIndex, String name);
public final boolean isNetworkFD();
public final CANBus.CANBusStatus getStatus();
```

So the Pigeon2 call site is `new Pigeon2(id, CANBus.systemcore(0))`. Note CTRE also dropped its
single-arg `Pigeon2(int)` — same hard breaking change as REV's.

**The two vendors' bus numbering agrees exactly** — verified from `CANBus` bytecode **[V]**:

```
systemcore(int):  index must be 0..4     -> bus name "systemcore" + index
                  "Systemcore CAN bus index must be within 0-4."
motioncore(int):  index must be 0..19    -> internally adds 5   (iconst_5)
                  "Motioncore port must be within 0-19."
```

Both validate eagerly with `IllegalArgumentException`. `motioncore(n)` mapping to `n + 5` lines up
precisely with WPILib's `CAN_D0(5) .. CAN_D19(24)`, and `systemcore(n)` with `CAN_S0(0) ..
CAN_S4(4)`. So **CTRE's `systemcore(n)` and REVLib's `busId = CANBus.CAN_S<n>.value` refer to the
same physical bus for the same `n`.** Good — no off-by-five trap between our SPARKs and our
Pigeon2.

**[I]** They still expose it through different types (`int` vs a `CANBus` object), so define one
project-level constant per physical bus and convert at each call site rather than writing the
number twice.

**⚠️ But Phoenix 6 does not actually support Motioncore buses yet [V]** — CTRE known issues,
2026/06/12:

> **(26.50.0-alpha-1) Systemcore: Motioncore CAN buses are not supported. Only the Systemcore
> native CAN buses and CANivores are currently functional.** This will be fixed in a future release.

So `CANBus.motioncore(n)` compiles and validates but does not work. Irrelevant for us — we are on
`can_s*` — but it means the two vendors' *usable* bus sets currently differ.

**⚠️ CTRE's default bus is `can_s1`, not `can_s0` [V].** The changelog says *"The Systemcore
default CAN bus is 'can_s1' when a CANBus object is constructed with an empty string or no
string"*, and the 26.50.0-alpha-1 javadoc for `CANBus()` agrees: *"Creates a new CAN bus using the
default for the system: **'can_s1' on Systemcore**, 'can0' on Linux, '*' on Windows"*.

`SystemcoreTesting/CTR-Phoenix.md` contradicts this — it says *"If no parameter is provided, it will
use can_s0"* and spells the factory `CANBus.systemCore(int)` with a capital C. **The markdown is
wrong on both counts**; the shipped javadoc is authoritative. Harmless for `Pigeon2` (its only
constructor requires an explicit `CANBus`), but a trap if we ever write `new CANBus()`. **Always
pass the bus explicitly.**

---

## 4. Simulation

### 4.1 REVLib sim classes and the package trap **[V]**

The package layout is inconsistent and has been since 2025 — `SparkSim` is **not** in
`com.revrobotics.sim`:

| Class | Package |
| --- | --- |
| `SparkSim` | **`com.revrobotics.spark.SparkSim`** |
| `SparkMaxSim`, `SparkFlexSim` | `com.revrobotics.sim.*` |
| `SparkAbsoluteEncoderSim`, `SparkRelativeEncoderSim` | `com.revrobotics.sim.*` |
| `SparkAnalogSensorSim`, `SparkLimitSwitchSim` | `com.revrobotics.sim.*` |
| `SparkMaxAlternateEncoderSim`, `SparkFlexExternalEncoderSim` | `com.revrobotics.sim.*` |
| `SparkSimFaultManager` | `com.revrobotics.sim.*` |

Signatures **[V]**:

```java
public SparkSim(SparkBase spark, org.wpilib.math.system.DCMotor motor);
public SparkMaxSim(SparkMax sparkMax, DCMotor motor);        // extends SparkSim
public SparkFlexSim(SparkFlex sparkFlex, DCMotor motor);     // extends SparkSim
public SparkAbsoluteEncoderSim(SparkMax motor);              // also (SparkFlex)
public SparkRelativeEncoderSim(SparkMax motor);              // also (SparkFlex)
```

`SparkSim` surface **[V]**:

```java
public void   iterate(double velocity, double vbus, double dt);
public double getAppliedOutput();          public void setAppliedOutput(double);
public double getVelocity();               public void setVelocity(double);
public double getPosition();               public void setPosition(double);
public double getBusVoltage();             public void setBusVoltage(double);
public double getMotorCurrent();           public void setMotorCurrent(double);
public double getSetpoint();
public ClosedLoopSlot getClosedLoopSlot();
public void enable();  public void disable();  public void useDriverStationEnable();
public SparkRelativeEncoderSim  getRelativeEncoderSim();
public SparkAbsoluteEncoderSim  getAbsoluteEncoderSim();
public SparkAnalogSensorSim     getAnalogSensorSim();
public SparkLimitSwitchSim      getForwardLimitSwitchSim();
public SparkLimitSwitchSim      getReverseLimitSwitchSim();
public SparkSimFaultManager     getFaultManager();
```

`SparkAbsoluteEncoderSim` / `SparkRelativeEncoderSim` **[V]**: `setPosition/getPosition`,
`setVelocity/getVelocity`, `setInverted/getInverted`, `setZeroOffset/getZeroOffset`,
`getPositionConversionFactor()`, `getVelocityConversionFactor()`, `iterate(double, double)`.

**Note the DCMotor package: `org.wpilib.math.system.DCMotor`, not `...system.plant.DCMotor`.**
`getNEO(int)` and `getNeoVortex(int)` both exist there **[V]**.

### 4.2 The intended wiring **[I]**, grounded in **[V]** signatures

WPILib physics sim owns the plant; `SparkSim.iterate` pushes the resulting state into the SPARK's
`SimDevice` values so that normal robot-code reads (`getEncoder().getPosition()`, closed-loop
control) behave. Per module, per sim tick:

1. Read the SPARK's commanded output — `sparkSim.getAppliedOutput()` (or `getSetpoint()`).
2. Feed it into a WPILib plant (`DCMotorSim` / `FlywheelSim` built from
   `DCMotor.getNEO(1)` or `DCMotor.getNeoVortex(1)`), advance it by `dt`.
3. Push the plant's velocity back: `sparkSim.iterate(velocityRpm, RobotController.getBatteryVoltage(), dt)`.
4. For a steer module with an absolute encoder, drive
   `sparkSim.getAbsoluteEncoderSim().iterate(velocity, dt)` or set position directly.
5. For the gyro, `pigeon.getSimState().setRawYaw(...)` from the kinematics-integrated heading
   (see §4.3).

**[?] REV publishes no 2027 simulation doc** — docs.revrobotics.com has zero 2027 content. The
`iterate(double, double, double)` shape is unchanged from 2025/2026, so the 2025-era guidance
carries over, but this is inference from signatures, not a documented 2027 recipe.

### 4.3 `Pigeon2SimState` **[V]**

Obtained via `pigeon.getSimState()` (`CorePigeon2.getSimState()` returns `Pigeon2SimState`).

```java
public final StatusCode setSupplyVoltage(double);        // + (Voltage) overload
public final StatusCode setRawYaw(double);               // + (Angle) overload
public final StatusCode addYaw(double);                  // + (Angle) overload
public final StatusCode setPitch(double);                // + (Angle) overload
public final StatusCode setRoll(double);                 // + (Angle) overload
public final StatusCode setAngularVelocityX(double);     // + (AngularVelocity) overload
public final StatusCode setAngularVelocityY(double);     // + (AngularVelocity) overload
public final StatusCode setAngularVelocityZ(double);     // + (AngularVelocity) overload
```

Unit-typed overloads take `org.wpilib.units.measure.{Angle,Voltage,AngularVelocity}`. Note that
`Pigeon2` itself carries the WPILib sim plumbing directly — its fields include
`org.wpilib.hardware.hal.SimDevice m_simPigeon`, `SimDouble m_simYaw/m_simRawYaw/m_simPitch/...`,
and an `HAL.SimPeriodicBeforeCallback` **[V]**, so it registers into the HAL sim loop itself.

**Units are degrees** (and degrees-per-second), not rotations. `setSupplyVoltage` clamps: *"The
minimum allowed supply voltage is 4 V - values below this will be promoted to 4 V."* There is **no
`Orientation`/`ChassisReference` field** on `Pigeon2SimState` (unlike `TalonFXSimState` /
`CANcoderSimState`), no getters, and no `setYaw`.

**Use `setRawYaw` (absolute), not `addYaw`.** The `setRawYaw` javadoc is the load-bearing text **[V]**:

> Sets the simulated raw yaw of the Pigeon2. **Inputs to this function over time should be
> continuous**, as user calls of `CorePigeon2.setYaw(double)` will be accounted for in the callee.
> **The Pigeon2 integrates this to calculate the true reported yaw.** … Changes to `rawYawInput`
> will be integrated into the emulated yaw. **This way a simulator can modify the yaw without
> overriding hardware API calls for home-ing the sensor.**

`addYaw` is called **zero times** in any CTRE example or in the shipped library. CTRE's own
`SimSwerveDrivetrain.update` integrates into its own unwrapped accumulator and sets the absolute
value **[V]**:

```java
double omega = m_kinem.toChassisSpeeds(states).omegaRadiansPerSecond;
m_lastAngle = m_lastAngle.plus(Rotation2d.fromRadians(omega * dtSeconds));
m_pigeonSim.setRawYaw(m_lastAngle.getDegrees());
m_pigeonSim.setAngularVelocityZ(Units.radiansToDegrees(omega));
```

**Where it goes:** `simulationPeriodic()`, *after* the physics step — supply voltage first, plant
update, then yaw last. **No sign flip:** `getRotation2d()` javadoc says *"The angle increases as
the Pigeon 2 turns **counterclockwise** when looked at from the top. This follows the **NWU axis
convention**"*, matching WPILib; every CTRE example passes `getHeading().getDegrees()` straight
through.

**Gotcha [V]:** `setYaw()` offsets survive `setRawYaw()` *by design*, so `getYaw()` will not equal
what you wrote if user code ever called `setYaw()`. CTRE's own unit test zeroes both
(`setRawYaw(0)` then `cfg.setYaw(0)`) before asserting 1:1 tracking, and retries `setRawYaw` up to
5 times on a non-OK `StatusCode`.

**Gotcha [C]:** CAN latency is simulated — *"the influence of the CAN bus is simulated at a level
similar to what happens on a real robot… this may appear as a delay between setting a signal and
getting its real value."* CTRE's tests bump `setUpdateFrequency(Hertz.of(1000))` under
`Utils.isSimulation()`. Expect a lag between `setRawYaw` and `getYaw` unless we do the same.

**[C]** In sim, *"Multiple CAN buses using the CANivore API is not supported at this time. All CAN
devices will appear on the same CAN bus"* — so device IDs must be unique **across** buses in sim
even though hardware allows reuse (§3.1). Worth knowing before we assign IDs.

**Do not copy** `Phoenix6-Examples/java/Pigeon2/.../sim/TalonFXSimProfile.java:63` — it feeds a
rotations-valued variable into the degrees-typed `setRawYaw`. It is self-described as *"very
rudimentary physics simulation."*

### 4.4 Sim works on a dev laptop **[V]**

REVLib's `jniDependencies` list `validPlatforms` including `windowsx86-64`, `linuxx86-64`, and
`osxuniversal` alongside `linuxsystemcore`, with `skipInvalidPlatforms: true`. Desktop sim binaries
are published.

### 4.5 But SPARK sim is broken against current `main` **[V]**

See §0.2. `SparkSim` → `MovingAverageFilterSim` → `org.wpilib.math.util.Pair`, which no longer
exists. Against released alpha-6 it is fine.

Historically relevant **[C]**: SystemcoreTesting issue
[#284](https://github.com/wpilibsuite/SystemcoreTesting/issues/284) "REVLib - SparkSim does not
work" — sim devices were registered as `SPARK MAX [busId, deviceId]` but `SparkSim` built its
`SimDeviceSim` name from the device id alone, NPEing every getter. Fixed in alpha-4 ("Fixes Sim
classes to include CAN Bus ID in name"). **If we sim SPARKs we need ≥ alpha-4**, which alpha-6
satisfies.

---

## 5. Vendordep availability and versions

### 5.1 REVLib **[V]**

- **URL:** `https://software-metadata.revrobotics.com/REVLib-2027.json` — the only 2027 URL.
  `REVLib-2027-beta.json`, `-alpha.json`, `REVLib-2028.json` all 404. There is no beta channel.
- **Pins:** `2027.0.0-alpha-6`, published 2026-07-28, ~4 weeks old.
- **Platforms:** `windowsx86-64`, `linuxarm64`, `linuxx86-64`, **`linuxsystemcore`**, `osxuniversal`.
  **`linuxathena` and `linuxarm32` are gone** — this build cannot target a roboRIO at all.
- **JNI artifacts (3):** `REVLib-driver`, `RevLibBackendDriver`, `RevLibWpiBackendDriver` (2026 had
  the same three; 2025 had one).
- `maven.revrobotics.com` is a Netlify front-end that 302s to GitHub releases in
  `REVrobotics/REV-Software-Binaries`. Directory listings 404; `maven-metadata.xml` lists only the
  newest 2027 build, so it is not a full enumeration.
- **Full 2027 release history** (via the GitHub releases API): alpha-1 (2025-06-24), alpha-2
  (2026-05-22), alpha-3 (2026-05-29), alpha-4 (2026-07-02), alpha-5 (2026-07-27), **alpha-6
  (2026-07-28)**. `2027.0.0-alpha-7` and `2027.0.0-beta-1` do not exist.
- A `-sources.jar` and `-javadoc.jar` are both published for `REVLib-java` — useful, and the reason
  much of this doc is **[V]** rather than **[C]**.

### 5.2 Phoenix 6 — CTRE hosts no 2027 vendordep, but WPILib's marketplace does **[V]**

CTRE itself publishes nothing for 2027:

- `https://maven.ctr-electronics.com/release/com/ctre/phoenix6/latest/Phoenix6-frc2027-latest.json`
  → **404**. So do `Phoenix6-frc2027-beta-latest.json`, `Phoenix6-frc2027-alpha-latest.json`,
  `Phoenix6-dev-frc2027-latest.json`. `Phoenix6-frc2026-latest.json` (26.3.0),
  `Phoenix6-frc2026-beta-latest.json` and `Phoenix6-frc2025-latest.json` all return 200.
- `wpiapi-java/maven-metadata.xml` newest version is **`26.50.0-alpha-1`**, `lastUpdated`
  `20260612031539`. There is no `27.*` artifact of any kind.
- **`26.50.0-alpha-1` is nonetheless the 2027/SystemCore alpha.** I confirmed this from bytecode:
  it references `org.wpilib.units.measure.Angle`, `org.wpilib.hardware.hal.SimDevice`,
  `org.wpilib.math.geometry.Rotation2d` — i.e. it is compiled against WPILib 2027's renamed
  packages, not `edu.wpi.first.*`. CTRE's `x.50.0-alpha` line has historically been the *next*
  season's alpha (24.50 → 2025, 25.50 → 2026), so 26.50 → 2027 fits.
- `SystemcoreTesting/CTR-Phoenix.md` says only: *"Vendordep: Select from the vendor JSON repository
  in VS code"* **[C]**.

**The installable URL is on WPILib's vendordep marketplace, not CTRE's server [V]:**

```
https://frcmaven.wpi.edu/artifactory/vendordeps/vendordep-marketplace/2027_alpha5/Phoenix6-26.50.0-alpha-1.json
```

Backed by the bundle manifest `.../vendordep-marketplace/2027_alpha5.json`, which lists
`CTRE-Phoenix (v6)` with uuid `e995de00-…` and `"path": "2027_alpha5/Phoenix6-26.50.0-alpha-1.json"`.
Byte-identical git source:
`https://raw.githubusercontent.com/wpilibsuite/vendor-json-repo/main/2027_alpha5/Phoenix6-26.50.0-alpha-1.json`.
A `Phoenix6-replay-26.50.0-alpha-1.json` variant also exists.

**Note the bundle directory is `2027_alpha5`** — the same install-generation string as REVLib's
`wpilibYear` (§0.3). `vendor-json-repo` contains only `2027_alpha1` and `2027_alpha5` directories.
Independent corroboration that alpha-5/6 is one generation and that an alpha-7 generation does not
exist yet.

**Two gotchas in the JSON itself [V]:** it has **no `frcYear` key at all** (2026's has
`"frcYear": "2026"`), and its self-referential `jsonUrl` field points at the dead
`.../latest/Phoenix6-frc2027-latest.json`. Any tooling that re-fetches `jsonUrl` to check for
updates will fail.

**Platform classifiers — `linuxsystemcore` in, `linuxathena` out [V].** The 2027 vendordep's
platform list is `["windowsx86-64", "linuxx86-64", "linuxarm64", "linuxsystemcore"]`. Probing
`26.50.0-alpha-1`:

| Artifact | `linuxsystemcore` | `linuxarm64` | `linuxx86-64` | `windowsx86-64` | `linuxathena` |
| --- | --- | --- | --- | --- | --- |
| `api-cpp` | **200**, 766,521 B | 200, 766,491 B | 200, 849,309 B | 200, 846,165 B | **404** |
| `tools` | **200**, 1,284,347 B | 200, 1,270,640 B | 200, 1,404,167 B | 200, 911,927 B | **404** |
| `wpiapi-cpp` | **200**, 884,679 B | 200, 885,961 B | 200, 985,879 B | 200, 973,648 B | **404** |

All dated 2026-06-12. Cross-check at 26.3.0 (2026): `linuxathena` = 200, `linuxsystemcore` = 404 —
a clean swap. No `*static` classifiers exist for Phoenix. **Both vendors have abandoned the
roboRIO for 2027.**

### 5.2b CTRE's stated compatibility: alpha-5/6, explicitly not the roboRIO **[V]**

CTRE changelog at `https://api.ctr-electronics.com/changelog`, dated **2026/06/12**, verbatim:

> (Phoenix-Libs: 26.50.0-alpha-1) **This release is compatible with WPILib 2027 alpha 5/6 on the
> Systemcore on the Alpha/Beta 10 image and does not support the roboRIO.** Use the latest 2026
> device firmware with this release.

`SystemcoreTesting/CTR-Phoenix.md` agrees **[C]**: WPILib `2027_alpha5`-compatible release →
Phoenix 6 `26.50.0-alpha-1`; compatible firmware → any `26.X`. **Nothing claims alpha-7.**

Other load-bearing changelog lines for `26.50.0-alpha-1`, all **[V]**:

> - Device constructors accepting a CAN bus string have been removed. Construct a `CANBus` object instead.
> - Device constructors accepting a device ID without a CAN bus have been removed. **A CANBus object must be provided to the device constructor.**
> - Added `CANBus.systemcore(int)` … and `CANBus.motioncore(int)` …
> - **The Systemcore default CAN bus is "can_s1"** when a CANBus object is constructed with an empty string or no string.
> - `BaseStatusSignal.waitForAll()` is functional on all CAN buses on the Systemcore.
> - Pigeon 2: Added `getQuaternion()` …
> - Removed `Utils.fpgaToCurrentTime()` / `currentTimeToFPGATime()`. **Phoenix 6 and WPILib use the same timebase on the Systemcore.**

That last one is quietly good news for us: no clock conversion between Pigeon2 timestamps and
WPILib's — they share a timebase on SystemCore.

**Documentation availability [V]:** `v6.docs.ctr-electronics.com` has **no 2026 or 2027 branch**
(only `/en/latest/`, `/en/stable/`, `/en/2025/`, `/en/2024/`); `latest` prose is 2026-era. But
`https://api.ctr-electronics.com/phoenix6/latest/java/` **is** 26.50.0-alpha-1 generated javadoc —
so unlike REVLib (§ Open questions), CTRE does have a 2027-alpha primary reference. There are no
per-version javadoc paths, so this URL will drift when CTRE ships the next alpha.

### 5.3 What REVLib alpha-6 actually targets **[C]**

REV staff (`jan`) on Chief Delphi, 2026-05-21
([post](https://www.chiefdelphi.com/t/rev-robotics-2025-2026-new-releases-updates-and-fun/506380/508)):

> We are aiming to do a release of REVLib that is **compatible with WPILib 2027 alpha 6** in the
> coming week just to get A301 alpha testers up and running. This alpha version of REVLib will not
> have all of our planned features for 2027, but it should have most everything that was available
> in 2026.

The compatibility table in `SystemcoreTesting/README.md` is **stale** — it still lists REVLib
`v2027.0.0-alpha-2` against WPILib alpha-5/6, even though `REV.md` in the same repo documents
alpha-3 through alpha-6. Structurally alpha-2→alpha-6 is one continuous series against the
`2027_alpha5` install, so alpha-6 is the right choice; the table just was not bumped.

---

## 6. Compatibility evidence in detail

### 6.1 Every WPILib symbol REVLib alpha-6 calls **[V]**

Extracted from the constant pool of all 2027 REVLib classes and checked against the local build.
All present with matching signatures **except the one marked**:

```
org/wpilib/driverstation/DriverStationErrors.reportError:(Ljava/lang/String;Z)V
org/wpilib/driverstation/DriverStationErrors.reportWarning:(Ljava/lang/String;Z)V
org/wpilib/driverstation/RobotState.isEnabled:()Z
org/wpilib/hardware/bus/I2C.<init>:(Lorg/wpilib/hardware/bus/I2C$Port;I)V
org/wpilib/hardware/bus/I2C.read:(II[B)Z
org/wpilib/hardware/bus/I2C.write:(II)Z
org/wpilib/hardware/hal/SimBoolean.get:()Z / .set:(Z)V
org/wpilib/hardware/hal/SimDouble.get:()D  / .set:(D)V
org/wpilib/hardware/hal/SimInt.get:()I     / .set:(I)V
org/wpilib/hardware/hal/SimDevice.create:(Ljava/lang/String;II)L.../SimDevice;
org/wpilib/hardware/hal/SimDevice.createDouble:(Ljava/lang/String;L.../Direction;D)L.../SimDouble;
org/wpilib/math/filter/LinearFilter.movingAverage:(I)L.../LinearFilter;
org/wpilib/math/filter/LinearFilter.calculate:(D)D
org/wpilib/math/system/DCMotor.getCurrent:(DD)D
org/wpilib/math/util/Pair.<init>/getFirst/getSecond          <-- *** MISSING on main ***
org/wpilib/simulation/SimDeviceSim.<init>:(Ljava/lang/String;)V
org/wpilib/simulation/SimDeviceSim.getBoolean/getDouble/getInt
org/wpilib/util/Color.<init>:(DDD)V, .BLACK, .red, .green, .blue
org/wpilib/util/runtime/RuntimeLoader.loadLibrary:(Ljava/lang/String;)V
```

Notably `SparkLowLevel implements org.wpilib.hardware.motor.MotorController` and correctly
implements the **2027** interface shape **[V]** — `setThrottle(double)` / `getThrottle()`, not the
old `set(double)` / `get()`:

```java
// org.wpilib.hardware.motor.MotorController (local build)
public abstract void setThrottle(double);
public default void setVoltage(double);
public default void setVoltage(org.wpilib.units.measure.Voltage);
public abstract double getThrottle();
public abstract void setInverted(boolean);
public abstract boolean getInverted();
public abstract void disable();
```

```java
// com.revrobotics.spark.SparkBase — implements all of them
public void setThrottle(double); public double getThrottle();
public void setVoltage(double);  public void setInverted(boolean);
public boolean getInverted();    public void disable();  public void stopMotor();
```

So no `AbstractMethodError` risk. Good.

### 6.2 Other REVLib 2027 breaking changes vs 2026 **[V]**

Every 2027 removal was already `@Deprecated(forRemoval = true)` in 2026 — no surprises, but a lot
of stale-sample risk.

**New in alpha-2: the `Signal<T>` wrapper.** Plain-double getters are gone; reads now return a
value object:

```java
public final class com.revrobotics.util.Signal<T> {
  public T get();
  public T get(T defaultValue);
  public long getTimestamp();
  public boolean isValid();
  public REVLibError getError();
  public <R> Signal<R> map(Function<T, R>);
}
```

`SparkBase.getBusVoltage()`, `getAppliedOutput()`, `getOutputCurrent()`, `getMotorTemperature()`,
`getFaults()`, `getStickyFaults()`, `getWarnings()`, `getStickyWarnings()`, `hasActiveFault()`,
`isFollower()` and `SparkLowLevel.getPeriodicStatus0..9()` all return `Signal<...>` now **[V]**.

**This is directly useful to us.** `Signal.isValid()` + `getTimestamp()` gives per-signal staleness
detection for free, which is exactly the kind of thing we would otherwise have hand-rolled for
explicit telemetry logging. `get(T default)` gives a safe fallback without a null check.

**Setters removed in 2027:** `ClosedLoopConfig.pidf(...)`, `ClosedLoopConfig.velocityFF(...)` (use
the `feedForward` sub-config; `kV` replaced `kF`), `MAXMotionConfig.maxVelocity(...)` →
`cruiseVelocity(...)`, `MAXMotionConfig.allowedClosedLoopError(...)` → `allowedProfileError(...)`,
`LimitSwitchConfig.forwardLimitSwitchEnabled(...)` / `reverseLimitSwitchEnabled(...)` →
`forward/reverseLimitSwitchTriggerBehavior(Behavior)`, and the `SignalsConfig.*AlwaysOn(...)`
family.

**Accessor getters removed:** `getFF()`, `getMaxVelocity()`, `getAllowedClosedLoopError()` (no-arg),
`getForwardLimitSwitchEnabled()`, `getReverseLimitSwitchEnabled()`.

**Added in 2027:** `AbsoluteEncoderConfig.rangeOffset(double)` + `getRangeOffset()`, and
`SparkBase.getForwardSoftLimit()` / `getReverseSoftLimit()` returning `SparkSoftLimit`.

### 6.3 Firmware coupling **[C]**

From `SystemcoreTesting/REV.md`:

> Other than A301, there are currently no new device versions for 2027. Please use the latest 2026
> releases of firmware.

Relevant to us: **SPARK MAX `v26.1.5`, SPARK Flex `v26.1.6`**. The A301 firmware/REVLib lockstep
warnings in `A301.md` do not apply — we are not using A301.

### 6.4 Known SystemCore issues affecting us **[C]**

Issues in `wpilibsuite/SystemcoreTesting` labeled "REV Robotics":

| # | State | What |
| --- | --- | --- |
| [#284](https://github.com/wpilibsuite/SystemcoreTesting/issues/284) | closed 2026-07-03 | REVLib — `SparkSim` does not work (sim device name missing busId). Fixed in alpha-4. |
| [#170](https://github.com/wpilibsuite/SystemcoreTesting/issues/170) | closed 2026-07-29 | **Instantiating SparkMax controllers makes code unresponsive to TERM signals** — 90-second deploys. Reported on REVLib alpha-1. Closed the day after alpha-6 shipped, but no changelog entry names the fix. |
| [#303](https://github.com/wpilibsuite/SystemcoreTesting/issues/303) | **OPEN** | High SystemCore CPU utilization from REV Hardware Client. |
| [#337](https://github.com/wpilibsuite/SystemcoreTesting/issues/337) | **OPEN** | A301 absolute-position control corrupts encoder telemetry. A301 only — not us. |
| [#311](https://github.com/wpilibsuite/SystemcoreTesting/issues/311) | **OPEN** | REV touch sensor not detected on SmartIO port. Not our hardware. |

**#170 is the one to watch.** A 90-second deploy cycle would be miserable. It is marked closed but
the fix version is not stated — treat as **[C] claimed fixed**; confirm empirically on first
hardware bring-up.

Team reports on Chief Delphi are thin and all anecdotal: FRC 6328 and FRC 3075 both ran a SPARK MAX
on SystemCore without issue, but both predate alpha-2. **No public status report on REVLib alpha-6
exists** — it is four weeks old.

---

### 6.5 Phoenix 6 known issues on SystemCore **[C]**

CTRE changelog "Known Issues", 2026/06/12, all against `26.50.0-alpha-1`:

- **Motioncore CAN buses are not supported** (see §3.3).
- **Warnings and errors may not show up in the Driver Station — they are reported to stderr.**
  Directly relevant to us: if our config-mismatch path ever routes through Phoenix's error
  reporting, it may be invisible on the DS. Another argument for driving everything through
  `org.wpilib.util.Alert` ourselves rather than relying on vendor error surfacing.
- On-robot hoot logs are missing the "EStopped" state and the "AllianceStation" field.
- Hoot logs are not renamed to include the match name when an FMS is connected.
- Device APIs have no replacement for Sendable support.
- Swerve uses the 2026 field coordinate system; WPILib plans to change the field origin and
  orientation in 2027.
- Carried forward: **Tuner cannot deploy a temporary diagnostic server to SystemCore.** To use
  Tuner X you must deploy a robot program with a Phoenix 6 device initialized — which conflicts
  with disabling the diagnostic server (§8.4).

### 6.6 SystemCore CAN bus behaviour — affects both vendors **[C]**

This is vendor-independent and shapes how we lay modules out across buses.

**Naming and count.** `can_s0`–`can_s4` (SystemCore), `can_d0`–`can_d19` (Motioncore). The roboRIO
`"rio"` bus name no longer works (SystemcoreTesting #162, CTRE staff).

**CAN FD is live** at 5 and 8 Mbps as of July–Aug 2026 (#342/#343), having been off in early alphas.

**⚠️ Buses share SPI controllers pairwise, and small frames are the limiter — not bandwidth.**
SystemcoreTesting **#342** (closed, filed by CTRE) measured frame drops at high utilization.
Baseline: the roboRIO native bus and a CANivore on SystemCore both drop **zero** frames at 100%.
Final verified SystemCore state on beta13:

| Config | Result |
| --- | --- |
| CAN 2.0B, 8-byte, S3+S4 @ 100% | No dropped frames |
| CAN FD 5 Mbps, one bus, 64-byte @ 100% | No drops |
| CAN FD 5 Mbps, one bus, 16-byte @ 100% | No drops |
| CAN FD 5 Mbps, one bus, **8-byte** | **Drops start at 85%** |
| CAN FD 5 Mbps, **S3+S4**, 64-byte @ 100% each | No drops |
| CAN FD 5 Mbps, **S3+S4**, 16-byte | **Drops start at 52% each** |
| CAN FD 5 Mbps, **S3+S4**, 8-byte | **Drops start at 43% each** |

Root cause per Limelight: a hard **frames-per-second ceiling per SPI bus** (~12.5k fps FD), largely
independent of bitrate. Vendor design guidance, verbatim:

> the #1 goal has always been robot subsystem separability (eg DT, intake, indexer, shooter,
> endgame all on their own buses…). **There will be frame rate considerations dependent on robot
> can bus configuration** … I think recommendations such as **"Keep FD on even-numbered buses
> only"** are reasonable.

**Design consequence for us:** bus headroom is *not* a per-bus independent budget — S3 and S4 halve
each other. Eight SPARKs plus a Pigeon2 is small traffic, so this is unlikely to bite, but if we
ever split the drivetrain across buses, pair them with the SPI sharing in mind rather than assuming
S0–S4 are independent.

**⚠️ CAN hardware timestamps are not from the hardware — SystemcoreTesting #122, still OPEN.** The
`mcp251xfd` driver replaces the hardware timestamp with `ktime_get_raw()` at driver-receive time,
giving *"nondeterministic latency between the actual reception time and the reported timestamp."*
This degrades any latency compensation built on CAN timestamps — relevant if we ever do
timestamp-based odometry fusion. Note this also undercuts the value of Pigeon2 signal timestamps
and of REVLib's new `Signal.getTimestamp()`.

**Adjacent, OPEN (#352):** SystemCore's own `diagnosticsprocess` (Limelight's, *not* Phoenix's)
burns ~60% of a core and causes *"observable spikes in robot loop times"*, measured with a program
that does nothing but time a single `setControl` call. Being reduced (500 Hz → 50 Hz NT updates),
not yet closed. **If we see loop-time spikes on real hardware, suspect this before suspecting our
own code.**

**[?] CAN termination on SystemCore is undocumented** — no issue, README line, or doc in the repo
discusses it.

---

## 7. WPILib-side context that affects this ticket

### 7.1 There is no built-in swerve *class* **[V]**

I searched the whole local tree. WPILib 2027 ships swerve **math**, not a swerve **drivetrain
class**:

- `org.wpilib.math.kinematics.SwerveDriveKinematics`, `SwerveDriveOdometry`, `SwerveDriveOdometry3d`
- `org.wpilib.math.estimator.SwerveDrivePoseEstimator`, `SwerveDrivePoseEstimator3d`
- `org.wpilib.math.kinematics.{SwerveModuleVelocity, SwerveModulePosition, SwerveModuleAcceleration}`
- `org.wpilib.drive` contains only `DifferentialDrive`, `MecanumDrive`, `RobotDriveBase` — **no
  `SwerveDrive`.**

The only `SwerveDrive` class in the tree is an **example**:
`wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/mechanisms/SwerveDrive.java`. If
"WPILib built-in swerve" in our locked decisions means that example, it is a template to copy, not
a library to depend on. **Worth confirming what was actually intended** — it changes how much
module-level code we own.

**Big renames in 2027 [V]** (these will break every 2025/2026 sample and every LLM suggestion):

| 2026 | 2027 |
| --- | --- |
| `SwerveModuleState` | `SwerveModuleVelocity` |
| `ChassisSpeeds` | `ChassisVelocities` |
| `toSwerveModuleStates(...)` | `toSwerveModuleVelocities(...)` |
| `desaturateWheelSpeeds(...)` | `desaturateWheelVelocities(...)` |
| — | `SwerveModuleAcceleration`, `ChassisAccelerations` (new) |

No `Gyro` interface exists; odometry consumes a `Rotation2d` directly, so
`pigeon.getRotation2d()` (present on `Pigeon2` **[V]**) plugs straight in.

### 7.2 Telemetry: a new first-party module, and Epilogue's backend layer is gone **[V]**

`allwpilib` now has a top-level `telemetry/` module, `org.wpilib.telemetry`:

```java
// org.wpilib.telemetry.Telemetry — static façade
public static TelemetryTable getTable();
public static TelemetryTable getTable(String name);
public static void log(String name, double value);      // + boolean/byte/short/int/long/float/String
public static void log(String name, double[] value);    // + all array overloads
public static <T> void log(String name, T value, Struct<? super T> struct);
public static <T> void log(String name, T value, Protobuf<? super T, ?> proto);
public static void log(String name, Collection<?> value);
public static void setProperty(String name, String key, String value);
public static void keepDuplicates(String name);
```

Plus `TelemetryBackend`, `TelemetryRegistry`, `TelemetryTable`, `TelemetryEntry`,
`TelemetryLoggable`, `MultiTelemetryBackend`, `DiscardTelemetryBackend`, `MockTelemetryBackend`.
There is also a new `tunables/` module (`org.wpilib.tunable.{Tunable, TunableDouble, TunableTable,
TunableRegistry, ...}`).

`org.wpilib.epilogue.logging` has been reduced to `ClassSpecificLogger` + `errors/` —
`EpilogueBackend` and `NestedBackend` are gone, which is exactly why Phoenix 6's
`HootEpilogueBackend` fails to resolve (§0.2).

**This is directly relevant to our "explicit telemetry logging, no AdvantageKit" decision** —
`Telemetry.log(...)` is a first-party explicit-logging API that did not exist when that decision
was made. Worth a look before we hand-roll NetworkTables publishing.

### 7.3 `Alert` moved and gained an `id` **[V]**

Our config-mismatch alert path depends on this:

```java
// org.wpilib.util.Alert  — moved from edu.wpi.first.wpilibj.Alert into wpiutil
public Alert(String id, String text, Level level);
public Alert(String group, String id, String text, Level level);
public void set(boolean active);
public boolean get();
public void setText(String text);
public String getText();
public Level getLevel();
public void close();

public enum Level { HIGH, MEDIUM, LOW }   // red X / yellow ! / green i
```

Note the **new `id` parameter** — 2026's constructor was `Alert(String text, AlertType type)` with
`AlertType.{kError, kWarning, kInfo}`. Both the package, the parameter list, and the enum constant
names changed. A per-device alert id (`"spark-" + busId + "-" + deviceId`) falls out naturally.

`Level.HIGH` is the right level for a config mismatch — "problems which will seriously affect the
robot's functionality and thus require immediate attention".

---

## 8. Phoenix 6 Pigeon2 API

### 8.1 Class and construction **[V]**

`com.ctre.phoenix6.hardware.Pigeon2 extends com.ctre.phoenix6.hardware.core.CorePigeon2
implements AutoCloseable`.

```java
public Pigeon2(int deviceId, com.ctre.phoenix6.CANBus canbus);   // the only constructor
public static Pigeon2 none();
public final void reset();
public final org.wpilib.math.geometry.Rotation2d getRotation2d();
public final org.wpilib.math.geometry.Rotation3d getRotation3d();
public final org.wpilib.math.geometry.Quaternion getQuaternion();
public void close();
```

`getRotation2d()` returning a WPILib `Rotation2d` is what feeds `SwerveDriveOdometry` /
`SwerveDrivePoseEstimator` directly.

### 8.2 Signals **[V]** (on `CorePigeon2`)

Every signal has a no-arg and a `(boolean refresh)` overload:

```java
public final StatusSignal<org.wpilib.units.measure.Angle> getYaw();    // + getYaw(boolean)
public final StatusSignal<Angle> getPitch();                           // + (boolean)
public final StatusSignal<Angle> getRoll();                            // + (boolean)
public final StatusSignal<AngularVelocity> getAngularVelocityZWorld();  // + (boolean)
public final StatusSignal<AngularVelocity> getAngularVelocityZDevice(); // + (boolean)
public final StatusSignal<Integer> getVersion();  // Major/Minor/Bugfix/Build variants too
public final StatusCode setYaw(double);
public final StatusCode setYaw(double, double);          // with timeout
public final StatusCode setYaw(org.wpilib.units.measure.Angle);
public final Pigeon2Configurator getConfigurator();
public final Pigeon2SimState   getSimState();
```

Units are `org.wpilib.units.measure.*` — WPILib 2027 unit types, so the interop is native.
`getVersion*()` is our firmware-version check for the verify-on-boot path.

### 8.3 Config verification **[V]**

See §1.7. `Pigeon2Configurator` has matched `apply()`/`refresh()` pairs for `Pigeon2Configuration`,
`MountPoseConfigs`, `GyroTrimConfigs`, `Pigeon2FeaturesConfigs`, `CustomParamsConfigs`, each with a
`(configs, double timeoutSeconds)` overload. `MountPoseConfigs` (mount yaw/pitch/roll) is the one that actually matters for us and
is fully round-trippable.

### 8.4 Cost of depending on Phoenix only for a gyro

This turned out to be the most expensive part of the Pigeon2 decision, and it is **not** disk space.
Nothing here is a blocker, but three defaults need explicit overriding.

**Disk: ~9.9 MB unzipped [V].** The compressed classifier zips (§5.2) unpack to:

| File | Bytes |
| --- | --- |
| `libCTRE_Phoenix6.so` | 4,091,808 |
| `libCTRE_PhoenixTools.so` | 4,942,392 |
| `wpiapi-java-26.50.0-alpha-1.jar` | 891,485 |
| **Total** | **≈ 9.9 MB** |

For a device with one constructor and a yaw signal. A large share of `PhoenixTools` is a **complete
embedded diagnostic-server web UI** (HTML/CSS/JS, device tables, firmware-flash progress bars).

**~10 background threads, started implicitly [V].** CTRE's `Unmanaged.loadPhoenix()` javadoc:

> **Calling this function will load and start the Phoenix background tasks.** … This function does
> NOT need to be called if you are using any of the Phoenix CAN device classes.

So constructing a `Pigeon2` starts them. CTRE never enumerates them, but `strings` on the shipped
`linuxsystemcore` `libCTRE_PhoenixTools.so` shows the `std::thread` entry points **[V]**:

```
ctre::phoenix::diagnostics::PhoenixDiagnosticsServer::StartThread
ctre::phoenix::diagnostics::{Control,Plotter}
ctre::phoenix::platform::can::HootLogger::Start
ctre::phoenix::platform::can::LoggerManager
ctre::phoenix::platform::can::NetworkStateManager::StartThread
ctre::phoenix::platform::can::BusMgr::StartThreads       (two lambdas)
CtreDeviceInterface
ctre::phoenix::legacy::diagnostics::{Control,Plotter}
```

**⚠️ Hoot logging auto-starts on SystemCore — the default differs from every other platform [V].**
`SignalLogger.enableAutoLogging` javadoc, 26.50.0-alpha-1:

> **Auto logging is enabled by default on the Systemcore** and disabled by default on other systems.
> When auto logging is enabled, logging is started by any of the following (whichever occurs first):
> It has been at least 1 second since program startup … and the robot is enabled. It has been at
> least 5 seconds since program startup … and the Driver Station is connected to the robot (if on a
> Systemcore).

A Pigeon2-only program therefore **writes `.hoot` files to disk within 1–5 s of every boot, one per
CAN bus**, unless we call `SignalLogger.enableAutoLogging(false)`. Binary strings confirm the
disk-pressure handling ships (*" MB, old hoot logs will be deleted."*). The 2026 prose docs still
describe the old roboRIO-only behavior — the alpha javadoc supersedes them.

**⚠️ Diagnostic HTTP server auto-starts, costs 0–5% CAN [V].** From
`v6.docs.ctr-electronics.com/en/latest/docs/troubleshooting/running-diagnostics.html`:

> **Phoenix Diagnostics will automatically run assuming you have instantiated a CTR Electronics
> device in your robot program.** … Note: The ID of the device does not need to be valid to run
> diagnostics.

Port 1250. From `docs/migration/canbus-utilization.html`: *"Using Phoenix API will automatically
start up a diagnostic server which adds a **constant 0-5% total CAN bus utilization**."*

Kill switch, present in 26.50.0-alpha-1 **[V]** — `Unmanaged.setPhoenixDiagnosticsStartTime(double)`:

> A value of 0 will start the server immediately. **A negative value will signal the server to
> shutdown or never start.**

Caveats: documented only in generated javadoc, never on the prose site; **absent from the C++
`ctre::phoenix::unmanaged` namespace** (Java/Python only); no build flag. Disabling it breaks Tuner
X — whose SystemCore fallback is itself a listed known issue (§6.5).

**Pigeon2's own CAN load [C]:** default `Yaw` rate 100 Hz; documented whole-device utilization
3.1% (CAN 2.0) / 1.3% (CAN FD). `optimizeBusUtilization()` drops unrequested signals to 4 Hz, but
its javadoc warns it *"will wait up to 0.100 seconds (100ms) for each signal"* — a real startup cost
if applied naively.

**Licensing: free, confirmed [V].** `docs/licensing/what-is-licensing.html`:

> **All supported Phoenix 6 devices can freely use the Phoenix 6 API.** Licensing a device can
> enable additional features…
> Note: **Using Phoenix 6 simulation with Pro features does not require licensing.**

Every core IMU reading — yaw/pitch/roll, angular velocity, accel, quaternion, mount calibration,
faults — is free. Pro-gated items that touch a Pigeon2 (time-synced signal publishing, device
timestamps) **additionally require a CANivore**, which we do not have. Non-issue.

**[?] Native library load / JNI init time is still unquantified.** CTRE documents nothing;
`ParentDevice` extends `CtreJniWrapper`, which has zero javadoc. The only documented startup delays
are the 1 s/5 s logger auto-start and `optimizeBusUtilization`'s 100 ms/signal.

In sim, `Pigeon2` registers an `HAL.SimPeriodicBeforeCallback` and a list of
`org.wpilib.simulation.CallbackStore` **[V]** — cheap, and sim-only.

---

## Key takeaways for this project

1. **Config-as-code with apply-then-verify-then-alert is viable on both vendors. The locked
   decision stands.** SPARK gives 84-of-87 parameter readback via `configAccessor`; Pigeon2 gives
   whole-struct readback via `Pigeon2Configurator.refresh()`.

2. **`configure()` throws — wrap every call.** `IllegalStateException` for everything except
   `kTimeout` and `kCannotPersistParametersWhileEnabled`. Unguarded, one dead SPARK kills
   `robotInit` and we get no alert at all — the exact failure mode our design exists to prevent.
   This is the single highest-value implementation note in this document.

3. **Own the desired-config table separately.** `SparkBaseConfig` has no getters and
   `BaseConfig.getParameter` is `protected`, so the verify list cannot be auto-derived from the
   config object. One structure drives both the setter chain and the comparison.

4. **Verify per-field, once, on one thread, with `getLastError()` after every read and a float
   tolerance on every comparison.** Reads return no error code and a failed read looks exactly like
   a zero value.

5. **Pin WPILib to released `v2027.0.0-alpha-6`; do not track `main`, and do not expect to move to
   alpha-7 on our own schedule.** REVLib declares `wpilibYear: 2027_alpha5`; WPILib main's own
   vendordeps already declare `2027_alpha7`, so the vendordep will be rejected outright on an
   alpha-7 project. On top of that, both vendor jars have exactly one unresolved class against
   current main — REVLib's breaks `SparkSim` (real, blocks simulation), Phoenix's breaks only the
   opt-in `HootEpilogueBackend` (inert for us). **Our WPILib upgrade cadence is gated by REV's
   republish cadence, not ours.** The readiness signal is the `wpilibYear` field in
   `REVLib-2027.json`.

6. **Both vendors made `busId`/`CANBus` a required constructor parameter.** SPARK:
   `new SparkMax(busId, deviceId, MotorType.kBrushless)` — busId first, plain `int`, values from
   `org.wpilib.hardware.bus.CANBus` (S0–S4 = 0–4 on SystemCore). Pigeon2:
   `new Pigeon2(deviceId, CANBus.systemcore(0))`. Different types for the same physical bus —
   define one project constant per bus.

7. **`ResetMode`/`PersistMode` moved to `com.revrobotics.*` top level**, and the whole `Signal<T>`
   wrapper is new. Nearly every REVLib code sample on the internet — and in any model's training
   data — is now wrong. Budget for that.

8. **`Signal<T>` is a gift for explicit telemetry.** `isValid()` + `getTimestamp()` gives per-signal
   staleness for free; `get(default)` gives safe fallbacks. Design the telemetry layer around it
   rather than unwrapping to raw doubles immediately. Caveat: SystemCore CAN timestamps are *not*
   hardware timestamps (§6.6, SystemcoreTesting #122, still open), so treat `getTimestamp()` as
   staleness detection, not as a latency-compensation input.

9. **Boot config: `kResetSafeParameters` + `kPersistParameters`**, per REV's own recommendation.
   Reset gives the known baseline that makes verification meaningful; persist survives a brownout.
   Remember Idle Mode is *not* reset by `kResetSafeParameters` — set and verify it explicitly.

10. **SPARK firmware: MAX `v26.1.5`, Flex `v26.1.6`.** There is no 2027 SPARK firmware; REV says
    use the latest 2026 releases.

11. **Install Phoenix 6 from WPILib's marketplace, not CTRE.** CTRE hosts no 2027 vendordep. Use
    `https://frcmaven.wpi.edu/artifactory/vendordeps/vendordep-marketplace/2027_alpha5/Phoenix6-26.50.0-alpha-1.json`.
    Its own `jsonUrl` field is a dead placeholder, so update-checking tooling will fail against it.
    CTRE's changelog states plainly that `26.50.0-alpha-1` is *"compatible with WPILib 2027 alpha
    5/6 … and does not support the roboRIO"* — corroborating takeaway 5 from the other vendor's side.

12. **Turn off two Phoenix defaults at startup, or accept them knowingly.** Constructing a single
    `Pigeon2` starts ~10 background threads, an HTTP diagnostic server on port 1250 costing a
    *constant 0–5% CAN utilization*, and — **uniquely on SystemCore** — automatic `.hoot` disk
    logging 1–5 s after boot. Overrides: `SignalLogger.enableAutoLogging(false)` and
    `Unmanaged.setPhoenixDiagnosticsStartTime(-1)`. Note disabling diagnostics breaks Tuner X,
    whose SystemCore fallback is itself a known issue. Disk cost is ~9.9 MB — irrelevant. Licensing
    cost is zero — confirmed.

13. **Pigeon2 sim: `setRawYaw` with an absolute, continuous, unwrapped angle in degrees, NWU, no
    sign flip, last in `simulationPeriodic()`.** Never `addYaw` — CTRE's own swerve sim accumulates
    externally and sets absolute. Beware that `setYaw()` offsets survive `setRawYaw()` by design.

14. **Always pass the CAN bus explicitly to CTRE.** `new CANBus()` defaults to **`can_s1`** on
    SystemCore, not `can_s0` — and CTRE's own SystemcoreTesting markdown documents this wrongly.
    Also, `CANBus.motioncore(n)` compiles but is **not functional** in this release.

15. **Phoenix warnings and errors may not reach the Driver Station on SystemCore** (they go to
    stderr). Route every operator-facing condition through `org.wpilib.util.Alert` ourselves rather
    than relying on vendor error surfacing.

---

## Open questions / unknowns

**Blocking-ish — resolve before or during first hardware bring-up**

1. **[?] What does a `configAccessor` read actually cost?** No primary source publishes a latency
   number, and it is unclear whether the native layer caches. ~84 sequential blocking round-trips
   at boot is fine at 5 ms each and unacceptable at 100 ms each. **Measure it first.** This is now
   the only finding that could still invalidate the config-as-code design.

2. **[?] Is SystemcoreTesting #170 (SparkMax instantiation → 90-second deploys) actually fixed?**
   Closed 2026-07-29 with no changelog entry naming the fix. Verify empirically on first deploy.

3. **[?] Do we see the #352 loop-time spikes?** SystemCore's own `diagnosticsprocess` (Limelight's,
   not Phoenix's) burns ~60% of a core and causes observable robot-loop spikes; the issue is open
   and being mitigated. If loop times look bad on first bring-up, suspect this before our own code.

**Design-shaping**

4. **[?] What does "WPILib built-in swerve" mean in our locked decisions?** There is no swerve
   drivetrain class in WPILib 2027 — only kinematics/odometry math plus an *example*
   (`rebuiltcmdv3/mechanisms/SwerveDrive.java`). If the decision assumed a library class, it needs
   restating.

5. **[?] Should the new `org.wpilib.telemetry` module be our explicit-logging layer?** It did not
   exist when "explicit telemetry logging, no AdvantageKit" was decided. It is first-party,
   struct/protobuf-aware, and backend-pluggable. Evaluate before hand-rolling NetworkTables calls.

6. **[?] Does `configAccessor` return meaningful values under simulation?** I found no evidence
   that config reads are backed in sim. The verify path may need to be a no-op or a distinct
   sim-side check. `SparkAbsoluteEncoderSim`/`SparkRelativeEncoderSim` expose only
   `getInverted()`, `getZeroOffset()`, and the two conversion factors — far narrower than the real
   accessor.

7. **[?] Do we keep Phoenix's hoot logging?** It is on by default on SystemCore only, starts 1–5 s
   after boot, and writes one file per CAN bus. It is genuinely useful diagnostic data — but it is
   also a vendor-owned binary log sitting alongside our explicit telemetry, which is exactly the
   kind of duplication the "no AdvantageKit" decision was avoiding. Decide deliberately rather than
   inheriting the default.

8. **[?] Phoenix 6 native library load / JNI init time.** CTRE documents nothing; `CtreJniWrapper`
   has zero javadoc. The only quantified startup delays are the logger's 1 s/5 s and
   `optimizeBusUtilization`'s 100 ms per signal.

9. **[?] SPARK flash write-cycle endurance.** REV publishes no rating. Relevant only if we ever
   persist more than once per boot — which we should not.

10. **[?] REVLib 2027 has no documentation at all.** docs.revrobotics.com is entirely 2026;
    `/revlib/revlib-overview/known-issues` 404s; the changelog contains zero "2027" strings. The
    only 2027 REVLib docs anywhere are `REV.md` in `wpilibsuite/SystemcoreTesting`. Every REV doc
    quote in §2.4 is 2026-current prose that I am assuming still applies. **CTRE is better off
    here** — `https://api.ctr-electronics.com/phoenix6/latest/java/` is genuine 26.50.0-alpha-1
    javadoc — but note that URL is unversioned and will drift when CTRE ships its next alpha.

11. **[?] CAN termination on SystemCore is undocumented.** Nothing in SystemcoreTesting covers it.

**Resolved during this research** *(kept so we do not re-ask)*

- *Does CTRE publish a `linuxsystemcore` classifier?* — **Yes.** `api-cpp`, `tools`, and
  `wpiapi-cpp` all publish it at `26.50.0-alpha-1`; `linuxathena` 404s. §5.2.
- *How do we install Phoenix 6 for 2027?* — **WPILib's vendordep marketplace**, not CTRE. §5.2.
- *Does Phoenix 6 need a license for a Pigeon2?* — **No.** §8.4.
- *Can the Phoenix diagnostic server be disabled?* — **Yes**, `Unmanaged.setPhoenixDiagnosticsStartTime(-1)`,
  Java/Python only. §8.4.
- *Which WPILib alpha does Phoenix 6 target?* — **alpha 5/6, explicitly not the roboRIO**, per
  CTRE's changelog. §5.2b.

---

## Reproducing the compatibility check

The `jdeps` check in §0.2 is cheap and worth re-running whenever a vendordep or WPILib build moves:

```bash
CP=$(find ~/dev/allwpilib -name '*.jar' -path '*/build/libs/*' \
      | grep -vE 'sources|javadoc|test|Examples|benchmark|docs-|buildSrc|developerRobot|multiCamera' \
      | tr '\n' ':')
jdeps -cp "$CP" --multi-release 25 REVLib-java-2027.0.0-alpha-6.jar | grep -i 'not found'
```

Jars:

- `https://maven.revrobotics.com/com/revrobotics/frc/REVLib-java/2027.0.0-alpha-6/REVLib-java-2027.0.0-alpha-6.jar`
  (also `-sources.jar`, `-javadoc.jar` — use `curl -L`, the host 302s to GitHub releases)
- `https://maven.ctr-electronics.com/release/com/ctre/phoenix6/wpiapi-java/26.50.0-alpha-1/wpiapi-java-26.50.0-alpha-1.jar`
