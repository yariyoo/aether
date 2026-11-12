# Dwarven commission macro: verification and originality

## What was run

- `./gradlew build` on GitHub Actions (JDK 25) for `feat/dwarven-commissions`: compiles, all unit tests pass.
- New tests: `CommissionTests` (11), `CameraMotionTest` (8), `WorldAndMemoryTests` (10).

## Verified by tests

Commission parsing from the tab list, menu text and chat; objective and area from a name, including an area
never seen before; scheduling (fastest, overlap credit, hysteresis, disabled kinds); learned completion times;
bounded retries with backoff; camera motion (exact arrival, shortest way across the yaw seam, no overshoot,
bounded speed change, frame-rate independence, speed kept on retarget, pitch limits); vein grouping; stand
planning and line of sight; spot memory ranking, recheck, blocking and persistence; zone learning; trail
learning, routing, teleport breaks and one-way drops.

## Not verified yet (needs a run in the game)

Everything that touches the live client: ore recognition against real blocks, digging and break
confirmation, the pickaxe ability, the pathfinder legs, etherwarp legs, `/warp forge` and its learned landing,
the pigeon menu clicks, the scoreboard zone line, the settings page layout, the keybind and the HUD. How
Aether's existing farming failsafes behave while this macro runs is also untested.

## Known limits of the first version

- Area commissions are only taken for zones the player has stood in with Aether loaded; nothing is pre-seeded.
- Long walks over ground never walked before go to the pathfinder in one request, which has stalled on long
  routes before. Walking a route once by hand teaches it.
- Royal Pigeon only. No Emissary claiming, combat, events, drill refuelling or HOTM XP tracking.
- Settings from the brief left out because nothing real sits behind them yet: auto start and auto resume,
  stop after HOTM XP, claim method, mining tool slot (found by name), pathfinder mode, max path length,
  target-switch threshold for blocks, show mining path, HOTM XP per hour.

## Originality review

- No file, method, class layout, constant table or data file from MightyMinerV2 is in this branch. Its
  waypoint graph, per-area destination points, emissary positions, block priority numbers and state list
  were not carried over.
- What was learned from it is gameplay: commissions show in the tab list, a DONE line means claim, the pigeon
  opens a menu named Commissions whose finished items say completed, the pigeon has a cooldown, the forge
  warp is a travel shortcut, and a miner that never changes position runs dry.
- Where Aether differs by design: areas, spots and routes are learned at runtime instead of shipped; names
  are parsed by shape instead of looked up; break times are measured instead of derived from a stat;
  breaks are confirmed by block change; recovery is budgeted per failure kind; the camera is a
  velocity-limited integrator inside Aether's own rotation manager.
- This is a description of how the code was written, not a legal opinion on licensing.
