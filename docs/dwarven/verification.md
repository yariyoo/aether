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

## Efficiency pass (13:57 log): navigation dead ends, unreachable stands, Goblin Slayer, dig restarts

What the log showed, and the cause found in the code:
- **Lift and Village starts never moved.** Known ground there is a dead end, but the forge decision compared
  straight-line walking time and stayed put. It now asks whether a plan from here actually reaches the goal;
  if not, it warps to the forge. Any plan that ends with nowhere left to go also falls back to the forge, unless
  that trip already failed from the forge.
- **Goblin Slayer hunted in Forge Basin.** The bundled hunt points mixed in mobs from a Goblin Raid in Forge
  Basin during the recording, so (21, 144, -3) was "the busiest Goblin Burrows spawn". Being near a hunt point
  also counted as being in the area. Now: those 7 points are removed from the seed, and every hunt point, saved
  spawn and target is filtered by the zone it was seen in. Being in the area for combat needs the scoreboard to
  say so; arriving elsewhere forgets that spot and picks another, and only sightings made inside the area are
  learned.
- **"Mining never started (attack held...)".** Not mouse capture. `MacroInput.setAttack` only holds the key, which
  never counts as a click. On the same block again (after it turned into titanium, or on the restart), the game
  carries on its old client-side dig and never sends the start-breaking packet. The re-press now sends a real
  click. The first dig on a new block was never affected.
- **Walking 10s to stands it couldn't reach.** Before moving, a flood fill over loaded blocks (one-block climbs,
  drops up to three, slabs and stairs count as floor) rejects stands with no walk to them, and stands known to
  be in another zone. If nothing passes, it tries the best stand anyway as before. Once a walk is moving, 2.5s
  without getting closer ends it, instead of the full stuck timeout.

Also added:
- Teleports that missed recently cost more in route search (Route Reliability Weight), so a slightly longer
  dependable route wins a close call. Planning time is measured and logged per plan.
- Goblin Slayer weapon priority, read from item lore: Hyperion with Wither Impact (and enough mana by the action
  bar), then Terminator, then the hardest-hitting sword or shortbow, then the mining tool. Hyperion casts looking
  straight down only with an eligible goblin within 6 blocks, and prefers targets in groups. Shortbows shoot
  from range when there's line of sight. A weapon whose hits take no health off 4 times in a row is dropped for
  the next one. No usable weapon rests that commission instead of stopping the macro. Other combat commissions
  still use the mining tool.
- Kill credit: mob types whose confirmed kills never move the commission are dropped from the hunt after 5.
  Golden and Diamond Goblins are included and checked the same way.
- HUD stats: time per commission and travel per commission, navigation success and planning time, time spent
  waiting out failures, kills per minute, and the weapon with the reason it was picked.

