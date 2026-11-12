# Reference findings: how a Dwarven commission macro behaves

Source studied: MightyMinerV2 2.7.3, Dwarven commission flow only. It was read once to understand the gameplay
problem, the notes below were written, and it was then closed. No code, class layout, algorithm or data table
was carried into Aether. Each item lists what was observed, the requirement behind it, its limits, and what
Aether does instead.

## Commission detection

| Observed behaviour | Requirement | Limitation | Aether's approach |
| --- | --- | --- | --- |
| Reads the "Commissions:" block of the tab list on every tab update. | Know the active commissions without opening a menu. | Needs the tab widget enabled; nothing tells the player if it isn't. | `CommissionReader.fromTab`, polled once a second; after 10s of nothing, a bounded retry then a stop that says which widget to enable. |
| Looks names up in a fixed list of known commissions. | Turn a name into "what and where". | A new area or renamed commission is silently ignored. | `Objective.of` derives ore and area from the name's shape, so an unseen area still parses. |
| Any line showing DONE replaces everything with "go and claim". | Claim promptly. | Reads only the name, never the percentage. | Progress is parsed and used for scheduling and stall detection. |
| Two commissions are worked together only if they share a location or one is an "anywhere" miner. | Use overlap. | Hard rule; ore type isn't considered. | `Objective.alsoAdvances` checks place and ore; overlap is a weighted credit in the scheduler. |
| After claiming, the next commission is read from the still-open menu. | Refresh quickly. | Menu text and tab can disagree for a few seconds. | The tab list stays the single source; DONE lines are ignored for 6s after a claim. |

## Navigation

| Observed behaviour | Requirement | Limitation | Aether's approach |
| --- | --- | --- | --- |
| Each area has one or two hand-placed destination points; one is picked at random. | Know where an area is. | Fixed points go stale and ignore where ore actually is. | No shipped coordinates. `AreaMemory` learns zones from the scoreboard as the player moves; `SpotMemory` learns where mining paid off. |
| A hand-built graph of about a hundred walk nodes is searched, then walked node to node. | Cross the map reliably. | The graph is static data that must be maintained by hand. | `TrailMemory` records ground the player really walked and routes along it in short pathfinder legs. |
| Warps to the forge when the forge is at least ten blocks closer to the goal in a straight line. | Use the warp as a shortcut. | Straight-line distance, not travel time. | `AreaNavigator.travelSeconds` compares walk time along known trails against warp plus walk. |
| Path failure: retry up to three times, then hop lobby, then disable. | Bounded recovery. | Lobby hopping is drastic and hides the cause. | Retries alternate walk and etherwarp, then one forge warp, then the commission is rested for five minutes. No lobby hopping. |

## Mining

| Observed behaviour | Requirement | Limitation | Aether's approach |
| --- | --- | --- | --- |
| Ore is recognised by block type with a priority number per colour. | Classify ore. | - | `OreKind` plus `LiveOreWorld` mapping; titanium priority is a setting. |
| Mines whatever is in reach from where it arrived; walks a step if a block is slightly too far. | Mine without moving much. | Its own source notes "pathfind to a new vein" as not done: when blocks run out it waits two seconds, errors and restarts. | `StandPlanner` ranks standing positions by ore in reach; `DwarvenMiner` moves along the vein, then to other veins, then to remembered spots. |
| Break time is estimated from the player's mining speed stat read out of a menu. | Know when a block should have broken. | Needs a menu round trip and assumes the formula. | Break times are measured; the timeout adapts to four times the median. A break is only counted when the block actually changes. |
| Pickaxe ability is fired when chat says it is available. | Use the ability. | Disables itself if chat messages are off. | Same chat signals; if no confirmation arrives it backs off for 20s instead of stopping. |
| Titanium commissions just raise titanium's priority. | Finish titanium work. | No notion of mithril turning into titanium in place. | A block that changes into wanted ore is counted and re-dug with a fresh dig. |

## Claiming

| Observed behaviour | Requirement | Limitation | Aether's approach |
| --- | --- | --- | --- |
| Pigeon: hold it, right-click, wait for a menu named Commissions, click items whose text says completed, close. | Claim through the menu. | A click is assumed to work. | `ClaimManager` re-reads each clicked slot and only counts it once it stops reading as claimable; one retry per slot. |
| Emissary: walk to the nearest of five fixed NPC positions and interact. | Claim without a pigeon. | Fixed positions. | Not in the first version; without a pigeon the macro stops and says so. |
| Pigeon cooldown message: wait five seconds, up to three tries. | Survive the cooldown. | - | Same idea, written fresh: a cooldown line holds the next open for 5s, three opens at most. |

## Reliability

| Observed behaviour | Requirement | Limitation | Aether's approach |
| --- | --- | --- | --- |
| Stuck or failed: restart from the first state, or disable. | Recover. | Restart loops are unbounded in places. | `RetryBudget`: every failure kind has a fixed number of tries with a doubling wait; exhausting it rests the commission or stops safely. |
| Required items are checked once at start. | Don't run without tools. | A tool lost mid-run isn't noticed. | Tool is re-checked whenever mining picks a block. |
| Drill fuel below 100 triggers a refuel routine or a stop. | Don't run dry. | - | Not in the first version (listed as open). |
| No handling of the commission not progressing. | Notice wrong ground. | Can mine forever in the wrong place. | 45s and 15+ blocks with no progress counts as a stall and moves to another spot. |
| Death, world change and leaving the island go through generic failsafes. | Stop safely. | - | Explicit checks: death, 15s outside the mines, world change re-validates. |
