# NeoForge port: decision and plan

| Decision | Detail | Authority |
|---|---|---|
| Approach | Direct port with loader overlays. No cross-loader abstraction library. | Terra Rasa ADR, `docs/design/neoforge-port.md` in the `terra-rasa` checkout |
| Status | Build and entrypoint phases done 2026-11-06; integration verification on a NeoForge server is outstanding. | This document |
| Dependency | The Terra Rasa library is ported first; Soul Link's build substitutes `dev.terrarasa:terra-rasa-mc<version>[-neoforge]` with the local checkout. | Terra Rasa ADR, phase 7 |
| Scope | Every Minecraft target in `gradle/minecraft-targets.json`, both loaders. 26.1 and 26.1.1 have no stable NeoForge build and stay Fabric-only. | Operator direction; NeoForge releases |

## Decision

Port Soul Link to NeoForge directly. The shared decision (why no abstraction
library, and why the loader overlay pattern), the measurement criteria, and the
build/CI/publishing changes are recorded once, in Terra Rasa's ADR; this record
covers only Soul Link's own surface and differences.

Soul Link depends on Terra Rasa through plain `dev.terrarasa` classes
(`RunCoordinator`, `WorldPreparationService`, `PinnedSeed`, `Json`, …). None of
them use Fabric types, so the port does not touch the run/game logic, the lobby,
the commands, the mixin set, or the lobby dimension data pack. Terra Rasa's ADR
also removes the only Fabric-typed API (`CanonicalLevelEvents`), which Soul Link
does not consume.

## Measured port surface

Five files out of ~17 classes and two mixins:

| File | Coupling | NeoForge replacement |
|---|---|---|
| `src/main/resources/fabric.mod.json` | Fabric metadata schema; depends on `terrarasa` | `META-INF/neoforge.mods.toml` with `[[dependencies.soullink]]` for `neoforge`, `minecraft`, and `terrarasa` |
| `src/main/java/dev/soullink/SoulLinkInitializer.java` | Fabric command/player/entity/lifecycle/tick events | NeoForge equivalents (table below), behind `ModPlatform` |
| `src/main/java/dev/soullink/SoulLink.java` | `FabricLoader.getInstance().getConfigDir()` | `FMLPaths.CONFIGDIR` through `ModPlatform.configFile` |
| `src/main/java/dev/soullink/compat/WthitCompatibility.java` | Fabric-only WTHIT internals | Moved to the Fabric overlay; a NeoForge no-op behind `ModPlatform.beforeWorldReplacement` |
| `src/loader/*` (new) | Nothing | One entrypoint and one metadata file per loader |

Portable as-is: all mixins (vanilla `net.minecraft.*` targets), every command, the
lobby service and run coordinator logic, `SoulLinkConfig`, state writers, the setup
menus (vanilla chest menus), and `src/main/resources/data/soullink/dimension/lobby.json`
(data-pack resource, loader-agnostic). There are no custom payloads or channels.

## Structure added by the port

- `src/main/java/dev/soullink/ModPlatform.java` — the loader hooks Soul Link needs,
  typed with vanilla Minecraft and JDK types only (`configFile`, `registerCommands`,
  `onServerStarted`, `onServerStopping`, `onServerStopped`, `onDataPacksReloaded`,
  `onEndServerTick`, `onPlayerJoin`, `onPlayerLeave`, `onPlayerRespawn`,
  `onAllowDamage`, `onEntityDeath`, `beforeWorldReplacement`).
- `src/main/java/dev/soullink/SoulLinkRuntime.java` — `register(ModPlatform)` holds
  every line the old entrypoint executed: the config load and validation, the
  `PinnedSeed.setPolicy` call, the `LobbyService.TICKET` touch that registers the
  ticket type, the command registration, and all lifecycle, player, damage and death
  subscriptions. `src/main` therefore compiles for both loaders unchanged.
- `SoulLink` records the platform (`SoulLink.platform()`), so `SoulLink.configPath()`
  and `RunCoordinator`'s world-replacement hook keep working for either loader.
- `src/loader/fabric/java/dev/soullink/SoulLinkInitializer.java` — the previous
  entrypoint, unchanged in name and behavior, now a thin `ModPlatform` adapter.
- `src/loader/neoforge/java/dev/soullink/SoulLinkNeoForge.java` — the `@Mod`
  class and its adapter.

## Hook mapping (implemented)

