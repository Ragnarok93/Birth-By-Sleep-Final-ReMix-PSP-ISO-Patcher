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
| BBSA directory cross-references | `IsoBbsaDirectoryEvidence` | Bounded BBSA directory records and exact hash-field correlation against validated ARC external links; candidate name hashes/packed sector metadata, no extraction |
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

## Validated ARC directory results from latest exported log

The uploaded `ULJM05775_10192026.log` reports that the two
ARC header candidates previously found by sparse sampling are
**structurally valid ARC v1 directories**, not merely four-byte magic
matches. Both native injection candidates still overlap the executable
PT_LOAD declarations of **8/10 separately stored modules**.

- `BBS1.DAT`, relative byte offset **156,127,232**: all 6 records
  valid. Local entries: `g01sb_000.pam`, `g01sb_000.seb`,
  `g01sb_000.ead`, `g01sb00.pmo`, `g01sb00.epd`.
  An external link record is named `g01lua`.
- `BBS2.DAT`, relative byte offset **2,048**: all 4 records
  valid. Local entries: `n01bd00.pmo`, `n01bd00.txa`,
  `n01bd_000.ead`, `n01bd_000.pam`.
- The name `g01lua` **suggests** a potential script-related
  reference but does not prove that a Lua script exists at that
  location, nor identify its destination. The validated ARC record
  contains a raw 32-bit external-reference identifier, not a
  recovered script payload.

The inspector now logs these **raw external-reference IDs** and
**archive-relative offset/size pairs** for the first 12 fully
validated directory entries. These are useful leads for a later
cross-archive name-hash/index investigation, while avoiding byte
extraction or pretending the linked resource has been resolved.
Malformed ARC tables do not release individual record metadata as
verified evidence.

Do not restore Stage 4/5 gameplay injection at
`0x08B70000` or `0x08B71280`; static segment collision is now
source-backed by the full ISO inventory.

## October 9 later log: external ARC link correlation

The user-supplied `ULJM05775_10092026 (1).log` confirms the
expanded read-only inspector successfully completed on the same EBOOT
(SHA-256 `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`)
and lists the following fully validated ARC directory metadata:

| Archive | ARC relative byte offset | Record | Evidence |
| --- | ---: | --- | --- |
| `BBS1.DAT` | 156,127,232 | `g01lua` | External directory hash **`0x4D4D4947`**; length not printed by this older inspector build |
| `BBS1.DAT` | 156,127,232 | `g01sb00.pmo` | Local payload at ARC-relative **928**, length **36,464** |
| `BBS2.DAT` | 2,048 | `n01bd00.pmo` | Local payload at ARC-relative **144**, length **115,008** |
| `BBS2.DAT` | 2,048 | `n01bd00.txa` | Local payload at ARC-relative **115,152**, length **51,040** |

The current log does not expose the external record's payload-length
field, so it cannot independently prove the new zero-length link check
will pass on this source. That validation will be established in the
next exported inspector log.

