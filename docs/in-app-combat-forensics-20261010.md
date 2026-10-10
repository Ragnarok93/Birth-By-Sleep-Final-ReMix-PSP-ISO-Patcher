# In-app combat investigation (October 10, 2026)

No PPSSPP debugger, external Python process, rooted device or manually exported
EBOOT is required for the **static** evidence collected in this workflow.

## Using the Android / desktop app

1. Select the supported *original* ULJM05775 ISO (not an already modified output).
2. Expand **Setup → Combat Mods**.
3. Choose **Analyze combat & ISO**.
4. Open **Logs → Export Log**, save the `.log` file and share it for the next
   combat-port analysis. The app no longer silently drops entries after 2,000
   messages. The Logs screen virtualizes visible entries.

The inspection reads ISO9660 and the selected ISO's embedded EBOOT. It does
not patch the ISO or the game modules; no combat toggles are enabled.

## Research stages that run inside the app

- Original EBOOT fingerprint, resident sections, program/section-header
  consistency, ABI hazard checks, collision checks, and named native-script
  API signature checks.
- Existing BBSA ARC pointer, indexed resource, and archive-sector correlation
  (when structurally valid links are found).
- **Full BBSA Lua category census:** visits *every* validated 12-byte index
  record for path category `0xC0000000`, not just 24 chosen records. Includes
  duplicate G01, zero-length, out-of-extent, unavailable archive, and sentinel
  entries with explicit rejection reasons. Reads each resolved allocated script
  separately; logs byte hash, index identity, absolute archive mapping,
  validated Lua 5.1 parser results, function summaries, named Lua opcode
  operands, and structurally recognized `OnHitAttack` table assignments,
  child-closure summaries and handler opcode neighborhoods.
- **Detailed native executable-section scan:** enumerates direct MIPS JAL
  cross-references to the known native player, cancel, invincibility, motion,
  state, command and script-call helpers, including callsite, return address
  and delay-slot word. Enumerates every matching MIPS candidate state-field
  load and store across file-backed executable sections, logging register
  numbers and neighboring words. Applied to EBOOT and additional decoded
  ELF modules encountered in the ISO.
- Existing archive extent, ELF module overlap, native source-signature and
  general pointer-materialization diagnostics remain intact.

Each Lua allocation is limited to 128 KiB; the indexed full survey allows
32 MiB total bytes read. The ELF survey excludes huge or invalid executable
sections. These are **resource safety limits**, not log-size limits; exceeded
records appear explicitly in the log. The report identifies any unparsed or
unmapped record rather than treating it as absent. Readers are cancellable and
never emit modified executable payloads.

## What this proves, and what it cannot

This is an **exhaustive census of the decoded Lua category index**, not a
guarantee that every game script is present in that index. Lua constant
references, static opcodes and direct MIPS call targets are useful for finding
the likely script owner, but they cannot prove the game actually loads a script,
owns a callback as the player, lands an attack, or executes a specific path.

The historical Stage 4/5 overlays are unsafe and remain disabled. The
`player+0x23C` status field must not be relabeled hit-confirm from static
instructions alone. Invincibility ownership and Critical Mode grants similarly
require actual runtime behavior validation. Once the in-app report narrows the
script(s) or native code path, the implementation must still pass automated
binary safety gates and practical gameplay checks before an individual combat
toggle can be enabled.

**No PPSSPP debugging setup is requested from the user for this stage.**


## October 10 full-log findings and targeted follow-up

Analyzed the user-exported 80,893-line ULJM05775 in-app report. The canonical,
unchanged English-patched EBOOT matches SHA-256
`8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`.

- Lua category records: **205**; valid Lua 5.1 chunks: **200**;
  **4 allocated scripts exceeded 128 KiB** (index ordinals 27, 55, 123,
  140, hashes `239A2165`, `43936910`, `A699863D`, `B613CFD0`).
  One 100 KiB script (ordinal 48, hash `3B8D7786`) hit the old
  prototype/depth parser limit. The subsequent code raises the allocated
  script ceiling to **256 KiB** and increases structurally checked
  prototype/depth/opcode budgets. New ISO export must confirm this actually
  resolves all five; no success is assumed from changing constants alone.
- The successfully parsed entries contain **186** static hit-event
  `SETTABLE` assignments; **84** scripts contain an exact hit-event
  name, **87** contain a named native-combat API, and **196** contain
  callback-like names. These counts **do not prove runtime callbacks**.
- Native survey: 11 file-backed MIPS ELFs inspected. EBOOT direct-call
  matches: **50** to the guarded player resolver `0x089E74A8`, and
  **31** to the named script-call helper `0x089E6334`; zero direct
  calls to the native cancel setter is consistent with indirect/table
  dispatch, not absence. The census includes ordinary stack offset
  loads/stores; subsequent logging separates `sp`/`fp` access from
  other unknown object bases to prevent mistaking stack frames for player
  fields.
- Existing `SetTrgFlagCancel` name references occur in only **seven**
  indexed Lua scripts. Cross-referencing CRC32 hashes against
  `OpenKh.Bbs/resources/bbsa.txt` identifies their filenames as
  `B56EX00`, `B82VS00`, `M08EX00`, `B10CD00`, `B83VS00`,
  `B10SB00`, and `B52EX00`. These are battle resource filenames;
  they **do not establish** a global player event scheduler.
- The OpenKh dictionary resolves **all 205** hashes in this supported
  source. The app now records the name next to each entry and a candidate
  matrix showing which scripts contain relevant API, hit, OnUpdate and
  OnCommand names. These are ownership *leads*, not verified identities.
  Dictionary provenance: [OpenKh BBS resources](https://github.com/OpenKH/OpenKh/blob/master/OpenKh.Bbs/resources/bbsa.txt),
  [OpenKh Apache 2.0 license](https://github.com/OpenKH/OpenKh/blob/master/LICENSE).

The old Stage 4/5 overlay memory collision and overwritten `s0` restore
remain. This round makes **read-only diagnostic improvements**, not the
unvalidated combat patches. Gameplay implementation must still show
safe native state ownership, a reliable player scheduler and source-checked
patching without code colliding with dynamically loaded overlays.
