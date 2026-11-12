# Dwarven commission macro: design

Package `dev.aether.modules.dwarven`. Everything here is new code written for Aether. Planning logic is pure
(no Minecraft singletons) and unit tested; only thin classes touch the game.

## Integration map

| Need | Existing Aether piece used | New piece |
| --- | --- | --- |
| Macro on/off state, stop-on-screen, failsafes | `MacroStateManager`, `MacroState` (new `DWARVEN_COMMISSIONS` state, `DWARVEN_MINES` location) | `DwarvenCommissionMacro` |
| Ticking and chat | `AetherAutomationTickHandler`, `AetherChatEvents` | one call each |
| Walking and etherwarp | `PathfindingManager.startConfiguredWalk` / `startConfiguredEtherwarp` | `AreaNavigator` only coordinates legs; no second movement controller |
| Camera | `RotationManager` stays the single owner | `steer(...)` mode driven by `CameraMotion` |
| Hotbar swaps | `FailsafeManager.selectHotbarSlot` (keeps the slot failsafe informed) | - |
| Item use, keys, commands | `ClientUtils` | - |
| Settings page | `AbstractMiningRegistryProvider` ("Other" section), ServiceLoader file | `DwarvenCommissionsRegistryProvider` |
| Keybind | `AetherKeybindRegistry` | `key.aether.dwarven_commissions` |
| HUD | `HudRegistry`, `HudStyle` | `DwarvenHudElement` |
| Config | `AetherConfig` / `Config` factories | `DWARVEN_*` entries |
| Logging | `ClientUtils.sendDebugMessage` | `DwarvenLog` (`[Dwarven]` prefix) |

## Subsystems

- **Commission intelligence** (`commission/`): `CommissionReader` parses the tab list and menu text;
  `Objective` turns a name into kind, ore and area; `WorkStats` learns how long each commission takes.
- **Scheduler** (`CommissionScheduler`): cost = travel time + remaining work, divided by an overlap credit.
  Strategies: fastest, closest, titanium first, mithril first. The current commission is kept unless another
  is faster by the configured margin.
- **Area navigator** (`nav/AreaNavigator`): legs of about 24 blocks along `TrailMemory` when known ground
  connects, otherwise one pathfinder request. Alternates walk and etherwarp on retries, falls back to
  `/warp forge`, and learns where that warp lands.
- **Vein discovery** (`world/`): `OreScanner` caches ore per chunk section, a few columns per tick, out to the
  extended radius; the miner reads the 5-block cube around the eye directly. `VeinFinder` groups ore;
  `StandPlanner` ranks standable positions by ore visible within reach.
- **Memory** (`memory/`): `SpotMemory` (per standing spot: zone, blocks, titanium, mining time, failures),
  `AreaMemory` (which cells belong to which scoreboard zone), `TrailMemory` (walked breadcrumbs). All three
  learn whenever the player is in the mines, macro on or off, and persist under `config/aether/dwarven/`.
  Block states are never persisted.
- **Block miner** (`mine/DwarvenMiner`): pick, aim, dig, confirm by block change, move along the vein, wait
  for regeneration only when no other stand reaches ore.
- **Titanium strategy**: titanium jobs mine mithril until titanium appears, take titanium first, and follow a
  block that turns into titanium in place.
- **Claiming** (`claim/ClaimManager`): Royal Pigeon, menu validated by title, slots chosen by their text.
- **Recovery** (`RetryBudget`): bounded tries with doubling waits per failure kind.
- **Camera** (`camera/CameraMotion`): angular velocity with bounded acceleration and deceleration, a speed
  envelope from the chosen easing, a braking limit that prevents overshoot, an optional sideways bow that
  fades out before the target, and speed carried from one target to the next. Frame-rate independent.

## State machine

`OFF -> VALIDATE -> OBSERVE -> (CLAIM | TRAVEL | MINE) -> OBSERVE ...`, with `WAIT` for backoff.

| Stage | Enters when | Does | Leaves when | Timeout / recovery |
| --- | --- | --- | --- | --- |
| VALIDATE | start, world change | checks location and tool | both fine | stops if no tool |
| OBSERVE | after every job | reads commissions, claims or schedules | a job is chosen | 10s unreadable -> retry budget -> stop |
| CLAIM | a commission shows DONE | runs `ClaimManager` | claimed or nothing to claim | 3 opens, then retry budget -> stop |
| TRAVEL | objective is elsewhere | runs `AreaNavigator` | in the zone or at the spot | stuck timer, retries, forge warp, then rest the commission |
| MINE | in the right place | runs `DwarvenMiner`, watches progress | commission done or gone | no ore / stalled -> another spot -> rest the commission |
| WAIT | backoff | nothing | timer ends | - |

## Not in the first version

Combat and event commissions (recognised and skipped), Emissary claiming, drill refuelling, lobby hopping,
HOTM XP tracking, and the settings from the brief that had nothing real to control yet.
