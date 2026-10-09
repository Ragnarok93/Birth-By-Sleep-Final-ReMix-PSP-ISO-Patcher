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
| Unsupported/packed content | `IsoCombatResearchService` | Type classification of encrypted `~PSP`, PSAR/CPK/ZIP; raw/packed DAT/ARC/etc marked **not decoded** |

## Strict limits and nonclaims

- Directory traversal is capped at **8,192 entries** and 12 levels, with
  duplicate-directory offsets suppressed. An individual directory above
  **4 MiB** is recorded as truncated rather than fully staged.
- Up to **512 relevant file headers** are sampled, at most 64 bytes each;
  no full archive is staged merely for a signature search.
- At most **24 additional ELF modules**, each up to **12 MiB** and
  **64 MiB total**, are staged for full static decoding.
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

## Verification

Tests exercise a synthetic ISO with a main EBOOT, a second MIPS ELF,
DAT content, encrypted PSP marker and PSAR marker. They verify read-only
behavior, combined reports, directory traversal and file type
classification. Synthetic ELF tests verify MIPS calls, data-pointer
candidates, signed ADDIU/ORI address builds and malformed-source
fail-closed behavior.

CI workflows build/test Android, Linux, macOS and Windows. Report any
failed status explicitly; compilation or unit tests are **not proof**
of runtime combat behavior.
