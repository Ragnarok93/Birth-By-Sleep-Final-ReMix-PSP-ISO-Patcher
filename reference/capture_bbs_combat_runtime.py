#!/usr/bin/env python3
"""Read-only PPSSPP runtime evidence capture for the ULJM05775 combat port.

Requires: python -m pip install websocket-client
Starts *temporary* CPU execution breakpoints and reads registers/player state.
Never changes EBOOT, player memory, PSP instructions or the ISO.
"""
from __future__ import annotations

import argparse
import base64
import json
import time
from datetime import datetime, timezone
from pathlib import Path

EXPECTED_GAME = "ULJM05775"
# Each candidate has a source-fingerprinted instruction establishing player
# pointer ownership at the breakpoint PC; no inferred global player pointer.
SITES = {
    0x08955F10: ("cancel_consumer", "s0"),  # lw a0,+0x238(s0)
    0x08B07020: ("cancel_setter", "a0"),    # lw a2,+0x238(a0)
}
# Source-fingerprinted resident PSP instructions (not Steam memory offsets).
# Validate these in live emulated memory before adding any breakpoints.
EXPECTED_INSTRUCTIONS = {
    0x08955F10: (
        0x8E040238, 0x30841000, 0x14800006, 0x3404000E, 0x8E040240,
        0x00912024, 0x148001D7, 0x00000000, 0x3404000E, 0xA604022C,
    ),
    0x08B07020: (
        0x8C860238, 0x2407EFFF, 0x30A50001, 0x00C73024,
        0x00052B00, 0x00C52825, 0x03E00008, 0xAC850238,
    ),
}


def verify_resident_source(debugger: "Debugger") -> None:
    for address, instructions in EXPECTED_INSTRUCTIONS.items():
        response = debugger.request(
            "memory.read", address=address, size=4 * len(instructions), replacements=False
        )
        actual = base64.b64decode(response["base64"], validate=True)
        expected = b"".join(word.to_bytes(4, "little") for word in instructions)
        if actual != expected:
            raise RuntimeError(
                f"PSP instruction signature mismatch at {address:#010x}; "
                "refusing runtime trace for a different or modified executable"
            )


PLAYER_FIELDS = {
    "state": (0x220, 4),
    "substate": (0x22C, 2),
    "command_state": (0x22E, 2),
    "player_flags": (0x234, 4),
    "trigger_flags": (0x238, 4),
    "attack_status": (0x23C, 4),
    "command_kind": (0x268, 4),
}
READ_SIZE = 0x26C


def extract_gprs(reply: dict) -> dict[str, int]:
    """Use category 0 names, never assume a register array's positional order."""
    for category in reply.get("categories", []):
        if category.get("id") == 0:
            names = category.get("registerNames", [])
            values = category.get("uintValues", [])
            if not isinstance(names, list) or len(names) != len(values):
                raise ValueError("invalid PPSSPP register response")
            return {name.lstrip("$"): int(value) for name, value in zip(names, values)}
    raise ValueError("PPSSPP GPR category not present")


def valid_player_address(pointer: int) -> bool:
    # Conservative bound for PPSSPP emulated user-memory addresses.
    return pointer % 4 == 0 and 0x08000000 <= pointer <= 0x09FFFFFF - READ_SIZE


def decode_fields(base64_data: str) -> dict[str, int]:
    raw = base64.b64decode(base64_data, validate=True)
    if len(raw) != READ_SIZE:
        raise ValueError(f"unexpected player read size {len(raw)}")
    return {
        name: int.from_bytes(raw[offset:offset + width], "little")
        for name, (offset, width) in PLAYER_FIELDS.items()
    }


class Debugger:
    def __init__(self, websocket):
        self.ws = websocket
        self.ticket = 0
        self.events: list[dict] = []

    def send(self, event: str, **params):
        self.ws.send(json.dumps({"event": event, **params}))

    def recv(self, timeout: float) -> dict:
        self.ws.settimeout(max(0.1, timeout))
        return json.loads(self.ws.recv())

    def request(self, event: str, timeout: float = 10, **params) -> dict:
        self.ticket += 1
        ticket = self.ticket
        self.send(event, ticket=ticket, **params)
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            reply = self.recv(until - time.monotonic())
            if reply.get("ticket") == ticket:
                if reply.get("event") == "error":
                    raise RuntimeError(f"{event}: {reply.get('message')}")
                if reply.get("event") != event:
                    raise RuntimeError(f"{event}: unexpected reply {reply.get('event')}")
                return reply
            if reply.get("event") in ("cpu.stepping", "cpu.breakpoint.hit"):
                self.events.append(reply)
        raise TimeoutError(f"PPSSPP did not reply to {event}")


