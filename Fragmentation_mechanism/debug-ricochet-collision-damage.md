# Debug Session: ricochet-collision-damage
- **Status**: [OPEN]
- **Issue**: Ricochet bullets cross solid walls, ricochet/fragment bullets do no entity damage, and upward-fired bullets disappear before completing a ballistic arc.
- **Debug Server**: Pending startup
- **Log File**: .dbg/trae-debug-log-ricochet-collision-damage.ndjson

## Reproduction Steps
1. Fire at a configured solid cover and make the bullet ricochet into another non-penetrable wall.
2. Make ricochet or fragment projectiles hit a player or mob.
3. Fire vertically upward and observe whether the projectile is removed before descending.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Ricochet projectiles retain a pierce/collision state that skips later block hits | High | Low | Pending |
| B | Ricochet/fragment projectiles are removed or their entity hit event is cancelled before damage | High | Low | Pending |
| C | Special projectiles lose owner/source metadata required by TACZ damage routing | Medium | Medium | Pending |
| D | Upward projectiles are removed by lifetime or low-speed cleanup before gravity can produce descent | High | Low | Pending |

## Log Evidence
Pending pre-fix reproduction.

## Verification Conclusion
Pending pre-fix and post-fix comparison.
