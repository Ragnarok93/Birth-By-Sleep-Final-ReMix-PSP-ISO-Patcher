# PSP Better Battle System — independent static reconstruction (October 9, 2026)

## Goal and available source material

This pass deliberately requires **no user-operated PPSSPP debugger**. The
reference materials available to the project were:

- exact decrypted, English-patched `ULJM05775` EBOOT, SHA-256
  `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`;
- original Xendra `BBS_BetterBattleSystem_Steam.lua` from the
  `Better Battle System - STEAM-31-STEAM-1-1-1740769623.zip` archive
  (February 28, 2025);
- archived Stage 3/4.1 PSP-native address map and the currently shipped
  Kotlin patcher code.

The 2025 Steam Lua is **a specification for behavior**, not a source of
native PSP memory addresses, functions, or object lifetime information.
Later 2026 community Steam variants are not assumed to describe this build.

## Verified native PSP instructions (static, exact source only)

The in-app read-only inspector now authenticates **complete instruction
sequences**, not just the first byte of a proposed hook. Each sequence is
sampled from the supported EBOOT:

| PSP VA | Native operation | Source evidence |
| --- | --- | --- |
| `08B07020` | Set/clear `0x1000` in `player+0x238` | `lw`, `andi 1`, `sll 12`, `sw` |
| `08B07084` | Read `0x1000` from `player+0x238` | `lw`, `andi 0x1000`, `srl 12` |
| `08955F10` | Consume cancel bit in player-state handler | `lw +0x238`, `andi 0x1000`, branch, `sh +0x22C` |
| `08955FD8` | Clear cancel bit during a native state transition | `and -0x1001`, `sw +0x238` |
| `08816904` | Restore callee-saved `s0` from stack | `lw s0, 0x48(sp)` |

These are confirmed **instructions and branch relationships**, not proof
that a replacement hook runs during every relevant gameplay frame.

The archived PSP Stage 3 reference additionally identifies player
manager `0x08B5A4B0`, live player pointer `manager+0xA8`,
player state `+0x220`, substate `+0x22C`, trigger `+0x238`,
status `+0x23C`, command kind `+0x268`, motion pointer `+0x4C`,
motion ID `motion+0x1A`, motion time `motion+0x24`, and native input
globals `0x08B41970` etc. **This does not authenticate their live
values in every menu, scene, character, or module.** The low bits of
`+0x23C` remain unattributed; in particular hit-lifecycle meanings
0/1/2 cannot be copied from Steam.

## Combat decision logic reconstructed

`CombatDecisionModel` is a pure, host-side Kotlin reference that consumes
**semantic snapshots** and the project's existing independent `PatchOptions`.
It implements the policy from the archived Steam script:

- hit-aware command/finisher cancels, including grounded and airborne timing;
- character-specific timed command cancels: Terra 45, Aqua 40, Ven 35 frames;
- motion `0x5F` 46-frame override, only for mapped native command categories
  `0..3`;
- guard cancel after frame 5 and selected normal-state defense cancels;
- protected cinematic/finisher states, high-risk action exclusions, Triangle-
  sensitive and Cross-sensitive command IDs;
- requested dodge/form/slow-command invincibility, without any PSP writes;
- separately selected Critical-mode ability and passive bonus **requests**
  on difficulty 3 only, without modifying ability tables or saved data.

All features remain independently selectable as reference decisions, with
explicit safe defaults. Unit tests cover threshold boundaries, three
characters, invalid/unknown categories, special action exclusions,
airborne neutral-state selection, invulnerability requests, and Critical
ability/passive isolation. Finisher protection intentionally strengthens
the 2025 Lua behavior, which allowed problematic cancel interactions.

This is **not an activated PSP mod**. There is currently no safe native
runtime snapshot producer, decision dispatcher, memory writer, or
resident code/data area proven across dynamic overlay lifetime.

## Why blindly enabling Stage 4/5 remains incorrect

- `0x08B70000` and `0x08B71280` are in the MainApp dynamic overlay
  arena beginning `0x08B6EE7C`. Increasing ELF PT_LOAD coverage cannot
  reserve overlay ownership.
- Replacing `0x08816904` displaces an `s0` stack restore. A JAL at this
  position must preserve the displaced semantics and calling convention.
- The PC animation IDs, animation types, input history flags, hit-state
  values, i-frame byte and ability IDs have **not** been mapped to exact
  PSP runtime fields by static evidence alone.
- A successful Gradle compilation, instruction fingerprint match, or
  ELF-repack check cannot certify that cancels work in-game.

## Next engineering steps that do not require manual debugging

1. Statically trace setters, consumers, and initialization of the PSP
   player-status and motion fields through the original MIPS control-flow
   graph, marking each field read/write by owner and module.
2. Reverse-engineer command metadata table category and sensitive-input
   semantics; preserve native guard/finish/Shootlock exclusions.
3. Locate a genuinely resident, load-safe hook mechanism. Do not infer
   allocation safety from zero bytes or an apparent gap in the ELF.
4. Generate a **telemetry-only** ISO research profile with a safe hook and
   bounded recording, then automate PPSSPP tests when a complete test ISO
   and compatible emulator runner are available.
5. Promote combat behaviors individually after automation proves correct
   input/state ownership across three characters and scene transitions.

The user need not manually inspect logs or attach a debugger for the
static steps. Automated runtime validation still needs a bootable game
and a test runner; the decrypted EBOOT alone is not the full game ISO.

## Current release safety

The production engine permanently excludes historical Stage4/5 injection
and retains the existing native camera and 30/60-FPS paths. Selecting any
combat modification still fails validation with
`UnvalidatedPspPortFeature`. No combat behavior is claimed playable.
