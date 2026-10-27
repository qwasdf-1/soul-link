# Operations and compatibility reference

This reference covers session reset behavior, server costs, restart recovery, and the tested compatibility stack. Performance numbers are measurements from the integration suites on a development machine, not universal hardware guarantees.

## Session reset

Reset on live objects at every activation: scoreboard objectives, display slots and teams; command storage; custom boss bars; scheduled functions; random sequences; weather; world clocks; the wandering-trader timer. Then `#minecraft:load` runs again, so data packs set themselves up as in a new world.

Per player: inventory, ender chest, XP, health, food, saturation, absorption, fire, freeze, air, effects, attribute modifiers, entity tags, respawn point, last death location, game mode, recipes, advancements, statistics, scores, and ender pearls. Players are dismounted and passengers ejected. A player who was offline during a reset is reset when they rejoin.

Deliberately kept: game rules, difficulty, ops and whitelist, and Soul Link's lobby and state. Map item data is not reset; host or mod state outside vanilla's saved world and player data must be handled separately.

## Measured costs

### Idle

With a run in progress and nothing being prepared or swapped, Soul Link's own work per server tick (`/soullink debug lifecycle` → `tickCost.idle`, over 1,200 ticks) is about 20 µs on average, 8–10 µs at the median and about 120 µs at p99, against a 50 ms tick budget. The integration suite treats a 50 µs mean or 500 µs p99 as the upper bound.

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

In the earlier four-preparation benchmark, the worst server-thread step was about 15 ms and preparation at the default preload radius took 10–12 s, mostly vanilla's spawn search. Worst full server tick per phase in that benchmark:

| Phase | Worst server tick |
|---|---|
| Finding the spawn | 7–25 ms |
| Preloading the spawn area | 3–29 ms |

While the prepared worlds are flushed, ticks measure up to about 50 ms. Vanilla unloads released chunks in the tick's spare time, as it does when players leave terrain.

A focused 26.2 Spark profile on 2026-10-26 found a much larger outlier with the reference pack (21 pinned mods, Fabric API and Soul Link; two idle headless clients). During construction of the next run's three staged dimensions, C2ME's density function compiler took 2.9 s in total, with one 2.36 s server-thread step. About 1.35 s was inside C2ME's `GenDumper.dumpDot` call, which builds and writes diagnostic compiler graphs; about 0.97 s was the remaining compiler work. Redirecting the graph output to RAM still left a 2.08 s creation step in one run, so ordinary disk I/O is not the main cost. This is outside the earlier four-preparation sample above.

Terra Rasa now skips only those DOT graph dumps while it constructs a staged level. C2ME's density-function compiler remains enabled, and its compiled class output continues to grow. In a same-seed comparison with the same 26.2 reference pack and two idle clients, staged construction fell from 2.9 s total / 2.36 s for the worst step to 1.52 s / 1.11 s. Spawn search selected different coordinates in the two runs, so total preparation times are not comparable. No server or file errors appeared in the hook run. The mixin targets C2ME internals optionally; if a later C2ME build changes the call site, startup continues and graph dumping simply remains enabled.

