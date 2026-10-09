# PPSSPP GE menu scaling frame comparison (October 9, 2026)

> **Historical research only.** Pause / Main Menu Scaling and all
> menu-specific patch algorithms were removed on October 9, 2026.
> The comparison below documents the retired experiment and must not
> be interpreted as an active menu scaling implementation.

Source captures: `ULJM05775_0003.ppdmp` and `ULJM05775_0004.ppdmp` (ULJM05775; PPSSPP GE recorder v6). These capture GPU geometry, not source-level MIPS callsites.

| Capture | Entries | Captured pushbuffer | Primitives | Decoded 2D draw calls |
|---|---:|---:|---:|---:|
| 0003 | 2,437 | 798,684 B | 752 | 391 |
| 0004 | 2,428 | 798,348 B | 749 | 388 |

388 2D calls match in order when comparing primitive type, vertex type and unchanged texture UVs. Three non-unique repeated draw records in 0003 were excluded. The files are not explicitly labelled stock/patched; their relative 70% transform is directly visible.

## Identified bug: entire PSP backdrop shrinks

The first 2D quad uses UV `(30,3)-(33,6)`. In 0003, its screen rectangle is **(-40,-50)-(519,323)**, while in 0004 it is **(44,6)-(435,267)**. This is approximately the 70% scale `x' = 240 + (x-240)*0.7`, `y' = 136 + (y-136)*0.7`: a full 559x373 backdrop became 391x261, smaller than the native PSP 480x272 viewport. Menu content can then occupy areas the backdrop no longer covers.

Matching UV and local group geometry to the source-confirmed BBS1 `camp.l2d` (archive offset **204434000**, SHA-256 `20db310957d3296bbceb326d938e1feb750b38f8dcf1f498900a15f118411983`) identifies:

- **Sprite 10**, Group **54**: local `(-280,-186)-(279,187)`, UV `(30,3)-(33,6)`. On screen, origin offset is exactly `(240,136)`.
- **Sprite 14**, Groups **80–88**: nine tiled horizontal strips with UV `(1,57)-(2,96)`, combined local bounds `(-277,-172)-(281,171)`. Both sets of geometry enclose the *entire* native viewport.

These are full-screen background surfaces, not resizable row/button content. The previous scaler protected sprites that were **completely outside** the native viewport, but did not protect sprites **enclosing** it. Consequently both backgrounds were shrunk at 70%.

## Patch and regression rule

When `preserveMenuAnchors` is enabled, preserve an entire SP2 Sprite's original geometry if the union of its quad bounds satisfies:

```kotlin
minX <= -240 && maxX >= 240 && minY <= -136 && maxY >= 136
```

Retain the existing protection for sprites wholly outside the window; permit *partial* overscan in smaller sprites, to avoid undoing ordinary UI scaling. The regression tests cover the exact Group 54 bounds plus a reconstructed nine-strip Sprite 14, checking all 70–95% percentages. All eight menu resource digest profiles were regenerated from the user's matching original DAT / BBS0 exported data.

**Caveat:** this is a source- and GPU-evidence-based correction, but not proof of complete screen alignment. PPSSPP's virtual controls are also an independent overlay. After a new build, test Menus 70% vs Menus 100% on the same in-game menu: the first full-screen backdrop should remain full-size while menus, buttons and text still scale. If controls still misalign, compare the relevant draw pairs and their texture UVs before changing another coordinate policy.