| Fabric call site | NeoForge | Notes |
|---|---|---|
| entrypoint `main` (`ModInitializer.onInitialize`) | `@Mod("soullink")` constructor | server-side work stays in the constructor |
| `CommandRegistrationCallback.EVENT` | `RegisterCommandsEvent` (`getDispatcher()`) | identical dispatcher; no registry access is used |
| `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent` | |
| `ServerLifecycleEvents.SERVER_STOPPING` | `ServerStoppingEvent` | |
| `ServerLifecycleEvents.SERVER_STOPPED` | `ServerStoppedEvent` | |
| `ServerLifecycleEvents.END_DATA_PACK_RELOAD` | `TagsUpdatedEvent`, filtered by `shouldUpdateStaticData()` | see the reload section below |
| `ServerTickEvents.END_SERVER_TICK` | `ServerTickEvent.Post` | |
| `ServerPlayerEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` | both fire at the end of `PlayerList.placeNewPlayer` |
| `ServerPlayerEvents.LEAVE` | `PlayerEvent.PlayerLoggedOutEvent` | fires at the top of `PlayerList.remove`, before the player's data is saved; Soul Link only forgets the vitality pool |
| `ServerPlayerEvents.AFTER_RESPAWN` | `PlayerEvent.PlayerRespawnEvent` | `PlayerEvent.Clone` fires too early; see the respawn section |
| `ServerLivingEntityEvents.ALLOW_DAMAGE` | `LivingIncomingDamageEvent`, `setCanceled(true)` | `LivingDamageEvent` is not cancellable; see the damage section |
| `ServerLivingEntityEvents.AFTER_DEATH` | `LivingDeathEvent` | both fire at the top of `LivingEntity.die`, before drops and the respawn screen |
| `FabricLoader.getConfigDir()` | `FMLPaths.CONFIGDIR` | |
| `FabricLoader.isModLoaded("wthit")` | unused on NeoForge | WTHIT is Fabric-only; the hook is a documented no-op |

Every hook above fires on the game event bus (`NeoForge.EVENT_BUS`), so the adapter
registers on that bus directly; no registry event is used.

### Data pack reload

NeoForge has no `END_DATA_PACK_RELOAD`. `AddServerReloadListenersEvent` can inject a
`PreparableReloadListener`, but a hand-written listener would have to implement
`PreparableReloadListener.reload` and its signature is not stable across the covered
Minecraft versions, so the loader overlay could not compile for one source tree.

