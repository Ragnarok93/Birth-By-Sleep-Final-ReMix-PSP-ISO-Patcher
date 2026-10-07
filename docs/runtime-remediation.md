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
until independently re-derived against the exact English-patched ULJM-05775 PSP
executable.

The earlier "direct-poll helper" candidate was still placed at `0x08B6EE80`,
which is inside MainApp's dynamic overlay arena. It has been retired.

The current candidate follows the observable PSP behavior of
TheOfficialFloW/RemasteredControls without allocating a plugin syscall or any
overlay-resident code. PPSSPP's `CtrlData` layout provides the second stick at
bytes 10/11. MainApp already polls four 16-byte records through its unchanged
`0x08816688` controller call. The patch captures the final record's bytes
10/11 into resident padding at `0x08B4199A/0x08B4199B`.

The resident raw-axis entry points become selectors: normal callers branch to
the original left-X/left-Y getters, while only the four existing Type-B camera
calls set selector `0x5253` in their JAL delay slots and receive centered
right-stick values. Existing float normalization remains unchanged.

This profile does **not** modify ELF program-header count, LOAD #0 size, add a
third PT_LOAD, or write the legacy `0x08B6EE80` overlay area. Structural tests
enforce those invariants. Runtime validation in PPSSPP is still required.

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


## Current diagnostic gate

The no-op **Diagnostic rebuild** remains available alongside the right-stick-only
candidate. It writes the original EBOOT unchanged, verifies its directory
extent/size, and requires the complete rebuilt ISO to compare byte-for-byte equal
to the staged source before committing the result. This isolates ISO-rebuild
correctness from gameplay-code correctness.
