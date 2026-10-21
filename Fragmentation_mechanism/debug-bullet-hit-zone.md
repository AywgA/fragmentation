# Debug Session: bullet-hit-zone
- **Status**: [OPEN]
- **Issue**: Determine TACZ bullet impact body zones without FirstAid and map foot impacts to the corresponding leg.
- **Debug Server**: Unavailable in the current Windows tool environment
- **Log File**: .dbg/trae-debug-log-bullet-hit-zone.ndjson

## Reproduction Steps
1. Start a Forge 1.20.1 test instance with TACZ and Fragmentation.
2. Shoot a player at the head, chest, each arm, each leg, and each foot.
3. Compare the reported ray/impact data and resolved zone for each shot.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Expected signal | Evidence |
|----|------------|------------|--------|-----------------|----------|
| A | The TACZ gun event exposes the bullet's current tick trajectory. | High | Low | `position()` and `deltaMovement` match TACZ's collision segment. | Static source confirmed; runtime pending |
| B | The TACZ trajectory can be intersected with its latency-corrected target AABB. | High | Medium | `HitboxHelper.getFixedBoundingBox` returns the box used for the target. | Static source confirmed; runtime pending |
| C | Foot hits can be represented by the corresponding leg without a foot zone. | High | Low | Every low-height branch returns `LEFT_LEG` or `RIGHT_LEG`. | Static source confirmed; runtime pending |
| D | Removing FirstAid integration eliminates this mod's duplicate FirstAid damage path. | High | Low | No FirstAid source, dependency, or class remains in the output JAR. | Build confirmed; gameplay pending |

## Instrumentation Plan
- Record TACZ event and bullet runtime types.
- Record all candidate impact coordinates and their source.
- Record normalized local hit coordinates and the selected zone.
- Record which damage path executes for the same hit.

## Log Evidence
Runtime evidence is pending.

## Static And Build Evidence
- TACZ 1.20.1 calls `onBulletTick()` before moving the bullet and ray traces from `position()` to `position() + deltaMovement`.
- TACZ `HitboxHelper.getFixedBoundingBox(Entity, Entity)` matches the reflected method signature.
- `clean compileJava` and `build` completed successfully on Java 17.
- The final `fragmentation-4.4.0.jar` contains no FirstAid class.
- The compiled `DetailedHitZone` contains only head, chest, left/right arm, and left/right leg.

## Verification Conclusion
Implementation and build verification passed. In-game hit-zone verification remains pending.
