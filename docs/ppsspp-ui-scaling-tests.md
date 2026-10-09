# PPSSPP UI scaling — experimental test checklist

This feature is an **ISO resource geometry patch**, not a PPSSPP
texture scaler. Select independent 70–100% options in 5% increments
on the app's **Setup → UI Scaling** panel. All start at **100% (stock)**.

## What the build changes

- Preserves LY2 top-level Layout X/Y and parentless node positions
  (native PSP screen anchors). Scales only child-node X/Y, SP2 sprite
  group geometry, positive LY2 font sizes and SQ2 position animations
  relative to those unchanged anchors.
- Centered subtitle window/text geometry and font sizes inside the
  verified `BBS0.DAT/CT00000.ctd` resource.
- Every replacement preserves the original asset size and texture UVs,
  and must match the pinned digest for its requested percentage.
- Only changed categories are patched. The original ISO is never overwritten.
- FPS, right-stick, and camera options remain independently selectable.

**Scope:** 111 source-authenticated L2D assets across BBS0/BBS1/BBS2/BBS3,
plus CT00000 (25 subtitle layout rows). The editor now also transforms
SQ2 animated BaseX/Y and OffsetX/Y positions; it intentionally leaves
SQ2 ScaleX/Y animation factors unchanged so already-scaled SP2 geometry
cannot be double-shrunk. 41 original/identical-copy assets have
independently calculated per-percent output digests, and 70 extended
per-scene layouts have exact source SHA-256 plus patch-time output-byte
verification. Dynamic HUD rendering and input hit areas
are not yet adjusted or verified. Consequently the build is intended
for visual/in-game testing, not presented as pixel-perfect HUD scaling.

## Suggested sanity test

1. Use the supported English-patched game ISO with the expected
   decrypted EBOOT; leave unrelated mods unchanged.
2. Create a **control ISO with all UI scaling at 100%**.
   Run it in PPSSPP, keep camera and 30/60fps settings constant.
3. Create a **test ISO at 85%** for one UI category (begin with
   **Command Deck**), keeping every other category at 100%.
   The patcher reports asset changes in **Logs → Export Log**.
4. Verify the patched ISO with **Verify Output** using the **same selected
   percentages**. It compares structure and the reference fingerprints,
   but cannot verify on-screen animation or layout.
5. Compare the same scene, window size, aspect ratio, texture setup and
   UI transition in PPSSPP, including: idle gameplay, scrolling deck,
   HP/Focus/D-Link bar, Shotlock transition, pause menu, and subtitles.
6. Repeat for each category at **95%** and **70%** to capture
   rounding, clipping, anchor and animation issues. Finally, test
   combined settings (for example Command Deck 85%, Gauges 90%,
   Subtitles 95%) and re-enable the HD texture replacements without
   changing the ISO itself.

Run with both **stock textures** and the optional PPSSPP HD pack.
Texture images and replacement identifiers are never scaled by this
patcher; only the game's layout geometry changes.

## Useful bug reports

Include the app **Export Log**, tested scale selections, PPSSPP version,
screenshots or video with the same scene at 100% and the test percentage,
texture mode (stock/HD), and any black-screen/error details.
For an unrecognized or mismatched BBS archive, the patcher fails closed;
the log identifies which exact resource did not match instead of writing
unsupported bytes.

## Guard rails

- 100% writes no scaling overlays.
- No source file is modified; the destination is a separate ISO.
- Preimage SHA-256 is validated before output creation; all output
  spans are compared byte-for-byte to the generated replacement.
- A partial/incompatible output is not treated as successful.
- Verified bytes **do not prove a valid in-game visual result**.

## Corrupted ISO / Android storage-provider safeguards

On Android, **Choose Output** invokes the system `CreateDocument` picker,
which can create an empty placeholder before the patch starts. Do not run
that file in PPSSPP. Only use the result after the app shows
**Patched ISO ready** and the completed commit status.

