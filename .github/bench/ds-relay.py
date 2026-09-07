#!/usr/bin/env python3
"""Puts the bench's simulation on the runner's own loopback, where a Driver Station looks.

mrclib's sim system server binds loopback only — a desktop simulation and its Driver Station
are normally the same machine — so a simulation on the bench is invisible to a Driver Station
anywhere else. This bridges the three UDP ports of that link, one copy of this on each box,
with TCP going through ssh -L alongside it.

  bench:  ds-relay.py --role bench  --peer <runner>
  runner: ds-relay.py --role runner --peer <bench>

Both ends need the peer up front. The simulation announces itself to the station before the
station has said anything, so a bench relay that waited to learn where to send would deadlock
against a station waiting to be announced to.

The packets do cross, in both directions, and the Driver Station still does not attach: it
finds no robot it did not find locally. See docs/bench-runner.md — what is missing is upstream's
rather than a port that was left out here.

Nothing writes NetworkTables: port 6767 accepts writes, propagates none of them, and corrupts
the view for every other reader.
"""

import argparse
import select
import socket
import subprocess
import sys

TO_ROBOT = (1110, 1115)
TO_STATION = 1135


def lan_address(peer):
    """The address this box reaches the peer from, which is the one the peer must answer on."""
    route = subprocess.run(
        ["ip", "route", "get", peer], capture_output=True, text=True
    ).stdout.split()
    if "src" not in route:
        raise SystemExit(f"no route to {peer}; pass --bind")
    return route[route.index("src") + 1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", required=True, choices=("bench", "runner"))
    parser.add_argument("--peer", required=True, help="the address of the other box")
    parser.add_argument("--bind", default=None, help="this box's LAN address")
    arguments = parser.parse_args()

    lan = arguments.bind or lan_address(arguments.peer)
    runner = arguments.role == "runner"

    # Each end binds the side its local process is not already using: the simulation holds
    # loopback on the bench, and the Driver Station holds loopback on the runner.
    inbound = {}
    for port in TO_ROBOT:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("127.0.0.1" if runner else lan, port))
        inbound[sock] = port

    returning = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    returning.bind((lan if runner else "127.0.0.1", TO_STATION))

    out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    print(f"relay up as {arguments.role} on {lan}, peer {arguments.peer}", flush=True)

    while True:
        ready, _, _ = select.select([*inbound, returning], [], [], 1.0)
        for sock in ready:
            data, _ = sock.recvfrom(65535)
            if sock is returning:
                # Robot to station: onto the wire from the bench, into loopback on the runner.
                out.sendto(data, ("127.0.0.1" if runner else arguments.peer, TO_STATION))
            else:
                port = inbound[sock]
                out.sendto(data, (arguments.peer if runner else "127.0.0.1", port))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
