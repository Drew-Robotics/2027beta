# SystemCore: build, deploy, supervision and performance profiling

Research for [Drew-Robotics/2027beta#9](https://github.com/Drew-Robotics/2027beta/issues/9).

**Date:** 2026-08-24
**Researcher:** background agent (Claude)
**Status:** complete; open questions listed at the end.

## How to read this document

Every claim is tagged:

- **[VERIFIED-DEVICE]** — I observed this on the live SystemCore image at `192.168.1.202` over SSH/HTTP.
- **[VERIFIED-LOCAL]** — I ran this on this machine (`~/dev/allwpilib`, Gradle, WSL2) and saw the result.
- **[SOURCE]** — read from primary source: a file in `~/dev/allwpilib`, a file in a `wpilibsuite` repo on GitHub, or a published artifact on `frcmaven.wpi.edu`. Path/URL given.
- **[INFERRED]** — my reasoning on top of the above. Treat as a hypothesis.

Local WPILib checkout under test: `~/dev/allwpilib` at `v2027.0.0-alpha-6-366-gcafb0cc79` (i.e. 366 commits past the alpha-6 tag, heading toward alpha-7). Confirmed with `git describe --tags`. **[VERIFIED-LOCAL]**

---

## 1. Executive summary

The 2027 toolchain is recognisably GradleRIO, renamed and re-namespaced, with the roboRIO target
replaced by a `SystemCore` target. The mechanics are:

| Concern | 2027 answer |
|---|---|
| Gradle plugin | `org.wpilib.GradleRIO`, latest published `2027.0.0-alpha-6` |
| Gradle wrapper (template) | `9.4.1` |
| Java | 25 (source/target 25; Temurin 25.0.2 on-device) |
| Maven namespace | `org.wpilib.*` (was `edu.wpi.first.*`) |
| Deploy transport | SSH + SFTP, user `systemcore` / password `systemcore` |
| What lands on device | `/home/systemcore/wpilib/classpath/*.jar`, `/home/systemcore/wpilib/third-party/lib/*.so`, `/home/systemcore/robotCommand`, `/home/systemcore/deploy/` |
| Supervision | systemd unit `robot.service`, `Restart=always`, `RestartSec=3` |
| Logs | systemd journal (`journalctl -u robot`) |
| Default GC for robot code | **ZGC** (`-XX:+UseZGC`), set by GradleRIO |
| Local WPILib consumption | `./gradlew publish` → `~/releases/maven/development`, consumed via `wpi.maven.useWpilibMavenLocalDevelopment = true` |
| Desktop sim | works today on this WSL2 box via WSLg |

Two findings deserve attention before the repo-layout ticket (#19) starts — see
[§10 Decision impacts](#10-impacts-on-locked-decisions).

---

## 2. The live device: what it actually is

All of this section is **[VERIFIED-DEVICE]** unless marked otherwise. Access used:
`ssh systemcore@192.168.1.202` with password `systemcore` (password auth only; no key installed).
`sshpass` is not installed on this machine, so I drove SSH through a small `pty`-based helper script.

```
$ uname -a
Linux robot 6.12.77-v8-16k #1 SMP PREEMPT_RT Thu Aug 13 08:47:25 UTC 2026 aarch64 GNU/Linux

$ cat /etc/os-release
NAME=LIMELIGHTOS_SYSTEMCORE_BETA
VERSION=cce35b3d110854608301a6c247e67186bdae9e45
ID=limelightos_systemcore_beta
```

Facts:

- Hostname is **`robot`**. `robot.local` resolves from this WSL2 box to `192.168.1.202` via mDNS/avahi — **[VERIFIED-LOCAL]** (`getent hosts robot.local` → `192.168.1.202 robot.local`). That matters: GradleRIO's `useDefaultSystemcoreHostName()` will work from this dev machine without any address override.
- Kernel is **`PREEMPT_RT`** — a real real-time kernel, not just `PREEMPT`.
- Hardware: `Raspberry Pi 5 Model B Rev 1.0`, 4 cores, 8 GB RAM (`7415 MB` free at rest), NVMe root (`/dev/nvme0n1p5`, 6.8 G, 22 % used).
- CPU governor `ondemand`, current freq 2.4 GHz.
- `/proc/cmdline` has **no `isolcpus`, no `nohz_full`, no CPU affinity partitioning**. It does have `cgroup_disable=memory` (so no memory cgroup accounting) and `pcie_aspm.policy=performance`.
- `/proc/sys/kernel/sched_rt_runtime_us` = `950000` — the standard RT throttle: RT tasks can consume at most 95 % of each second. A runaway RT thread cannot fully lock the box.
- `ulimit -r` in an interactive SSH session is **`0`** — a plain SSH login cannot raise RT priority. RT priority for the robot comes from the systemd unit (see §4).
- User `systemcore` (uid 105) is in `sudo` and has **passwordless sudo** (`sudo -n true` succeeds).
- Java on device: **`openjdk 25.0.2 2026-01-20 LTS, Temurin-25.0.2+10`**, and it is a **full JDK**, not a JRE — `javac`, `jcmd`, `jfr`, `jmap`, `jstack`, `jhsdb`, `jconsole`, `jlink` are all present in `/usr/lib/jvm/bin`.
- `perf`, `async-profiler` and `bpftrace` are **not** installed. `strace` and `htop` are.

### Running services (the SystemCore "OS")

The image is LimelightOS. Enabled units include `robot.service`, `mrccomm.service`,
`elastic_dashboard.service`, `apache.service`, `llttyd.service`, `blocks.service`,
`powerdistribution.service`, `radiodaemon.service`, `expansionhubdaemon.service`, `docker.service`,
`hailort.service` and ~20 `limelight_*` services (CAN bus process/sniffer/watchdog/loadmon,
vision servers, IO daemon, package manager, update manager, access point, dnsmasq).

`mrccomm` is the DS communications daemon (`/usr/bin/MrcCommDaemon`), listening on TCP **1740** and
**1741** — the roboRIO's `FRC_NetworkCommunications` equivalent. `mrclib` is its client library and is
a real Gradle dependency of WPILib (`org.wpilib.mrclib:mrclib-cpp:2027.1.0-alpha-1-112-g3f8f56e`,
`gradle/libs.versions.toml`) **[SOURCE]**.

### The web dashboard at `http://192.168.1.202/`

- Apache (`httpd`) serves `/var/www/html`, a React SPA whose `<title>` is **`Limelight`**. It is the Limelight web UI, extended for SystemCore, not a WPILib-authored page. All unknown paths 200 (SPA fallback), so probing endpoints from the outside tells you nothing.
- The backing API is **gunicorn on port 9001** (`gunicorn --timeout 60 -b 0.0.0.0:9001 main:app`, running as root).
- The dashboard is where you **set the team number** and **install packages** ("Add Package" card, `.ipk` files) — per `wpilibsuite/SystemcoreTesting/README.md` **[SOURCE]**. Elastic and AdvantageScope Lite ship as installable packages; the README says they will be pre-baked into the OS soon.
- `elastic_dashboard.service` is already running on this unit: it is literally `python3 start_elastic.py`, a `http.server.SimpleHTTPRequestHandler` on port **5803**, serving `/usr/local/bin/elastic`. **[VERIFIED-DEVICE]** AdvantageScope is **not** installed on this unit.
- `llttyd.service` gives a **web terminal on port 4901** (`ttyd`), auto-logged-in as `systemcore`. That is a usable escape hatch when SSH is inconvenient.
- Vision/telemetry ports 5800–5837 are bound by `visionserver*` processes.
- NetworkTables port **5810** is *not* bound right now, because no robot program is running.

Per `SystemcoreTesting/README.md` **[SOURCE]**, the canonical addresses are:
`10.TE.AM.2` (Ethernet/radio), `172.26.0.1` (USB from Windows), `172.27.0.1` (USB from Linux/macOS),
`172.30.0.1` (built-in AP, SSID `SYSTEMCORE` / password `PASSWORD`). Our unit is additionally on the
LAN at `192.168.1.202`.

---

## 3. What a 2027 robot project's Gradle setup looks like

### 3.1 The plugin

GradleRIO survived; it moved namespace. **[SOURCE]** `~/dev/allwpilib/DevelopmentBuilds.md`:

```groovy
plugins {
  id "java"
  id "org.wpilib.GradleRIO" version "2027.0.0-alpha-5"
}
```

Published versions on the WPILib plugin repo — **[VERIFIED-LOCAL]**, fetched
`https://frcmaven.wpi.edu/artifactory/ex-gradle/org/wpilib/GradleRIO/org.wpilib.GradleRIO.gradle.plugin/maven-metadata.xml`:

```xml
<latest>2027.0.0-alpha-6</latest>
<release>2027.0.0-alpha-6</release>
<versions>
  <version>2027.0.0-alpha-5</version>
  <version>2027.0.0-alpha-6</version>
</versions>
```

So **`org.wpilib.GradleRIO` version `2027.0.0-alpha-6`** is the newest published plugin as of today.
(An older, now-dead id `edu.wpi.first.GradleRIO2027` version `2027.0.0-alpha-1` appears in
`wpilibsuite/SystemcoreTesting/testprojects/pwmoutput/build.gradle` **[SOURCE]** — that test project is
stale, still on Java 17. Do not copy it.)

GradleRIO source lives at `github.com/wpilibsuite/GradleRIO`, package `org.wpilib.gradlerio`.
It applies `org.wpilib.DeployUtils` (`org.wpilib:DeployUtils:2027.2.0`) and pulls vendordep handling
from `org.wpilib:native-utils:2027.13.1` (`org.wpilib.nativeutils.vendordeps`). **[SOURCE]**

### 3.2 The current project template

The authoritative template is `wpilibsuite/vscode-wpilib`, at
`vscode-wpilib/resources/gradle/java/build.gradle` and
`vscode-wpilib/resources/gradle/shared/settings.gradle`. **[SOURCE]** Verbatim highlights:

```groovy
plugins {
    id "java"
    id "org.wpilib.GradleRIO" version "###GRADLERIOREPLACE###"
    id "com.gradleup.shadow" version "9.3.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

deploy {
    targets {
        systemcore(getTargetTypeClass('SystemCore')) {
            team = project.wpilib.getTeamNumber()
            useDefaultSystemcoreHostName()
            debug = project.wpilib.getDebugOrDefault(false)

            artifacts {
                wpilibJava(getArtifactTypeClass('WPILibJavaArtifact')) {
                }
                wpilibStaticFileDeploy(getArtifactTypeClass('FileTreeArtifact')) {
                    files = project.fileTree('src/main/deploy')
                    directory = '/home/systemcore/deploy'
                    deleteOldFiles = false
                }
            }
        }
    }
}

dependencies {
    annotationProcessor wpi.java.deps.wpilibAnnotations()
    implementation wpi.java.deps.wpilib()
    implementation wpi.java.vendor.java()

    systemcoreDebug   wpi.java.deps.wpilibJniDebug(wpi.platforms.systemcore)
    systemcoreDebug   wpi.java.vendor.jniDebug(wpi.platforms.systemcore)
    systemcoreRelease wpi.java.deps.wpilibJniRelease(wpi.platforms.systemcore)
    systemcoreRelease wpi.java.vendor.jniRelease(wpi.platforms.systemcore)

    nativeDebug   wpi.java.deps.wpilibJniDebug(wpi.platforms.desktop)
    nativeDebug   wpi.java.vendor.jniDebug(wpi.platforms.desktop)
    simulationDebug   wpi.sim.enableDebug()
    nativeRelease wpi.java.deps.wpilibJniRelease(wpi.platforms.desktop)
    nativeRelease wpi.java.vendor.jniRelease(wpi.platforms.desktop)
    simulationRelease wpi.sim.enableRelease()

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

wpi.sim.addGui().defaultEnabled = true
wpi.sim.addDriverstation()
```

Renames vs. 2025, all confirmed in source:

| 2025 | 2027 |
|---|---|
| `edu.wpi.first.GradleRIO` | `org.wpilib.GradleRIO` |
| `roborio(getTargetTypeClass('RoboRIO'))` | `systemcore(getTargetTypeClass('SystemCore'))` |
| `frcJava(... 'FRCJavaArtifact')` | `wpilibJava(... 'WPILibJavaArtifact')` |
| `frcStaticFileDeploy` | `wpilibStaticFileDeploy` |
| `project.frc.getTeamNumber()` | `project.wpilib.getTeamNumber()` |
| `roborioDebug` / `roborioRelease` configurations | `systemcoreDebug` / `systemcoreRelease` |
| `wpi.platforms.roborio` (`linuxathena`) | `wpi.platforms.systemcore` (`linuxsystemcore`) |
| `edu.wpi.first.gradlerio.GradleRIOPlugin.javaManifest(...)` | `org.wpilib.gradlerio.GradleRIOPlugin.javaManifest(...)` |
| deploy dir `/home/lvuser` | deploy dir `/home/systemcore` |

`WPILibDeployPlugin` registers the project extension under the name **`wpilib`** (not `frc`):
`project.getExtensions().create("wpilib", WPILibExtension.class, ...)`. **[SOURCE]**

### 3.3 `settings.gradle`

```groovy
pluginManagement {
    repositories {
        String wpilibYear = '2027_alpha5'
        // ...resolves to ~/wpilib/2027_alpha5/maven on Linux,
        //    C:\Users\Public\wpilib\2027_alpha5\maven on Windows
        maven { name = 'wpilibHome'; url = wpilibHomeMaven }
        mavenLocal()
        gradlePluginPortal()
    }
}
Properties props = System.getProperties();
props.setProperty("org.gradle.internal.native.headers.unresolved.dependencies.ignore", "true");
```

**[SOURCE]** `vscode-wpilib/resources/gradle/shared/settings.gradle`.

Note the *offline-first* ordering: the WPILib-installer home directory maven repo comes **before**
the plugin portal. `.wpilib/wpilib_preferences.json` carries `projectYear: "2027_alpha5"` and the team
number. `SystemcoreTesting/README.md` explains the mismatch: "the latest WPILib is 2027.0.0-alpha-6,
which installs to a `2027_alpha5` folder … this is intended behavior" **[SOURCE]**.

Meanwhile the vendordep JSONs shipping in *our* checkout already say `2027_alpha7`:
`~/dev/allwpilib/commandsv3/CommandsV3.json` has `"wpilibYear": "2027_alpha7"` **[SOURCE]**.
The year string is validated hard — native-utils throws
`InvalidVendorDepYearException` with the message *"Vendor Dependency %s has invalid year %s. Expected
to be %s. … Attempting to modify an existing dependency will break at runtime, and will result in
loss of support from the WPILib team."* **[SOURCE]** (strings extracted from
`native-utils-2027.13.1.jar`). Plan for a lockstep bump of `wpilibYear` + folder name + template on
each alpha.

### 3.4 Gradle wrapper version

`vscode-wpilib/resources/gradle/shared/gradle/wrapper/gradle-wrapper.properties` **[SOURCE]**:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
distributionPath=permwrapper/dists
zipStorePath=permwrapper/dists
```

`allwpilib` itself pins the same 9.4.1 **[SOURCE]** `gradle/wrapper/gradle-wrapper.properties`.
Note `permwrapper` (not `wrapper`) for the dist path — that is WPILib's convention so the installer
can pre-seed the distribution; keep it if we want offline-at-competition to work.

### 3.5 `vendordeps/`

Handled by native-utils, not GradleRIO. From `native-utils-2027.13.1.jar` **[SOURCE]** (extracted
strings from `org.wpilib.nativeutils.vendordeps.*`):

- Default folder is **`vendordeps`** relative to the project root ("The default path (from the project root) is vendordeps"). Overridable via property `nativeutils.vendordep.folder.path`, which also emits a warning if changed.
- Install task: **`gradlew vendordep --url=<insert_url_here>`**. It accepts either a URL or `FIRSTLOCAL/Filename.json` to pull from the installed WPILib home folder.
- There is a task to "Update the existing vendordeps".
- Error types are first-class: `ConflictingVendorDependencyException`, `DuplicateVendorDependencyException`, `InvalidVendorDepYearException`, `MissingRequiredVendorDependencyException`, `MissingVendorDependencyPlatformException`.
- Known platform strings: `linuxsystemcore`, `linuxarm64`, `linuxx86-64`, `windowsx86-64`, `windowsarm64`, `osxuniversal` (plus legacy `linuxx64`/`windowsx64`/`windowsx86`).

Commands v2 and v3 are **vendordeps, not core deps** — `wpi.java.deps.wpilib()` does not include them
(see the artifact list in §3.6). The JSONs live in the WPILib repo:

`~/dev/allwpilib/commandsv3/CommandsV3.json` **[SOURCE]**, verbatim:

```json
{
  "fileName": "CommandsV3.json",
  "name": "Commands v3",
  "version": "1.0.0",
  "uuid": "4decdc05-a056-46cf-9561-39449bbb0130",
  "wpilibYear": "2027_alpha7",
  "mavenUrls": [],
  "jsonUrl": "",
  "conflictsWith": [
    { "uuid": "111e20f7-815e-48f8-9dd6-e675ce75b266",
      "errorMessage": "Users can not have both Commands v2 and Commands v3 vendordeps in their robot program.",
      "offlineFileName": "CommandsV2.json" }
  ],
  "javaDependencies": [
    { "groupId": "org.wpilib", "artifactId": "commandsv3-java", "version": "wpilib" }
  ],
  "jniDependencies": [],
  "cppDependencies": []
}
```

Points worth noting: `version: "wpilib"` is a sentinel meaning "track the WPILib version"; `mavenUrls`
and `jsonUrl` are empty because it ships inside WPILib's own maven; and `conflictsWith` is enforced.
Commands v3 is **Java-only** — `cppDependencies` is empty, while `CommandsV2.json` has a full C++
artifact list including `linuxsystemcore`.

### 3.6 Real maven coordinates

Namespace is `org.wpilib.*`. **[SOURCE]** `~/dev/allwpilib/MavenArtifacts.md`:

> All artifacts are based at `org.wpilib.artifactname` in the repository.
> `org.wpilib.wpilibj:wpilibj-java:version`
> `org.wpilib.wpimath:wpimath-cpp:version:classifier@zip`

Java jars get a `-java` suffix on the artifactId; native zips get `-cpp`; classifier is `{os}{arch}`
with `static` and/or `debug` appended. Repos are
`https://frcmaven.wpi.edu/artifactory/release/` and `.../development/`.

The exact dependency set GradleRIO injects for `wpi.java.deps.wpilib()` — **[SOURCE]**
`GradleRIO/src/main/java/org/wpilib/gradlerio/wpi/java/WPIJavaDepsExtension.java`:

```
org.wpilib.wpilibj:wpilibj-java          org.wpilib.wpinet:wpinet-java
org.wpilib.wpimath:wpimath-java          org.wpilib.wpiutil:wpiutil-java
org.wpilib.ntcore:ntcore-java            org.wpilib.apriltag:apriltag-java
org.wpilib.cscore:cscore-java            org.wpilib.wpiunits:wpiunits-java
org.wpilib.cameraserver:cameraserver-java  org.wpilib.epilogue:epilogue-runtime-java
org.wpilib.hal:hal-java                  org.wpilib.datalog:datalog-java
org.wpilib:annotations-java              org.wpilib.drivers:drivers-java
org.wpilib.thirdparty.opencv:opencv-java   io.avaje:avaje-jsonb
org.ejml:ejml-simple                     us.hebi.quickbuf:quickbuf-runtime
```

`wpi.java.deps.wpilibAnnotations()` (annotation processor path):
`io.avaje:avaje-jsonb-generator`, `org.wpilib.epilogue:epilogue-processor-java`,
`org.wpilib.epilogue:epilogue-runtime-java`, `org.wpilib:javac-plugin-java`, `org.wpilib:annotations-java`.

JNI zips (`wpilibJniRelease(platform)`): `hal-cpp`, `wpimath-cpp`, `ntcore-cpp`, `cscore-cpp`,
`opencv-cpp`, `wpinet-cpp`, `wpiutil-cpp`, `apriltag-cpp`, `datalog-cpp`, plus
`org.wpilib.mrclib:mrclib-cpp` **only for non-systemcore platforms** (`mrclib` is already on the device).

New in 2027 and worth naming explicitly: `datalog`, `drivers`, `annotations`, `javac-plugin` are now
separate artifacts. `telemetry` and `tunables` are separate Gradle subprojects in allwpilib
(`~/dev/allwpilib/telemetry`, `~/dev/allwpilib/tunables`) but do **not** appear in
`WPIJavaDepsExtension` — see §11 open questions.

The GradleRIO default WPILib version — **[SOURCE]** `WPIVersionsExtension.java`:

```java
private static final String wpilibVersion = "2027.0.0-alpha-6-336-g92efd74d9";
private static final String opencvVersion = "2027-4.13.0-3";
private static final String mrcLibVersion = "2027.1.0-alpha-1-112-g3f8f56e";
```

i.e. GradleRIO `main` already defaults to a *development* build 336 commits past alpha-6, not the
alpha-6 tag. Our local checkout is `-366-`, 30 commits newer than that. **[INFERRED]** GradleRIO on
`main` and allwpilib on `main` are meant to be used together at similar commits.

---

## 4. How deploy actually works, mechanically

### 4.1 Transport and target

`SystemCore` extends `WPIRemoteTarget` and creates SSH deploy locations. **[SOURCE]**
`GradleRIO/src/main/java/org/wpilib/gradlerio/deploy/systemcore/SystemCore.java`:

```java
setDirectory("/home/systemcore");
setMaxChannels(4);
setTimeout(7);
getTargetPlatform().set(NativePlatforms.systemcore);   // "linuxsystemcore"

private String username = "systemcore";
private String password = "systemcore";

public void setTeam(int team) {
    setAddresses(
        "10." + (team / 100) + "." + (team % 100) + ".2",       // 10.TE.AM.2
        OperatingSystem.current().isWindows() ? "172.26.0.1" : "172.27.0.1",  // USB
        "172.30.0.1");                                          // built-in WiFi AP
}
public void useDefaultSystemcoreHostName() { this.addAddress("robot.local"); }
public void useCustomSystemcoreHostName(String hostName) { this.addAddress(hostName); }
```

Each address becomes an `SshDeployLocation` (user/password baked in, IPv6 off). Two extra
locations are also registered: `FirstDsDeployLocation` and `NiDsDeployLocation` — these ask the
running Driver Station (the 2027 "FIRST" DS or the legacy NI DS) for the robot's current IP, the
2027 equivalent of the old "ask the DS where the RIO is" trick. **[SOURCE]**

So: **deploy is plain SSH/SFTP over TCP 22, plus shell commands.** No FTP, no `nc`, no custom
protocol. This is a real simplification vs. the roboRIO. Confirmed on the device: `sshd` is the only
thing on port 22 and password auth works. **[VERIFIED-DEVICE]**

Two guardrails exist: `FMSConnectedException` ("You can't deploy code while connected to the FMS!
Ask the FTA to allow you to tether your robot.") and `RobotNotConnectedToDsException`. **[SOURCE]**

### 4.2 Deploy stages

`DeployStage` is an ordered enum; artifacts register into a stage. Observed stages and their commands
**[SOURCE]** (`RobotProgramKillArtifact.java`, `RobotProgramStartArtifact.java`):

1. **ProgramKill** — `sudo systemctl stop robot 2> /dev/null`
2. **FileDeploy** — SFTP the jars, the `.so`s, `robotCommand`, `robotCommand.args`, and `src/main/deploy/**`
3. **ProgramStart** — `sudo systemctl enable robot 2> /dev/null`, then `sudo systemctl start robot 2> /dev/null`, then `sudo sync`

Passwordless sudo for `systemcore` (verified on-device) is what makes stages 1 and 3 work.

### 4.3 What lands where

**[SOURCE]** `WPILibJavaArtifact.java` and `WPILibDeployPlugin.java`:

```java
// WPILibJavaArtifact
public static final String CLASSPATH_PATH = "/home/systemcore/wpilib/classpath";
// WPILibDeployPlugin
public static final String LIB_DEPLOY_DIR  = "/home/systemcore/wpilib/third-party/lib";
```

| Path | Contents |
|---|---|
| `/home/systemcore/wpilib/classpath/` | the project jar + every runtime-classpath jar, individually. `deleteOldFiles = true` |
| `/home/systemcore/wpilib/third-party/lib/` | JNI/native `.so` files, unzipped from the `systemcoreRelease`/`systemcoreDebug` configurations (`**/*.so*`, excluding `*.so.debug`) |
| `/home/systemcore/robotCommand` | the launch script systemd runs |
| `/home/systemcore/robotCommand.args` | JVM `@argfile` (newer GradleRIO only, see below) |
| `/home/systemcore/deploy/` | `src/main/deploy/**` static files |

**Important:** the Java deploy is a **classpath deploy of many jars**, not a single fat jar, in
current GradleRIO `main`. The vscode-wpilib template still builds a `shadowJar` and hands it to
`deployArtifact.jarTask`; even then, the *runtime classpath configuration* is what gets enumerated
into the args file. See §11 for the open question here.

On this device right now, none of those paths exist — nothing has ever been deployed
(`/home/systemcore` contains only `.bash_history` and a `blocks/` dir). **[VERIFIED-DEVICE]**

### 4.4 `robotCommand` — the launch line

Two generations exist, and they differ. Both are real.

**Released alpha-6** (extracted from `GradleRIO-2027.0.0-alpha-6.jar` **[SOURCE]**): a single
`robotCommand` file written by shelling out, containing the whole `java` command. String constants in
the jar include `' > /home/systemcore/robotCommand`,
`chmod +x /home/systemcore/robotCommand; chown systemcore /home/systemcore/robotCommand`,
`-Djava.library.path=/home/systemcore/wpilib/third-party/lib`, and the GC flags.

**GradleRIO `main`** (post-alpha-6) splits it: `robotCommand` becomes a one-liner that points at an
`@argfile`. **[SOURCE]** `RobotCommandArtifact.java` / `WPILibJavaArtifact.java`:

```java
public static final String ROBOT_COMMAND_FILE = "robotCommand";
public static final String ARG_FILE = "robotCommand.args";
// robotCommand contents:
//   /usr/bin/java @/home/systemcore/robotCommand.args
```

and the args file is assembled as:

```java
private GarbageCollectorType gcType = GarbageCollectorType.ZGC;   // <-- the default
private String javaCommand = "/usr/bin/java";

jvmArgs.add("-Djava.library.path=" + WPILibDeployPlugin.LIB_DEPLOY_DIR);
jvmArgs.add("--add-opens"); jvmArgs.add("java.base/jdk.internal.vm=ALL-UNNAMED");
jvmArgs.add("--add-opens"); jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
jvmArgs.add("--enable-native-access=ALL-UNNAMED");
// then: gcType args, jvmArgs, -cp "<every deployed jar, colon-joined>", [debug flags], mainClass, args
```

`--add-opens java.base/jdk.internal.vm=ALL-UNNAMED` is there because **Commands v3 uses continuations
reflectively** — allwpilib's own `developerRobot/build.gradle` says exactly that:
`// Commands v3 needs reflective access to the continuation classes` **[SOURCE]**.

Debug mode appends
`-XX:+UsePerfData -agentlib:jdwp=transport=dt_socket,address=0.0.0.0:<port>,server=y,suspend=y`.

### 4.5 The garbage collector

**[SOURCE]** `GradleRIO/src/main/java/org/wpilib/gradlerio/deploy/systemcore/GarbageCollectorType.java`, verbatim:

```java
public enum GarbageCollectorType {
    G1("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=1", "-XX:GCTimeRatio=1"),
    G1_LongPause("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=5", "-XX:GCTimeRatio=1"),
    G1_Base("-XX:+UseG1GC"),
    ZGC("-XX:+UseZGC"),
    Serial("-XX:+UseSerialGC"),
    Parallel("-XX:+UseParallelGC"),
    Serial_PauseGoal("-XX:+UseSerialGC", "-XX:MaxGCPauseMillis=5"),
    Parallel_PauseGoal("-XX:+UseParallelGC", "-XX:MaxGCPauseMillis=5"),
    Other();
}
```

**ZGC is the default**, and I confirmed it holds in the *released* alpha-6 plugin too, not just on
`main` — disassembling `WPILibJavaArtifact.<init>` from `GradleRIO-2027.0.0-alpha-6.jar` shows
**[VERIFIED-LOCAL]**:

```
29: getstatic  #21  // Field .../GarbageCollectorType.ZGC:L.../GarbageCollectorType;
32: putfield   #27  // Field gcType:L.../GarbageCollectorType;
```

To override in `build.gradle`:

```groovy
deploy.targets.systemcore.artifacts.wpilibJava.gcType =
    org.wpilib.gradlerio.deploy.systemcore.GarbageCollectorType.G1
```

The presence of eight tuned options — including two G1 variants with `MaxGCPauseMillis=1` — is a
strong hint that WPILib is still actively benchmarking this and that ZGC's status as default is
not settled. **[INFERRED]**

Counter-signal worth recording: allwpilib's own `developerRobot` deploy writes
`-XX:+UseG1GC` by hand **[SOURCE]** `~/dev/allwpilib/developerRobot/build.gradle`:

```groovy
ctx.execute("echo '/usr/bin/java -XX:+UseG1GC -Djava.library.path=/home/systemcore/wpilib/third-party/lib ...")
```

So the WPILib devs' own scratch robot runs G1, while the plugin they ship to teams defaults to ZGC.

### 4.6 The systemd unit — supervision and restart

**[VERIFIED-DEVICE]**, `systemctl cat robot`:

```ini
# /etc/systemd/system/robot.service
[Unit]
Description=robot
After=network.target
ConditionPathExists=/home/systemcore/robotCommand

[Service]
Type=simple
User=systemcore
LimitRTPRIO=50
WorkingDirectory=/home/systemcore
ExecStartPre=/bin/bash -c '\
 timeout=15; \
 while [[ $timeout > 0 ]]; do \
   good=true; \
   for can in can_s0 can_s1 can_s2 can_s3 can_s4; do \
     if ! ip link show $can up 2>/dev/null | grep -q "state UP"; then \
       good=false; echo "$can down"; fi; \
   done; \
   if [[ $good = true ]]; then exit 0; fi; \
   sleep 1; timeout=$((timeout-1)); \
 done; \
 echo "could not find can bus interfaces"'
ExecStart=/bin/bash /home/systemcore/robotCommand
Restart=always
RestartSec=3
TimeoutStopSec=1

[Install]
WantedBy=multi-user.target

# /etc/systemd/system/robot.service.d/override.conf
[Service]
ExecStartPre=
ExecStartPre=/bin/bash -c 'echo "Waiting for CAN adapters (30s)..."; for i in $(seq 1 30); do ls /sys/class/net/can_s* >/dev/null 2>&1 && exit 0; sleep 1; done; echo "No CAN adapters found, starting robot anyway"; exit 0'
```

Read carefully, this says a lot:

- **`LimitRTPRIO=50`** — the robot process may use RT priorities up to 50. That is exactly the CAN HAL thread's priority (§6). A robot program cannot ask for 51+.
- **`Restart=always`, `RestartSec=3`** — a crashed or exiting robot program restarts after 3 s, forever. There is no restart-rate limiter configured, so a crash loop will hammer at 1/3 Hz.
- **`TimeoutStopSec=1`** — one second to exit on `systemctl stop` before SIGKILL.
- **`ConditionPathExists=/home/systemcore/robotCommand`** — the unit no-ops cleanly on a device that has never been deployed to. That is exactly the state of our device now (`Active: inactive (dead)`, `Condition: start condition unmet`). **[VERIFIED-DEVICE]**
- **`ExecStartPre` waits for CAN.** The base unit waits up to 15 s for `can_s0..can_s4` to be `state UP`. **Someone on this device has already installed a drop-in override** that replaces it with a 30 s wait that gives up gracefully and starts anyway — almost certainly because this unit is a bare Pi 5 with no CAN hardware. Worth knowing before we blame startup delays on our code. **[VERIFIED-DEVICE]**
- `WorkingDirectory=/home/systemcore` — so relative paths in robot code (e.g. `Filesystem.getDeployDirectory()` fallbacks) resolve there.

### 4.7 Logs

There is no `/var/log/robot`, no robot log file. `/var/log` contains only `hailo`, `journal`,
`private`, `sa`. **[VERIFIED-DEVICE]**

**Robot console output goes to the systemd journal.** `journalctl -u robot` is the console. On our
device it currently shows only the skipped-condition lines:

```
Aug 24 10:48:01 robot systemd[1]: robot was skipped because of an unmet condition check
                                  (ConditionPathExists=/home/systemcore/robotCommand).
```

Practical commands: `journalctl -u robot -f` (live tail), `journalctl -u robot -b` (this boot),
`journalctl -u robot -n 200 --no-pager`. Journald is running with persistent storage
(`/var/log/journal` exists), so logs survive reboot. **[VERIFIED-DEVICE]**

GradleRIO's `WPILibDeployPlugin` has `// TODO: project.getPluginManager().apply(RioLogPlugin.class);`
commented out **[SOURCE]** — **there is currently no `riolog` equivalent wired into GradleRIO.**
`journalctl` (or the ttyd web terminal on port 4901) is the console story today.

WPILib's own `DataLog`/`DataLogManager` output location is a separate question — see §11.

### 4.8 Deploy from allwpilib's own `developerRobot`

For working directly against WPILib sources without publishing, allwpilib ships a `developerRobot`
subproject. **[SOURCE]** `~/dev/allwpilib/developerRobot/README.md`:

```bash
./gradlew developerRobot:build      # build everything
./gradlew developerRobot:run        # desktop sim, Java
./gradlew developerRobot:runCpp     # desktop sim, C++
./gradlew developerRobot:deployJava # deploy Java + required native deps
./gradlew developerRobot:deployShared
./gradlew developerRobot:deployStatic
```

> "Those commands won't start the robot executable, so you have to manually ssh in and start it."
> ```
> ssh systemcore@robot.local sudo systemctl stop robot
> ssh systemcore@robot.local sudo ~/robotCommand
> ```
> "Console log prints will appear in the terminal."
> "Deploying any of these to a Systemcore will disable the current startup project until it is redeployed."

Note this deploy path uses `/home/systemcore/wpilib/allwpilibclasspath` (not `.../classpath`) and by
default only targets USB (`address` block in `developerRobot/build.gradle` must be edited for another
IP). It also does `setcap cap_sys_nice+eip` on the C++ executables so they can raise RT priority
without the systemd `LimitRTPRIO`.

**[INFERRED]** For our project this path is a debugging aid, not the primary flow — we want a normal
robot project consuming published artifacts (§5) so our repo layout matches every other team's.

---

## 5. Building against the LOCAL `~/dev/allwpilib` build

This is the part that matters most while we track alpha, and I verified it end to end.

### 5.1 The mechanism

`allwpilib` applies `org.wpilib.WPILibRepositoriesPlugin` and `org.wpilib.WPILibVersioningPlugin`
(2027.0.0 / 2027.0.1) **[SOURCE]** `gradle/libs.versions.toml`. The repositories plugin defines
`Local-Development` and `Local-Development-Publishing` repos rooted at
`System.getProperty("user.home") + "/releases/maven/" + <suffix>` — I confirmed this by disassembling
`WPILibRepositoriesPluginExtension.class`, whose string-concat constant is literally
`\u0001/releases/maven/`. **[VERIFIED-LOCAL]**

The versioning plugin injects the marker `424242` into the version for non-release, non-buildServer
builds (constant `424242` is in `GitVersionProvider.class`). **[VERIFIED-LOCAL]**

### 5.2 Verified: what `./gradlew publish` actually produces

I ran a real publish. **[VERIFIED-LOCAL]**

```
$ cd ~/dev/allwpilib && ./gradlew --offline :wpilibj:publish
> Task :wpilibj:publishJavaPublicationToLocal-Development-PublishingRepository
> Task :wpilibj:publish
BUILD SUCCESSFUL in 6s
```

Resulting files:

```
/home/drew/releases/maven/development/org/wpilib/wpilibj/wpilibj-java/
  wpilibj-java-2027.424242.0.0-alpha-6-20260824123203-366-gcafb0cc79.jar
  wpilibj-java-2027.424242.0.0-alpha-6-20260824123203-366-gcafb0cc79.pom
  wpilibj-java-...-sources.jar
  wpilibj-java-...-javadoc.jar
  (+ .md5/.sha1/.sha256/.sha512 for each)
```

And I confirmed the configured repositories directly by injecting a probe init script:

```
WPILIB_VERSION=2027.424242.0.0-alpha-6-20260824123028-366-gcafb0cc79
REPO Local-Development     -> file:/home/drew/releases/maven/development
REPO Remote-Development    -> https://frcmaven.wpi.edu/artifactory/development
PUBLISH_REPO Local-Development-Publishing -> file:/home/drew/releases/maven/development
```

**Note the timestamp is inside the version string** — `20260824123028` on the first probe,
`20260824123203` on the publish a couple of minutes later. **Every local build mints a new version.**
That is precisely why the documented consumption uses a dynamic version.

`README.md` §Publishing **[SOURCE]**:

> "simply run the `publish` task. This task will publish all available packages to
> ~/releases/maven/development. If you need to publish the project to a different repo, you can
> specify it with `-Prepo=repo_name`. Valid options are: development (default), beta
> (~/releases/maven/beta), stable (~/releases/maven/stable), release (~/releases/maven/release)."

### 5.3 The exact robot-project incantation

**[SOURCE]** `~/dev/allwpilib/DevelopmentBuilds.md`, "Local Build" section, verbatim:

```groovy
plugins {
  id "java"
  id "org.wpilib.GradleRIO" version "2027.0.0-alpha-5"
}

wpi.maven.useLocal = false
wpi.maven.useWpilibMavenLocalDevelopment = true
wpi.versions.wpilibVersion = 'YEAR.424242.+'
```

For us, with the plugin at the newest published version:

```groovy
plugins {
  id "java"
  id "org.wpilib.GradleRIO" version "2027.0.0-alpha-6"
}

wpi.maven.useLocal = false
wpi.maven.useWpilibMavenLocalDevelopment = true
wpi.versions.wpilibVersion = '2027.424242.+'
```

`WPIMavenExtension` confirms the flag names and their defaults **[SOURCE]**:
`useDevelopment = true`, `useLocal = true`, `useWpilibMavenLocalDevelopment = false`,
`useWpilibMavenLocalRelease = false`, `useMavenCentral = true`, `useWpilibMavenVendorCache = true`.

(The alternative — track WPILib CI rather than our own build — is
`wpi.maven.useDevelopment = true` + `wpi.versions.wpilibVersion = '2027.+'`, which pulls per-commit
builds from `frcmaven.wpi.edu/artifactory/development`.)

### 5.4 Practical cautions for the alpha workflow

1. **Full workflow is:** `cd ~/dev/allwpilib && git pull && ./gradlew build && ./gradlew publish`, then rebuild the robot project. `./gradlew build` builds *everything* including all installed cross-compilers; README documents `buildDesktopJava` / `testDesktopJava` shortcuts but there is no documented "publish only Java" shortcut. `:wpilibj:publish` style targeted publishes work (verified) but you must remember every artifact your project depends on.
2. **`2027.424242.+` is a dynamic version.** Gradle caches dynamic versions for 24 h by default. Since every local publish mints a new timestamped version, add to the robot project:
   ```groovy
   configurations.all { resolutionStrategy.cacheDynamicVersionsFor 0, 'seconds' }
   ```
   or run with `--refresh-dependencies` after each publish. **[INFERRED]** — allwpilib's own root `build.gradle` does exactly this for its `mrclibVersion` configuration, which is corroborating.
3. **The build requires git metadata.** README: *"The build process uses versioning information from git. Downloading the source is not sufficient to run the build."* **[SOURCE]**
4. **Vendordeps must match.** REVLib/Phoenix6 vendordeps are built against a released alpha, not our local build. `SystemcoreTesting/README.md` gives the compatibility matrix **[SOURCE]**: for WPILib alpha-5/6 — CTRE Phoenix 6 `v26.50.0-alpha-1`, REVLib `v2027.0.0-alpha-2`, ReduxLib `v2027.0.0-alpha-6`, PathPlannerLib `v2027.0.0-alpha-3`, AdvantageKit `v27.0.0-alpha-4`, ThriftyLib `v2027.0.0-alpha-1`, ChoreoLib ❌ (no compatible release). DevelopmentBuilds.md warns: *"Development builds are also likely to be incompatible with vendor libraries."*
5. **Build requirements** (README **[SOURCE]**): JDK 25 (full JDK), GCC 14 on Linux, `./gradlew installSystemCoreToolchain` for SystemCore native builds, and `sudo apt install libx11-dev libgl-dev libxcursor-dev libxrandr-dev libxinerama-dev libxi-dev` for glfw-dependent things.

### 5.5 Toolchain: does Linux/WSL2 work?

Yes, and it is already installed here. **[VERIFIED-LOCAL]**

```
~/.gradle/toolchains/first/2027/systemcore/bin/aarch64-systemcore2027-linux-gnu-gcc
~/.gradle/toolchains/first/2027/arm64/bin/aarch64-trixie-linux-gnu-gcc
```

Installed by `./gradlew installSystemCoreToolchain` (and `installArm64Toolchain`), from
`org.wpilib:ToolchainPlugin:2027.13.1`. The checkout has built real
`linuxsystemcore` artifacts — e.g. `build/allOutputs/_GROUP_org_wpilib_wpilibc_ID_wpilibc-cpp_CLS-linuxsystemcorestaticdebug.zip`.
`NativePlatforms.systemcore` = `"linuxsystemcore"`. **[VERIFIED-LOCAL]**

**For a Java-only robot project none of this is needed** — the `systemcoreRelease` configuration pulls
prebuilt JNI `.so` zips from maven. The cross toolchain only matters if we build WPILib itself or
write C++. **[INFERRED]**

---

## 6. Real-time thread priorities

`~/dev/allwpilib/design-docs/real-time-thread-priorities.md` is **the entire document** — it is a
two-row table and nothing else. Verbatim **[SOURCE]**:

> # Real-time thread priorities
>
> | Name | Location | Priority |
> |---|---|---|
> | CAN HAL thread | hal/src/main/native/systemcore/CAN.cpp | 50 |
> | Notifier HAL thread | hal/src/main/native/systemcore/Notifier.cpp | 40 |

Confirmed in source **[SOURCE]**:

```cpp
// hal/src/main/native/systemcore/CAN.cpp:200
if (HAL_SetCurrentThreadPriority(50) != 0) {
  wpi::util::print("Failed to set CAN thread priority\n");
}
// hal/src/main/native/systemcore/Notifier.cpp:78
if (HAL_SetCurrentThreadPriority(40) != 0) {
  wpi::util::print("Failed to set HAL Notifier thread priority\n");
}
```

The mechanism, from `hal/src/main/native/systemcore/Threads.cpp` **[SOURCE]**:

```cpp
HAL_Status HAL_SetThreadPriority(NativeThreadHandle handle, int32_t priority) {
  if (priority < 0 || priority > 99) return HAL_THREAD_PRIORITY_RANGE_ERROR;
  int scheduler = priority > 0 ? SCHED_RR : SCHED_OTHER;
  ...
  pthread_setschedparam(..., scheduler, &sch);
}
```

So: **`SCHED_RR`** (round-robin), not `SCHED_FIFO`. Priority 0 means `SCHED_OTHER` (normal).

The user-facing Java API is `org.wpilib.system.Threads`
(`wpilibj/src/main/java/org/wpilib/system/Threads.java`) **[SOURCE]**:

```java
public static int getCurrentThreadPriority();

/**
 * ...Priorities range from 0 to 99 where 0 is non-real-time, 1-99 are real-time...
 * @deprecated Incorrect usage of real-time priority can lead to system lockups. Only use this
 *     function if you are trained in real-time software development.
 */
@Deprecated
public static boolean setCurrentThreadPriority(int priority);
```

Note the package move: it is `org.wpilib.system.Threads`, not `edu.wpi.first.wpilibj.Threads`.
And it is **`@Deprecated` on purpose** — WPILib is discouraging teams from touching this.

### What this means for how we structure periodic work

**[INFERRED]**, but grounded:

1. **Your robot code is not real-time.** `TimedRobot`/`OpModeRobot` run their loop on the JVM main thread at `SCHED_OTHER`. Only two WPILib threads are RT: CAN at 50 and the HAL Notifier at 40. The Notifier thread merely *signals* the alarm the main thread is blocked on; the periodic callback itself executes at normal priority. Deterministic wake-up, non-deterministic execution.
2. **The ceiling is 50.** `LimitRTPRIO=50` in `robot.service` (verified on device) caps the process. Anything you set above 50 will fail; anything you set *at* 50 competes with the CAN thread, which is a bad idea.
3. **Raising priority only works under systemd.** `ulimit -r` is `0` in an interactive SSH session (verified). If you follow the `developerRobot` README and run `sudo ~/robotCommand` by hand, RT priority requests behave differently than in production. Do timing measurements under `systemctl start robot`, not from a shell.
4. **The kernel is PREEMPT_RT with `sched_rt_runtime_us=950000`.** Latency should be genuinely good, and a runaway RT thread is throttled at 95 %.
5. **No CPU isolation.** No `isolcpus`, and ~20 `limelight_*` services plus 4 vision servers plus Docker share the same 4 cores. Our loop is competing with the vision stack for CPU. `limelight_irqconf.service` exists, suggesting IRQ affinity *is* being managed, but nothing partitions cores for user code.
6. **Practical guidance stands unchanged from the roboRIO era, only more so:** keep `robotPeriodic` short and allocation-light; use `addPeriodic(callback, period, offset)` to stagger sub-loops rather than spawning threads; if you truly need a high-rate control loop, prefer a `Notifier` (which rides the priority-40 HAL thread's alarm) over a hand-rolled `Thread` + `Threads.setCurrentThreadPriority`. Do not raise priority without measuring first.
7. **The design doc is a stub.** It documents *what WPILib does*, gives teams **no** guidance, and says nothing about ZGC, allocation, core pinning, or user threads. Do not over-read it.

`TimedRobot.addPeriodic` **[SOURCE]** `wpilibj/.../framework/TimedRobot.java`:

```java
public final void addPeriodic(Runnable callback, double period);
public final void addPeriodic(Runnable callback, double period, double offset);
```

backed by a `PeriodicPriorityQueue` and a single `NotifierJNI` alarm. `OpModeRobot` has the same
`addPeriodic` and the same structure.

---

## 7. Profiling and performance measurement

### 7.1 Inside WPILib

- **`org.wpilib.system.Watchdog`** — `IterativeRobotBase` already instruments the loop for you **[SOURCE]** `wpilibj/.../framework/IterativeRobotBase.java`. It adds named epochs around `disabledInit()`, `autonomousInit()`, `teleopInit()`, `utilityInit()`, the corresponding `*Periodic()` calls, `robotPeriodic()`, `TunableRegistry.update()`, and `simulationPeriodic()`. On overrun it calls `m_watchdog.printEpochs()` and reports a warning: `"Loop time of " + m_period + "s overrun"`. There is a public `printWatchdogEpochs()` you can call unconditionally. `OpModeRobot` does the same but routes overruns to a persistent **Alert** (`"opmode-loop-overrun"`) rather than only printing. **[SOURCE]**
- **`org.wpilib.system.Tracer`** — manual epoch timing, unchanged in shape from 2025 **[SOURCE]**: `new Tracer()`, `resetTimer()`, `addEpoch(String)`, `printEpochs()`, `printEpochs(Consumer<String>)`, rate-limited to one print per second (`MIN_PRINT_PERIOD = 1000000` µs). Uses `RobotController.getMonotonicTime()`.
- **`org.wpilib.telemetry.Telemetry`** — new in 2027, a dedicated `telemetry` subproject with `TelemetryBackend` / `TelemetryRegistry` / `TelemetryTable` / `MultiTelemetryBackend` / `DiscardTelemetryBackend` / `MockTelemetryBackend`, and a wide `Telemetry.log(name, value)` overload set covering primitives, arrays, collections, `Struct` and `Protobuf` serializers. **[SOURCE]** `~/dev/allwpilib/telemetry/src/main/java/org/wpilib/telemetry/`. This is the explicit-logging API to build our telemetry decision on.
- **Epilogue** — `@Logged` annotation processing, now `org.wpilib.epilogue:epilogue-runtime-java` + `epilogue-processor-java`, with `EpilogueTelemetry` bridging into the new telemetry system. `Epilogue.update(this)` in `robotPeriodic()` is the documented pattern (see the Commands v3 skeleton template).
- **`org.wpilib.tunable`** — a new `tunables` subproject (`Tunable`, `TunableDouble`, `TunableRegistry`, `TunableBackend`, …), and `TunableRegistry.update()` is already an instrumented epoch in the robot loop. **[SOURCE]**
- **JMH** is a dependency of allwpilib itself (`org.openjdk.jmh:jmh-core:1.37` in `libs.versions.toml`, plus a `benchmark` subproject) — microbenchmarking harness available if we want it for pure-Java math. **[SOURCE]**

### 7.2 On-device JVM tooling

This is the good news, and it is a genuine upgrade over the roboRIO. **[VERIFIED-DEVICE]** the device
carries a **full JDK 25**, so:

- **JDK Flight Recorder** is present (`/bin/jfr`, and JFR is built into the JVM). This is the right tool for both loop timing and allocation profiling under ZGC. Options:
  - Attach live: `jcmd <pid> JFR.start name=robot settings=profile duration=60s filename=/tmp/robot.jfr`, then `jcmd <pid> JFR.dump`.
  - Or bake it into the launch by appending to `jvmArgs`:
    ```groovy
    deploy.targets.systemcore.artifacts.wpilibJava.jvmArgs.add(
      "-XX:StartFlightRecording=settings=profile,filename=/home/systemcore/robot.jfr,maxsize=100M")
    ```
    (`WPILibJavaArtifact.getJvmArgs()` returns a mutable `List<String>` — **[SOURCE]**.)
  - Pull the file back with `scp` and open in JDK Mission Control / `jfr print --events jdk.ObjectAllocationSample`.
- `jcmd <pid> GC.heap_info`, `jcmd <pid> Thread.print`, `jcmd <pid> VM.native_memory` (needs `-XX:NativeMemoryTracking`), `jstack`, `jmap -histo` all work.
- `jcmd -l` / `jps` will find the process because the robot runs as `systemcore` and we log in as `systemcore`. HotSpot perf data is on by default (GradleRIO adds `-XX:+UsePerfData` explicitly only in debug mode).
- **ZGC specifics:** JDK 25's `-XX:+UseZGC` is generational ZGC. Add `-Xlog:gc*:file=/home/systemcore/gc.log:time,uptime:filecount=3,filesize=10M` for a GC log; ZGC's stop-the-world pauses are sub-millisecond, so the failure mode to watch for is *allocation stall* (the mutator being throttled), not pause time. JFR's `jdk.ZAllocationStall` event is the one that matters. **[INFERRED]**
- **What is missing:** no `perf`, no async-profiler, no bpftrace. So no kernel-level or mixed-mode flame graphs out of the box. `strace` and `htop` are present. **[VERIFIED-DEVICE]**
- Also on-device: `top -H -p <pid>` to see per-thread CPU and confirm the RT threads are behaving; `chrt -p <tid>` to read a thread's actual scheduling policy and priority — that is the direct way to verify the priority-50/40 claims empirically once code is deployed.

### 7.3 A cheap standing measurement

**[INFERRED]** Since `Watchdog` already produces named epochs and the new `Telemetry` API is
structured, the low-effort high-value move is to log loop-timing epochs as telemetry every cycle
rather than only on overrun, so we get a continuous distribution in AdvantageScope/Elastic instead of
sporadic console spew. That needs a small wrapper — `printWatchdogEpochs()` only prints.

---

## 8. Desktop simulation round-trip

### 8.1 Verified working on this machine

I ran the sim. **[VERIFIED-LOCAL]**

```
$ cd ~/dev/allwpilib && ./gradlew --offline :developerRobot:run
> Task :developerRobot:run
HAL Extensions: Attempting to load: libhalsim_guid
Simulator GUI Initializing.
Simulator GUI Initialized!
HAL Extensions: Successfully loaded extension
********** Robot program starting **********
NT: Listening on port 5810
NT: mDNS announcing as service 'robot' on port 5810
********** Robot program startup complete **********
Default disabledPeriodic() method... Override me!
Default simulationPeriodic() method... Override me!
```

The GUI extension loaded, so **WSLg is sufficient** — `DISPLAY=:0`, `WAYLAND_DISPLAY=wayland-0`,
`/tmp/.X11-unix/X0` present, `/mnt/wslg` mounted. No X server setup needed. **[VERIFIED-LOCAL]**
Desktop platform resolves to `linuxx86-64`; the sim links `halsim_gui`, `glass`, `glassnt`, `wpigui`,
`imgui_suite` — all already built in the checkout.

### 8.2 How a robot project does it

From the template **[SOURCE]**:

```groovy
wpi.sim.addGui().defaultEnabled = true
wpi.sim.addDriverstation()
```

`SimulationExtension` creates the `simulationDebug` / `simulationRelease` configurations; the
`nativeDebug` / `nativeRelease` configurations bring in desktop JNI. GradleRIO extracts them to
`build/jni/{debug,release}` and sets `HALSIM_EXTENSIONS` before launching. **[SOURCE]**
`WPIJavaExtension.configureRunTask` also injects the same `--add-opens` / `--enable-native-access`
args as the on-device launch, plus `-XstartOnFirstThread` on macOS.

**Task names — and here the two GradleRIO generations diverge.** From the released
`GradleRIO-2027.0.0-alpha-6.jar` string table **[VERIFIED-LOCAL]** the registered tasks are:

```
simulateJava            simulateJavaDebug            simulateJavaRelease
simulateNative          simulateNativeDebug          simulateNativeRelease
simulateExternalJavaDebug   simulateExternalJavaRelease
simulateExternalNativeDebug simulateExternalNativeRelease
```

alongside `configureExecutableTasks`, which the vscode-wpilib template calls
(`wpi.java.configureExecutableTasks(shadowJar)`).

On GradleRIO `main`, `configureExecutableTasks` is **gone**. `WPIJavaExtension` now exposes
`configureApplication(JavaApplication)` and configures the standard Gradle **`run`** task, plus a
single `simulateExternalJava` task. GradleRIO's own `testing/java/build.gradle` matches:
`id "application"`, `application.mainClass = ROBOT_MAIN_CLASS`,
`deployArtifact.configureApplication(application)`, `wpi.java.configureApplication(application)`.
**[SOURCE]**

**[INFERRED]** So `./gradlew simulateJava` is the alpha-6 answer and `./gradlew run` is where it is
heading. Whichever plugin version we pin, check which of the two the plugin actually registers.

### 8.3 Round-trip speed

Measured on this machine **[VERIFIED-LOCAL]**:

- `./gradlew --offline :developerRobot:build -x test -x javadoc` on an already-built tree: **~11 s wall** for 461 tasks (12 executed, 449 up-to-date). That is the *worst case* because `developerRobot` compiles C++ for multiple platforms.
- `./gradlew --offline :wpilibj:publish` including javadoc + sources jars: **6 s**.

A plain Java robot project has no native compilation at all — just `compileJava`, an annotation
processing pass (Epilogue + javac-plugin), and jar. **[INFERRED]** expect **3–8 s** for an incremental
edit→sim cycle once the Gradle daemon is warm, dominated by JVM startup and annotation processing, not
compilation. The first run of the day (daemon cold, dependency resolution, native extraction) will be
much slower.

Two things to configure for speed **[INFERRED]**:
- Gradle **configuration cache** — the build output explicitly suggested it: *"Consider enabling configuration cache to speed up this build"*. Whether GradleRIO 2027 is configuration-cache-compatible is untested (§11).
- `org.gradle.jvmargs` — allwpilib itself sets `-Xmx4g` in `gradle.properties` **[SOURCE]**. Our robot project needs far less.

### 8.4 Sim caveats

- The sim binds NT on 5810 and mDNS-announces as `robot`. Our physical device also announces as `robot` on the LAN — **[INFERRED]** if the sim and the device are on the same network simultaneously, mDNS name collision is plausible. Worth a quick check before we rely on `robot.local` in scripts while simming.
- Commands v3's `--add-opens java.base/jdk.internal.vm=ALL-UNNAMED` must be present in sim too; the template's `wpi.java.configureApplication`/`configureRunTask` adds it automatically, but a hand-rolled `JavaExec` or an IDE run configuration will not.
- `wpi.sim.addDriverstation()` pulls in `halsim_ds_socket`; there are also websocket client/server extensions (`halsim_ws_client`, `halsim_ws_server`) and `halsim_xrp`. **[SOURCE]** `~/dev/allwpilib/settings.gradle`.

---

## 9. Deploying to *our* device specifically

**[INFERRED]** from the verified pieces above. Our unit is at `192.168.1.202` on the LAN, and
`robot.local` resolves to it from WSL2. So the plain template works:

```groovy
systemcore(getTargetTypeClass('SystemCore')) {
    team = project.wpilib.getTeamNumber()
    useDefaultSystemcoreHostName()          // adds robot.local
    // or, to be explicit about our LAN address:
    // useCustomSystemcoreHostName('192.168.1.202')
    debug = project.wpilib.getDebugOrDefault(false)
    ...
}
```

Sequence to expect on `./gradlew deploy`:

1. Target discovery: try each registered address over SSH, 7 s timeout, 4 channels.
2. `sudo systemctl stop robot`.
3. SFTP jars → `/home/systemcore/wpilib/classpath/` (old files deleted), `.so`s → `/home/systemcore/wpilib/third-party/lib/`, `src/main/deploy/**` → `/home/systemcore/deploy/`, write `robotCommand` (+ `robotCommand.args`), `chmod +x` and `chown systemcore`.
4. `sudo systemctl enable robot`, `sudo systemctl start robot`, `sudo sync`.
5. Watch it with `journalctl -u robot -f`.

Because there is no CAN hardware attached, the `ExecStartPre` override on this unit prints
`No CAN adapters found, starting robot anyway` and proceeds — the base unit's 15 s CAN wait has
already been neutralised by a drop-in. **[VERIFIED-DEVICE]**

**Not attempted** (this is a research ticket; a separate ticket owns setup/deploy): I did not deploy
anything, did not install packages, and did not change any configuration on the device.

---

## 10. Impacts on locked decisions

### 10.1 CONFIRMED — Java 25

Device runs Temurin 25.0.2 LTS; template sets `sourceCompatibility/targetCompatibility = VERSION_25`;
allwpilib README requires JDK 25. No conflict. **[VERIFIED-DEVICE] + [SOURCE]**

### 10.2 CONFIRMED — Gradle 9.4.1

Both allwpilib and the vscode-wpilib project template pin `gradle-9.4.1-bin.zip`. **[SOURCE]**

### 10.3 CONFIRMED — `org.wpilib` namespace

`MavenArtifacts.md`, `WPIJavaDepsExtension`, and every source path in allwpilib
(`org/wpilib/...`) agree. **[SOURCE]**

### 10.4 CONFIRMED and clarified — ZGC

The "ZGC default" decision is *more* true than assumed: it is not just the Gradle daemon's collector,
it is **GradleRIO's default collector for the deployed robot program**
(`private GarbageCollectorType gcType = GarbageCollectorType.ZGC;`, verified in both the released
alpha-6 jar and GradleRIO `main`). We inherit it by doing nothing.

Two caveats to log, not to act on yet:
- WPILib's own `developerRobot` hardcodes `-XX:+UseG1GC`, i.e. the devs' scratch robot disagrees with the default they ship. **[SOURCE]**
- The `GarbageCollectorType` enum offers eight tuned variants including `G1("-XX:+UseG1GC","-XX:MaxGCPauseMillis=1","-XX:GCTimeRatio=1")`. That level of optionality reads like an unresolved benchmark. **[INFERRED]** Plan to measure with JFR rather than assume.

### 10.5 ⚠️ CHALLENGES A LOCKED DECISION — "TimedRobot + Commands v3"

The locked stack says **TimedRobot + Commands v3**. WPILib's own Commands v3 templates do **not** use
`TimedRobot`. They use **`OpModeRobot`**.

**[SOURCE]** `~/dev/allwpilib/wpilibjExamples/src/main/java/org/wpilib/templates/commandv3skeleton/Robot.java`, verbatim and complete:

```java
package org.wpilib.templates.commandv3skeleton;

import org.wpilib.command3.Scheduler;
import org.wpilib.epilogue.Epilogue;
import org.wpilib.epilogue.Logged;
import org.wpilib.framework.OpModeRobot;

@Logged
public class Robot extends OpModeRobot {
  public Robot() {}

  @Override
  public void robotPeriodic() {
    Scheduler.getDefault().run();
    Epilogue.update(this);
  }
}
```

`templates/commandv3/Robot.java` likewise `extends OpModeRobot`. By contrast `templates/timed/Robot.java`
and `templates/commandv2/Robot.java` both `extends TimedRobot`. **[SOURCE]** So the template matrix is:

| Template | Base class |
|---|---|
| Timed Robot / Timed Skeleton | `TimedRobot` |
| Command v2 Robot / skeleton | `TimedRobot` |
| **Command v3 Robot / skeleton** | **`OpModeRobot`** |
| OpMode Robot | `OpModeRobot` |
| Timeslice Robot / skeleton | (Timeslice) |

Supporting evidence that this is deliberate and deep, not incidental:

- `design-docs/opmodes.md` is a full design document (≈40 KB) specifying operator-selectable opmodes, DS integration, `@Autonomous`/`@Teleop`/`@Utility` annotations, and an `OpModeRobot extends RobotBase` base class. It cross-references *"How opmodes work with the command-based framework is described in a separate design document (opmodes-commandbased.md)"*. **[SOURCE]**
- Commands v3 ships `org/wpilib/command3/OpModeFetcher.java`. **[SOURCE]**
- The HAL has `OpModeOption`, `RobotMode`; there is a `javacPlugin/.../OpModeAnnotationValidator.java` compile-time validator; `wpilibj` has `OpModeLifecycleTest` and `OpModeRobotTest`. **[SOURCE]**
- `SystemcoreTesting/README.md`: *"Some newer Driver Station features (for example, OpMode selection and Alerts) are only available in the 2027 Driver Station."* **[SOURCE]**

`OpModeRobot` is *not* a wrapper over `TimedRobot` — it extends `RobotBase` directly and reimplements
the periodic machinery with its own `PeriodicPriorityQueue`, `NotifierJNI` alarm, `Watchdog`, and
`addPeriodic(Runnable, double)`. **[SOURCE]** So they are siblings, and `TimedRobot` + Commands v3 is
*technically* possible but is off the paved path: you would give up DS opmode selection and diverge
from every WPILib example, template, and future doc for v3.

**Recommendation:** re-open the "TimedRobot" half of that decision as its own ticket before #19 fixes
the repo layout. There is already a `docs/research/opmodes.md` and `docs/research/commands-v3.md` in
this repo from sibling research — cross-check against those. This finding is **out of scope for #9**;
I am flagging it, not deciding it.

### 10.6 CONFIRMED — "fully sim capable"

Desktop sim runs today on this WSL2 box with no extra setup. No blocker found.

### 10.7 New, unplanned decisions this research surfaces

1. **Shadow/fat jar vs. classpath deploy.** The vscode-wpilib template applies `com.gradleup.shadow:9.3.0` and builds a fat jar; GradleRIO `main` moved to `application` + `configureApplication` with a multi-jar classpath deploy. We must pick one, and the choice is coupled to which GradleRIO version we pin.
2. **Which GradleRIO to pin.** `2027.0.0-alpha-6` (released, matches the vscode template, `simulateJava`, single `robotCommand`) vs. building GradleRIO from `main` (matches our alpha-7-era allwpilib, `run`, `robotCommand.args`). Tracking a locally-built WPILib argues for the latter; stability argues for the former.
3. **`wpilibYear` bookkeeping.** `2027_alpha5` in the current template vs. `2027_alpha7` in our checkout's vendordep JSONs. Every alpha bump touches `settings.gradle`, `.wpilib/wpilib_preferences.json`, and every `vendordeps/*.json`. Worth a script.
4. **Console strategy.** There is no `riolog` in 2027 (`RioLogPlugin` is commented out in `WPILibDeployPlugin`). We need to decide on `journalctl -u robot -f` over SSH, the ttyd web terminal on :4901, DS console, or our own tail script.
5. **GC choice is ours to make, deliberately.** ZGC is the default we inherit; G1 with `MaxGCPauseMillis=1` is a one-line override; WPILib's own dev robot uses G1. This is now a measurable decision, not a given.

---

## 11. Open questions / unknowns

1. **Where do WPILib `DataLog` files land on SystemCore?** I found no `/U`, `/V`, `/home/systemcore/logs`, or `/var/log/robot` on the device, and `DataLogManager`'s SystemCore default path is not documented in the design docs. `SystemcoreTesting/README.md` says USB storage mounts at `/U`, `/V`, etc. Needs a deployed program to answer.
2. **Is `org.wpilib.telemetry` (and `tunables`) shipped to robot projects?** They are separate Gradle subprojects in allwpilib and are `implementation project(':telemetry')` in `developerRobot`, but they do **not** appear in `WPIJavaDepsExtension.wpilib()`. Either GradleRIO `main` is behind, or they arrive transitively via `wpilibj`, or they need an explicit dependency. Resolve before building telemetry on them.
3. **Which GradleRIO generation should we target, and does `configureExecutableTasks` still exist in the version we pin?** The released alpha-6 jar has it; GradleRIO `main` does not. Determines whether the sim task is `simulateJava` or `run`, and whether we fat-jar.
4. **Is GradleRIO 2027 configuration-cache compatible?** Untested. Directly affects edit-run cycle time.
5. **Does the DS-based deploy location work with the 2027 Driver Station on Linux?** `FirstDsDeployLocation` and `NiDsDeployLocation` exist, but the 2027 DS (`wpilibsuite/FirstDriverStation-Public`) platform support on Linux/WSL2 is unverified. Relevant to whether we can enable the robot at all from this dev machine.
6. **Actual measured loop timing and GC behaviour on-device.** Nothing was deployed, so every performance claim here is structural, not empirical. First real task: deploy a trivial program, attach JFR, characterise loop jitter and allocation rate under ZGC vs. G1.
7. **Do the RT priorities actually take effect?** `LimitRTPRIO=50` should permit it, but the CAN thread's `HAL_SetCurrentThreadPriority(50)` prints a failure message if it cannot. Confirm empirically with `chrt -p <tid>` on a running robot — and note that with no CAN hardware on this unit the CAN thread's behaviour may differ.
8. **mDNS collision between the sim and the device.** Both announce as `robot` on port 5810. Untested.
9. **What the dashboard's gunicorn API on :9001 exposes.** I did not enumerate its endpoints (that would mean probing a service beyond read-only observation). Deploy/logs/config surface area unknown; the README only documents team-number setting and package installation via the UI.
10. **`vendordep` task behaviour against a locally-published WPILib.** `useWpilibMavenVendorCache` defaults to `true` and there is a `WPILibMavenVendorCache` repo concept; how that interacts with `useWpilibMavenLocalDevelopment` is untested.
11. **Is `2027.0.0-alpha-7` out yet, and does it renumber any of this?** Our checkout's vendordeps already say `2027_alpha7`, but no alpha-7 plugin is published to `ex-gradle`.

---

## 12. Key takeaways for this project

1. **The build system is GradleRIO, renamed.** Pin `id "org.wpilib.GradleRIO" version "2027.0.0-alpha-6"`, Gradle wrapper 9.4.1, Java 25. Start from `wpilibsuite/vscode-wpilib`'s `resources/gradle/java/build.gradle`, not from the stale `SystemcoreTesting` test project.
2. **Deploy is SSH.** No exotic protocol. `deploy` = stop the systemd unit → SFTP jars/`.so`s/`robotCommand`/deploy files under `/home/systemcore` → enable+start the unit → `sync`.
3. **Supervision is `robot.service`**: `Restart=always`, `RestartSec=3`, `LimitRTPRIO=50`, `TimeoutStopSec=1`, gated on `/home/systemcore/robotCommand` existing. Logs go to the **systemd journal** — `journalctl -u robot -f`. There is no `riolog`.
4. **We can absolutely build against our local WPILib, and I proved it.** `./gradlew publish` in `~/dev/allwpilib` writes `~/releases/maven/development`; the robot project consumes it with `wpi.maven.useLocal = false`, `wpi.maven.useWpilibMavenLocalDevelopment = true`, `wpi.versions.wpilibVersion = '2027.424242.+'`. Add `resolutionStrategy.cacheDynamicVersionsFor 0, 'seconds'` because every local build mints a fresh timestamped version.
5. **ZGC is already the default for deployed robot code** — we inherit our locked decision for free. It is a one-line override if measurement says otherwise, and WPILib's own dev robot uses G1, so measure.
6. **The RT design doc is a two-row stub.** Only the CAN HAL thread (50) and Notifier HAL thread (40) are real-time, via `SCHED_RR`. User code is not RT. `Threads.setCurrentThreadPriority` is `@Deprecated` and capped at 50 by systemd. Structure periodic work the way you always would: short, allocation-light, `addPeriodic` with offsets rather than threads.
7. **Profiling is dramatically better than the roboRIO**: a full JDK 25 with **JFR**, `jcmd`, `jmap`, `jstack` lives on the device. No `perf`/async-profiler. JFR + `-Xlog:gc*` is the plan; watch ZGC allocation stalls, not pause times.
8. **Sim works on WSL2 today** via WSLg, verified. Expect a ~3–8 s warm edit→run cycle for a pure-Java project; ~11 s was the measured worst case for allwpilib's C++-heavy `developerRobot`.
9. **The device is a Pi 5 running LimelightOS with PREEMPT_RT** and roughly 25 other services (vision stack, Docker, CAN daemons) sharing 4 cores with no CPU isolation. Our loop is a tenant, not the owner.
10. **Flag for #19 and beyond: Commands v3's templates use `OpModeRobot`, not `TimedRobot`.** That is a live challenge to a locked decision and deserves its own ticket.

---

## Appendix: source index

**Local `~/dev/allwpilib` @ `v2027.0.0-alpha-6-366-gcafb0cc79`**

| Path | Used for |
|---|---|
| `README.md` | build requirements, `./gradlew publish`, `-Prepo=`, toolchain install |
| `DevelopmentBuilds.md` | dev-build and local-build `build.gradle` snippets |
| `MavenArtifacts.md` | `org.wpilib` namespace, classifiers, repos |
| `design-docs/real-time-thread-priorities.md` | the RT table (2 rows) |
| `design-docs/opmodes.md` | OpMode design; `OpModeRobot extends RobotBase` |
| `design-docs/commands-v3.md`, `commands-v3-state-machines.md` | (not read in depth here) |
| `developerRobot/README.md`, `developerRobot/build.gradle` | source-tree deploy, `-XX:+UseG1GC`, `setcap`, addresses |
| `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml` | Gradle 9.4.1, plugin versions, mrclib |
| `settings.gradle`, `build.gradle`, `shared/java/javacommon.gradle` | subprojects, publishing, `wpilibVersioning` |
| `hal/src/main/native/systemcore/{CAN,Notifier,Threads}.cpp` | priorities 50/40, `SCHED_RR` |
| `wpilibj/src/main/java/org/wpilib/system/{Threads,Tracer}.java` | RT + timing APIs |
| `wpilibj/src/main/java/org/wpilib/framework/{TimedRobot,IterativeRobotBase,OpModeRobot}.java` | loop structure, Watchdog epochs |
| `telemetry/src/main/java/org/wpilib/telemetry/*` , `tunables/...` | new telemetry/tunable APIs |
| `commandsv3/CommandsV3.json`, `commandsv2/CommandsV2.json` | vendordep JSON shape, `wpilibYear` |
| `wpilibjExamples/src/main/java/org/wpilib/templates/**` | which base class each template uses |

**GitHub (`wpilibsuite`)**

| Repo / path | Used for |
|---|---|
| `GradleRIO/src/main/java/org/wpilib/gradlerio/deploy/systemcore/*` | `SystemCore`, `WPILibJavaArtifact`, `RobotCommandArtifact`, `RobotProgram{Start,Kill}Artifact`, `GarbageCollectorType` |
| `GradleRIO/src/main/java/org/wpilib/gradlerio/wpi/**` | `WPIVersionsExtension`, `WPIMavenExtension`, `WPIJavaDepsExtension`, `WPIJavaExtension`, `SimulationExtension` |
| `GradleRIO/testing/java/build.gradle` | current (`main`) robot-project shape |
| `vscode-wpilib/vscode-wpilib/resources/gradle/{java,shared}/**` | the shipped project template |
| `SystemcoreTesting/README.md` | addresses, flashing, DS/image/vendor compatibility matrix, packages |
| `SystemcoreTesting/testprojects/pwmoutput/**` | stale alpha-1 project (contrast only) |

**Artifacts / binaries inspected**

| Artifact | Used for |
|---|---|
| `frcmaven.wpi.edu/artifactory/ex-gradle/org/wpilib/GradleRIO/.../maven-metadata.xml` | published plugin versions |
| `GradleRIO-2027.0.0-alpha-6.jar` | released task names, ZGC default, `robotCommand` strings |
| `native-utils-2027.13.1.jar` | vendordep folder/task/errors, platform names |
| `ToolchainPlugin-2027.13.1.jar` | `NativePlatforms` constants, toolchain install |
| `wpilib-repositories-plugin-2027.0.0.jar` | `~/releases/maven/` local repo derivation |
| `wpilib-version-plugin-2027.0.1.jar` | the `424242` local-version marker |

**Live device** — `ssh systemcore@192.168.1.202`, `http://192.168.1.202/`. Read-only; nothing was
installed, deployed, or reconfigured.
