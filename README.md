# Birth By Sleep Final ReMix PSP ISO Patcher

Kotlin Multiplatform + Compose Multiplatform app for applying **Birth By Sleep - Final ReMix** gameplay, camera, and quality-of-life patches directly to a supported Kingdom Hearts: Birth by Sleep Final Mix PSP ISO.

The app accepts an `.iso` as its patch input. It locates `PSP_GAME/SYSDIR/EBOOT.BIN` internally, validates the exact supported EBOOT fingerprint, applies only the options selected by the user, rebuilds a separate ISO, reopens the result, and can independently verify a selected patched output ISO. The source image is never overwritten.

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

## App flow

1. Select a supported source ISO.
2. The app automatically validates the ISO and reads `PSP_GAME/ICON0.PNG` plus available `PARAM.SFO` metadata for the expandable **Detected game** panel.
3. Expand **Camera & controls** and **Combat modifications** to enable or disable individual patch components. There is no mode selector.
4. Choose a separate output ISO path and patch.
5. Use **Verify Output…** to choose any candidate patched ISO and compare its embedded EBOOT byte-for-byte against the output expected from the currently selected source and options.
6. Open **Info** for the repository source link, GPL-3.0 license link, and placeholder Ko-fi integration.

The donation prompt is shown at launch until the user selects **Do not show again**. The current Ko-fi destination is intentionally a placeholder.

## Patch options

Camera options are independent:

- Right-stick camera control
- Camera distance override, default `4.5`, accepted range `1.0–12.0`
- Camera height override, default `1.0`, accepted range `0.0–4.0`

Combat modifications are grouped into collapsible submenus and remain individually toggleable:

- Hit-aware cancels
- Invincibility windows
- Extended defense
- Command cancels
- Strict category exclusions
- Critical Mode abilities
- Critical Mode passives
- Runtime telemetry

The engine refuses unsupported size/hash, ELF program-header layout, occupied code caves, unexpected instruction bytes, camera constants, invalid option values, malformed ISO directory records, ambiguous target paths, and output validation mismatches.

## Interface

The shared Compose UI uses a dark navy/blue visual system inspired by the project concept layouts. Phone layouts stack the workflow vertically, while larger Android windows/DeX and desktop targets use wider paired panels and split option categories without duplicating navigation.

Android continues to host the app inside OneUI-Compose while the shared Material color tokens keep Android and desktop visually consistent.

## Build outputs

Both debug and release workflows produce application files directly rather than application ZIP bundles:

- Android: `.apk`
- Windows: portable self-contained `.exe` wrapper; no installer
- Linux: `.AppImage`
- macOS: `.dmg`
- `Source.zip`
- `SHA.zip`, containing `SHA256SUMS.txt`

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:createDistributable
./gradlew :composeApp:packageDmg
```

Platform packaging helpers live under `packaging/`. The release workflow supplies Android signing from Actions secrets when configured; otherwise it produces an explicitly named unsigned release APK.

## Reference and validation

The Python script in `reference/` is retained as the behavioral and byte-parity oracle; the shipped app does not require Python. Synthetic ISO tests cover versioned path lookup, directory-record preservation, in-place replacement, extent growth, unrelated-file preservation, cancellation cleanup, and no-output-on-failure.

The exact patched bytes still require a valid user-owned decrypted EBOOT fixture for an end-to-end Python/Kotlin parity run. No copyrighted ISO or EBOOT is stored in Git or CI.

## License

This project is distributed under the repository's GPL-3.0 license. OneUI-Compose is consumed as an Android dependency and retains its own license and notices.
