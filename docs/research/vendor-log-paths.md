# Where Phoenix 6 and REVLib write their logs on SystemCore

**Research for:** [Drew-Robotics/2027beta#135](https://github.com/Drew-Robotics/2027beta/issues/135)
**Date:** 2026-09-20

## Source and trust level

- **[source]** — read out of source code, a shipped binary, or official vendor
  documentation, cited by path and line or by URL. Binary claims name the
  function and address they were disassembled from; §9 has the commands.
- **[executed]** — observed by running something, on this workstation or on the
  robot. §8 marks which.
- **[decided]** — a project decision.
- **[unverified]** — believed but unconfirmed. Each one says what would confirm it.

A claim about a *string inside a binary* is `[source]`. A claim about what the
robot *does* is `[executed]` only where §8 ran it on the device.

§1–§7 are the source and binary pass. **§8 is the device pass, and it overrides
them where they disagree** — it corrects §5.1 on REVLib's actual path and §0 on
whether REVLib writes anything at all.

### What was under test

| Thing | Coordinate |
| --- | --- |
| Phoenix 6 Java | `com.ctre.phoenix6:wpiapi-java:26.70.0-alpha-2` (`vendordeps/Phoenix6-26.70.0-alpha-2.json:4`) |
| Phoenix 6 native, **device** | `com.ctre.phoenix6:tools:26.70.0-alpha-2:linuxsystemcore` → `libCTRE_PhoenixTools.so`, `sha256 f58c4394c60bb087a33e495f529ae4190756e096e6ae4d44aedb718172a152ea` **[executed]** |
| Phoenix 6 native, **desktop** | `com.ctre.phoenix6.sim:tools-sim:26.70.0-alpha-2:osxuniversal` → `libCTRE_PhoenixTools_Sim.dylib` |
| REVLib Java | `com.revrobotics.frc:REVLib-java:2027.0.0-alpha-7` (`vendordeps/REVLib.json:4`) |
| REVLib native, **device** | `com.revrobotics.frc:REVLib-driver:2027.0.0-alpha-7:linuxsystemcore` → `libREVLibDriver.so`, `sha256 b22cd869b12ceae33e7fe03839bb1809447897007d5d4281e730243274128319` **[executed]** |
| REVLib native, **desktop** | `com.revrobotics.frc:REVLib-driver:2027.0.0-alpha-7:osxuniversal` → `libREVLibDriver.dylib` |
| WPILib | `~/dev/allwpilib` at tag `v2027.0.0-alpha-7` |

Both `linuxsystemcore` natives ship an unstripped `.symtab`, so every claim below
names a real C++ symbol rather than a guessed offset. **[source]**

---

## 0. Bottom line

1. **`SignalLogger.setPath(String)` works on SystemCore.** It is not a no-op and
   not a stub. It reaches a 1556-byte implementation in the shipped
   `linuxsystemcore` native that validates the directory, stops a running log
   and stores the path. **[source]** §1.
2. **Phoenix's default on SystemCore is `/u/logs` if `/u` is a mounted
   directory, otherwise `/home/systemcore/logs`.** Exactly one mount point is
   probed — `/u` — which is the same rule WPILib's own `DataLogManager` uses at
   the alpha-7 tag. **[source]** §2. Confirmed on our bench Pi, which has no
   USB attached: both the robot's own hoots and a probe run from `/tmp` land in
   `/home/systemcore/logs`. **[executed]** §8.1.
3. **REVLib creates a file, in the wrong place, and never writes to it.** It
   computes a directory as `current_working_directory + "/logs"` and creates it
   **[source]**, then composes the filename onto that string without a
   separator, so the file lands *beside* the directory as
   `<cwd>/logsREV_<stamp>.revlog` — `/home/systemcore/logsREV_*.revlog` under
   `robot.service`. **[executed]** §8.4. All 55 on the device are **zero
   bytes**, because REVLib's daemon thread segfaults at its first error flush
   before writing anything. **[executed]** §8.5.
4. **`.revlog` is proprietary and `logtool` cannot read it** — unchanged from
   ADR 0009 — and on this platform there is nothing in one to read. §6, §8.5.

Two findings that were not in the ticket and matter more than the answers:

- **REVLib's logger never starts in desktop simulation.** `c_Spark_Create` calls
  `c_SIM_Spark_IsSim` and *branches around* `c_REVLib_RunDaemon` when the device
  is simulated; the daemon is the only caller of the logger's start.
  **[source — `c_Spark_Create+0x184`…`+0x18c`]** That is why this working copy
  holds 18 `.hoot` files and **zero** `.revlog` files **[executed]**, and why
  "no REVLib artifact appears anywhere in the working copy" is *not* evidence
  that REVLib writes nothing. §4.3.
- **REVLib's SystemCore native still carries the roboRIO path
  `/home/lvuser/logs/` and compares against it at runtime.** It is a live
  comparison, not dead data, and on SystemCore it can never match — which
  silently disables REVLib's "a flash drive was detected" hint. **[source]** §4.4.

---

## 1. `SignalLogger.setPath` on SystemCore

### 1.1 The Java call reaches native code by Panama, not JNI **[source]**

`SignalLogger.setPath(String)` allocates the string in a confined `Arena` and
calls `SignalLoggerNative.c_ctre_phoenix6_platform_set_logger_path(MemorySegment)`,
converts the returned `int` with `StatusCode.valueOf`, and reports a non-OK code
through `ErrorReportingNative.reportStatusCode(code, "ctre.phoenix6.SignalLogger.setPath")`.
The downcall handle is built with `SymbolLookup.findOrThrow("c_ctre_phoenix6_platform_set_logger_path")`,
so a missing symbol would throw at class-init rather than silently no-op.
**[source — `javap -c com.ctre.phoenix6.SignalLogger`, `…ffi.SignalLoggerNative`]**

### 1.2 The symbol is exported and forwards to real code **[source]**

`libCTRE_PhoenixTools.so` (linuxsystemcore) exports it as a one-instruction
tail call:

```
0006a800 <c_ctre_phoenix6_platform_set_logger_path>:
   6a800: b  <ctre::phoenix::platform::can::CANComm_SetLoggerPath(char const*)@plt>
```

`CANComm_SetLoggerPath` is defined in the *same* library at `0x173920` — there
is no unresolved dependency — and it is a virtual dispatch on
`BaseHootLogger::GetInstance()`, vtable slot `+0x18`. **[source]**

### 1.3 The instance on SystemCore is the real logger, not the stub **[source]**

The library defines two classes: `BaseHootLogger` (whose `Start`/`Stop`/
`LogUserSignal` are 8-byte stubs at `0x2298c0`–`0x2298e0`) and `HootLogger`
(the real one — `Start` `0x212a90`, `Background` `0x214040`,
`UpdateCANSockets` `0x213348`).

`BaseHootLogger::GetInstance()` at `0x212f00` allocates 464 bytes and writes the
vtable pointer `0x37f608`, which is `_ZTVN4ctre7phoenix8platform3can10HootLoggerE`
(`0x37f5f8`) `+ 0x10` — i.e. **it constructs `HootLogger`**. **[source]**

This matters because it is the only thing `SetPath` checks before refusing: see
§1.4. On this platform it cannot refuse for that reason.

### 1.4 What `setPath` actually does, and every code path out of it **[source]**

`BaseHootLogger::SetPath(char const*)` at `0x229a80`, 0x614 bytes. It is defined
on the *base* class and is not overridden, so the same implementation serves
every platform. Decoded:

| Condition | Return | Effect |
| --- | --- | --- |
| Argument equals the current path | `OK` (0) | nothing; the logger keeps running |
| `fs::status(arg)` reports `none`/`not_found` | **`DirectoryMissing`** (−10029) | path **not** applied |
| Active logger's vtable `Stop` slot is `BaseHootLogger::Stop` (the stub) | **`FeatureNotSupported`** (−125) | path **not** applied |
| otherwise | `OK`, or whatever `Stop()` returned | calls `Stop()`, then stores the path |
| Argument is the empty string | — | re-resolves the platform default (§2) |

Status-code numbers from `StatusCode.java:122` (`FeatureNotSupported(-125)`) and
`:741` (`DirectoryMissing(-10029)`) in the `26.50.0-alpha-1` sources jar
**[source]**; the enum values are identical in the `26.70.0-alpha-2` bytecode
**[source]**.

Two consequences worth writing down:

- **The directory must already exist.** `setPath` does not create the directory
  you hand it; it only creates the *default* directories (§2). CTRE's own docs
  agree: *"If the directory does not exist, this function will return an
  error"* — [CTRE, Signal Logging](https://v6.docs.ctr-electronics.com/en/latest/docs/api-reference/api-usage/signal-logging.html)
  **[source]**.
- **`setPath` stops a running log.** If auto-logging has already started (1–5 s
  after boot on SystemCore — `docs/research/vendordeps.md:1245-1257`), calling
  `setPath` later closes that `.hoot` and the *next* start writes to the new
  directory. Calling it before the first Phoenix device is constructed avoids a
  stray file in the default directory.

`BaseHootLogger::GetPath()` at `0x229970` returns the stored path when one is
set and re-resolves the default otherwise. There is a
`CANComm_GetLoggerPath()` export at `0x173950` with **no Java binding**, so the
path cannot be read back from Java. **[source]**

**Answer to sub-question 1: `setPath` works on SystemCore. [source]** The only
`[unverified]` residue is behavioural confirmation on the device — see §8.

---

## 2. Phoenix's default path, and its probing order

### 2.1 The rule **[source]**

`BaseHootLogger::SetPath`/`GetPath` both inline
`ctre::phoenix6::hoot::HootWriterMgr::GetDefaultPath(bool)`, identified by its
two function-static guards, which are the only symbols left of it:

```
0x383be8  HootWriterMgr::GetDefaultPath(bool)::hasCreatedDir      (1 byte)
0x383bf0  HootWriterMgr::GetDefaultPath(bool)::hasCreatedUsbDir   (1 byte)
```

The inlined body does exactly one probe:

1. Build a `std::filesystem::path` from the 2-byte literal `"/u"` — materialised
   inline as `mov w1, #0x752f` / `strh` (bytes `2f 75`), not as a rodata string.
2. `std::filesystem::status("/u", ec)`.
3. If `ec == 0` **and** the type byte is `2` (`file_type::directory`) **and**
   bit 33 of the returned `file_status` pair is set — that is bit 1 of the
   `perms` half, `perms::others_write` (`0o002`) — use **`/u/logs`**
   (rodata `0x2891a0`, length 7).
4. Otherwise use **`/home/systemcore/logs`** (rodata `0x2891a8`, length 21).
5. `fs::create_directory` the chosen directory once, guarded by the matching
   `hasCreated*Dir` flag.

The permission-bit decode in step 3 is mine, from
`tbnz x19, #0x21`; the rest is literal. **[source]**

**There is no wider media scan.** No `/media`, `/mnt`, `/run/media`, `/U`, `/V`,
`/proc/mounts`, `getmntent` or `getenv` appears anywhere in the resolution path,
and the only absolute-path string literals in the whole library are `/u/logs`,
`/home/systemcore/logs`, `/home/systemcore/deploy/`, `/usr/bin/canivore_setup`
and `/sys/bus/usb/devices`. **[source — `strings libCTRE_PhoenixTools.so`]**

### 2.2 WPILib uses the identical rule, which is where `/u` comes from **[source]**

`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java` at tag
`v2027.0.0-alpha-7`:

```java
241:  private static String makeLogDir(String dir) {
249:        Path usbDir = Paths.get("/u").toRealPath();   // prefer a mounted USB drive
250:        if (Files.isWritable(usbDir)) {
255:          return "/u/logs";
264:      return "/home/systemcore/logs";
266:    String logDir = Filesystem.getOperatingDirectory().getAbsolutePath() + "/logs";
```

and its class javadoc (`:37-38`): *"The data file will be saved to a USB flash
drive in a folder named `"logs"` if one is attached, or to
`/home/systemcore/logs` otherwise."* **[source]**

So on SystemCore, **"removable media mounted" means precisely one thing: `/u`
resolves to a writable directory.** Phoenix tests a permission bit where WPILib
calls `Files.isWritable`, but the mount point and the fallback are the same
string. This also **answers open question 1 of `docs/research/systemcore-deploy.md:1099`**
for WPILib's own `DataLog`.

### 2.3 The desktop default, for contrast **[source]** / **[executed]**

`libCTRE_PhoenixTools_Sim.dylib` (osxuniversal) contains **no** absolute path
literal — no `/u/logs`, no `/home/systemcore/logs` — and defines only
`GetDefaultPath(bool)::hasCreatedDir`, with **no `hasCreatedUsbDir`**: the USB
branch is compiled out of the desktop build entirely. **[source]**

That matches what this working copy shows: 43 timestamped directories under
`logs/`, holding 18 `.hoot` files, named `logs/<YYYY-MM-DD_HH-MM-SS>/<YYYY-MM-DD_HH-MM-SS>.hoot`
**[executed — 2026-09-20]**. The timestamp format in the binary is `%F_%T`
(rodata `0x2893a0`) with extension `.hoot` (`0x2893a8`) **[source]**; the
colons `%T` produces are evidently rewritten to dashes on the way to disk, which
is **[unverified]** as to where.

CTRE documents the layout: *"Each CAN bus gets its own dedicated log file. All
logs will be placed in a subfolder named after the date and time of the start of
the program."* — [CTRE, Signal Logging](https://v6.docs.ctr-electronics.com/en/latest/docs/api-reference/api-usage/signal-logging.html)
**[source]**.

### 2.4 What the prose docs do and do not say **[source]**

CTRE's Signal Logging page is still written for the roboRIO: it says logging is
*"enabled by default on a roboRIO 1 with a USB flash drive or a roboRIO 2"* and
its `setPath` example is `SignalLogger.setPath("/media/sda1/ctre-logs/")` — a
roboRIO mount point that does not exist on SystemCore. **It names no SystemCore
default path at all.** The alpha javadoc's auto-logging note
(`docs/research/vendordeps.md:1245-1257`) is the only place CTRE acknowledges
SystemCore, and it covers *when* logging starts, not *where*. **[source]**

**Answer to sub-question 2: `/u/logs` when `/u` is a writable directory,
`/home/systemcore/logs` otherwise; one probe, no wider scan. [source]**

---

## 3. Phoenix's disk-pressure policy, since it decides what a `pull` finds

All `[source]`, from rodata `0x2893b0`–`0x289568` and the
`HootWriterMgr::DeleteOldLogs(unsigned long)` (`0x22122c`) and
`ProcessFreeSpace()` (`0x223ae0`) symbols:

- *"Signal Logger: Available disk space (N MB) below M MB, but number of hoot
  logs found (K) is not greater than L. Logging will stop once available disk
  space is below N MB. Use Tuner X to download and delete hoot logs."*
- *"… MB) is low; at N MB, old hoot logs will be deleted."*
- *"… logs have been stopped"*, *"… stopping log"*,
  *"… could not start log at \"…\""*
- `[phoenix] Signal Logger Started at "<path>"` (`0x2895d8`) and
  `[phoenix] Signal Logger Failed to Start at "<path>"` (`0x2895a8`) —
  **these two lines print the resolved path to the console**, which is the
  cheapest device-side confirmation of §2 (see §8).
- Three `Alert`s in group `Phoenix 6` (`0x2891c0`): `Signal Logger Failed Start`,
  `Signal Logger Low Storage`, `Signal Logger Stopped`.

CTRE documents the threshold as *"If the target drive reaches 5 MB of free
space, logging will be stopped"* **[source — CTRE Signal Logging]**. The numeric
constants in this build were not read out; **[unverified]** whether the
SystemCore build uses 5 MB.

---

## 4. REVLib: does it write a file?

### 4.1 The Java surface is what the ticket says it is **[source]**

```
public class com.revrobotics.util.StatusLogger {
  public static void start();
  public static void stop();
  public static void disableAutoLogging();
}
public class com.revrobotics.jni.StatusLoggerJNI extends RevJNIWrapper {
  public static native void start(); stop(); disableAutoLogging();
}
```

`javap -p` on `REVLib-java-2027.0.0-alpha-7.jar`. No `setPath`, no getter, no
path argument anywhere. The alpha-6 sources jar carries the javadoc — *"start
capturing data from REV devices to a REV binary log (.revlog)"*, *"logging
begins automatically on the first call to any REVLib function"*
(`StatusLogger.java:35`, `:38-39`) **[source]**.

### 4.2 It writes a real file, and here is the code that opens it **[source]**

`libREVLibDriver.so` (linuxsystemcore) exports
`Java_com_revrobotics_jni_StatusLoggerJNI_{start,stop,disableAutoLogging}`
(`0x415c0`, `0x415c4`, `0x415c8`), which are 4-byte tail calls into
`StatusLoggerDriver_manualStart` (`0x51ff0`), `_stop` (`0x4f7e0`) and
`_disableAutoLogging` (`0x4f800`).

`StatusLoggerDriver_start` (`0x51108`, 0xee8 bytes) calls
`std::basic_filebuf<char>::open` and prints one of:

- `StatusLogger: Logging REVLOG to '<path>'` (rodata `0x7e508`)
- `StatusLogger: Failed to open log file at <path>` (`0x7e4d8`)

**[source]** That is a file on disk, not NetworkTables and not stderr-only.
There is no NetworkTables symbol anywhere in the logger's call graph, and
`libREVLibWpi.so` and `libBackendDriver.so` contain no logging strings at all
**[source]**.

### 4.3 …but only when the REVLib daemon runs, which excludes desktop sim **[source]**

The start chain is:

```
c_Spark_Create (0x48370)
  └─ c_SIM_Spark_IsSim  → if sim, SKIP the next call        ← 0x484f4 / 0x484f8
  └─ c_REVLib_RunDaemon (0x351f0)
       └─ c_REVLib_InitDaemon (0x34c48)
            └─ StatusLoggerDriver_getAutoLogging (0x4f810)
                 └─ if set: tail-call StatusLoggerDriver_start   ← 0x34d34
```

`autoLogging` is a 1-byte `.data` symbol at `0xd0988` whose **initial value is
`1`** in the linuxsystemcore build — and also `1` at `0x74050` in the arm64
slice of the osxuniversal build. **Auto-logging is on by default on both
platforms.** `StatusLoggerDriver_disableAutoLogging` clears that byte and tail
calls `_stop`; `StatusLoggerDriver_start` returns immediately if the `running`
flag (`.bss`, `0xd4721`) is already set. **[source]**

The `c_SIM_Spark_IsSim` guard is the whole explanation for the ticket's premise.
**A simulated SPARK never starts the daemon, so it never starts the logger.**
This working copy has 18 `.hoot` files and **zero `.revlog` files anywhere**,
including `build/` **[executed — 2026-09-20]**, and the test run that produced
the newest of them (`build/test-results/test`, 13:43) exercises only static
`SwerveModule` math, never a `SparkFlex` constructor **[source —
`src/test/java/first/robot/mechanisms/SwerveModuleTest.java`]**. The absent
`.revlog` is therefore evidence about *simulation*, not about the device.

This also sharpens ADR 0009's third limitation — *"It has never run on
SystemCore here"* (`docs/adr/0009-characterisation-and-tuning.md:518-523`). It
has never run **anywhere** here, and could not have.

### 4.4 The SystemCore native still carries roboRIO paths **[source]**

`libREVLibDriver.so` (linuxsystemcore) contains the rodata strings
`/home/lvuser/logs/` (`0x7e590`, reached as `0x814e0` + `"s/"`), `/logs`
(`0x7e4b0`), `.revlog` (`0x7e4d0`) and
*"StatusLogger: A file write operation failed. Stopping logger. Please reboot
your **roboRIO**"* (`0x7e530`).

`/home/lvuser/logs/` is not dead data. At `StatusLoggerDriver_read+0x678`
(`0x52f18`) the code compares the *current log directory* against it — length
`0x12`, a 16-byte SIMD compare against `0x814e0`, then a 2-byte compare against
`"s/"` — and only if it matches prints
*"StatusLogger: A flash drive was detected. Restart your robot code to write to
the drive."* (`0x7e5a8`).

On SystemCore the log directory is never `/home/lvuser/logs/` (§5), so **that
branch is unreachable and REVLib's USB hint is silently dead on this
platform.** **[source]** Whether REVLib has any *other* removable-media
handling on SystemCore: no `/media`, `/mnt`, `/u`, `getmntent`, `statvfs` or
`getenv` appears in the logger's strings or call graph, so the answer looks like
**no** — **[unverified]**, because absence-of-string is weaker than presence.

**Answer to sub-question 3: yes, REVLib writes a `.revlog` file, on the device;
it writes nothing in desktop simulation. [source]**

---

## 5. Where REVLib writes it, and in what format

### 5.1 The directory is the process's working directory plus `/logs` **[source]**

`StatusLoggerDriver_start` opens with:

```
51108: ldr  x0, [GOT + 0xd28]        ; `running` flag; return if already set
5115c: bl   std::filesystem::current_path()
511ec: add  x0, x0, #0x4b0           ; rodata 0x7e4b0 = "/logs"
   …   str  x1, [GOT + 0xed0]        ; store into the global log-dir string
   …   bl   std::filesystem::create_directory(path, ec)
```

The global at `GOT+0xed0` is the same object `StatusLoggerDriver_read` compares
against `/home/lvuser/logs/` in §4.4, and the same one
`_GLOBAL__sub_I_StatusLoggerDriver.cpp` (`0x14340`) registers for destruction.
So the log directory is **`cwd + "/logs"`, created if absent**, with no
environment variable and no override. **[source]** The directory is created; the
`.revlog` does not go in it. See §8.4 — the string this builds has no trailing
slash, §5.2's filename is concatenated straight onto it, and the file lands as a
sibling named `logsREV_…`. **[executed]**

`robot.service` sets `WorkingDirectory=/home/systemcore`
(`docs/research/systemcore-deploy.md:541`, **[VERIFIED-DEVICE]** there), so on a
deployed robot REVLib's *intended* directory is **`/home/systemcore/logs`** —
the same directory Phoenix falls back to and the same one `DataLogManager` uses.
It does not get there. **§8.4 corrects this: the file is written to
`/home/systemcore/logsREV_<stamp>.revlog`, one character short of the shared
directory.** **[executed]** Phoenix and `DataLogManager` do share it; REVLib
creates it, leaves it empty and writes beside it.

Note what this does **not** do: REVLib never probes `/u`. If a USB drive is
mounted, Phoenix and WPILib move to `/u/logs` and REVLib stays on internal
storage. **[source]**

### 5.2 Filenames **[source]**

`StatusLoggerDriver_start` builds `REV_TBD_` (rodata `0x7e4c0`) + **16**
characters drawn from `0123456789abcdef` (`0x73e70`) by `std::random_device`
(the loop counter is `mov w25, #0x10` at `0x514a4`) + `.revlog`.

`StatusLoggerDriver_read` (`0x528a0`) later `rename()`s it using `REV_`
(`0x7e618`) + `strftime`/`gmtime` with `%Y%m%d_%H%M%S` (`0x7e608`), printing
*"StatusLogger: Renamed REVLOG from '…' to '…' at '…'"*. The literal `default`
(`0x7e4b8`) is used in the same construction and is most likely the placeholder
event name — **[unverified]**.

This is a copy of WPILib's own scheme — `WPILIB_TBD_{random}.wpilog`, renamed to
`WPILIB_yyyyMMdd_HHmmss[_{event}_{match}].wpilog`
(`DataLogManager.java:40-43` at `v2027.0.0-alpha-7`) **[source]**. So a
`logtool pull` glob written for WPILOGs transfers to `.revlog` with only the
prefix and extension changed.

### 5.3 Disk policy **[source]**

From the same function: *"Log storage device has less than N MB of free space
remaining. Logging has been stopped automatically due to low disk space."*,
*"… Deleted '…'"*, *"StatusLogger: could not delete '…'"*, and
*"Log storage device has N MB of free space remaining. REVLOGs will get deleted
below M MB. Consider deleting logs off the storage device."* It sorts
`directory_entry`s and deletes oldest-first (`std::__introsort_loop<…
StatusLoggerDriver_read::'lambda'…>`). The thresholds were not read out —
**[unverified]**.

### 5.4 Format **[source]** / **[decided]**

`.revlog` is a proprietary REV binary format. Nothing in this repo reads it,
`logtool` reads WPILOG only, and converting one needs AdvantageScope or the npm
package `@rev-robotics/revlog-converter` — a Node toolchain in a repo whose only
tool is Python `uv run`. That is ADR 0009's second limitation
(`docs/adr/0009-characterisation-and-tuning.md:513-517`) and ADR 0014's contract
boundary; nothing found here changes it. **[decided]**

**REV documents none of this.** `docs.revrobotics.com` returns 404 for a
status-logger page, and its own `llms-full.txt` aggregate contains no
occurrence of "Status Logger", "revlog" or any file location — fetched
2026-09-20 **[source]**. The binary is the only primary source there is.

**Answer to sub-question 4: `<cwd>/logs/REV_TBD_<16 hex>.revlog`, renamed to
`REV_<UTC yyyymmdd_HHMMSS>….revlog`; proprietary format, unreadable by
`logtool`. [source]**

---

## 6. What this means for #114 (`logtool pull`)

Stated as consequences, not decisions — #114 owns the decision.

1. **No fallback is needed for `setPath`.** It works (§1). If #114 wants one
   directory for everything, `SignalLogger.setPath(dir)` before the first
   Phoenix device is constructed is sufficient, and the directory must be
   `mkdir`'d first because `setPath` will not create it (§1.4).
2. **Doing nothing converges for two of the three.** With no USB attached,
   Phoenix and `DataLogManager` both write under `/home/systemcore/logs`
   — confirmed on the device (§8.1). **REVLib does not**: it writes
   `/home/systemcore/logsREV_*.revlog`, a sibling of that directory, not a
   child (§8.4). **[executed]** A `pull` that globs the one directory for
   `*.hoot` and `*.wpilog` gets everything worth having; it will not see a
   `.revlog`, and §8.5 says there is nothing in one to see. Note also that the
   hoot sits one `<timestamp>/` level down (§8.2), so the glob must recurse.
3. **A USB drive splits them.** Mount one at `/u` and Phoenix and WPILib move to
   `/u/logs` while REVLib stays put (§5.1). Any `pull` that assumes one
   directory breaks the moment someone plugs in a stick.
4. **`.revlog` is not worth transferring today** (§5.4), and on this platform
   it is empty besides (§8.5). It is worth *deleting*: REVLib's rotation
   competes for the same free space as Phoenix's, and both delete oldest-first
   on the same disk. 55 zero-byte files and an unused `logs/` directory are the
   whole of what REVLib has produced here since 2026-09-06.
5. **REVLib's auto-start is one CAN-device constructor away** and on by default
   (§4.3). If #114 decides it is not worth the bytes,
   `StatusLogger.disableAutoLogging()` must run before *any* REVLib call — the
   javadoc says first line of robot init (`StatusLogger.java:68-69`) — and ADR
   0015 records that on its own it is not sufficient for the older shim problem,
   which no longer applies.

---

## 7. Open questions

1. **Does Phoenix's `/u` permission test behave like `Files.isWritable`?**
   Phoenix tests `perms::others_write` on the `fs::status` result (§2.1); WPILib
   calls `Files.isWritable` (§2.2). A FAT mount usually shows `0777` so both
   pass, but a drive mounted `0755` would make WPILib write to `/u/logs` and
   Phoenix fall back to `/home/systemcore/logs`. Settled by mounting a stick
   with a restrictive `umask` and reading both `Started at "…"` lines.
2. **Phoenix's and REVLib's free-space thresholds in *these* builds.** CTRE
   documents 5 MB; REVLib documents nothing; neither constant was read out of
   the binary (§3, §5.3).
3. ~~**Does anything rewrite `%F_%T`'s colons?**~~ Settled on the device: the
   directory names are `2026-09-20_14-07-56`, dashes throughout, so something
   does (§8.7). **[executed]**
4. **Is REVLib's `default` literal the event-name placeholder?** (§5.2)
5. **Does REVLib have any removable-media handling on SystemCore at all?** The
   only one found is dead (§4.4), and absence-of-string is weak evidence.

---

## 8. On the device

Run against the team's SystemCore at `systemcore@192.168.1.202` on 2026-09-20,
while `robot.service` ran this project's own `2027beta.jar` (`first.Main`,
deployed 13:58 PDT the same day) with no Driver Station attached.

