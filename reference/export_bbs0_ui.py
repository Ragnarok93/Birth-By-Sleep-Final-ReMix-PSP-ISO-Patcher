#!/usr/bin/env python3
"""Export only BBS0's index and small UI assets for Birth by Sleep PSP analysis.

Usage:
  python reference/export_bbs0_ui.py /path/to/BBS0.DAT -o bbs0-ui.zip
  python reference/export_bbs0_ui.py /path/to/BBS0.DAT -o bbs0-index.zip --metadata-only

Requires only Python 3.9+ (stdlib). Reads the supplied file, never edits it.
Does not include textures, movies, or full ARC/game archives.
OpenKh format references:
https://openkh.dev/bbs/file/type/bbsa.html
https://openkh.dev/bbs/file/type/arc.html
https://openkh.dev/bbs/file/type/l2d.html
https://openkh.dev/bbs/file/type/ctd.html
"""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import re
import struct
import zipfile

SECTOR = 2048
MAX_INDEX_BYTES = 4 * 1024 * 1024
MAX_ITEM_BYTES = 4 * 1024 * 1024
MAX_ARC_ENTRIES = 1024
UI_KEYWORDS = re.compile(
    r"(hud|command|comm|deck|gauge|face|portrait|shot|lock|focus|pause|camp|"
    r"menu|sub|dialog|text|hp|board|window|cursor|target|help|info)", re.I,
)


def u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def valid_ascii_name(raw):
    name = raw.split(b"\x00", 1)[0]
    if not name or len(name) > 16 or any(b < 32 or b > 126 for b in name):
        return None
    value = name.decode("ascii")
    if "/" in value or "\\" in value or value in {".", ".."}:
        return None
    return value


def ctd_layouts(data, offset, max_end):
    """Return CTD numeric layout records, not game dialogue or text strings."""
    if offset < 0 or offset + 0x20 > max_end:
        return None
    if data[offset:offset + 4] != b"@CTD" or u32(data, offset + 4) != 1:
        return None
    layouts = u16(data, offset + 0x0C)
    messages = u16(data, offset + 0x0E)
    msg_offset = u32(data, offset + 0x10)
    layout_offset = u32(data, offset + 0x14)
    if layouts > 2048 or messages > 50000 or msg_offset < 0x20 or layout_offset < 0x20:
        return None
    if (offset + layout_offset + layouts * 0x20 > max_end or
            offset + msg_offset + messages * 0x10 > max_end):
        return None
    entries = []
    for index in range(layouts):
        pos = offset + layout_offset + index * 0x20
        # Positions, box sizes, font size, spacing and text XY offsets.
        values = struct.unpack_from("<hhhhBBhhhhhhh", data, pos)
        entries.append({
            "index": index,
            "x": values[0], "y": values[1],
            "box_width": values[2], "box_height": values[3],
            "alignment": values[4], "border": values[5],
            "text_align": values[6], "font_size": values[7],
            "letter_spacing": values[8], "line_spacing": values[9],
            "text_x": values[10], "text_y": values[11],
        })
    return {"layout_count": layouts, "message_count": messages,
            "layout_relative_offset": layout_offset,
            "layout_table_sha256": sha256(data[offset + layout_offset:
                                                offset + layout_offset + layouts * 0x20]),
            "layouts": entries}


