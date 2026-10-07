# PSP-native runtime revalidation

The Stage 3/4/5 gameplay payloads are now treated as research artifacts, not a
supported PSP port. User runtime testing showed that the corrected Stage 5 ABI
candidate still reproduced both original crash classes with cheats/plugins off:

- right-stick path: destination/PC `00000000`, RA `08816690`;
- post-input/PC-derived path: changing garbage destinations such as
  `2C0D56D7` and `2B9076D7`, repeatedly with RA `08B10000`.

The original Better Battle System mod targeted the PC/Steam game. Its timings and
behavior remain a specification for what the PSP port should eventually do, but
PC addresses, structures, state bits, camera fields and write semantics are not
accepted as PSP evidence.

## Current supported research profile

Only the right-stick camera candidate is patchable. Camera distance, camera
height and all combat modifications are disabled in both defaults and validation
until they are independently re-derived against the exact English-patched
ULJM-05775 PSP executable.

The right-stick candidate no longer hooks the game's main controller poll at
`0x08816688`. Static inspection of the exact supported EBOOT shows that call
already passes the game's 0x70-byte stack input buffer to the controller import.
The previous capture detour was therefore unnecessary and was the common point
for the repeatable RA `08816690` crash.

Instead, the candidate follows the PSP-native strategy in
TheOfficialFloW/RemasteredControls (KingdomHearts/main.c, commit
`a5b75aff53531befb4ddbc319f2b4e8c00e3f2c5`): force the existing Type-B camera
paths and source horizontal/vertical values from
`SceCtrlData.Rsrv[0]`/`Rsrv[1]`. Because an ISO patch cannot allocate a plugin
syscall dynamically, each injected helper uses the game's existing controller
peek import stub at `0x08B16D38`, reads one local `SceCtrlData`, converts the
reserved byte around 128 to the game's -1..1 float, and returns in `f0`.

The helper code stays in the verified zero-filled gap immediately following
LOAD #0 and only extends that existing load segment. It does not add the Stage
4/5 third PT_LOAD segment and does not grow the EBOOT file for the supported
profile.

## ISO isolation

The next diagnostic gate is a no-op rebuild: copy the selected ISO, replace
EBOOT.BIN with its byte-identical original contents, reopen it, and require the
entire output ISO to be byte-identical to the staged source. If that output does
not boot in PPSSPP, the ISO pipeline must be fixed before any executable work is
trusted. If it boots, subsequent failures are isolated to EBOOT modifications.

## Re-derivation order

1. Prove no-op ISO rebuild bootability.
2. Validate the PSP-native right-stick candidate with no other modifications.
3. Re-derive camera distance and height from PSP XREFs/runtime evidence.
4. Build a telemetry-only PSP combat probe with no gameplay writes.
5. Re-enable one combat behavior at a time only after its PSP state ownership and
   transition semantics are demonstrated.
6. Keep release packaging gated until the exact payload under test has recorded
   PPSSPP evidence.

Structural ELF/ISO/hash checks remain necessary but are never labeled gameplay
validation.
