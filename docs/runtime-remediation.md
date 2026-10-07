# Stage 5 runtime remediation candidate

This candidate corrects the capture hook ABI and disables unvalidated combat
writes. It has **not** passed PPSSPP gameplay validation. The retained combat
routine is Stage 4.1 with conservative configuration bits, rather than a claimed
byte-for-byte restoration of the unavailable Stage 3 source.

## Frozen failing reference

Repository commit `f9b2693825fb6b56623a5711ebbbea666db3ac5d` retains all original
payloads and provides a reproducible reference without duplicating them here.

| Payload | Bytes | SHA-256 |
| --- | ---: | --- |
| S2 capture/camera | 370 | `2a7e52eeb0a6ec9afccf565a691a71a790be5f015395f6d8024c5dd87b14ba7a` |
| S4 combat | 4680 | `e4546b76d71d419e3e0553cfd9e7db4b7db692797b4fc9067d61a3655501fa7f` |
| S5 wrapper | 104 | `3aadbe952c3fa1a26470ff6ba109a8a74424fbf3109af4c15e550ea0897eb15b` |

Reported failures: destination `00000000`, RA `08816690` with capture enabled;
destination `2C0D56D7`, RA `08B10000` with cheats/capture disabled. These are
user observations, not reproductions performed by CI. The latter remains an
unlocalized corruption hypothesis; clearing advanced writes does not prove its
cause or resolution.

## Hook contracts

| Hook | Contract |
| --- | --- |
| `08816688` | Replaces a JAL with capture. Calls the original `08B16D38` exactly once with original arguments. Captures the original `a0` pointer in saved `s0`; saves `s0`/`ra` at `sp+24`/`sp+28`, beyond four outgoing argument slots. Restores both and a 32-byte frame; retains `v0`/`v1`. Failed/empty reads and null buffers publish neutral axes. Reads pad bytes +10/+11 only after successful return. |
| X/Y read sites | Regenerated JALs target `08B6EED4`/`08B6EEE0`. Preserve the prior Type-B byte-to-float conversion and its delay slots. Camera cave now occupies 134 bytes, entirely before original LOAD #1. |
| `08816904` | Replaces `lw s0,0x48(sp)` with a JAL. S4 entry reproduces that load using the unchanged caller stack. S5 calls S4 then tail-jumps to original epilogue `08816908`, which restores the real caller RA. Caller-saved scratch/FPU use is retained, and still requires game-site runtime validation. |

The original Stage 1 artifact was not located during this pass. Capture uses its
documented preservation architecture, assembled explicitly from labels, rather
than asserting exact historical byte identity. Game assets are not included.

## Configuration and tests

Default combat byte is `3C`; camera-distance adds `80` for `BC`. Passive config
is zero. Hit-aware cancels (`01`), forced invincibility (`02`), Critical ability
grants (`40`), and Critical passive writes are unavailable in the UI and rejected
by the Python segment builder/Kotlin option validator. Exclusions, defensive
cancels, command cancels, telemetry, and ordinary Square/Circle rules remain.
The dormant advanced code stays in the frozen S4/S5 bytes for future isolated
research, but supported configurations cannot execute those gated writes.

Run `python -m unittest discover -s reference -p 'test_*.py'` and
`python reference/sync_payloads.py --check`. The synthetic capture interpreter
honors delay slots, destroys caller-saved registers, and overwrites all four
argument slots in the nested callee. It reproduced a jump to zero with the old
payload and passes the replacement. Static audits check aligned executable
branch/jump targets and delay slots. Kotlin tests cover conservative defaults,
option rejection, pending runtime status, and combat-code tampering.

Python remains the payload oracle; `reference/sync_payloads.py` regenerates
Kotlin payload bytes and addresses. Both debug/release CI require parity and
the synthetic ABI audit. The verifier compares the whole initialized combat
segment, not just its wrapper/configuration, and reports structural matching
separately from `RuntimeValidation.PENDING`.

## Required emulator work

Use clean user-owned EBOOT/ISO fixtures outside the repository. Record source
and patched EBOOT SHA-256, exact toggles/config byte, PPSSPP version/device,
boot/gameplay result, crash PC/RA, and trace or save-state evidence. Test original
EBOOT, original Stage 1, Stage 3 combat-only, Stage 4.1, frozen Stage 5, corrected
camera-only, conservative combat-only, and this combined candidate separately.
Historical fixtures must be recovered before claiming that matrix complete.

For each of Terra, Ventus, Aqua, validate boot/gameplay, free/lock-on camera,
right-stick movement, guard/Square/Circle and command cancels, Critical Mode,
scene transitions, save/load, and five minutes of normal combat. Record results
in `docs/runtime-evidence.json`; all rows currently remain pending. Release
packaging is gated by `reference/check_runtime_evidence.py`, requiring the exact
current payload signature and evidence references. Debug builds remain available
for collecting those observations. Changing payload bytes invalidates evidence.

Advanced features require separate research builds, one feature at a time:
prove ownership/meaning of player+23C hit state, PSP invincibility transitions,
and Critical ability/passive fields from runtime traces before removing gates.
Neither synthetic MIPS execution nor ISO/ELF/hash checks substitute for this work.
