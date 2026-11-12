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

## Added since the first version

- Combat commissions (Goblin Slayer, Glacite Walker Slayer, Treasure Hoarder Puncher, Star Sentry Puncher): mobs found by
  their name tags and fought with the mining tool; spots to look are seeded from a recorded run.
- Goblin Raid and Goblin Raid Slayer are joined only while a raid is running where the macro already is.
- Claiming through the nearest remembered Emissary or the King when there is no pigeon (Claim Method setting).
- Travel follows recorded walks and AOTV/etherwarp hops in short legs; zones, trails, hops and fight spots are seeded
  from a recorded run of the maintainer's, then learned on top.
- Settings: auto start, resume after leaving and coming back, tool name, progress check interval, rest time for a
  failing commission, exploration range, walk length, stop on unknown area, claim method, events, HUD rows.
- HUD: mining rate, titanium rate and blocks since the last titanium, powder earned per hour.
- Tests: `LifecycleTests` (choose, work, claim, move on; events gated; teleport routes and saved trails), plus raid,
  name tag, reward and zone-line parsing.

## Known limits

- Stop after X HOTM XP and HOTM XP per hour: the recorded claim rewards list Mithril Powder and Mining Exp, not HOTM
  XP, so there is nothing reliable to count yet. The HUD shows HOTM XP only if a reward line names it.
- Emissary and King name tags are matched by "Emissary" / "King ..."; not yet seen in a live run.
- Raffle, Lucky Raffle, Elusive Goblin and powder events are recognised and skipped.
- Settings from the brief still left out because nothing real sits behind them: pathfinder mode, sprint and jump
  toggles, cluster mining toggle, block retry limit, target switch threshold, show mining path.

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