def scan_bbs0(path):
    """Yield metadata and candidate asset views from a read-only memory map."""
    with path.open("rb") as stream:
        data = mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            if len(data) < SECTOR or data[0:4] != b"bbsa":
                raise ValueError("Not a BBS0 BBSA archive (missing 'bbsa' header).")
            version = u32(data, 4)
            if version not in (5, 6):
                raise ValueError("Unsupported BBSA format version: " + str(version))
            archive0_sector = u16(data, 0x1A)
            index_size = archive0_sector * SECTOR
            if index_size < SECTOR or index_size > MAX_INDEX_BYTES or index_size > len(data):
                raise ValueError("Invalid or oversized BBS0 index boundary.")
            header = {
                "format": "BBSA", "version": version, "size": len(data),
                "archive0_sector": archive0_sector,
                "index_length": index_size,
                "partition_count": u16(data, 8),
                "directory_count": u16(data, 0x0E),
                "partition_offset": u32(data, 0x10),
                "directory_offset": u32(data, 0x14),
                "archive_partition_sector": u16(data, 0x18),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
            assets = []
            links = []
            raw_ctd = []
            found_ctd = set()
            valid_arcs = 0
            for base in range(index_size, len(data) - 0x20, SECTOR):
                if data[base:base + 4] == b"@CTD":
                    parsed = ctd_layouts(data, base, len(data))
                    if parsed is not None:
                        raw_ctd.append({"offset": base, **parsed})
                        found_ctd.add(base)
                if data[base:base + 4] != b"ARC\x00" or u16(data, base + 4) != 1:
                    continue
                count = u16(data, base + 6)
                if count < 1 or count > MAX_ARC_ENTRIES or base + 16 + count * 32 > len(data):
                    continue
                candidates = []
                valid = True
                for j in range(count):
                    pos = base + 16 + j * 32
                    directory_hash, relative, size = struct.unpack_from("<III", data, pos)
                    name = valid_ascii_name(data[pos + 16:pos + 32])
                    if name is None:
                        valid = False
                        break
                    if directory_hash != 0:
                        if name.lower().endswith((".ctd", ".l2d")):
                            candidates.append(("link", name, directory_hash))
                        continue
                    if relative < 16 + count * 32 or size <= 0 or size > MAX_ITEM_BYTES:
                        if name.lower().endswith((".l2d", ".ctd")):
                            valid = False
                            break
                        continue
                    start = base + relative
                    if start > len(data) or size > len(data) - start:
                        valid = False
                        break
                    if name.lower().endswith(".l2d"):
                        if size < 0x40 or data[start:start + 4] != b"L2D@" or u32(data, start + 0x2C) != size:
                            continue
                        candidates.append(("asset", name, start, size, "l2d"))
                    elif name.lower().endswith(".ctd"):
                        parsed = ctd_layouts(data, start, start + size)
                        if parsed is not None:
                            candidates.append(("asset", name, start, size, "ctd"))
                            if start not in found_ctd:
                                raw_ctd.append({"offset": start, "name": name, **parsed})
                                found_ctd.add(start)
                if not valid:
                    continue
                valid_arcs += 1
                for item in candidates:
                    if item[0] == "link":
                        links.append({"arc_offset": base, "name": item[1],
                                      "directory_hash": item[2]})
                    else:
                        _, name, start, size, kind = item
                        assets.append({"arc_offset": base, "name": name, "offset": start,
                                       "size": size, "kind": kind,
                                       "sha256": sha256(data[start:start + size]),
                                       "priority": bool(UI_KEYWORDS.search(name))})
            yield data, index_size, {
                "source": path.name, "header": header, "arc_count": valid_arcs,
                "assets": assets, "links": links, "ctd_layout_metadata": raw_ctd,
            }
        finally:
            data.close()


def export(path, output, metadata_only=False, limit_mib=12):
    if not path.is_file():
        raise FileNotFoundError(path)
    if path.resolve() == output.resolve():
        raise ValueError("Output ZIP must not overwrite BBS0.DAT.")
    limit = limit_mib * 1024 * 1024
    if limit_mib < 1 or limit_mib > 100:
        raise ValueError("Max embedded UI data must be between 1 and 100 MiB.")
    with next_scan(path) as (data, index_size, manifest):
        if output.exists():
            raise FileExistsError("Refusing to overwrite existing ZIP: " + str(output))
        assets = sorted(manifest["assets"], key=lambda x: (not x["priority"], x["size"], x["offset"]))
        selected = []
        skipped = []
        total = 0
        for asset in assets:
            if metadata_only or total + asset["size"] > limit:
                skipped.append(asset["offset"])
            else:
                selected.append(asset)
                total += asset["size"]
        manifest["exported_count"] = len(selected)
        manifest["skipped_count"] = len(skipped)
        manifest["export_limit_bytes"] = limit
        manifest["exported_uncompressed_bytes"] = total
        manifest["skipped_offsets"] = skipped
        with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
            archive.writestr("bbs0/index.bin", data[0:index_size])
            for asset in selected:
                safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", asset["name"])
                target = "bbs0/assets/%08X_%s" % (asset["offset"], safe_name)
                archive.writestr(target, data[asset["offset"]:asset["offset"] + asset["size"]])
            archive.writestr("bbs0/ui_manifest.json", json.dumps(manifest, indent=2) + "\n")
    return manifest


class next_scan:
    """Adapter for keeping the mmap alive throughout ZIP creation."""
    def __init__(self, path):
        self.gen = scan_bbs0(path)

    def __enter__(self):
        return next(self.gen)

    def __exit__(self, *ignored):
        self.gen.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bbs0", type=Path, help="BBS0.DAT extracted from your game")
    parser.add_argument("-o", "--output", type=Path, default=Path("bbs0-ui.zip"))
    parser.add_argument("--metadata-only", action="store_true",
                        help="Export BBSA index and numeric manifest only")
    parser.add_argument("--limit-mib", type=int, default=12,
                        help="Max raw L2D/CTD bytes included, default 12 MiB")
    args = parser.parse_args()
    result = export(args.bbs0, args.output, args.metadata_only, args.limit_mib)
    print("Created:", args.output, "| ARC:", result["arc_count"],
          "| L2D/CTD:", len(result["assets"]),
          "| exported:", result["exported_count"],
          "| CTD layout tables:", len(result["ctd_layout_metadata"]))
    if result["skipped_count"]:
        print("Some assets omitted due to size limit. Their offsets/hashes remain in the manifest.")


if __name__ == "__main__":
    main()
