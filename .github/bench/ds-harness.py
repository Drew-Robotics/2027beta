#!/usr/bin/env python3
"""Drives the FIRST Driver Station with nobody at it. Runs on the bench runner.

Control is synthetic input and nothing else. The DS reads keyboards from evdev directly, which
is why its hotkeys work even when it is not focused, and its NetworkTables mirror on 6767
accepts writes that reach neither the DS nor the robot. So this creates a uinput keyboard of
its own and sends the documented hotkeys to it.

The opmode is chosen by writing the DS's settings file before it starts rather than by clicking
it: an opmode id is (mode << 56) | (name.hashCode() & 0x00FFFFFFFFFFFFFF), which is
OpModeOption.makeId, so it is computable from the name here. Nothing then depends on where a
control sits in a window.

The startup gate is undocumented and never times out — the DS asks for the spacebar to verify
the E-stop before it will do anything at all — so Space goes first, always.

Every part of this is exercised and the robot still does not enable, because the Driver Station
attaches to no simulation but the one on its own machine. See docs/bench-runner.md.
"""

import argparse
import fcntl
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
EV_SYN, EV_KEY, SYN_REPORT = 0x00, 0x01, 0x00

KEYS = {
    "esc": 1,
    "i": 23,
    "enter": 28,
    "space": 57,
    "leftbrace": 26,
    "rightbrace": 27,
    "backslash": 43,
}

MODES = {"autonomous": 1, "teleoperated": 2, "utility": 3}
STORAGE_KEY = {"autonomous": "AutoOpModeHash", "teleoperated": "TeleOpModeHash",
               "utility": "UtilOpModeHash"}

DS_HTTP_PORT = 6768


def opmode_id(mode, name):
    digest = 0
    for char in name:
        digest = (31 * digest + ord(char)) & 0xFFFFFFFF
    if digest >= 2**31:
        digest -= 2**32
    return ((MODES[mode] & 0x3) << 56) | (digest & 0x00FFFFFFFFFFFFFF)


class Keyboard:
    """A uinput keyboard. The DS enumerates evdev at startup, so create it before launching."""

    def __init__(self):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        for code in KEYS.values():
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, code)
        name = b"bench-ds-harness"
        device = struct.pack("80sHHHHi" + "i" * 256, name.ljust(80, b"\0"),
                             0x03, 0x0001, 0x0001, 1, 0, *([0] * 256))
        os.write(self.fd, device)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)
        # udev has to publish the event node with the group the DS can read before it is useful.
        time.sleep(1.0)

    def _event(self, kind, code, value):
        os.write(self.fd, struct.pack("qqHHi", 0, 0, kind, code, value))

    def _sync(self):
        self._event(EV_SYN, SYN_REPORT, 0)

    def chord(self, *names, hold=0.08):
        for name in names:
            self._event(EV_KEY, KEYS[name], 1)
            self._sync()
        time.sleep(hold)
        for name in reversed(names):
            self._event(EV_KEY, KEYS[name], 0)
            self._sync()
        time.sleep(0.15)

    def close(self):
        fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        os.close(self.fd)


class Display:
    """The runner has no screen, so the DS gets an Xvfb. An existing DISPLAY is used as it is."""

    def __init__(self, number=":99"):
        self.process = None
        self.name = os.environ.get("DISPLAY")
        if self.name:
            return
        if not shutil.which("Xvfb"):
            raise SystemExit("no DISPLAY and no Xvfb; see docs/bench-runner.md")
        self.name = number
        self.process = subprocess.Popen(
            ["Xvfb", number, "-screen", "0", "1280x1024x24", "-nolisten", "tcp"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2.0)

    def close(self):
        if self.process:
            self.process.terminate()


def select_opmode(storage, mode, name):
    """Writes the selection into the DS's settings file and returns the previous contents."""
    original = storage.read_text() if storage.exists() else None
    settings = json.loads(original) if original else {}
    settings[STORAGE_KEY[mode]] = opmode_id(mode, name)
    storage.parent.mkdir(parents=True, exist_ok=True)
    storage.write_text(json.dumps(settings, indent=2))
    return original


def wait_for_ds(deadline):
    while time.time() < deadline:
        with socket.socket() as probe:
            probe.settimeout(0.5)
            if probe.connect_ex(("127.0.0.1", DS_HTTP_PORT)) == 0:
                return True
        time.sleep(0.5)
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--opmode", default="DefaultTeleop")
    parser.add_argument("--mode", default="teleoperated", choices=sorted(MODES))
    parser.add_argument("--enable-seconds", type=float, default=40.0)
    parser.add_argument("--ds-home", default=os.path.expanduser("~/ds"))
    parser.add_argument("--storage", default=os.path.expanduser(
        "~/.local/share/FIRSTDriverStation/DriverStationStorage.json"))
    args = parser.parse_args()

    storage = Path(args.storage)
    binary = Path(args.ds_home) / "FirstDriverStation"
    if not binary.exists():
        raise SystemExit(f"no Driver Station at {binary}; see docs/bench-runner.md")

    original = select_opmode(storage, args.mode, args.opmode)
    display = None
    keyboard = None
    ds = None

    status = 0
    try:
        display = Display()
        keyboard = Keyboard()

        environment = dict(os.environ)
        environment.update(DISPLAY=display.name, NO_AT_BRIDGE="1", LIBGL_ALWAYS_SOFTWARE="1")
        environment.pop("SESSION_MANAGER", None)
        ds = subprocess.Popen([str(binary)], cwd=args.ds_home, env=environment,
                              stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)

        if not wait_for_ds(time.time() + 60):
            raise SystemExit("the Driver Station never came up")
        time.sleep(5.0)

        keyboard.chord("space")            # the startup gate
        time.sleep(2.0)
        keyboard.chord("leftbrace", "rightbrace", "backslash")   # enable
        print(f"enabled for {args.enable_seconds:.0f}s", flush=True)
        time.sleep(args.enable_seconds)
        keyboard.chord("enter")            # disable
        time.sleep(1.0)
    except SystemExit as failure:
        print(failure, file=sys.stderr)
        status = 1
    finally:
        if ds is not None:
            ds.send_signal(signal.SIGTERM)
            try:
                ds.wait(timeout=10)
            except subprocess.TimeoutExpired:
                ds.kill()
        if keyboard is not None:
            keyboard.close()
        if display is not None:
            display.close()
        # A box whose Driver Station had never stored settings gets its clean state back, not
        # this run's opmode selection.
        if original is not None:
            storage.write_text(original)
        else:
            storage.unlink(missing_ok=True)

    return status


if __name__ == "__main__":
    sys.exit(main())