`LIMELIGHTOS_SYSTEMCORE_BETA`, kernel `6.12.77-v8-16k` aarch64 PREEMPT_RT,
Temurin 25.0.2 **JDK** (so `javac` and single-file source launch are available
on the device). Single ext4 root on `/dev/nvme0n1p5`. **No removable media was
mounted** — `/u`, `/U`, `/v`, `/V` do not exist, `/media` and `/mnt` are empty —
so every path below is §2.1's no-USB branch. **[executed]**

### 8.1 Phoenix's default is `/home/systemcore/logs`, and it is absolute

Three `.hoot` files predate this session, one per robot run on 2026-09-07:

```
/home/systemcore/logs/2026-09-07_03-55-08/can_s0_2026-09-07_17-02-32.hoot   409111
/home/systemcore/logs/2026-09-07_10-10-01/can_s0_2026-09-07_10-10-06.hoot   244933
/home/systemcore/logs/2026-09-07_10-14-01/can_s0_2026-09-07_10-14-06.hoot   689355
```

§2.1's rule, confirmed: `/u` absent → `/home/systemcore/logs`, with §2.3's
`<dir>/<%F_%T>/<…>.hoot` layout and one file per CAN bus. **[executed]**

The directory is **not** cwd-relative. A probe calling `SignalLogger.start()`
with cwd `/tmp/hootprobe/ctrl` wrote
`/home/systemcore/logs/2026-09-20_14-07-11/2026-09-20_14-07-12.hoot` and left
nothing under cwd. **[executed]** Its leaf was `<timestamp>.hoot`, not
`can_s0_*`, because the probe constructed no CTRE device — the bus prefix tracks
what is being logged, not the platform. **[executed]**

