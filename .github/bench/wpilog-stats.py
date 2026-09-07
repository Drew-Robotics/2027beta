#!/usr/bin/env python3
"""Prints the facts sim-hitl asserts on, as KEY=value lines, from a robot WPILOG.

The record framing is parsed here rather than by a library because there is no released WPILOG
reader for 2027 and the framing is a short parse. Timestamps on disk are microseconds: the Java
API is nanoseconds in both directions and DataLog divides on the way in.

`DS:controlWord` is one uint64 bitfield whose layout is in the log's own schema entry —
opModeHash:56, robotMode:2, enabled:1, eStop:1, fmsAttached:1, dsAttached:1.
"""

import struct
import sys

LOOP = "/Telemetry/Robot/LoopDelta"
CONTROL = "DS:controlWord"
OPMODE = "DS:opMode"


def records(data):
    """Walks the file, stopping at the first partial record rather than reading past it.

    The simulation is killed rather than closed, so a torn tail record is the normal ending.
    """
    extra = struct.unpack("<I", data[8:12])[0]
    pos = 12 + extra
    while pos < len(data):
        header = data[pos]
        id_len = (header & 0x3) + 1
        size_len = ((header >> 2) & 0x3) + 1
        stamp_len = ((header >> 4) & 0x7) + 1
        pos += 1
        if pos + id_len + size_len + stamp_len > len(data):
            return
        entry = int.from_bytes(data[pos : pos + id_len], "little")
        pos += id_len
        size = int.from_bytes(data[pos : pos + size_len], "little")
        pos += size_len
        stamp = int.from_bytes(data[pos : pos + stamp_len], "little")
        pos += stamp_len
        if pos + size > len(data):
            return
        yield entry, stamp, data[pos : pos + size]
        pos += size


def read(path):
    data = open(path, "rb").read()
    if data[:6] != b"WPILOG":
        raise SystemExit(f"{path} is not a WPILOG")
    names = {}
    loop = []
    control = []
    opmodes = []
    for entry, stamp, payload in records(data):
        if entry == 0:
            if len(payload) < 9 or payload[0] != 0:
                continue
            entry_id, length = struct.unpack("<II", payload[1:9])
            if len(payload) < 9 + length:
                continue
            names[entry_id] = payload[9 : 9 + length].decode(errors="replace")
        else:
            name = names.get(entry)
            if name == LOOP and len(payload) == 8:
                loop.append((stamp, struct.unpack("<d", payload)[0]))
            elif name == CONTROL and len(payload) == 8:
                control.append((stamp, struct.unpack("<Q", payload)[0]))
            elif name == OPMODE:
                opmodes.append(payload.decode(errors="replace"))
    return loop, control, opmodes


def spans(control, bit, until):
    """The [start, end) stamps over which the given control-word bit was set.

    The control word is logged only when it changes, so a run that ends while still enabled has
    its enable as the last record. Such a span runs to `until` — the end of the log — rather
    than to itself, which would be empty and would put every enabled sample in the other bucket.
    """
    out = []
    open_at = None
    for stamp, word in control:
        on = (word >> bit) & 1
        if on and open_at is None:
            open_at = stamp
        elif not on and open_at is not None:
            out.append((open_at, stamp))
            open_at = None
    if open_at is not None:
        out.append((open_at, until + 1))
    return out


def stats(samples):
    if not samples:
        return {}
    ordered = sorted(samples)
    at = lambda q: ordered[min(len(ordered) - 1, int(len(ordered) * q))]
    return {
        "SAMPLES": len(ordered),
        "P50": at(0.50),
        "P95": at(0.95),
        "P99": at(0.99),
        "MAX": ordered[-1],
    }


def main():
    loop, control, opmodes = read(sys.argv[1])
    last = max((stamp for stamp, _ in loop + control), default=0)
    enabled = spans(control, 58, last)
    attached = spans(control, 61, last)

    # The first loops after an enable are the opmode's construction and its first schedule, which
    # are not the steady state this is watching for a regression.
    settle = 1_000_000
    inside = [
        value
        for stamp, value in loop
        if any(start + settle <= stamp < end for start, end in enabled)
    ]
    outside = [
        value
        for stamp, value in loop
        if not any(start <= stamp < end for start, end in enabled)
    ]

    print(f"DS_ATTACHED={1 if attached else 0}")
    print(f"ENABLED_WINDOWS={len(enabled)}")
    print(f"ENABLED_SECONDS={sum(end - start for start, end in enabled) / 1e6:.1f}")
    print(f"OPMODE={opmodes[-1] if opmodes else ''}")
    for key, value in stats(inside).items():
        print(f"ENABLED_{key}={value}")
    for key, value in stats(outside).items():
        print(f"DISABLED_{key}={value}")


if __name__ == "__main__":
    main()
