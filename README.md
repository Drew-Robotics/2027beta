# 2027beta

FRC team 8852's swerve drive base for the 2027 season, on the SystemCore
control system. WPILib 2027 alpha, Java 25, Commands v3.

The architecture was decided and written down before it was built.
Fourteen ADRs in [`docs/adr/`](docs/adr/README.md) hold the decisions,
[`CONTEXT.md`](CONTEXT.md) defines every word this project invents or
overloads,
[`docs/commands-v3-house-style.md`](docs/commands-v3-house-style.md)
teaches the code you are about to write, and
[`docs/research/`](docs/research/) holds the measurements they cite.

The robot code is the next effort, and so is most of the tooling below.
Anything specified but not yet built says so where it is described.

## Getting set up

1. **JDK 25** and the **WPILib 2027 VSCode extension.** The extension
   installs a JDK and the local maven home the build reads from.
2. **Clone it and open it in VSCode.** Trusting the project is one
   keystroke, and there is nothing else to install — what an agent needs
   is in the repo, described under *What is checked in for agents*.
3. **`./gradlew simulateJava`** — the sim GUI and the simulated Driver
   Station are both enabled in `build.gradle`.
4. **`./gradlew test`** — Tier 1, where every number this project
   asserts lives. It needs no hardware and no HAL.
5. **`./gradlew sysidLog`**, when you are characterising — it runs the
   routines against the simulation and writes
   `logs/sysid-simulation.wpilog`, which opens in the SysId analyser.
   Every gain fitted from it describes the model and not a robot.
6. **`uv`**, only if you are touching `tools/logtool`. *Not built yet.*

Then read [`CONTEXT.md`](CONTEXT.md), the house style, and the ADR
covering whatever you are about to change.

**Nobody pushes to `main`.** Branch, push the branch, open a pull
request. [ADR 0013](docs/adr/0013-ci-and-test-strategy.md) makes the
gated workflow a required check including administrators, with a
per-event branch as the escape hatch during competition — a branch is a
thing you have to mean, where an admin override is a habit that gets
used on a Tuesday. `main` carries that ruleset now: the check is
required, and deletion and force-push are refused.

While 2027 is in flux the project builds against a local allwpilib
checkout rather than a published release; see
[ADR 0003](docs/adr/0003-project-and-package-structure.md) for the
decision and
[`docs/research/systemcore-deploy.md`](docs/research/systemcore-deploy.md)
for the recipe.

## Seats, and who gets one

**Team plan seats for the mentor and for students who are 18 or over.**
Everyone else works through the reviewer.

That is not a budget decision. **Nobody under 18 can hold a Claude
account, on any plan.** The rule is written in four places, recorded
here rather than left on the tracker so that the 2028 fork does not
re-derive it:

