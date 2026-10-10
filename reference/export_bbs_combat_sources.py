#!/usr/bin/env python3
"""One-pass, read-only PSP Lua source evidence exporter.

Export every structurally indexed, bounded Lua-category (.lub) resource
from an ORIGINAL ULJM-05775 ISO, or from extracted BBS0.DAT. No sparse
sampling, no EBOOT hooks, no ISO modification, no PSP memory assumptions.

Usage:
  python3 reference/export_bbs_combat_sources.py "BBS_FinalMix.iso" -o bbs-combat-sources.zip
  python3 reference/export_bbs_combat_sources.py BBS0.DAT -o bbs-combat-sources.zip

Uses only the Python standard library. The archive contains the BBS0
index, Lua allocated bytes (including sector padding), and a JSON manifest
with exact source offsets, hashes, and records skipped for safety.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

SECTOR = 2048
MAX_INDEX_BYTES = 4 * 1024 * 1024
MAX_DIRECTORY_RECORDS = 32768
MAX_SINGLE_SCRIPT = 128 * 1024
MAX_TOTAL_SCRIPTS = 32 * 1024 * 1024
LUA_DIRECTORY_ID = 0xC0000000
SUPPORTED_EBOOT_SHA256 = "8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7"
HINTS = {
    "B11CD00": 0x1A322A80, "B11SB00": 0x4EA601AD,
    "G31VS00": 0x7B6CB674, "G13HE00": 0xE409BB71,
    "G14SW00": 0xCE93C0E1, "TERRA": 0x41158217,
    "VENTUS": 0xDED69D4D, "AQUA": 0xA9857CD4,
    "G01": 0x040C749E,
}
NAME_BY_HASH = {v: k for k, v in HINTS.items()}


def u16(blob, offset):
    return struct.unpack_from("<H", blob, offset)[0]


def u32(blob, offset):
    return struct.unpack_from("<I", blob, offset)[0]


def sha256(blob):
    return hashlib.sha256(blob).hexdigest()


def read_at(handle, file_size, offset, length):
    if offset < 0 or length < 0 or offset > file_size or length > file_size - offset:
        raise ValueError("Read exceeds source file bounds.")
    handle.seek(offset)
    result = handle.read(length)
    if len(result) != length:
        raise ValueError("Short read from source file.")
    return result


def iso_record(directory, at):
    size = directory[at]
    if size < 34 or at + size > len(directory):
        raise ValueError("Malformed ISO9660 directory record.")
    sector = u32(directory, at + 2)
    length = u32(directory, at + 10)
    if sector != struct.unpack_from(">I", directory, at + 6)[0] or (
        length != struct.unpack_from(">I", directory, at + 14)[0]
    ):
        raise ValueError("ISO9660 big/little-endian record mismatch.")
    count = directory[at + 32]
    if 33 + count > size:
        raise ValueError("Malformed ISO9660 path identifier.")
    raw = directory[at + 33:at + 33 + count]
    name = raw.decode("ascii", errors="replace").split(";", 1)[0].upper()
    return {"name": name, "sector": sector, "length": length,
            "directory": bool(directory[at + 25] & 2)}


def find_iso_file(handle, file_size, path):
    pvd = read_at(handle, file_size, 16 * SECTOR, SECTOR)
    if pvd[0] != 1 or pvd[1:6] != b"CD001" or u16(pvd, 128) != SECTOR:
        raise ValueError("Unsupported ISO9660 primary volume descriptor.")
    current = iso_record(pvd, 156)
    for part in path.split("/"):
        if not current["directory"] or current["length"] > 16 * 1024 * 1024:
            raise ValueError("Invalid or oversized ISO directory.")
        base = current["sector"] * SECTOR
        buf = read_at(handle, file_size, base, current["length"])
        pos = 0
        found = []
        while pos < len(buf):
            record_size = buf[pos]
            if record_size == 0:
                pos = ((pos // SECTOR) + 1) * SECTOR
                continue
            rec = iso_record(buf, pos)
            if rec["name"] == part:
                found.append(rec)
            pos += record_size
        if len(found) != 1:
            raise ValueError("ISO path missing/ambiguous: " + path)
        current = found[0]
    if current["directory"]:
        raise ValueError("Expected an ISO file, found a directory.")
    base = current["sector"] * SECTOR
    if base > file_size or current["length"] > file_size - base:
        raise ValueError("ISO data extent is outside the source.")
    return base, current["length"]


def locate_bbs0(handle, file_size):
    first = read_at(handle, file_size, 0, 4)
    if first == b"bbsa":
        return 0, file_size, "BBS0.DAT direct", None
    if first != b"\x00\x00\x00\x00":
        # Do not assume an arbitrary binary file is an ISO.
        raise ValueError("Input is neither BBS0.DAT nor an ISO9660 image.")
    bbs0_base, bbs0_length = find_iso_file(
        handle, file_size, "PSP_GAME/USRDIR/BBS0.DAT")
    eboot_base, eboot_len = find_iso_file(
        handle, file_size, "PSP_GAME/SYSDIR/EBOOT.BIN")
    if eboot_len > 8 * 1024 * 1024:
        raise ValueError("Unreasonably large EBOOT; cannot fingerprint game variant.")
    eboot_hash = sha256(read_at(handle, file_size, eboot_base, eboot_len))
    if eboot_hash != SUPPORTED_EBOOT_SHA256:
        raise ValueError(
            "This ISO's EBOOT SHA256 is not the supported unmodified source: " +
            eboot_hash + " (no Lua bytes were exported).")
    return bbs0_base, bbs0_length, "ISO: PSP_GAME/USRDIR/BBS0.DAT", eboot_hash


def parse_index(index, bbs0_length):
    if len(index) < 0x30 or index[:4] != b"bbsa" or u32(index, 4) not in (5, 6):
        raise ValueError("Unsupported/missing BBSA header.")
    header_sector = u16(index, 0x1A)
    archive1 = u32(index, 0x20)
    archive2 = u32(index, 0x24)
    archive3 = u32(index, 0x28)
    archive4 = u32(index, 0x2C)
    total = u32(index, 0x1C)
    if not (0 < header_sector <= MAX_INDEX_BYTES // SECTOR and
            header_sector < archive1 < archive2 < archive3 < archive4 < total):
        raise ValueError("Invalid BBSA archive-sector boundaries.")
    count = u16(index, 0x0E)
    table = u32(index, 0x14)
    if not (0 < count <= MAX_DIRECTORY_RECORDS and
            0x30 <= table <= len(index) and count * 12 <= len(index) - table):
        raise ValueError("Invalid BBSA 12-byte file directory table.")
    entries = []
    for i in range(count):
        at = table + i * 12
        if u32(index, at + 8) != LUA_DIRECTORY_ID:
            continue
        packed = u32(index, at + 4)
        name_hash = u32(index, at)
        entries.append({
            "index_byte_offset": at, "filename_hash": f"{name_hash:08X}",
            "filename_hint": NAME_BY_HASH.get(name_hash),
            "logical_sector": packed >> 12,
            "sector_count": packed & 0xFFF,
        })
    return header_sector, archive1, count, entries


def export(source, output, max_single_script=MAX_SINGLE_SCRIPT,
           max_total_scripts=MAX_TOTAL_SCRIPTS):
    source, output = Path(source), Path(output)
    if not source.is_file():
        raise FileNotFoundError(source)
    if source.resolve() == output.resolve():
        raise ValueError("Output cannot overwrite the source.")
    if output.exists():
        raise FileExistsError("Output exists; refusing to overwrite: " + str(output))
    if not (SECTOR <= max_single_script <= MAX_SINGLE_SCRIPT):
        raise ValueError("Invalid per-script byte limit.")
    if not (max_single_script <= max_total_scripts <= MAX_TOTAL_SCRIPTS):
        raise ValueError("Invalid aggregate Lua byte limit.")
    size = source.stat().st_size
    with source.open("rb") as handle:
        base, length, source_kind, eboot_hash = locate_bbs0(handle, size)
        header = read_at(handle, size, base, 0x30)
        index_sectors = u16(header, 0x1A)
        index_length = index_sectors * SECTOR
        if not (SECTOR <= index_length <= MAX_INDEX_BYTES and index_length <= length):
            raise ValueError("Invalid BBSA index length.")
        index = read_at(handle, size, base, index_length)
        archive0, archive1, total_count, entries = parse_index(index, length)
        manifest = {
            "tool": "export_bbs_combat_sources", "version": 1,
            "source_type": source_kind, "source_file": source.name,
            "source_size": size, "eboot_sha256": eboot_hash,
            "supported_eboot_verified": eboot_hash == SUPPORTED_EBOOT_SHA256,
            "bbs0_archive_bytes": length,
            "bbs0_index_sha256": sha256(index), "bbs0_index_bytes": len(index),
            "bbsa_12byte_directory_records": total_count,
            "lua_category_records": len(entries),
            "lua_directory_id": "C0000000",
            "name_hints_only": True,
            "extracted": [], "skipped": [],
        }
        total_bytes = 0
        try:
            with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED,
                                 compresslevel=8) as archive:
                archive.writestr("bbs0/index.bin", index)
                for item in entries:
                    sector, count = item["logical_sector"], item["sector_count"]
                    reason = None
                    allocated = count * SECTOR
                    if count == 0xFFF or count == 0:
                        reason = "streaming sentinel/zero sectors"
                    elif allocated > max_single_script:
                        reason = "script allocation exceeds per-file cap"
                    elif sector >= archive1 or count > archive1 - sector:
                        reason = "not wholly stored in BBS0.DAT"
                    else:
                        relative = (sector + archive0) * SECTOR
                        if relative > length or allocated > length - relative:
                            reason = "archive offset/length outside BBS0"
                        elif total_bytes + allocated > max_total_scripts:
                            reason = "total source bundle byte cap"
                    if reason is not None:
                        manifest["skipped"].append({**item, "reason": reason})
                        continue
                    relative = (sector + archive0) * SECTOR
                    payload = read_at(handle, size, base + relative, allocated)
                    if not payload.startswith(b"\x1bLua\x51\x00"):
                        manifest["skipped"].append({
                            **item, "reason": "not a Lua 5.1 format-0 header",
                            "first16_hex": payload[:16].hex().upper(),
                        })
                        continue
                    item = {**item, "bbs0_relative_byte_offset": relative,
                            "allocated_bytes": allocated, "sha256": sha256(payload)}
                    filename = ("lua/%s_at_%06X.lub" %
                                (item["filename_hash"], item["index_byte_offset"]))
                    archive.writestr(filename, payload)
                    manifest["extracted"].append({**item, "zip_path": filename})
                    total_bytes += allocated
                manifest["exported_allocated_bytes"] = total_bytes
                manifest["exported_count"] = len(manifest["extracted"])
                manifest["skipped_count"] = len(manifest["skipped"])
                archive.writestr("manifest.json",
                                 json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        except BaseException:
            output.unlink(missing_ok=True)
            raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Original game ISO or extracted BBS0.DAT")
    parser.add_argument("-o", "--output", type=Path,
                        default=Path("bbs-combat-sources.zip"))
    args = parser.parse_args()
    result = export(args.input, args.output)
    print("Created:", args.output)
    print("Lua-category indexed:", result["lua_category_records"],
          "| exported:", result["exported_count"],
          "| explicitly skipped:", result["skipped_count"])
    print("Index fingerprint:", result["bbs0_index_sha256"])
    print("Only read source bytes; no image/asset/modification was written.")


if __name__ == "__main__":
    main()
