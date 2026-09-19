# dyn4j on the robot's classpath — where an ordinary `implementation` dependency lands

**Research for:** [Drew-Robotics/2027beta#143](https://github.com/Drew-Robotics/2027beta/issues/143)
**Date:** 2026-09-19

## Source and trust level

This document uses the ADR index's claim tags rather than the `[V]`/`[C]`/`[?]` key the
older research files carry, because every claim here is either a file read or a command run:

| Tag | Means |
|---|---|
| **[source]** | Read from a named file, cited by path and line. |
| **[executed]** | Verified by running it. |
| **[unverified]** | Not checked. |

### Artifacts under test

| Thing | Coordinate / location |
| --- | --- |
| GradleRIO | `org.wpilib.GradleRIO:2027.0.0-alpha-7` — the version `build.gradle:7` pins |
| GradleRIO source | `wpilibsuite/GradleRIO` tag `v2027.0.0-alpha-7`, tarball fetched from GitHub |
| DeployUtils | `org.wpilib:DeployUtils:2027.2.0`, the jar GradleRIO alpha-7 resolves; read with `javap -c` |
| dyn4j | `org.dyn4j:dyn4j:6.0.0` |
| WPILib | `~/dev/allwpilib` at `ad36e7932` |
| JVM | Temurin OpenJDK 25.0.3 (arm64) |

GradleRIO's classes are Java, not Groovy, and the tag's source and the cached jar agree on
every signature this document quotes, so the source tarball is read as the plugin's own text.

---

## 0. The answer

**dyn4j reaches the robot. Both halves of it.** An ordinary
`implementation 'org.dyn4j:dyn4j:6.0.0'` is SFTP'd to
`/home/systemcore/wpilib/classpath/dyn4j-6.0.0.jar` *and* written into the `-cp` line of
`/home/systemcore/robotCommand.args`, with no vendordep, no repository edit and no fat jar.
**[executed]**

The premise in #143 — *"`WPILibJavaArtifact.generateArgFile` writes a `-cp` line over
`/home/systemcore/wpilib/classpath` plus our own jar … so nothing in that path installs it"* —
is wrong, and so is the paraphrase it inherited from ADR 0003's *Build, deploy and console*.
The classpath directory is not a pre-existing WPILib install that the deploy adds one jar to.
**GradleRIO creates it, and it fills it from our `runtimeClasspath` configuration** — every
jar, individually, ours last. That is also what this repo's own earlier research already said,
in a sentence nobody carried into the ADR: *"the project jar + every runtime-classpath jar,
individually"* (`docs/research/systemcore-deploy.md:425`). **[source]**

So **ADR 0010's "Sim ships in the deploy artifact, behind `isSimulation()`" survives
untouched.** Nothing has to be amended, no alternative has to be costed, and the reasoning it
was challenged on — *"a deploy artifact carrying a few unreferenced classes is not a cost
anybody can measure"* — is only strengthened: the measurable cost of the dependency is
**493 KiB on a flash card**.

The rest of this document is the evidence, plus the one genuine hazard the investigation
turned up, which is not about the classpath at all (§4).

---

## 1. Where the dependency lands, from GradleRIO's own source

### 1.1 The artifact is pointed at `runtimeClasspath`, not at the jar

`build.gradle:223` calls `deployArtifact.configureApplication(application)`. That method is
four lines:

```java
public void configureApplication(JavaApplication javaApplication) {
    JvmFeatureInternal mainFeature = JavaPluginHelper.getJavaComponent(getTarget().getProject()).getMainFeature();
    setConfiguration(mainFeature.getRuntimeClasspathConfiguration());
    setJar(mainFeature.getJarTask().get());
    this.mainClass.set(javaApplication.getMainClass());
}
```

`WPILibJavaArtifact.java:115-120`, tag `v2027.0.0-alpha-7`. **[source]** The artifact holds
**two** things: the project's `jar` task *and* the whole `runtimeClasspath` configuration. An
`implementation` dependency is in `runtimeClasspath` by definition.

### 1.2 `getFiles()` is the union of the two

`WPILibJavaArtifact` extends `DebuggableJavaArtifact`
(`gradlerio/.../deploy/DebuggableJavaArtifact.java:10`) extends DeployUtils'
`JavaClasspathArtifact` extends `FileTreeArtifact`. **[source]** `JavaClasspathArtifact`'s
constructor registers a pre-worker-thread action; disassembled from
`DeployUtils-2027.2.0.jar` it is exactly:

```java
files.set(
    layout.files(configurationProperty.get().resolve())
        .plus(jarProperty.get().getOutputs().getFiles())
        .getAsFileTree());
```

(`org/wpilib/deployutils/deploy/artifact/JavaClasspathArtifact.class`, `lambda$new$0`,
bytecode offsets 25–100.) **[source]** `Configuration.resolve()` on `runtimeClasspath`
returns every resolved jar, transitive ones included.

### 1.3 `FileTreeArtifact.deploy` uploads all of them

```java
files.get().visit((FileVisitDetails d) -> { ... map.put(d.getPath(), d.getFile()); });
ctx.execute("mkdir -p " + String.join(" ", dirs));
if (deleteOldFiles.getOrElse(false)) { /* find . -type f -print0, delete anything not in map */ }
ctx.put(map, cacheMethod.getOrElse(null));
```

(`FileTreeArtifact.class`, `deploy`, offsets 0–234.) **[source]** The destination is
`getDirectory()`, which `WPILibJavaArtifact`'s constructor sets to `CLASSPATH_PATH =
"/home/systemcore/wpilib/classpath"` with `deleteOldFiles = true`
(`WPILibJavaArtifact.java:23, :65-66`). **[source]**

`deleteOldFiles = true` is worth noticing in both directions: the directory is *ours*, wiped
of anything we did not send, so a dependency that is later removed is cleaned off the device
on the next deploy rather than lingering.

### 1.4 `generateArgFile` enumerates the same collection

```java
args.add("-cp \"\\");
String deployDirectory = getDirectory().get();
List<File> files = new ArrayList<>(getFiles().get().getFiles());
for (int i = 0; i < files.size(); i++) {
    String path = PathUtils.combine(deployDirectory, files.get(i).getName());
    args.add(i != files.size() - 1 ? path + ":\\" : path + "\"");
}
```

`WPILibJavaArtifact.java:143-158`. **[source]** Same `getFiles()`. So the `-cp` line and the
upload set cannot disagree — one collection feeds both.

### 1.5 Run against the real toolchain

`org.dyn4j:dyn4j:6.0.0` was added to `dependencies {}` in a scratch edit, a task was
registered that runs the artifact's own `preWorkerThread` actions and then calls the private
`generateArgFile(null)` reflectively, and `./gradlew dumpDeployArgFile` was run. The edit has
been reverted; nothing of it is committed. **[executed]**

The uploaded file set gained exactly one entry, `dyn4j-6.0.0.jar`, and the generated arg file
read:

```
-XX:+UseZGC
-Djava.library.path=/home/systemcore/wpilib/third-party/lib
--add-opens
java.base/jdk.internal.vm=ALL-UNNAMED
--add-opens
java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
-cp "\
/home/systemcore/wpilib/classpath/wpilibj-java-2027.0.0-alpha-7.jar:\
...
/home/systemcore/wpilib/classpath/wpiapi-java-26.70.0-alpha-2.jar:\
/home/systemcore/wpilib/classpath/dyn4j-6.0.0.jar:\
/home/systemcore/wpilib/classpath/avaje-json-core-3.14.jar:\
...
/home/systemcore/wpilib/classpath/2027beta.jar"
first.Main
```

Thirty-eight jars, dyn4j among them, ours last. **[executed]** Two incidental facts from the
same run, both useful:

- `photonlib-java-v2027.0.0-alpha-2.jar` and `photontargeting-java-…` are **already** deployed
  and already on the `-cp` line, although `CLAUDE.md` correctly notes that nothing in `src`
  imports photonlib. A vendordep costs its jars on the device whether or not a line of code
  names them, so dyn4j's 493 KiB is not a new *kind* of cost.
- The project jar is named after the checkout directory. The run above happened in a worktree,
  so it printed `agent-…​.jar`; it is `2027beta.jar` in the normal checkout, and is quoted that
  way above.

### 1.6 No repository has to be added

`build.gradle` has no `repositories {}` block. GradleRIO supplies eight, and the last is Maven
Central:

```
REPO: WPILocal file:/Users/drewwilliams/.wpilib/2027_alpha7/maven
REPO: WPIOfficialRelease https://frcmaven.wpi.edu/artifactory/release
REPO: WPIWPILibMavenVendorCacheRelease https://frcmaven.wpi.edu/artifactory/vendor-mvn-release
REPO: WPI3f48eb8c-…_0Release https://maven.revrobotics.com/
REPO: WPI515fe07e-…_0Release https://maven.photonvision.org/repository/internal
REPO: WPI515fe07e-…_1Release https://maven.photonvision.org/repository/snapshots
REPO: WPIe995de00-…_0Release https://maven.ctr-electronics.com/release/
REPO: MavenRepo https://repo.maven.apache.org/maven2/
```

Printed from an init script over the unmodified build. **[executed]** dyn4j resolved from it
with no edit beyond the one dependency line.

---

## 2. What the artifact actually is

| Property | Value | How |
|---|---|---|
| Jar size | 504,793 bytes (493 KiB), 318 entries | **[executed]** |
| Runtime dependencies | none — the only `<dependency>` in the POM is `junit:junit:4.13.1`, `<scope>test</scope>` | **[source]** `dyn4j-6.0.0.pom:418-425` |
| Licence | BSD-3 | **[source]** same POM, `<licenses>` |
| Class file version | major 50 (Java 6); `module-info.class` is major 53 | **[executed]** `javap -v` |
| Loads on JDK 25 | yes — constructed a `World`, called `step(1)` under Temurin 25.0.3 | **[executed]** |
| Modularity | carries `module org.dyn4j@6.0.0`, ignored on the classpath | **[source]** `javap module-info.class` |

The Java 6 class files are the only thing here that looked like it might bite, so it was run
rather than reasoned about; the JVM loaded them without complaint.

---

## 3. `sim-hitl` gets it for free too

The bench job stages from `simHitlStage`, whose classpath directory is:

```groovy
into('classpath') {
    from jar
    from configurations.runtimeClasspath
}
```

`build.gradle:256-259`. **[source]** The same `runtimeClasspath` again — so dyn4j stages. And
the launch line the bench script writes is a wildcard over that directory:

```
exec /usr/bin/java -Djava.library.path=$PWD/lib \
    ... -cp 'classpath/*' first.Main
```

`.github/bench/sim-hitl.sh:90-93`. **[source]** A wildcard classpath needs no edit at all when
a jar is added.

`./gradlew simHitlStage` could **not** be run here: the `linuxarm64` native zips are not in the
local Gradle cache and `frcmaven.wpi.edu` was unreachable for them during this investigation
(five minutes of timeouts on `wpimath-cpp`, `wpinet-cpp` and `opencv-cpp`). So the staging is
**[source]**, not **[executed]** — but the part that carries dyn4j is `configurations
.runtimeClasspath`, which §1.5 enumerated directly, and that enumeration is **[executed]**.

This also disposes of the "desktop-only sim" option before it needs costing. `sim-hitl` runs a
`linuxarm64` sim build **on the Pi** (ADR 0013, *Job 2*), so "desktop-only" would not have
meant "runs everywhere a developer sits" — it would have meant a sim that does not run on the
one machine ADR 0013 measures loop time on. It is moot, because nothing forces the choice.

---

## 4. The real hazard is the opmode scanner, and it is not about the classpath

#143 asks what happens if dyn4j lands nowhere and reasons that Java's lazy linking makes an
unreferenced class harmless. The first half is moot (§0). The second half is **not quite
right**, and the reason is worth writing down because it outlives this ticket.

### 4.1 The scanner loads every class under `first.robot`, sim included

`addAnnotatedOpModeClasses(Package)` walks jar entries and keeps any whose name
`startsWith(packagePath)` and ends `.class` (`OpModeRobot.java:452-458`). **[source]** The
package path is `first/robot`, so `first/robot/sim/SwerveDriveSim.class` matches. The ticket's
premise that the scanner being "package-scoped" keeps it away from `first.robot.sim` is wrong
— the scan is explicitly *"in the specified package and all nested packages"*
(`OpModeRobot.java:424-425`). **[source]**

Each match goes to `addAnnotatedOpModeClass`:

```java
Class<? extends OpMode> cls;
try {
    cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader())
            .asSubclass(OpMode.class);
} catch (ClassNotFoundException | ClassCastException e) {
    return;
}
```

`OpModeRobot.java:384-393`. **[source]** Two things matter. `initialize = false`, so no static
initialiser runs. And the catch list is `ClassNotFoundException | ClassCastException` —
**`NoClassDefFoundError` is neither**, and the enclosing `try` in `addAnnotatedOpModeClasses`
catches only `IOException | URISyntaxException` (`OpModeRobot.java:469`). **[source]**

### 4.2 Which references survive the load and which do not

Run rather than reasoned. Two classes were compiled against a third, the third's class file
was deleted, and a scanner with the exact shape above was pointed at both:

```
UsesInBody:    skipped quietly (ClassCastException)
ExtendsAbsent: ESCAPED THE CATCH: java.lang.NoClassDefFoundError: Absent
```

**[executed]**, Temurin 25.0.3. A class that merely *holds, returns or constructs* the missing
type loads fine — field and method descriptors are resolved lazily, so it reaches
`asSubclass`, fails the cast, and is skipped exactly as an ordinary non-opmode class is. A
class that **extends or implements** the missing type cannot be loaded at all, because the
superclass is resolved during the load itself.

### 4.3 Where that error would go

Out of `addAnnotatedOpModeClasses`, out of the `OpModeRobot` constructor (called at
`OpModeRobot.java:534`), into `RobotBase.runRobot`'s `catch (Throwable)` around
`robotConstructor.get()`, which reports *"Could not instantiate robot"* and returns
(`RobotBase.java:468-488`). **[source]** The program then exits, and `robot.service` is
`Restart=always` with `RestartSec=3` (`docs/research/systemcore-deploy.md`), so it becomes a
crash loop — the same shape as the MRC API mismatch ADR 0003 describes, and the same
difficulty to read.

### 4.4 So: real or theoretical?

**Theoretical today, on two independent grounds, and worth one sentence in the physics
design.**

- dyn4j *is* on the robot's classpath (§1), so nothing under `first.robot.sim` can fail to
  link there in the first place.
- Nothing in this repo is reflective: a grep of `src/main/java` for `Class.forName`,
  `getDeclaredConstructor`, `newInstance`, `ServiceLoader`, `MethodHandles`, `ClassLoader`,
  `getAnnotation` and `isAnnotationPresent` returns **zero hits**. **[executed]** The opmode
  scanner is the only reflective thing that touches our classes, Commands v3 and the telemetry
  layer register nothing by reflection, and `Drive` holds `physics` as a plain field
  constructed inside `if (RobotBase.isSimulation())` (`Drive.java:193-194`). **[source]**

The sentence worth carrying forward is the narrow one, because it is the only case that
survives the scanner: **a `first.robot.sim` class must not `extends` or `implements` a dyn4j
type.** Composition is free; inheritance is the one shape that turns a missing jar into a
crash loop rather than into a quiet skip. It is also good design advice independently of any
of this — ADR 0010's seam is *"a pure function of voltages"* with no vendor type crossing it,
and a `SwerveDriveSim extends AbstractPhysicsWorld` would cross it in the loudest possible
way.

---

## 5. ADR 0003's rule is engaged

> We do not rename its packages, move its files, or hand-edit its `build.gradle`. When a
> departure from stock becomes necessary, it carries a one-line comment at the edit saying
> why, and `git diff` against the generator's output is the list of everything that is not
> stock.

`docs/adr/0003-project-and-package-structure.md`, *The stock template, unmodified*.
**[source]**

Adding a line to `dependencies {}` is a hand-edit of `build.gradle`. The rule does not
distinguish "structural" edits from "just a dependency" ones, and it does not need to: its
whole mechanism is that `git diff` against the generator's output is the complete list, and
every entry in that list carries its reason. `build.gradle` already holds five commented
departures under this rule — the analyzers at `:8`, `mrcApiPreflight` at `:56-59`, the
`simHitl*` configurations at `:235-238`, checkstyle/PMD at `:288-289`, and
`generateBuildMetadata` at `:332-333`. A sixth is not an exception; it is the rule working.

Note that the stock `dependencies {}` block is *not* where vendordeps go — those arrive
through `wpi.java.vendor.java()` from `vendordeps/*.json`, which is why the rest of that block
is untouched generator output. dyn4j would be the first hand-written coordinate in it.

Drafted comment, in the voice of the five already there — it answers *why this is not a
vendordep*, which is the question a reader of that line will actually have:

```groovy
// Departs from the template: dyn4j is a plain Maven library rather than a vendordep, so it
// has no JSON to import — one line here is the whole of it. It ships to the robot like any
// other runtime-classpath jar; nothing under first.robot.sim is constructed there.
implementation 'org.dyn4j:dyn4j:6.0.0'
```

---

## 6. Alternatives, and why none of them is needed

Costed only because #143 asks. **None is engaged**, because §1 holds.

| Option | What it would cost |
|---|---|
| **Desktop-only sim** (`src/sim/java`, or a `runtimeOnly`-less configuration) | Reopens ADR 0010's *No `src/sim/java`* and ADR 0003's *stock template*, and breaks `sim-hitl` outright — §3: that job is a `linuxarm64` sim build running on the Pi, so a "desktop-only" sim is one that does not run where ADR 0013 measures loop time. Worst option on the list. |
| **Ship the jar by hand** (a `FileTreeArtifact`, or unpacking dyn4j into ours) | Solves a problem that does not exist, and re-introduces the fat jar ADR 0003 moved to *Rejected* when alpha-7 dropped the shadow plugin. |
| **A vendordep-shaped install** (hand-written `vendordeps/dyn4j.json`) | Would work, and is strictly worse: a vendordep JSON must declare `wpilibYear`, and GradleRIO refuses to configure when that string is not the exact year of the WPILib being built against (`CLAUDE.md`, *Vendor deps*). That is the exact trap `photonlib.json` is already sitting in. A plain Maven coordinate has no year field to go stale. |
| **One `implementation` line** | 493 KiB on the device, one commented line in `build.gradle`, nothing else. |

---

## 7. What was not checked

- **Nothing was deployed to a SystemCore.** The arg file and the upload set in §1.5 were
  produced by GradleRIO's own code on this machine, from the real configuration, but no device
  received them. That the robot's JVM then finds `org/dyn4j/…` on that `-cp` line is
  **[unverified]** on hardware, and can only be verified by a deploy.
- **`./gradlew simHitlStage` did not run** — §3. `frcmaven.wpi.edu` would not serve the
  `linuxarm64` native zips, which is unrelated to dyn4j but did stop that one execution.
- **No `first.robot.sim` class references dyn4j yet**, so §4 is a statement about what the
  loader does, not an observation of this repo's sim classes doing it. Their current imports
  are `org.wpilib.*` only. **[executed]**
- **dyn4j's own runtime behaviour under the 5 ms loop** is not this ticket's question and was
  not measured. §2 establishes only that the jar loads and steps.

---

## 8. Sources

Read for this document:

- GradleRIO `v2027.0.0-alpha-7`, tarball from `wpilibsuite/GradleRIO`:
  `src/main/java/org/wpilib/gradlerio/deploy/systemcore/WPILibJavaArtifact.java`,
  `.../systemcore/RobotCommandArtifact.java`,
  `src/main/java/org/wpilib/gradlerio/deploy/DebuggableJavaArtifact.java`.
- `DeployUtils-2027.2.0.jar` from the Gradle cache, disassembled with `javap -c -p`:
  `org/wpilib/deployutils/deploy/artifact/JavaClasspathArtifact.class`,
  `.../FileTreeArtifact.class`, `.../JavaArtifact.class`, `.../AbstractArtifact.class`.
- `~/dev/allwpilib` at `ad36e7932`:
  `wpilibj/src/main/java/org/wpilib/framework/OpModeRobot.java:378-470, :534`,
  `wpilibj/src/main/java/org/wpilib/framework/RobotBase.java:464-500, :549-590`.
- `org.dyn4j:dyn4j:6.0.0` POM, jar manifest and class files from the Gradle cache.
- In this repo: `build.gradle`, `settings.gradle`, `.github/bench/sim-hitl.sh`,
  `docs/adr/0003-project-and-package-structure.md`,
  `docs/adr/0010-simulation-architecture.md`,
  `docs/adr/0013-ci-and-test-strategy.md`, `docs/bench-runner.md`,
  `docs/research/systemcore-deploy.md`, `src/main/java/first/**`.
