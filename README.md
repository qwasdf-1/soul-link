# Soul Link

Cooperative Soul Link speedruns for a Fabric dedicated server. All players share one pool of health, hunger and saturation; any death ends the run for everyone; killing the Ender Dragon wins. Every run happens in **freshly seeded worlds that are the real canonical dimensions** (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`), swapped in within one continuously running server while players wait in a persistent lobby. Nobody reconnects between runs.

Soul Link is a standalone reimplementation of the game played by [Soul Link Speedrun](https://github.com/zenzty/Soul-Link-Speedrun); it is not a fork and not affiliated with that project, and shares none of its code. Because run worlds are canonical, vanilla handles portals, the End platform, the dragon fight, spawn selection and dimension advancements itself, with no per-feature compatibility shims.

Soul Link is the game. The world swapping underneath it is **[Terra Rasa](https://github.com/qwasdf-1/terra-rasa)** (mod id `terrarasa`), a separate library in its own repository. Terra Rasa prepares freshly seeded dimensions in the background, swaps them in under the canonical keys, journals every step for crash recovery, and returns server-owned and per-player state to a new world's baseline. It knows nothing about this game. Soul Link adds the lobby, the run lifecycle, shared vitality and potions, commands and the timer, and its jar bundles Terra Rasa.

What a reset covers, measured costs, behaviour across restarts and crashes, and modpack compatibility are in [docs/reference.md](docs/reference.md).

## Versions

Minecraft **26.2** only (exact), Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Loom 1.18.4, Gradle 9.7.1. The mod runs on the server only; vanilla clients connect normally.

## Build and install

Check out Terra Rasa next to this repository, as `../terra-rasa`, then build:

```sh
git clone https://github.com/qwasdf-1/terra-rasa.git
git clone https://github.com/qwasdf-1/soul-link.git
cd soul-link
./gradlew build
```

The build uses that checkout in place of the published `dev.terrarasa:terra-rasa` artifact, so the two can be changed and tested together. `-PterraRasaPath=<dir>` points at another checkout. Until Terra Rasa has a public Maven repository, `-PterraRasaPath=` (empty) resolves a release that was published to the local Maven repository with `./gradlew publishToMavenLocal` in Terra Rasa.

Copy `build/libs/soullink-mc26.2-<version>.jar` and Fabric API into the server's `mods/` folder. The jar contains Terra Rasa, so nothing else is needed. The mod ships the `soullink:lobby` dimension as built-in data, so worlds need no extra setup.

## Playing

| Command | Who | Effect |
|---|---|---|
| `/start` | everyone | Enter the prepared run, or prepare one and start automatically when ready |
| `/reset` | everyone | Abandon the current run and start the next |
| `/runinfo` | everyone | Status, timer, shared stats, next-world readiness |
| `/stoprun` | operators | End the run and send everyone to the lobby |
| `/soullink status` | operators | Full lifecycle state as JSON |
| `/soullink prepare [seed] [preloadRadius]` | operators | Discard the prepared run and prepare a new one |
| `/soullink rules sharedPotions <true\|false>` | operators | Shared potions from the next run on (saved to the config) |
| `/soullink debug lifecycle\|probe [<dimension> <x> <z>]\|end\|gc\|player <p>\|fail <point>` | operators | Diagnostics; `fail` needs the JVM flag `-Dterrarasa.faultInjection=true` |

The next run is generated in the background while the current one is played, so `/start` and `/reset` normally take about one to two seconds. The area around its spawn is pregenerated too; if a start comes first, the rest is generated in the live world while you play. A timer shows in the action bar; it counts while at least one participant is online. Late joiners are synced into the running run.

Any death ends the run for everyone and turns participants into spectators. Killing the dragon wins: the timer stops, and players keep playing, so the exit portal rolls the credits and returns them to the run's spawn as in vanilla. A restart or crash resumes the run: worlds, timer, shared pool, rules and the prepared next run.

**Shared potions** (one of Soul Link Speedrun's chaos options, off by default): every effect a participant gains, upgrades or loses (potions, beacons, mobs, milk, totems, expiry) applies to every participant, following vanilla's merge rules. Instant healing and damage are already shared by the vitality pool.

## Configuration

`config/soullink.json` is created on first start:

| Key | Default | Meaning |
|---|---|---|
| `resettableDimensions` | overworld, the_nether, the_end | Dimensions replaced on every run. Add a datapack/mod dimension here to reset it too |
| `persistentDimensions` | `soullink:lobby` | Never touched by run activation |
| `preloadRadius` | 6 | Chunk radius pregenerated around the prepared spawn |
| `pregeneration` | overworld 500, the_nether 96, the_end 160 | Blocks to pregenerate per run dimension, around the run spawn (scaled 1:8 in the Nether) or the End's main island. A start never waits for it; what is left continues in the live world. Give the server at least 4 GB of heap with it on. `{}` turns it off |
| `preparationTimeoutSeconds` / `drainTimeoutSeconds` | 900 / 60 | Lifecycle timeouts |
| `autoPrepare` | true | Keep one prepared run ready |
| `allowLateJoin` | true | Late joiners enter the running run instead of waiting in the lobby |
| `sharedPotions` | false | Shared potions; a change applies from the next run |
| `debugCommands` | true | Register `/soullink debug` (operators only) |

Dimensions in neither list, including dimensions mods create at runtime, are left alone. A mod or data-pack dimension that belongs to the run (its portal sits in the run's Overworld, say) should be listed as resettable; it is then staged with the run seed and replaced with the vanilla three.

## How a reset works

Terra Rasa does the first three steps; its README describes them in detail.

1. **Prepare:** the next run's dimensions are built under staging keys with their own seed, generated around vanilla's spawn in the background, then saved and closed.
2. **Activate:** with everyone in the lobby, one server-thread section at the end of a tick retires the old canonical levels and promotes the staged directories under the canonical keys. It then rebinds the global seed and constructs new vanilla levels. Failures roll back in process, and crashes are reconciled from a journal at the next startup.
3. **Reset session state:** scoreboard, teams, command storage, boss bars, scheduled functions, random sequences, weather, clocks and the wandering-trader timer go back to a new world's baseline, and `#minecraft:load` runs again. Players get inventory, ender chest, XP, effects, attributes, tags, respawn point, recipes, advancements, statistics and "seen the credits" reset. Game rules and difficulty are operator settings and persist.
4. **Enter:** Soul Link teleports players to the prepared spawn in the new Overworld.

## Testing

```sh
./gradlew test                 # JUnit: vitality math, timer, state snapshots
./gradlew integrationTest      # real dedicated-server suite (needs uv and Python 3.14)
./gradlew integrationTest -Ppytest.args="-m slow"   # 100-cycle longevity run
./gradlew modpackTest          # the reference modpack suite (downloads the pinned mods from Modrinth)
```

`integrationTest` assembles a production-style server in `build/integration/server`: the official Fabric server launcher, the pinned vanilla 26.2 jar, Fabric API and the built jar. The [Python harness](testing/) uses the test kit that ships with Terra Rasa, from the checkout next to this one (`../terra-rasa/testing`). It starts one isolated server per test module, drives it over RCON and connects headless protocol clients. Their packet ids come from the vanilla data generator's `packets.json` for the same jar. The clients behave like vanilla players (keep-alives, teleport confirmation, chunk batches), so the suite verifies the player-facing contract: clients stay connected through resets and observe the real dimension keys. Each module fails if the server log contains unexpected `ERROR` lines. Pass extra pytest arguments with `-Ppytest.args="..."`.

`modpackTest` runs the same harness on a server with the [reference modpack](modpack/reference-pack.lock.json): 19 server-side mods pinned by version and SHA-512, covering modded worldgen in all three dimensions (Terralith, Tectonic, Incendium, Nullscape, Structory), an extra data-pack dimension with its own portal (Deeper Dark), runtime dimensions (Multiworld), and mods that rewrite worldgen or the chunk system, or cache levels (C2ME, Fast Noise, Fast Surface, Lithium, ScalableLux, VMP, Krypton, FerriteCore, spark, Chunky). The jars are downloaded at test time, verified, and never redistributed.

## Not implemented yet

Other chaos options (shared jumping, half-heart mode, synced inventory), a manhunt mode, settings GUIs and meta-progression.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