In a separate isolated comparison using the same C2ME build with `useDensityFunctionCompiler = false`, construction took 188 ms total and its longest measured step was about 70 ms. The compared future runs used the same seed and selected the same spawn coordinates. This test had no area pregeneration, so it does not establish the effect on terrain-generation throughput or full terrain parity. Keep the compiler enabled; C2ME is intended to improve chunk generation, I/O and loading through parallel work ([C2ME project](https://github.com/RelativityMC/C2ME-fabric)).

### Pregeneration

Soul Link's defaults pregenerate 500 blocks around the run spawn in the Overworld, 96 in the Nether and 160 around the End's main island: 3,639 chunks. The measurements below used two clients online, a run in progress, and the reference modpack's world-generation mods:

| | Measured |
|---|---|
| Full default area | 171–192 s over four runs (19–21 chunks/s) |
| Worst server tick while pregenerating | under 60 ms |
| Worst 5 s window of server ticks, pregeneration running both live and for the next run (P50, P95, P99) | 4.1, 14.1, 44.7 ms |
| `/soullink start` or `/soullink reset` issued mid-pregeneration: players waited | 3.5–4.4 s |

A start stops pregeneration; the rest of the area continues in the live world and resumes after a restart. With nobody online the generation budget rises to 16 chunks; Terra Rasa's own suite measures about 45 chunks/s on vanilla worlds.

Give the server at least 4 GB of heap with pregeneration on. The figures above are on 4 GB with ZGC. On 2 GB, ZGC stalled allocating threads (the server thread included, for up to 35 ms) while two worlds generated at once, and a few ticks per run reached about 70 ms.

### Swapping worlds

`/soullink start` and `/soullink reset` run one server-thread section while every player is in the lobby. On vanilla worlds it takes 0.8–1.8 s: about 50 ms retires, promotes and reconstructs the levels, and the rest loads the new spawn chunk to validate it. With the reference modpack it takes 3–3.4 s, mostly C2ME compiling density functions for each new level (about 2.3 s for the Overworld). The levels for the following run are constructed in the same pause, so they cost no separate hitch later.

### Longevity

No retired level stays reachable after garbage collection across repeated resets, with or without the modpack. Over 100 reset cycles with two connected clients, the mean swap took 1,232 ms over the first 10 cycles and 1,308 ms over the last 10, each client logged in once for the whole run, and the heap after GC went from 334 to 406 MiB.

## Restarts and crashes

The run worlds are ordinary vanilla dimensions, so vanilla saves the terrain and every player's own state: position, dimension, inventory, effects and health. Soul Link saves the rest to `<world>/soullink/state.json`: the run and its seed, the timer, the shared health and food pool, the rules of this run and the next, the participants, and the result of an ended run. Terra Rasa keeps the prepared next run under `<world>/terrarasa/`. Integration coverage includes each case:

- **Graceful stop and restart.** The run resumes exactly where it stopped: same run, seed, rules, shared pool, positions, dimensions, inventories and effects. Shared damage still links the players. The prepared next run is adopted from disk, not generated again.
- **Hard kill.** The run resumes from its last state save, losing at most 5 s of timer. Participants come back into the run in survival, not into the lobby. A crash in the middle of a swap is reconciled by Terra Rasa's journal at the next startup.
- **Restart during preparation.** The incomplete staged run is deleted at startup. With `autoPrepare` enabled, preparation starts again in the background; otherwise the next `/soullink start` begins it.
- **Ended run.** It stays ended across a restart, with its result. The next `/soullink start` works normally.

The run timer only counts while a participant is online, so an empty server does not use up a run's time.

## Compatibility

`modpackTest` runs the integration harness on a server with the [reference modpack](../modpack/reference-pack.lock.json), 21 server-side mods pinned to exact Modrinth versions and verified by SHA-512:

- **Modded worldgen** (Terralith, Tectonic, Incendium, Nullscape, Structory, and the worldgen rewrites C2ME, Fast Noise and Fast Surface): modded biomes appear in all three run dimensions; the prepared spawn equals the pack's own spawn for the seed; noise heights, biomes and structure starts equal a control server started with the same seed.
- **Extra dimensions:** Deeper Dark, listed in `resettableDimensions`, is prepared with the run seed and replaced with the vanilla three, and its data-pack portal works after a reset. A Multiworld runtime dimension and the lobby are left untouched.
- **Mods that cache levels:** Chunky kept the retired Overworld and silently "completed" tasks against it. Terra Rasa's adapter now cancels its tasks before a swap and rebinds its selection. No retired level stays reachable after garbage collection with the full pack.
- **WTHIT and C2ME:** the server modpack test alone is insufficient because its headless clients do not send block-tooltip requests. On 2026-10-26, a rendered 26.2 Fabric client reproduced a server hang and player timeout during `/soullink reset` with WTHIT 20.0.0, Bad Packets 0.12.2, and C2ME 0.4.2-alpha.0.60. Soul Link moves players to the persistent lobby before replacing the run levels. The trace ran from WTHIT's `BlockDataRequestPlayC2SPacket` through `FluidStorageProvider` into chunk loading; C2ME logged repeated async read cancellations and a fallback that blocked loading old spawn chunk `[37,-19]`. Two controls completed normally: with C2ME removed and rendered WTHIT client requests present, the swap took 4.606 s; with C2ME present and WTHIT removed from the client (but still installed server-side), it took 5.952 s and the server remained responsive. In these tests, the failure required both C2ME and rendered-client WTHIT block-data traffic. Bytecode inspection found that WTHIT 20.0.0's singleton `FluidStorageProvider` keeps a `BlockApiCache`; its next request calls `getBlockEntity()` on the previous cache before replacing it for the new target. Since that cache can retain the retired `ServerLevel`, this is a plausible explanation for the access to the old spawn chunk, but the upstream root cause is not confirmed. The similar-sounding upstream [WTHIT issue #367](https://github.com/badasintended/wthit/issues/367) reports a client-side intermittent freeze in `TextileFluidDescriptor.getCauldronFluidData` while calling Fabric's `CauldronFluidContent.getForBlock`; our captured failure is in the server-side `FluidStorageProvider`/`BlockApiCache` path with C2ME chunk loading, so #367 does not establish the same cause. Keep this compatibility issue open; use a rendered-client transition to verify any fix. Test logs are under `build/integration/server-modpack/sessions/` (`wthit-client-manual`, `diagnose-no-c2me`, and `diagnose-c2me-no-wthit`).

NoisiumForked has no stable 26.2 build yet, and Fast Noise declares itself incompatible with Moonrise, so neither is covered.