The closest correct hook is `TagsUpdatedEvent`. NeoForge posts
`TagsUpdatedEvent.ServerDataLoad` from
`ReloadableServerResources.updateComponentsAndStaticRegistryTags()`, which
`MinecraftServer.reloadResources` calls after the new resources are committed to the
server and the postponed tags are applied — the same point in the sequence as
Fabric's hook, and the same point a failed reload never reaches (Fabric's `success`
check). A listener registered for the base class receives both the server-data and
client-packet subclasses; `shouldUpdateStaticData()` separates them (true for the
server data load, false for an integrated server's own client packet). A remote
vanilla client with Soul Link installed sees no coordinator and is inert. The event
also fires while a world's first reload completes, where no coordinator exists yet,
which matches Fabric's behavior.

`AddReloadListenerEvent` does not exist; the events are `AddServerReloadListenersEvent`
(server) and `AddClientReloadListenersEvent` (client). Both are used for adding
listeners, not for completion notification.

### Respawn

`PlayerEvent.Clone` is fired inside `ServerPlayer.restoreFrom`, before the new player
is added to the player list and the level, while Fabric's `AFTER_RESPAWN` runs after
both (`PlayerList.respawn` adds the player, calls `ServerLevel.addRespawnedPlayer`,
and then Fabric's callback). Soul Link's respawn handler moves the player
(eliminated spectator, half-heart reapplication, or the lobby), so the earlier
`Clone` instant would teleport a player that vanilla has not registered yet.
`PlayerEvent.PlayerRespawnEvent` fires exactly where Fabric's hook does, which was
confirmed against the patched 26.2 server classes, so it is the mapping used.
`Clone.isWasDeath()` is the death-vs-End-portal distinction; `PlayerRespawnEvent`
covers both paths, as the Fabric registration did.

### Damage

`LivingDamageEvent` is not cancellable on current NeoForge (26.1.2, 26.2, 26.3 and
21.11.48 all post a plain `LivingDamageEvent` whose `Pre`/`Post` subclasses only
carry a `DamageContainer`), so the planned `event.setCanceled(true)` does not
compile. `LivingIncomingDamageEvent` is fired in `LivingEntity.hurt` after the
invulnerability checks and before any damage processing — where Fabric's
`ALLOW_DAMAGE` is injected — and it is cancellable. The adapter registers a
listener that cancels the event when the coordinator says the lobby damage must not
apply, which is the documented replacement for returning `false` from the Fabric
callback.

## Semantic items to prove on NeoForge

Each maps to existing Fabric integration coverage that must be re-run on the new
loader:

- **Lobby damage protection.** The void-rescue ordering tests must hold under
  `LivingIncomingDamageEvent`.
- **Death outcomes.** One-death defeat, shared-vitality rules, dragon-kill detection,
  and spectator/defeat freezing depend on where death fires relative to
  finalization.
- **Respawn handling.** Shared-effects transfer must not double-apply on the
  `PlayerRespawnEvent` path.
- **Datapack reload behavior.** Config and dimension-policy reload completion,
  including the `/reload` path with an active run.
- **WTHIT workaround parity.** NeoForge's equivalent is Jade; without an adapter,
  the C2ME/WTHIT fluid-cache workaround stays Fabric-only, and that limitation is
  stated in `docs/reference.md`.

## Build, tests, and packaging specifics

- `settings.gradle` adds the NeoForged Maven repository to `pluginManagement` and
  makes the Terra Rasa substitution loader-aware: `-Ploader=neoforge` resolves
  `dev.terrarasa:terra-rasa-mc<version>-neoforge`. The property is passed through
  to the included build, so the substituted project builds the matching artifact.
- `build.gradle` applies `net.neoforged.moddev` when `-Ploader=neoforge` and Loom
  otherwise, exactly as Terra Rasa's build does. `remapJar` is replaced by `jar`
  for the NeoForge build, and the NeoForge test server needs Terra Rasa installed
  as its own mod, so `prepareIntegrationServer` copies it next to Soul Link's jar.
- `gradle/mod-conventions.gradle` appends `src/loader/<loader>/java` and
  `src/loader/<loader>/resources` to the main source set next to the version
  overlays, sets the `-neoforge` archive-name tag, sets the MDG
  `neoForge { version }` extension value, adds the
  `modDevCompileDependencies` classpath extension to the test configurations, and
  expands `META-INF/neoforge.mods.toml` during `processResources` with the same
  value set as `fabric.mod.json`.
- The Fabric path is unchanged: `modImplementation` vs `implementation`, Loom's
  `include` of Terra Rasa, the same `fabric.mod.json` expansion keys, the same jar
  license rename, and the same test task settings.
- Unit tests stay loader-neutral. Nothing in the suite bootstraps Minecraft, so the
  suite passes on both loaders; under NeoForge, `SharedConstants` and
  `Bootstrap.bootStrap` would need an active loader and `unitTest { enable() }`
  cannot be used because its NeoForm runtime tasks demand a JDK this machine does
  not have.
- The reference-modpack suite (`modpackTest`, pinned Modrinth SHA-512 lock) stays
  Fabric-only unless the pack gains NeoForge equivalents. WTHIT itself is
  Fabric-only; Jade would need its own pinned entry.
- Note for CI and release workflows: the matrix in
  `gradle/minecraft-targets.json` now contains one row per (Minecraft version ×
  loader). Builders must pass the loader's own toolchain properties per row
  (`-Ploader=neoforge -Pmoddev_version=… -Pneoforge_version=…` versus
  `-Ploader=fabric -Ploom_version=… -Pfabric_loader_version=… -Pfabric_api_version=…`)
  and only use `remapJar` on Fabric's 1.21.11 target.

## Phases

1. **Build skeleton and metadata.** Done. Conditional plugin application, loader
   source sets, both metadata files, and the loader-aware Terra Rasa substitution;
   `jar` produces `soullink-mc26.2-neoforge-0.4.3.jar`.
2. **Entrypoint port.** Done. `ModPlatform` + `SoulLinkRuntime` in `src/main`, the
   Fabric adapter in the previous entrypoint file, and the NeoForge adapter with
   every hook mapped above. The loader overlay was compiled against NeoForge
   21.11.48 and the legacy 1.21.11 source set as well, so the shared overlay is
   source-compatible across the covered NeoForge builds.
3. **Integration verification.** Outstanding: run the existing Fabric suites on
   NeoForge for 26.2 and fix any semantics surfaced above. The integration server
   assembler already supports NeoForge (install script, `libraries/`, `mods/`).
4. **Matrix, CI, and releases.** Outstanding: the workflow steps, Modrinth version
   loaders/dependencies, and CurseForge game versions still pass one loader's
   properties for every row.
5. **Docs and public wording.** Done: README, `docs/project-reference.md`,
   `docs/publishing.md`, `docs/reference.md`, and `docs/architecture-decisions.md`
   describe both loaders.

## Acceptance criteria

Shared with Terra Rasa's ADR, plus: Soul Link's Fabric-only integration tests pass
unchanged on NeoForge (same-as-Fabric behavior, not merely green runs), and the
published NeoForge jar declares `terrarasa`, `neoforge`, and `minecraft` only.
