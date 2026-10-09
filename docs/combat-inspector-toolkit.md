# Combat Inspector — read-only PSP game research toolkit

Target branch: `feature/refine-ui-verification-packaging-20261007`

## Purpose

The **Analyze combat & ISO** control on the Setup page now audits the
**selected staged ISO**, not merely the embedded `EBOOT.BIN`.
It runs on Android, Windows, Linux and macOS using the existing Okio
ISO9660 reader and Kotlin shared analysis code. All findings are appended
to **Logs** and can be exported using the existing log export feature.

This is **static reverse engineering**, not PSP instrumentation or a game
runtime debugger. No copy of the selected ISO is written, no game asset bytes
are exported, no PPSSPP cheat is installed, and no experimental combat patch
is activated.

## Included analyzers

| Area | Tool / operation | Evidence produced |
| --- | --- | --- |
| Source identity | `CombatPortInspector` | EBOOT size/SHA-256, program headers, input-hook opcode, source fingerprint |
| Known combat calls | `PspCombatNativeEvidence`, `PspCombatApiCatalog` | MIPS instruction signatures, named script API references, state/flag offsets, mismatches |
| Combat policy | `CombatDecisionModel` | Tested *host-side policy rules only*, no PSP runtime integration |
| Native references | `PspCombatStaticAnalysis` | Executable section map; full direct `JAL` counts with bounded sample PCs; indirect-call limits |
| ELF/MIPS extended audit | `PspCombatDeepStaticInspector` | ELF entry/type, JAL/JALR/JR totals, reads/writes using relevant immediate offsets, sampled PCs across all object types |
| Native data XREF candidates | `PspCombatDeepStaticInspector` | Number of numeric pointers to important native code and event-string addresses in file-backed data sections |
| Address formation | `PspCombatDeepStaticInspector` | Bounded potential `LUI+ADDIU/ORI` materializations with sample PCs; **not** general register-dataflow proof |
| Hit-event names | `PspCombatEventEvidence` | Exact NUL-terminated names, .rodata addresses, aligned string self-pointers, limits on what names prove |
| Generic script calls | `PspCombatScriptCallEvidence` | Verified native named-call helper call sites and MIPS delay-slot setup |
| Whole disc | `IsoCombatResearchService` | Recursive ISO9660 directory/file inventory, extension counts, relevant file names/sizes, file-format header probes |
| Additional game modules | `IsoCombatResearchService` | SHA-256, ELF sections, executable JAL/JALR totals, event-name candidates and sampled offset-opcode counts for bounded unencrypted ELF modules |
| BBSA archive header | `IsoCombatResearchService` | Recognized `bbsa` game DAT archive, version (5/6), bounded index size and SHA-256; embedded asset index *not* decoded |
| BBS0–BBS4 DAT reconnaissance | `IsoArchiveSectorResearch` | SHA-256 and first 16 bytes of initial sector; bounded, evenly distributed 2 KiB-sector probes; ARC-like header candidates, without decoding assets or pretending a zero-sample result is exhaustive |
| Auxiliary ELF load map | `PspElfModuleMap` | PT_LOAD file/virtual ranges, memory and file sizes, segment flags, entry point, executable-section addresses and bounds checks; no runtime overlay reservation claim |
| Unsupported/packed content | `IsoCombatResearchService` | Type classification of encrypted `~PSP`, PSAR/CPK/ZIP; raw/packed DAT/ARC/etc marked **not decoded** |

## Strict limits and nonclaims

- Directory traversal is capped at **8,192 entries** and 12 levels, with
  duplicate-directory offsets suppressed. An individual directory above
  **4 MiB** is recorded as truncated rather than fully staged.
- Up to **512 relevant file headers** are sampled, at most 64 bytes each;
  no full archive is staged merely for a signature search.
  An identified `bbsa` archive (versions 5/6) can additionally have its
  index prefix hashed, bounded to **4 MiB**, with no internal extraction.
- At most **24 additional ELF modules**, each up to **12 MiB** and
  **64 MiB total**, are staged for full static decoding.
- DAT sector reconnaissance probes at most **48 sector headers** per archive
  (first 8, last 8 and 32 evenly distributed probes), using constant memory.
  It is a **sample**, not an exhaustive ARC scan; zero candidates found does
  not prove the archive contains no ARC resources.
- No more than **48 candidate file records** are printed individually.
- Large/unsafe ELF sections are omitted from the deep opcode census.
- Each opcode and pointer category has a limited number of example PCs.
  The count is a *whole-section count*, not a count inferred from capped
  samples.
- Named instruction offsets cannot prove **which object type** owns the
  field. For example, a `lw ...,0x23C(...)` could reference unrelated
  structures, not just the player's attack-status word.
- Unencrypted ELF32 MIPS files can be inspected statically. PSP PRX/module
  relocations and overlay load addresses are not resolved, and encrypted
  modules are not decrypted. Compressed game archives may require
  additional format-specific parsers; access to the ISO alone does
  not mean their contents have been decoded.
- The EBOOT's native combat toggle verification gates remain
  untouched. There is still **no safe resident hook** or confirmed
  hit-confirm state field. Consequently combat options are **unavailable**.

## Recommended user flow

