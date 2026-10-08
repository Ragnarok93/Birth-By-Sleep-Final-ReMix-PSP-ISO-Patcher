import hashlib
import struct
import tempfile
import unittest
from pathlib import Path

from audit_bbs_ui import scan_dat


class TestBbsUiAuditor(unittest.TestCase):
    def test_scans_sector_aligned_arc_and_valid_l2d_without_payload_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "BBS1.DAT"
            data = bytearray(4096)
            data[0x800:0x804] = b"ARC\x00"
            struct.pack_into("<HH", data, 0x804, 1, 2)
            arc_entry = 0x810
            struct.pack_into("<IIII16s", data, arc_entry, 0, 0x50, 0x40, 0,
                             b"test.l2d\x00")
            struct.pack_into("<IIII16s", data, arc_entry + 32, 0x1234, 0, 0, 0,
                             b"CT00000.ctd\x00")
            start = 0x850
            data[start:start + 4] = b"L2D@"
            struct.pack_into("<I", data, start + 0x2c, 0x40)
            path.write_bytes(data)
            report = scan_dat(path)
            self.assertEqual(1, report["arc_count"])
            self.assertEqual(2, report["arc_entry_count"])
            self.assertEqual(1, report["l2d_count"])
            self.assertEqual(1, report["ctd_links"])
            self.assertEqual(0, report["ctd_embedded"])
            asset = report["l2d"][0]
            self.assertEqual("test.l2d", asset["name"])
            self.assertEqual(start, asset["offset"])
            self.assertEqual(hashlib.sha256(data[start:start + 0x40]).hexdigest(), asset["sha256"])

    def test_rejects_invalid_arc_entry_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "BBS2.DAT"
            data = bytearray(4096)
            data[0x800:0x804] = b"ARC\x00"
            struct.pack_into("<HH", data, 0x804, 1, 1)
            struct.pack_into("<IIII16s", data, 0x810, 0, 0x50, 99999, 0,
                             b"bad.l2d\x00")
            path.write_bytes(data)
            self.assertEqual(0, scan_dat(path)["arc_count"])


if __name__ == "__main__":
    unittest.main()
