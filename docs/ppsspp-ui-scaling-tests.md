# PPSSPP experimental UI scaling — active test checklist

**Active supported settings:** Combat HUD, Command Deck, HP / Focus / D-Link,
Character portraits, Shotlock, and Subtitles. Each is selectable from
70% through 100% in 5% increments, default 100%.

**Retired:** Pause / Main Menu Scaling. It has been deleted from the user
interface and ISO patch code. The patcher no longer modifies `camp.l2d`,
`pause.l2d`, `t_menu.l2d`, `info_00.l2d`, `c_help.l2d` or
`c_icon_00.l2d` through UI scaling.

## How to verify

1. Start with a matching **original** unmodified ISO. An ISO produced by
   an older build with menu scaling cannot be restored to stock merely by
   turning a control off in the newer build; build a fresh output.
2. First patch with the six remaining controls at 100% and confirm the
   normal pause menu still works in PPSSPP.
3. Patch a second output with only **Command Deck = 70%** and all other
   scales at 100%. Verify the battle HUD visually from a normal in-game
   save, not a savestate.
4. Repeat separately for Combat HUD, Gauges, Portraits, Shotlock and
   Subtitles. Note which in-game scene was used for each comparison.
5. Use **Verify Output** or the patcher logs to validate selected
   replacement bytes. This cannot independently prove alignment in the
   PPSSPP renderer.

## Integrity checks

- **101** permitted L2D source entries: 21 original resources, 11
  identical-copy aliases and 69 scene-specific variants.
- **22** independent output digest profiles: 21 original L2D layouts
  and one subtitle CTD source.
- The menu source entries, all eight menu digest profiles and the
  experimental menu-only geometry transform have been removed.
- Original archive size, SHA-256, pointer bounds, and ISO patch overlay
  collision checks remain enforced for enabled categories.

**Visual confirmation is still required for the remaining six categories.**
