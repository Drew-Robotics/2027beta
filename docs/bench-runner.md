# The bench runner

[ADR 0013](adr/0013-ci-and-test-strategy.md) decides what the bench workflow runs and what it
asserts. This is the box it runs on, and it is the one piece of that ADR that is provisioning
rather than design.

There is one bench Pi, so the workflow can gate nothing. Nothing here should ever be made a
required check.

## What the runner is

A Linux box on the same LAN as the bench Pi, registered as a self-hosted GitHub runner with the
labels `self-hosted`, `linux` and `bench`. It builds, deploys over ssh, and — with `DS=on` —
runs the Driver Station.

The Pi has to be able to send UDP back to it, not only receive: the simulation's Driver Station
link is two-way. A runner behind NAT from the Pi cannot run `sim-hitl` with `DS=on`.

## Registering it

```bash
# A registration token, from a repo admin's gh:
gh api -X POST repos/Drew-Robotics/2027beta/actions/runners/registration-token --jq .token

mkdir ~/actions-runner && cd ~/actions-runner
curl -sL https://github.com/actions/runner/releases/latest/download/actions-runner-linux-x64.tar.gz | tar xz
./config.sh --url https://github.com/Drew-Robotics/2027beta --token <token> \
    --labels self-hosted,linux,bench --unattended
sudo ./svc.sh install && sudo ./svc.sh start
```

Two repo settings go with it, and it is both rather than either:

- **Actions → Fork pull request workflows from outside collaborators → require approval for all
  outside collaborators.** A self-hosted runner on a public repo runs whatever a workflow tells
  it to.
- **Branch protection on `main` must not list `real-hal-boot` or `sim-hitl`.** They are allowed
  to be red, and they are allowed to be absent.

The workflow itself triggers only on pushes to `main`, the nightly schedule and manual dispatch —
never on `pull_request` — for the same reason.

## What the runner needs installed

| what | why |
|---|---|
| an ssh key in `systemcore@<bench>`'s `authorized_keys` | every job reaches the bench over `BatchMode=yes` ssh |
| `python3` | the WPILOG reader and the Driver Station relay |
| JDK 25 | `actions/setup-java` provides it in CI; a hand run needs one |

For `DS=on` only:

| what | why |
|---|---|
| `xvfb` | the Driver Station is Avalonia/X11 and exits with `XOpenDisplay failed` without a display |
| `/dev/uinput` readable by the runner's user | the harness makes its own keyboard; the DS reads evdev directly, which is why its hotkeys work unfocused |
| the Driver Station unpacked at `~/ds` | `--ds-home` moves it |

```bash
sudo apt-get install -y xvfb
echo 'KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="input", MODE="0660"' |
    sudo tee /etc/udev/rules.d/99-uinput.rules
sudo udevadm control --reload-rules && sudo chgrp input /dev/uinput && sudo chmod 660 /dev/uinput
```

## Running either job by hand

Both scripts print the same Markdown the workflow puts in its step summary, and both treat an
unreachable bench as a skip that exits 0.

```bash
BENCH=systemcore@192.168.1.202 .github/bench/real-hal-boot.sh
BENCH=systemcore@192.168.1.202 .github/bench/sim-hitl.sh
```

`sim-hitl` stops `robot.service`, `mrccomm.service` and `limelight_diagnosticsprocess.service`
for the run and starts all three again afterwards, whatever happens. The last two hold UDP 1110
and the system NetworkTables server on 6810; leaving them up makes the simulation abort with
`Multiple user programs detected` as soon as a previous program is still registered there.

## Why `DS=on` is not the default

The Driver Station finds a simulated robot **only on the machine it is running on**. Measured on
this bench:

- against the real robot it discovers the Pi and connects over TCP 1740, having first touched
  6810 and 5810;
- against a simulation on its own box it reports `robotIp 127.0.0.1` and drives it;
- against the bench simulation it reports `robotIp 0.0.0.0` and opens nothing — with the sim
  owning 6810 and 5810 on the LAN, TCP 1740/1741 proxied onto the LAN, the sim's loopback UDP
  doorbell bridged both ways, and 6810/1740/1741/5810 tunnelled onto the runner's own loopback.

`halsim_ds_socket` no longer implements the protocol itself; it hands the link to mrclib's
`MRC_SimSystemServer`, and nothing in that path publishes a team number
(`MRC_SimSystemServer_SetTeam` exists and the extension never calls it). So a remote simulation
is not something the Driver Station can be pointed at today, and `sim-hitl` measures the
**disabled** loop, which is real and regresses the same way.

The harness itself is built and every part of it is exercised: Xvfb runs the Driver Station
headless, the uinput keyboard appears as an evdev device the DS can read, and an opmode is
selected by writing `DriverStationStorage.json` — the id is
`(mode << 56) | (name.hashCode() & 0x00FFFFFFFFFFFFFF)`, so no window coordinates are involved.
What is missing is upstream's, not ours.
