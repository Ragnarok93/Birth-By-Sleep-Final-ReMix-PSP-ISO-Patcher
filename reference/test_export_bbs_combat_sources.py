"""Deterministic stdlib regression tests for the one-pass BBSA source bundle."""
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from export_bbs_combat_sources import export, parse_index, iso_record, SECTOR


def put16(buf, pos, value):
    struct.pack_into("<H", buf, pos, value)


def put32(buf, pos, value):
    struct.pack_into("<I", buf, pos, value)


def fixture_bbs0():
    data = bytearray(18 * SECTOR)
    data[:4] = b"bbsa"
    put32(data, 4, 6)
    put16(data, 0x1A, 2)  # BBS0 header is two sectors
    put16(data, 0x0E, 4)
    put32(data, 0x14, 0x100)
    put32(data, 0x1C, 310)
    for i, val in enumerate((200, 230, 260, 290)):
        put32(data, 0x20 + 4 * i, val)

    # Two valid Lua entries (one multi-sector), streaming sentinel,
    # and another non-Lua path that must not be included.
    entries = [
        (0x040C749E, (5 << 12) | 2, 0xC0000000),
        (0x1A322A80, (7 << 12) | 1, 0xC0000000),
        (0xCAFEBABE, (8 << 12) | 0xFFF, 0xC0000000),
        (0xF5BE1086, (9 << 12) | 2, 0x4D4D4947),
    ]
    for i, entry in enumerate(entries):
        struct.pack_into("<III", data, 0x100 + i * 12, *entry)
    for physical_sector, name in ((7, b"g01"), (9, b"hit")):
        at = physical_sector * SECTOR
        data[at:at + 12] = b"\x1bLua\x51\x00\x01\x04\x04\x04\x04\x00"
        data[at + 12:at + 12 + len(name)] = name
    return data


def iso_directory_record(name, sector, length, directory=False):
    name_bytes = name if isinstance(name, bytes) else name.encode("ascii")
    size = (33 + len(name_bytes) + 1) & -2
    value = bytearray(size)
    value[0] = size
    put32(value, 2, sector)
    struct.pack_into(">I", value, 6, sector)
    put32(value, 10, length)
    struct.pack_into(">I", value, 14, length)
    value[25] = 2 if directory else 0
    value[32] = len(name_bytes)
    value[33:33 + len(name_bytes)] = name_bytes
    return bytes(value)


def synthetic_iso(bbs0):
    iso = bytearray(96 * SECTOR)
    pvd = bytearray(SECTOR)
    pvd[0] = 1
    pvd[1:6] = b"CD001"
    pvd[6] = 1
    put16(pvd, 128, SECTOR)
    pvd[156:156 + 34] = iso_directory_record(b"\x00", 20, SECTOR, True)
    iso[16 * SECTOR:17 * SECTOR] = pvd
    def directory(sector, rows):
        data = b"".join(rows)
        iso[sector * SECTOR:sector * SECTOR + len(data)] = data
    directory(20, [iso_directory_record("PSP_GAME", 21, SECTOR, True)])
    directory(21, [iso_directory_record("USRDIR", 22, SECTOR, True),
                   iso_directory_record("SYSDIR", 23, SECTOR, True)])
    directory(22, [iso_directory_record("BBS0.DAT;1", 30, len(bbs0), False)])
    directory(23, [iso_directory_record("EBOOT.BIN;1", 24, 64, False)])
    iso[24 * SECTOR:24 * SECTOR + 64] = b"\x7fELF" + bytes(60)
    iso[30 * SECTOR:30 * SECTOR + len(bbs0)] = bbs0
    return iso


class CombatSourceExporterTests(unittest.TestCase):
    def test_one_pass_exports_all_indexed_valid_lua_files_without_source_writes(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / "BBS0.DAT"
            target = root / "evidence.zip"
            original = fixture_bbs0()
            source.write_bytes(original)
            manifest = export(source, target)
            self.assertEqual(4, manifest["bbsa_12byte_directory_records"])
            self.assertEqual(3, manifest["lua_category_records"])
            self.assertEqual(2, manifest["exported_count"])
            self.assertEqual(1, manifest["skipped_count"])
            self.assertEqual(3 * SECTOR, manifest["exported_allocated_bytes"])
            self.assertEqual(original, source.read_bytes())
            self.assertFalse(manifest["supported_eboot_verified"])
            with zipfile.ZipFile(target) as archive:
                names = archive.namelist()
                self.assertEqual(4, len(names))
                self.assertIn("bbs0/index.bin", names)
                self.assertIn("lua/040C749E_at_000100.lub", names)
                self.assertIn("lua/1A322A80_at_00010C.lub", names)
                payload = archive.read("lua/1A322A80_at_00010C.lub")
                self.assertEqual(b"\x1bLua\x51\x00", payload[:6])
                manifest2 = json.loads(archive.read("manifest.json"))
                self.assertEqual("B11CD00",
                    next(x["filename_hint"] for x in manifest2["extracted"]
                         if x["filename_hash"] == "1A322A80"))
                self.assertEqual("streaming sentinel/zero sectors",
                    manifest2["skipped"][0]["reason"])

    def test_malformed_index_is_rejected_without_creating_output(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            source, target = d / "BBS0.DAT", d / "out.zip"
            b = fixture_bbs0()
            put32(b, 0x14, len(b) - 1)
            source.write_bytes(b)
            with self.assertRaisesRegex(ValueError, "directory table"):
                export(source, target)
            self.assertFalse(target.exists())

    def test_non_lua_and_beyond_bbs0_extents_are_skipped_with_reason(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            source, target = d / "BBS0.DAT", d / "out.zip"
            b = fixture_bbs0()
            put32(b, 0x100 + 12 + 4, (199 << 12) | 2)
            source.write_bytes(b)
            result = export(source, target)
            self.assertEqual(1, result["exported_count"])
            self.assertTrue(any("not wholly stored" in x["reason"]
                                for x in result["skipped"]))
            with self.assertRaises(FileExistsError):
                export(source, target)

    def test_iso_path_is_parsed_but_unknown_eboot_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            source, target = d / "game.iso", d / "out.zip"
            source.write_bytes(synthetic_iso(fixture_bbs0()))
            with self.assertRaisesRegex(ValueError, "EBOOT SHA256"):
                export(source, target)
            self.assertFalse(target.exists())

    def test_bad_magic_and_unverified_archive_header_fail_closed(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            source, target = d / "BBS0.DAT", d / "out.zip"
            b = fixture_bbs0()
            b[0:4] = b"XXXX"
            source.write_bytes(b)
            with self.assertRaisesRegex(ValueError, "neither BBS0.DAT nor"):
                export(source, target)
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