### 8.2 `setPath` works, and fails silently on a missing directory

Same probe, `setPath` before `start()`:

| call | returned | result on disk |
| --- | --- | --- |
| `setPath("/tmp/hootprobe/target/")`, directory **absent** | `DirectoryMissing` | nothing at the target; the hoot went to `/home/systemcore/logs/2026-09-20_14-07-33/` |
| `setPath("/tmp/hootprobe/target/")`, directory **present** | `OK` | `/tmp/hootprobe/target/2026-09-20_14-07-56/2026-09-20_14-07-56.hoot` |
| `setPath("/tmp/hootprobe/target2")`, no trailing slash | `OK` | `/tmp/hootprobe/target2/2026-09-20_14-08-08/` |

**[executed]** §1.4's decoded return paths are exactly what the device does.
Three consequences for a caller:

- It does not create the directory, and **a `DirectoryMissing` that nobody reads
  looks exactly like success** — logging still happens, just somewhere else.
- The trailing slash does not matter.
- It sets the *parent*. The hoot still lands one `<timestamp>/` level below the
  path given, so anything copying hoots has to recurse.

### 8.3 Auto-logging never fires without a Driver Station

No robot run since 2026-09-07 has produced a `.hoot`, though every run since
constructs a `Pigeon2` (`Drive.java:145`). That is the documented trigger, not a
fault: auto-logging starts at 1 s **if the robot is enabled**, or at 5 s **if
the Driver Station is connected** (`docs/research/vendordeps.md`, quoting the
`enableAutoLogging` javadoc). A bench boot with neither reaches no trigger and
writes no hoot at all. Consistent with the filenames: the 2026-09-07 runs are
the only ones whose WPILOGs carry real timestamps — `WPILIB_20260907_*` rather
than `WPILIB_TBD_*` — i.e. the only runs a Driver Station ever attached to.
**[executed]**

