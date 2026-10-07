# PSP-native gameplay port restart

## Established crash root cause

The previous Stage 5 payload placement is invalid for the supported English-patched
ULJM-05775 executable.

The supported EBOOT ELF section map ends resident MainApp data at the overlay
boundary:

- `.linkonce.d`: `0x08B6C4C8..0x08B6EE7B`
- `.overlays`: starts at `0x08B6EE7C`
- original second `PT_LOAD`: starts at `0x08BB4780`

The old injected payloads were placed inside that overlay arena:

| Payload | VA | Result |
| --- | ---: | --- |
| right-stick S2 | `0x08B6EE80` | four bytes after the overlay boundary |
| combat S4 | `0x08B70000` | inside the overlay arena |
| Stage 5 wrapper | `0x08B71280` | inside the overlay arena |

The supplied PSP module research independently places `TITLE.ELF` and
`FRUIT_BALL.ELF` at `0x08B6EE80`, confirming that the region is reused by
loaded overlays. Extending MainApp `PT_LOAD #0` or adding a third load segment
does not make those addresses resident-safe: loaded modules can replace the same
virtual memory.

This explains both observed runtime failure families:

- right-stick enabled: jump to `00000000` with `RA=08816690` after the
  MainApp controller call was redirected to code at `08B6EE80`;
- right-stick disabled/combat path: changing garbage execution targets with
  `RA=08B10000`, consistent with executing or consuming overwritten payload
  state in the same overlay arena.

The earlier ABI cleanup was still valid hardening, but it could not solve an
address-lifetime collision.

## Immediate product behavior

Gameplay ISO patch output is disabled while the PSP-native implementation is
re-derived. The HD texture workflow is unaffected.

The app exposes a **Diagnostic rebuild** instead. It rebuilds the ISO with the
original EBOOT byte-for-byte unchanged, reopens the result, verifies the EBOOT
extent and size are unchanged, and compares the entire rebuilt ISO against the
staged source. A successful diagnostic must be fully byte-identical.

The low-level legacy Stage 5 engine remains in-tree only as a research reference.
It is not a supported runtime payload.

## Right-stick re-port

TheOfficialFloW's RemasteredControls Kingdom Hearts implementation is the
behavioral PSP reference, not the previous injected overlay blob. Its MainApp
patch locates the native camera patterns, forces Control Type B, and replaces the
camera analog calls with plugin syscalls whose functions call
`sceCtrlPeekBufferPositive` and read `SceCtrlData.Rsrv[0]` /
`SceCtrlData.Rsrv[1]`.

For the ISO-only patcher, do not copy the plugin architecture blindly. Re-derive
an equivalent resident implementation from this exact EBOOT:

1. Keep all code and data outside the `.overlays` arena.
2. Prove any chosen resident code/data location from the ELF section map and
   runtime ownership, not merely from zero-filled bytes.
3. Prefer minimal in-place MainApp changes. Do not extend a load segment into
   `.overlays`.
4. Validate one no-op hook first: original behavior, same registers, same delay
   slots, and successful title/gameplay transitions.
5. Add right-analog sampling only after the no-op hook boots across title,
   load/save, scene transitions, and all three characters.

## Better Battle System re-port

The Steam mod is a behavioral specification only. PC addresses, object layouts,
bit meanings, and lifecycle assumptions are not evidence for the PSP build.

Restart combat work as PSP-native reverse engineering:

1. Telemetry only; no gameplay writes.
2. Identify player/state/command/attack/invincibility fields in the exact
   English-patched EBOOT.
3. Prove field semantics from runtime traces for Terra, Ventus, and Aqua.
4. Implement one behavior per research build.
5. Use native PSP state transitions/functions where possible instead of
   transplanting Steam memory writes.
6. Keep every new code/data allocation outside dynamic overlay memory.

## Validation gates

Before gameplay patching can be re-enabled:

- Diagnostic rebuild must produce a byte-identical ISO.
- A no-op resident MainApp hook must boot and survive scene/module transitions.
- Right-stick-only must boot and operate without touching combat.
- Combat telemetry-only must boot without gameplay writes.
- Each gameplay behavior must pass isolated runtime validation before inclusion.
- Combined builds are allowed only after each component independently passes.

Release packaging remains blocked until the current payload identity has recorded
runtime evidence.