Verified here: `EfficiencyTests` (weapon priority, mana parsing, kill credit, walk reach, Lift-to-forge
fallback, reliability penalty, area-filtered spawns, the seed's goblin spawns all in the burrows) and
`NavigationRecoveryTests`. Not yet verified in game: Wither Impact and Terminator behaviour, the re-press click
fix, and the walk check on real terrain.

Settings from the brief that were not added because nothing would sit behind them: Fast Pathfinding, Prefer
Cached Routes and Maximum Route Planning Budget (graph planning measures in milliseconds), Combat Strategy and a
separate Weapon Priority order (the per-weapon toggles cover it), Cluster Mining Optimization (stands are already
chosen for the most ore in reach). Already present under other names: Prefer Teleport Shortcuts (Use Etherwarp),
Route Learning (Learn And Reuse Walked Routes), Fast Target Switching (Combat Mode), Spawn Location Learning,
Persistent Respawn Search.

## Goblin Slayer combat pass

Why goblins in view went unattacked, as far as the code shows:
- Target selection rejected any goblin whose position the zone map attributed to another area. The zone map was
  being taught stale names after every teleport (see performance-report.md), so real burrows goblins could be
  filtered out, and the fighter kept searching and repositioning.
- Nothing recorded why a scan found nothing, so the log couldn't show it.

Now:
- **Targets.** Visible goblins are fought wherever the zone map puts them; the area filter applies only to
  destinations and spawn learning. Kill credit still drops a mob type whose kills never move the commission.
- **Scan reports.** Each scan reports what it saw: no mobs, mobs but none of the wanted kinds, or eligible goblins
  that are all unusable (no body under the tag, too far, ignored for now). Repeated filtering reaches the log as
  `[GOBLIN] ...`.
- **Weapon priority** follows the item's SkyBlock id first and the display name only as a fallback:
  - any Hyperion first, plus a scrolled Valkyrie, Scylla or Astraea
  - then Terminator
  - then the hardest-hitting sword or shortbow
  - then the mining tool
  - low mana no longer hands over to the Terminator
- **Hyperion.**
  - Wither Impact is planned from the blocks: the teleport goes up to 10 blocks along the view, stops before a
    block and lands on the floor.
  - It counts the goblins within 6 blocks of the landing, and an in-place cast looking down is one of the options.
    Clusters are preferred when that setting is on. No cast is planned onto a landing with nothing under it.
  - A cast is checked by teleport, mana spent, or Hypixel's own "not enough mana" or cooldown message.
  - Not enough mana switches to left-click melee with the same Hyperion, held until mana reaches the recovery
    threshold, with a 1.5s minimum so the modes don't flicker.
  - Repeated casts with no response suspend the ability for the failure cooldown.
- **Terminator.**
  - It shoots from range with line of sight, leads moving goblins, and aims above the target to allow for arrow drop.
  - Every shot is checked for a new arrow appearing. Automatic input starts with right click, the shortbow's
    documented "instantly shoots", and switches to left click after 3 shots with no arrow. If neither works, it
    falls back to the next weapon.
- **Melee and drill.** Melee range is a setting. Attack problems are reported: crosshair off the goblin, on another
  entity, or input suppressed by another module.
- **HUD.** Combat debug rows show the weapon, mode, target, distance, ability, mana fallback, input, failure reason
  and confirmed kills, each with its own toggle.

**Verified here.** `GoblinCombatTests`: the six priority combinations, item ids over names, in-place and cluster
casts, out of range, a wall stopping the teleport, a ledge with no floor, and the mana and cooldown messages.

**Not verified in game.** The Wither Impact blast radius (6 blocks, deliberately on the low side), real teleport
landings, which click the Terminator fires on in practice (the arrow check decides at runtime), and Hypixel's
exact refusal wording beyond the phrases matched.

## Optimization and rotation pass

No log came with this request, so the baseline is the figures quoted in it: 114.8s standing still between
legs, 25.2s waiting to retry and 10.9s of failed stand walks. The navigation report now prints the same
measurements (mean, median, p95, max and total), so a session on this build can be compared against those numbers.
None of the changes below has been measured in the game yet.

Root causes found:
- idle between legs: every leg stopped the pathfinder, released keys and started a new search, even when the next
  leg was a straight continuation. the etherwarp pathfinder was never chosen because legs are capped at 24 blocks
  and the old rule needed 30
- "prefer titanium" applied to every commission, so Mithril jobs walked to titanium that counted for nothing
- "blue / quartz is efficient" isn't true on Hypixel: blue (light blue wool) is the strongest Mithril, and there
  is no quartz Mithril. the choice now uses measured break times per kind, and the priority mode picks the trade-off
- repeated stuck spots: nothing remembered where walks stalled, so the same corner was planned through again

What changed:
- camera: the acceleration ramps at a jerk limit, while braking stays instant. the first version also limited
  braking, and the test caught it: the camera swung back and forth past the target and never settled. there is
  bounded per-move variation (speed, acceleration, bend) as a slow random walk between moves, and a per-block aim
  point on a visible face, checked with a raycast. each activity has its own speed scale, and etherwarp aims in
  precision mode with no bend or variation
- mining: commission-aware block cost = (break + aim + move time) / contribution. titanium counts only for titanium
  jobs, or when another open titanium commission counts it. Mithril counts at the learned titanium spawn rate in
  titanium jobs. cluster mining values a stand by the ore it reaches per second of digging
- navigation: legs walking in a straight line are merged (route simplification). a failed merged leg turns merging
  off and replans. the pathfinder isn't stopped between two walking legs. a leg uses the etherwarp pathfinder when
  its measured time beats walking. teleports seen during etherwarp legs are learned as hops. per-hop success,
  failure, deviation and time are saved, and unreliable hops cost more. stuck points are penalised in the trail
  search and fade over hours. smart forge compares expected times
- profiling: block choice, stand choice, rotation onto a block, time between breaks, break time per kind, walk and
  etherwarp leg time, teleport/stuck/failed counts and progress per minute

Settings that the request named and that already existed were not duplicated. "Profile", "Speed", "Accel", "Decel",
"Curved" and "Completion Tolerance" map to the existing camera settings, and "Manual Mouse Override" to "Pause When
I Move The Camera". The camera page has a note listing these.

Automated tests: OptimizationTests (17) plus every earlier class, 88 tests in all, pass against stubs of the
Minecraft types. The CI build compiles the whole mod. Not verified in game: how the camera feels, whether the merged legs
cut the idle time, whether learned teleports help, and the real Mithril break times. Goblin Slayer combat logic
was not changed; it only moved to the combat camera profile, and it remains unverified in game.

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
