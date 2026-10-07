# Birth By Sleep - Final ReMix PSP ISO Patcher

- [x] Foundation: KMP/Compose project and shared contracts
- [x] Patch engine: strict Stage 5 Kotlin port and tests
- [x] ISO: reader, safe rebuild, validation, and synthetic tests
- [x] UI/platform: Android picker, desktop dialogs, adaptive flow
- [x] CI/docs: two workflows, requirements, build instructions
- [x] UI refinement: concept palette, compact/large-screen layout, Detected Game pane, feature toggles, Info page
- [x] Output verification: separate picker and patched-image validation result
- [x] Packaging: portable Windows executable, Linux AppImage, per-platform artifacts, Source.zip, SHA.zip
- [x] Final verification and GitHub branch publication

## Checkpoint: after foundation and patch contracts

- [x] Shared tests pass in the hosted Gradle environment
- [x] No game binaries, ISOs, or proprietary keys are committed

## Checkpoint: after ISO pipeline

- [x] Synthetic replacement/rebuild tests pass in the hosted Gradle environment
- [x] Cancellation and temporary cleanup are covered by tests and platform cleanup paths
- [x] Optional cover-art lookup is covered by a synthetic ISO test

## Checkpoint: after UI and verification

- [x] Compact portrait and large-screen layout follow the concept hierarchy
- [x] Combat features are independently toggleable with trailing switches
- [x] Output verification reports valid, unpatched, incompatible, and malformed cases
- [x] Info links and first-run donation prompt are platform functional

## Checkpoint: complete

- [ ] Android debug/release builds pass
- [ ] Windows portable executable and Linux AppImage packaging pass
- [x] macOS desktop artifact remains available
- [x] Debug and release workflows publish application artifacts, Source.zip, and SHA.zip
- [x] User-owned ISO validation status is documented
