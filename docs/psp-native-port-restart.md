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

The HD texture workflow is unaffected. The only gameplay patch currently exposed
is the **resident right-stick-only** research profile. Camera distance, camera
height, and all Better Battle System combat options remain disabled.

The app also exposes **Diagnostic rebuild**. It rebuilds the ISO with the original
EBOOT byte-for-byte unchanged, reopens the result, verifies the EBOOT extent and
size are unchanged, and compares the entire rebuilt ISO against the staged
source. A successful diagnostic must be fully byte-identical.

The legacy Stage 4/5 combat payloads and the old Stage 2 overlay helper remain
in-tree only as research artifacts. They are not supported runtime payloads.

## Right-stick re-port

TheOfficialFloW's RemasteredControls Kingdom Hearts implementation is the
behavioral PSP reference. PPSSPP's current controller implementation confirms
that `CtrlData` contains `analog[2][2]`; the second stick occupies bytes
10/11, corresponding to the historical `SceCtrlData.Rsrv[0]/Rsrv[1]` fields
used by RemasteredControls.

The ISO-only candidate now implements the behavior entirely in resident MainApp
code/data:

- The original controller poll at `0x08816688` remains unchanged.
- After MainApp processes its four 16-byte controller records, the final
  sample's right-X/right-Y bytes are loaded together, XORed with `0x8080`,
  and stored in resident padding `0x08B4199A/0x08B4199B`. The original
  zero-filled bytes are therefore already the neutral centered state.
- The existing raw X/Y getter entry points are rewritten as small resident
  dispatchers. Untagged callers branch directly to the original left-stick
  getters at `0x088164D0/0x088164F8`; tagged camera calls signed-load the
  centered right-stick byte. X is negated; Y retains the centered sign because
  runtime testing showed that the game's vertical camera path has the opposite
  polarity convention from the horizontal path.
- The four original float camera JALs remain unchanged. Their previously empty
  delay slots load selector `0x5253` so only those camera calls request the
  right stick.
- The RemasteredControls-equivalent L-modifier and Type-B camera branches are
  disabled in-place.
- ELF program-header count and LOAD sizes remain unchanged, and
  `0x08B6EE7C+` dynamic overlay memory is untouched.

This removes the address-lifetime collision responsible for the old
`0x08B6EE80` payload failure. Runtime testing of the first resident candidate
confirmed that right-stick camera control works and remains compatible with both
in-game camera-control options without interfering with other controls. The v2
candidate corrected horizontal direction but runtime testing showed vertical
direction remained reversed. The current v3 profile keeps X inverted and returns
Y with the native centered polarity. It remains pending confirmation plus the
remaining transition/save-load matrix.

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

Runtime promotion gates:

- Diagnostic rebuild must produce a byte-identical ISO and boot normally.
- The resident right-stick-only candidate must boot, preserve left-stick movement,
  rotate the camera from PPSSPP Right Analog X/Y, and survive scene/module
  transitions plus save/load for Terra, Ventus, and Aqua.
- Combat telemetry-only must boot without gameplay writes.
- Each gameplay behavior must pass isolated runtime validation before inclusion.
- Combined builds are allowed only after each component independently passes.

Release packaging remains blocked until the current payload identity has recorded
runtime evidence.
