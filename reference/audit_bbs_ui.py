#!/usr/bin/env python3
"""Audit readable BBS1/2/3.DAT ARC/L2D metadata, without extracting game artwork.

This script recognizes the user's supplied sector-aligned, plaintext research
copies. It does NOT decrypt Sony's PSP PDG storage or infer the BBS0 index.
Output is offsets, lengths, SHA-256 fingerprints and counts only.
Format reference: https://openkh.dev/bbs/file/type/arc.html
                  https://openkh.dev/bbs/file/type/l2d.html
"""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import struct

SECTOR = 2048

def u16(data, pos):
    return struct.unpack_from("<H", data, pos)[0]

def u32(data, pos):
    return struct.unpack_from("<I", data, pos)[0]

def scan_dat(path):
    result = {"filename": path.name, "file_size": path.stat().st_size,
              "arc_count": 0, "arc_entry_count": 0, "l2d": []}
    with path.open("rb") as handle:
        mm = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            for start in range(SECTOR, len(mm) - 64, SECTOR):
                if mm[start:start + 4] != b"ARC\x00" or u16(mm, start + 4) != 1:
                    continue
                count = u16(mm, start + 6)
                if count > 300 or start + 16 + count * 32 > len(mm):
                    continue
                entries = []
                valid = True
                for index in range(count):
                    name_offset = start + 16 + index * 32
                    directory_hash, relative, length = struct.unpack_from("<III", mm, name_offset)
                    raw = mm[name_offset + 16:name_offset + 32].split(b"\x00", 1)[0]
                    if not raw or any(ch < 32 or ch > 126 for ch in raw):
                        valid = False
                        break
                    if directory_hash == 0 and (
                        relative < 16 + count * 32 or relative + length > len(mm) - start
                    ):
                        valid = False
                        break
                    if directory_hash == 0 and raw.lower().endswith(b".l2d"):
                        offset = start + relative
                        if mm[offset:offset + 4] == b"L2D@" and length >= 0x40:
                            declared = u32(mm, offset + 0x2c)
                            if declared == length:
                                entries.append({
                                    "name": raw.decode("ascii"), "arc_offset": start,
                                    "offset": offset, "length": length,
                                    "sha256": hashlib.sha256(mm[offset:offset + length]).hexdigest(),
                                    "sq2p_count": u32(mm, offset + 0x20),
                                    "ly2_offset": u32(mm, offset + 0x28),
                                })
                if valid:
                    result["arc_count"] += 1
                    result["arc_entry_count"] += count
                    result["l2d"].extend(entries)
        finally:
            mm.close()
    result["l2d_count"] = len(result["l2d"])
    result["unique_l2d_names"] = len(set(item["name"] for item in result["l2d"]))
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dat", type=Path, nargs="+", help="Read-only BBS1/2/3.DAT paths")
    parser.add_argument("--output", type=Path, help="Optional JSON manifest output")
    args = parser.parse_args()
    manifest = {"method": "OpenKh ARC and L2D read-only sector scan", "archives": [
        scan_dat(path) for path in args.dat
    ]}
    output = json.dumps(manifest, indent=2)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
        print("Saved metadata-only inventory:", args.output)
    else:
        print(output)

if __name__ == "__main__":
    main()
