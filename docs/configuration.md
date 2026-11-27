# Configuration

Soul Link reads `config/soullink.toml` at server startup and creates it with defaults if it does not exist. If an existing `config/soullink.json` is present and `config/soullink.toml` is missing, Soul Link migrates the JSON values into `config/soullink.toml` on first boot. Restart after editing non-runtime settings in the file. In game, operators can open `/soullink setup` for a vanilla chest-style menu; it uses the server's normal menu packets and needs no Soul Link client mod. The dedicated-server console gets a text summary and can use the command forms below.

The first run on a new server starts automatically once its spawn is ready. Rules changed with `/soullink rules <rule> <value>` apply in memory immediately (or from the next run for `sharedVitality`, `soulLinkHardcore`, `sharedPotions`, and `halfHeart`); passing optional `default` at the end (e.g. `/soullink rules sharedVitality false default`) also updates the default in `config/soullink.toml`. Changes made through `/soullink setup` save directly to `config/soullink.toml`.

```toml
# Gameplay rules (sharedVitality, soulLinkHardcore, sharedPotions, and halfHeart apply from the next run)
sharedVitality = true
soulLinkHardcore = false
sharedPotions = false
halfHeart = false
naturalRegeneration = true

# Display and end-of-run statistics
timerVisible = true
# Damage report verbosity in chat: "normal", "minimal", or "off"
damageReport = "normal"
# Print concise per-player time played and damage taken at the end of a run
runStats = true

# Server and lobby behavior
allowLateJoin = true
autoPrepare = true
debugCommands = true

# World preparation and pool
preparedRunPoolSize = 1
preloadRadius = 6
activeRunConstructionIntervalTicks = 20
preparationTimeoutSeconds = 900
drainTimeoutSeconds = 60

resettableDimensions = [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end",
]
persistentDimensions = [
    "soullink:lobby",
]

[pregeneration]
"minecraft:overworld" = 500
"minecraft:the_nether" = 96
"minecraft:the_end" = 160
```

## Settings