def trace(debugger: Debugger, max_hits: int, seconds: int) -> dict:
    debugger.request("version", name="bbs-combat-trace", version="1.0")
    game = debugger.request("game.status").get("game")
    if not isinstance(game, dict) or game.get("id") != EXPECTED_GAME:
        raise RuntimeError(f"Expected {EXPECTED_GAME}, connected game: {game!r}")
    verify_resident_source(debugger)
    # Adding a breakpoint replaces an existing breakpoint at the same address;
    # refuse rather than clobbering a human debugger session.
    current = debugger.request("cpu.breakpoint.list").get("breakpoints", [])
    existing = {int(item["address"]) for item in current}
    conflicting = existing.intersection(SITES)
    if conflicting:
        raise RuntimeError(f"Existing breakpoints at {[hex(x) for x in sorted(conflicting)]}")

    records: list[dict] = []
    installed: list[int] = []
    ours_paused = False
    deadline = time.monotonic() + seconds
    try:
        for address in SITES:
            debugger.request("cpu.breakpoint.add", address=address, enabled=True, log=True)
            installed.append(address)

        while len(records) < max_hits and time.monotonic() < deadline:
            if debugger.events:
                event = debugger.events.pop(0)
            else:
                try:
                    event = debugger.recv(min(1.0, max(0.1, deadline - time.monotonic())))
                except TimeoutError:
                    continue
                except Exception as exc:
                    # websocket-client raises WebSocketTimeoutException, a subclass
                    # of TimeoutError on supported versions.
                    if exc.__class__.__name__ == "WebSocketTimeoutException":
                        continue
                    raise

            if event.get("event") != "cpu.stepping":
                continue
            hit = event.get("hit") or {}
            address = hit.get("address")
            if hit.get("kind") != "exec" or address not in SITES or not hit.get("paused"):
                continue

            ours_paused = True
            label, register = SITES[address]
            item: dict = {
                "site": label, "pc": address, "sequence": hit.get("hits"),
                "emulated_ticks": event.get("ticks"),
                "collected_at": datetime.now(timezone.utc).isoformat(),
            }
            try:
                regs = extract_gprs(debugger.request("cpu.getAllRegs"))
                item["registers"] = {k: regs.get(k) for k in
                                     ("pc", "ra", "sp", "a0", "a1", "a2", "a3",
                                      "s0", "s1", "v0", "v1")}
                player = regs.get(register, 0)
                item["player_pointer"] = player
                if valid_player_address(player):
                    payload = debugger.request("memory.read", address=player,
                                               size=READ_SIZE, replacements=False)
                    item["fields"] = decode_fields(payload["base64"])
                else:
                    item["read_error"] = "player pointer outside conservative PSP user-memory bounds"
            except (ValueError, RuntimeError, KeyError) as exc:
                item["read_error"] = str(exc)
            records.append(item)
            # Do not change any player state. Resume only a pause caused by us.
            debugger.send("cpu.resume")
            ours_paused = False
    finally:
        if ours_paused:
            debugger.send("cpu.resume")
        for address in reversed(installed):
            try:
                debugger.request("cpu.breakpoint.remove", address=address)
            except Exception as exc:
                print(f"WARNING: remove breakpoint {address:#x} manually: {exc}")
    return {
        "schema": "bbs-combat-runtime-trace-v1",
        "game_id": EXPECTED_GAME,
        "sites": {hex(addr): {"name": name, "pointer_register": reg}
                  for addr, (name, reg) in SITES.items()},
        "records": records,
        "limits": {
            "max_hits": max_hits, "timeout_seconds": seconds,
            "memory_reads": "player 0x0..0x26b read only, known fields extracted",
            "not_proven": [
                "hit-confirm ownership", "per-frame scheduler",
                "input timing", "critical abilities", "combat gameplay correctness",
            ],
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="ws://127.0.0.1:8080/debugger")
    parser.add_argument("-o", "--output", type=Path, default=Path("bbs-combat-trace.json"))
    parser.add_argument("--max-hits", type=int, default=24)
    parser.add_argument("--timeout-seconds", type=int, default=120)
    args = parser.parse_args()
    if not 1 <= args.max_hits <= 200 or not 1 <= args.timeout_seconds <= 600:
        parser.error("max-hits must be 1..200 and timeout-seconds 1..600")
    try:
        import websocket  # pip install websocket-client
    except ImportError as exc:
        raise SystemExit("Install websocket-client: python -m pip install websocket-client") from exc

    ws = websocket.create_connection(args.url, timeout=10,
                                     subprotocols=["debugger.ppsspp.org"])
    try:
        print("Connected. Perform attacks/guard/command transitions with the game running.")
        result = trace(Debugger(ws), args.max_hits, args.timeout_seconds)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"Recorded {len(result['records'])} safe read-only breakpoint snapshots to {args.output}")
        return 0
    finally:
        ws.close()


if __name__ == "__main__":
    raise SystemExit(main())
