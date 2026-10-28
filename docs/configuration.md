# Configuration

Soul Link reads `config/soullink.json` at server startup and creates it with defaults if it does not exist. Restart after editing the file. The first run on a new server starts automatically once its spawn is ready. `/soullink rules sharedVitality`, `/soullink rules soulLinkHardcore`, and `/soullink rules sharedPotions` write their settings immediately, but only change the next run. `/soullink rules naturalRegeneration` changes vanilla's persisted game rule immediately.

```json
{
  "resettableDimensions": [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end"
  ],
  "persistentDimensions": ["soullink:lobby"],
  "preloadRadius": 6,
  "pregeneration": {
    "minecraft:overworld": 500,
    "minecraft:the_nether": 96,
    "minecraft:the_end": 160
  },
  "preparedRunPoolSize": 1,
  "activeRunConstructionIntervalTicks": 20,
  "preparationTimeoutSeconds": 900,
  "drainTimeoutSeconds": 60,
  "autoPrepare": true,
  "allowLateJoin": true,
  "sharedVitality": true,
  "soulLinkHardcore": false,
  "sharedPotions": false,
  "halfHeart": false,
  "debugCommands": true
}
```

## Settings

| Key | Default | Valid values and behavior |
|---|---|---|
| `resettableDimensions` | Overworld, Nether, End | Dimension identifiers with registered level stems. Must include `minecraft:overworld`; cannot include a persistent dimension. Add a mod or datapack dimension here if its world should be fresh each run. |
| `persistentDimensions` | `soullink:lobby` | Registered dimensions that must remain loaded during activation and are never replaced. Must include `soullink:lobby`. |
| `preloadRadius` | `6` | `0`–`32` chunks around the prepared spawn, loaded before a run can start. `/soullink prepare <seed> <radius>` can override it for one preparation. |
| `pregeneration` | Overworld `500`, Nether `96`, End `160` | Map of resettable dimension identifiers to radii in blocks. `0` or omission disables an area; maximum radius is `4096`. Pregeneration is optional background work and is never a prerequisite for starting. |
| `preparedRunPoolSize` | `1` | Number of closed future runs to keep ready, from `1` to `3`. The active run does not count. Change it with `/soullink setup pool <futureRuns>`. |
| `activeRunConstructionIntervalTicks` | `20` | Minimum ticks between creating staged dimensions while a run world is active or ended. Set `1` to construct at most one dimension per tick; valid range is `1`–`1200`. Lobby and startup preparation always use one tick between dimensions. Editing this setting requires a server restart. |
| `preparationTimeoutSeconds` | `900` | At least `10` seconds. Bounds preparation through the usable spawn; if the only remaining work is optional pregeneration, it stops there without failing the ready run. |
| `drainTimeoutSeconds` | `60` | At least `5` seconds to finish pending saves and close retired levels during a swap. |
| `autoPrepare` | `true` | Fill the configured future-run pool while the server is in the lobby or a run is active. The initial first run starts automatically; later prepared runs wait for `/soullink start`. |
| `allowLateJoin` | `true` | Admit new players to an active run. When false, players who are not recorded participants wait in the lobby. |
| `sharedVitality` | `true` | Share health, food, and saturation between active participants. `/soullink rules sharedVitality <true|false>` saves the next-run value immediately. |
| `soulLinkHardcore` | `false` | End a run on any participant's first death, even when shared vitality is disabled. `/soullink rules soulLinkHardcore <true|false>` saves the next-run value immediately. |
| `sharedPotions` | `false` | Mirror ongoing status effect gains, upgrades, removals, and expirations among active participants. Instant damage and healing follow shared vitality when that rule is on. |
| `halfHeart` | `false` | Give each participant a 1 HP maximum and make the shared pool 1 HP, starting next run. Original max-health base values are restored when participants return to the lobby. |
| `debugCommands` | `true` | Register `/soullink debug` for operators. Set false to omit those diagnostics; `/soullink status`, `/soullink prepare`, and `/soullink rules` remain available. |

## Custom dimensions

Only dimensions in `resettableDimensions` are replaced. A dimension in `persistentDimensions` is kept as a service world. Every other dimension is external: Terra Rasa does not move its files or replace its level object, and it keeps the world's pinned seed.

To refresh a mod or datapack dimension for each run:

1. Make sure its level stem is registered when the server starts.
2. Add its identifier to `resettableDimensions`.
3. Add a radius for it to `pregeneration` only if you want an area generated ahead of play. When you replace the default `pregeneration` object, keep entries for vanilla dimensions you still want pregenerated.

The Overworld must remain resettable, and `soullink:lobby` must remain persistent. A dimension cannot appear in both lists. Invalid or unregistered policy entries prevent the server configuration from being accepted. If the JSON cannot be parsed, Soul Link logs the error and uses defaults.

## Shared potions at runtime

Operators can change the next-run value without restarting:

```text
/soullink rules sharedPotions true
```

The command saves `sharedPotions` to `config/soullink.json`. The current run keeps the rule it started with.

## Shared vitality at runtime

Operators can choose whether players share health, food, and saturation:

```text
/soullink rules sharedVitality false
```

The setting is saved in `config/soullink.json` and applies to the next run. The active run keeps its original setting. Shared potions and half-heart mode remain independent settings.

When shared vitality is disabled, normal-difficulty runs use vanilla respawns. In vanilla hardcore, each dead participant is eliminated and the run ends after the whole team is eliminated. Set `soulLinkHardcore` to `true` to make any participant death end the run, regardless of vanilla hardcore or shared vitality.

The pool size can also be changed without restarting:

```text
/soullink setup pool 3
```

This keeps up to three prepared future runs on disk, in start order. Reducing the size discards the newest queued runs. The active run is separate from the pool. `/soullink setup` shows the current pool count and per-dimension pregeneration radii.

Toggle natural regeneration immediately through vanilla's game rule:

```text
/soullink rules naturalRegeneration false
```

This changes the same world-persisted rule as `/gamerule naturalRegeneration`; it is not duplicated in Soul Link's config.

Half-heart mode is saved for the next run through:

```text
/soullink rules halfHeart true
```

Each participant is limited to one health point (half a heart), and shared health is capped at the same value. The mode stays fixed for the current run.

## Debug and failure injection

Operators can use `/soullink status` for readable lifecycle and preparation details. `/soullink debug lifecycle` retains the machine-readable JSON output. `/soullink debug fail <point>` is intended for recovery testing. It can simulate activation failures; the `halt-after-*` points forcibly halt the JVM. The points are inert unless the server starts with `-Dterrarasa.faultInjection=true`. Disable `debugCommands` on servers where operators should not have these diagnostics.
