package dev.soullink;

import dev.soullink.command.SoulLinkCommands;
import dev.soullink.lobby.LobbyService;
import dev.soullink.run.RunCoordinator;
import dev.terrarasa.PinnedSeed;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/**
 * Loader-neutral wiring, shared by every loader entrypoint.
 *
 * <p>A loader's entrypoint builds a {@link ModPlatform} and calls {@link #register} once, which loads
 * the configuration, installs the seed policy, registers the lobby ticket type and subscribes every
 * server, tick, player, damage and death hook. Nothing here differs between Fabric and NeoForge; the
 * difference lives entirely in the overlay.
 */
public final class SoulLinkRuntime {
    private SoulLinkRuntime() {
    }

    /** Registers lifecycle hooks and optional integrations. Call exactly once per mod load. */
    public static void register(ModPlatform platform) {
        SoulLink.setPlatform(platform);

        SoulLinkConfig config = SoulLinkConfig.load(platform.configFile(SoulLink.CONFIG_FILE));
        config.normalize();
        config.validate();
        // Dimensions that runs never reset (the lobby, external ones) keep the world's original seed.
        PinnedSeed.setPolicy(config.dimensionPolicy());
        // Ticket types are registered by each loader's entrypoint at the moment the built-in
        // registries still accept additions (see SoulLink.registerTicketTypes), so by now the
        // lobby ticket type is in place and only needs to be observed here.
        SoulLink.LOGGER.debug("Lobby ticket type: {}", LobbyService.TICKET);

        platform.registerCommands(dispatcher -> SoulLinkCommands.register(dispatcher, config.debugCommands));

        platform.onServerStarted(server -> {
            RunCoordinator started = new RunCoordinator(server, config);
            RunCoordinator.setActive(started);
            started.onServerStarted();
        });
        platform.onServerStopping(() -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onServerStopping();
            }
        });
        // The coordinator is created when the server starts, so the reload a server performs while
        // loading its first world has nothing to recheck; only a later `/reload` reaches a run.
        platform.onDataPacksReloaded(server -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onDataPacksReloaded();
            }
        });
        platform.onServerStopped(() -> RunCoordinator.setActive(null));

        // World lifecycle work runs at the end of a server tick: outside level iteration and packet handling.
        platform.onEndServerTick(server -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.tick();
            }
        });

        platform.onPlayerJoin(player -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerJoin(player);
            }
        });
        platform.onPlayerLeave(player -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerLeave(player);
            }
        });
        platform.onPlayerRespawn(player -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerRespawn(player);
            }
        });

        platform.onAllowDamage((entity, source) -> {
            RunCoordinator coordinator = RunCoordinator.active();
            return !(entity instanceof ServerPlayer player && coordinator != null
                    && coordinator.shouldCancelLobbyDamage(player, source));
        });
        platform.onEntityDeath((entity, source) -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator == null) {
                return;
            }
            if (entity instanceof ServerPlayer player) {
                coordinator.onPlayerDeath(player);
            } else if (entity instanceof EnderDragon && entity.level() instanceof ServerLevel level) {
                coordinator.onDragonKilled(level);
            }
        });
    }
}
