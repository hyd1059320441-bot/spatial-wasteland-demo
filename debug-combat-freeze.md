# Combat freeze and input investigation

Session: combat-freeze
Status: OPEN
Device: B3110 / PB331XPGL6040019G
SDK: public 6.2.6

## Symptoms

Combat eventually freezes the scene. Earlier report also describes firing without pulling
the trigger. The default white controller ray remains visible after the HUD-only workaround.

## Hypotheses

1. Navigation repeatedly computes expensive/failed A* routes on the ECS main thread.
2. Combat is deliberately stopped by death, pause, stale tracking or Activity lifecycle.
3. Trigger boolean and analog value disagree, or a stale pressed sample is reused.
4. Native effects/animation updates accumulate work or resources during combat.

## Evidence

Live B3110 reproduction confirmed a 443.55 ms synchronous navigation update; the
120-tick probe spent 1135.87 ms in main-thread A*. After moving A* to a bounded
single worker, the equivalent probe's main-thread request time was 0.45 ms and
worst tick 33.85 ms. The layout also changed to add missing structural collision,
so the whole probe duration is not an isolated microbenchmark.

Post-fix live combat: 120028 ms, 3465 ECS frames, 395 shots, maximum application
update 27.93 ms, sampled median approximately 2.38 ms. No crash in the current PID
910 log. Skeletal joint transforms, 60 m ray hit, wall occlusion, capsule movement,
retriggered impact/tracers and mission selfchecks passed. 28 unit tests passed.

Hypothesis 1 confirmed as a source of long main-thread stalls. Empty-route retry
also reproduced in a regression test and now respects the interval. Hypothesis 2
is not a reproduced combat failure: current inactive controller evidence says
PENDING/DEVICE_NOT_SUPPORTED. Hypothesis 3 cannot be verified with real inputs
while controllers are disconnected; analog-only release gating has regression
coverage. Hypothesis 4 not reproduced in the 120-second pooled-effects/animation run.

Default white system ray remains unresolved: no matched public SDK toggle found.
HUD input disabling/removing InteractableComponent is not evidence of ray hiding.

Artifacts: `artifacts/2026-10-15-combat-repair/verification.md`,
`runtime-evidence.ndjson`, `device-post-fix.log`.
Run labels: `pre-fix` / `post-fix`, Debug Server 127.0.0.1:7777 via adb reverse.
Keep OPEN until user confirms the controller/visual outcome or aborts debugging.

## Requested corrections

Preserve first person initially. Longer weapon range; visible moving tracers and hit feedback;
star crosshair; real skeletal walking; remove ambiguous red dot; consistent player and
enemy-damage occlusion. Third person is a fallback if the first-person implementation cannot work.
