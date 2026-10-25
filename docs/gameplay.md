# Gameplay guide

## Run flow

Soul Link has four states:

1. **Lobby:** players wait on a protected platform in a persistent void dimension. Falling below the platform returns a player to its spawn. The lobby survives every run.
2. **Preparing:** the next run's worlds and spawn are generated in the background. Players see preparation progress while a requested start waits for the spawn area to be ready.
3. **Running:** everyone present enters the new Overworld. The timer begins and shared vitality is active. By default, the next world is prepared at the same time.
4. **Ended:** defeat or victory is saved. `/start` begins the next fresh run; `/reset` abandons a running attempt and starts the next one.

Players stay connected across transitions while [Terra Rasa](https://github.com/qwasdf-1/terra-rasa) handles the world replacement.

## Shared vitality

Every active participant has the same health, food, and saturation values.

- Damage from different participants adds to the shared health loss.
- Healing is shared, but simultaneous healing only uses the largest individual health gain for that tick.
- Food and saturation gains and losses from all participants are combined, then clamped to vanilla's allowed ranges.
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
| `/start` | Everyone | Enter a prepared run, or queue a fresh run that starts as soon as preparation completes. |
| `/reset` | Everyone | Mark a running attempt abandoned, then start or prepare the next run. |
| `/runinfo` | Everyone | Show the status, run id, timer, shared health and food, next-world readiness, and best time. |
| `/stoprun` | Operators | End the attempt and return players to the lobby without immediately starting another run. |
| `/soullink status` | Operators | Print machine-readable lifecycle, run, preparation, activation, and pregeneration state. |
| `/soullink prepare [seed] [preloadRadius]` | Operators | Discard any queued run and prepare a new one. The preload radius is in chunks and is supplied after an explicit seed. |
| `/soullink rules sharedPotions <true\|false>` | Operators | Persist the shared-potions setting for the next run. |

When enabled by `debugCommands`, operators also get `/soullink debug lifecycle`, `gc`, `probe`, `end`, `spawners`, `player <name>`, and `fail <point>`. These are diagnostic tools; see [Configuration](configuration.md) before enabling failure injection.

## Reset between runs

Starting a run moves everyone to the lobby, replaces the resettable worlds, resets vanilla's run-owned server state and player state, and then sends players to the new spawn. Game rules, difficulty, operators, whitelist, and the lobby are preserved. The [operations reference](reference.md#session-reset) lists the state that resets and what survives.
