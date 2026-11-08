# Soul Link

Cooperative challenge runs for Fabric and NeoForge dedicated servers and integrated single-player/LAN worlds, inspired by the Soul Link format and similar co-op challenges. Shared health, hunger, and saturation are enabled by default and can be disabled for future runs; defeating the Ender Dragon wins.

Each run uses fresh, seeded Overworld, Nether, and End worlds while players stay connected in a persistent lobby. [Terra Rasa](https://github.com/qwasdf-1/terra-rasa) handles world preparation and replacement; it is bundled inside Soul Link's Fabric jar and installed as a separate required mod on NeoForge. Its [README](https://github.com/qwasdf-1/terra-rasa#terra-rasa) explains how canonical replacement keeps vanilla's world behavior intact.

## Features

- **Shared vitality:** all active participants share one health, food, and saturation pool. Damage and food changes add together; simultaneous healing only applies the largest individual heal, so the team cannot multiply regeneration.
- **Optional unlinked vitality:** operators can use `/soullink rules sharedVitality false` to let players keep their own health, food, and saturation in the next run. This leaves world preparation and swaps active.
- **Run outcomes:** with shared vitality enabled, any participant death or an empty shared health pool ends the run in defeat. With it off, normal worlds allow vanilla respawns; vanilla hardcore ends after every participant is eliminated. Operators can enable Soul Link Hardcore to end on the first death regardless of shared vitality. Killing the Ender Dragon wins. After victory, players remain in the world and can use vanilla's exit portal and credits.
- **Fresh worlds without reconnecting:** a persistent lobby holds players during the swap. Runs use new seeds and vanilla dimension identities; world generation and modded biomes work through the normal registries.
- **Background preparation:** the next run's spawn area and optional pregeneration work are prepared while the current run is played. Staged dimensions are constructed one per tick while players wait in the lobby or to start, and at a configurable interval while a run world remains playable. `/soullink start` and `/soullink reset` do not wait for the full pregeneration radius.
- **Run timer and records:** the timer counts only while a participant is online. Run count, victories, and best victory time persist with the world.
- **Late join and restart recovery:** by default, a new player joining a live run is reset to its baseline and enters that run. Existing participants resume their run after a disconnect or server restart.
- **Optional shared potions:** when enabled, ongoing effect gains, upgrades, removals, and expirations are mirrored to teammates. Instant healing and damage already flow through shared vitality.
- **Optional half-heart runs:** limit participants and the shared health pool to one health point, with player max health restored when they return to the lobby.
- **Operator controls:** pause the run timer without pausing gameplay, toggle vanilla natural regeneration, and configure optional pregeneration.
- **Server-owned gameplay:** the host needs Soul Link installed for dedicated or integrated servers on either loader. LAN guests can connect with vanilla clients.

## Requirements and installation

Supported game versions are **1.21.11**, **26.1**, **26.1.1**, **26.1.2**, **26.2**, and **26.3**. Minecraft 1.21.11 builds with Java 21; the 26.x versions build with Java 25. Soul Link provides a separate build for each supported game version and loader, and each target's toolchain versions are pinned in [`gradle/minecraft-targets.json`](gradle/minecraft-targets.json). Gradle 9.9.0 is required to build from source.

Fabric builds cover every listed Minecraft version and use Fabric Loader 0.19.5:

| Minecraft | Java | Fabric Loom | Fabric API |
|---|---:|---:|---|
| 1.21.11 | 21 | 1.17.21 | 0.141.6+1.21.11 |
| 26.1 | 25 | 1.18.4 | 0.145.1+26.1 |
| 26.1.1 | 25 | 1.18.4 | 0.145.4+26.1.1 |
| 26.1.2 | 25 | 1.18.4 | 0.155.3+26.1.2 |
| 26.2 | 25 | 1.18.4 | 0.161.0+26.2 |
| 26.3 | 25 | 1.18.4 | 0.165.2+26.3 |

Each Fabric build declares the pinned build as a minimum - fabric-api of at least that version - and runs on any newer build for the same Minecraft version. The table names the build it is compiled against, not the only one it accepts.

NeoForge builds exist for the Minecraft versions NeoForge has a stable release for. They use ModDevGradle 2.0.148:

| Minecraft | Java | NeoForge |
|---|---:|---:|
| 1.21.11 | 21 | 21.11.48 |
| 26.1.2 | 25 | 26.1.2.126 |
| 26.2 | 25 | 26.2.0.93 |
| 26.3 | 25 | 26.3.0.101 |

Build the mod with a Terra Rasa checkout next to this repository:

```sh
git clone https://github.com/qwasdf-1/terra-rasa.git
git clone https://github.com/qwasdf-1/soul-link.git
cd terra-rasa
./gradlew publishToMavenLocal
cd ../soul-link
./gradlew build
```

The build uses the matching Terra Rasa artifact from Maven Local. To build a non-default game target, pass the matching Minecraft, Java, and loader values from [`gradle/minecraft-targets.json`](gradle/minecraft-targets.json) to both projects; the default is the 26.2 Fabric build.

For example, to build the library for Minecraft 1.21.11 before building Soul Link:

```sh
# Run these commands from the parent directory containing both checkouts.
cd terra-rasa
./gradlew publishToMavenLocal -Ploader=fabric -Ploom_version=1.17.21 -Pminecraft_version=1.21.11 -Pfabric_loader_version=0.19.5 -Pfabric_api_version=0.141.6+1.21.11 -Pjava_version=21
cd ../soul-link
./gradlew build -Ploader=fabric -Ploom_version=1.17.21 -Pminecraft_version=1.21.11 -Pfabric_loader_version=0.19.5 -Pfabric_api_version=0.141.6+1.21.11 -Pjava_version=21
```

For a NeoForge build, pass the loader and the NeoForge toolchain values to both projects; Terra Rasa publishes a separate artifact for that loader:

```sh
# Run these commands from the parent directory containing both checkouts.
cd terra-rasa
./gradlew publishToMavenLocal -Ploader=neoforge -Pmoddev_version=2.0.148 -Pminecraft_version=26.2 -Pneoforge_version=26.2.0.93 -Pjava_version=25
cd ../soul-link
./gradlew build -Ploader=neoforge -Pmoddev_version=2.0.148 -Pminecraft_version=26.2 -Pneoforge_version=26.2.0.93 -Pjava_version=25
```

Place the jar matching the game version and loader in the dedicated server's `mods/` directory or the single-player host's `mods/` directory. The Fabric jar is named `build/libs/soullink-mc<game-version>-<mod-version>.jar`, for example `soullink-mc1.21.11-<mod-version>.jar` on 1.21.11 or `soullink-mc26.2-<mod-version>.jar` on 26.2. The NeoForge jar is the `soullink-mc<game-version>-neoforge-<mod-version>.jar` file, for example `soullink-mc26.2-neoforge-<mod-version>.jar` on 26.2.

Fabric servers also need Fabric API in `mods/`. Terra Rasa is bundled inside the Soul Link jar on Fabric; on NeoForge it is a separate required mod, so install the matching Terra Rasa jar (`terra-rasa-mc<game-version>-neoforge-<mod-version>.jar`) next to it. The lobby dimension and its platform are included, so no extra datapack or world setup is required.

By default, Soul Link replaces the vanilla Overworld, Nether, and End. The lobby and any dimension not listed for replacement remain untouched. Mod or datapack dimensions used by the run can be added in `config/soullink.json`; details are in the [configuration guide](docs/configuration.md).

## Playing

| Command | Access | What it does |
|---|---|---|
| `/soullink help` | Everyone | Show command help and permissions. |
| `/soullink start` | Everyone | Start the prepared run, or prepare one and start automatically when it is ready. The first run on a new server starts automatically. |
| `/soullink reset` | Everyone | Abandon the current run and start a fresh one. |
| `/soullink info` | Everyone | Show run state, time, shared vitality, and next-world phase. |
| `/soullink status` | Operators | Show readable lifecycle, preparation, and failure details. |
| `/soullink stop` | Operators | End the run and return everyone to the lobby. |
| `/soullink pause` / `/soullink resume` | Operators | Pause or resume only the run timer; gameplay continues. |
| `/soullink setup` | Operators | Open the vanilla settings menu in game; from console, show a text summary. `/soullink setup info` always shows text. The menu controls live rules, pool size, and per-dimension pregeneration. |
| `/soullink setup pool <futureRuns>` | Operators | Set the prepared future-run pool to 1–3; the active run is separate. |
| `/soullink setup pregen <dimension> <radiusBlocks>` | Operators | Set that dimension's pregeneration radius in blocks; `0` disables it. |
| `/soullink prepare [seed|random] [preloadRadius]` | Operators | Replace queued ready runs with a new preparation. Wait for any current preparation to finish first. Omitting the seed chooses one randomly; omitting the radius uses the configured default. |
| `/soullink rules sharedVitality <true\|false>` | Operators | Link or separate health, food, and saturation starting next run. |
| `/soullink rules soulLinkHardcore <true\|false>` | Operators | End the next run on any participant's first death, even with shared vitality off. |
| `/soullink rules sharedPotions <true\|false>` | Operators | Set whether shared potions are enabled for the next run and save the setting. |
| `/soullink rules naturalRegeneration <true\|false>` | Operators | Change vanilla's `naturalRegeneration` game rule immediately. |
| `/soullink rules halfHeart <true\|false>` | Operators | Set max health and shared health to 1 HP starting next run. |
| `/soullink rules allowLateJoin <true\|false>` | Operators | Allow or prevent new players from joining an active run immediately. |
| `/soullink rules autoPrepare <true\|false>` | Operators | Enable or disable background preparation of future runs immediately. |

All mod commands are grouped under `/soullink`. Machine-readable status is available through `/soullink debug lifecycle` when `debugCommands` is enabled.

Players can read the [gameplay guide](docs/gameplay.md) for the lobby, run states, shared-pool rules, joining, and end conditions.

## Configuration

`config/soullink.json` is created on first start. These are its default settings:

| Setting | Default | Purpose |
|---|---|---|
| `resettableDimensions` | Overworld, Nether, End | Dimensions replaced for each run; add registered mod or datapack dimensions that belong to the run. |
| `persistentDimensions` | `soullink:lobby` | Dimensions that activation must keep loaded and never replace. Must include the lobby. |
| `preloadRadius` | `6` chunks | Spawn area loaded before a run can start. |
| `pregeneration` | Overworld `500`, Nether `96`, End `160` blocks | Background area generated around the run spawn or End island. Use `{}` to disable it. |
| `preparedRunPoolSize` | `1` | Number of prepared future runs, from `1` to `3`, excluding the active run. Change it with `/soullink setup pool <futureRuns>`. |
| `activeRunConstructionIntervalTicks` | `20` | Minimum ticks between staged dimension constructions during and after a run (`1`–`1200`). Lobby and startup preparation always use one tick between dimensions. |
| `preparationTimeoutSeconds` | `900` | Limit for preparation up to a usable spawn. |
| `drainTimeoutSeconds` | `60` | Time allowed to drain and close old levels during activation. |
| `autoPrepare` | `true` | Fill the configured prepared-run pool. The first run starts automatically once its spawn is ready; later runs wait for `/soullink start`. |
| `allowLateJoin` | `true` | Admit new players into an active run; otherwise they wait in the lobby. |
| `sharedVitality` | `true` | Share or separate health, food, and saturation. The operator command saves the next-run value. |
| `soulLinkHardcore` | `false` | End a run on the first participant death, independent of shared vitality. Applies to the next run. |
| `sharedPotions` | `false` | Mirror ongoing status effects between participants. Applies to the next run. |
| `halfHeart` | `false` | Give each participant and the shared pool 1 HP starting next run. |
| `debugCommands` | `true` | Register operator-only `/soullink debug` diagnostics. |

See the [configuration guide](docs/configuration.md) for valid ranges, custom dimensions, and which settings can change while the server is running.

## Documentation

- [Documentation map](docs/README.md)
- [Gameplay guide](docs/gameplay.md)
- [Configuration guide](docs/configuration.md)
- [Operations and compatibility reference](docs/reference.md): reset coverage, restarts, pregeneration, performance, and tested mod interactions.
- [Publishing](docs/publishing.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Build and test

```sh
./gradlew build
./gradlew integrationTest
./gradlew modpackTest
```

The integration suites run the mod on a real dedicated server with headless clients. The modpack suite adds pinned, checksum-verified world-generation and compatibility mods. Both suites currently install a Fabric server; the NeoForge server install path is in place and the suites still need a NeoForge run.

## Not included

Features like shared jumping, shared inventory, manhunt, settings screens, and meta-progression are not implemented.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
