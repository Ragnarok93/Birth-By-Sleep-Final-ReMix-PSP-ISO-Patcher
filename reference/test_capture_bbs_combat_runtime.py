"""Offline tests for read-only PPSSPP combat tracing; no ISO/game required."""
import base64
import importlib.util
import json
import struct
import unittest
from collections import deque
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "combat_trace", Path(__file__).with_name("capture_bbs_combat_runtime.py")
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class FakeWebSocket:
    def __init__(self, game="ULJM05775", existing=()):
        self.game = game
        self.existing = existing
        self.sent = []
        self.incoming = deque()
        self.added = 0
        self.memory = bytearray(mod.READ_SIZE)
        struct.pack_into("<I", self.memory, 0x220, 0x15)
        struct.pack_into("<H", self.memory, 0x22C, 0x33)
        struct.pack_into("<I", self.memory, 0x238, 0x1000)
        struct.pack_into("<I", self.memory, 0x23C, 0x10000000)
        struct.pack_into("<I", self.memory, 0x268, 0x42)

    def settimeout(self, value):
        pass

    def send(self, payload):
        packet = json.loads(payload)
        self.sent.append(packet)
        event = packet["event"]
        ticket = packet.get("ticket")
        if ticket is None:
            return
        reply = {"event": event, "ticket": ticket}
        if event == "game.status":
            reply["game"] = {"id": self.game}
        elif event == "cpu.breakpoint.list":
            reply["breakpoints"] = [{"address": address} for address in self.existing]
        elif event == "cpu.getAllRegs":
            reply["categories"] = [{
                "id": 0,
                "registerNames": ["pc", "s0", "a0", "ra"],
                "uintValues": [0x08955F10, 0x08900000, 0x0, 0x08800000],
            }]
        elif event == "memory.read":
            reply["base64"] = base64.b64encode(self.memory).decode("ascii")
        self.incoming.append(reply)
        if event == "cpu.breakpoint.add":
            self.added += 1
            if self.added == 2:
                self.incoming.append({
                    "event": "cpu.stepping", "ticks": 123456,
                    "hit": {"kind": "exec", "address": 0x08955F10,
                            "paused": True, "hits": 1}
                })

    def recv(self):
        if not self.incoming:
            raise TimeoutError("empty fake response queue")
        return json.dumps(self.incoming.popleft())


class ReadOnlyCombatTraceTests(unittest.TestCase):
    def test_state_fields_use_little_endian_offsets(self):
        fake = FakeWebSocket()
        fields = mod.decode_fields(base64.b64encode(fake.memory).decode("ascii"))
        self.assertEqual(fields["state"], 0x15)
        self.assertEqual(fields["substate"], 0x33)
        self.assertEqual(fields["trigger_flags"], 0x1000)
        self.assertEqual(fields["attack_status"], 0x10000000)
        self.assertEqual(fields["command_kind"], 0x42)
        self.assertNotIn("hit_landed", fields)

    def test_pointer_bounds_fail_closed(self):
        self.assertTrue(mod.valid_player_address(0x08900000))
        for bad in (0, 1, 0x08000001, 0x0A000000, 0x09FFFFFE):
            self.assertFalse(mod.valid_player_address(bad))

    def test_snapshot_and_breakpoint_cleanup(self):
        fake = FakeWebSocket()
        result = mod.trace(mod.Debugger(fake), max_hits=1, seconds=5)
        self.assertEqual(len(result["records"]), 1)
        record = result["records"][0]
        self.assertEqual(record["site"], "cancel_consumer")
        self.assertEqual(record["player_pointer"], 0x08900000)
        self.assertEqual(record["fields"]["trigger_flags"], 0x1000)
        actions = [packet["event"] for packet in fake.sent]
        self.assertEqual(actions.count("cpu.breakpoint.add"), 2)
        self.assertEqual(actions.count("cpu.breakpoint.remove"), 2)
        self.assertIn("cpu.resume", actions)
        self.assertFalse(any(a.startswith("memory.write") or a == "cpu.setReg" for a in actions))

    def test_wrong_game_refused_without_breakpoints(self):
        fake = FakeWebSocket(game="ULUS00000")
        with self.assertRaisesRegex(RuntimeError, "Expected ULJM05775"):
            mod.trace(mod.Debugger(fake), max_hits=1, seconds=5)
        self.assertFalse(any(x["event"] == "cpu.breakpoint.add" for x in fake.sent))

    def test_preexisting_breakpoint_not_replaced(self):
        fake = FakeWebSocket(existing=(0x08955F10,))
        with self.assertRaisesRegex(RuntimeError, "Existing breakpoints"):
            mod.trace(mod.Debugger(fake), max_hits=1, seconds=5)
        self.assertFalse(any(x["event"] == "cpu.breakpoint.add" for x in fake.sent))

    def test_malformed_register_response_is_rejected(self):
        with self.assertRaises(ValueError):
            mod.extract_gprs({"categories": [{"id": 0, "registerNames": ["a0"],
                                                 "uintValues": []}]})


if __name__ == "__main__":
    unittest.main()