| Source | What it says |
|---|---|
| [Consumer Terms](https://www.anthropic.com/legal/consumer-terms) (eff. 2025-10-08) | At least 18, or your local minimum age, whichever is higher. |
| [Usage Policy](https://www.anthropic.com/legal/aup) (eff. 2025-09-15) | A minor is anyone under 18, *regardless of jurisdiction* — which closes the local-age reading the Consumer Terms leave open. |
| [Minimum age requirement](https://support.claude.com/en/articles/13117299-minimum-age-requirement-access-restriction) | All users must be at least 18 to create and use an account. **No exception** for a school, an institution, an education program, parental consent or supervision. |
| [Team plan](https://support.claude.com/en/articles/9266767-what-is-the-team-plan) | No age exception. An admin invite does not bypass account creation, so each member still meets the gate personally. |

Two things make signing up anyway a bad plan rather than a grey one.
[Age assurance](https://support.claude.com/en/articles/15171100-age-assurance-on-claude)
shipped on 2026-05-18: it fires on under-18 signals and *disables* the
account until verification passes, so the failure mode is a student
losing their account mid-season with no warning and nothing a mentor can
do. And
[Claude for Teachers](https://www.anthropic.com/news/claude-for-teachers)
(2026-07-14) is free premium access including Claude Code — for the
teacher: *"Claude for Teachers is for educators only, consistent with
Claude's 18-and-over policy."* If a student path existed anywhere, that
product is where it would be. The full finding, including why the API
door leads somewhere a volunteer team cannot go, is on
[#24](https://github.com/Drew-Robotics/2027beta/issues/24).

The consequence worth stating plainly: **for most of this team, the
reviewer is the whole channel.** It is not a nice-to-have on top of
per-student seats — it is what delivers the same assistance to a student
who cannot have an account, and it needs no student identity at all.

## The safety rule

**An agent may build, test, simulate and deploy freely. A human presses
enable.**

Sim and unit tests are unbounded on purpose — the whole autonomous loop
closes with no HAL precisely so that an agent can iterate there. But
nothing an agent does may put a real robot in motion. There is no undo
on a swerve base at 4 m/s.

No agent commits to `main`.

Past that one rule the guardrail is review, not configuration. A student
reading agent-written code and asking why it does that is the learning,
not an obstacle to it.

## What is checked in for agents

**`.claude/settings.json` holds a permission allowlist, and nothing
else** — the calls that should never prompt: `./gradlew *`, read-only
`git`, read-only `gh issue`, `ssh systemcore@…`, `uv run`. The argument
for it is safety rather than convenience: being prompted constantly
teaches you to approve without reading, which is the actual risk.
Personal preferences belong in `.claude/settings.local.json`, which
stays out of the repo. *Neither file is committed yet.*

**No hooks.** A format-on-save or build-before-commit hook is
enforcement machinery, and the gated workflow already owns enforcement.
A hook would put the same rule in a second place, invisible in the CI
log that is supposed to be the record.

**No MCP servers.** Every candidate is already a CLI an agent drives
natively — the device is `ssh` and `journalctl`, the log store is
`tools/logtool`, the tracker is `gh`. A server would buy no capability
and add a process to install, version, keep alive and re-approve on
every clone. Revisit only if something turns out to be genuinely
unreachable from a shell.

**One skill, `/analyze-match`**, because it is a *process* with a
completion bar — every one of five question classes reported with a
finding or an explicit *clear*
([ADR 0014](docs/adr/0014-ai-log-analysis-contract.md)). Reference
material gets a pointer in a document instead: a scaffolder for
mechanisms or autos would be a second copy of the house style, and a
second copy drifts. *Not built yet.*

`CLAUDE.md` is the file agents actually load. It points rather than
copies; what it holds inline is only the small set of things an agent
cannot look up because it does not know it is wrong — the 2027 API
renames, the four hazards that compile clean and fail on the field, and
the comment rule.

## The reviewer

*Specified in [ADR 0013](docs/adr/0013-ci-and-test-strategy.md) and on
[#18](https://github.com/Drew-Robotics/2027beta/issues/18); not built
yet.*

It is a normal Claude Code session with a different opening prompt. It
reads `CLAUDE.md` and the ADRs like any other session, so there is no
second knowledge store to keep in sync.

- **Trigger:** `workflow_run` against the gated workflow, gated on
  `conclusion == success`. It does not spend a review on defects the
  build catches in seconds — and, more importantly, `workflow_run` runs
  the workflow definition from `main`, so a student with write access
  cannot edit the reviewer's workflow inside their own pull request and
  print the key.
- **Credential:** a Console API key with a monthly spend cap, held in a
  GitHub Environment whose required reviewer is the map owner. It is
  never a student's account and no student ever sees it.
- **Remit:** what CI structurally cannot check — the 2027 hazards, and
  the comment rule. Pasting `ChassisSpeeds` out of a 2025 tutorial is
  the single most likely defect in this repo, and an agent catches it in
  seconds.
- **Not its job:** formatting, which spotless owns; anything a test
  covers; general opinions about your code.
- **Output:** inline comments where the problem is, and a summary at the
  end. The cost of the trigger lands here: a `workflow_run` job does not
  know its pull request, so posting means resolving the number and
  calling the review API by hand.

## Deploying

Use the VSCode extension's deploy button, or `./gradlew deploy`. It is
plain SSH and SFTP — stop `robot.service`, copy over the jars, the JNI
`.so` files, `robotCommand` and everything in `src/main/deploy/`, start
the unit again.

**There is no riolog.** The console is the systemd journal, on the
device:

```bash
ssh systemcore@robot.local journalctl -u robot -f
```

The web terminal at port 4901 is the same thing without an SSH client.
And never `println` from anything that runs periodically — one was
measured at ~25 ms, enough to trip the watchdog by itself
([ADR 0003](docs/adr/0003-project-and-package-structure.md)).

**The failure that costs an afternoon.** The OS image and the allwpilib
commit are a version *pair*, and a mismatch is not a build error: the
HAL terminates at startup, `robot.service` restarts it three seconds
later, and it does that forever. **A robot that reboots every three
seconds is an ABI mismatch** — flash the Pi forward to the current
image. There is no pin to look up, deliberately: a recorded triple goes
stale within the week and would eventually tell a student to flash
backwards ([#51](https://github.com/Drew-Robotics/2027beta/issues/51)).

[#18](https://github.com/Drew-Robotics/2027beta/issues/18) proposed
catching this before deploy, as a Gradle task wired ahead of `deploy`
rather than a skill — because a skill only reaches someone running an
agent, and the person who most needs the check is the student clicking
the VSCode button. [ADR 0013](docs/adr/0013-ci-and-test-strategy.md)
declined the probe as over-engineering a system that is going stable
shortly. What detects the drift instead is the nightly bench build
going red as the floating dependency outruns the flashed image, and what
gets a student out of it at the bench is the paragraph above.

## The machines around this project

**`~/dev/allwpilib` — the reference for reading source.** A *built*
checkout of the alpha-7 WPILib that `CONTEXT.md` defines and pins. Read
it before believing yourself: `docs.wpilib.org` lags the alpha badly,
and 2027 renamed enough that every snippet on the internet is wrong at
the import line. It carries built javadoc under `*/build/docs/javadoc/`
and upstream's own design docs under `design-docs/`.

**The bench Pi — one Raspberry Pi 5 running the SystemCore image** at
`192.168.1.202`, also reachable as `robot.local`. Key-based SSH is
installed and the stock `systemcore` / `systemcore` password still
works; a key survives an OS update applied as a `.llupdate` payload but
is wiped by a full reflash from the `.zip` image. No CAN hardware is
attached to it, and deploy from a dev machine is verified end to end
([`docs/research/systemcore-smoketest.md`](docs/research/systemcore-smoketest.md)).
There is exactly one of it, which is why it can never gate anything:
hardware CI is advisory, and an unreachable bench is a skip rather than
a red.

**Where the logs are — two different logs.** The console is the systemd
journal, and it exists only on the device. The `.wpilog` is the artifact
of record: `/u/logs` when a USB stick is mounted, `/home/systemcore/logs`
otherwise. Pulling it is a habit rather than a convenience, because the
robot deletes old logs on its own and the filename is the only place a
match's identity exists
([ADR 0014](docs/adr/0014-ai-log-analysis-contract.md)).

**The image is BusyBox, and it is missing the tools you will reach for
first:** `timeout`, `pgrep`, `gcc`/`cc`, `perf`, `unzip`. Use `jcmd -l`
where you wanted `pgrep`. A full JDK 25 *is* on the device, along with
`python3` and the binutils, so profiling there is JFR rather than
`perf`.