### 8.4 REVLib creates `logs/`, then writes its file *beside* it

§5.1 derives the directory as `current_path() + "/logs"`, created if absent.
The device confirms the `create_directory` — and shows that the file does not
go in it. `/home/systemcore` holds **55** files named

```
logsREV_<YYYYMMDD>_<HHMMSS>.revlog
```

one per robot process start since 2026-09-06, as **siblings** of
`/home/systemcore/logs`, not inside it. A probe run from a directory that had no
`logs/` produced both an **empty** `logs/` directory and a
`logsREV_TBD_a21bf1d38b8a3774.revlog` file beside it. **[executed]**

So the global log-dir string §5.1 builds is `"<cwd>/logs"` with **no trailing
slash**, and §5.2's filename is concatenated straight onto it. The roboRIO
literal in §4.4, `/home/lvuser/logs/`, carries its trailing slash and composes
correctly; the computed fallback does not. On a roboRIO REVLib writes
`/home/lvuser/logs/REV_<stamp>.revlog`; here it writes `<cwd>/logsREV_<stamp>.revlog`.
**[executed]** This is a REVLib defect, not a configuration choice, and it means
**§5.1's "three vendors, one directory" is wrong**: Phoenix and `DataLogManager`
share `/home/systemcore/logs`, and REVLib misses it by one character.

