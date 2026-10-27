package dev.soullink;

import dev.soullink.command.SoulLinkCommands;
import dev.soullink.lobby.LobbyService;
import dev.soullink.run.RunCoordinator;
import dev.terrarasa.PinnedSeed;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/** Fabric entrypoint: wires Soul Link into server, tick, player and death events. */
public final class SoulLinkInitializer implements ModInitializer {

    @Override
    public void onInitialize() {
        SoulLinkConfig config = SoulLinkConfig.load(SoulLink.configPath());
        config.normalize();
        config.validate();
        // Dimensions that runs never reset (the lobby, external ones) keep the world's original seed.
        PinnedSeed.setPolicy(config.dimensionPolicy());
        // Registers the lobby ticket type while built-in registries are still open.
        SoulLink.LOGGER.debug("Lobby ticket type: {}", LobbyService.TICKET);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SoulLinkCommands.register(dispatcher, config.debugCommands));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RunCoordinator started = new RunCoordinator(server, config);
            RunCoordinator.setActive(started);
            started.onServerStarted();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onServerStopping();
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (success && coordinator != null) {
                coordinator.onDataPacksReloaded();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            RunCoordinator.setActive(null);
        });

        // World lifecycle work runs at the end of a server tick: outside level iteration and packet handling.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.tick();
            }
        });

        ServerPlayerEvents.JOIN.register(player -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerJoin(player);
            }
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerLeave(player);
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            RunCoordinator coordinator = RunCoordinator.active();
            if (coordinator != null) {
                coordinator.onPlayerRespawn(newPlayer);
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            RunCoordinator coordinator = RunCoordinator.active();
            return !(entity instanceof ServerPlayer player && coordinator != null
                    && coordinator.shouldCancelLobbyDamage(player, source));
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
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
