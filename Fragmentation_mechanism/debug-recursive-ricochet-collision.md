# Debug Session: recursive-ricochet-collision
- **Status**: [OPEN]
- **Issue**: A bullet can pass through otherwise solid blocks shortly after ricocheting. The ricochet trajectory and bullet hole render, but no subsequent ricochet, fragmentation, stop, or attenuated damage effect is applied.
- **Debug Server**: http://127.0.0.1:7780/event
- **Log File**: .dbg/trae-debug-log-recursive-ricochet-collision.ndjson

## Reproduction Steps
1. Configure a bullet and wall material with a reliable ricochet chance.
2. Fire at a shallow angle so the first wall produces a ricochet.
3. Place another non-penetrable wall along the reflected path.
4. Observe whether the reflected bullet passes through the second wall and whether a bullet hole appears without an impact effect.
5. Repeat with a target behind the second wall and with fragmentation enabled.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Expected Evidence |
|----|------------|------------|--------|-------------------|
| A | TACZ applies an additional full-tick movement after the ricochet transition, stepping beyond a thin wall before the next ray trace. | High | Low | The post-ricochet tick-end position is one reflected movement vector beyond the transition position, crossing the next block before its impact callback. |
| B | A penetrated-cover or transition marker causes subsequent blocks to be treated as already handled. | High | Low | The second impact enters the handler with a skip marker or a matching entry in `penetratedCoverBlocks` and returns without ricochet/fragment/stop processing. |
| C | TACZ and the mod use different ray start/end points after the delta movement is replaced. | Medium | Medium | The native block hit is outside the segment represented by the mod's current position and movement. |
| D | The second impact callback runs, but its branch state is stale after the first ricochet. | Medium | Low | The callback logs a block hit and bullet hole, while ballistic state, speed, or recursion counters prevent all physical branches. |

## Log Evidence
Instrumentation build completed successfully.

Instrumentation points:
- A: TACZ `onHitBlock` callback start/end segment and post-native-tick traveled/future segments.
- B: Cover lookup, tracked-block set, and penetration marker at each impact.
- C: Ricochet impact, transition position, reflected movement, and recursive ricochet count.
- D: Final impact outcome: ricochet, fragmentation, penetration, or unhandled.

Pending pre-fix reproduction with `runId=pre-fix`.

## Verification Conclusion
Pending pre-fix evidence.
