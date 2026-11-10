# Debug Session: rapid-hit-crash
- **Status**: [OPEN]
- **Issue**: Rapid consecutive bullet hits on an enemy can crash the B3110 app. Expected behavior is stable repeated hit processing.
- **Debug Server**: http://127.0.0.1:7777/event (ADB reverse to host server)
- **Log File**: .dbg/trae-debug-log-rapid-hit-crash.ndjson

## Reproduction Steps
1. Launch V3 on B3110.
2. Use the automatic rifle at close range.
3. Hit the same enemy rapidly until the app exits.

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | Same-frame particle emitter retrigger corrupts native particle state | High | Medium | Rejected: 52 shots and particle calls completed; stack is material assignment |
| B | Last hit respawns/reuses an enemy slot before `fire()` finishes processing the old hit | High | Low | Observed but not root cause: line 129 shows slot reuse without exception |
| C | Shotgun or rapid fire processes additional pellets after death against the reused slot | High | Low | Rejected: crash reproduced with one-pellet rifle |
| D | Audio/haptic/effect queues overload under rapid hit events | Medium | Medium | Rejected: no queue/audio frame in crash stack |
| E | Crash originates in Spatial Runtime native code rather than Kotlin | Medium | Low | Rejected: Kotlin `IllegalArgumentException` from closed material |

## Log Evidence
- Device crash buffer contains two identical crashes at 14:01:28 and 14:02:24.
- Exception: `IllegalArgumentException: You can't set material for ModelComponent with a closed UnlitMaterial`.
- Top app frame: `WastelandScene.updateEffects(WastelandScene.kt:360)`.
- Pre-fix NDJSON lines 127-130: final hit transitions slot 2 into the QUARRY wave while both shared materials remain valid.
- Line 131: `whiteValid` changes to `false` while the red hit flash is still active.
- Line 132: hit flash reaches zero; the next frame attempts to restore the already closed white material and crashes.

## Instrumentation
- `A-D`: shot sequence, weapon, pellet count, and shared material validity.
- `B-C`: enemy slot health/mission before and after every sampled hit.
- `A-E`: red/white reticle material validity transitions before assignment.
- `E`: whether scene disposal occurred before the crash.

## Verification Conclusion
Confirmed root cause: local `UnlitMaterial` instances are reused across components. Replacing the last binding lets Spatial SDK auto-release the material even though `SceneKit` still holds the Kotlin object. The next assignment throws. Shared scene resources must call `toGlobal()` and remain manually closed by `SceneKit.close()`.

## Post-Fix Evidence
- Minimal fix: `SceneKit.own()` calls `Resource.toGlobal()`; existing `SceneKit.close()` remains the sole owner cleanup.
- Post-fix rapid fire: 24 sampled shots and one kill completed with `whiteValid=true`, `redValid=true`; process stayed alive.
- Deterministic device self-check: 32 alternating red/white assignments completed with both materials valid.
- Final difficulty build: `SCENE_READY ... robots=12`, material stress PASS, complete four-zone self-check PASS, process remained alive.
- Manual post-fix rapid-fire confirmation is pending; instrumentation and Debug Server remain active.

## Requested Difficulty Update
- Concurrent waves: entry 8, quarry 10, reactor 8 including elite, final 10.
- HP: charger 120, shooter 160, elite 960.
- Damage/speed/cooldown increased per enemy class.