§5.2's naming is confirmed in both states: the probe's file kept the
`REV_TBD_<16 hex>` form because its JVM died before the rename, and every file
the robot produced carries the renamed `REV_<%Y%m%d_%H%M%S>` form in **UTC** —
`logsREV_20260920_210025.revlog` for a process that started at 14:00:25 PDT.
**[executed]**

### 8.5 Every `.revlog` on the device is zero bytes, because the daemon dies first

**All 55 are empty.** Not a flush artifact caught mid-write: the live robot JVM
held its `.revlog` open on fd 111 with `pos: 0` after ten minutes of running
(`/proc/<pid>/fdinfo/111`), and no `.revlog` anywhere on the device exceeds zero
bytes. **[executed]**

The reason is in the crash logs `/home/systemcore` is littered with. Every robot
process dies with a SIGSEGV on REVLib's daemon thread:

```
C  [libc.so.6+0x9c944]
C  [libREVLibWpi.so+0x3df4]   RevLibWpiDriver::sendError(int, char const*, bool)+0x54
C  [libREVLibDriver.so+0x35ca0] c_REVLib_FlushErrors+0x1dc
C  [libREVLibDriver.so+0x34b40] (anonymous namespace)::REVLibDaemon::Main()+0x274
```

**[executed]** `REVLibDaemon::Main` is the same daemon §4.3 identifies as the
logger's only starter. It opens the file, renames it, and then dies at its first
error flush — so the file is created, named correctly, and never written to.
`robot.service` carries `Restart=always` / `RestartSec=3`, so the count of
`.revlog` files is a **count of crashes**, not of sessions: 55 since 2026-09-06,
three of them during this investigation. **[executed]**

