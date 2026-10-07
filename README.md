# Birth By Sleep - Final ReMix PSP ISO Patcher

Kotlin Multiplatform + Compose Multiplatform app for applying the Birth By Sleep - Final ReMix patch set to a supported Kingdom Hearts: Birth by Sleep Final Mix PSP ISO.

The app accepts an `.iso` as its only patch input. It locates `PSP_GAME/SYSDIR/EBOOT.BIN`, validates the supported decrypted EBOOT fingerprint, applies the selected features, rebuilds the ISO, reopens the result, and commits a separate output image. The source image is never overwritten.

## Supported image profile

The current verified profile is an ISO9660 image containing the decrypted English-patched EBOOT used by the reference patcher:

| Check | Required value |
| --- | --- |
| EBOOT path | `PSP_GAME/SYSDIR/EBOOT.BIN` (version suffixes such as `;1` are accepted) |
| EBOOT representation | decrypted 32-bit little-endian PSP ELF |
| EBOOT size | 3,589,832 bytes |
| EBOOT SHA-256 | `8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7` |
| ISO logical block size | 2048 bytes |

Encrypted PSP PRX containers (`~PSP`/`~SCE`) are identified and rejected before any output is created. No Sony/game decryption keys are distributed in this repository. See [validation.md](docs/validation.md).

## User flow

1. Select a source `.iso` with the Android document picker or native desktop file dialog.
2. Review the expandable Detected Game pane. When present, the patcher loads PSP cover art from `PSP_GAME/ICON0.PNG`.
3. Enable or disable individual camera and Combat Mods. Combat Mods are grouped in an expandable category, with advanced compatibility controls nested beneath it.
4. Select a separate output path.
5. Patch, cancel safely, and receive a rebuilt ISO whose embedded EBOOT is reopened and checked.
6. Open Logs, use Verify Output to pick an ISO, and review the embedded patch verification result. Texture installation and verification remain separate.
7. Export the live operation log to a user-chosen location. The filename uses the detected game ID and local date; when the ISO has no valid ID, the filename clearly says the serial is unavailable.

The app does not ask the user to extract EBOOT.BIN, run Python, decrypt files, or repackage an ISO. It does not upload images or collect analytics.

## Patch options

Defaults are enabled for right-stick camera control, camera distance `4.5`, camera height `1.0`, and the Combat Mods. Every feature is independently toggleable:

- Right-stick camera control
- Camera distance and camera height
- Hit-aware cancels
- Invincibility windows
- Extended defense
- Command cancels
- Telemetry
- Critical Mode abilities
- Critical Mode passives
- Optional strict category exclusions

Camera distance accepts `1.0–12.0`; camera height accepts `0.0–4.0`. The engine refuses unsupported size/hash, ELF program-header layout, occupied code caves, unexpected instruction bytes, invalid option values, malformed ISO directory records, ambiguous target paths, and output validation mismatches.

## UI and links

The portrait and large-screen layouts use the same dark navy, electric-blue, pale-blue, and success-green visual language. The Info page exposes Source Code, the GNU License File, and a placeholder Ko-fi link. A first-run Ko-fi placeholder prompt can be dismissed or disabled with “Do not show again.”

## Implementation notes

- `commonMain` contains the patch model, option validation, pure Kotlin SHA-256, strict patch engine, ISO9660 reader/rebuilder, bounded-memory streaming, output verification, progress, cancellation, cover-art extraction, and tests.
- Android pins the SESL8 One UI 8 Compose fork at `7cd7c88d1232f6269caa333deb6edce015a13779`, whose successful `One UI 8 Compose demo APK` run also verifies release AAR publication and its dependency metadata. The workflow publishes a demo APK and reports; the application resolves the library from the pinned JitPack commit. Desktop uses shared parity adapters for the controls that library does not publish for JVM.
- ISO rebuilding preserves source bytes and directory metadata except for target extent/size fields and volume-space fields that must change.
- Desktop output is atomically moved from a temporary file when the filesystem supports it. Android document-provider output is streamed only after validation; temporary staging files are cleaned on success, cancellation, and failure.

## Build

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:packageAppImage   # Linux
./gradlew :composeApp:createDistributable # Windows portable app image with .exe and runtime
./gradlew :composeApp:packageDmg        # macOS
```

The debug and release workflows publish individual Android APK, Linux AppImage, Windows portable app image, and macOS DMG artifacts, plus `Source.zip` and `SHA.zip`. The Windows artifact contains a runnable `.exe` with its adjacent `lib` and `runtime` directories; extract the artifact and run the `.exe` inside `bin`. No installer is used for Windows. Workflow checks inspect the Windows PE header, Linux ELF type, and macOS disk image before upload. Android release signing is supplied through Actions secrets when configured; otherwise the workflow continues with an unsigned artifact.

## Reference and validation

The Python script in `reference/` is retained as the behavioral and byte-parity oracle; the shipped app does not require Python. Synthetic ISO tests cover versioned path lookup, cover-art extraction, directory-record preservation, in-place replacement, extent growth, unrelated-file preservation, cancellation cleanup, and no-output-on-failure.

The exact patched bytes still require a valid user-owned decrypted EBOOT fixture for an end-to-end parity run. No copyrighted ISO or EBOOT is stored in Git or CI. Manual validation steps and the encrypted-PRX boundary are recorded in [docs/validation.md](docs/validation.md).

## License

This project remains under the repository's GPL-3.0 license. OneUI-Compose is consumed as an Android dependency and retains its own license and notices.
