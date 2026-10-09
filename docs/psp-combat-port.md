# PSP combat mod port — evidence and safety gates

Status: **research / read-only inspector shipped for testing**. No combat
gameplay patches are enabled yet. In particular, building successfully or
matching an ISO byte hash is **not** runtime evidence that a hook is safe.

## Scope and original behavior

The target is an independently configurable PSP-native adaptation of the
Better Battle System combat behavior. The original mod targets the PC port
via runtime Lua memory editing; its process pointers and offsets cannot be
copied to the PSP EBOOT. This project currently exposes these independent
options (all disabled until each one has been validated):

| Option | PC behavior to re-derive | PSP proof required |
|---|---|---|
| Hit-aware cancels | Cancel attacks/finishers after valid hits; character-specific timing | Attack target-state meaning, hit flag lifetimes, timing per Terra/Ven/Aqua |
| Invincibility windows | Dodge, form change, selected long-command windups | State/motion identifiers, iframes, leave-state restoration |
| Extended defense | Guard and evasion cancels; safe recovery | Input state, guard recovery and invulnerability interactions |
| Command cancels | Faster command flow with animation category exclusions | Animation class pointers, commands, cooldowns and safe transition windows |
| Telemetry | Record input/state checks for debugging | Independent ring placement, tick lifetime and valid runtime export |
| Critical Mode abilities | Reload Boost and Second Chance | Validated difficulty and runtime ability-table location |
| Critical Mode passives | Munny Plus, Berserk, Auto-Remedy, Double CP | Player object lifetime, difficulty selection, mask field semantics |
| Strict category exclusions | Avoid canceling disallowed attacks/commands | Category ID lookup, priority and opt-in behavior |

The dormant reference Stage 4 payload mentions PSP player state at
`player + 0x23c`, a possible passive bitmask `0x04008300` at
`player + 0x30`, and character-specific timing candidates. **These
are unverified hypotheses**, not safe production offsets.

## Why the old Stage 4/5 implementation is not enabled

Original MainApp's loaded module / dynamic overlay area begins around
`0x08B6EE7C`. The old research code used:

- Stage 4 payload at `0x08B70000`
- Stage 5 wrapper at `0x08B71280`
- Candidate post-input call site at `0x08816904`

Both payloads are on the wrong side of the overlay boundary. A correct ELF
program header is not enough to keep a dynamic overlay from replacing code.
Further, the existing research `makeConfig` did not provide one-to-one
independent bit control for every combat option. Reactivating it without
re-deriving the state writes could reintroduce crash bugs.

The app keeps the obsolete blob and Python oracle only as historical
research fixtures. The **supported right-stick, 30/60 FPS and native camera**
mod paths are separate and must stay untouched.

## Read-only inspector (first completed work package)

1. Select a supported original ISO in the app.
2. Expand **Combat Mods** in Setup.
3. Press **Inspect combat hooks (read-only)**.
4. Open **Logs** and export the log.

The inspector reads the ISO's embedded EBOOT once. It reports its size/hash
and supported-source match; validates bounded ELF program headers; checks
the original post-input instruction; shows the historical overlay collision;
and exports *three small, bounded 48-byte opcode windows* from MainApp for
manual MIPS analysis. No new ISO is generated, no live memory is changed,
and no combat feature is selected or enabled.

The opcode windows are evidence for disassembly **only**; they are not
proof that a candidate hook is reachable at runtime or preserves registers
and delay-slot behavior.

## October 9, 2026: user-supplied EBOOT inspection

The read-only log for ULJM05775 reports the expected decrypted ELF fingerprint
(`8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7`)
and matching original instruction at `0x08816904`. This authenticates the
**source**, not the behavior of any combat modification.

Three independent blockers emerge:

1. **Overlay lifetime:** both `0x08B70000` and `0x08B71280` are above the
   resident/overlay boundary `0x08B6EE7C`, so the Stage 4/5 payload cannot be
   made live just by allocating a new ELF program header.
2. **Register restoration:** the old proposed `0x08816904` insertion point
   contains `0x8FB00048` (`lw s0, +0x48(sp)`). It is an epilogue restore, not
   an empty call slot. Replacing it with a JAL without re-executing the original
   load violates the calling convention even if the destination code is safe.
3. **No field evidence:** the raw words at `0x08B6A490` resemble a table of
   pointers/data; the six `jr ra`/NOP pairs at `0x08B16D20` resemble stubs.
   Neither establishes combat state ownership, a hit flag, command category,
   invincibility behavior, or a patch-safe player object.

The enhanced inspector now reports ELF32 section ownership and bounded static
JAL cross-references **from executable sections only**. It identifies the
original input restoration as an ABI hazard. Invalid section tables fail closed.
This is intentionally a research tool; static cross-references do not validate
runtime state semantics, indirect calls, dynamic overlays, or gameplay outcomes.

**Next evidence required before writing combat state:** a PPSSPP runtime trace
or debugger capture for at least one native PSP attack/hit/command transition,
including call stack, object identity, relevant field values before/after,
and module load state. Compare Terra/Ventus/Aqua and a neutral baseline.
The Stage 4/5 code remains disabled until that evidence exists.

## Required gates before first enabled combat option

1. **Native mapping:** Match a concrete PSP function and its call sites,
   object lifetime and state offsets. Preserve the exact supported source
   fingerprint; reject any unsupported revision.
2. **No overlay collision:** Use only confirmed resident code/caves or a
   lifecycle-safe loader. Never use `0x08B70000` or an unreserved dynamic
   overlay address merely because it appears free in the ELF file.
3. **ABI and control flow:** Account for displaced instructions, delay
   slots, saved volatile/nonvolatile registers, stack alignment, nested
   JALs and original function continuations.
4. **Isolated feature switch:** Each option needs an independently
   validated behavior path, including its default/off state. No umbrella
   configuration that silently enables extra combat behavior.
5. **Offline integrity:** Exact source patch-site bytes, output checksum
   profile, idempotent 30/60 coexistence tests, and ISO re-open
   verification must pass.
6. **PPSSPP runtime testing:** Repeat gameplay tests with Terra, Aqua and
   Ventus, lock-on, command styles, Shotlock, D-Link, form changes, cutscenes,
   menu transitions, and 30/60 FPS. Verify both unchanged baseline and
   selected behavior; report crashes/softlocks before expanding coverage.

No archived Steam/Epic process offset should be treated as an equivalent
PSP address. A GPU frame dump can reveal graphics, but not combat-state
branch instructions; the ELF evidence and runtime traces are separate.

## Source links

- PC Better Battle System by Xendra:
  https://www.nexusmods.com/kingdomheartsbirthbysleepfinalmix/mods/31
- Older dormant PSP candidate in this repo:
  `reference/bbsfm_psp_bbs_stage5_patcher.py`
- Static tests:
  `reference/test_stage5_runtime.py` and
  `composeApp/src/commonTest/kotlin/com/ragnarok93/bbsremix/patch/CombatPortInspectorTest.kt`