As of the October 9 SAF integrity fix, the exporter requests explicit
**write + truncate**, then reopens the file and checks its **complete
byte count and streaming SHA-256** against the locally built ISO. A
mismatch is an error, never a successful patch. Aborted or failed
operations attempt to remove the selected incomplete document and clear
the pending output selection. The Android source staging step also
rejects empty, non-sector-aligned and prematurely truncated source ISOs.

If the patched ISO is still rejected by PPSSPP after the corrected build,
compare these three files in order:

1. The **original source ISO** (should launch as before).
2. The app's **Diagnostic rebuild** (unchanged ISO, byte-for-byte
   comparison enabled; should also launch).
3. The **patched output**, initially with all UI controls at 100% and
   other options held constant, then one scale changed to 85%.

Export the app Logs for the failed operation. Capture the first
PPSSPP loader error from the PPSSPP log when possible. Structural ISO
checks cannot guarantee that the PSP executable and modded UI code
will initialize without a game-specific runtime fault.

## Frame-dump-driven resource correction (October 9, 2026)

The first supplied PPSSPP version-6 dump, `ULJM05775_0001.ppdmp`,
contains 8,931 GPU recorder events and 179 decodable 2D/through-mode
render primitives. Comparing the drawn sprite UV rectangles against all
270 catalogued BBS1/BBS2/BBS3 L2D resources identified **bc01_00.l2d** as
the dominant lower-left Command Deck layout:

- BBS1 offset `659696`, length `222544`, SHA-256
  `bfee4970d4a0c8388f619b90f6e0fdada53cfb237ccff6899892a47ec9dd1601`
- BBS1 offset `22768224`, length `222560`, SHA-256
  `9a16df7996b6a98f1f97487138d90bfe4462a26f5b784f56ad72d4ede47baf0c`

Each contains 45 distinct UV rectangles matching the frame dump.
This mapping is a strong indication, not proof that both variants are
loaded by the same scene. The rendered left-side Command Deck features
approximately 84 draw calls with those UV patterns; the existing
`gauge_01.l2d` is independently mapped to the right-side HUD.

Both bc01_00 variants now belong to the **Command Deck** patch option.
Their stock resource hashes, bounds and structure are verified. A single
frame dump does not include the MIPS program counters of the calls that
produced its draw data, nor can it prove whether the patch changed
presentation. Compare a **stock 100% frame dump** with a **patched 70%
frame dump** from the same location/scene to verify real rendered changes.


## Root-anchor placement correction (October 9, 2026)

The supplied game screenshots demonstrated that reducing UI size worked,
but entire panels drifted inward. Inspecting original LY2 data showed
absolute screen placement such as `(-240,-136)`, `(-239,40)`,
`(187,103)`, and `(235,-130)`, measured from screen center.
Applying `percent / 100` to those absolute positions shifts whole panels
toward the center by up to **72 horizontal and 41 vertical PSP pixels**
at 70%. This is not a scaling of UI size.

The new transform keeps all LY2 Layout X/Y placements and all nodes with
`Parent IDX < 0` at their exact original screen positions. It scales
child-relative node coordinates, sprite vertices, font sizes, and SQ2
translation keys without touching UVs, animation timing, or colors.
The existing 16-byte L2D archive padding safety checks remain unchanged.

Reference SHA-256 prefix profiles for **all 29 original L2D resources**
were independently regenerated at 70%, 75%, 80%, 85%, 90% and 95%.
All 70 scene-specific/alias entries retain original SHA-256 validation,
and output bytes are independently checked after ISO rebuild. These are
offline integrity checks; final visual placement must still be evaluated
in PPSSPP.

**Next comparison:** With identical in-game save and PPSSPP settings,
compare Command Deck 70% and 100%, then Menu 70% and 100%. Check left
screen margin and right-screen gauges, alignment between menu background,
selection bars and text, and animation transitions. If a specific widget
still drifts, record its screen position and category before adding a
targeted secondary anchor adjustment.
