# Research: driving the FIRST Driver Station headlessly from CI

Resolves [#22](https://github.com/Drew-Robotics/2027beta/issues/22). Feeds
[#23](https://github.com/Drew-Robotics/2027beta/issues/23).

**Everything marked VERIFIED below was executed on 2026-08-24 against real hardware**, not read
from documentation:

- FIRST Driver Station `2027.0.0-alpha-6`, linux-x64, at `~/ds/FirstDriverStation`, run under
  WSLg on `tars`.
- Bench SystemCore at `192.168.1.202` (`robot.local`), image `beta14-203`, running
  `developerRobot` built from `~/dev/allwpilib` at `cafb0cc79` (alpha-7). **No CAN hardware
  attached** — every enable below was physically inert.
- `wpilibsuite/FirstDriverStation-Public` `docs/` at `main`, read in full.

Claims marked **INFERRED** are reasoning on top of that. Claims marked **UNVERIFIED** were not
executed and say what would settle them.

---

## 1. Executive summary

- **Port 6767 is not a control surface.** It is a genuine one-way mirror: an NT server that
  *accepts* writes, stores them, shows them to other clients on 6767 — and never propagates
  them into the Driver Station. Verified with the DS fully live (past its startup gate) on
  `ControlData`, `Keyboard/EnableButtonPressed`, `Control/TeamNumber` and
  `Control/RestartRobotCode`. **Answer to the ticket's single biggest unknown: no.**
- **The synthetic-input path works completely.** Enable, disable, E-stop, E-stop recovery,
  A-stop and joystick reload are reachable by keystroke; robot mode (Tele/Auto/Util) and
  op-mode selection are reachable by mouse click. A full cold-start → select auto op mode →
  enable → robot runs `DefaultAutoMode` → disable cycle was driven end to end with zero human
  input. Round-trip enable latency is ~100 ms.
- **There is an undocumented startup gate.** The DS boots to a full-window splash reading *"The
  spacebar is your Emergency Stop. Please press it to verify functionality."* It **never times
  out** and the DS ignores enable until Space is pressed. This is not in
  `docs/KeyboardShortcuts.md`. Any CI harness must send Space first.
- **"Headless" is the wrong word.** `SDL_VIDEODRIVER=dummy` does nothing for this: the UI is
  Avalonia/X11, not SDL. With no `DISPLAY` the DS aborts with `XOpenDisplay failed`. It needs a
  real X server — but tolerates a software-rendered one, verified.
- **The DS exposes no robot loop timing.** `LoopTime`/`LoopDelta` are the DS's own comm loop —
  proved by watching them tick at 50 Hz with the robot program stopped. Robot-side timing
  reaches the DS only as an alert plus a console text dump, and **not at all** for the op mode's
  own `periodic()`. The timing-regression work in #23 must read the robot's WPILOG.
- **Alerts do reach the dashboard** — the standing open question on the map is now closed.
  Verified with custom group/level/id from robot code.
- The full `mrc.proto` schema is recoverable at runtime from NT topic
  `/.schema/proto:MrcComm.proto`. It is reproduced in §11 — no guessing needed.

**Recommendation: the headless-control path is viable, and the input-injection path must be the
primary. There is no NT control path to fall back to.**

---

## 2. Corrections to what #22 recorded as established

The charting session inferred some of this from strings in a stripped binary. Five items are
wrong or incomplete:

| #22 says | Actually |
|---|---|
| `/Dscomm/*` are on a "local forwarding server on port 6767 … for tools such as AdvantageScope", implying it *might* be writable | **VERIFIED** one-way. Writes are accepted and stored but never reach the DS. See §3. |
| `SDL dummy video driver with evdev` suggests `SDL_VIDEODRIVER=dummy` is supported | **VERIFIED wrong.** That string is SDL's own built-in driver table, statically linked into the 58 MB self-contained binary. The DS window is Avalonia/X11. See §4. |
| DS writes its wpilog to `~/.firstds`, settings in `~/.firstds/DriverStationSettings.json` | **VERIFIED wrong on linux alpha-6.** Logs are `~/.local/share/FIRSTDriverStation/Logs/*.wpilog`, settings are `~/.local/share/FIRSTDriverStation/DriverStationStorage.json`. `~/.firstds` is never created. The published docs are stale. |
| `/Dscomm/Status/StatusByte` | The live topic is **`/Dscomm/Status/StatusWord`** (int). `LoggingKeys.md` is stale. |
| `hal/.../systemcore/DriverStation.cpp` is a shim into "the closed `libMrcLib.so`" | True but understated. `GetMrcLibDs()` resolves to `MrcLibDsImpl` in **open source** at `hal/src/main/native/cpp/mrclib/MrcLibDs.cpp` (704 lines), which calls the closed C API. The mrclib headers ship in the Gradle cache and document the whole DS↔robot struct set. See §10. |

Two things #22 got right that are worth restating because they were load-bearing: the
`control_word` bit layout and the `current_op_mode` packing are both exactly as documented, and
both were confirmed against live traffic (§11).

---

## 3. Q1 — Is port 6767 writable, and do writes propagate? **No.**

### What 6767 actually is

**VERIFIED.** On startup the DS logs exactly one line: `NT: Listening on port 6767`. A standard
NT4 client (ntcore from `~/dev/allwpilib`, so version-exact) connects, sees 66 topics, and can
read everything. The server is bound to `127.0.0.1` only.

### Writes are accepted

**VERIFIED.** Every write attempted was accepted by the server and became visible to a *second,
independent* NT client process connected to 6767. So there is no ACL, no read-only flag, no
publisher rejection. The mirror instance genuinely takes the value.

### Writes never reach the Driver Station

**VERIFIED**, four ways, with the DS past its startup gate and demonstrably live (a physical
enable chord in the same session enabled the robot 13 s later):

| Write | Accepted by 6767 | Effect on DS / robot |
|---|---|---|
| `/Dscomm/Control/ControlData` = enabled teleop, republished at 50 Hz for 6 s | yes | none. `StatusWord` stayed `0x2`, robot stayed disabled |
| `/Dscomm/Keyboard/EnableButtonPressed` = true (1.2 s pulse) | yes | none |
| `/Dscomm/Control/RestartRobotCode` = true | yes | none. Robot code not restarted |
| `/Dscomm/Control/TeamNumber` = `"9999"` (real value `"8852"`, `RequireTeamNumberMatch` = true) | yes | none. DS UI still showed 8852, robot connection never dropped |

The `Keyboard/*` result is the decisive one. `LoggingKeys.md` says those topics are *"published
by the native keyboard/input layer and are consumed by the Driver Station UI"* — i.e. they are
exactly the seam a control client would want. Writing them does nothing; pressing the physical
key publishes the identical value and the DS enables in ~100 ms.

### Proof it is a one-way mirror, not a shared instance

**VERIFIED.** Two independent observations:

1. **Rapidly-changing topics are overwritten within one DS loop.** Writing `999999` to
   `/Dscomm/Status/LoopTime` was replaced by the DS's own value on the next sample. So the DS
   pushes into the mirror continuously.
2. **Slow-changing topics stay poisoned indefinitely.** After writing `TeamNumber = "9999"` and
   then *unpublishing*, the mirror still read `"9999"` minutes later — the DS never re-sent its
   unchanged value. Likewise the poisoned `ControlData` (`ENABLED`) sat in the mirror for 17 s
   until a real hotkey caused the DS's own value to change.

A one-way, change-driven mirror explains both. There is no path back.

> **Hazard for #23.** Because writes stick, anything writing to 6767 corrupts what
> AdvantageScope and any CI reader see, silently, until the DS's own value next changes. A CI
> harness should connect to 6767 **read-only** and never publish. If a probe must write, write
> to a private prefix (e.g. `/ci/...`), never under `/Dscomm`.

### Method note on an earlier false start

The first round of these writes was run while the DS was sitting on its startup splash (§4),
which invalidated them. All results above are from a re-run after the splash was cleared and
with a physical enable proving the DS was responsive in the same session.

### Other candidate write surfaces, all checked

**VERIFIED.** `docs/DashboardInterface.md` describes TCP `6770`, WebSocket `ws://localhost:6768/ipws`
and HTTP `GET /ip` — all three carry the same three-field read-only payload (`robotIp`,
`fmsControlled`, `dockedHeight`). Live: `{"robotIp":"192.168.1.202","fmsControlled":false,"dockedHeight":0}`.

The HTTP server on 6768 has exactly three POST endpoints, all discovered from `/overrides.html`:
`/overrides/dashboard-overrides`, `/overrides/gamepad-mappings`, `/overrides/vid-pid-ignore-list`.
All require an `X-Api-Key` header (verified: 401 without it) and none control the robot.
`GET /settings/ds-settings` returns the live settings JSON with **no** auth — a convenient way
for CI to read the currently-selected op-mode hashes.

`/overrides/gamepad-mappings` is worth remembering for #23: it is the SDL gamepad mapping
database, uploadable over HTTP. That is how you would teach the DS about a synthetic controller
whose GUID it does not recognise.

---

## 4. Q2 — Does it run headless? **Not without an X server.**

**VERIFIED.** With `DISPLAY` and `WAYLAND_DISPLAY` unset and `SDL_VIDEODRIVER=dummy` set, the DS
aborts immediately:

```
Unhandled exception. System.Exception: XOpenDisplay failed
   at Avalonia.X11.AvaloniaX11Platform.Initialize(X11PlatformOptions)
   at Avalonia.AppBuilder.SetupUnsafe()
   at FirstDriverStation.Program.Main(String[] args)
```

`SDL_VIDEODRIVER` is irrelevant — SDL is only the joystick/audio backend. The UI is Avalonia on
X11. The DS accepts no CLI flags (`--help` just launches it normally; the only `--` strings in
the binary belong to the .NET crash-report handler).

**VERIFIED and encouraging:** the DS runs fine with software rendering. Launched without the
`MESA_LOADER_DRIVER_OVERRIDE=d3d12` that `~/ds/run-ds.sh` sets, it logs
`Renderer 'llvmpipe (LLVM 21.1.8, 256 bits)' is blacklisted by 'llvmpipe'`, falls back to CPU
Skia, and works normally. So no GPU is required.

**VERIFIED:** keyboard access is independent of X. The binary scans `/dev/input/event*` directly
(strings: `/dev/input/event`, `No keyboard found, exiting`, `No keyboard permissions, exiting`)
and links `libinput`/`libudev`/`libevdev`. A uinput virtual keyboard satisfies it — that is
exactly what `~/ds/wsl-setup/wsl-input-bridge` provides, and the DS started and held keyboard
permission against it for the entire session.

**UNVERIFIED:** running under `Xvfb`. Neither `Xvfb` nor `Xephyr` is installed on this box and
there is no passwordless sudo, so I could not install one. Everything the DS needs from X is
window creation plus software rendering, both of which Xvfb provides, so I rate this very likely
to work — but it is the one link in the chain I did not execute. **To settle it:**
`xvfb-run -s "-screen 0 1600x900x24" ./FirstDriverStation` on any box with `xvfb`, then check
that port 6767 comes up and the enable chord still works. Budget one hour.

### The startup gate (undocumented)

**VERIFIED.** On every launch the DS presents a full-window splash:

> This Operating System does not support being used for official Competition Matches. See the
> Game Manual for legal operating systems and devices.
> This application does not support the roboRIO or Control Hub.
> **The spacebar is your Emergency Stop. Please press it to verify functionality.**

Screenshots at t = 12 s, 30 s, 60 s and 90 s after launch were byte-identical: **it does not
time out.** While it is up, the DS still connects to the robot, publishes `/Dscomm/*` at full
rate and serves 6767/6768 — but it *ignores enable*. The enable chord publishes
`Keyboard/EnableButtonPressed=true` and nothing else happens. A single Space clears it (the
keypress registers on `/Dscomm/Keyboard/EStopButtonPressed`, so the splash is consuming the
E-stop key as its verification), after which everything works.

This is a hard prerequisite for CI and is in none of the published docs.

---

## 5. Q3 — Op-mode selection end to end. **Works, but only by mouse.**

**VERIFIED.** There is no keyboard shortcut for robot mode or op-mode selection —
`docs/KeyboardShortcuts.md` is complete, and the live `/Dscomm/Keyboard/*` topic set contains
only enable / disable / E-stop / A-stop / reset-E-stop / reload-joysticks. Selection is a UI
gesture.

`XTestFakeMotionEvent` + `XTestFakeButtonEvent` against the DS window drives it correctly. Full
sequence, executed from a cold DS start with no human input:

```
1. Space                                    -> clears the startup gate
2. click (origin + 811,138)  "Auto"         -> ControlWord 0x24 -> 0x22 (mode=auto)
3. click (origin + 141,118)  Autonomous ▾   -> dropdown opens
4. click (origin +  85,152)  DefaultAutoMode-> CurrentOpMode = 0x1FFFFFF91693D33
5. [ + ] + \                                -> ControlWord 0x23, StatusWord 0x5
                                               robot: "Starting OpMode DefaultAutoMode"
6. Enter                                    -> StatusWord 0x2, "Ending/Closing OpMode"
```

Confirmation channels all behaved as documented:

- `/Dscomm/Status/CurrentOpModeTrace` = `0x1FFFFFF91693D33` (robot echoes the selection while
  disabled), then `0x5FFFFFF91693D33` once enabled — bit 58 set by the robot.
- `/Dscomm/Control/IsCurrentOpModeTraceCorrect` flicks `false` for ~1 ms on each transition and
  settles `true`. **This is the assertion to use**: it is the DS's own statement that the robot
  is in the mode the DS asked for.
- `/Dscomm/Console/ConsoleLine` carries `********** Starting OpMode DefaultAutoMode **********`.

**VERIFIED negative:** preseeding `AutoOpModeHash` in `DriverStationStorage.json` before launch
does **not** work. The DS overwrites the file from its own UI state at startup; after a launch
with a preseeded hash the Autonomous dropdown was still empty and the file was back to `0`. So
there is no file-based shortcut around the clicks.

**INFERRED risk for #23.** Fixed pixel coordinates are the fragile part of this whole design.
The window is 1280×200 and its root-relative origin moved between launches (`+241+350` then
`+16+37`), so coordinates must be computed from `xwininfo` at runtime, not hard-coded — that is
what the sequence above does and it survived a restart. Widget positions within the window are
still a hard dependency on the alpha-6 layout and *will* break on a DS update. Mitigation:
screenshot-assert the post-click state (`/Dscomm/Control/ControlData` already tells you the
mode and hash, so the assertion is cheap and does not need image comparison).

---

## 6. Q4 — Joystick injection. **Only through a virtual input device.**

**VERIFIED negative on the NT path.** `joysticks[]` lives inside `ControlData`, which is not
writable (§3). There is no separate joystick topic in the DS→robot direction —
`/Dscomm/Control/JoystickDescriptors` is metadata only, and `/Dscomm/Status/JoystickOutputs` is
the robot→DS rumble/LED return path.

**VERIFIED positive on the uinput path**, from this machine's own DS wpilog. During an earlier
session on 2026-08-24 the `wsl-input-bridge` had a uinput gamepad up. The DS enumerated it and
its axes flowed into the DS→robot packet at ~50 Hz:

```
DS:/Dscomm/Control/JoystickDescriptors
    name=Xbox 360 Controller isGamepad=1 type=2

DS:/Dscomm/Control/ControlData
    ControlWord=0x24{mode=teleop,DSCONN,station=0} Joysticks=1
    [js availBtn=0x7FFF availAxes=63 axes=2256,2441,-1001,655,0,0 ]
```

Axes are `sint32`, packed, on the ±32767 scale. `availableButtons` is a `uint64` bitmask;
`availableAxes` is a bitmask (`63` = six axes present).

So joystick injection is reachable, and the mechanism is a synthetic evdev device the DS
enumerates through libinput/SDL — the same mechanism that already supplies its keyboard. It
needs `/dev/uinput`, which is `root:root 0600`, so the CI harness must run as root or the
runner must relax that node. **UNVERIFIED:** creating a fresh uinput gamepad *in this session* —
no passwordless sudo. `~/ds/wsl-setup/wsl-input-bridge.c` is a complete worked example of doing
it (uinput xpad emulation with force-feedback), and is the thing to lift for #23.

**VERIFIED oddity worth knowing.** When enabled in autonomous with `AllowJoysticksInAuto=false`,
the DS sends `Joysticks=6` — six joystick structs — where it sends `Joysticks=0` when disabled
and when enabled in teleop with no pads attached. Do not read joystick count as "pads
connected".

---

## 7. Q5 — Is robot-side loop timing exposed? **No. This is the important one.**

### `LoopTime`/`LoopDelta` are the DS, not the robot

**VERIFIED by execution**, not by reading the doc note. I stopped `robot.service` on the bench
Pi for ~12 s while subscribed to `/Dscomm/Status`:

| window | robot code | `LoopDelta` updates observed |
|---|---|---|
| t = 0–5 s | running | 243 |
| t = 6–14 s | **stopped** (`HasUserCode=false`) | 379 |
| t = 20–30 s | running again | 465 |

They never paused. Steady-state values are `LoopDelta ≈ 20 085 µs` (the DS's own 50 Hz comm
loop) and `LoopTime ≈ 80–110 µs` (how long that loop takes) — far too fast to be a robot
periodic. `UiLoopDelta ≈ 10 080 µs` is the 100 Hz Avalonia loop. **None of the four is the
robot's 20 ms periodic.**

### What robot timing *does* reach the DS

**VERIFIED** by deploying a probe that sleeps 35 ms inside `robotPeriodic()`:

1. **`/Dscomm/Alerts/Alerts/1/opmode-loop-overrun/active`** flips from `0` to an activation
   timestamp. Text: `Loop time of "0.02"s overrun`. It is a latch, not a count — it tells you
   *that* an overrun happened, never how many or how bad.
2. **`/Dscomm/Console/ConsoleLine`** carries `Watchdog.printEpochs()`, and this *is* numeric:
   ```
   Warning at ...reportWarning(DriverStationErrors.java:40): 	robotPeriodic(): 0.035093s
   	disabledPeriodic(): 0.000012s
   	opMode.disabledPeriodic(): 0.000001s
   	TunableRegistry.update(): 0.000017s
   ```
   Per-epoch seconds to 6 dp — but emitted *only on an overrun*, as free text spread over
   several `ConsoleLine` records that must be reassembled by `sequence_number`.
3. **`/Dscomm/Status/WatchdogNotFed`** stayed `false` through every overrun I induced,
   including a robot-code crash. It is not the loop watchdog.

### The trap: op-mode `periodic()` is not watched at all

**VERIFIED, and this is a genuine WPILib 2027 alpha finding.** My first probe put the 35 ms
sleep in `DefaultTeleMode.periodic()` and ran it enabled for 14 s, overrunning 14 times.
**Zero DS-visible evidence**: no alert, no console warning, no `WatchdogNotFed`.

The cause is in `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java`.
The watchdog wraps `loopFunc`, which is registered as one periodic callback:

```java
m_watchdog = new Watchdog(Seconds.of(m_period), () -> m_loopOverrunAlert.set(true));
addPeriodic(this::loopFunc, period);
```

but the current op mode's `periodic()` is registered as a **separate, sibling** callback in the
same queue:

```java
m_currentOpModePeriodic = m_callbacks.add(m_currentOpMode::periodic, m_startTimeUs, m_period);
```

So the watchdog covers `robotPeriodic()`, `disabledPeriodic()`, `TunableRegistry.update()` and
op-mode `start()`/`end()` — i.e. framework and robot-level code — but **not the op mode body,
which is where all of our subsystem work will live.**

### Consequence for #23

The DS cannot be the timing-regression surface. It offers a latched boolean for the wrong half
of the loop and a text dump on overrun only. **#23 must source timing from the robot's own
WPILOG** (`DataLogManager.start()`, per `docs/research/telemetry-api.md` §8) or from explicit
`Telemetry.log(...)` of a measured loop duration. The DS's role in #23 shrinks to *control* and
*coarse pass/fail* (crashes, alerts, op-mode transitions), not measurement.

---

## 8. Q6 — Do `/Dscomm/Alerts/...` populate? **Yes. Fully.**

**VERIFIED.** This closes the standing "whether alerts reach a dashboard at all" question on the
map. I deployed a probe raising three `org.wpilib.util.Alert`s — two in a custom group, one in
the default group, one at each level — and all four topics per alert appeared:

```
/Dscomm/Alerts/CiProbe/0/ci-high/text                  = "CI probe HIGH alert"
/Dscomm/Alerts/CiProbe/0/ci-high/active                = 4220036990
/Dscomm/Alerts/Alerts/1/ci-medium-default-group/text   = "CI probe MEDIUM alert"
/Dscomm/Alerts/Alerts/1/ci-medium-default-group/active = 4220036994
/Dscomm/Alerts/CiProbe/2/ci-low/text                   = "CI probe LOW alert"
/Dscomm/Alerts/CiProbe/2/ci-low/active                 = 4220050654
/Dscomm/Alerts/Alerts/1/opmode-loop-overrun/text       = "Loop time of \"0.02\"s overrun"
```

Confirmed properties:

- **Path is `/Dscomm/Alerts/{group}/{level}/{id}/{text,active}` exactly as documented.** `group`
  is the `Alert` group string (default `"Alerts"`), `id` is the new mandatory id parameter.
- **`level` is the numeric enum from `AlertDataJNI`**: `0` HIGH, `1` MEDIUM, `2` LOW. This
  matches `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/AlertDataJNI.java`.
- **Topics are created at `Alert` construction**, before the alert is ever set, with
  `active = 0`. So CI can assert on the *absence* of activation, not just its presence.
- **`active` is live and bidirectional.** A LOW alert toggled every 2 s produced a clean
  alternation between `0` and a fresh microsecond timestamp, tracked in real time.
- Transport is `WPI_AlertBackend` → `hal/src/main/native/cpp/mrclib/MrcLibAlert.cpp`
  (`SetMrcLibAlertBackend()`, installed from `MrcLibDs.cpp:346`) → closed mrclib → DS. Nothing
  in the robot program needs to opt in.
- **The DS's alert topics also land in its wpilog** with the `DS:` prefix (§9).

**VERIFIED gotcha, not in scope but expensive if missed.** `Alert` allocations are registered by
group+id and are not freed unless `close()`d. Op modes are **re-constructed on every
disable→enable cycle**, so an `Alert` field on a `PeriodicOpMode` throws on the second cycle:

```
Unhandled exception: org.wpilib.util.AlertException: Alert already allocated
  at org.wpilib.util.Alert.<init>(Alert.java:97)
  at wpilib.robot.DefaultTeleMode.<init>(DefaultTeleMode.java:17)
```

and takes the robot program down with it (`robot.service` exited 137 and restarted). Alerts must
live on the robot object or be `static`, never on an op mode. Worth a line in whatever coding
standard #23 lands next to.

---

## 9. Q7 — The DS's own wpilog as an assertion surface. **Good, with two caveats.**

**VERIFIED.** Location is `~/.local/share/FIRSTDriverStation/Logs/FIRST_DS_<UTC>.<ns>.wpilog`
(**not** `~/.firstds`). Header `extraHeader = "DS Log File"`. Readable directly with
`org.wpilib.datalog.DataLogReader` from `~/dev/allwpilib/datalog/build/libs/datalog.jar`.
Downloadable over HTTP from `/logfiles/` (a plain directory index), no auth.

Every `/Dscomm/*` topic is present with the `DS:` prefix and its NT type preserved, protobuf
included — 64 to 72 entries per file. Record counts from one 58 s session give a feel for the
sampling:

| entry | records | note |
|---|---|---|
| `DS:/Dscomm/Control/ControlData` | 5474 | full-rate, duplicates kept |
| `DS:/Dscomm/Status/CurrentOpModeTrace` | 2235 | every loop |
| `DS:/Dscomm/Status/LoopTime` | 2699 | DS loop, not robot |
| `DS:/Dscomm/Console/ConsoleLine` | 0–35 | everything the robot printed |
| `DS:/Dscomm/Alerts/**` | 1 per topic | on change |
| `DS:/Dscomm/Control/JoystickDescriptors` | 1 | on change |

**Is it enough on its own?** For control-plane and crash assertions, yes — and that is a real
result, because it means CI needs no robot-side instrumentation to answer *"did it enable, did
it enter the right op mode, did it raise an alert, did it throw, did it stay connected"*. The
full Java stack trace of the crash in §8 was reconstructed from `ConsoleLine` records alone.

Two caveats:

1. **It cannot answer timing questions** (§7). Anything about the 20 ms loop has to come from
   the robot's WPILOG.
2. **VERIFIED: the DS rotates its log file mid-session.** One ~22-minute DS run produced nine
   files. Timestamps inside each file are relative to *DS start*, not file creation, so files
   overlap and the filename timestamp is not the content start time. Rotation appears to track
   robot-code restarts. A CI harness must collect the whole `Logs/` directory (or the whole
   `/logfiles/` index) for the run window, not "the newest file".

`ConsoleLine` reassembly note: each record is one line with a `sequence_number`; multi-line
output (stack traces, watchdog epoch dumps) arrives as a run of consecutive records and must be
stitched by sequence.

---

## 10. Q8 — Version pairing. **The DS is independent. It is a fourth thing, but not ABI-pinned.**

**VERIFIED.**

- **The DS binary is fully self-contained.** `ldd` on `FirstDriverStation` shows only system
  libraries — `libinput`, `libudev`, `libevdev`, `libusb`, `libavahi`, glibc/libstdc++. It is a
  58 MB .NET AOT single file with SDL, Skia, ntcore's counterpart, and an embedded copy of
  AdvantageScope statically linked in. **It does not load `libMrcLib.so` and has no build-time
  dependency on allwpilib or the SystemCore image.**
- **The MRC ABI pairing that does exist is allwpilib ↔ on-device `libMrcLib.so`**, via
  `MRC_API_VERSION 11` in `mrclib/ApiVersion.h`, checked by `MRC_CheckApiVersion()`. The headers
  ship as a Gradle artifact — locally
  `~/.gradle/caches/.../mrclib-cpp-2027.1.0-alpha-1-112-g3f8f56e-headers/mrclib/`. The DS is not
  part of that handshake.
- **No wire-protocol version field exists** anywhere in `mrclib/DsComms.h`,
  `mrclib/DsCommsControl.h` or `mrclib/MrcLib.h`. The DS↔robot pairing is by struct layout
  convention, unnegotiated.
- **Empirically, skew works.** DS `alpha-6` (released 2026-07-02) drove allwpilib `alpha-7`
  (mrclib `alpha-1-112`) on image `beta14-203` through every operation in this document with no
  degradation.
- DS releases are tagged `v2027.0.0-alpha-N` in lockstep with WPILib alpha numbering, and an
  **arm64 linux tarball ships in every release** — so a Pi-class CI runner can host the DS.

**INFERRED:** pin the DS version in CI anyway, but pin it for *UI-layout stability* (§5 depends
on pixel coordinates) rather than for ABI. A DS bump is a "re-measure the click targets" event,
not a "rebuild everything" event.

---

## 11. Reference: the real `mrc.proto` schema

**VERIFIED.** No guessing was needed and none should be needed in #23. The DS publishes its own
protobuf descriptor as an NT topic:

```
/.schema/proto:MrcComm.proto     type = proto:FileDescriptorProto     1923 bytes
```

Decoded (field numbers and wire types are exact; note `ProtobufControlData` starts at field 5,
and that the doc's snake_case names are actually PascalCase):

```protobuf
package mrc.proto;

message ProtobufControlData {
  uint32   ControlWord   = 5;    // bit layout below
  int32    MatchTime     = 2;
  repeated ProtobufJoystickData Joysticks = 3;
  fixed64  CurrentOpMode = 4;    // packing below
  string   GameData      = 6;
}

message ProtobufJoystickData {
  uint64   AvailableButtons = 1;
  uint64   Buttons          = 2;
  uint32   AvailableAxes    = 3;   // bitmask
  repeated sint32 Axes      = 4;   // PACKED. ±32767 scale
  uint32   POVCount         = 5;
  uint32   POVs             = 6;
  repeated ProtobufTouchpadData Touchpads = 7;
}

message ProtobufOpMode {
  fixed64 Hash = 1; string Name = 2; string Group = 3; string Description = 4;
  int32 TextColor = 5; int32 BackgroundColor = 6;
}
message ProtobufAvailableOpModes { repeated ProtobufOpMode Modes = 1; }

message ProtobufJoystickDescriptor {
  string JoystickName = 1; bool IsGamepad = 2; uint32 GamepadType = 3; uint32 SupportedOutputs = 4;
}
message ProtobufJoystickDescriptors { repeated ProtobufJoystickDescriptor Descriptors = 1; }

message ProtobufJoystickOutput  { uint32 LEDs = 1; uint32 Rumble = 2; uint32 TriggerRumble = 3; }
message ProtobufJoystickOutputs { repeated ProtobufJoystickOutput Outputs = 1; }

message ProtobufMatchInfo { string EventName = 1; int32 MatchNumber = 2; int32 ReplayNumber = 3; int32 MatchType = 4; }

message ProtobufErrorInfo { bool IsError = 1; sint32 ErrorCode = 2; string Details = 3; string Location = 4; string CallStack = 5; }
message ProtobufErrorInfoTimestamp { ProtobufErrorInfo ErrorInfo = 1; uint64 Timestamp = 2; int32 SequenceNumber = 3; int32 NumOccurrences = 4; }
message ProtobufConsoleLineTimestamp { string ConsoleLine = 1; uint64 Timestamp = 2; int32 SequenceNumber = 3; }
message ProtobufProgramCrashInfo { string Details = 3; string Location = 4; string CallStack = 5; }
message ProtobufProgramCrashInfoTimestamp { ProtobufProgramCrashInfo ProgramCrashInfo = 1; uint64 Timestamp = 2; }

message ProtobufTouchpadData { repeated ProtobufFingerData Fingers = 1; }
message ProtobufFingerData   { uint32 X = 1; uint32 Y = 2; bool Down = 3; }

message ProtobufResolvedTeamNumber    { string TeamNumber = 1; string HostName = 2; string IpAddress = 3; }
message ProtobufResolvedTeamNumberSet { repeated ProtobufResolvedTeamNumber Entries = 1; }
```

The equivalent C structs are in `mrclib/DsComms.h` and `mrclib/DsCommsControl.h` in the Gradle
cache, and are what `hal/src/main/native/cpp/mrclib/MrcLibDs.cpp` marshals to/from the HAL.

### Bit layouts, confirmed against live traffic

`ControlWord` — every value below was observed:

| value | meaning |
|---|---|
| `0x24` | disabled, teleop, DS connected, station Red 1 |
| `0x25` | **enabled**, teleop |
| `0x22` | disabled, autonomous |
| `0x23` | **enabled**, autonomous |
| `0x2C` | **E-stopped** (bit 3), teleop, enable bit cleared |

Bits: `0` enabled, `1-2` mode (0 unknown / 1 auto / 2 teleop / 3 utility), `3` E-stop, `4` FMS,
`5` DS connected, `8-11` alliance station, `13` timed match. As documented.

`CurrentOpMode` / `CurrentOpModeTrace` — bits `0-55` hash, `56-57` mode, `58` enabled. As
documented. Observed: `DefaultTeleMode` hash `0xFFFFFFEA882DAE`, `DefaultAutoMode` hash
`0xFFFFFF91693D33`; DS publishes `0x2FFFFFFEA882DAE` (mode 2, not enabled), robot echoes
`0x6FFFFFFEA882DAE` (bit 58 set) once running.

`/Dscomm/Status/StatusWord` — observed `0x2` disabled-teleop, `0x6` enabled-teleop, `0x1`
disabled-auto, `0x5` enabled-auto, `0x82` E-stopped. So `0x40` enabled, `0x80` E-stop, low bits
mode.

### Topics present live but absent from `LoggingKeys.md`

`/Dscomm/Display/AnsiText` (string; the DS display panel, written by
`HAL_WriteDisplayAnsi` → `MRC_DsCommsControl_WriteAnsi`), `/Dscomm/Status/GcCount`,
`GcDeltaTimeUs`, `GcReason`, `GcType` (robot JVM GC telemetry — potentially interesting for
#23), and `/Dscomm/Status/OptimizedWlanError`. And `StatusWord` where the doc says `StatusByte`.

---

## 12. The third path: `ForceDsInstance`

**VERIFIED reachable on real hardware.** `wpi::hal::ForceDsInstance(MrcLibDs*)` is declared in
the public header `hal/src/main/native/include/wpi/hal/cpp/MrcLibDs.hpp`, defined at
`hal/src/main/native/cpp/FIRSTDriverStation.cpp:296`, and **exported as a global text symbol
from the SystemCore build**:

```
$ nm -DC --defined-only hal/build/libs/hal/shared/linuxsystemcore/release/libwpiHal.so
0000000000003d980 T wpi::hal::ForceDsInstance(wpi::hal::MrcLibDs*)
```

`MrcLibDs` is a 26-method pure-virtual interface (control word, op-mode options, all joystick
accessors, match info/time, alliance station, game data, console/error/crash reporting, the
new-data event handles, `observeUserProgram*`, `writeDisplayAnsi`). Substituting an
implementation replaces the Driver Station **entirely, inside the robot process**.

**Assessment: do not use it for #23.** Three reasons:

1. It requires modifying or preloading into the robot program, so the artifact CI exercises is
   no longer the artifact that ships.
2. It bypasses the DS and `MrcCommDaemon` completely, so it tests strictly less of the stack
   than the input-injection path — which is exactly the coverage hardware CI exists to buy.
3. It is unnecessary. The input-injection path works today (§5).

It is worth keeping in the back pocket for a *different* job: deterministic, fast, no-DS
integration tests that need to sweep control-word states at machine speed on real hardware.
That is a distinct capability from what #22/#23 are chasing.

---

## 13. Recommendation for #23

**The headless-control path is viable. Make input injection the primary and only control path.**

There is no NT fallback — §3 removes the option the ticket was hoping for, in both directions
(neither `ControlData` nor the `Keyboard/*` topics work). So the design is not "NT writes with
uinput hotkeys as fallback"; it is "synthetic input for control, NT read-only for observation."

### The shape of the harness

**Control — synthetic input, owned by the harness:**

- A uinput virtual keyboard created by the harness itself (root or a relaxed `/dev/uinput`),
  modelled on `~/ds/wsl-setup/wsl-input-bridge.c`. **Do not** depend on the XTEST→bridge relay I
  used here — that is a WSL convenience and it means any human typing at the machine is injected
  into the DS. Writing evdev events directly is simpler, has no X dependency, and is isolated.
- Uinput gamepads by the same mechanism when input-driven tests arrive (§6).
- X mouse events for mode and op-mode selection (§5), with click targets computed from
  `xwininfo` at runtime.
- Sequence: **Space first** (§4), then select, then `[ ] \`, then verify.

**Observation — NT on 6767, strictly read-only, plus the DS wpilog:**

- Assert on `StatusWord`, `IsCurrentOpModeTraceCorrect`, `CurrentOpModeTrace`,
  `HasUserCode`/`HasUserCodeReady`, `UdpConnAddr`, `/Dscomm/Alerts/**`, and `ConsoleLine`.
- Never publish under `/Dscomm` (the poisoning hazard in §3).
- Collect the whole `Logs/` directory at the end of the run, not the newest file (§9).

**Timing — from the robot, not the DS.** §7 is the change to #23's plan: the DS cannot measure
the 20 ms loop, and worse, the framework does not watch the op-mode body at all. Source loop
timing from the robot's own WPILOG or from explicit telemetry.

### Every action verified end to end

| action | mechanism | verified |
|---|---|---|
| clear startup gate | `Space` | yes |
| enable | `[` + `]` + `\` | yes, ~100 ms |
| disable | `Enter` | yes |
| E-stop | `Space` | yes, `StatusWord 0x82` |
| enable refused while E-stopped | — | yes |
| reset E-stop | `Esc` + `i` held ≥1 s | yes, recovers to `0x2` |
| A-stop | `Backspace` | fires, auto/match only |
| reload joysticks | `Left Control` | not exercised |
| select robot mode | mouse click | yes |
| select op mode | mouse click ×2 | yes |
| inject joystick axes | uinput gamepad | yes (from prior-session log) |
| restart robot code / reboot robot | — | **no path.** UI-only, and NT writes are inert |

### Open items for #23 to close, in priority order

1. **Run the DS under `Xvfb` once** (§4). One hour. It is the only unexecuted link.
2. **Decide the click-target strategy** (§5). Fixed offsets from `xwininfo`, re-measured on each
   DS bump, with a post-click assertion on `ControlData`.
3. **Build the uinput harness** from `wsl-input-bridge.c` (§6), keyboard first.
4. **Pick the robot-side timing source** (§7) — this changes #23's scope, not just its plumbing.
5. Consider the arm64 DS build (§10) so the CI runner can be a Pi rather than a PC.

---

## 14. Where things live

| what | path |
|---|---|
| DS binary and launcher | `~/ds/FirstDriverStation`, `~/ds/run-ds.sh` |
| uinput bridge (reference implementation) | `~/ds/wsl-setup/wsl-input-bridge.c` |
| DS logs (wpilog) | `~/.local/share/FIRSTDriverStation/Logs/` |
| DS settings | `~/.local/share/FIRSTDriverStation/DriverStationStorage.json` |
| DS HTTP (localhost only) | `:6768` — `/`, `/logfiles/`, `/ascope`, `/ip`, `/ipws`, `/overrides.html`, `/settings/ds-settings` |
| DS NT mirror (localhost only, read-only in practice) | `:6767` |
| DS dashboard TCP feed | `:6770` |
| MRC glue, open source | `~/dev/allwpilib/hal/src/main/native/cpp/mrclib/MrcLibDs.cpp`, `MrcLibAlert.cpp` |
| `MrcLibDs` interface + `ForceDsInstance` | `~/dev/allwpilib/hal/src/main/native/include/wpi/hal/cpp/MrcLibDs.hpp` |
| `ForceDsInstance` definition | `~/dev/allwpilib/hal/src/main/native/cpp/FIRSTDriverStation.cpp:296` |
| SystemCore DS shim | `~/dev/allwpilib/hal/src/main/native/systemcore/DriverStation.cpp` |
| mrclib C headers (incl. `MRC_API_VERSION`) | `~/.gradle/caches/9.4.1/transforms/*/transformed/mrclib-cpp-*-headers/mrclib/` |
| loop watchdog + overrun alert | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java:524` |
| the un-watched op-mode callback | `~/dev/allwpilib/wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java` (`m_currentOpModePeriodic`) |
| `Alert` levels (0/1/2) | `~/dev/allwpilib/wpiutil/src/main/java/org/wpilib/util/AlertDataJNI.java` |
| ntcore jar / native for an NT client | `~/dev/allwpilib/ntcore/build/libs/ntcore.jar`, `~/dev/allwpilib/ntcore/build/install/ntcoreDev/linuxx86-64/lib/` |
| wpilog reader | `~/dev/allwpilib/datalog/build/libs/datalog.jar` (`org.wpilib.datalog.DataLogReader`) |
| DS public docs | `wpilibsuite/FirstDriverStation-Public` `docs/` — `LoggingKeys.md`, `Webpage.md`, `DashboardInterface.md`, `KeyboardShortcuts.md`, `Settings.md` |

### State left behind

- Bench Pi: `robot.service` running the stock `developerRobot` `OpRobot` build, as found. All
  probe code removed and redeployed.
- `~/dev/allwpilib`: `git status` clean.
- `~/ds`: untouched.
- The DS is **not** running; it was not running at the start of the session either.
- `~/.local/share/FIRSTDriverStation/DriverStationStorage.json` now has `AutoOpModeHash` set to
  `DefaultAutoMode` — a normal UI selection, harmless, and the DS rewrites it anyway.