This is the `HAL_SendError` half of the ABI break ADR 0015 was written for,
alive on alpha-7 after that ADR was retired and its shim deleted. It is a robot
defect well outside this ticket; it is recorded here only because it is the
mechanism behind the empty files. A separate probe calling
`StatusLogger.start()` outside a robot program crashes the other way, at the
startup banner — `StatusLoggerDriver_start` → `HAL_SendConsoleLine` — which is
the *other* symbol ADR 0015 bridged. **[executed]**

### 8.6 REVLib's output does not reach the WPILOG either

The banner crash shows REVLib routing status text through `HAL_SendConsoleLine`,
which is the pipe `DataLogManager` drains into the WPILOG's `console` entry. So
"the `.revlog` is empty because the text went to the console instead" is the
natural reading. It is wrong.

Four robot-written WPILOGs, parsed record by record:

| log | size | `console` records | `messages` records |
| --- | --- | --- | --- |
| `WPILIB_20260907_170237.wpilog` | 158,580,070 | **0** | 0 |
| `WPILIB_20260907_171003.wpilog` | 1,410,208 | **0** | 0 |
| `WPILIB_20260907_171402.wpilog` | 3,669,328 | **0** | 0 |
| `WPILIB_TBD_e5bae797b16fe035.wpilog` (live) | 8,078,355 | **0** | 0 |

