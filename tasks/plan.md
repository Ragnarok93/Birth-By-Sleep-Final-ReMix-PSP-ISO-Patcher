# Implementation Plan: Birth By Sleep Final ReMix PSP ISO Patcher

## Overview

Build a focused Kotlin Multiplatform + Compose Multiplatform application for Android, Windows, Linux, and macOS. The app accepts a supported Kingdom Hearts: Birth by Sleep Final Mix English-patched ISO, verifies and patches `PSP_GAME/SYSDIR/EBOOT.BIN` internally, rebuilds a separate ISO safely, and validates the result before reporting success.

## Architecture Decisions

- Keep the patch contract and patch engine in `commonMain`; platform code only supplies file selection, temporary storage, and output commit operations.
- Use a small shared ISO9660 reader/rebuilder. It parses directory records, matches the target path case-insensitively while removing ISO version suffixes, and appends a larger replacement at a sector boundary rather than overwriting adjacent data.
- Treat the exact decrypted ELF fingerprint as a hard trust boundary. Encrypted or otherwise unrecognized EBOOT representations fail with an actionable error until a verified, key-free conversion profile is available; no checks are weakened and no proprietary keys are shipped.
- Keep the Python patcher as a non-runtime reference artifact/documented parity oracle. Kotlin patch bytes, defaults, option bits, and structural checks mirror it exactly.
- Use the actual `oneui-compose` Android artifact (`com.github.TrainerSnow:oneui-compose:0.7.0`) only in Android source where its APIs are available. Shared and desktop UI use Compose Multiplatform/Material primitives with matching OneUI tokens and component structure.
- Rebuild with temporary output plus atomic move/commit where the selected platform supports it. The source ISO is never modified.

## Task List

### Phase 1: Foundation and patch contract

- [x] Scaffold KMP targets for Android, JVM desktop, and native desktop packaging.
- [x] Define shared patch options, validation results, errors, progress, cancellation, file service, and ISO reader interfaces.
- [x] Add strict option boundary tests and unsupported-fingerprint tests.

### Phase 2: Patch engine

- [x] Port the Stage 5 reference constants, patch payloads, ELF checks, camera/combat option mapping, and output verification to Kotlin.
- [x] Add byte-level patch tests using synthetic negatives and document the local-only user-owned fixture parity check; do not commit game binaries.

### Phase 3: Direct ISO patching

- [x] Implement ISO9660 PVD/directory-record parsing and target-path lookup with version-suffix handling.
- [x] Implement sector-safe replacement, directory-record and volume-size updates, streaming copy, temporary-file cleanup, and output re-open validation.
- [x] Add synthetic ISO tests for path lookup, replacement, extent growth, metadata preservation, cancellation, and failure-before-output.

### Phase 4: User flow and platform integration

- [x] Implement adaptive Compose UI for source selection, preflight, patch options, output selection, progress/cancel, and completion/error states.
- [x] Use Android document picker/content URIs and platform desktop file dialogs without broad storage permissions.
- [x] Apply Android OneUI-Compose components directly where compatible and provide shared desktop equivalents.

### Phase 5: Documentation and CI

- [x] Document supported image/version requirements, encrypted/unsupported errors, patch semantics, local-only parity validation, and build commands.
- [x] Add only `.github/workflows/multi-platform-release-build.yml` and `.github/workflows/multi-platform-debug-build.yml` as primary workflows, with least-privilege permissions and platform-labelled failures.
- [ ] Run focused tests, full shared tests, and available desktop/Android builds; record any validation requiring a user-owned ISO.

## Checkpoints

### Foundation checkpoint

- Shared contracts compile.
- Validation tests pass.
- No game binary or key is present in the repository.

### Core patching checkpoint

- The Kotlin engine rejects every unsupported fingerprint/structural condition before mutation.
- A valid local-only fixture, when supplied, produces the same bytes as the Python reference.

### ISO checkpoint

- Synthetic ISO tests prove target lookup, unrelated-file preservation, safe extent growth, and output re-open validation.

### Completion checkpoint

- The full user flow is available on all requested platforms.
- Both named workflows run the relevant tests/builds and produce clearly labelled artifacts.

## Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Retail EBOOT is an encrypted PRX rather than the reference decrypted ELF | High | Detect representation explicitly, preserve strict hash/layout checks, and fail safely with a precise message unless a verified conversion profile is available. Never ship proprietary keys. |
| Patched EBOOT grows beyond its original ISO extent | High | Append at a sector boundary, update both-endian ISO directory fields and volume-space metadata, and re-open the rebuilt image before success. |
| Android OneUI library is Android-only | Medium | Keep shared UI API-neutral, use OneUI directly in Android source, and implement desktop equivalents with matching tokens and semantics. |
| No copyrighted fixture is suitable for CI | Medium | Use synthetic ISO fixtures and a local-only fixture path/test hook; document manual byte-parity validation as pending when absent. |

## Open Questions

- A user-owned supported ISO is required to prove the complete encrypted/decrypted representation path and final in-emulator behavior; CI must remain self-contained.
- If the target English-patched ISO is encrypted, a verified conversion method/key source must be supplied or approved before claiming encrypted-image support.
