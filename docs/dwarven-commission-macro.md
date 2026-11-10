# Dwarven commission macro

What the macro must do, as specified by the maintainer and as observed in a 22.5 minute recording of a
working commission macro (Polinex) running untouched from start to stop. Rules marked **spec** came from
the maintainer directly and win over anything observed.

## Rules

- **spec** Do every commission type: area mithril and titanium, Mithril Miner, Titanium Miner, Goblin Slayer,
  Glacite Walker Slayer, Treasure Hoarder Puncher, Star Sentry Puncher, Goblin Raid Slayer, and any new ones.
- **spec** Event commissions (Goblin Raid Slayer, ...) are never prioritised and never hunted for. Do them only
  when the event is happening where the macro already is: if it runs into a Goblin Raid, it takes part.
  Whether Star Sentry Puncher counts as an event commission is not confirmed yet.
- **spec** Ignore 2x Mithril Powder Collector completely: it only progresses during 2x powder.
- **spec** Ignore Raffle for now; it gets its own work later.
- **spec** Pick the commission that is fastest to reach. Working on two at once only happens by luck when a
  new commission lands near the current one; do not aim for it.
- **spec** Claim with the Royal Pigeon. Find it by name in the hotbar, never assume a slot.
- **spec** Later, not now: without a Royal Pigeon, walk to the nearest Emissary to claim.
- **spec** Mining spots are not fixed: go to the best and nearest spot, which can be one of the spots Polinex
  used (table below) but does not have to be.
- **spec** Fight with the best weapon available in the hotbar, not a fixed item.
- **spec** Travel with the fastest mix of what the player has:
  - no Aspect of the Void: walk
  - Aspect of the Void: walk plus AOTV teleports
  - Aspect of the Void with Etherwarp: walk plus AOTV plus etherwarp
- **spec** `/warp forge` when it is the fastest way to the next commission.
- **spec** Optional setting (default on, matching the maintainer's setup): after a Goblin Slayer or Glacite
  Walker Slayer commission, warp to the forge before the next commission, unless the next one is also a slayer.
- **spec** Area commissions only need the commission to show DONE before claiming; the amount needed scales with
  fortune, so never count blocks toward it.
- **spec** Titanium commissions mine mithril while no titanium is in reach, since mined mithril can turn into
  titanium; titanium in reach is always taken first.

## How it is built

- Travel follows a route graph built from recordings (`tools/build_dwarven_routes.py` writes
  `assets/aether/routes/dwarven_mines.json`): recorded walks, AOTV hops, etherwarps and `/warp forge`. The planner
  only uses the moves the player's gear allows. Each hop is aimed from where the player stands and checked on
  landing. A step that fails is banned for five minutes and the route is replanned. If no recorded route fits,
  travel falls back to the pathfinder.
- Commissions are ranked by planned route time, not straight-line distance. A chosen commission is kept until it
  is DONE, it vanishes, or travel to it fails.
- Spot memory: the miner remembers the standing spots that produced ore in each area. Seeds come from
  recordings (`tools/build_dwarven_spots.py`), and what each run learns is saved in
  `config/aether/mining_spots.json`. When nothing is visible in the area it walks to the best remembered spot,
  instead of waiting. A spot found empty is rechecked after 30s, and a spot that is unreachable 3 times is
  ignored for 10 minutes. Whether a spot was empty is never saved.
- The miner sneaks while mining. It throws Pickobulus at the densest patch of 8 or more wanted ore when the tab
  list or chat says it is ready. The block timeout drops to 4x the measured median break time, never above the
  setting.
- Safe stops: death, being outside the Dwarven Mines for 15s, 3 claim runs in a row that leave something
  unclaimed, or 4 commission areas in a row that can't be reached. A missing mining tool or pigeon is reported.
- The Commission HUD shows status, commission, progress, area, session time, completions per hour and blocks
  mined.
- Not done yet: combat commissions, the forge-after-slayer setting (it needs combat first), the Emissary
  fallback and Raffle.

## Observed behaviour (34 commissions in 22.5 minutes, about one per 40s)

### Claiming
- Within about 1.5s of the chat line `<NAME> Commission Complete! Visit the King to claim your rewards!`.
- Switch to the Royal Pigeon, right-click it, switch back to the drill straight away (before the menu opens).
- In the `Commissions` menu, right-click (button 1) the finished commission in slots 11-15; it is replaced
  by a new one whose name ends in ` NEW`.
- A finished commission's lore ends with `COMPLETED` and `Click to claim rewards!`; unfinished ones end with
  `Progress` and a percentage.

### Progress
- Tab list `Commissions:` section, one line per commission: `Name: 12.4%` or `Name: DONE`, refreshed about
  every 3s.

### Mining
- Each area is mined from one fixed standing spot, kept within about 2 blocks every time:

  | Area | Spot |
  |---|---|
  | Upper Mines (also used for Titanium Miner) | -110, 167, -74 |
  | Royal Mines | 177, 152, 24 |
  | Lava Springs | 42, 198, -13 |
  | Cliffside Veins | 8, 129, 38 |
  | Rampart's Quarry | -85, 154, -16 |

- Blocks break in about 350ms (p10 100ms, p90 500ms); next target follows about 450ms later.
- Next target is adjacent to the last one 58% of the time (sweeping).
- Turn speed while mining: median 38 deg/s, p90 240, p99 490.
- Sneaks while mining; reach up to about 5.4 blocks eye to block centre.
- Uses Pickobulus whenever it is off cooldown (45 times), right-clicking the drill aimed down at ore.

### Combat
- Goblin Slayer around -134, 144, 142 (x -155..-55, z 96..172).
- Glacite Walker Slayer around 28, 129, 158 (x -33..76, z 138..186).
- Goblin Raid in the Forge Basin around -4, 145, -13.
- Attacks with left click while holding the Hyperion.

### Travel
- 319 teleports of about 8.5 blocks; legs of 150-270 blocks take 5-10s.
- Swaps to the Aspect of the Void for each hop and back to the drill in the same tick.
- Sprints forward through most of each leg; some hops are made sneaking (etherwarp).
- `/warp forge` after far combat commissions (see the forge rule above).

### Hotbar during the recording
- Aspect of the Void, drill, Hyperion, Royal Pigeon. Positions must be detected by name, not assumed.