1. Select the original unmodified ULJM05775 game ISO on the Setup page.
2. Open Combat options and choose **Analyze combat & ISO**.
3. View the report in **Logs** and use the existing export action.
4. When investigating a report, distinguish authenticated native EBOOT
   opcodes from module-name inventories, generic numeric xrefs and
   unsupported packed file headers.

Analysis is cancellable using the app's existing cancellation token.
Results are logged in bounded text only. A malformed directory record
produces an error rather than being silently treated as an empty directory.

## Why the expansion matters

Previous inspection only read `PSP_GAME/SYSDIR/EBOOT.BIN`.
Now the selected ISO can reveal separately stored ELF executables,
encrypted PSP modules, auxiliary binaries, and archive candidates
without asking the user to collect manual MIPS debugger traces.
The tool does **not** falsely label an archive's internal scripts as
analyzed if only its outer header was visible.

## Findings from the October 9 full-ISO log

`ULJM05775_10092026 (6).log` demonstrates real source-image coverage
for a 1,671,329,792-byte ULJM05775 game ISO:

- 29 ISO files, 7 directories, no directory truncation.
- 10 separately stored MIPS ELF modules under
  `PSP_GAME/USRDIR/MODULE/`; all staged within size limits.
- EBOOT's original SHA-256 and the 8/8 native instruction and 13/13
  script-API signatures continue to match.
- BBS0.DAT is a version-6 BBSA archive with a **256,000-byte index** and
  index SHA-256 `18e80141ae690337d958fd06416c6f44cdee6a24ed9806efbe24ff8b70b65ea0`.
- BBS1.DAT, BBS2.DAT, BBS3.DAT and BBS4.DAT have unrecognized initial
  headers; their internal combat resources are **not** decoded.
- The opcode census on EBOOT examines **805,656 instructions**, reports
  49,042 JAL, 3,401 JALR and 23,401 JR instructions. The field-offset
  counts describe arbitrary object types, not confirmed player data.
- No dynamic hit-confirm state, safe resident hook or correct Stage4/5
  gameplay implementation has been verified.

The added sparse DAT probes are designed to provide more evidence about
the four unrecognized files in the *next* exported log. Finding an ARC-like
header would be a structural lead, not proof of valid combat asset content.
The auxiliary ELF program-header mapping should similarly be interpreted
as **static** layout evidence, not a runtime address map.

## October 9 — new DAT/overlay evidence from the next user export

The user-provided `ULJM05775_10092026.log` (07:45:55 inspector run)
reports another clean, read-only inspection of the exact supported EBOOT
(SHA-256 `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`).
The full ISO has 29 files, 7 directories and 10 auxiliary MIPS ELF modules.

**New sampled DAT evidence**:

| Archive | 48 sampled sectors | ARC-v1 header candidates |
| --- | --- | --- |
| `BBS0.DAT` | BBSA v6 outer header | zero ARC hits in sample; 256,000-byte BBSA index previously fingerprinted |
| `BBS1.DAT` | candidate ARC at relative sector 76,234 | byte offset **156,127,232**, declared **6** records |
| `BBS2.DAT` | candidate ARC at relative sector 1 | byte offset **2,048**, declared **4** records |
| `BBS3.DAT`, `BBS4.DAT` | no sampled ARC hits | no inference about unsampled sectors |

The BBS1–BBS4 file beginnings contain ASCII `bbs1.dat\0`,
`bbs2.dat\0`, etc. This is **not an ARC header at file offset 0**.
The `IsoArcMetadataProbe` now checks entire bounded v1 directory tables
at the two sampled candidate offsets, validates 16-byte printable
record names and payload bounds, distinguishes hashed external links
from in-archive payloads, and logs at most 12 names. A table is
marked `VALID` only if every record passes these structural tests.
A matching four-byte `ARC\0` signature alone remains insufficient.

**New module placement proof**:

All ten auxiliary ELF files have proprietary ELF type `65448`
(`0xFFA8`) with one PT_LOAD segment apiece. Eight load their code
starting at `0x08B6EE80`, with declared executable memory spans
containing **both** historical combat injection sites
`0x08B70000` and `0x08B71280`. The two modules
`CAMP_MAIN.ELF` and `CHARA_REPORT.ELF` instead start at
`0x08B799D0` and do not overlap those earlier addresses.
These ranges are *per-module declared load maps*, not a claim
that all modules coexist in RAM. Nevertheless, they invalidate the
notion that historical Stage4/5 addresses are permanently free.
`PspOverlayConflictAudit` now reports exact executable PT_LOAD
intersections from the selected ISO, automatically.

Both new probes are **read-only**, bounded and regression-tested;
they do not locate a verified combat-state bit or register a safe
resident hook.

## Verification

Tests exercise a synthetic ISO with a main EBOOT, a second MIPS ELF,
version-5 BBSA DAT content/index, encrypted PSP marker and PSAR marker. They verify read-only
behavior, combined reports, directory traversal and file type
classification. Synthetic ELF tests verify MIPS calls, data-pointer
candidates, signed ADDIU/ORI address builds and malformed-source
fail-closed behavior.

CI workflows build/test Android, Linux, macOS and Windows. Report any
failed status explicitly; compilation or unit tests are **not proof**
of runtime combat behavior.
