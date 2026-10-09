# Birth By Sleep - Final ReMix PSP ISO Patcher

Kotlin Multiplatform + Compose Multiplatform app for applying the Birth By Sleep - Final ReMix patch set to a supported Kingdom Hearts: Birth by Sleep Final Mix PSP ISO.

The app accepts an `.iso` as its patch input and validates the supported decrypted English-patched EBOOT fingerprint. The current PSP-native profile supports a **30/60 FPS switch, right-stick camera control, camera distance, and camera height** using only resident MainApp code/data. It does not extend an ELF load segment and leaves the dynamic overlay arena untouched. Better Battle System combat features remain disabled while they are re-derived against the PSP executable. A separate Diagnostic rebuild writes the original EBOOT unchanged and requires the complete output ISO to remain byte-identical. The source image is never overwritten.

## Supported image profile

The current supported source fingerprint is an ISO9660 image containing the decrypted English-patched EBOOT used by the reference patcher:

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
3. Toggle **60 FPS** above the camera options, then select any combination of **Right-stick camera control**, **Camera distance**, and **Camera height**. With the switch off the game keeps stock 30 FPS behavior; with it on the patch forces the validated native 60 FPS mode. Combat Mods remain disabled.
4. Select a separate output path.
5. Use **Diagnostic rebuild** first when validating a new environment. It writes the original EBOOT unchanged and requires the complete output to compare byte-for-byte equal to the staged source.
6. Use **Patch ISO** to create the selected resident PSP-native FPS/camera profile. Right-stick control modifies resident MainApp input/camera instructions. Distance and height update the resident camera table and replace the camera-only 0x70-byte BCam copier in place so loaded camera resources cannot overwrite the selected values. The original two ELF program headers remain intact and the legacy overlay payload region is untouched.
7. Open Logs, use Verify Output to inspect an ISO, and keep texture installation/verification separate.
8. Export the live operation log to a user-chosen location. The filename uses the detected game ID and local date; when the ISO has no valid ID, the filename clearly says the serial is unavailable.

The app does not ask the user to extract EBOOT.BIN, run Python, decrypt files, or repackage an ISO. It does not upload images or collect analytics.

## Patch options

The current PSP-native profile exposes a **60 FPS switch** plus three independent camera features. The switch is off by default for stock 30 FPS behavior. Enabling it forces MainApp's validated native mode-0 60 FPS path without requiring a PPSSPP cheat.

 Right-stick camera direction and compatibility with both in-game camera-control options have passed runtime testing. The first static-table-only distance/height candidate produced no visible change because the game's BCam loader overwrites that table at runtime; the current candidates preserve selected geometry values after every native BCam copy and are pending runtime confirmation. Combat features remain visible for roadmap context but are disabled and rejected by validation:

- 60 FPS — switch off for stock 30 FPS, on for validated native 60 FPS
- Right-stick camera control
- Camera distance — writes the native signed Z component in the two resident player-camera mode vectors
- Camera height — writes the native Y component in the same two resident camera-mode vectors
- Hit-aware cancels
- Invincibility windows
- Extended defense
- Command cancels
- Telemetry
- Critical Mode abilities
- Critical Mode passives
- Optional strict category exclusions

The UI exposes only integer camera levels `1–5`, with **level 1 as the default for both sliders**. Internally, distance levels map to `2.0, 3.0, 4.0, 5.0, 6.0`; height levels map to `1.0, 1.375, 1.75, 2.125, 2.5`. The raw values are patch-engine details and are not shown by the sliders. The engine refuses unsupported size/hash, ELF program-header layout, occupied code caves, unexpected instruction bytes, invalid option values, malformed ISO directory records, ambiguous target paths, and output validation mismatches.


## UI scaling (experimental ISO resource patches)

