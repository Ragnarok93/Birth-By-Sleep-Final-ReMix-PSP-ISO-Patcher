# Validation notes

## Self-contained CI coverage

CI can run without game data. The shared tests use a small synthetic ISO9660 image and verify:

- `PSP_GAME/SYSDIR/EBOOT.BIN;1` lookup and path normalization;
- optional `PSP_GAME/ICON0.PNG;1` cover-art lookup and bounded extraction;
- ISO9660 little/big-endian fields;
- preservation of unrelated directory records and file sectors;
- in-place replacement when the new file fits its allocation;
- sector-aligned append and directory extent updates when it grows;
- output reopen and embedded-EBOOT byte equality;
- patched-output verification states for verified, unpatched, incompatible, and malformed images;
- cancellation and partial-output cleanup;
- strict patch option boundaries and unsupported/encrypted EBOOT rejection;
- byte-identical no-op ISO rebuild behavior;
- resident right-stick invariants: unchanged ELF program headers/LOAD sizes,
  unchanged native controller/camera JALs, and no writes in the dynamic overlay arena.

## Manual parity and runtime check

Use a user-owned decrypted English-patched EBOOT that has the exact reference fingerprint:

```text
size: 3589832
sha256: 8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7
```

The current reference CLI patches only the resident right-stick profile:

```bash
python reference/bbsfm_psp_bbs_stage5_patcher.py EBOOT.BIN --verify-only
python reference/bbsfm_psp_bbs_stage5_patcher.py EBOOT.BIN EBOOT.psp-native-rightstick.BIN
```

Expected right-stick candidate invariants:

- output EBOOT size remains 3,589,832 bytes;
- `e_phnum` remains 2;
- LOAD #0 `p_filesz/p_memsz` remain `0x0036AE64`;
- the legacy overlay region beginning at file `0x0036BE80` remains zero;
- native controller poll `0x08816688` remains `0x0E2C5B4E`;
- the four native camera JALs remain unchanged;
- the deterministic patched EBOOT SHA-256 is
  `28017fe7d0eb9bb7da8f574f30dd8cefbfbd0c0191ddae93865ec28a9b143c3b`.

For application validation:

1. Run **Diagnostic rebuild** first. The app must report the complete output ISO
   byte-identical to the staged source. Boot that ISO in PPSSPP.
2. Create isolated Patch ISO outputs for **60 FPS**, **90 FPS**, and **120 FPS**
   with camera mods disabled. Keep PPSSPP cheats/plugins off and CPU clock at
   default. Record both PPSSPP's reported game FPS and host/display FPS.
3. Validate 60 FPS first. It should reproduce the known native high-frame-rate
   behavior without CWCheat.
4. Treat 90/120 as experimental. Check whether PPSSPP actually presents more
   than 60 unique game frames; separately check gameplay speed, animation,
   command timing, physics, menus, cutscenes, and audio synchronization.
5. Validate right-stick, distance, and height independently and then combined
   with the confirmed FPS profile. Bind the physical second stick to
   **Right Analog X/Y** when right-stick camera is selected.
6. Exercise title -> load -> gameplay -> room transitions -> lock-on -> save/load
   for Terra, Ventus, and Aqua. Record PPSSPP version, device, patched EBOOT
   hash, selected FPS target, and any crash PC/RA in
   `docs/runtime-evidence.json`.

Better Battle System combat options remain disabled until their PSP-native
re-derivation is independently validated.

The fixture must remain outside Git. The repository does not contain game binaries,
ISO images, encryption keys, or a release copy of the fixture.

## Encrypted PRX boundary

Normal PSP UMD images commonly contain encrypted PRX data. The reader detects `~PSP` and `~SCE` headers and reports that the supported profile requires the exact decrypted ELF; it refuses to write an image. The current repository does not ship Sony/game decryption keys. Before enabling an encrypted-input conversion, the conversion must be implemented in shared Kotlin, verified against a user-owned target ISO, and retain the same post-conversion size, SHA-256, ELF-layout, instruction-byte, and code-cave checks.

This explicit failure is safer than accepting a different EBOOT representation or weakening the reference fingerprint gate.

## Failure messages

The app reports the first relevant structural reason, including:

- invalid or non-ISO9660 volume descriptors;
- volume data extending beyond the selected file;
- missing or ambiguous target path;
- encrypted/unknown EBOOT representation;
- unsupported EBOOT size or SHA-256;
- program-header, code-cave, hook, or camera-constant mismatch;
- invalid option ranges;
- existing output path, cancellation, or rebuilt-image validation failure.


## Runtime crash remediation

See [psp-native-port-restart.md](psp-native-port-restart.md) and
[runtime-remediation.md](runtime-remediation.md) for the frozen failing overlay
payloads, resident right-stick design, automated audits, and required PPSSPP
matrix. A structural match is not gameplay validation. Release packaging
requires recorded evidence for the exact resident right-stick profile; debug
builds are used to collect it.
