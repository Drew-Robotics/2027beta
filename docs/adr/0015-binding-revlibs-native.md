# ADR 0015 — Binding REVLib's native

## Status

Accepted — 2026-08-30. Resolves the blocker ADR 0013 records against
Tier 2 and ADR 0010 records against running the simulation at all: both
were written while constructing a SPARK terminated the JVM, and on the
desktop it no longer does.

**Amended — 2026-08-30**, after the deploy failure this ADR first filed
under *Open* was diagnosed. That section concluded that "no shim fixes
it and only a REVLib rebuild will". It was wrong, and *Context*,
*Decision*, *Consequences*, *Traps*, *Open* and *Rejected* all move with
it: the shim stops being twenty lines that alias two link-time gaps and
becomes a live interposer on a console path WPILib itself uses. The
argument is on
[#87](https://github.com/Drew-Robotics/2027beta/issues/87) and
[#92](https://github.com/Drew-Robotics/2027beta/issues/92).

Claim tags are defined in the index. WPILib `[source]` claims here were
read at `~/dev/allwpilib` commit `cafb0cc79` — main, 366 commits past
`v2027.0.0-alpha-6`, the checkout ADR 0003 calls alpha-7. Native
`[executed]` claims were run against the artifacts GradleRIO resolves
today: WPILib `2027.0.0-alpha-6-358-gf90dfe8f4` and REVLib
`2027.0.0-alpha-6`, the version `vendordeps/REVLib.json` pins. An
unqualified path is a file in this repo.

## Context

**REVLib has exactly one published 2027 version.** `maven-metadata.xml`
at `maven.revrobotics.com` lists `2027.0.0-alpha-6` and nothing else.
**[executed]** It was built against a WPILib old enough that two of the
symbols it imports have since changed or gone.

`libREVLibWpi.so` has 44 undefined symbols. Forty-two resolve against
the natives GradleRIO puts on the library path. Two do not:

```
_ZN3wpi4util13WaitForObjectEj    wpi::util::WaitForObject(unsigned int)
_ZN3fmt3v127vformatB5cxx11...    fmt::v12::vformat(basic_string_view<char>,
                                                   basic_format_args<context>)
```

**[executed — `ldd -r`, both `linuxx86-64` and `linuxsystemcore`]** The
two platforms are identical: same names, same count.

This is a dynamic-linker abort, not an exception, so nothing catches or
contains it. `Robot`'s constructor builds eight SPARKs, so **nothing
that stands up `Robot` ran anywhere** — not `simulateJava`, not ADR
0013's Tier 2, not a deploy, and not a student selecting an opmode,
since the framework only constructs opmodes after that constructor
returns. Tier 1 was unaffected, because ADR 0010 keeps vendor types out
of `first.robot.sim` by construction; that rule was insurance, and this
is the second thing it paid for.

There is no version pair that resolves this by pinning. The WPILib
release `2027.0.0-alpha-6` exports the fmt symbol but ships no
`telemetry-java` or `tunables-java`, so ADR 0005's backend will not
compile against it, and no development build past it exports the symbol
at all. That is the whole reason this ADR exists: the fix cannot be a
version number.

### The second gap: two symbols that resolve and still misfire

Everything above is a *linker* problem — symbols with no definition
anywhere. There is a second gap that is not, and it is why the deploy
stayed down after the shim landed.

allwpilib `6e5171cd8` — "[hal] Use MrcLib to talk to DS" (#8858),
2026-06-06 — rewrote both Driver Station console entry points
(`hal/src/main/native/include/wpi/hal/DriverStation.h:37,55`).
**[source]** A parameter was dropped and three `const char*` became
`const WPI_String*`, where `WPI_String` is `{ const char* str; size_t
len; }` (`wpiutil/src/main/native/include/wpi/util/string.h:40`):
**[source]**

```c
-int32_t HAL_SendError(HAL_Bool isError, int32_t errorCode, HAL_Bool isLVCode,
-                      const char* details, const char* location,
-                      const char* callStack, HAL_Bool printMsg);
+int32_t HAL_SendError(HAL_Bool isError, int32_t errorCode,
+                      const struct WPI_String* details,
+                      const struct WPI_String* location,
+                      const struct WPI_String* callStack, HAL_Bool printMsg);

-int32_t HAL_SendConsoleLine(const char* line);
+int32_t HAL_SendConsoleLine(const struct WPI_String* line);
```

These are `extern "C"`, so the names never changed. REVLib
`2027.0.0-alpha-6`, compiled against a header older than that commit,
resolves them, calls them with the old ABI, and nothing anywhere reports
a mismatch. The HAL then reads a *length* out of the message text: for
REVLib's status-logger banner, bytes 0–7 become `str` and bytes 8–15
become `len`, and MrcLib asks for `len + 1` bytes to NUL-terminate a
string whose length is the ASCII of `"gger: Lo"`. That allocation is the
`std::bad_alloc` this ADR originally recorded under *Open*, and every
digit of it is accounted for. **[executed]**
[#87](https://github.com/Drew-Robotics/2027beta/issues/87) has the
interposed backtrace and the arithmetic.

It is one path with three failures on it, not one. Removing each exposes
the next: the banner through `HAL_SendConsoleLine`; then REVLib's CAN
error reporting through `HAL_SendError`, where the dropped `isLVCode`
shifts the whole argument list and a `bool` is dereferenced as a
`callStack` pointer, giving `SIGSEGV`; then the shim's own unresolved
forward target. **[executed]**

## Decision

### A shim supplies the two missing symbols, and nothing in Java changes

`src/main/native/revshim/revshim.cpp` — about twenty lines — defines
exactly the two symbols above and is preloaded into every JVM that
touches REVLib. **[decided]** No Java changes, no seam, no vendor type
moves. `SwerveModule` keeps its fourteen REV imports and `Drive`
keeps its sim plumbing, exactly as ADR 0003 and ADR 0010 have them.

**`WaitForObject` is an exact alias, not an approximation.** wpiutil
still exports the function; only its parameter type changed.
`WPI_Handle` is `typedef int32_t`
(`wpiutil/src/main/native/include/wpi/util/Handle.h:19`) **[source]**
where REVLib was compiled against a `uint32_t`. Same width, same
register, same call — the difference is entirely in the mangled name,
and forwarding one to the other loses nothing. **[executed]**

**`fmt::vformat` is a stub, and it is live code.** wpiutil moved from
fmtlib to `std::format`, so there is nothing to forward to. The stub
returns its format string unexpanded. REVLib reaches it **once per
SPARK**, with the format string `"REV_SPARK_Flex[{},{}]"` — eight calls
in a four-module robot, all returning the same value. **[executed]**
The first call prints one line to stderr, so the shim announces itself
rather than being an invisible lie. **[decided]**

Nothing this project reads is downstream of that string: the SPARK
`SimDevice` names, which ADR 0010's sensor sims and `SparkOutputSim`
resolve by name, are built on a different path and come back correct
and distinct as `SPARK Flex [0,1]` through `[0,4]`. **[executed]**

### The console pair is translated, and the caller says which ABI

The same shim defines `HAL_SendError` and `HAL_SendConsoleLine` with the
**current** signatures and routes each call by where it came from.
`dladdr(__builtin_return_address(0))` against the four native libraries
`vendordeps/REVLib.json` pins — `libREVLib.so`, `libREVLibDriver.so`,
`libBackendDriver.so`, `libREVLibWpi.so` — decides it. A hit is REVLib,
so the arguments are read as the old ABI, wrapped in `WPI_String`s, and
forwarded to the real HAL. Anything else — including a `dladdr` that
fails — is passed straight through. **[decided]**

**The partition is closed, not a sniff test.** Across every
`linuxsystemcore` native GradleRIO resolves, exactly two libraries import
the pair, one per ABI, and the library that defines them imports
neither, so there is no intra-library traffic to interpose:
**[executed]**

| Library | Imports | Defines |
|---|---|---|
| `libwpiHal.so` | — | both |
| `libwpiHaljni.so` | both, new ABI | — |
| `libREVLibWpi.so` | both, old ABI | — |

Nothing else in the tree imports either symbol — not Phoenix, not
PhotonVision, not ntcore, datalog or telemetry. **[executed]**

**Match the set, not the importer.**
[#87](https://github.com/Drew-Robotics/2027beta/issues/87) measured
REVLib's calls arriving from `libREVLibDriver.so`, which imports
neither symbol; the call tail-jumps through it out of
`libREVLibWpi.so`, which imports both. Both land on the same side of a
test that asks *is this any REVLib native*, so the discriminator
survives either codegen where a test
naming the importer would not. `libBackendDriver.so` is why the four
names are written out rather than matched as a `libREVLib*` prefix.
**[decided]**

**An unknown caller gets the modern ABI.** The fallback direction is the
whole safety argument: a WPILib native that starts calling the pair is
handled correctly by default, and the only thing a misclassification can
damage belongs to a vendor whose native set is pinned by a file in this
repository. **[decided]**

**Forwarded, not swallowed.** *Swallowing* both is already known to be
enough — it gets the real jar to *"Robot program startup complete"* with
eight SPARKs constructed, Phoenix up and 25 s clean, swallowing 46 calls
on the way. **[executed]** That is an instrument, not a design. Those 46
are the status-logger banner, but the same path carries CAN timeouts,
brownouts and firmware mismatches, and a shim that drops them trades a
crash for a robot that fails quietly. Translating costs one struct
literal per function; `HAL_SendError` additionally drops the `isLVCode`
argument the current signature no longer takes. **[decided]**

**One discriminator, not two.** Argument shape is a clean second check
for `HAL_SendError` — old-ABI `a2` is `isLVCode` ∈ {0,1} where new-ABI
`a2` is a pointer — and for `HAL_SendConsoleLine`, which takes one
argument, it does not exist without dereferencing that argument and
judging whether sixteen bytes look like a `{ptr, len}`. Reading it
safely needs a fault-free probe on the one path that must not fault.
Checking only where checking is cheap buys asymmetric confidence that
reads as uniform. **[decided]**

**It announces itself on the first translated call**, one line to
stderr, the posture the `fmt::vformat` stub already takes. stderr is not
the path that is broken, and on `linuxx86-64` the line never prints,
because nothing there ever calls the pair. **[decided]**

### It ships built, with its source beside it

Two stripped binaries are checked in — `linuxx86-64/librevshim.so` and
`linuxsystemcore/librevshim.so` — together with the `.cpp` they came
from and the exact commands that built them. Gradle does not compile
them. **[decided]**

**One source, both platforms, no `#ifdef`.** The console translators are
dead weight on `linuxx86-64`, where REVLib never calls the pair, and
they are compiled in anyway. Building both binaries from one unguarded
file is what makes *rebuild both, or edit neither* checkable by reading;
a platform guard would produce two shims that are hard to tell apart.
**[decided]**

The point of shipping binaries is that no student needs a C++ toolchain
to run a simulation, and no `./gradlew build` breaks on a missing
cross-compiler. The point of shipping the source anyway is that a 66 KB
blob is otherwise the one artifact in this tree nobody can review, in a
repository whose whole posture is that a reader can check a claim.
`.gitignore` ignores `*.so`; the negation rule is scoped to this one
directory so the binaries stay in the single obvious place.

**The aarch64 build uses allwpilib's own SystemCore toolchain**,
`~/.gradle/toolchains/first/2027/systemcore/bin/aarch64-systemcore2027-linux-gnu-g++`
(GCC 14.3.0) — not a distribution cross-compiler. The stub returns a
`std::string`, so its ABI has to be the one the Pi's libstdc++ hands
REVLib, and the toolchain that built the Pi's other natives is the one
that guarantees it. **[decided]**

### `LD_PRELOAD`, in three places, because `System.load` cannot work

HotSpot's `System.load` dlopens with `RTLD_LOCAL`, so a shim loaded
from Java is invisible to libraries loaded after it. Preloading from
`Main` was tried and does not resolve the symbols. **[executed]** The
fix therefore cannot live in Java.

`build.gradle` sets `LD_PRELOAD` in the three places a JVM meets
REVLib: `tasks.withType(Test)`, `JavaSimulationTask`, and the robot's
own start command, through `WPILibJavaArtifact.setJavaCommand` — a
public, supported hook whose value is echoed into
`/home/systemcore/robotCommand`. **[source]**

**On the Pi, `libwpiutil.so` is preloaded ahead of the shim.** The shim
forwards `WaitForObject` to wpiutil, and the JVM loads wpiutil with
`RTLD_LOCAL`, so a library preloaded at process start cannot see the
symbol it forwards to: preloaded alone, `librevshim.so` dies with
`undefined symbol: _ZN3wpi4util13WaitForObjectEi` even though the Pi's
`libwpiutil.so` exports it in all three copies on the image.
**[executed]** Naming wpiutil first in `LD_PRELOAD` puts it in the
global scope before the shim is relocated. **[decided]** A `DT_NEEDED`
plus `RPATH` was rejected for baking the Pi's directory layout into a
checked-in binary, and a first-call `dlopen(RTLD_GLOBAL)` for putting
failure handling on the path that must not fail. The desktop does not
need it: every push preloads the shim alone and `WiringTest` passes.
**[executed]** Why the two platforms differ here is not established, and
does not need to be — the ordering is correct on both. On the Pi this
stayed invisible because the `std::bad_alloc` always arrived first.

The aarch64 binary
deploys to `/home/systemcore/deploy` as its own `FileTreeArtifact`,
which is a directory this project already owns with
`deleteOldFiles = false`, rather than GradleRIO's third-party library
directory, which GradleRIO rewrites. **[decided]**

### `WiringTest` is the deletion trigger

The `@Disabled("constructing a SPARK terminates the JVM")` annotation is
gone and the test runs. It is also the check that says when this ADR
can be deleted:

```
./gradlew test --tests first.robot.WiringTest -PnoRevShim
```

**`-PnoRevShim` drops the preload; a green run means REVLib's native
binds on its own and this whole ADR can go.** Today it exits 127, which
is the original abort. **[executed]** The shim ships with its own
dismantling instrument wired up, passing with it and failing without it,
rather than with a note asking somebody to remember.

**It is necessary and not sufficient.** That test runs on the desktop,
where REVLib never makes a console call at all: the same interposer on
`linuxx86-64` records **zero** `HAL_SendError` and `HAL_SendConsoleLine`
calls from REVLib across a full SPARK construction, while a control
proves the interposition is live on that platform. **[executed]** So a
rebuilt REVLib would turn `-PnoRevShim` green while the aarch64 half is
still necessary, and somebody would delete a directory the robot
needs. The second half of the trigger is a deploy with the preload
dropped, confirmed to reach *"Robot program startup complete"* on the
Pi. Both commands live in `revshim.cpp`'s header comment. Nothing
enforces running both, and nothing should. **[decided]**

## Consequences

`simulateJava` runs. A student picks an opmode and drives. `first.Main`
was run headless for twenty seconds with the shim preloaded: full
startup, Phoenix CAN bus up, telemetry logging, zero crashes, and
`simulationPeriodic(): 0.009247s` — which is `Drive.updateSim()`,
`SwerveDriveSim`, `OnboardLoopSim`, both sensor sims and
`SparkOutputSim` all executing. **[executed]**

ADR 0013's **Tier 2 is live for the first time**. `WiringTest` runs, so
#61's criteria are met, and #59's `Drive`-against-an-independent-
`Scheduler` test becomes writable — deliberately as its own change,
not buried in a linker fix.

**The robot boots, and the shim is bigger than it was.** The
`std::bad_alloc` was a second REVLib/WPILib ABI gap that the two-symbol
one was hiding, on a path the shim did not touch — see *Context*. With
both bridged, the real jar on the Pi reaches *"Robot program startup
complete"* and enters `robotPeriodic`. **[executed — 192.168.1.202,
2026-08-30]** One boot exercises both branches of the discriminator and
both are legible: REVLib's own errors arrive intact — *"Bus 0: [Spark
Flex] IDs: 1, timed out while waiting for Get Firmware Version"* where
there used to be an abort — and WPILib's arrive with their stack traces
unchanged.

The shim now interposes two functions WPILib itself calls, on every
error anything reports, rather than supplying two symbols nobody else
defines. That is a real increase in what it can break, and it is the
price of a robot that boots before REV publishes again.

**It is still not a running robot.** `robotPeriodic` throws on its first
pass — `RobotController.getMeasureBatteryVoltage()` reaches
`PowerJNI.getVinVoltage()` and gets `HalHandleException` code `-1098`,
which `startCompetition` does not handle — so the service crash-loops one
step further along than it did. **[executed]** That is the next failure
on the same path, it is not REVLib's and not the shim's, and it is
[#94](https://github.com/Drew-Robotics/2027beta/issues/94).

**The aarch64 half was shipped incorrect, not "correct and unproven".**
This ADR's first version claimed the latter. In fact `librevshim.so`
preloaded alone on the Pi never loads at all — it cannot resolve
`WaitForObject`, the symbol it forwards to — so the `javaCommand` line
was wrong independently of the console problem, and would have been
wrong even if REVLib had never printed a line. **[executed]**
[#93](https://github.com/Drew-Robotics/2027beta/issues/93) carries that
defect; it lands with this.

**CI covers the desktop binary and nothing covers the aarch64 one.**
Every push proves `linuxx86-64/librevshim.so` still binds, because the
test task preloads it. The aarch64 binary has now been run on a
robot — see above — but nothing automated will run it again, and at
build time it is checked only by `nm -D --defined-only`. The rebuild
commands in `revshim.cpp` are what keep that reproducible rather than
folkloric.

That gap is worse than when it was written. The console translators are
the half of the shim with a way to be *quietly* wrong — a misclassified
call produces bad Driver Station output, not an abort — and they are the
half no automated check on any machine will ever execute, because the
calls they exist for are never made on the desktop. **[executed]** What
covers them is a deploy and a human reading the console.

**`PathFollowingTest` keeps its copy of `Drive.updateSim()`'s sub-step
loop.** It could now be written against a real `Drive`, and it must not
be: it holds Tier 1's tightest numeric assertions, and ADR 0013 puts
those in the tier with no vendor jars precisely so they do not depend on
something we intend to delete. If the drift becomes a problem, the
answer is to extract the sub-step loop into `first.robot.sim`.

## Traps

**`System.load` from Java looks like the clean fix and silently is not.**
It runs, it prints nothing, and the symbols stay unresolved, so the
abort arrives later and looks unrelated to the line that was supposed to
prevent it. **[executed]**

**`LD_PRELOAD` is set by Gradle and by nothing else.** A JVM launched
outside Gradle — an IDE test run, a debugger, `java -jar` by hand —
gets the original exit-127 abort with no hint of why, on a machine where
`./gradlew test` passes. This is the price of not patching the binary;
see *Rejected*.

**All eight SPARKs get the same string out of `fmt::vformat`.** Today
nothing reads it. That is a fact checked against one REVLib version by
enumerating `SimDevice` names, not a property of the design, and a
REVLib build that starts keying something on that string would produce
four modules that share an identity with no error anywhere.

**A distribution `aarch64-linux-gnu-g++` is not the right compiler.**
It will build a shim that exports the correct symbols and may still
disagree with the Pi's libstdc++ about `std::string`. Use the toolchain
named in *Decision*, and check the result with
`nm -D --defined-only`, which must name both symbols.

**Nothing checks that the checked-in binaries match the checked-in
source.** Editing `revshim.cpp` without rebuilding both binaries leaves
a repository that reads correctly and behaves the way the old binary
did. Rebuild both, or edit neither.

**A REVLib native the discriminator does not know about is passed
through.** The four library names are written out in `revshim.cpp`. A
REVLib release that renames or splits one, plus a `vendordeps` bump that
pulls it in, silently moves that library's console calls to the
pass-through branch, where they are read as the new ABI and misfire in
exactly the way this shim exists to prevent — with no error anywhere.
Re-read the `libName`s in `vendordeps/REVLib.json` whenever REVLib's
version moves.

**A misclassified call corrupts output instead of aborting.** Both
branches return normally, so the failure mode is wrong text on the
Driver Station console, not a crash. This is the one part of the shim
that can be wrong without saying so, which is why an unrecognised caller
falls back to the modern ABI rather than the old one.

**Declare the interposer against the header, never from memory.**
The first instrument in
[#87](https://github.com/Drew-Robotics/2027beta/issues/87) used the
2026 seven-argument `HAL_SendError` and produced a convincing, entirely
wrong reading in
which WPILib's own calls looked shifted. The signatures come from
`hal/src/main/native/include/wpi/hal/DriverStation.h` in
`~/dev/allwpilib`. **[source]**

**`LD_PRELOAD` order matters on the Pi, not on the desktop.**
`libwpiutil.so` must be named before `librevshim.so`. Reversing them, or
dropping wpiutil, gives `symbol lookup error: undefined symbol:
_ZN3wpi4util13WaitForObjectEi` at JVM start — on a machine where
`./gradlew test`, which preloads the shim alone, is green.

## Open

**When this can be deleted.** `maven.revrobotics.com` still lists exactly
one 2027 version, `2027.0.0-alpha-6`, last published 2026-07-28.
**[executed — 2026-08-30]** Nothing here waits on a decision; it waits on
REV. No defect has been filed with them, because a beta run against a
WPILib it was not built for is not a supportable report. **[decided]**

The question this section used to hold — why `new SparkFlex(...)` threw
`std::bad_alloc` on the SystemCore — is answered in *Context*, and the
conclusion it drew, that no shim could fix it, was wrong.
[#87](https://github.com/Drew-Robotics/2027beta/issues/87) has the
diagnosis.

## Rejected

**Ripping REVLib out of the mechanisms.** The original request, and the
reason this session started. It reverses ADR 0003's *the hardware
boundary: there is not one* — an IO interface per mechanism with one
production implementation — and ADR 0010's rule that a mechanism reads
its encoders in simulation exactly as it does on hardware. It is a
class-level rewrite of `SwerveModule` and `Drive` against twenty lines
of C, and it would have fixed the laptop while leaving the robot dead.
Not to be re-raised on the strength of this blocker; it needs a reason
of its own.

**A fake `com.revrobotics.*` source set** swapped in for tests. Smaller
than an IO seam and larger than the shim, and it makes the simulated
path stop exercising the vendor code the real path uses — which is the
one thing ADR 0010 was trying to preserve.

**`patchelf --add-needed librevshim.so libREVLibWpi.so`.** Works
everywhere, including the IDE runs that `LD_PRELOAD` misses. Rejected
because it mutates an extracted native under `build/` that Gradle
re-extracts, and because a bridge should be removable in one commit —
a patched binary is exactly the kind of thing that outlives its reason.

**Gradle compiling the shim.** Then every student needs a C++
toolchain, including an aarch64 cross-compiler for a platform they never
deploy to, or `./gradlew build` fails on a machine that only ever runs
simulations.

**Decoding `fmt::basic_format_args` so the stub formats properly.**
Correct, and it couples this repository to fmtlib v12's internal packed
argument descriptor — a second ABI dependency on a library WPILib has
already stopped using, bought for fidelity in a string nothing reads.

**Waiting for REVLib to publish against a current WPILib.** The honest
estimate is weeks-to-never; there is no alpha-7 and no announced date.
Meanwhile the simulation, Tier 2 and the deploy are all down. The console
gap did not change this: a month after that one published version,
`maven-metadata.xml` is unmoved. **[executed]**

**Swallowing REVLib's console calls rather than translating them.**
Fewer lines, and already proven to reach *"Robot program startup
complete"*. **[executed]** It silences the path that reports CAN
timeouts, brownouts and firmware mismatches, so the first symptom of a
module dropping off the bus would be a robot that does nothing and says
nothing. A crash is a better failure than that.

**Redirecting REVLib's imports so no discrimination is needed.**
`libREVLibWpi.so` is the only REVLib native that imports the pair
**[executed]**, so a `patchelf`-class rewrite pointing it at a
shim-private name would remove the classification problem outright. It
is the same rewrite rejected above for `--add-needed`, for the same
reasons — it mutates a native Gradle re-extracts, and a patched binary
outlives its reason — and it trades a bounded runtime test for an
unbounded build step.

**A second discriminator on argument shape.** Clean and free for
`HAL_SendError`, unavailable for `HAL_SendConsoleLine` without
dereferencing its one argument behind a fault-free probe. Half a
cross-check reads like a whole one.

**`StatusLoggerJNI.disableAutoLogging()` on its own.** It removes the
startup banner, which is where the crash surfaces, and leaves
`HAL_SendError` untouched — so the robot boots and then dies the first
time REVLib reports anything. **[executed]** Insufficient, and worse
than the crash it hides.

**Filing the ABI break with REV.** The mismatch is between a beta REVLib
and a WPILib development build it was never compiled against. There is
no supported configuration to report a defect against, and REV cannot
act on one. **[decided]**

## Source

The design was settled in a grilling session on 2026-08-30, which found
the two-symbol gap by resolving the native by hand rather than by
reading REVLib's release notes. The decision to ship built binaries
rather than compile them is Drew's, taken against a recommendation to
have Gradle build the source.