The Setup page has independent 70–100% controls in 5% steps (stock 100%)
for combat HUD, Command Deck, HP/Focus/D-Link gauges, portraits, Shotlock,
menus, and subtitles. Selecting a non-stock value now **generates real,
same-size UI resource edits in the output ISO** through the existing
`IsoBytePatch` pipeline, alongside any selected FPS/camera EBOOT edits.

The catalog covers 111 source-fingerprinted `.l2d` files inside
`BBS0.DAT`, `BBS1.DAT`, `BBS2.DAT`, and `BBS3.DAT`, plus the 25 centered subtitle
layouts in `BBS0.DAT/CT00000.ctd`. Source sizes and exact per-resource
SHA-256 fingerprints are checked before the output is created. The rebuilder
verifies every preimage and then verifies the exact patched spans in the
completed ISO; the original is never overwritten. No PNG files, PPSSPP HD
texture IDs, or SP2 UVs are changed.

**Screen anchoring:** LY2 Layout X/Y represents absolute screen placement
(relative to the native 480×272 PSP screen center), and root LY2 nodes
(with Parent IDX < 0) represent top-level anchors. The patcher now keeps
those positions unchanged while shrinking child-node offsets, SP2 sprite
vertices, font sizes, and local SQ2 animated translation offsets for
**non-menu** elements. For **Pause / main menus**, the editor now preserves
**all LY2 node X/Y placements and SQ2 Base/Offset animation positions**,
because even child nodes can be positioned in screen coordinates. Menus
instead scale visible SP2 sprites around their local **alignment edges**
(left/top for positive local coordinates, right/bottom for negative
coordinates, and origin when a sprite spans zero). This keeps command-bar
backgrounds attached to text positioned by the runtime. Menu sprites whose local bounds overlap the native
viewport are eligible for the same scaling, even when some vertices lie
outside it; preserving every such sprite left large panels at stock size.
Only sprites entirely outside the local viewport are conservatively held
fixed to avoid drawing offscreen transitions into view. Font sizes remain
scalable.
This policy is experimental pending PPSSPP on-screen checks.
Original-source hashes, output byte checks, and 70–95% reference profiles
were updated for this anchor-preserving transform.

**Experimental limitations:** not all SQ2 animation keys, game-side runtime
transforms, text scissor rectangles, hitboxes, and dynamically positioned
elements have been mapped or visually verified. The feature is not yet a
guarantee of pixel-perfect 70–100% scaling in PPSSPP.
Unrecognized, encrypted, missing or mismatched DAT resources fail closed.
The standalone Verify Output action can detect structurally valid non-stock
resources but cannot authenticate the exact scale value without the source ISO;
patch-time verification *does* compare the exact generated replacements.
Testing with stock and HD textures is still required before treating the
feature as production-stable.

## Texture packs

