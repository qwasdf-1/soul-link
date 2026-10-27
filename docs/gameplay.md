# Gameplay guide

## Run flow

Soul Link has four states:

1. **Lobby:** players wait on a protected platform in a persistent void dimension. Falling below the platform returns a player to its spawn. The lobby survives every run.
2. **Preparing:** the next run's worlds and spawn are generated in the background. The first run starts automatically as soon as it is ready. Later `/soullink start` requests wait for preparation and show phase-based progress; optional wider-area pregeneration continues in the live world.
3. **Running:** everyone present enters the new Overworld. The timer begins and shared vitality is active. By default, the next world is prepared at the same time.
4. **Ended:** defeat or victory is saved, and players remain in that run's world. `/soullink start` begins the next fresh run; `/soullink reset` abandons a running attempt and starts the next one. Both move players through the lobby during world replacement.

The lobby is a persistent dimension, not a world that is created and deleted between attempts. It is the first-run waiting area and the safe transition space during replacement. Operators can use `/soullink stop` to abandon an active attempt and leave everyone waiting there. There is no teleport-only command that moves players to the lobby while keeping the run active.

Players stay connected across transitions while [Terra Rasa](https://github.com/qwasdf-1/terra-rasa) handles the world replacement.

## Shared vitality

Every active participant has the same health, food, and saturation values.

- Damage from different participants adds to the shared health loss.
- Healing is shared, but simultaneous healing only uses the largest individual health gain for that tick.
- Food and saturation gains and losses from all participants are combined, then clamped to vanilla's allowed ranges.
- Teammates receive a chat message naming players who lost health and showing the health lost and resulting shared pool. It identifies affected players, not the attacker or damage source.
- Optional half-heart mode sets participant max health and the shared team pool to 1 health point. It is configured for the next run.
- A hit to one participant is shown to teammates as a hurt animation and sound.
- If the pool reaches zero, the run ends even if no participant has individually died.

This is not shared inventory or shared equipment. Those modes are not implemented.

## Defeat and victory

Any participant death in a run dimension ends the run in defeat. Remaining participants become spectators; the dead participant is also put into spectator mode on respawn. The run also ends in defeat if shared health is depleted.

Defeating the Ender Dragon in the canonical End ends the run in victory. Players stay in the world, so vanilla's exit portal, credits, and return to the run spawn work normally. The timer stops at the result. Soul Link records total runs, victories, and the best victory time.

## Players and reconnects

- All players in the server join the run when it starts.
- With `allowLateJoin: true`, a new player joins the current run in survival at its spawn, with a clean player baseline. If shared potions are enabled, they receive the active team's effects.
- With `allowLateJoin: false`, new or unrecognized players stay in the lobby until the next run.
- A participant who disconnects and returns during the same run is restored to that run rather than treated as a new late joiner.
- The timer advances only while at least one participant is online. Empty-server time does not count.

## Commands

| Command | Access | Behavior |
|---|---|---|
| `/soullink help` | Everyone | Explain commands, arguments, permissions, and behavior. |
| `/soullink start` | Everyone | Enter a prepared run, or queue a fresh run that starts as soon as preparation completes. |
| `/soullink reset` | Everyone | Mark a running attempt abandoned, then start or prepare the next run. |
| `/soullink info` | Everyone | Show the status, run id, timer, shared health and food, next-world phase, and best time. |
| `/soullink status` | Operators | Show readable lifecycle, run, preparation, and recent failure details. |
| `/soullink stop` | Operators | End the attempt and return players to the lobby without immediately starting another run. |
| `/soullink pause` | Operators | Pause the run timer; the world and gameplay continue normally. |
| `/soullink resume` | Operators | Resume a paused run timer. |
| `/soullink setup` | Operators | Show optional per-dimension pregeneration settings. |
| `/soullink setup pregen <dimension> <radiusBlocks>` | Operators | Change a dimension's pregeneration radius for future preparations; `0` disables it. |
| `/soullink prepare [seed|random] [preloadRadius]` | Operators | Discard queued ready runs and prepare a new one. Omitted seed means random; omitted radius uses the configured default. Use `random 0` to disable spawn preload. Wait for any current preparation to finish first. |
| `/soullink rules sharedPotions <true\|false>` | Operators | Persist the shared-potions setting for the next run. |
| `/soullink rules naturalRegeneration <true\|false>` | Operators | Change vanilla's world-persisted `naturalRegeneration` game rule immediately. |
| `/soullink rules halfHeart <true\|false>` | Operators | Set each participant's maximum health and the shared pool to 1 HP from the next run. |

When enabled by `debugCommands`, operators also get `/soullink debug lifecycle`, `gc`, `probe`, `end`, `spawners`, `player <name>`, and `fail <point>`. These are diagnostic tools; lifecycle JSON is available through `debug lifecycle`. See [Configuration](configuration.md) before enabling failure injection.

## Reset between runs

Starting a run moves everyone to the lobby, replaces the resettable worlds, resets vanilla's run-owned server state and player state, and then sends players to the new spawn. Game rules, difficulty, operators, whitelist, and the lobby are preserved. The [operations reference](reference.md#session-reset) lists the state that resets and what survives.
