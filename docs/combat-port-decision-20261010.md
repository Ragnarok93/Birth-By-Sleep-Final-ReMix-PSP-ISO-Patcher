# Better Battle System PSP port — architecture decision (2026-10-09 CDT)

## Decision: stop treating in-game OnHitAttack Lua constants as the missing PC port

The provided Xendra Steam mod `BBS_BetterBattleSystem_Steam.lua` is
**a separate memory-editing backend script**, not a native Birth by Sleep
`lua/*.lub` game script. Its externally invoked `_OnFrame()` callback
performs PC-process `ReadByte/ReadLong/ReadFloat` and
`WriteByte/WriteFloat/WriteInt` calls. It derives a PC animation-object
pointer by a seven-level chain beginning at PC address `0x8F6D450`,
uses PC-specific button memory `0x8202B9`, writes the PC animation
state byte to 1 or 4 to request a cancel, and modifies a PC
invincibility byte and Critical Mode ability flags.

**These PC addresses, animation-byte offsets, and externally
scheduled `_OnFrame()` service do not port directly to PSP EBOOT.**
The existing `CombatDecisionModel` captures a rules reference, not an
executable PSP port, and must remain a pure decision model.

The recent PSP analysis logs located **205 BBSA index entries** in
Lua category `0xC0000000`; sampled Lua code includes in-game per-entity
`OnHitAttack` registrations. These are independently useful engine
clues but **not a globally executed player-hit event**. In particular,
`B11CD00`, `G14SW00`, and `G13HE00` have very small
`OnHitAttack` closure bodies (1–3 Lua instructions for some registered
variants), and a nontrivial handler in `B11SB00` appears in its own
entity script. Neither establishes the Steam mod's frame processor.

The user-provided supported `EBOOT.BIN`, SHA-256
`8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`,
has two ELF LOAD segments:

| Segment | Virtual start | Virtual end (files/memory) | Executable? |
| --- | --- | --- | --- |
| Resident | `0x08804018` | `0x08B6EE7C` | Yes |
| Auxiliary data/BSS | `0x08BB4780` | `0x09F40E10` | No |

Resident `.text` ends at `0x08B163D4`; subsequent resident
`.rodata`, `.data` and `.linkonce.d` occupy memory up to
`0x08B6EE7C`. An offline check found no contiguous zero-filled span
of even 32 bytes in `.text`. The old native Stage4/5 area
`0x08B70000` and `0x08B71280` intersects the auxiliary executable
ELF overlays recorded in the logs. **Do not inject persistent code
there, overwrite `0x08816904` without restoring `s0`, or assume a
new LOAD section is safely resident.** The ELF memory map does not
prove that every smaller instruction padding sequence is safe for code.

The source-fingerprinted PSP cancel bit `0x1000` at player
`+0x238` and native setter `0x08B07020` are verified mechanisms,
but their frame timing, authoritative player pointer lifetime, and
input-to-state transition behavior are not. The separate attack-status
bit at `+0x23C` is **not a proved hit-confirm flag**.

## Required implementation path

**Prioritize source-level or existing script scheduler integration**
over another fixed-address native code cave. Either route must prove:

1. A reliably scheduled per-frame execution point **owned by the
   player/game** (not a gimmick or enemy `OnHitAttack` handler),
   plus safe player-pointer and actor-lifetime rules.
2. PSP-native equivalents of Steam's animation frame/type, action
   type, hit-landed state, airborne check, button pressed/released,
   difficulty, invincibility, command category, and Critical flags.
   No PC numeric offset may be silently reused.
3. Correct action gating (guard, finisher exclusions, command categories,
   input-release protection) backed by emulator/device gameplay tests.
4. A collision-free, source-version-checked patch mechanism. If a
   `.lub` replacement is used, validate format, link, sector allocation,
   exact original hash, and runtime script ownership; fail closed if it
   needs an unsupported BBSA repack or raises entity lifecycle hazards.
5. Independent PPSSPP/gameplay tests for each toggle, with repeated
   combat, menus, saving/reloading and auxiliary module transitions.

Do not advertise or activate hit-aware cancels, command cancels,
extended defense, invincibility or Critical bonuses before those
gates are satisfied. Static tests and ISO verification do not replace
in-game performance/correctness validation.

## Replace repeated small inspector logs with one deterministic source bundle

`reference/export_bbs_combat_sources.py` extracts the **whole
bounded BBSA Lua-category evidence set** in one pass from an original
supported ISO, or extracted `BBS0.DAT`:

```bash
python3 reference/export_bbs_combat_sources.py \
  "/path/to/Kingdom Hearts Birth by Sleep Final Mix.iso" \
  -o ~/bbs-combat-sources.zip
```

The ZIP contains `bbs0/index.bin`, `manifest.json` with every
indexed Lua record including explicit skip reasons, and named
`lua/<hash>_at_<indexOffset>.lub` original allocated payloads.
It does **not** contain the full ISO, a large texture archive, or an
EBOOT copy. Default byte caps: **128 KiB per script, 32 MiB total**.
No replacement is written to the source ISO or DAT.

When given an ISO the exporter **requires the exact supported
unmodified EBOOT SHA-256** to guard against mismatched game versions.
For standalone BBS0 input the manifest states that EBOOT identity
was not verifiable. This is explicit, not assumed. Records with
invalid sector allocation, outside-BBS0 mapping, absent Lua 5.1
header or export caps are listed as skipped, not silently accepted.

A complete, bounded source bundle is the minimum evidence required
to inspect player-script scheduling and to design/test an actual
bytecode patch. **It is not itself the finished combat mod.**
Further repeated sampled Lua name or hit-callback log collection
is not a substitute.

## Project status

- In-place right-stick controls, camera geometry, and 30/60 FPS patches:
  preserve existing validated implementations.
- Combat rules: source-level reference only.
- Lua source identity: verified with BBS0 index and game Lua 5.1 headers.
- Runtime-safe combat execution point: **unverified**.
- Combat feature toggles: **disabled**, deliberately.
- Next engineering gate: player-scheduler/native-state mapping
  based on the single complete evidence set, followed by a tested
  in-place patch plan or a clear infeasibility finding.

The engineering priority is **functionality and correctness**, not
adding more log messages, word-counting candidate strings, or another
guess at an unsafe code cave.