| Key | Default | Valid values and behavior |
|---|---|---|
| `resettableDimensions` | Overworld, Nether, End | Dimension identifiers with registered level stems. Must include `minecraft:overworld`; cannot include a persistent dimension. Add a mod or datapack dimension here if its world should be fresh each run. |
| `persistentDimensions` | `soullink:lobby` | Registered dimensions that must remain loaded during activation and are never replaced. Must include `soullink:lobby`. |
| `preloadRadius` | `6` | `0`–`32` chunks around the prepared spawn, loaded before a run can start. `/soullink prepare <seed> <radius>` can override it for one preparation. |
| `pregeneration` | Overworld `500`, Nether `96`, End `160` | Table of resettable dimension identifiers to radii in blocks. `0` or omission disables an area; maximum radius is `4096`. Pregeneration is optional background work and is never a prerequisite for starting. |
| `preparedRunPoolSize` | `1` | Number of closed future runs to keep ready, from `1` to `3`. The active run does not count. Change it with `/soullink setup pool <futureRuns>`. |
| `activeRunConstructionIntervalTicks` | `20` | Minimum ticks between creating staged dimensions while a run world is active or ended. Set `1` to construct at most one dimension per tick; valid range is `1`–`1200`. Lobby and startup preparation always use one tick between dimensions. Editing this setting requires a server restart. |
| `preparationTimeoutSeconds` | `900` | At least `10` seconds. Bounds preparation through the usable spawn; if the only remaining work is optional pregeneration, it stops there without failing the ready run. |
| `drainTimeoutSeconds` | `60` | At least `5` seconds to finish pending saves and close retired levels during a swap. |
| `autoPrepare` | `true` | Fill the configured future-run pool while the server is in the lobby or a run is active. The initial first run starts automatically; later prepared runs wait for `/soullink start`. Toggle with `/soullink rules autoPrepare <true|false> [default]` or the setup menu. Disabling it does not cancel work already in progress. |
| `allowLateJoin` | `true` | Admit new players to an active run. When false, players who are not recorded participants wait in the lobby. Toggle immediately with `/soullink rules allowLateJoin <true|false> [default]` or the setup menu. |
| `sharedVitality` | `true` | Share health, food, and saturation between active participants. Set the next-run value with `/soullink rules sharedVitality <true|false> [default]` or the setup menu. |
| `soulLinkHardcore` | `false` | End a run on any participant's first death, even when shared vitality is disabled. Set the next-run value with `/soullink rules soulLinkHardcore <true|false> [default]` or the setup menu. |
| `sharedPotions` | `false` | Mirror ongoing status effect gains, upgrades, removals, and expirations among active participants. Instant damage and healing follow shared vitality when that rule is on. |
| `halfHeart` | `false` | Give each participant a 1 HP maximum and make the shared pool 1 HP, starting next run. Original max-health base values are restored when participants return to the lobby. Set with `/soullink rules halfHeart <true|false> [default]` or the setup menu. |
| `naturalRegeneration` | `true` | Default for vanilla's `naturalRegeneration` game rule applied at startup and updated immediately with `/soullink rules naturalRegeneration <true|false> [default]` or the setup menu. |
| `timerVisible` | `true` | Show or hide the action-bar run timer during active runs. Toggle immediately with `/soullink rules timerVisible <true|false> [default]` or the setup menu. |
| `damageReport` | `"normal"` | Controls shared-damage chat notices: `"normal"` shows `<player> took <amount> damage [♥ <hp>/<max>]`, `"minimal"` shows `took <amount> damage`, and `"off"` suppresses chat notices. Change immediately with `/soullink rules damageReport <normal|minimal|off> [default]` or the setup menu. |
| `runStats` | `true` | Broadcast concise per-player time played and damage taken when a run ends (victory, defeat, stop, or reset). Toggle immediately with `/soullink rules runStats <true|false> [default]` or the setup menu. |
| `debugCommands` | `true` | Register `/soullink debug` for operators. Set false to omit those diagnostics; `/soullink status`, `/soullink prepare`, and `/soullink rules` remain available. |

## Custom dimensions

Only dimensions in `resettableDimensions` are replaced. A dimension in `persistentDimensions` is kept as a service world. Every other dimension is external: Terra Rasa does not move its files or replace its level object, and it keeps the world's pinned seed.

To refresh a mod or datapack dimension for each run:

1. Make sure its level stem is registered when the server starts.
2. Add its identifier to `resettableDimensions`.
3. Add a radius for it to `[pregeneration]` only if you want an area generated ahead of play. When you replace the default `[pregeneration]` table, keep entries for vanilla dimensions you still want pregenerated.

The Overworld must remain resettable, and `soullink:lobby` must remain persistent. A dimension cannot appear in both lists. Invalid or unregistered policy entries prevent the server configuration from being accepted. If the TOML cannot be parsed, Soul Link logs the error and uses defaults.

## Changing rules at runtime

Operators can change rules without restarting:

```text
/soullink rules sharedPotions true
```

Without `default`, the change stays in memory (affecting the current run for immediate rules, or starting next run for `sharedVitality`, `soulLinkHardcore`, `sharedPotions`, and `halfHeart`). Pass `default` to persist the setting as the new default in `config/soullink.toml`:

```text
/soullink rules sharedPotions true default
```

The setup menu (`/soullink setup`) shows live values, toggles settings (saving to `config/soullink.toml`), adjusts the prepared-run pool, and provides paged controls for each resettable dimension's pregeneration radius. Advanced settings that require a restart are shown read-only with that requirement labeled. Use `/soullink setup info` for text output in game or console.

## Debug and failure injection

Operators can use `/soullink status` for readable lifecycle and preparation details. `/soullink debug lifecycle` retains the machine-readable JSON output. `/soullink debug fail <point>` is intended for recovery testing. It can simulate activation failures; the `halt-after-*` points forcibly halt the JVM. The points are inert unless the server starts with `-Dterrarasa.faultInjection=true`. Disable `debugCommands` on servers where operators should not have these diagnostics.
