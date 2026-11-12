# Navigation performance report (16:10 log) and what changed

## What the log measures

The log has one-second timestamps, so these figures are good to about a second. Route planning is the only
thing it times more finely.

| What | Measured in the log | Share of the time lost |
| --- | --- | --- |
| Upper Mines loop: three trips to the same destination (16:15:39, 16:15:55, 16:16:15), each ending "wrong area", two of them restarted from the forge | 16s + 20s + 20s, about 56s, with no progress, until the macro was toggled off | about 80% |
| Two mining-stand walks stuck in a row (16:15:07 to 16:15:10, then to 16:15:14) | about 7s | about 10% |
| Route leg 4/8 stuck after the first forge etherwarp (16:14:39 to 16:14:43) | about 4s | about 6% |
| Route planning (graph search), 5 plans | 0.8 to 1.4ms each | well under 0.1% |
| AOTV, planned to landed | about 1s each, 8 to 10 per Upper Mines trip | not waste, but the largest part of a working trip |

Graph search is not the bottleneck: it is about a thousand times cheaper than one teleport. What lost time was
choosing the wrong destination, going back to the forge for it, and walks that got stuck.

## Root cause of the Upper Mines loop

Three faults, each making the next one worse:

1. **The zone was judged before the scoreboard caught up.** Each "wrong area" verdict came in the same second
   as the AOTV that landed there. The route passed through Rampart's Quarry, and Hypixel refreshes the sidebar on
   its own schedule, so it still said Rampart's Quarry. The evidence:
   - The bundled zone map, built from a recorded run with readings right after teleports thrown out, puts
     (-108, 166, -69) in Upper Mines, with no Rampart's Quarry cell within 24 blocks.
   - The 13:59 session mined at that exact spot and completed Upper Mines Titanium.
2. **The verdict hit the wrong thing.**
   - The handler deleted the Upper Mines zone cells around the player, which were correct.
   - It recorded a navigation failure, which made the next start "the last trip from here failed, come at it from
     the forge".
   - It never touched the saved mining spot (-110, 167, -70, labelled Upper Mines) that was the actual destination.
     That spot kept winning, so every trip went back to it.
3. **Live zone learning saved the stale reading.** Every teleport taught the previous zone's name for the new
   place, so the saved zone map got worse over time.

## What changed

- **Fresh zone readings.** `ZoneReading` tracks teleports every tick (a move over 3 blocks in one tick).
  - A zone line counts only once it has changed since the teleport, or 1.5s have passed. Arrival, mining, combat,
    spot labelling and zone learning all use only fresh readings.
  - On arrival, the macro waits for the sidebar, at most 1.5s. The wait is timed as `area.confirm`.
- **Destination verdicts.**
  - `AreaMemory` has four confidence levels: VERIFIED_CORRECT_AREA, LIKELY_CORRECT_AREA, UNVERIFIED and
    VERIFIED_WRONG_AREA.
  - A destination confirmed wrong, after the zone line caught up, is saved as wrong for that one area, together
    with where the player stood. That covers everything within 3 blocks, survives restarts and forge warps, and is
    lifted only by standing there and seeing the right zone.
  - Saved spots, zone points, hunt points and stands all respect it.
  - Destinations prefer interior points over border points.
- **No forge reset after a wrong area.**
  - Nothing is recorded as a navigation failure, because the route worked.
  - The next destination is picked from where the player stands, so a nearby one is walked to.
  - The forge fallback now also checks that the forge has a route at all.
- **Expected-time route choice.** Starting here versus from the forge is compared in seconds. Walking is counted at
  walking speed, each teleport at its measured cost (once three have been timed), and the warp at its measured
  cost. The graph's per-teleport price also follows the measured teleport time.
- **AOTV aim tolerance** is 2.5° instead of 1.2°. An AOTV throws a fixed distance along the view, so 2.5° over 12
  blocks moves the landing about half a block. Etherwarp, which lands on the exact block aimed at, keeps 1.2°.
  This is reasoned, not measured; `rot.aim` will show the effect.
