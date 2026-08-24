# Research: `org.wpilib.telemetry` and explicit logging (WPILib 2027)

Resolves [#2](https://github.com/Drew-Robotics/2027beta/issues/2).

**Primary source:** local built checkout `~/dev/allwpilib` at
`v2027.0.0-alpha-6-366-gcafb0cc79` (commit `cafb0cc79`, *"Update vendordep wpilibYear to
2027_alpha7"* — i.e. effectively alpha-7). Every claim below marked **VERIFIED** was read
directly out of that source tree. Claims marked **INFERRED** are my reasoning on top of it.
`docs.wpilib.org` was **not** used.

Absolute paths below are given relative to nothing — they are literal paths on this machine.

---

## 1. Executive summary

- `org.wpilib.telemetry` is a small, backend-pluggable, **write-only** logging façade. It is
  genuinely independent of Epilogue: you can use it with zero annotations.
- The intended explicit call is `Telemetry.log(name, value)` (static, root table) or
  `table.log(name, value)` on a `TelemetryTable` you hold.
- **`Alert` DOES exist in this alpha.** AdvantageKit's claim is wrong for alpha-7. It has been
  *moved and rewritten* (`org.wpilib.util.Alert`, JNI-backed, new mandatory `id` parameter).
  See §7 — this is the single biggest correction to our plan.
- **`MockTelemetryBackend` makes telemetry fully unit-testable**, and there are two ways to do it:
  a global-registry way and a much better dependency-injected way (§6).
- **Nothing writes a WPILOG by default.** `DataLogTelemetryBackend` exists but WPILib itself
  never registers it. The practical path to a `.wpilog` is `DataLogManager.start()`, which
  captures NetworkTables (including everything telemetry publishes under `/Telemetry`). See §8.
- **Epilogue has been rewritten on top of telemetry.** Its own backend layer (`EpilogueBackend`,
  `NTEpilogueBackend`, `FileBackend`, `LazyBackend`) is **deleted**; generated loggers now emit
  `TelemetryTable.log(...)`. Telemetry does not depend on Epilogue in any direction (§11).
- **`Sendable` and `SendableBuilder` no longer exist anywhere in WPILib.** `TelemetryLoggable`
  replaces them. This has real consequences for vendor libraries (§12d).
- **Three things we had not planned for:** a whole new `org.wpilib.tunable` module (§12b), the
  fact that **Commands v3 has no telemetry integration at all** (§12a), and the `Sendable`
  removal's impact on REV/CTRE vendordeps (§12d).

---

## 2. The module: files and shape

**VERIFIED** — `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/`:

| File | Lines | Role |
| --- | --- | --- |
| `Telemetry.java` | 361 | Static façade over the root table |
| `TelemetryTable.java` | 1194 | The real workhorse: naming, dispatch, entry caching |
| `TelemetryRegistry.java` | 479 | Global registry: prefix→backend routing, type handlers, warnings |
| `TelemetryEntry.java` | 189 | Per-path sink interface (backend-facing) |
| `TelemetryBackend.java` | 64 | Backend SPI |
| `TelemetryLoggable.java` | 26 | The "log yourself into a table" interface |
| `MockTelemetryBackend.java` | 378 | Recording backend for tests |
| `MultiTelemetryBackend.java` | 432 | Fan-out to N backends |
| `DiscardTelemetryBackend.java` | 85 | Null sink |
| `CollectionElementTypeRequiredException.java` | 15 | Compile-time-ish guard for `Collection` |
| `util/PathUtil.java` | 93 | Path normalization |

Only one test file ships in the module: `~/dev/allwpilib/telemetry/src/test/java/org/wpilib/telemetry/TelemetryTableTest.java` (~1800 lines).

---

## 3. `Telemetry` / `TelemetryTable` — the explicit call pattern

### `Telemetry` (façade)

**VERIFIED**, `telemetry/src/main/java/org/wpilib/telemetry/Telemetry.java:16-30`:

```java
public final class Telemetry {
  /** The root {@link TelemetryTable}. */
  private static final TelemetryTable m_root = TelemetryRegistry.getTable("/");

  public static TelemetryTable getTable() { return m_root; }
  public static TelemetryTable getTable(String name) { return m_root.getTable(name); }
```

Everything else on `Telemetry` is a one-line delegate to `m_root`. It is a pure convenience
layer — **there is no behaviour in `Telemetry` that is not in `TelemetryTable`.**

### The overload set (identical on both classes)

**VERIFIED** from `TelemetryTable.java` (line numbers are `TelemetryTable`'s):

```java
public <T> void log(String name, T value);                                  // :399  generic dispatch
public <T> void log(String name, T value, Struct<? super T> struct);        // :561
public <T> void log(String name, T value, Protobuf<? super T, ?> proto);    // :577
public <T> void log(String name, T[] value);                                // :594
public <T> void log(String name, T[] value, Struct<? super T> struct);      // :678
public void log(String name, Collection<?> value)                           // :696  ALWAYS THROWS
       throws CollectionElementTypeRequiredException;
public void log(String name, Collection<?> value, Class<?> elementType);    // :715
public <T> void log(String name, Collection<T> value, Struct<? super T> s); // :772

// primitives — no boxing
public void log(String name, boolean value);   // :786
public void log(String name, byte value);      // :800
public void log(String name, short value);     // :814
public void log(String name, int value);       // :828
public void log(String name, long value);      // :842
public void log(String name, float value);     // :856
public void log(String name, double value);    // :870
public void log(String name, String value);    // :884
public void log(String name, String value, String typeString);  // :899

// primitive arrays
public void log(String name, boolean[] value); // :913
public void log(String name, short[] value);   // :927
public void log(String name, int[] value);     // :941
public void log(String name, long[] value);    // :955
public void log(String name, float[] value);   // :969
public void log(String name, double[] value);  // :983
public void log(String name, String[] value);  // :997
public void log(String name, byte[] value);    // :1011  (raw)
public void log(String name, byte[] value, String typeString);  // :1026

// metadata
public void keepDuplicates(String name);                          // :334
public void setProperty(String name, String key, String value);   // :360
public boolean setType(String typeString);                        // :124
public String getType();                                          // :170
public String getPath();                                          // :112
public TelemetryTable getTable(String name);                      // :182
```

Note the `Collection` trap: **`log(name, someList)` does not compile-and-work — it throws.**

**VERIFIED**, `Telemetry.java:143-146`:

```java
public static void log(String name, Collection<?> value)
    throws CollectionElementTypeRequiredException {
  throw new CollectionElementTypeRequiredException();
}
```

It's a *checked* exception on `Telemetry`, so the compiler forces you to notice. But on the
generic-dispatch path (`log(String, T)` with a runtime `Collection`) it degrades to a *warning*
instead — `TelemetryTable.java:527-533`:

```java
case Collection<?> _ -> {
  TelemetryEntry entry = getEntry(name);
  if (!entry.isDiscard()) {
    TelemetryRegistry.reportWarning(
        getEntryPath(name), "collection element type must be specified");
  }
}
```

### Canonical explicit usage (from WPILib's own examples)

**VERIFIED**, `~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/encoder/Robot.java:50-51`:

```java
Telemetry.log("Encoder Distance", encoder.getDistance());
Telemetry.log("Encoder Rate", encoder.getRate());
```

And logging a whole object into a sub-table,
`.../examples/hatchbotcmdv3/Robot.java:97-98`:

```java
Telemetry.log("Drivetrain", robotDrive);
Telemetry.log("HatchMechanism", hatchMechanism);
```

The idiomatic team pattern is to grab a table once and log into it (avoids re-walking the
path string), e.g. `Telemetry.getTable("Drive/FrontLeft")` held as a field.

---

## 4. `TelemetryLoggable` — how a type logs itself

**VERIFIED**, `TelemetryLoggable.java` in full:

```java
@FunctionalInterface
public interface TelemetryLoggable {
  void logTo(TelemetryTable table);

  default String getTelemetryType() { return null; }
}
```

`getTelemetryType()` writes a `.type` entry on the child table and is checked for consistency —
a mismatch is a reported warning and the log is skipped (`TelemetryTable.setType`, :124-158).

WPILib's own hardware classes implement it. **VERIFIED**,
`~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/hardware/rotation/Encoder.java:278-291`:

```java
@Override
public void logTo(TelemetryTable table) {
  table.log("Velocity", getRate());
  table.log("Distance", getDistance());
  table.log("Distance per Tick", getDistancePerPulse());
}

@Override
public String getTelemetryType() {
  if (m_encodingType == EncodingType.X4) { return "Quadrature Encoder"; }
  else { return "Encoder"; }
}
```

Lots of `wpilibj` hardware does this — `PWMMotorController`, `DutyCycleEncoder`, `Compressor`,
`Solenoid`, `DoubleSolenoid`, `Gamepad`, `Tachometer`, `AnalogPotentiometer`, `SharpIR`, etc.
(VERIFIED by grep over `wpilibj/src/main/java` for `org.wpilib.telemetry` imports.)

**This is the interface our subsystems should implement.** It is the explicit-logging
equivalent of the old `Sendable`, minus the read-back/actuator half.

---

## 5. Backends: `TelemetryBackend`, `TelemetryRegistry`, Multi / Discard

### The SPI

**VERIFIED**, `TelemetryBackend.java`:

```java
public interface TelemetryBackend extends AutoCloseable {
  TelemetryEntry getEntry(String path);
  default boolean ownsBackend(TelemetryBackend backend) { return this == backend; }
  default boolean sharesBackendWith(TelemetryBackend backend) { ... }
  default void removeEntry(String path) {}
}
```

Contract notes quoted from the javadoc (VERIFIED): *"Implementations registered with
`TelemetryRegistry` must be thread-safe"*, and *"Backend implementations must not throw from
this method. Recoverable failures should be reported through
`TelemetryRegistry.reportWarning(String, String)` and represented with a discard entry."*

### Prefix routing

**VERIFIED**, `TelemetryRegistry.registerBackend(String prefix, TelemetryBackend backend)`
(`:191`). Routing is **longest-prefix match**, walking up the path segment by segment, then
falling back to `"/"` then `""` (`getBackendForNormalizedPath`, `:333-352`).

This is the mechanism for selective logging. Concretely:

```java
// silence an entire subtree at near-zero cost
TelemetryRegistry.registerBackend("/Drive/Debug", new DiscardTelemetryBackend());
```

**VERIFIED** — `DiscardTelemetryBackend` returns a single shared static entry whose
`isDiscard()` is `true`, and `TelemetryTable` checks `entry.isDiscard()` **before** doing any
serialization work on essentially every path. So a discarded subtree costs one map lookup
(cached) plus a boolean check.

### Fan-out

**VERIFIED**, `MultiTelemetryBackend.java:21-40`:

```java
public class MultiTelemetryBackend implements TelemetryBackend {
  public MultiTelemetryBackend(TelemetryBackend... backends) { ... }
  public MultiTelemetryBackend(Collection<? extends TelemetryBackend> backends) { ... }
```

It handles the awkward lifecycle problem of a child backend being registered in two places
(`ownsBackend` / `sharesBackendWith` / `collectOwnedBackends`), so re-registering doesn't
close a backend someone else still holds.

### There is NO default backend in the registry

**VERIFIED.** `TelemetryRegistry` registers nothing at class-init. If no backend matches,
`getBackend` reports `"no backend for path"` and returns a `DiscardTelemetryBackend`
(`:255-266`). The default backend is installed by `RobotBase`'s constructor, not by the
telemetry module.

**VERIFIED**, `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/RobotBase.java:226-232`:

```java
// set up telemetry
TelemetryRegistry.setReportWarning(m_telemetryWarningReporter);
TelemetryRegistry.registerBackend("", new NetworkTablesTelemetryBackend(inst, "/Telemetry"));
TelemetryRegistry.registerTypeHandler(
    Measure.class,
    (table, name, value) -> {
      UnitTelemetry.log(table, name, value);
    });
```

So out of the box: **everything goes to NetworkTables under `/Telemetry`, and nowhere else.**

### Warnings

`TelemetryRegistry.setReportWarning(BiConsumer<String,String>)` / `reportWarning(path, msg)`.
Default implementation prints to `System.err` with a stack trace and carries a
`// TODO: do something smarter here` (VERIFIED, `:96-108`). `RobotBase` overrides it — see §7.

---

## 6. Unit-testability — YES, and there are two seams

### Seam A — global registry + mock (what WPILib's own tests do)

**VERIFIED**, `telemetry/src/test/java/org/wpilib/telemetry/TelemetryTableTest.java:39-53`:

```java
MockTelemetryBackend m_mock;
List<String> m_warnings;

@BeforeEach
public void init() {
  m_mock = new MockTelemetryBackend();
  m_warnings = new ArrayList<>();
  TelemetryRegistry.reset();
  TelemetryRegistry.setReportWarning((path, msg) -> m_warnings.add(path + ": " + msg));
  TelemetryRegistry.registerBackend("", m_mock);
}

@AfterEach
public void shutdown() {
  TelemetryRegistry.setReportWarning(null);
  TelemetryRegistry.reset();
}
```

`TelemetryRegistry.reset()` is documented as *"Clear all registered types and backends and
closes all entries. Should typically only be used by unit test code."* (VERIFIED, `:296-298`).

Assertion API on the mock (VERIFIED, `MockTelemetryBackend.java`):

```java
public record Action(String path, Object value) {}
public List<Action> getActions();
public void clear();
public Action getLastAction(String path);
public <T> T getLastValue(String path, Class<T> cls);
```

Plus typed value records for the non-primitive cases: `LogStringValue(String value, String
typeString)`, `LogRawValue(byte[] value, String typeString)`, `LogStructValue<T>(T value,
Struct<? super T> struct)`, `LogProtobufValue<T>`, `LogStructArrayValue<T>`,
`KeepDuplicateValue(boolean)`, `SetPropertyValue(String key, String value)`.

Real example, **VERIFIED**,
`~/dev/allwpilib/commandsv2/src/test/java/org/wpilib/command2/CommandTelemetryTest.java:37-48`:

```java
command.logTo(TelemetryRegistry.getTable("command"));

var name = m_backend.getLastValue("/command/name", MockTelemetryBackend.LogStringValue.class);
assertNotNull(name);
assertEquals("renamed", name.value());
```

Note paths in assertions are **normalized absolute paths** (`/command/name`), and
`getLastAction` normalizes its argument via `PathUtil.normalizeName` too.

### Seam B — inject a `TelemetryTable`, no global state at all (better for us)

**VERIFIED**, `TelemetryTable.java:80-87`:

```java
/**
 * Constructs a root telemetry table that writes directly to a backend without using the global
 * telemetry registry.
 *
 * @param backend telemetry backend
 */
public TelemetryTable(TelemetryBackend backend) {
  this("/", Objects.requireNonNull(backend));
}
```

This is the important find. A `TelemetryTable` built this way sets `m_backend != null` and
**every code path bypasses `TelemetryRegistry`** (verified in `getEntry`, `:218-247`;
`applyEntryMetadata`; `keepDuplicates`; `getTable` returns child tables that also carry the
backend). So:

```java
var mock = new MockTelemetryBackend();
var table = new TelemetryTable(mock);      // no global registry, no reset(), no test ordering hazard
subsystem.logTo(table.getTable("Drive"));
assertEquals(3.0, mock.getLastValue("/Drive/Velocity", Double.class));
```

**Recommendation:** subsystems should take a `TelemetryTable` (constructor-injected, defaulting
to `Telemetry.getTable("Whatever")` in production) rather than calling static `Telemetry.log`
directly. That buys parallel-safe, order-independent tests. Seam A relies on mutable global
state and needs `@ResourceLock`-style discipline if tests run concurrently.

**Caveat (VERIFIED):** `MockTelemetryBackend`'s javadoc says *"Logging is thread-safe, but
callers must quiesce or externally synchronize logging while inspecting live results."*
`getActions()` returns the live backing `ArrayList`, not a copy.

**Caveat 2 (VERIFIED):** the mock's `logStruct`/`logStructArray`/`logProtobuf` clone the value
when `struct.isImmutable()` is false and `isCloneable()` is true, and emit a warning
(*"logging non-immutable and non-cloneable struct"*) when neither holds. So mutable custom
structs will produce warnings in tests.

---

## 7. Alerts — **THEY EXIST.** AdvantageKit's claim is wrong for alpha-7

This was the flagged question. **VERIFIED in source**, present and functional:

- `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/Alert.java`
- `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/AlertDataJNI.java`
- `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/AlertException.java`
- `~/dev/allwpilib/wpiutil/src/test/java/org/wpilib/util/AlertTest.java` (passing tests in-tree)
- `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/simulation/AlertSim.java`
- native: `~/dev/allwpilib/wpiutil/src/main/native/cpp/Alert.cpp`,
  `~/dev/allwpilib/wpiutil/src/main/native/include/wpi/util/Alert.h`

### What changed vs. 2025

1. **Package moved**: `edu.wpi.first.wpilibj.Alert` → **`org.wpilib.util.Alert`** (it is now in
   `wpiutil`, not `wpilibj` — so it is available to any module).
2. **New mandatory `id` parameter.** Constructors (VERIFIED, `Alert.java:80-97`):
   ```java
   public Alert(String id, String text, Level level);                 // group defaults to "Alerts"
   public Alert(String group, String id, String text, Level level);
   ```
   The old API was `Alert(String text, AlertType type)` / `Alert(String group, String text,
   AlertType type)`. **There is no text-only constructor any more.**
3. **`AlertType` → `Alert.Level`**, and the constants renamed:
   `kError/kWarning/kInfo` → **`HIGH` / `MEDIUM` / `LOW`** (VERIFIED, `Alert.java:35-63`).
4. **It is now `AutoCloseable` and JNI/native-backed.** Alerts live in a process-global native
   registry (`AlertManager` in `Alert.cpp`), not in a NetworkTables `Sendable`.
5. **Duplicate `(group, id)` throws.** VERIFIED,
   `wpiutil/src/test/java/org/wpilib/util/AlertTest.java:163-179`:
   ```java
   @Test
   void duplicateAlertThrowsAndCanStillBeCreatedAfterwards() {
     try (Alert alert = new Alert("group", "id", "text", Alert.Level.HIGH)) {
       alert.set(true);
       assertThrows(
           AlertException.class, () -> new Alert("group", "id", "duplicate", Alert.Level.HIGH));
   ```
   **This is a real footgun for config-as-code**: if we generate alerts in a loop over N devices,
   the ids must be unique per device or construction throws at runtime.

### Full `Alert` surface (VERIFIED)

```java
public class Alert implements AutoCloseable {
  public enum Level { HIGH, MEDIUM, LOW; public int getValue(); }
  public Alert(String id, String text, Level level);
  public Alert(String group, String id, String text, Level level);
  public void set(boolean active);      // safe to call periodically
  public boolean get();
  public void setText(String text);     // dynamic detail
  public String getText();
  public Level getLevel();
  @Override public void close();
}
```

The class javadoc explicitly endorses our intended pattern: *"Alerts should be created once and
stored persistently, then updated to 'active' or 'inactive' as necessary. `set(boolean)` can be
safely called periodically."*

### Alerts are the built-in fault-surfacing story, and telemetry already feeds them

**VERIFIED**, `RobotBase.java:61-68, 92-121` — `RobotBase` routes telemetry and tunable warnings
into persistent alerts:

```java
private final Map<String, Alert> m_telemetryWarningAlerts = new HashMap<>();
private final Map<String, Alert> m_tunableWarningAlerts = new HashMap<>();
...
private void reportTelemetryWarning(String path, String msg) {
  reportWarningAlert(
      m_telemetryWarningAlerts, "Telemetry", path + '\n' + msg,
      "Telemetry '" + path + "': warning: " + msg);
}
...
alert = new Alert(group, m_warningAlertIdPrefix + m_nextWarningAlertId.getAndIncrement(),
                  text, Alert.Level.MEDIUM);
```

Confirmed by test, `~/dev/allwpilib/wpilibj/src/test/java/org/wpilib/framework/TimedRobotTest.java:266-294`
(`constructorMapsWarningsToAlerts`) — a `TelemetryRegistry.reportWarning` produces an alert in
group `"Telemetry"`, a `TunableRegistry.reportWarning` produces one in group `"Tunables"`.

So **a type mismatch or bad log call in our code shows up as a MEDIUM alert on the dashboard,
for free.** That is exactly the apply+verify+alert loop we wanted, already half-built.

Also **VERIFIED**: `OpModeRobot` raises a loop-overrun alert
(`OpModeRobot.java:522-527`):
```java
m_loopOverrunAlert = new Alert(..., Alert.Level.MEDIUM);
m_watchdog = new Watchdog(Seconds.of(m_period), () -> m_loopOverrunAlert.set(true));
```
`IterativeRobotBase` / `TimedRobot` do **not** — they still call
`DriverStationErrors.reportWarning("Loop time of " + m_period + "s overrun\n", false)`
(`IterativeRobotBase.java:347-349`). See §11, this matters for our TimedRobot choice.

### Open: how alerts reach a dashboard

**VERIFIED**: `Alert.h:54-70` defines a pluggable `WPI_AlertBackend` (function pointers
`createAlert` / `destroyAlert` / `setAlertActive` / `setAlertText` / `getAlerts` …) with
`WPI_SetAlertBackend()` / `WPI_GetAlertBackend()` at `:185-192`. The default in-tree backend
just stores them in a process-global vector.

**I could not find any code in the allwpilib tree that publishes alerts to NetworkTables.**
Grepping the whole repo for alert consumers turns up only `AlertSim`, `AlertDataJNI`, the tests,
and `DriverStationBackend`'s internal joystick alerts. **INFERRED:** the real dashboard
integration is supplied out-of-tree by the SystemCore/DS runtime installing an alert backend, or
it is not wired up yet in alpha-7. This is the one genuinely open item on alerts (§13).

---

## 8. What writes a WPILOG, and what's in it

### `DataLogTelemetryBackend` exists but is never registered by WPILib

**VERIFIED**, `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/backend/DataLogTelemetryBackend.java:35-52`:

```java
public class DataLogTelemetryBackend implements TelemetryBackend {
  public DataLogTelemetryBackend(DataLog log, String prefix) { ... }
```

Grepping the whole tree (excluding `/build/`) for constructions of it: the only hits are the
class itself and `wpilibj/src/test/java/org/wpilib/backend/DataLogTelemetryBackendTest.java:47`.
**`RobotBase` registers only the NetworkTables backend.** So a stock 2027 robot program writes
telemetry to NT and to no file.

### Two ways to get telemetry into a `.wpilog`

**Option 1 (indirect, zero extra wiring) — `DataLogManager` captures NT.**
`~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/system/DataLogManager.java`. VERIFIED:

- `private static boolean m_ntLoggerEnabled = true;` (`:60`) and
  `private static boolean m_consoleLoggerEnabled = true;` (`:63`) — **both default to on**.
- Class javadoc `:49`: *"By default, all NetworkTables value changes are stored to the data log."*
- `startNtLog()` (`:288-292`):
  ```java
  m_ntEntryLogger = inst.startEntryDataLog(m_log, "", "NT:");
  m_ntConnLogger = inst.startConnectionDataLog(m_log, "NTConnection");
  ```

Since telemetry publishes to `/Telemetry/...` in NT, `DataLogManager.start()` alone gets
everything into the file under `NT:/Telemetry/...`. **INFERRED but strongly implied:** this is
the intended default path for most teams.

**Option 2 (direct, what we probably want) — register a fan-out backend ourselves:**

```java
TelemetryRegistry.registerBackend("",
    new MultiTelemetryBackend(
        new NetworkTablesTelemetryBackend(NetworkTableInstance.getDefault(), "/Telemetry"),
        new DataLogTelemetryBackend(DataLogManager.getLog(), "/Telemetry")));
```

This is exactly the shape exercised in `TelemetryTableTest.java:1368-1371`
(`new MultiTelemetryBackend(oldBackend, datalogBackend)`). Advantage: telemetry is written to
disk on its own schedule and does not depend on NT publish/dedup semantics or NT being up; you
can also then `DataLogManager.logNetworkTables(false)` to avoid double-writing everything.

### File locations and rotation (VERIFIED, `DataLogManager.java`)

- Default dir (`makeLogDir`, `:241-271`): prefers a mounted USB at **`/u/logs`** if writable,
  else **`/home/systemcore/logs`** on a real robot (note: **SystemCore**, not `/home/lvuser`),
  else `<operating dir>/logs` in sim.
- Filenames (`:40-48`, `makeLogFilename` `:273-286`): starts as
  `WPILIB_TBD_{16 hex}.wpilog`, renamed on DS connect to `WPILIB_yyyyMMdd_HHmmss.wpilog` (UTC),
  and to `WPILIB_yyyyMMdd_HHmmss_{event}_{match}.wpilog` when FMS supplies a match number.
- Startup deletes all leftover `WPILIB_TBD_*.wpilog`.
- Free-space guard (`:107, 340-...`): `FREE_SPACE_THRESHOLD = 50_000_000L`,
  `FILE_COUNT_THRESHOLD = 10` — deletes oldest `WPILIB_*.wpilog` until 50 MB free **or** 10
  files remain.
- Default flush period is `0.25` s (`start()` → `start("", "", 0.25)`).
- `DataLogManager.getLog()` returns the managed `DataLog`, auto-starting if needed.
- `DataLogManager.log(String)` appends to a `"messages"` `StringLogEntry` **and** prints to stdout.

`datalog` module classes (VERIFIED by listing
`~/dev/allwpilib/datalog/src/main/java/org/wpilib/datalog/`): `DataLog`, `DataLogWriter`,
`DataLogBackgroundWriter`, `DataLogEntry`, `DataLogReader`, `DataLogIterator`, `DataLogRecord`,
`DataLogJNI`, `FileLogger`, and typed entries `Boolean/Double/Float/Integer/String/Raw` +
their `*ArrayLogEntry` forms + `StructLogEntry`, `StructArrayLogEntry`, `ProtobufLogEntry`.

---

## 9. Type support — what's loggable out of the box

Dispatch order for the generic `log(String name, T value)` is a Java 21+ pattern switch,
**VERIFIED** at `TelemetryTable.java:399-545`. In order:

1. **`TelemetryLoggable`** → logged into a **child table** named `name`; `getTelemetryType()`
   written as `.type`.
2. **`StructSerializable`** → reflectively reads the `public static final Struct struct` field
   (cached in a `ClassValue`, `:34-40`) and calls `entry.logStruct`.
3. **`ProtobufSerializable`** → same via `public static final Protobuf proto` (`:42-48`).
4. **`Boolean`, `Float`, `Double`, `Byte`, `Short`, `Integer`, `Long`** → primitive entries.
   Byte/Short/Integer/Long all funnel to `logLong`.
5. **`Number`** (anything else, e.g. `BigDecimal`) → `logDouble(v.doubleValue())`.
6. **`String`** → `logString(v, "string")`.
7. **`Collection`** → warning, nothing logged (see §3).
8. **fallback** → `TelemetryRegistry.getTypeHandler(value)`; if none, **`entry.logString(value.toString(), "string")`**.

### Consequences worth internalising

- **Enums are NOT special-cased.** They fall through to the `toString()` fallback and land as
  plain strings. (VERIFIED by absence — there is no `case Enum` in the switch.) Fine for
  dashboards; means no ordinal/int encoding unless we add a handler.
- **Records are NOT auto-decomposed.** A record hits the `toString()` fallback too, unless it
  implements `TelemetryLoggable` or is struct-serializable. WPILib's own tests demonstrate the
  intended pattern — **VERIFIED**, `TelemetryTableTest.java:56-62`:
  ```java
  record Thing(double x, double y) implements TelemetryLoggable {
    @Override
    public void logTo(TelemetryTable table) {
      table.log("x", x);
      table.log("y", y);
    }
  }
  ```
- **Units (`Measure`) work, but only because `RobotBase` registers a handler.** VERIFIED,
  `RobotBase.java:228-232` registers `Measure.class` → `UnitTelemetry.log`, which writes the
  value **in base units** plus a `unit` property. `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/internal/UnitTelemetry.java:87-92`:
  ```java
  public static void log(TelemetryTable table, String name, Measure<?> value) {
    Unit baseUnit = value.unit().getBaseUnit();
    table.setProperty(name, "unit", getUnitMetadata(baseUnit));
    table.log(name, value.baseUnitMagnitude());
  }
  ```
  **Important:** in a unit test that does `TelemetryRegistry.reset()` without constructing a
  `RobotBase`, that handler is gone and `Measure` values silently degrade to `toString()`.
- Geometry/kinematics types (`Pose2d`, `SwerveModuleState`, …) are `StructSerializable`, so they
  Just Work with no ceremony. (INFERRED from the dispatch + the well-known `static final Struct
  struct` convention; the `Struct` interface is at
  `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/struct/Struct.java`.)

### Logging a custom type — three options

1. **Implement `TelemetryLoggable`** (simplest; produces a sub-table). Recommended default.
2. **Provide `public static final Struct<T> struct`** and implement `StructSerializable` —
   binary, compact, replayable in AdvantageScope. `Struct` requires `getTypeClass`,
   `getTypeName`, `getSize`, `getSchema`, `pack`, `unpack`; optional `isImmutable()` /
   `isCloneable()` / `clone()` (defaults: not immutable, not cloneable) — and **the mock backend
   warns if neither is true**, so set `isImmutable()` for value types.
3. **Register a global handler** for a type you don't own:
   ```java
   TelemetryRegistry.registerTypeHandler(MyType.class, (table, name, value) -> { ... });
   ```
   VERIFIED signature, `TelemetryRegistry.java:158`. Handlers are stored most-specific-first and
   re-registering the same `Class` replaces the previous one.

### Metadata

- `setProperty(name, key, value)` — **`value` must be a valid JSON value string** (quoted
  string, number, `true`, `false`, `null`, object, array). Both the NT and DataLog backends
  validate with `isValidPropertyJson` and reject otherwise. (VERIFIED in both backends.)
- `keepDuplicates(name)` — by default duplicate values are **suppressed**. On the NT backend
  this republishes the topic with `PubSubOption.KEEP_DUPLICATES`
  (`NetworkTablesTelemetryBackend.java:135-160, 162-...`). Relevant for us: a signal that sits
  at a constant value produces **no samples** unless we opt in — which matters a lot for
  post-match analysis with no replay.

---

## 10. Performance and the 20 ms loop

**VERIFIED facts (no published numbers exist in-tree; these are design properties I read):**

- **Entry lookup is cached per `TelemetryTable`** in a `ConcurrentMap<String, CachedEntry>`
  keyed by the *short* name, guarded by a reset generation counter
  (`TelemetryTable.java:53, 218-247`). So a repeated `table.log("Velocity", v)` is one
  `ConcurrentHashMap.get` + a generation compare, not a path concatenation + registry walk.
- **`Telemetry.log("a/b/c", v)` on the root table is NOT as cheap** — it still caches, but keyed
  on the full `"a/b/c"` string. Holding a child `TelemetryTable` is the cheaper idiom.
- **Primitive overloads avoid boxing entirely**; the generic `log(String, T)` boxes.
- **Discard is checked before serialization** on every path (`if (entry.isDiscard()) return;`),
  so turning a subtree off via `DiscardTelemetryBackend` is genuinely near-free.
- **Duplicate suppression is on by default**, so a constant value costs a comparison in NT
  rather than a network write.
- Arrays are **cloned** by `MockTelemetryBackend` (`value.clone()`) — that's test-only. The NT
  backend hands the array to the publisher.
- Struct logging goes through a cached `StructBuffer` per entry
  (`NetworkTablesTelemetryBackend.Entry.m_structBuffer`), so it reuses a `ByteBuffer` rather than
  allocating per call.

**WPILib maintains a dedicated allocation benchmark:**
`~/dev/allwpilib/benchmark/src/main/java/wpilib/robot/TelemetryTunableAllocationBenchmark.java`
(VERIFIED) — a JMH suite, `@BenchmarkMode(Mode.AverageTime)`,
`@OutputTimeUnit(TimeUnit.NANOSECONDS)`, with `primitiveTelemetry`, `arrayTelemetry`,
`objectTelemetry`, tunable-update and remote-tune benchmarks, and a `CountingTelemetryBackend`
to isolate backend cost. **No result numbers are committed**, so I cannot quote ns/op.

**No official "how much can you log in 20 ms" guidance exists in the repo.** (VERIFIED by
absence — `design-docs/` contains only `commands-v3.md`, `commands-v3-state-machines.md`,
`opmodes.md`, `real-time-thread-priorities.md`; none discuss telemetry budgets.)

**INFERRED guidance for us:** hold child `TelemetryTable`s as fields; prefer primitive overloads
and struct types over `toString()` fallback (string formatting is the expensive path); use
`DiscardTelemetryBackend` on debug subtrees for comp builds; measure with the JMH harness if it
ever becomes a question rather than guessing.

---

## 11. Relationship to Epilogue, NT4, and `datalog`

### Epilogue has been rewritten *on top of* telemetry — its own backend layer is gone

**VERIFIED.** `org.wpilib.telemetry` is the substrate; `org.wpilib.epilogue` is now a
pure compile-time code generator that emits `TelemetryTable.log(...)` calls.

- **`EpilogueBackend`, `NTEpilogueBackend`, `FileBackend`, `LazyBackend`, `NullBackend` no
  longer exist** — a repo-wide grep returns **zero** hits. Deleted, not renamed.
- `ClassSpecificLogger` now takes a `TelemetryTable` where it used to take an
  `EpilogueBackend` (`~/dev/allwpilib/epilogue-runtime/src/main/java/org/wpilib/epilogue/logging/ClassSpecificLogger.java:9,40,50`):
  ```java
  protected abstract void update(TelemetryTable table, T object);
  public final void tryUpdate(TelemetryTable table, T object, ErrorHandler errorHandler)
  ```
- `EpilogueConfiguration.java:22` binds straight to the telemetry root:
  ```java
  public TelemetryTable table = Telemetry.getTable();
  ```
- Dependency direction is **one-way**: `epilogue-runtime/build.gradle` declares
  `api(project(':telemetry'))`; `telemetry`'s own deps are only `wpiutil` + quickbuf.
  `wpilibj` does **not** depend on epilogue at all.
- Generated loggers **do not** implement `TelemetryLoggable` — they extend
  `ClassSpecificLogger<T>` and call into a table. `TelemetryLoggable` is what *data types*
  implement, and the processor's `TelemetryHandler` treats it as the **last-resort fallback**
  (after `@Logged` types, custom loggers, struct, and protobuf).
- Telemetry is a full Java + C++ + Python module; Epilogue is Java-only. Another sign of which
  is the primitive layer.

**So: Epilogue is genuinely optional and our explicit-logging decision is fully supported.**
Dozens of WPILib examples log with zero annotations. The one coupling runs the other way —
Epilogue cannot work without telemetry.

### `Sendable` has been DELETED

**VERIFIED**: `find -name "Sendable.java" -o -name "SendableBuilder.java"` returns **nothing**,
and the epilogue processor's old `SendableHandler` has been replaced by `TelemetryHandler`.
`TelemetryLoggable` is the successor. Any 2025 code or vendor library built on
`Sendable`/`SendableBuilder`/`SendableRegistry` **will not compile**. See §12e.

### Other relationships

- **NetworkTables is the default sink**, at prefix `/Telemetry`, installed by `RobotBase`.
- **`datalog` is a sibling**, reachable either via NT capture (`DataLogManager`) or directly via
  `DataLogTelemetryBackend`. See §8.
- The flagship WPILib swerve + Commands-v3 example
  (`~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/`) uses
  **`@Logged` + `Epilogue.update(this)`** (VERIFIED, `rebuiltcmdv3/Robot.java:14-15, 22, 48`).
  The annotation path is what WPILib showcases; explicit logging is fully supported but less
  exemplified.
- **Epilogue confirms our recommended test seam.** Its own runtime test does exactly what §6
  Seam B recommends — `~/dev/allwpilib/epilogue-runtime/src/test/java/org/wpilib/epilogue/logging/ClassSpecificLoggerTest.java:61-62`:
  ```java
  var backend = new MockTelemetryBackend();
  logger.update(new TelemetryTable(backend).getTable("Point"), point);
  ```
  That is independent corroboration that `new TelemetryTable(backend)` is the blessed seam.
- **`@Logged` surface** (VERIFIED, `epilogue-runtime/.../Logged.java`): `String name()`,
  `Strategy strategy()` (`OPT_OUT` default; `OPT_IN` implied for un-annotated classes),
  `Importance importance()` (`DEBUG`/`INFO`/`CRITICAL`), `Naming defaultNaming()`
  (`USE_CODE_NAME`/`USE_HUMAN_NAME`), `boolean warnForNonLoggableTypes()`.
- Note Epilogue's `EnumHandler` emits `table.log("name", access.name())` — so **Epilogue logs
  enums as name strings**, matching the telemetry module's own `toString()` fallback (§9).

---

## 12. Things that touch our locked decisions

### 12a. Commands v3 has **no** telemetry integration — plan for it

**VERIFIED**: grepping `commandsv3/src/main/java` for `Telemetry`/`TelemetryLoggable` returns
**zero** hits. Commands *v2*'s `Command` has a `logTo(TelemetryTable)`; **v3 does not.**

What v3 offers instead (VERIFIED, `commandsv3/src/main/java/org/wpilib/command3/Scheduler.java:88-102`):

> *"There are two mechanisms for telemetry for a scheduler. A protobuf serializer can be used to
> take a snapshot of a scheduler instance, and report what commands are queued …, commands that
> are running …, and total time spent in the most recent `run()` call. However, it cannot detect
> one-shot commands … effectively, commands that never call `Coroutine#yield()` are invisible.*
>
> *A second telemetry mechanism is provided by `addEventListener(Consumer)`. … **However, it is
> up to the user to log those events themselves.**"*

- `Scheduler implements ProtobufSerializable` with `public static final SchedulerProto proto`
  (`Scheduler.java:147-149`), so a snapshot is
  `Telemetry.log("Scheduler", Scheduler.getDefault(), Scheduler.proto)`.
- `SchedulerEvent` is a sealed interface with records `Scheduled`, `Mounted`, `Yielded`,
  `Completed`, `CompletedWithError(Command, Throwable, long)`, `Canceled`,
  `Interrupted(Command, Command interrupter, long)` — each carrying `timestampMicros`
  (VERIFIED, `SchedulerEvent.java:17-90`).
- `Mechanism` is a bare `public interface Mechanism` — it does **not** extend `TelemetryLoggable`
  (VERIFIED, `Mechanism.java:21`).

**Implication for us:** since we rejected AdvantageKit and have no replay, "what command was
running when it broke" is not free. We must write a `SchedulerEvent` listener that logs every
event — especially `CompletedWithError`, which is otherwise the only place a command exception
surfaces. Budget a work item for it.

### 12b. A whole new `org.wpilib.tunable` module we hadn't planned for

**VERIFIED**, `~/dev/allwpilib/tunables/src/main/java/org/wpilib/tunable/`: `Tunable`,
`TunableBase`, `TunableBoolean/Int/Long/Float/Double`, `TunableOption`, `ComplexTunable`,
`Selectable`, `TunableConfig`, `TunableTable`, `Tunables`, `TunableRegistry`, `TunableBackend`,
**`MockTunableBackend`**.

`RobotBase` wires it up alongside telemetry (VERIFIED, `RobotBase.java:234-247`):

```java
TunableRegistry.setReportWarning(m_tunableWarningReporter);
TunableRegistry.registerBackend("", new NetworkTablesTunableBackend(inst, "/Tunables"));
```

It mirrors the telemetry architecture exactly (prefix-routed pluggable backends, a mock backend,
`TunableRegistry.reset()`), but for **read-back / remote-settable** values under `/Tunables`.
`TunableConfig` has `withRobust`, `withMutable`, `withOnTune(Runnable)`, `withAlwaysGet`,
`withPolling`, `withTypeString`, `withProperty` (VERIFIED, `TunableConfig.java:157-260`).
`TunableRegistry.update()` is the periodic pump.

**This is the successor to `SmartDashboard.getNumber` / `SendableChooser`, and it is directly
relevant to config-as-code.** `Selectable` is presumably the auto-chooser replacement. We had
not scoped this. It deserves its own research issue.

### 12c. TimedRobot vs OpModeRobot — a real, but non-blocking, difference

`TimedRobot` still exists (`~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/TimedRobot.java`),
so **the locked "TimedRobot + Commands v3" decision is workable** — I found no hard coupling
forcing `OpModeRobot`.

But two observations (VERIFIED):
- WPILib's Commands-v3 examples (`rebuiltcmdv3`, `commandv3skeleton`, `hatchbotcmdv3`) use
  `OpModeRobot`, and there is a `design-docs/opmodes.md`. The v3 API surface includes
  `OpModeFetcher`, `BindingScope`, `SchedulerEvent` — built with opmodes in mind.
- **`OpModeRobot` raises a loop-overrun `Alert`; `TimedRobot`/`IterativeRobotBase` only print a
  DS warning.** If we stay on TimedRobot we lose that alert and should raise it ourselves.

Not evidence the decision is unworkable — just that we're off the paved path and will hand-roll
a bit more.

### 12d. `Sendable` is gone — audit vendor libraries before committing to REV/CTRE

**VERIFIED**: there is no `Sendable.java` or `SendableBuilder.java` anywhere in the tree.
`TelemetryLoggable` replaces it.

Our locked hardware decision is **REV SPARK + CTRE Pigeon2**. Historically both vendors'
Java classes implement `Sendable` and register with `SendableRegistry`. If their 2027 vendordeps
have not been ported to `TelemetryLoggable`, they will not compile against alpha-7 — and even
once ported, `Telemetry.log("Pigeon", pigeon)` will only produce a useful sub-table if the
vendor implements `TelemetryLoggable`; otherwise it silently falls back to `toString()`.

**This does not invalidate the hardware decision**, but it is an unplanned dependency: our
logging completeness for vendor devices depends on vendor porting work we don't control.
Mitigation is cheap and worth planning for anyway — write our own thin
`TelemetryLoggable` wrappers around SPARK and Pigeon2 that log exactly the signals we care
about (applied output, current, temp, faults, sticky faults, yaw/pitch/roll). Given
"logs must be deliberate and complete", we'd likely want that even if the vendors do port.

### 12e. Namespace / naming churn to expect

Everything is `org.wpilib.*` and packages moved a lot. Spotted in passing (VERIFIED from imports
in `rebuiltcmdv3/mechanisms/SwerveDrive.java`):
`org.wpilib.framework.*` (robot base classes), `org.wpilib.hardware.*`,
`org.wpilib.driverstation.*`, `org.wpilib.system.*` (Timer, DataLogManager, Watchdog),
`org.wpilib.math.kinematics.ChassisVelocities` (**was `ChassisSpeeds`**),
`org.wpilib.math.kinematics.SwerveModuleVelocity` (**was `SwerveModuleState`**),
`org.wpilib.networktables.*`, `org.wpilib.units.*`, `org.wpilib.util.*`.
Not a telemetry finding, but it will dominate porting effort.

---

## 13. Key takeaways for this project

1. **`Alert` exists — delete that risk from the map.** But update every planned call site:
   `org.wpilib.util.Alert`, `Alert.Level.HIGH/MEDIUM/LOW`, and a **mandatory unique `id`**.
   Duplicate `(group, id)` throws `AlertException`, so any loop that creates per-device alerts
   must generate unique ids. Alerts are `AutoCloseable`.
2. **Alerts are already the fault channel for telemetry itself.** `RobotBase` turns every
   `TelemetryRegistry.reportWarning` into a MEDIUM alert in group `"Telemetry"`. Our
   apply+verify+alert config loop should use the same `Alert` API so everything lands in one
   place. Consider a `"Config"` alert group.
3. **Telemetry IS unit-testable — use the injected-table seam, not the global registry.**
   `new TelemetryTable(new MockTelemetryBackend())` bypasses all global state. Make subsystems
   take a `TelemetryTable` in their constructor. This is the single highest-leverage design
   decision in this doc.
4. **Decide the WPILOG path explicitly, and soon.** Nothing writes a file by default.
   Either (a) `DataLogManager.start()` and rely on NT capture, or (b) register
   `MultiTelemetryBackend(NT, DataLog)` and `logNetworkTables(false)`. Given "no replay, logs
   must be deliberate and complete", **(b) is the better fit** — it decouples the on-disk record
   from NT connectivity and from NT's dedup behaviour.
5. **`keepDuplicates` matters more for us than for most teams.** With no replay, a constant-value
   signal producing zero samples can make a post-match log ambiguous. Opt in for anything where
   "it was still X at time T" is the question.
6. **Implement `TelemetryLoggable` on subsystems/records; prefer primitive overloads in loops.**
   Enums and records fall back to `toString()` — acceptable, but know it.
7. **Never log a `Collection` without an element type.** `Telemetry.log(name, list)` throws a
   checked exception; the generic path silently warns and drops.
8. **Budget work for Commands-v3 telemetry — it does not exist.** Log `Scheduler.proto`
   snapshots each loop *and* attach a `SchedulerEvent` listener (especially for
   `CompletedWithError`). One-shot commands are invisible to the proto snapshot.
9. **`setProperty` values must be JSON.** `"\"meters\""` not `"meters"`. Both backends silently
   reject invalid JSON.
10. **Scope a follow-up on `org.wpilib.tunable`.** It is the config-as-code / dashboard-tuning
    half of this story and mirrors the telemetry architecture (including `MockTunableBackend`).
11. **`Measure` logging depends on `RobotBase` being constructed.** In unit tests that call
    `TelemetryRegistry.reset()`, re-register the `Measure` handler or your units silently become
    `toString()` strings.
12. **If we keep TimedRobot, hand-roll the loop-overrun alert** that `OpModeRobot` gets for free.
13. **Audit the REV and CTRE 2027 vendordeps for `Sendable` removal early.** It's gone from
    WPILib. Plan on writing our own `TelemetryLoggable` wrappers for SPARK and Pigeon2
    regardless — that's the "deliberate and complete" answer anyway.
14. **Our explicit-logging decision is well-supported and low-risk.** Epilogue depends on
    telemetry, not the reverse; `wpilibj` doesn't depend on Epilogue at all; and dozens of
    WPILib examples log with zero annotations. We can ignore Epilogue entirely.

---

## 14. Open questions / unknowns

1. **How do alerts actually reach a dashboard in alpha-7?** Nothing in the allwpilib tree
   publishes them to NT. `Alert.h` exposes a pluggable `WPI_AlertBackend`, so the integration is
   presumably supplied by the SystemCore/DS runtime out-of-tree — **unverified**. If it isn't
   wired yet, our alerts may be invisible on the dashboard even though the API works. Worth
   testing on real hardware or in Glass before we depend on it.
2. **No published performance numbers.** The JMH harness exists but no results are committed.
   Per-call ns/op and allocation-per-log are unknown. Our loop budget is guesswork until measured.
3. **No WPILib guidance on logging volume in a 20 ms loop.** `design-docs/` says nothing about
   telemetry.
4. **`Selectable` semantics unexamined** — is it the `SendableChooser` replacement? Not read.
5. **The `.wpilog` binary format spec** was not located in-tree in this pass; the record and
   metadata model is inferred from the entry classes rather than a spec doc.
6. ~~Epilogue's internals~~ — **RESOLVED**, see §11. Epilogue's backend layer is deleted and the
   processor emits `TelemetryTable.log(...)`. One minor loose end: `epilogue-processor/build.gradle`
   still declares `api project(':commandsv2')`, which appears vestigial but was not exhaustively
   verified.
6b. **Have REV and CTRE shipped 2027 vendordeps yet, and have they ported off `Sendable`?**
   Not answerable from this checkout. Blocks nothing today but should be checked before the
   hardware bring-up work starts.
7. **`TelemetryRegistry` thread-safety in anger.** The source has careful generation-counter and
   retry loops around backend re-registration, implying re-registering backends at runtime is
   supported but subtle. If we ever swap backends mid-match (e.g. enable debug logging on the
   fly), test it hard.
8. **Alpha-7 is not final.** All of the above is a moving target; re-verify against the beta.

---

## 15. Source index

| Claim area | File |
| --- | --- |
| Facade | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/Telemetry.java` |
| Dispatch, caching, tables | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/TelemetryTable.java` |
| Routing, handlers, warnings | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/TelemetryRegistry.java` |
| Backend SPI | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/TelemetryBackend.java` |
| Entry SPI | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/TelemetryEntry.java` |
| Self-logging types | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/TelemetryLoggable.java` |
| Test backend | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/MockTelemetryBackend.java` |
| Fan-out | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/MultiTelemetryBackend.java` |
| Null sink | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/DiscardTelemetryBackend.java` |
| Path rules | `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/util/PathUtil.java` |
| Module tests / usage patterns | `~/dev/allwpilib/telemetry/src/test/java/org/wpilib/telemetry/TelemetryTableTest.java` |
| Default wiring, warning→alert | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/RobotBase.java` |
| NT sink | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/backend/NetworkTablesTelemetryBackend.java` |
| DataLog sink | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/backend/DataLogTelemetryBackend.java` |
| WPILOG files | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/system/DataLogManager.java` |
| Units handler | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/internal/UnitTelemetry.java` |
| Alerts (Java) | `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/Alert.java`, `AlertDataJNI.java`, `AlertException.java` |
| Alerts (native + backend SPI) | `~/dev/allwpilib/wpiutil/src/main/native/cpp/Alert.cpp`, `~/dev/allwpilib/wpiutil/src/main/native/include/wpi/util/Alert.h` |
| Alert behaviour | `~/dev/allwpilib/wpiutil/src/test/java/org/wpilib/util/AlertTest.java` |
| Warning→alert test | `~/dev/allwpilib/wpilibj/src/test/java/org/wpilib/framework/TimedRobotTest.java` |
| Commands v3 telemetry story | `~/dev/allwpilib/commandsv3/src/main/java/org/wpilib/command3/Scheduler.java`, `SchedulerEvent.java` |
| Tunables | `~/dev/allwpilib/tunables/src/main/java/org/wpilib/tunable/` |
| Perf harness | `~/dev/allwpilib/benchmark/src/main/java/wpilib/robot/TelemetryTunableAllocationBenchmark.java` |
| Example: explicit logging | `~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/encoder/Robot.java` |
| Example: v3 + swerve (annotation-based) | `~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/examples/rebuiltcmdv3/` |
| Epilogue runtime (rewritten on telemetry) | `~/dev/allwpilib/epilogue-runtime/src/main/java/org/wpilib/epilogue/` |
| Epilogue → telemetry test seam | `~/dev/allwpilib/epilogue-runtime/src/test/java/org/wpilib/epilogue/logging/ClassSpecificLoggerTest.java` |
| Epilogue codegen (emits `table.log`) | `~/dev/allwpilib/epilogue-processor/src/main/java/org/wpilib/epilogue/processor/` |
| Proof `TelemetryLoggable` replaced `Sendable` | `~/dev/allwpilib/epilogue-processor/src/main/java/org/wpilib/epilogue/processor/TelemetryHandler.java` |
