# Debug Session: fragment-impact-order
- **Status**: [OPEN]
- **Issue**: Fragmentation can coexist with parent-bullet penetration/damage, special bullets do not damage entities or continue beyond about three blocks, spawned bullets are not fully independent, armor ricochet does not trigger, and armor trajectories snap to entity feet.
- **Debug Server**: http://127.0.0.1:7777/event
- **Log File**: .dbg/trae-debug-log-fragment-impact-order.ndjson

## Reproduction Steps
1. Enable `/ftrajectory on` and `/fdebug`.
2. Fire a fragmentation-enabled bullet at configured armor and inspect parent and fragment trajectories.
3. Fire the same bullet at configured cover and inspect fragment spawn sides.
4. Repeat with penetration values above and below 20.
5. Trigger a wall ricochet and a wall fragmentation, then place a living target in each outgoing path.
6. Let a ricocheted or fragmented projectile hit a second configured cover block.
7. Shoot configured helmets and chest armor from front, side, above, and below.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Parent bullet continues into penetration/damage after fragmentation succeeds | High | Low | Confirmed: lines 66-67 and 101-102 |
| B | Fragment directions are sampled only in the forward cone | High | Low | Confirmed: lines 64-65, 73-75, 98-100 all have positive forward dot |
| C | Fragment bullets retain independent cover collision/ricochet handling | High | Low | Confirmed: lines 103, 106, 109 show independent fragment cover impacts |
| D | A fixed penetration >= 20 gate prevents low-penetration bullets from reaching probability calculation | High | Low | Confirmed: line 1 rejects penetration 2; line 63 accepts penetration 20 |
| E | Armor ricochet reaches a defensive surface but is rejected by entity ricochet calculation | High | Medium | Confirmed branch entry at lines 18 and 21; no entity ricochet success event |
| F | Spawned/special bullets lack valid TACZ owner, ammo, or damage context at entity impact | High | Low | Pending: compare spawn state with gun-damage event entry |
| G | Special bullets inherit terminal lifetime/state or are discarded by TACZ immediately after their first special interaction | High | Low | Pending: compare spawn and post-tick removal/velocity events |
| H | Spawned bullets inherit parent collision/fragment guards, preventing independent second impacts | High | Low | Pending: inspect fragment guards and penetrated-cover set at spawn/impact |
| I | Armor hit location/normal resolves from entity origin or a failed ray clip, and TACZ post-hit handling overwrites ricochet state | High | Medium | Pending: compare render hit point, defensive surface, and post-tick state |

## Log Evidence
Pre-fix instrumentation build `fragmentation-5.5.0.jar` compiled successfully.
Runtime reproduction confirmed the hypotheses below before the fix.

Instrumentation points:
- A: cover/entity fragmentation return followed by parent penetration/damage.
- B: fragment direction dot product and spawn offset relative to impact.
- C: whether a ricochet-triggering projectile is a spawned fragment.
- D: penetration, kinetic energy, and fragmentation eligibility.

Pre-fix reproduction evidence:
- Lines 1 and 63 confirm the hard penetration threshold controls eligibility.
- Lines 66-67 confirm entity fragmentation is followed by parent damage calculation.
- Lines 101-102 and 117-118 confirm cover fragmentation is followed by successful parent penetration.
- Fragment direction samples are all forward-facing; cover samples spawn 0.02 blocks before impact.
- Lines 18 and 21 confirm armor resolves to a defensive surface, but no entity ricochet succeeds.

## Verification Conclusion
Pre-fix root causes confirmed.

Post-fix build `fragmentation-5.6.0.jar`:
- Removed penetration depth from fragmentation eligibility; penetration 0 is covered by the standalone math assertions.
- Preserved the inverse penetration/probability relationship, so lower penetration has a higher fragmentation probability.
- Stops and discards the parent bullet after fragments spawn, and cancels TACZ entity damage.
- Resolves armor impact normals from the previous-to-current bullet segment and guarantees 100% ricochet when both configured rates are 100% at any valid angle.
- Marks all flight segments after cover penetration as green penetration trajectories.
- Clips yellow flight segments at entity impacts so they do not visually continue through armor after ricochet or fragmentation.

`clean build`, standalone `BallisticsMathTest`, Mixin remapping, and IDE diagnostics pass. The post-fix log was cleared before handoff; in-game comparison is pending.

## Current Instrumentation Pass (5.7.0)
- F: records special-projectile spawn context and gun-damage event entry.
- G: records special-projectile position, velocity, tick age, and removal state both at the base-entity tick point and at the actual TACZ bullet `tick` return.
- H: records inherited ballistic/collision state and recursive-fragment guard decisions.
- I: records each rendered segment's previous/current/end positions and entity intercept, plus the selected defensive surface and impact normal.
- No ballistic or damage behavior is changed in this pass.
