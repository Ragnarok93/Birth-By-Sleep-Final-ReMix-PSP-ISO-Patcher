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

## Second static pass: native script registrations and invincibility

The supported executable contains an in-file script-name/function-pointer
registration table, allowing selected gameplay APIs to be identified with
**three independent checks**: exact function name bytes, the paired function
address, and distinctive native MIPS opcodes. The Kotlin
`PspCombatApiCatalog` inspector now performs all checks without writing to
the executable. It rejects truncated files, missing names, changed pointer
entries and changed instructions. The full source SHA-256 still gates any
claim of matching the supported revision.

| Registered name | Native wrapper | Observed native access |
| --- | --- | --- |
| `GetPlayerState` | `0x089E924C` | `lw +0x220` on resolved player |
| `GetSubState` | `0x089E92B4` | `lh +0x22C` on resolved player |
| `GetCommandKind` | `0x089E78F4` | `lhu +0x268` on resolved player |
| `GetCommandState` | `0x089E91E4` | `lh +0x22E` on resolved player |
| `GetCommandSubcate` | `0x089E7970` | `lhu +0x268`, lookup from `0x08B1AE44 + kind*16 + 3` |
| `GetCommandCategory` | `0x089E7A38` | `lhu +0x268`, lookup from `0x08B1AE44 + kind*16 + 1` |
| `SetTrgFlagCancel` | `0x089E7604` | sets/clears bit `0x1000` at player `+0x238` |
| `IsTrgFlagCancel` | `0x089E8BC0` | tests bit `0x1000` at player `+0x238` |
| `EnableInvincible` | `0x089D6850` | sets/clears bit `0x8000` at resolved entity `+0x190` |
| `IsInvincible` | `0x089D6DF4` | tests bit `0x8000` at entity `+0x190` |
| `SetPlayerFlagInvincible` | `0x089EA344` | sets/clears bit `0x1` at resolved player `+0x234` |

**New conclusion:** invulnerability has at least two distinct native
mechanisms. The script `EnableInvincible` operates on an entity-level flag
(`+0x190 / 0x8000`), while `SetPlayerFlagInvincible` operates on a
player-level flag (`+0x234 / 0x1`). Their lifetime, state restoration and
precedence cannot be inferred from names or their setters alone. Repeatedly
writing either bit each frame would not be an acceptable port until the
game's owning state machine is understood. Steam's hard-coded `0x241320`
byte has no justified equivalence in this PSP executable.

**New command-category proof:** the actual PSP wrapper computes its category
from the current command kind as a 16-byte indexed definition table and
reads byte `+1`, while the distinct `GetCommandSubcate` wrapper uses
byte `+3`. `GetCommandState` reads a signed halfword at
`player+0x22E`. These operations establish the real PSP command metadata
layout and rule out treating command kind, category, subcategory and current
command state as interchangeable. This supports the existing PSP-only category exclusion
model. The wrapper does not perform an explicit bounds check around the
table index; code that evaluates an arbitrary kind must validate it before
using the table. It would be unsafe to copy an unvalidated Steam command ID
directly as a PSP definition index.

**Player object lifetime:** the common script-player resolver at
`0x089E74A8` first obtains an object via `0x088E8E9C`, checks for null,
reads a callback from its metadata (`object+0x1C`, with a small
`+0x30` metadata displacement), invokes it using `jalr`, and accepts
the object only after checking the callback result against `0x00010001`.
Otherwise it returns null. This demonstrates a native guard/virtual-dispatch
contract; simply dereferencing a historical static player-manager pointer
cannot be assumed equivalent across scenes, unloaded actors or cutscenes.
The inspector fingerprints the corresponding instructions. It remains
unclear how to safely call the resolver from a new native resident hook.

**Player status clarification:** native small setter/getter functions at
`0x08B07094` and `0x08B070B8` manipulate/test bit `0x10000000`
at `player+0x23C`. That establishes a flag's presence, but **does not
identify the Steam 0/1/2 hit-confirm semantics** in the lower bits of this
word. A hit-aware cancel needs further call-path and value-flow attribution.

