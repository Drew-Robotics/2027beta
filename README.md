# 2027beta

FRC Team 8852's 2027 SystemCore swerve drive base. It uses WPILib 2027
alpha, Java 25, and Commands v3.

Read these before changing code:

- [`CONTEXT.md`](CONTEXT.md) defines the project's special terms.
- [`docs/adr/`](docs/adr/README.md) records architecture decisions.
- [`docs/commands-v3-house-style.md`](docs/commands-v3-house-style.md)
  teaches this project's command style.
- [`docs/research/`](docs/research/) contains the measurements and source
  notes behind the ADRs.

## Start here

1. Install JDK 25 and the WPILib 2027 VSCode extension.
2. Clone the repository and open it in VSCode.
3. Run `./gradlew run` to start the simulation.
4. Run `./gradlew test` to run tests without hardware.
5. Run `./gradlew sysidLog` when working on SysId. Its log describes the
   simulation, not the real robot.
6. Install `uv` only when working on `tools/logtool`, which is not built yet.

The project currently uses a local allwpilib checkout. See
[ADR 0003](docs/adr/0003-project-and-package-structure.md) and
[`docs/research/systemcore-deploy.md`](docs/research/systemcore-deploy.md).

## Working safely

- Never push directly to `main`. Use a branch and pull request.
- An agent may build, test, simulate, and deploy. A person must enable a real
  robot.
- No agent may commit to `main`.

The required CI workflow protects `main`, including for administrators. During
competition, use a deliberate per-event branch if needed. See
[ADR 0013](docs/adr/0013-ci-and-test-strategy.md).

## AI access

Team-plan seats are for the mentor and students who are at least 18. Everyone
else uses the reviewer. Anthropic requires users to be at least 18; team plans,
school use, and mentor supervision do not change that. Details and sources are
in [issue #24](https://github.com/Drew-Robotics/2027beta/issues/24).

The reviewer is a Claude Code session with a different opening prompt, reading
`CLAUDE.md` and the ADRs like any other session. It runs once a pull request's CI
run is green, and comments only on the field-only risks CI cannot detect. It is
not a replacement for student review. See
[ADR 0013](docs/adr/0013-ci-and-test-strategy.md) and
[issue #18](https://github.com/Drew-Robotics/2027beta/issues/18).

Each run waits for a person. Its API key lives in the `pr-review` GitHub
Environment, and the job pauses on the run's page under Actions until the map
owner approves it — nothing is posted, and no key is injected, before that.

`.claude/settings.json` is checked in. It is a permission allowlist, so an agent
runs `./gradlew`, read-only `git`, read-only `gh issue`, `ssh systemcore@...` and
`uv run` without asking. Claude Code ignores it until you accept the trust dialog
once, on the first run in a fresh clone. Your own approvals go in
`.claude/settings.local.json`, which is not checked in.

## Deploying

Use the VSCode deploy button or `./gradlew deploy`. Deployment stops
`robot.service`, copies the robot files, and starts the service again.

Watch the robot journal with:

```bash
ssh systemcore@robot.local journalctl -u robot -f
```

Do not use `println` in periodic code. It can exceed the loop budget.

If the robot restarts every three seconds, the SystemCore image and allwpilib
checkout are incompatible. Update the Pi to the current image. See
[issue #51](https://github.com/Drew-Robotics/2027beta/issues/51).

## Local references

- `~/dev/allwpilib` is the WPILib source reference. Check it before using online
  examples: 2027 APIs changed substantially.
- The bench Pi is at `192.168.1.202` or `robot.local`. It has no CAN hardware,
  so hardware CI is advisory only.
- The system journal is the console. WPILOG files are stored in `/u/logs` with a
  USB stick or `/home/systemcore/logs` otherwise. Copy logs before the robot
  deletes old files.
- The SystemCore image uses BusyBox. It does not include `timeout`, `pgrep`,
  compilers, `perf`, or `unzip`. Use `jcmd -l` instead of `pgrep`; use JFR for
  profiling.
