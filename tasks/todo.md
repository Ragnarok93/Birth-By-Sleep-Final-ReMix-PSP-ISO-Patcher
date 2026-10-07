# Birth By Sleep Final ReMix PSP ISO Patcher

- [x] Foundation: KMP/Compose project and shared contracts
- [x] Patch engine: strict Stage 5 Kotlin port and tests
- [x] ISO: reader, safe rebuild, validation, and synthetic tests
- [x] UI/platform: Android picker, desktop dialogs, adaptive flow
- [x] CI/docs: two workflows, requirements, build instructions
- [ ] UI refinement: concept palette, compact/large-screen layout, Detected Game pane, feature toggles, Info page
- [ ] Output verification: separate picker and patched-image validation result
- [ ] Packaging: portable Windows executable, Linux AppImage, per-platform artifacts, Source.zip, SHA.zip
- [ ] Final verification and GitHub branch publication

## Checkpoint: after foundation and patch contracts

- [ ] Shared tests pass in the hosted Gradle environment
- [x] No game binaries, ISOs, or proprietary keys are committed

## Checkpoint: after ISO pipeline

- [ ] Synthetic replacement/rebuild tests pass in the hosted Gradle environment
- [x] Cancellation and temporary cleanup are covered by tests and platform cleanup paths
- [ ] Optional cover-art lookup is covered by a synthetic ISO test

## Checkpoint: after UI and verification

- [ ] Compact portrait and large-screen layout follow the concept hierarchy
- [ ] Combat features are independently toggleable with trailing switches
- [ ] Output verification reports valid, unpatched, incompatible, and malformed cases
- [ ] Info links and first-run donation prompt are platform functional

## Checkpoint: complete

- [ ] Android debug/release builds pass
- [ ] Windows portable executable and Linux AppImage packaging pass
- [ ] macOS desktop artifact remains available
- [ ] Debug and release each publish application artifacts, Source.zip, and SHA.zip
- [ ] User-owned ISO validation status is documented
