# Architecture decisions

| Decision | Current choice | Authority |
|---|---|---|
| World identity | Replace vanilla canonical dimensions through Terra Rasa so vanilla portals, End behavior, credits, and data-pack hooks remain in control. | Root README and Terra Rasa references |
| Player availability | Keep players connected in a persistent lobby during a world swap. | `gameplay.md`, lifecycle source |
| Shared vitality | Enable shared health, food, and saturation by default. Let operators persistently disable it for future runs without changing run preparation, world replacement, or the independent potion and half-heart rules. | Operator direction, 2026-10-27; config and run-state snapshot |
| Death outcomes | Linked runs keep their one-death defeat. With shared vitality off, vanilla normal worlds allow respawn and vanilla hardcore ends on team wipe. A separate persistent Soul Link Hardcore rule makes any participant death end the next run. | Operator direction, 2026-10-27; run-state snapshot and death-rule tests |
| Lobby fall recovery | Use a two-block-high barrier ring just outside the platform, rescue immediately on void damage, and retain a five-second teleport-to-spawn backstop. Lobby damage protection cancels the void hit after the rescue is requested. | Operator decision, 2026-10-27; implemented and covered by integration test |
| Lobby lifetime | Keep the lobby dimension registered and retain its platform ticket. A two-client 26.2 reference-modpack comparison found the same 25 loaded chunks and 1 block-ticking chunk with Soul Link's ticket enabled or disabled, with no measurable tick-time benefit from disabling it. The source of those remaining chunks without the ticket is unknown. | `reference.md`; measurement, 2026-10-27 |
| Next-run construction pacing | Keep following-run dimension construction on Terra Rasa's regular tick path. Use one per tick while players wait in the lobby and a configurable interval (default 20 ticks) while the run world is playable, including after victory. A single constructor remains synchronous and indivisible. | Operator direction, 2026-10-28; `configuration.md`, `reference.md`; vanilla and pinned-modpack 26.2 integration checks |
| Ended-run simulation | Keep victory worlds live and playable, including vanilla End exit portal and credits. Defer defeat freezing while spectators remain in the run: `ServerLevel.tick` drives both player entity ticks and world simulation in 1.21.11 and 26.2, and selectively suppressing the latter needs deeper hooks with cross-version and mod compatibility risk. | Operator direction, 2026-10-27; server bytecode inspection, 2026-10-27 |
| Client requirement | Keep gameplay logic server-authoritative. The host installs Soul Link on dedicated servers or its integrated-server client; LAN guests may use vanilla clients. | Root README and `fabric.mod.json` |
| Compatibility split | Share run/game logic and isolate API/mapping differences in target overlays. | `gradle/minecraft-targets.json`, `src/versions/` |
| Release shape | One GitHub release per mod version, with a target-specific jar and Modrinth version for each supported Minecraft version. | `publishing.md` |

Update this page together with the runtime or distribution contract when one of
these choices changes.
