# Configuration

Soul Link reads `config/soullink.json` at server startup and creates it with defaults if it does not exist. Restart after editing the file. The `/soullink rules sharedPotions` command is the exception: it writes that setting immediately, but it still only changes the next run.

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
  "preparationTimeoutSeconds": 900,
  "drainTimeoutSeconds": 60,
  "autoPrepare": true,
  "allowLateJoin": true,
  "sharedPotions": false,
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
| `preparationTimeoutSeconds` | `900` | At least `10` seconds. Bounds preparation through the usable spawn; if the only remaining work is optional pregeneration, it stops there without failing the ready run. |
| `drainTimeoutSeconds` | `60` | At least `5` seconds to finish pending saves and close retired levels during a swap. |
| `autoPrepare` | `true` | Keep one future run ready while the server is in the lobby or a run is active. It does not start a run; players still use `/start`. |
| `allowLateJoin` | `true` | Admit new players to an active run. When false, players who are not recorded participants wait in the lobby. |
| `sharedPotions` | `false` | Mirror ongoing status effect gains, upgrades, removals, and expirations among active participants. Instant damage and healing are already part of shared vitality. |
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

The command saves `sharedPotions` to `config/soullink.json`. The current run keeps the rule it started with; `/soullink status` reports current and next-run rules separately.

## Debug and failure injection

`/soullink debug fail <point>` is intended for recovery testing. It can simulate activation failures; the `halt-after-*` points forcibly halt the JVM. The points are inert unless the server starts with `-Dterrarasa.faultInjection=true`. Disable `debugCommands` on servers where operators should not have these diagnostics.