All of the functions above are part of the *unmodified MainApp text
section*, not the dynamic overlay. That makes them valid static
investigation targets; it does **not** make every call context safe or
supply free resident storage for a new dispatcher.

## Third static pass: combat event names, attack queries and call graph

A full `JAL` instruction scan over the exact supported ELF's executable
sections (including `.text` and `.sceStub.text`, excluding data and
`.rodata`) establishes the following **direct-call counts**:

| Native routine | Address | Direct MIPS `JAL` references |
| --- | --- | ---: |
| Validated script-player resolver | `0x089E74A8` | **50** |
| Cancel bit `player+0x238 / 0x1000` setter | `0x08B07020` | **0** |
| Cancel bit getter | `0x08B07084` | **0** |
| Status bit `player+0x23C / 0x10000000` setter/getter | `0x08B07094` / `0x08B070B8` | **0** each |
| Registered `IsAttacking` script wrapper | `0x089DC664` | **0** |
| Registered `GetMotionNowFrame` script wrapper | `0x089DB50C` | **0** |

The zero values are **not evidence that functions are unused**: script
engine function-pointer tables, indirect `JALR`, dynamically loaded
overlay modules and other runtime dispatch are intentionally not counted.
The inspector now reports full counts separately from its first 12 sample
addresses, avoiding capped samples being mistaken for total call counts.

Two script APIs were matched to their exact name/pointer registrations and
machine-code call chains:

- **`IsAttacking`** is a registered native wrapper at `0x089DC664`. It
  obtains an entity and calls `0x088E6294`; that native routine traverses
  entity/list state and recursively calls itself. It represents active
  attack-related state, **not a reliable successful-hit event**.
- **`GetMotionNowFrame`** is a registered native wrapper at `0x089DB50C`.
  Its helper `0x088E5EB8` checks the motion context at entity `+0x4C`;
  `0x0881797C` reads motion float `+0x24` and also uses `+0x38`
  and `+0x34` to account for animation loop/wrap behavior. This confirms
  the historical `player+0x4C` motion pointer and `motion+0x24` frame
  offset, but raw `+0x24` does **not** always equal the value returned
  by `GetMotionNowFrame`.

A separate bounded string scan of `.rodata` finds NUL-terminated native
script/event names:

| Callback name | Count | Referenced VA(s) |
| --- | ---: | --- |
| `OnHitAttack` | 3 | `0x08B2DE9C`, `0x08B2FE34`, `0x08B31C04` |
| `OnHitBody` | 2 | `0x08B2DF70`, `0x08B31C88` |
| `OnHitAttackBg` | 1 | `0x08B2DEAC` |

This is **event-name presence** only, not proof of callback invocation,
who owns the hit, which actor is the target, or which memory field changes.
The names may belong to different script namespaces/classes. They are
promising candidates for a correct hit-event path instead of interpreting
the arbitrary low bits of `player+0x23C`.

### Unresolved static tasks

1. Attribute the event name descriptors to the script dispatcher and their
   receiver types; map how hit callbacks are scheduled.
2. Identify attack-collision target ownership and success/failure state in
   the actual native code. `IsAttacking` alone is insufficient.
3. Map native i-frame flag lifetime through its setter and clearing call
   paths. In particular `EnableInvincible` (`+0x190/0x8000`) and
   `SetPlayerFlagInvincible` (`+0x234/0x1`) are distinct.
4. Find a loader-managed, overlay-safe integration mechanism. The main ELF
   `.bss` extends through `0x09F40E10` and `0x08B70000` remains
   inside a transient overlay region. No executable code cave has been
   authenticated and no historical Stage4/5 injection has been restored.
5. Validate these semantics with automated emulation if a bootable game ISO
   and emulator test harness become available; no manual debugger traces
   are needed for the static work.

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
