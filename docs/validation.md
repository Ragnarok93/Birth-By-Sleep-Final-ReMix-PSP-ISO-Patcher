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
- strict patch option boundaries and unsupported/encrypted EBOOT rejection.

## Manual parity check

Use a user-owned decrypted English-patched EBOOT that has the exact reference fingerprint:

```text
size: 3589832
sha256: 8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7
```

For each independent feature selection and option combination:

1. Run the Python reference with `--verify-only` and the selected flags.
2. Run the Kotlin engine through the application or a local test harness.
3. Compare the resulting EBOOT bytes and SHA-256.
4. Put the valid EBOOT at `PSP_GAME/SYSDIR/EBOOT.BIN` in a user-owned ISO and verify the application can rebuild and reopen it.

The fixture must remain outside Git. The repository does not contain game binaries, ISO images, encryption keys, or a release copy of the fixture.

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

See [runtime-remediation.md](runtime-remediation.md) for the frozen failing
reference, hook ABI contracts, conservative configuration, automated audits,
and required PPSSPP matrix. A structural match is not gameplay validation.
Release packaging requires recorded runtime evidence; debug builds can be used
to collect it. All gameplay evidence is currently pending.
