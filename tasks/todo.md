# Birth By Sleep Final ReMix PSP ISO Patcher

- [x] Foundation: KMP/Compose project and shared contracts
- [x] Patch engine: strict Stage 5 Kotlin port and tests
- [x] ISO: reader, safe rebuild, validation, and synthetic tests
- [x] UI/platform: Android picker, desktop dialogs, adaptive OneUI flow
- [x] CI/docs: two workflows, requirements, errors, build instructions
- [ ] Final verification and GitHub branch publication

## Checkpoint: after foundation and patch contracts

- [ ] Shared tests pass in the hosted Gradle environment
- [x] No game binaries, ISOs, or proprietary keys are committed

## Checkpoint: after ISO pipeline

- [ ] Synthetic replacement/rebuild tests pass in the hosted Gradle environment
- [x] Cancellation and temporary cleanup are covered by tests and platform cleanup paths

## Checkpoint: complete

- [x] Android and desktop debug/release build paths are configured
- [x] User-owned ISO validation status is documented
