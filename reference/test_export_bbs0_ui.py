import importlib.util
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from export_bbs0_ui import ctd_layouts, export


class ExportBbs0UiTests(unittest.TestCase):
    def synthetic_file(self, path):
        b = bytearray(5 * 2048)
        b[0:4] = b"bbsa"
        struct.pack_into("<I", b, 4, 6)
        struct.pack_into("<H", b, 0x1A, 1)
        struct.pack_into("<HH", b, 8, 3, 0)
        start = 2048
        b[start:start + 4] = b"ARC\x00"
        struct.pack_into("<HH", b, start + 4, 1, 2)
        struct.pack_into("<IIII16s", b, start + 16, 0, 0x60, 0x40, 0, b"hud.l2d\x00")
        struct.pack_into("<IIII16s", b, start + 48, 0x12345678, 0, 0, 0, b"dialog.ctd\x00")
        l2d = start + 0x60
        b[l2d:l2d + 4] = b"L2D@"
        struct.pack_into("<I", b, l2d + 0x2C, 0x40)
        ctd = 4096
        b[ctd:ctd + 4] = b"@CTD"
        struct.pack_into("<I", b, ctd + 4, 1)
        struct.pack_into("<HHIII", b, ctd + 0x0C, 1, 0, 0x20, 0x20, 0x40)
        struct.pack_into("<hhhhBBhhhhhhh", b, ctd + 0x20,
                         1, 2, 300, 120, 2, 5, 6, 16, 0, 0, 10, 20, 0)
        path.write_bytes(b)

    def test_exports_index_assets_ctd_layouts_without_large_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            source, dest = Path(directory) / "BBS0.DAT", Path(directory) / "small.zip"
            self.synthetic_file(source)
            result = export(source, dest)
            self.assertEqual(1, result["arc_count"])
            self.assertEqual(1, len(result["assets"]))
            self.assertEqual(1, len(result["ctd_layout_metadata"]))
            self.assertEqual(1, result["ctd_layout_metadata"][0]["layouts"][0]["x"])
            with zipfile.ZipFile(dest) as archive:
                self.assertIn("bbs0/index.bin", archive.namelist())
                self.assertIn("bbs0/assets/00000860_hud.l2d", archive.namelist())
                self.assertIn("bbs0/ui_manifest.json", archive.namelist())
                self.assertEqual(b"bbsa", archive.read("bbs0/index.bin")[:4])
                self.assertEqual(1, json.loads(archive.read("bbs0/ui_manifest.json"))["exported_count"])

    def test_metadata_only_does_not_include_layout_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            source, dest = Path(directory) / "BBS0.DAT", Path(directory) / "index.zip"
            self.synthetic_file(source)
            result = export(source, dest, metadata_only=True)
            self.assertEqual(0, result["exported_count"])
            with zipfile.ZipFile(dest) as archive:
                self.assertEqual({"bbs0/index.bin", "bbs0/ui_manifest.json"}, set(archive.namelist()))

    def test_rejects_bad_signature_and_preexisting_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, dest = Path(directory) / "BBS0.DAT", Path(directory) / "index.zip"
            source.write_bytes(b"bad" * 3000)
            with self.assertRaises(ValueError):
                export(source, dest)
            self.synthetic_file(source)
            dest.write_bytes(b"preserve me")
            with self.assertRaises(FileExistsError):
                export(source, dest)
            self.assertEqual(b"preserve me", dest.read_bytes())


if __name__ == "__main__":
    unittest.main()