- **Stuck-approach memory for mining stands.** Where a walk got stuck is remembered. Stands whose straight approach,
  from about the same start, runs through that spot are skipped until the record expires.
- **Saved zone cells reset once (v3).** Cells learned before this fix were taught stale names. They are dropped in
  favour of the clean bundled seed, and learning carries on with fresh readings only.

## Diagnostics now available

Turn on Diagnostics, then High-Frequency Debug Enabled. Files go to `config/aether/dwarven/diag/`.

- **Snapshots** every 50ms (adjustable from 50 to 1000ms) while the macro runs. Each one records:
  - when it was taken: the real interval since the last one, plus how late it was and how many deadlines were
    missed when it ran late
  - the macro: stage and time in it, commission, objective, zone (and whether it's stale), position, speed and
    expected speed
  - the camera: rotation, rotation target and turn rate
  - the navigator: route, leg, waypoint, distances, time left, no-progress time, plan kind, failures and replans,
    plus teleport state and wait time during a hop
  - the miner: target block, ray check, input state, dig time left, last problem
  - the fighter: weapon, mode, target, mana, ability state, why a scan found nothing, kills, casts and shots
  - performance: tick time, last finished and oldest pending operation, total planning time, pathfinder calls,
    queue size and dropped count

  Snapshots come from the client tick and from each rendered frame, whichever is due first. A late sample is
  recorded as late; missed samples are never made up.
- **Events** are written as they happen, with millisecond wall time, a session-relative time and a duration where
  one applies. They cover state transitions, route requests, each leg, teleport preparation, request and landing,
  rotation, stuck, recovery, area confirmation, mining starts and breaks, and combat modes.
- **Writing.** Records go into a bounded queue. A batch is written about every 250ms on Aether's worker thread,
  never on the game thread. A full queue drops the record and counts it, and the count is written into the file.
  Files rotate by size and count. A ring buffer of recent records feeds Export Diagnostic Session, which also writes
  the report below.
- **Timed operations** (`Ops`) store nanoseconds, with count, mean, 95th percentile, max and total. They are always
  collected; the file is optional.
  - Navigation: route planning, pathfinder start, each leg, the whole trip, idle time between legs, stuck-detection
    delay, the forge warp.
  - Teleports: AOTV and etherwarp preparation and execution, landing confirmation, aim rotation.
  - Arrival: zone confirmation.
  - Mining: stand walks, dig start, block break.
  - Recovery: time spent waiting to retry.
- **Watchdog.** Each monitored operation has an expected time; the multiplier is a setting.
  - Past that, still making progress: it notes "slow but progressing" once, in the diagnostic file.
  - Past that, with no progress for 1.5s: it logs a `[PERF] WARNING` once.
  - It never interrupts anything.
- **Session report.** When the macro stops, the three largest sources of wasted time go to the log. The full table
  goes to the diagnostic file and the export.

## Verified, and not yet

**Verified by tests.** The `UpperMinesTests` regression covers stale versus fresh zone readings, the wrong-area
verdict, it surviving a save and reload, it being overturned by new evidence, interior-first picks, no forge after
a wrong arrival, no repeated destination, and the full sequence (Rampart's Quarry done, Upper Mines chosen, stale
arrival, real wrong area, nearby alternative, correct arrival). `DiagnosticsTests` covers microsecond timing, the
watchdog, 50ms cadence with late samples, millisecond timestamps, queue overload, rotation, concurrent writing and
category filtering. `GoblinCombatTests` covers weapon priority and Wither Impact planning.

**Not verified in game.** The real sidebar refresh delay, which these numbers assume to be at most about 1.5s; real
AOTV aim timing; and real Wither Impact and Terminator behaviour. The diagnostic file is how to check each of them.
