"""Static/synthetic ABI regression tests; these do not validate gameplay."""
import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('patcher', Path(__file__).with_name('bbsfm_psp_bbs_stage5_patcher.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class RuntimeRegressionTest(unittest.TestCase):
    def test_supported_right_stick_patch_stays_out_of_overlay_arena(self):
        word_addresses = {va for va, _expected, _replacement, _desc in p.RIGHT_STICK_WORD_PATCHES}
        byte_addresses = {va for va, _expected, _replacement, _desc in p.RIGHT_STICK_BYTE_PATCHES}
        self.assertTrue(word_addresses)
        self.assertFalse(byte_addresses)
        self.assertTrue(all(va < 0x08B6EE7C for va in word_addresses))
        self.assertNotIn(0x08816688, word_addresses)
        self.assertNotIn(p.S2_VA, word_addresses)
        self.assertEqual(0x08B4199A, p.RIGHT_STICK_X_BYTE_VA)
        self.assertEqual(0x08B4199B, p.RIGHT_STICK_Y_BYTE_VA)

    def test_right_stick_axes_are_centered_and_inverted_in_place(self):
        replacements = {va: replacement for va, _expected, replacement, _desc
                        in p.RIGHT_STICK_WORD_PATCHES}

        # Final CtrlData analog[1] is captured as one halfword and both sign
        # bits are flipped. X is negated; Y keeps the native centered sign.
        self.assertEqual(0x9545FFFA, replacements[0x0881683C])  # lhu a1,-6(t2)
        self.assertEqual(0x38A58080, replacements[0x08816840])  # xori a1,a1,0x8080
        self.assertEqual(0xA485199A, replacements[0x0881684C])  # sh a1,0x199a(a0)
        self.assertNotIn(0x08816854, replacements)

        self.assertEqual(0x8042199A, replacements[0x08816304])  # lb v0,rightX
        self.assertEqual(0x00021023, replacements[0x0881630C])  # subu v0,zero,v0
        self.assertEqual(0x8042199B, replacements[0x08816320])  # lb v0,rightY
        self.assertEqual(0x00000000, replacements[0x08816328])  # preserve centered Y

        def centered(raw):
            stored = raw ^ 0x80
            return stored if stored < 0x80 else stored - 0x100

        def transformed_x(raw):
            return -centered(raw)

        def transformed_y(raw):
            return centered(raw)

        self.assertEqual(0, transformed_x(0x80))
        self.assertEqual(0, transformed_y(0x80))
        self.assertGreater(transformed_x(0x00), 0)
        self.assertLess(transformed_x(0xFF), 0)
        self.assertLess(transformed_y(0x00), 0)
        self.assertGreater(transformed_y(0xFF), 0)

    def test_camera_geometry_uses_resident_native_mode_vectors(self):
        self.assertLess(p.CAMERA_TABLE_VA, 0x08B6EE7C)
        for address in (
            p.CAMERA_FREE_HEIGHT_VA,
            p.CAMERA_FREE_DISTANCE_VA,
            p.CAMERA_LOCK_HEIGHT_VA,
            p.CAMERA_LOCK_DISTANCE_VA,
        ):
            self.assertLess(address, 0x08B6EE7C)

        data = bytearray(p.SUPPORTED_SIZE)
        p.p32(data, p.foff(p.CAMERA_TABLE_VA), p.CAMERA_TABLE_SIGNATURE)
        p.p32(data, p.foff(p.CAMERA_MODE1_RECORD_VA), 1)
        p.p32(data, p.foff(p.CAMERA_MODE2_RECORD_VA), 2)
        for va, value in (
            (p.CAMERA_FREE_HEIGHT_VA, p.CAMERA_FREE_HEIGHT_ORIG),
            (p.CAMERA_FREE_DISTANCE_VA, p.CAMERA_FREE_DISTANCE_ORIG),
            (p.CAMERA_LOCK_HEIGHT_VA, p.CAMERA_LOCK_HEIGHT_ORIG),
            (p.CAMERA_LOCK_DISTANCE_VA, p.CAMERA_LOCK_DISTANCE_ORIG),
            (p.CAMERA_MODE1_RECORD_VA + 0x1C, 1.0),
            (p.CAMERA_MODE2_RECORD_VA + 0x1C, 1.0),
        ):
            p.pf32(data, p.foff(va), value)

        p.verify_camera_geometry_source(data)
        p.apply_camera_geometry(
            data,
            camera_distance_enabled=True,
            camera_distance=4.5,
            camera_height_enabled=True,
            camera_height=1.0,
        )

        self.assertAlmostEqual(-4.5, p.f32(data, p.foff(p.CAMERA_FREE_DISTANCE_VA)))
        self.assertAlmostEqual(-4.5, p.f32(data, p.foff(p.CAMERA_LOCK_DISTANCE_VA)))
        self.assertAlmostEqual(1.0, p.f32(data, p.foff(p.CAMERA_FREE_HEIGHT_VA)))
        self.assertAlmostEqual(1.0, p.f32(data, p.foff(p.CAMERA_LOCK_HEIGHT_VA)))
        self.assertEqual(p.CAMERA_TABLE_SIGNATURE, p.u32(data, p.foff(p.CAMERA_TABLE_VA)))
        self.assertEqual(1, p.u32(data, p.foff(p.CAMERA_MODE1_RECORD_VA)))
        self.assertEqual(2, p.u32(data, p.foff(p.CAMERA_MODE2_RECORD_VA)))

    def test_camera_patch_preserves_native_jals_and_tags_delay_slots(self):
        replacements = {va: replacement for va, _expected, replacement, _desc
                        in p.RIGHT_STICK_WORD_PATCHES}
        self.assertEqual(0x34045253, p.RIGHT_STICK_SELECTOR)
        self.assertEqual(p.RIGHT_STICK_SELECTOR, replacements[0x0898F6A0])
        self.assertEqual(p.RIGHT_STICK_SELECTOR, replacements[0x0898F6E0])
        self.assertEqual(p.RIGHT_STICK_SELECTOR, replacements[0x0898F864])
        self.assertEqual(p.RIGHT_STICK_SELECTOR, replacements[0x0898F8A4])

        # The supported path does not replace the controller poll or the native
        # camera JALs. Those calls are deliberately absent from the patch map.
        for address in (0x08816688, 0x0898F69C, 0x0898F6DC, 0x0898F860, 0x0898F8A0):
            self.assertNotIn(address, replacements)

    def test_legacy_overlay_helpers_are_research_only(self):
        # Retained solely for historical Python/Kotlin payload parity; the
        # supported right-stick path no longer writes this blob.
        self.assertEqual(168, len(p.S2_BLOB))
        self.assertEqual(0x08B6EE80, p.S2_VA)
        self.assertGreaterEqual(p.S2_VA, 0x08B6EE7C)

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
        with self.assertRaisesRegex(ValueError, 'resident right-stick profile'):
            gate.validate(evidence)


if __name__ == '__main__':
    unittest.main()
