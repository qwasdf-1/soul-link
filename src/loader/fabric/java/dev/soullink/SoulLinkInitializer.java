package dev.soullink;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import dev.soullink.compat.WthitCompatibility;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;

/** Fabric entrypoint: builds the platform adapter and registers the shared runtime wiring. */
public final class SoulLinkInitializer implements ModInitializer {

    @Override
    public void onInitialize() {
        // A Fabric entrypoint runs while the built-in registries still accept additions, which is
        // the moment the lobby ticket type must be registered; NeoForge does this from its
        // registry event.
        SoulLink.registerTicketTypes();
        SoulLinkRuntime.register(new FabricPlatform());
    }

    private record FabricPlatform() implements ModPlatform {
        @Override
        public Path configFile(String name) {
            return FabricLoader.getInstance().getConfigDir().resolve(name);
        }

        @Override
        public void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    callback.accept(dispatcher));
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            ServerLifecycleEvents.SERVER_STARTED.register(callback::accept);
        }

        @Override
        public void onServerStopping(Runnable callback) {
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> callback.run());
        }

        @Override
        public void onServerStopped(Runnable callback) {
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> callback.run());
        }

        @Override
        public void onDataPacksReloaded(Consumer<MinecraftServer> callback) {
            // Only a successful reload reaches the coordinator; a failed one posts nothing.
            ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
                if (success) {
                    callback.accept(server);
                }
            });
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            ServerTickEvents.END_SERVER_TICK.register(callback::accept);
        }

        @Override
        public void onPlayerJoin(Consumer<ServerPlayer> callback) {
            ServerPlayerEvents.JOIN.register(callback::accept);
        }

        @Override
        public void onPlayerLeave(Consumer<ServerPlayer> callback) {
            ServerPlayerEvents.LEAVE.register(callback::accept);
        }

        @Override
        public void onPlayerRespawn(Consumer<ServerPlayer> callback) {
            ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> callback.accept(newPlayer));
        }

        @Override
        public void onAllowDamage(BiPredicate<LivingEntity, DamageSource> callback) {
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> callback.test(entity, source));
        }

        @Override
        public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> callback) {
            ServerLivingEntityEvents.AFTER_DEATH.register(callback::accept);
        }

        @Override
        public void beforeWorldReplacement() {
            // WTHIT is a Fabric mod: its server-side data providers are reached reflectively.
            WthitCompatibility.beforeWorldReplacement();
        }
    }
}
