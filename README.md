# Soul Link

Cooperative challenge runs for a Fabric dedicated server, inspired by the Soul Link format and similar co-op challenges. Players share health, hunger, and saturation; any participant's death ends the run, and defeating the Ender Dragon wins.

Each run uses fresh, seeded Overworld, Nether, and End worlds while players stay connected in a persistent lobby. [Terra Rasa](https://github.com/qwasdf-1/terra-rasa) handles world preparation and replacement and is bundled in Soul Link's mod jar. Its [README](https://github.com/qwasdf-1/terra-rasa#terra-rasa) explains how canonical replacement keeps vanilla's world behavior intact.

## Features

- **Shared vitality:** all active participants share one health, food, and saturation pool. Damage and food changes add together; simultaneous healing only applies the largest individual heal, so the team cannot multiply regeneration.
- **Run outcomes:** any participant death or an empty shared health pool ends the run in defeat. Killing the Ender Dragon wins. After victory, players remain in the world and can use vanilla's exit portal and credits.
- **Fresh worlds without reconnecting:** a persistent lobby holds players during the swap. Runs use new seeds and vanilla dimension identities; world generation and modded biomes work through the normal registries.
- **Background preparation:** the next run's spawn area and optional pregeneration work are prepared while the current run is played. `/start` and `/reset` do not wait for the full pregeneration radius.
- **Run timer and records:** the timer counts only while a participant is online. Run count, victories, and best victory time persist with the world.
- **Late join and restart recovery:** by default, a new player joining a live run is reset to its baseline and enters that run. Existing participants resume their run after a disconnect or server restart.
- **Optional shared potions:** when enabled, ongoing effect gains, upgrades, removals, and expirations are mirrored to teammates. Instant healing and damage already flow through shared vitality.
- **Server-side only:** vanilla clients connect normally.

## Requirements and installation

Minecraft **26.2** (exact), Java 25, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, and Gradle 9.7.1 to build from source.

Build the mod with a Terra Rasa checkout next to this repository:

```sh
git clone https://github.com/qwasdf-1/terra-rasa.git
git clone https://github.com/qwasdf-1/soul-link.git
cd soul-link
./gradlew build
```

Place `build/libs/soullink-mc26.2-<version>.jar` and Fabric API in the dedicated server's `mods/` directory. Terra Rasa is bundled; the lobby dimension and its platform are included, so no extra datapack or world setup is required.

By default, Soul Link replaces the vanilla Overworld, Nether, and End. The lobby and any dimension not listed for replacement remain untouched. Mod or datapack dimensions used by the run can be added in `config/soullink.json`; details are in the [configuration guide](docs/configuration.md).

## Playing

| Command | Access | What it does |
|---|---|---|
| `/start` | Everyone | Start the prepared run, or prepare one and start automatically when it is ready. |
| `/reset` | Everyone | Abandon the current run and start a fresh one. |
| `/runinfo` | Everyone | Show run state, time, shared vitality, and next-world readiness. |
| `/stoprun` | Operators | End the run and return everyone to the lobby. |
| `/soullink status` | Operators | Print the full lifecycle state as JSON. |
| `/soullink prepare [seed] [preloadRadius]` | Operators | Replace any queued preparation with a new one. A radius is optional after a seed. |
| `/soullink rules sharedPotions <true\|false>` | Operators | Set whether shared potions are enabled for the next run and save the setting. |

Players can read the [gameplay guide](docs/gameplay.md) for the lobby, run states, shared-pool rules, joining, and end conditions.

## Configuration

`config/soullink.json` is created on first start. These are its default settings:

| Setting | Default | Purpose |
|---|---|---|
| `resettableDimensions` | Overworld, Nether, End | Dimensions replaced for each run; add registered mod or datapack dimensions that belong to the run. |
| `persistentDimensions` | `soullink:lobby` | Dimensions that activation must keep loaded and never replace. Must include the lobby. |
| `preloadRadius` | `6` chunks | Spawn area loaded before a run can start. |
| `pregeneration` | Overworld `500`, Nether `96`, End `160` blocks | Background area generated around the run spawn or End island. Use `{}` to disable it. |
| `preparationTimeoutSeconds` | `900` | Limit for preparation up to a usable spawn. |
| `drainTimeoutSeconds` | `60` | Time allowed to drain and close old levels during activation. |
| `autoPrepare` | `true` | Keep one future run ready; this does not start a run automatically. |
| `allowLateJoin` | `true` | Admit new players into an active run; otherwise they wait in the lobby. |
| `sharedPotions` | `false` | Mirror ongoing status effects between participants. Applies to the next run. |
| `debugCommands` | `true` | Register operator-only `/soullink debug` diagnostics. |

See the [configuration guide](docs/configuration.md) for valid ranges, custom dimensions, and which settings can change while the server is running.

## Documentation

- [Documentation map](docs/README.md)
- [Gameplay guide](docs/gameplay.md)
- [Configuration guide](docs/configuration.md)
- [Operations and compatibility reference](docs/reference.md): reset coverage, restarts, pregeneration, performance, and tested mod interactions.
- [Publishing to Modrinth](docs/publishing.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Build and test

```sh
./gradlew build
./gradlew integrationTest
./gradlew modpackTest
```

The integration suites run the mod on a real dedicated server with headless clients. The modpack suite adds pinned, checksum-verified world-generation and compatibility mods.

## Not included

Features like shared jumping, shared inventory, half-heart mode, manhunt, settings screens, and meta-progression are not implemented.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
