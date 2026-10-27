# Architecture decisions

| Decision | Current choice | Authority |
|---|---|---|
| World identity | Replace vanilla canonical dimensions through Terra Rasa so vanilla portals, End behavior, credits, and data-pack hooks remain in control. | Root README and Terra Rasa references |
| Player availability | Keep players connected in a persistent lobby during a world swap. | `gameplay.md`, lifecycle source |
| Client requirement | Keep gameplay logic server-authoritative. The host installs Soul Link on dedicated servers or its integrated-server client; LAN guests may use vanilla clients. | Root README and `fabric.mod.json` |
| Compatibility split | Share run/game logic and isolate API/mapping differences in target overlays. | `gradle/minecraft-targets.json`, `src/versions/` |
| Release shape | One GitHub release per mod version, with a target-specific jar and Modrinth version for each supported Minecraft version. | `publishing.md` |

Update this page together with the runtime or distribution contract when one of
these choices changes.
