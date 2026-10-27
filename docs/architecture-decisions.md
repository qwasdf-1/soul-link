# Architecture decisions

| Decision | Current choice | Authority |
|---|---|---|
| World identity | Replace vanilla canonical dimensions through Terra Rasa so vanilla portals, End behavior, credits, and data-pack hooks remain in control. | Root README and Terra Rasa references |
| Player availability | Keep players connected in a persistent lobby during a world swap. | `gameplay.md`, lifecycle source |
| Lobby fall recovery | Use a two-block-high barrier ring just outside the platform, rescue immediately on void damage, and retain a five-second teleport-to-spawn backstop. Lobby damage protection cancels the void hit after the rescue is requested. | Operator decision, 2026-10-27; implemented and covered by integration test |
| Lobby lifetime | Keep the lobby dimension registered and retain its platform ticket. A two-client 26.2 reference-modpack comparison found the same 25 loaded chunks and 1 block-ticking chunk with Soul Link's ticket enabled or disabled, with no measurable tick-time benefit from disabling it. The source of those remaining chunks without the ticket is unknown. | `reference.md`; measurement, 2026-10-27 |
| Ended-run simulation | Keep victory worlds live and playable, including vanilla End exit portal and credits. Defer defeat freezing while spectators remain in the run: `ServerLevel.tick` drives both player entity ticks and world simulation in 1.21.11 and 26.2, and selectively suppressing the latter needs deeper hooks with cross-version and mod compatibility risk. | Operator direction, 2026-10-27; server bytecode inspection, 2026-10-27 |
| Client requirement | Keep gameplay logic server-authoritative. The host installs Soul Link on dedicated servers or its integrated-server client; LAN guests may use vanilla clients. | Root README and `fabric.mod.json` |
| Compatibility split | Share run/game logic and isolate API/mapping differences in target overlays. | `gradle/minecraft-targets.json`, `src/versions/` |
| Release shape | One GitHub release per mod version, with a target-specific jar and Modrinth version for each supported Minecraft version. | `publishing.md` |

Update this page together with the runtime or distribution contract when one of
these choices changes.
