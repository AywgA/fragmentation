# Debug Session: trajectory-shield-routing
- **Status**: [OPEN]
- **Issue**: Cover ricochet/fragment transitions leave a visible trajectory gap, and passive or modded shields are not recognized by the configured shield protection path.
- **Debug Server**: http://127.0.0.1:7777/event
- **Log File**: .dbg/trae-debug-log-trajectory-shield-routing.ndjson

## Reproduction Steps
1. Enable trajectory display and fire through configured cover until ricochet or fragmentation triggers.
2. Observe the missing segment between the cover impact and the resumed yellow trajectory.
3. Hold a configured modded shield without using the item and fire from its facing side.
4. Compare shield, player health, and worn armor durability before and after the hit.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Replacement ricochet/fragment projectiles begin recording only after their first later tick, so no connector is emitted from the impact point to their first sampled position. | High | Low | Pending |
| B | Cover impact handling terminates or clears the parent trajectory before the chosen transition segment is sent. | High | Low | Pending |
| C | Penetration, ricochet, and fragmentation branches run in an incorrect order, moving the projectile or spawn origin past the impact before transition recording. | Medium | Medium | Pending |
| D | Shield discovery requires isUsingItem and therefore rejects passive modded shields that block while merely held. | High | Low | Pending |
| E | A modded shield cancels the TACZ entity-hit event before the configured shield damage path can observe the impact. | Medium | Medium | Pending |

## Log Evidence
Instrumentation added for:
- Cover ricochet, penetration, and fragment transition positions.
- Explicit transition segments and each projectile's first post-transition flight segment.
- TACZ hit event entry/tail cancellation state and configured shield selection.

Pre-fix evidence:
- `trae-debug-log-fragment-impact-order.ndjson:10-12` shows a cover
  ricochet transition followed by the next flight segment only after TACZ has
  advanced the projectile by a full physical frame. The transition renderer
  covers only 3 blocks while that frame is about 37 blocks, confirming A.
- `trae-debug-log-fragment-impact-order.ndjson:25-27` repeats the same
  discontinuity on another ricochet trace, rejecting packet loss as the cause.
- `TaczGunEventHandler.getActiveShieldSlot` returns null unless
  `isUsingItem()` is true, confirming D for passive vanilla and modded shields.
- The main TACZ handler did not opt into canceled events and its LOWEST
  observer did not restore the configured directional result, confirming that
  a shield mod can preempt or re-cancel the hit path (E).

## Verification Conclusion
Confirmed root causes:
1. Fixed-length 2/3-block transition lines do not span TACZ's remaining
   same-tick movement after a ricochet or fragment is initialized.
2. Passive configured shields are excluded by the active-use gate.
3. A modded shield can cancel the TACZ event independently of this mod's
   directional shield decision.

Post-fix implementation:
- Ricochet and fragment transitions now render the complete first movement
  frame from the impact/spawn point, clipped against blocks and entities.
- Ricocheted TACZ bullets preserve enough `pierce` count to survive TACZ's
  post-hit decrement, while fragments are initialized with a valid hit count.
- Configured shields are discovered in either hand without requiring
  `isUsingItem()`, then protect only within the holder-facing arc.
- The configured shield decision is applied again at LOWEST priority so a
  modded shield cannot leave rear/side hits canceled before this handler
  finishes.

Build verification:
- Version: `5.10.0`
- Artifact: `build/libs/fragmentation-5.10.0.jar`
- `gradlew.bat clean build --no-daemon`: passed (14 tasks).
- JAR inspection confirmed the ballistic renderer, TACZ handler, shield event
  handler, kinetic-bullet Mixin, Mixin config, and `mods.toml`.

Post-fix in-game comparison is pending. The debug server is running at
`http://127.0.0.1:7777/event` with a clean session log.
