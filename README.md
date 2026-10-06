# Birth By Sleep Final ReMix PSP ISO Patcher

Kotlin Multiplatform + Compose Multiplatform app for applying the Better Battle System Stage 5 patch to a supported Kingdom Hearts: Birth by Sleep Final Mix PSP ISO.

The app accepts an `.iso` as its only patch input. It locates `PSP_GAME/SYSDIR/EBOOT.BIN` internally, validates the exact supported EBOOT fingerprint, applies the shared Kotlin patch engine, rebuilds the ISO, reopens the result, and commits a separate output image. The source image is never overwritten.

## Supported image profile

The current verified profile is an ISO9660 image containing the decrypted English-patched EBOOT used by the reference patcher:

| Check | Required value |
| --- | --- |
| EBOOT path | `PSP_GAME/SYSDIR/EBOOT.BIN` (version suffixes such as `;1` are accepted) |
| EBOOT representation | decrypted 32-bit little-endian PSP ELF |
| EBOOT size | 3,589,832 bytes |
| EBOOT SHA-256 | `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7` |
| ISO logical block size | 2048 bytes |

Encrypted PSP PRX containers (`~PSP`/`~SCE`) are identified and rejected before any output is created. No Sony/game decryption keys are distributed in this repository. This is intentional: an encrypted image is never mislabeled as patched, and adding a conversion profile requires a verified, legally distributable key-free implementation and a user-owned ISO parity check. See [validation.md](docs/validation.md).

## User flow

1. Select a source `.iso` with the Android document picker or the native desktop file dialog.
2. The app verifies the ISO directory tree and the strict EBOOT fingerprint automatically. **Verify only** repeats this preflight without writing an output.
3. Choose Combined (default), Camera only, or Combat only. Camera distance and height can be disabled; their accepted ranges are 1.0–12.0 and 0.0–4.0 respectively.
4. Select a separate output path.
5. Patch, cancel safely, and receive a rebuilt ISO whose embedded EBOOT is reopened and compared byte-for-byte with the patched result.

The app does not ask the user to extract EBOOT.BIN, run Python, decrypt files, or repackage an ISO. It does not upload images or collect analytics.

## Patch options

Defaults mirror `reference/bbsfm_psp_bbs_stage5_patcher.py`:

- Combined mode, with right-stick camera support.
- Camera distance enabled at `4.5`.
- Camera height enabled at `1.0`.
- Combat options enabled: hit-aware cancels, invincibility windows, extended defense, command cancels, telemetry, Critical Mode abilities, and Critical Mode passives.
- Strict Steam exclusions disabled by default, matching the reference flag semantics.

The engine refuses unsupported size/hash, ELF program-header layout, occupied code caves, unexpected instruction bytes, camera constants, invalid option values, malformed ISO directory records, ambiguous target paths, and output validation mismatches.

## Implementation notes

- `commonMain` contains the patch model, option validation, pure Kotlin SHA-256, strict Stage 5 patch engine, ISO9660 reader/rebuilder, bounded-memory streaming, progress, cancellation, and tests.
- Android uses the actual OneUI-Compose components from `com.github.TrainerSnow:oneui-compose:0.7.0` for theme surfaces, buttons, checkboxes, and progress. The dependency is Android-only, so desktop uses Compose equivalents with the same rounded-card design language.
- ISO rebuilding preserves source bytes and directory metadata except for the target extent/size fields and volume-space fields that must change. A larger patched EBOOT is appended on a sector boundary and referenced by a new directory extent.
- Desktop output is atomically moved from a temporary file when the filesystem supports it. Android document-provider output is streamed only after validation; temporary staging files are cleaned on success, cancellation, and failure.

## Build

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:packageDeb       # Linux
./gradlew :composeApp:packageMsi       # Windows
./gradlew :composeApp:packageDmg       # macOS
```

The release workflow builds Android and all three desktop distributions. Android release signing is supplied through Actions secrets when configured; otherwise the workflow labels the APK as unsigned and continues to produce an inspectable artifact. Signing keys are never committed.

## Reference and validation

The Python script in `reference/` is retained as the behavioral and byte-parity oracle; the shipped app does not require Python. Synthetic ISO tests cover versioned path lookup, directory-record preservation, in-place replacement, extent growth, unrelated-file preservation, cancellation cleanup, and no-output-on-failure.

The exact patched bytes still require a valid user-owned decrypted EBOOT fixture for an end-to-end Python/Kotlin parity run. No copyrighted ISO or EBOOT is stored in Git or CI. Manual validation steps and the current encrypted-PRX boundary are recorded in [docs/validation.md](docs/validation.md).

## License

This project remains under the repository's GPL-3.0 license. OneUI-Compose is consumed as an Android dependency and retains its own license and notices.
