# PPSSPP UI scaling — experimental test checklist

This feature is an **ISO resource geometry patch**, not a PPSSPP
texture scaler. Select independent 70–100% options in 5% increments
on the app's **Setup → UI Scaling** panel. All start at **100% (stock)**.

## What the build changes

- LY2 screen layout/node X/Y, SP2 on-screen sprite group vertices and
  positive LY2 font-size bytes in selected game `.l2d` assets.
- Centered subtitle window/text geometry and font sizes inside the
  verified `BBS0.DAT/CT00000.ctd` resource.
- Every replacement preserves the original asset size and texture UVs,
  and must match the pinned digest for its requested percentage.
- Only changed categories are patched. The original ISO is never overwritten.
- FPS, right-stick, and camera options remain independently selectable.

**Scope:** 29 source-authenticated L2D assets across BBS0/BBS1/BBS3,
plus CT00000 (25 subtitle layout rows). Some scene-specific layouts,
SQ2 animated transform keys, dynamic HUD rendering and input hit areas
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