**[executed]** The `console` entry is *declared* in all four — `DataLogManager`
starts it unconditionally — and never receives a record. Two of the four are
Driver-Station-attached runs. Nothing REVLib emits lands in the WPILOG, and
ADR 0014's **"The console is in the log"**, tagged `[source]`, does not hold on
this platform.

### 8.7 What §7's open questions look like from here

Question 1 (`/u` permissions) is untouched — no stick was mounted. Question 3 is
settled: the on-disk directory names use dashes, `2026-09-20_14-07-56`, so
something does rewrite `%F_%T`'s colons. **[executed]** Questions 2, 4 and 5
are untouched.

The two `journalctl` checks §8's placeholder suggested return nothing: the unit
logs neither `Signal Logger Started at` nor `Logging REVLOG to`, consistent with
§8.3 (Phoenix never started) and §8.5 (REVLib died before flushing). **[executed]**

### 8.8 What was left on the device

The two `.hoot` directories the probes created under `/home/systemcore/logs/`
were removed afterwards, so the robot's log directory is as it was found. Probe
sources remain at `/tmp/hootprobe/` (tmpfs, cleared on reboot). `robot.service`
was not stopped, restarted or reconfigured at any point.

---

## 9. Reproducing this

Artifacts come from the Gradle cache; nothing was downloaded.

