# ADR 0015 — Binding REVLib's native

## Status

Accepted — 2026-08-30. Resolves the blocker ADR 0013 records against
Tier 2 and ADR 0010 records against running the simulation at all: both
were written while constructing a SPARK terminated the JVM, and on the
desktop it no longer does. It does **not** resolve the deploy, which
fails further in for a different reason — see *Open*.

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

## Decision

### A shim supplies the two symbols, and nothing else changes

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

### It ships built, with its source beside it

Two binaries are checked in — `linuxx86-64/librevshim.so` and
`linuxsystemcore/librevshim.so`, stripped, 14 KB and 66 KB — together
with the `.cpp` they came from and the exact commands that built them.
Gradle does not compile them. **[decided]**

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
`/home/systemcore/robotCommand`. **[source]** The aarch64 binary
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

**The deploy is not unblocked, and the shim is not what is stopping
it.** On the Pi, `new SparkFlex(...)` throws `std::bad_alloc` — and it
throws it identically with the shim preloaded and without it, at the
same call, with the shim's own stub never reached. **[executed —
192.168.1.202, 2026-08-30]** So `linuxsystemcore` has a second, earlier
REVLib failure that the two-symbol gap was hiding, and the aarch64 half
of this ADR is shipped correct and unproven. See *Open*.

**CI covers the desktop binary and nothing covers the aarch64 one.**
Every push proves `linuxx86-64/librevshim.so` still binds, because the
test task preloads it. The aarch64 binary is checked only by
`nm -D --defined-only` at build time and by the static fact that
`libwpiutil.so` for `linuxsystemcore` exports the `WaitForObject(int)`
it forwards to. **[executed]** It has never been shown to do anything
on a running robot, because nothing on the Pi gets far enough to need
it. The rebuild command in `revshim.cpp` is what keeps that
reproducible rather than folkloric.

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

## Open

**Why `new SparkFlex(...)` throws `std::bad_alloc` on the SystemCore.**
It is not the shim: the failure is byte-identical with `LD_PRELOAD` set
and unset, and the stub prints its announcement line on first call and
never printed. **[executed]** Everything around it on the Pi is healthy
— `HAL.initialize()` returns true, the telemetry backends register,
`PowerDistribution` opens, `TrajectoryLoader` parses, and `can_s0` and
`can_s1` are both `UP`. **[executed]** The leading hypothesis is that
REVLib's `linuxsystemcore` native disagrees with the current wpiutil
about more than two symbol names — a struct that links but no longer
matches — in which case no shim fixes it and only a REVLib rebuild will.

Unblocking it means a REVLib built against a current WPILib, or a
narrower diagnosis than this ADR has — the evidence and the next
measurements are on
[#87](https://github.com/Drew-Robotics/2027beta/issues/87). Until then the aarch64 binary and
the `LD_PRELOAD` on `robotCommand` are shipped because they are correct
and cost nothing, not because they have been shown to help.

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
Meanwhile the simulation, Tier 2 and the deploy are all down.

## Source

The design was settled in a grilling session on 2026-08-30, which found
the two-symbol gap by resolving the native by hand rather than by
reading REVLib's release notes. The decision to ship built binaries
rather than compile them is Drew's, taken against a recommendation to
have Gradle build the source.
