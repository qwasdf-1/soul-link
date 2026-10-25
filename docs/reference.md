# Soul Link Reference

What a reset covers, what Soul Link costs a server, how a run survives restarts, and what the compatibility suite checks. The numbers come from the integration suites on a development machine; the tests that assert them are named in each section.

## What a reset covers

Reset on live objects at every activation: scoreboard objectives, display slots and teams; command storage; custom boss bars; scheduled functions; random sequences; weather; world clocks; the wandering-trader timer. Then `#minecraft:load` runs again, so data packs set themselves up as in a new world.

Per player: inventory, ender chest, XP, health, food, saturation, absorption, fire, freeze, air, effects, attribute modifiers, entity tags, respawn point, last death location, game mode, recipes, advancements, statistics, scores, ender pearls and vehicles. A player who was offline during a reset is reset when they rejoin.

Deliberately kept: game rules, difficulty, ops and whitelist, and Soul Link's lobby and state. Not reset yet: map item data and stopwatches.

## Measured costs

### Idle

With a run in progress and nothing being prepared or swapped, Soul Link's own work per server tick (`/soullink status` → `tickCost.idle`, over 1,200 ticks) is about 20 µs on average, 8–10 µs at the median and about 120 µs at p99, against a 50 ms tick budget. `test_10_performance` fails above a 50 µs mean or a 500 µs p99.

| Work | Average cost per tick |
|---|---|
| Run timer on the action bar, every 10 ticks (formatting plus one packet per player) | about 7 µs (about 70 µs on the ticks that send it; this is the p99) |
| Shared health and food: finding the active participants and comparing them with the pool | about 8 µs |
| Run clock and online-participant lookup | about 5 µs |
| Lobby fall rescue, every 5 ticks | about 1 µs |
| Run-state save, every 100 ticks: a field copy on the server thread; serializing and writing happen on a writer thread, and only when something changed | under 1 µs |

Staged levels hold chunk tickets that load chunks without ticking them, so the next run's worlds never tick blocks or entities. Everything else is event-driven: damage, food, effects, deaths and joins.

### Preparing the next run

Preparation does a bounded slice of work per tick:

- 8 spawn-search checks per tick. Vanilla's climate spawn search runs on a background thread.
- One level constructed or closed per tick.
- Saving uses vanilla's asynchronous chunk, entity and POI IO, and the run manifest is written off the server thread.
- Chunk generation is paced: at most 6 chunks generate at once, and none start while the server's smoothed tick time is over 30 ms. One chunk always keeps generating, so a busy server still finishes.

Soul Link's worst server-thread step in any phase is about 15 ms, and preparation at the default preload radius takes 10–12 s, mostly vanilla's spawn search. Worst full server tick per phase, over four preparations:

| Phase | Worst server tick |
|---|---|
| Finding the spawn | 7–25 ms |
| Preloading the spawn area | 3–29 ms |

While the prepared worlds are flushed, ticks measure up to about 50 ms. That is not lag: vanilla unloads released chunks in a tick's spare time, up to the deadline of the next tick, exactly as when players leave terrain.

### Pregeneration

Soul Link's defaults pregenerate 500 blocks around the run spawn in the Overworld, 96 in the Nether and 160 around the End's main island: 3,639 chunks. `test_14_pregeneration` measures it with two clients online, a run in progress and the reference modpack's worldgen mods:

| | Measured |
|---|---|
| Full default area | 171–192 s over four runs (19–21 chunks/s) |
| Worst server tick while pregenerating | under 60 ms (asserted) |
| Worst 5 s window of server ticks, pregeneration running both live and for the next run (P50, P95, P99) | 4.1, 14.1, 44.7 ms |
| `/start` or `/reset` issued mid-pregeneration: players waited | 3.5–4.4 s |

A start stops pregeneration; the rest of the area continues in the live world and resumes after a restart. With nobody online the generation budget rises to 16 chunks; Terra Rasa's own suite measures about 45 chunks/s on vanilla worlds.

Give the server at least 4 GB of heap with pregeneration on. The figures above are on 4 GB with ZGC. On 2 GB, ZGC stalled allocating threads (the server thread included, for up to 35 ms) while two worlds generated at once, and a few ticks per run reached about 70 ms.

### Swapping worlds

`/start` and `/reset` run one server-thread section while every player is in the lobby. On vanilla worlds it takes 0.8–1.8 s: about 50 ms retires, promotes and reconstructs the levels, and the rest loads the new spawn chunk to validate it. With the reference modpack it takes 3–3.4 s, mostly C2ME compiling density functions for each new level (about 2.3 s for the Overworld). The levels for the following run are constructed in the same pause, so they cost no separate hitch later.

### Longevity

No retired level stays reachable after garbage collection across repeated resets, with or without the modpack. Over 100 reset cycles with two connected clients (`-m slow`), the mean swap took 1,232 ms over the first 10 cycles and 1,308 ms over the last 10, each client logged in once for the whole run, and the heap after GC went from 334 to 406 MiB.

## Restarts and crashes

The run worlds are ordinary vanilla dimensions, so vanilla saves the terrain and every player's own state: position, dimension, inventory, effects and health. Soul Link saves the rest to `<world>/soullink/state.json`: the run and its seed, the timer, the shared health and food pool, the rules of this run and the next, the participants, and the result of an ended run. Terra Rasa keeps the prepared next run under `<world>/terrarasa/`. `test_11_persistence` covers each case:

- **Graceful stop and restart.** The run resumes exactly where it stopped: same run, seed, rules, shared pool, positions, dimensions, inventories and effects. Shared damage still links the players. The prepared next run is adopted from disk, not generated again.
- **Hard kill.** The run resumes from its last state save, losing at most 5 s of timer. Participants come back into the run in survival, not into the lobby. A crash in the middle of a swap is reconciled by Terra Rasa's journal at the next startup.
- **Restart during preparation.** The half-prepared run is deleted at startup and preparation starts over.
- **Ended run.** It stays ended across a restart, with its result. The next `/start` works normally.

The run timer only counts while a participant is online, so an empty server does not use up a run's time.

## Compatibility

`modpackTest` runs the integration harness on a server with the [reference modpack](../modpack/reference-pack.lock.json), 19 server-side mods pinned by version and SHA-512:

- **Modded worldgen** (Terralith, Tectonic, Incendium, Nullscape, Structory, and the worldgen rewrites C2ME, Fast Noise and Fast Surface): modded biomes appear in all three run dimensions; the prepared spawn equals the pack's own spawn for the seed; noise heights, biomes and structure starts equal a control server started with the same seed.
- **Extra dimensions:** Deeper Dark, listed in `resettableDimensions`, is prepared with the run seed and replaced with the vanilla three, and its data-pack portal works after a reset. A Multiworld runtime dimension and the lobby are left untouched.
- **Mods that cache levels:** Chunky kept the retired Overworld and silently "completed" tasks against it. Terra Rasa's adapter now cancels its tasks before a swap and rebinds its selection. No retired level stays reachable after garbage collection with the full pack.

NoisiumForked has no stable 26.2 build yet, and Fast Noise declares itself incompatible with Moonrise, so neither is covered.