```sh
C=~/.gradle/caches/modules-2/files-2.1
find $C/com.ctre.phoenix6 $C/com.revrobotics.frc -name '*linuxsystemcore*'

# Phoenix, device build
unzip -o -d phx  $C/com.ctre.phoenix6/tools/26.70.0-alpha-2/*/tools-26.70.0-alpha-2-linuxsystemcore.zip
L=phx/linux/systemcore/shared/libCTRE_PhoenixTools.so
nm -D  "$L" | grep -i 'logger_path\|start_logger'
objdump -t "$L" | grep -E 'HootLogger|HootWriterMgr' | c++filt
objdump -d --start-address=0x229a80 --stop-address=0x22a094 "$L"   # BaseHootLogger::SetPath
objdump -d --start-address=0x229970 --stop-address=0x229a80 "$L"   # BaseHootLogger::GetPath
objdump -d --start-address=0x212f00 --stop-address=0x213074 "$L"   # GetInstance -> HootLogger vtable
strings -a "$L" | grep -E '^/u/logs$|^/home/systemcore/logs$|Signal Logger'

# REVLib, device build
unzip -o -d rev $C/com.revrobotics.frc/REVLib-driver/2027.0.0-alpha-7/*/REVLib-driver-2027.0.0-alpha-7-linuxsystemcore.zip
R=rev/linux/systemcore/shared/libREVLibDriver.so
nm -D  "$R" | grep -i statuslogger
objdump -t "$R" | grep -iE 'statuslogger|autoLogging|running'
objdump -d --start-address=0x51108 --stop-address=0x51ff0 "$R"     # StatusLoggerDriver_start
objdump -d --start-address=0x484b0 --stop-address=0x48540 "$R"     # c_Spark_Create's IsSim guard
strings -a "$R" | grep -E 'StatusLogger:|revlog|/home/lvuser'

# Java surfaces
javap -p -c -cp <phoenix jar> com.ctre.phoenix6.SignalLogger com.ctre.phoenix6.ffi.SignalLoggerNative
javap -p    -cp <revlib jar>  com.revrobotics.util.StatusLogger com.revrobotics.jni.StatusLoggerJNI
```

`objdump` here is Apple LLVM 21.0.0, which reads `elf64-littleaarch64`
**[executed]**. ELF virtual addresses were mapped to file offsets with the
program headers when reading rodata directly.

---

## Appendix: source index

**Vendor binaries** (Gradle cache, coordinates and hashes in *What was under
test*): `libCTRE_PhoenixTools.so` (linuxsystemcore),
`libCTRE_PhoenixTools_Sim.dylib` (osxuniversal), `libREVLibDriver.so`
(linuxsystemcore), `libREVLibDriver.dylib` (osxuniversal), `libREVLibWpi.so`,
`libBackendDriver.so`.

**Vendor Java**: `wpiapi-java-26.70.0-alpha-2.jar`,
`wpiapi-java-26.50.0-alpha-1-sources.jar` (`StatusCode.java`),
`REVLib-java-2027.0.0-alpha-7.jar`,
`REVLib-java-2027.0.0-alpha-6-sources.jar` (`StatusLogger.java`).

**WPILib**, `~/dev/allwpilib` at tag `v2027.0.0-alpha-7`:
`wpilibj/src/main/java/org/wpilib/system/DataLogManager.java`.

**Vendor documentation**:
<https://v6.docs.ctr-electronics.com/en/latest/docs/api-reference/api-usage/signal-logging.html>
(fetched 2026-09-20); <https://docs.revrobotics.com/llms-full.txt> and
`docs.revrobotics.com/brushless/status-logger` (fetched 2026-09-20 — 404, and
the aggregate contains nothing on the Status Logger).

**This repo**: `docs/research/vendordeps.md` (§0.1, auto-logging javadoc and
thread list at `:1230-1262`), `docs/research/systemcore-deploy.md` (§4.6 unit
file, §4.7 journal, §11 open question 1),
`docs/adr/0004-config-as-code.md` (keeping hoot logging on),
`docs/adr/0007-can-topology-and-frames.md:280-289`,
`docs/adr/0009-characterisation-and-tuning.md:499-535`,
`docs/adr/0014-ai-log-analysis-contract.md`,
`docs/adr/0015-binding-revlibs-native.md`,
`vendordeps/REVLib.json`, `vendordeps/Phoenix6-26.70.0-alpha-2.json`,
`src/test/java/first/robot/mechanisms/SwerveModuleTest.java`, and the working
copy's `logs/` tree.
