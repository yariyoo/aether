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

## Etherwarp route recovery (Forge to Upper Mines regression)

What the log showed: the first etherwarp out of the forge landed 10.6 blocks from its recording, the hop was banned,
replanning found "no known route", the 113-block direct walk was refused, and every retry repeated the same thing.

What the code was doing:
- The landing check called a miss "no closer" by straight-line distance to the goal. The seeded forge hop lands
  113 blocks from Upper Mines, the same as the forge itself, because the route goes around, so even a perfect
  landing would have read as no progress.
- Planning started only from the single nearest crumb within 12 blocks. Next to a short dead-end trail that one
  crumb has no route anywhere, and the connected trail 10 blocks away was never considered.
- With no route and more than 40 blocks to go, the trip failed outright, and the next attempt started from the
  same spot with the same graph.
- The aim fired at the landing block's centre even when its own ray was blocked by something else, which lands
  on whatever blocked it. It also never checked etherwarp range.

What changed:
- `HopLanding` judges a landing against the route: on target (within 4.5), continue from a route stop within
  12 blocks of where we are (later stops first), replan, or no teleport. The position is judged only after it
  has stopped changing for 2 ticks, and the log says whether it settled.
- `TrailMemory.routeFrom` searches from every crumb in reach (height weighted), so the route rejoins wherever
  it is connected. Planning widens from 12 to 24 to 40 blocks. A far join point is its own first leg, walked
  by the pathfinder: reaching it proves the connection; failing marks it unreachable and planning moves on.
- `RoutePlanner` levels: known route, reattached route, partial route, direct walk under 40 blocks, step to
  known ground at least 10 blocks closer (up to 6 steps), then a safe failure that says "unexplored" or "blocked".
- A miss that still lands next to the route keeps the hop; a second miss, or a miss with nothing to continue
  from, bans just that edge.
- Banned hops, misses, unreachable join points and trip failures live in `NavFailures` and survive
  WAIT/OBSERVE. A trip that failed from the forge isn't started from the forge again; a trip that failed from
  elsewhere tries the forge. Trips give up after 12 replans.
- Before an etherwarp: the landing block must exist with room above it, be within 60 blocks, and be visible
  (a ray blocked by the block right next to it is allowed). When the landing chunk isn't loaded, the recording
  is trusted only from its exact start.

Verified: `NavigationRecoveryTests` (the Forge to Upper Mines scenario plus the 18 listed cases) and the
existing trail tests pass. Not yet verified in game: why that particular throw landed 10.6 blocks off. The
range and visibility checks rule out two causes; if it keeps happening, the `[NAV] etherwarp completed` line
now shows launch, expected and actual positions to compare.

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
