"""Synthetic ABI regression tests; these do not validate gameplay."""
import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('patcher', Path(__file__).with_name('bbsfm_psp_bbs_stage5_patcher.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class CaptureMachine:
    """Small interpreter for the capture wrapper's integer instruction subset.

    The fake original callee deliberately uses all four O32 argument slots and
    destroys caller-saved registers. Controller bytes live outside the stack.
    """
    def __init__(self, result, pointer=0x09000000):
        self.r = [0] * 32
        self.r[4], self.r[5] = pointer, 1
        self.r[16], self.r[29], self.r[31] = 0x12345678, 0x09100000, 0x08816690
        self.initial = self.r[:]
        self.mem = {pointer + 10: 17, pointer + 11: 239}
        self.pc, self.pending, self.result = p.S2_CAPTURE_PAD_VA, None, result

    def load(self, address, size):
        return sum(self.mem.get(address + i, 0) << (8 * i) for i in range(size))

    def store(self, address, value, size):
        for i in range(size):
            self.mem[address + i] = (value >> (8 * i)) & 255

    def run(self):
        for _ in range(100):
            if self.pc == self.initial[31]:
                return
            if self.pc == 0x08B16D38:
                for offset in range(0, 16, 4):
                    self.store(self.r[29] + offset, 0, 4)
                for reg in [3, *range(4, 16), 24, 25]:
                    self.r[reg] = 0xDEAD0000 + reg
                self.r[2] = self.result & 0xffffffff
                self.pc = self.r[31]
                continue
            offset = self.pc - p.S2_VA
            if offset < 0 or offset + 4 > len(p.S2_BLOB):
                raise AssertionError(f'escaped capture code at {self.pc:08x}')
            w = struct.unpack_from('<I', p.S2_BLOB, offset)[0]
            op, rs, rt, rd = w >> 26, (w >> 21) & 31, (w >> 16) & 31, (w >> 11) & 31
            imm = w & 65535
            signed = imm - 65536 if imm & 32768 else imm
            previous, self.pending = self.pending, None
            address = (self.r[rs] + signed) & 0xffffffff
            if w == 0:
                pass
            elif op == 0 and w & 63 == 8:
                self.pending = self.r[rs]
            elif op == 0 and w & 63 in (33, 37):
                self.r[rd] = (self.r[rs] + self.r[rt] if w & 63 == 33 else self.r[rs] | self.r[rt]) & 0xffffffff
            elif op == 9:
                self.r[rt] = address
            elif op == 15:
                self.r[rt] = imm << 16
            elif op in (35, 36):
                self.r[rt] = self.load(address, 4 if op == 35 else 1)
            elif op in (40, 43):
                self.store(address, self.r[rt], 1 if op == 40 else 4)
            elif op == 3:
                self.r[31] = self.pc + 8
                self.pending = ((self.pc + 4) & 0xf0000000) | ((w & 0x3ffffff) << 2)
            elif op == 4:
                if self.r[rs] == self.r[rt]:
                    self.pending = self.pc + 4 + signed * 4
            elif op in (6, 7):
                value = self.r[rs] - 0x100000000 if self.r[rs] & 0x80000000 else self.r[rs]
                if (value <= 0 if op == 6 else value > 0):
                    self.pending = self.pc + 4 + signed * 4
            else:
                raise AssertionError(f'unsupported instruction {w:08x}')
            self.r[0] = 0
            self.pc = previous if previous is not None else self.pc + 4
        raise AssertionError('capture did not return')


class RuntimeRegressionTest(unittest.TestCase):
    def test_null_buffer_and_failed_reads_publish_neutral_input(self):
        for result, pointer in [(0, 0x09000000), (-1, 0x09000000), (1, 0)]:
            m = CaptureMachine(result, pointer)
            m.run()
            for helper in [p.S2_RIGHT_X_VA, p.S2_RIGHT_Y_VA]:
                words = struct.unpack_from('<III', p.S2_BLOB, helper - p.S2_VA)
                load = next(w for w in words if w >> 26 == 36)
                low = load & 65535
                address = ((words[0] & 65535) << 16) + (low - 65536 if low & 32768 else low)
                self.assertEqual(128, m.load(address, 1))

    def test_all_static_transfers_stay_in_executable_code(self):
        regions = [(p.S2_BLOB, p.S2_VA, p.S2_CODE_SIZE),
                   (p.S4_BLOB, p.S4_VA, p.S4_TELEMETRY_VA - p.S4_VA - 4),
                   (p.S5_WRAPPER_BLOB, p.S5_WRAPPER_VA, len(p.S5_WRAPPER_BLOB))]
        external = {0x08B16D38, p.S4_POST_INPUT_VA, 0x08816908}
        for blob, base, code_size in regions:
            self.assertEqual(0, base % 4)
            self.assertEqual(0, code_size % 4)
            for offset in range(0, code_size, 4):
                word = struct.unpack_from('<I', blob, offset)[0]
                op = word >> 26
                branch = op in (1, 4, 5, 6, 7, 20, 21, 22, 23) or (op == 17 and (word >> 21) & 31 == 8)
                transfer = branch or op in (2, 3) or (op == 0 and word & 63 in (8, 9))
                if branch:
                    imm = word & 65535
                    target = offset + 4 + (imm - 65536 if imm & 32768 else imm) * 4
                    self.assertTrue(0 <= target < code_size, f'{base + offset:08x} targets data')
                if op in (2, 3):
                    target = ((base + offset + 4) & 0xf0000000) | ((word & 0x3ffffff) << 2)
                    self.assertTrue(base <= target < base + code_size or target in external)
                if transfer:
                    self.assertLess(offset + 4, code_size)
                    delay = struct.unpack_from('<I', blob, offset + 4)[0]
                    self.assertNotIn(delay >> 26, (1, 2, 3, 4, 5, 6, 7, 20, 21, 22, 23))
                    self.assertFalse(delay >> 26 == 0 and delay & 63 in (8, 9))

    def test_advanced_configuration_is_rejected_at_python_entry(self):
        for cfg, passives in [(0x3d, False), (0x3e, False), (0x7c, False), (0x3c, True)]:
            with self.assertRaisesRegex(ValueError, 'runtime evidence'):
                p.add_stage5(bytearray(p.S4_FILE_OFF), cfg, 4.5, passives)

    def test_kotlin_is_generated_from_same_payloads(self):
        import sync_payloads
        source = sync_payloads.TARGET.read_text()
        self.assertEqual(source, sync_payloads.synchronized(source))

    def test_release_gate_rejects_missing_or_stale_gameplay_evidence(self):
        import check_runtime_evidence as gate
        import json
        evidence = json.loads((gate.Path(__file__).resolve().parents[1] / 'docs/runtime-evidence.json').read_text())
        evidence['patched_eboot_sha256'] = None
        with self.assertRaises(ValueError):
            gate.validate(evidence)
        # Synthetic gate acceptance exercises schema validation only.
        evidence.update(patched_eboot_sha256='a' * 64, ppsspp_version='synthetic',
                        device='synthetic', tested_at='synthetic', evidence_reference='synthetic')
        for character in evidence['cases'].values():
            for case in character.values():
                case.update(result='pass', evidence_reference='synthetic')
        gate.validate(evidence)
        evidence['profile_sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'current default payload'):
            gate.validate(evidence)

    def test_nested_callee_cannot_corrupt_return_or_buffer(self):
        m = CaptureMachine(1)
        m.run()
        self.assertEqual(m.initial[29], m.r[29])
        self.assertEqual(m.initial[16], m.r[16])
        self.assertEqual(1, m.r[2])
        # Decode the helper load address rather than assuming a data offset.
        for helper, expected in [(p.S2_RIGHT_X_VA, 17), (p.S2_RIGHT_Y_VA, 239)]:
            words = struct.unpack_from('<III', p.S2_BLOB, helper - p.S2_VA)
            load = next(w for w in words if w >> 26 == 36)
            low = load & 65535
            address = ((words[0] & 65535) << 16) + (low - 65536 if low & 32768 else low)
            self.assertEqual(expected, m.load(address, 1))

    def test_failed_read_returns_original_status(self):
        for result in [0, -1]:
            m = CaptureMachine(result)
            m.run()
            self.assertEqual(result & 0xffffffff, m.r[2])
            self.assertEqual(m.initial[29], m.r[29])


if __name__ == '__main__':
    unittest.main()
