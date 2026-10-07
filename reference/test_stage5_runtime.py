"""Static/synthetic ABI regression tests; these do not validate gameplay."""
import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('patcher', Path(__file__).with_name('bbsfm_psp_bbs_stage5_patcher.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class RuntimeRegressionTest(unittest.TestCase):
    def test_right_analog_helpers_poll_controller_directly(self):
        self.assertEqual(168, len(p.S2_BLOB))
        self.assertEqual(p.S2_VA, p.S2_RIGHT_X_VA)
        self.assertEqual(p.S2_VA + 84, p.S2_RIGHT_Y_VA)
        for helper, reserved in [(p.S2_RIGHT_X_VA, 0x1A), (p.S2_RIGHT_Y_VA, 0x1B)]:
            off = helper - p.S2_VA
            words = struct.unpack_from('<' + 'I' * 21, p.S2_BLOB, off)
            self.assertEqual(0x27BDFFD0, words[0])
            self.assertEqual(0xAFBF002C, words[1])
            self.assertEqual(0x27A40010, words[2])
            self.assertEqual(0x34050001, words[3])
            self.assertEqual(0x0E2C5B4E, words[4])
            self.assertEqual(0x93A80000 | reserved, words[8])
            self.assertEqual(0x03E00008, words[19])
            self.assertEqual(0x27BD0030, words[20])

    def test_camera_patch_set_does_not_replace_main_controller_poll(self):
        addresses = {va for va, _expected, _replacement, _desc in p.CAMERA_PATCHES}
        self.assertNotIn(0x08816688, addresses)
        self.assertEqual(
            {0x08940FEC, 0x0898F68C, 0x0898F850, 0x0898F69C,
             0x0898F6DC, 0x0898F860, 0x0898F8A0},
            addresses,
        )

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
        evidence.update(patched_eboot_sha256='a' * 64, ppsspp_version='synthetic',
                        device='synthetic', tested_at='synthetic', evidence_reference='synthetic')
        evidence['profile_sha256'] = gate.profile_sha256()
        for character in evidence['cases'].values():
            for case in character.values():
                case.update(result='pass', evidence_reference='synthetic')
        gate.validate(evidence)
        evidence['profile_sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'current default payload'):
            gate.validate(evidence)


if __name__ == '__main__':
    unittest.main()