The texture installer uses a manifest pinned to [AkiraJkr/Birth-by-Sleep-HD-ReMix](https://github.com/AkiraJkr/Birth-by-Sleep-HD-ReMix) commit `b858f34debbd7bd5b17e989cc194ab05a836c2b5` (v1.5.1).

| Serial | Game profile | Coverage |
| --- | --- | --- |
| `ULJM05775` | Japanese Final Mix with the English patch | Complete |
| `ULES01441` | European original release | Partial |
| `ULUS10505` | North American original release | Partial |

The manifest contains 4,903 core PNGs, plus `.nomedia` and `textures.ini`. Optional additions provide 15 regional button swaps and three HD portraits; selecting both additions brings the expanded install plan to 557,373,270 bytes.

The installer targets `PSP/TEXTURES/<serial>` in the selected PPSSPP data tree. It checks the exact archive root, planned sizes, and Git blob SHA-1 values before installation. Files are staged, existing files are not overwritten, and staging is removed after cancellation or failure. Texture verification is separate from ISO patch verification.

## VCDIFF

`commonMain` includes a bounded-memory decoder for the VCDIFF default code table: ADD, RUN, and COPY instructions with SELF, HERE, NEAR, and SAME address modes; source and target windows; and Adler-32 checks. It skips an xdelta application header and validates declared lengths and source/target bounds.

The decoder rejects custom code tables and secondary-compressed sections. Default limits cap each window at 16 MiB, delta data at 64 MiB, and total output at 2,000,000,000 bytes.

The Aqua model patch remains disabled. The available patch cannot be matched safely to an ISO using the supported EBOOT fingerprint alone; enabling it requires a verified full source-ISO fingerprint.

## UI and links

The portrait and large-screen layouts use the same dark navy, electric-blue, pale-blue, and success-green visual language. The Info page exposes Source Code, the GNU License File, and a placeholder Ko-fi link. A first-run Ko-fi placeholder prompt can be dismissed or disabled with “Do not show again.”

## Implementation notes

- `commonMain` contains the patch model, option validation, pure Kotlin SHA-256, strict patch engine, ISO9660 reader/rebuilder, bounded-memory streaming, output verification, progress, cancellation, cover-art extraction, and tests.
- Android UI wrappers use the SESL8 One UI 8 Compose fork pinned at `1fe97b4e5a4ad559a7252376fad650a64310b229`; desktop uses shared parity adapters for the controls that library does not publish for JVM. The fork is tracked as a Git submodule at `third_party/oneui-compose`, and Gradle maps its `com.github.Ragnarok93.oneui-compose:lib` dependency to the submodule's `:lib` project. Initialize the submodule with `git submodule update --init` before building Android from a Git checkout. The CI `Source.zip` artifacts also include the pinned library source at that path, so Android builds use source and do not depend on availability of the published AAR.
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


## Runtime validation status

The previous Stage 5 candidate reproduced both reported crash classes because its S2/S4/S5
payloads occupied MainApp's dynamic `.overlays` arena. The current right-stick implementation
contains **no injected overlay blob**: it preserves the controller poll at `0x08816688`,
captures PPSSPP's second-stick bytes from the game's existing four-record input buffer into
resident MainApp padding, and tags only the four native Type-B camera analog calls. Runtime
testing now confirms correct right-stick directions and operation with both in-game camera
control settings without interference with other controls.

Camera distance and height have also been re-derived from the exact PSP executable. MainApp's
resident working camera table at `0x08B59F00` contains two native camera-mode position
vectors: `[0.0, 1.5, -3.5, 1.0]` and `[0.0, 1.0, -3.5, 1.0]`. Runtime testing proved that
editing only this table is insufficient: the native BCam resource path copies 0x70 bytes over
it through `0x0893DBF4`, replacing the selected Y/Z values.

The current geometry candidate therefore keeps the patched fallback Y (height) and signed Z
(distance) values and replaces that camera-only copier in place with an equivalent resident
word copy followed by the selected component overrides. This preserves unselected components
from the loaded BCam resource, uses no overlay allocation or extra ELF segment, and does not
change ELF program-header count or LOAD sizes. Runtime testing now confirms that both corrected geometry controls affect the live camera as intended. Better Battle System combat ports remain disabled. Verify
Output checks structure and bytes; gameplay validation remains separate. See
[PSP-native gameplay port restart](docs/psp-native-port-restart.md) and
[Runtime remediation](docs/runtime-remediation.md).

## In-app BBS0 UI export

The **HD Textures** page includes a separate **BBS0 UI research export** card
(Android, Windows, Linux and macOS). Select an existing `BBS0.DAT` using a file
picker, optionally enable **Index + manifest only** for a smaller ZIP, and
choose the export destination. This does not patch the ISO or modify the DAT.

The app exports a compact `bbs0-ui.zip` containing the BBSA index, a JSON
manifest of validated ARC-embedded `.l2d` / `.ctd` resources, and up to
12 MiB of matching research assets. ARC links are recorded but not resolved;
some subtitle resources may remain absent. For portability, Android first
copies the source DAT into private temporary storage, which requires free space
roughly equal to its size. Temporary data is deleted after completion or
cancellation. Files are never uploaded automatically.

The standalone Python exporter remains available for command-line use.