The external `g01lua` label is a potential script-resource clue,
**not proof that a Lua script has been located or executed**.
The external directory hash is **not** a file offset or executable
address. The structural parser now enforces a zero-length field on
linked ARC records, in accordance with the [OpenKh ARC format notes](https://openkh.dev/bbs/file/type/arc.html).

The new `IsoBbsaDirectoryEvidence` parser uses the documented [BBSA
header and index directory structure](https://openkh.dev/bbs/file/type/bbsa.html).
For authenticated ARC external-link records, it looks up the raw
directory hash in BBSA's **directory hash field**, not arbitrary 4-byte
occurrences. It bounds the directory-table count and offsets before
reading, caps the number of sample records, and reports potential
matches as file-name hashes plus packed start-sector/sector-count
metadata. It does **not** attempt to decrypt or extract archive payloads,
infer the source partition, or claim a unique target when multiple
records share the same directory hash.

If a BBSA index is malformed, truncated, or absent, the parser reports
**UNVERIFIED**, never a misleading `0 matches`. A valid index with
zero matching directory records is specifically a zero-match result
for **that indexed table only**; it does not rule out other resource
storage or external references.

## October 9, 16:40 — first full index-correlation result

The latest uploaded `ULJM05775_10092026.log` completed the
read-only analyzer at 16:40:15 on the supported EBOOT
(SHA-256 `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`).
It found **one authenticated ARC external link** (`g01lua`,
directory hash `0x4D4D4947`), but found **zero matching directory-hash
entries among BBS0's 15,093 file-directory records**.
The BBSA header reports version 6 and file-directory table starting at
byte **22,388** in the 256,000-byte index prefix.

**Meaning:** the link does not match the *file directory-hash field*
examined in that BBS0 index. This is an exact field-level negative
result, **not proof** the `g01lua` resource is missing or that its
contents are known. It could be referenced through a different
index namespace or a different archive; no script payload has been read.

The inspector's newer corrected implementation checks the **BBSA
partition-descriptor path-ID field at fixed index offset `0x30`**
(`u16` descriptor count at `0x08`). The 32-bit header field at
`0x10` instead points to the separate partition-file-entry array.
Each partition descriptor has an 8-byte layout: path ID, file count,
and entry-array index. The corrected parser bounds all referenced
array ranges before checking path IDs and hashes of named files.
Any malformed descriptor/entry table is reported as **UNVERIFIED**,
never as a zero-match result. Hash agreement here still does not
establish the runtime behavior or contents of the linked resource. The external reference format is described in the [OpenKh ARC format](https://openkh.dev/bbs/file/type/arc.html), and the BBSA partition layout in the [OpenKh BBSA format](https://openkh.dev/bbs/file/type/bbsa.html).

The log also independently confirms both old Stage4/5 injection
addresses overlap executable segments in **eight of ten** auxiliary
ELF modules. Continue treating `0x08B70000` and `0x08B71280`
as unsafe permanent storage.

## 17:28 ISO export: corrected OpenKh BBSA directory interpretation

The new `ULJM05775_10092026 (1).log` finished cleanly and supplied
the first results of the experimental partition correlation:

- `g01lua` in `BBS1.DAT` still has external ARC directory ID
  **`0x4D4D4947`**, and the BBS0 file-directory table has **0 exact
  matches across 15,093 entries** in its directory-path-hash field.
- The previous report printed `BBSA partition table: count=15 byte_offset=332`
  and `matching_BBSA_partitions=0`. **That conclusion is invalid.**
  By comparing against the actual OpenKh C# implementation, we found
  that `0x10` in the header (332) points to the *partition-file-entry
  array*, **not** the partition descriptors. Partition descriptors
  begin at fixed byte offset **`0x30`**, and each record is 8 bytes
  (`path ID`, `file count`, `file-entry index`).
- **Critical discovery:** OpenKh's
  `OpenKh.Bbs/Bbsa.cs` explicitly maps the fixed directory ID
  **`0x4D4D4947` to `arc/gimmick`**. That value is also readable
  as `GIMM` in little-endian bytes; it is a known path identifier,
  **not** a dynamically calculated CRC32 name hash.
- Filename hashes are different: OpenKh's
  `OpenKh.Bbs/Bbsa.Hash.cs` uses standard reflected IEEE CRC32
  on UTF-8 bytes, making `CRC32("g01lua") == 0x64B5573A`.
  The correct evidence path is now to locate the **`arc/gimmick`
  partition header** at fixed offset `0x30`, then examine its
  bounded 8-byte file entries for this specific filename hash.
- **Only the new code can answer that lookup.** The uploaded log
  predates the correction, so its missing partition match is **not**
  evidence against the existence of an indexed `g01lua` script
  resource. Even a filename match is a location lead, not proof
  of Lua execution or any combat-callback connection.

The read-only inspector now authenticates every partition file-entry
range, prints known directory labels for ARC links, and reports exact
partition-name/filename hash matches with packed sector metadata.
It does **not** extract game data, allocate overlay code, or activate
any unsafe combat mod.

**Implementation references:** [OpenKh BBSA structure
`OpenKh.Bbs/Bbsa.cs`](https://github.com/OpenKH/OpenKh/blob/master/OpenKh.Bbs/Bbsa.cs);
[OpenKh CRC32 implementation
`OpenKh.Bbs/Bbsa.Hash.cs`](https://github.com/OpenKH/OpenKh/blob/master/OpenKh.Bbs/Bbsa.Hash.cs).

## October 9, 18:09 — valid gimmick partition, unresolved g01lua filename

The uploaded `ULJM05775_10092026 (2).log` confirms the corrected
OpenKh BBSA partition-descriptor layout is now working on the selected
ULJM05775 source. The observed result is **not** the earlier invalid
partition lookup:

- BBSA version 6; BBS0 index prefix SHA-256
  `18e80141ae690337d958fd06416c6f44cdee6a24ed9806efbe24ff8b70b65ea0`.
- `arc/gimmick` directory ID `0x4D4D4947` appears as **one actual
  partition descriptor at BBS0 index byte 128**, with **333** file
  entries starting at array index **1196**.
- Its valid OpenKh CRC32 filename search for `g01lua`
  (`0x64B5573A`) returns **zero matches among those 333
  partition-file entries**. The separate 15,093-record BBS0
  file-directory path-hash search also returns zero exact matches
  for directory ID `0x4D4D4947`.
- BBS1's six-entry ARC at byte `156127232` still has the
  structurally valid `g01lua` external dependency, and BBS2's
  four-entry ARC at byte `2048` remains valid. No runtime script
  contents, actor ownership, or handler invocation were recovered.
- Eight of ten auxiliary ELF modules declare executable loads
  overlapping both historical Stage4/5 addresses, which remain
  unsuitable for permanently resident combat hooks.

**Interpretation:** an authenticated ARC link references the
`arc/gimmick` namespace, but the exact `g01lua` filename hash does
not appear among that namespace's 333 partition entries. The link
could be absent, aliased, resolved differently, stored under
another namespace, or intentionally unused. The log cannot
distinguish those possibilities.

### Follow-up research added

`IsoBbsaDirectoryEvidence` now performs an additional bounded,
read-only **global filename-hash census**, independently of the
link's directory ID. For each validated ARC external link, it checks:

1. The **file-name hash field** of all BBS0 12-byte directory
   records, showing any different directory/path IDs and broad
   category hints (e.g. the documented `0xC0` Lua category).
2. The **file-name hash field** in all valid BBSA partition-file
   entry ranges, showing source partition IDs and packed sector
   metadata, capped at eight examples per link.

It reports exact match counts and distinguishes an invalid
partition index (**UNVERIFIED**) from a valid zero-match census.
The search is bounded by the existing 64-link selection and an
additional 100,000 partition-file-entry traversal ceiling.
Filename hashes alone do not prove the linked resource exists,
its extension, executable script format, or runtime invocation.
There is **no whole-archive extraction or modification**.

This new census has **not yet run on the user's game ISO**;
its results will appear in the next exported inspector log.

## October 9, 18:20 — filename case normalization correction

The latest supplied `ULJM05775_10092026 (3).log` completed successfully,
with the original EBOOT identity and static combat signatures unchanged.
It reports `g01lua` as a validated external ARC dependency inside
`BBS1.DAT`, referencing known directory ID `0x4D4D4947`
(`arc/gimmick`). That directory still has 333 valid BBSA partition
file entries and a partition descriptor at BBS0 index offset 128.

**Crucial newly identified false-negative risk:** the global census
reported `CRC32=0x64B5573A`, with 0 filename matches in the 15,093
twelve-byte index entries and 0 partition-file matches. However,
OpenKh's `Bbsa.cs` resolves file names by calling
**`GetHash(name.ToUpper())`**. The uppercase name `G01LUA`
(which also appears in OpenKh's `resources/bbsa.txt` filename
dictionary) hashes to **`0xF5BE1086`**. Earlier reports looked up
the lowercase string hash **`0x64B5573A`**, so they cannot establish
whether `G01LUA` is absent from the relevant indexes.

The inspector now normalizes ARC filename text to uppercase before
UTF-8 CRC32 lookup in **all** BBSA partition and global file-hash
scans. New regression tests explicitly distinguish lower-case raw
CRC32 from the correct normalized lookup hash and preserve the
read-only/bounded scanning contract.

Until a new ISO inspection is exported, there is **no game-source
lookup result for `0xF5BE1086`**; do not claim the resource is either
located or absent. Even if a matching archive entry is found, it
would not prove hit-confirm behavior, runtime script execution, or a
safe permanent combat hook.

Source: [OpenKh's exact-name lookup in Bbsa.cs](https://github.com/OpenKH/OpenKh/blob/master/OpenKh.Bbs/Bbsa.cs)
and [BBSA CRC32 implementation](https://github.com/OpenKH/OpenKh/blob/master/OpenKh.Bbs/Bbsa.Hash.cs).

## October 9, 18:33 — G01LUA confirmed indexed in arc/gimmick

The new complete user export `ULJM05775_10092026 (4).log`
shows a **positive exact index lookup** following the corrected OpenKh
uppercase hashing rules:

| Evidence field | Verified in log |
| --- | --- |
| Source EBOOT | Supported ULJM05775, SHA-256 `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7` |
| External reference | `g01lua`, validated BBS1 ARC link |
| Directory | `arc/gimmick`, ID `0x4D4D4947` |
| Filename normalized for index | `G01LUA`, CRC32 `0xF5BE1086` |
| BBS0 partition | Descriptor index offset 128; 333 file entries |
| Exact directory AND filename hit | **1**, index byte offset `12452` |
| Encoded global sector / count | **439523** / **2 sectors** |
| Global filename search | 0/15093 in the separate 12-byte directory entry names; **1** in the partition-file entries |

This is a **located BBSA partition-file index entry**, not yet a
read or decoding of the target's data. Earlier reports of zero
filename matches were caused by hashing lowercase `g01lua` rather
than `G01LUA`; those were not evidence of absence. The independent
12-byte *directory-path-ID* zero-match result refers to a different
index namespace and is not inconsistent with this partition hit.

The new `IsoBbsaIndexedPayloadProbe` follows OpenKh's
`CalculateArchiveOffset` rules to map the confirmed **global
sector** to a specific `BBS0.DAT`–`BBS4.DAT` physical sector,
validates archive boundary ordering and the ISO file extent, then
reads **at most the first 2048 bytes**. It records the prefix hex,
sample SHA-256, and signature classification (including `1B 4C 75
61` Lua bytecode) without exporting content or claiming to interpret
the bytecode. Streaming sentinel, malformed/cross-boundary/absent
archive mapping, and unverified index tables all fail closed.
No PSP hook, resident code cave, or combat flag is modified.

The provided (4) log **predates** this targeted payload-probe
addition; a subsequent inspector export is required to establish
the mapped physical DAT address and inspect its source header.

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
