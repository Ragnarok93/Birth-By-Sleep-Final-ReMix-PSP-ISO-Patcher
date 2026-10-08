# PSP L2D UI scaling — archive research and safety gate

This is an **asset-analysis and geometry-editor milestone**, not yet a finished
gameplay scaling feature. Do not expose the editor as a production ISO patch
until end-to-end runtime testing proves every affected overlay works.

## Primary references

- [OpenKh BBSA](https://openkh.dev/bbs/file/type/bbsa.html)
- [OpenKh ARC](https://openkh.dev/bbs/file/type/arc.html)
- [OpenKh L2D](https://openkh.dev/bbs/file/type/l2d.html)
- [OpenKh CTD](https://openkh.dev/bbs/file/type/ctd.html)

## Inspected supplied archives

Read-only inspection of the supplied plaintext research copies (not assumed
identical to their encrypted retail-ISO counterparts):

| File | Bytes | SHA-256 | Valid ARC | Embedded L2D |
| --- | ---: | --- | ---: | ---: |
| BBS1.DAT | 206092288 | `5fc6ece945653588ff5c07bddb3c8fbf8dac12e2791472dfc8da4a9391622f32` | 1546 | 151 |
| BBS2.DAT | 206399488 | `5768b5735237f80cc645ff744a0e528b62adbc24478315f97ac80b6be95a891c` | 198 | 4 |
| BBS3.DAT | 206391296 | `9772fee82a7896ce4486d29220d4d68568c9b4f90ce605c9a9c04ecba503995b` | 462 | 115 |

These files begin with a sector-sized wrapper marked `bbs1.dat` etc.,
not an OpenKh `bbsa` index header; raw ARC payloads and L2D data are readable.
The absent `BBS0.DAT` is still needed to resolve common assets, archive indices,
and gameplay HUD completeness. OpenKh notes that common files are in BBS0
and that BBS1/2/3 may be protected using PSP PDG encryption.

### Candidate layouts requiring visual/in-game classification

| Category being investigated | Example archive data | L2D-relative offset in supplied DAT |
| --- | --- | --- |
| Command UI | BBS1: `comm_00.l2d`, `comm_01.l2d` | `0xDDE1D0`, `0xE018E0` |
| Shotlock | BBS1: `shot_00.l2d`, `shot_02.l2d` | `0x15854C0`, `0x15B0250` |
| Gauge / lock-on | BBS1: `gauge_01.l2d` | `0x159F060` |
| Portrait/menu face | BBS1: `shp_face.l2d` | `0xAA46070` |
| Pause | BBS1: `pause.l2d` | `0xC392830` |
| Camp/main menu | BBS1: `camp.l2d` | `0xC2F6A50` |
| Command shop, **not confirmed combat deck** | BBS1: `cmd_b00.l2d`, `cmd_s00.l2d` | See `reference/audit_bbs_ui.py` |
| Command Board | BBS3: `bd_cock.l2d` | `0x521D030` |

**Never assume matching prefixes uniquely establish UI function.**
In particular, `shp_face.l2d` is in a shop-related ARC and has not been
established to be the main combat character-portrait renderer. Similarly,
`cmd_b00` is packaged with shop command resources and must not be treated as
the combat Command Deck without gameplay validation.

All 69 CTD references identified by the sector-aligned ARC scan are *links*
(BBS1: 49, BBS2: 1, BBS3: 19), not embedded CTD assets. The actual dialogue
and subtitle layout tables are therefore unavailable in these three supplied
DATs. The BBS0 archive/index is needed for that part of the feature.

**Never assume matching prefixes uniquely establish UI function.**
All offsets above are within the supplied DAT files, not ISO-relative offsets.
Must match game serial, storage representation and exact asset hashes before
any automated writing. Similar names appear at multiple archive locations.

## Geometry engine

`BbsL2dGeometry.scale` scales **only** documented LY2 layout XY,
LY2 node XY and SP2 group XY coordinates, preserving L2D length and original
texture UV/parts. It understands the SQ2P *pointer table* indirection and
validates bounds/signatures before writing to a new byte buffer.

Local offline 85% tests on seven named layouts produced size-preserving edits;
at 100%, the output was byte-identical to source. This is *not runtime
validation*: SQ2 animation keyframes, font/CTD sizes, clipping/scissor handling,
anchor repositioning, dynamic gauge geometry and input hit regions are not yet
mapped, so scaling only these fields may yield misalignment or visual bugs.
No game asset binaries have been committed to the repository.

## Remaining release gate

1. Obtain `BBS0.DAT` from the same supported game revision and, separately,
   its decrypted supported `EBOOT.BIN` (or a lawful ISO containing it) for
   renderer/asset lookup and profile verification.
2. Classify every HUD/Deck/gauge/portrait/Shotlock/menu/subtitle asset by matched
   PPSSPP captures. Examine CTD (font positions and sizes).
3. Re-derive the SQ2 animation key kinds and transforms, including texture UV
   invariants, clipping, centered coordinates and component anchors.
4. Connect the completed low-memory ISO `IsoBytePatch` overlay primitive to
   the identified DAT entries using exact L2D/ARC hashes, game-version guards
   and post-rebuild verification of changed output spans.
5. Validate repeated tests at 70, 75, 80, 85, 90, 95 and 100%, with the
   game's stock textures AND PPSSPP HD replacement profile.
6. Enable category sliders only when the category's complete required resource
   set and runtime behavior have been confirmed. Fail closed otherwise.
