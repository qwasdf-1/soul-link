package dev.soullink;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.core.registries.Registries;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge entrypoint: builds the platform adapter and registers the shared runtime wiring.
 *
 * <p>Every hook below is the NeoForge analogue of the Fabric API event the same call site uses in
 * {@link SoulLinkInitializer}. All of them fire on the game event bus ({@link NeoForge#EVENT_BUS}),
 * not the mod bus, so they are registered on that bus directly from the {@link Mod} constructor.
 */
@Mod(SoulLink.MOD_ID)
public final class SoulLinkNeoForge {
    /** The server that is running this mod, taken from server start; reload hooks need it. */
    private static @org.jspecify.annotations.Nullable MinecraftServer runningServer;

    public SoulLinkNeoForge(IEventBus modEventBus) {
        // The built-in registries are already frozen when a mod constructor runs, so the lobby
        // ticket type registers on the mod bus's registry event instead: that fires while vanilla
        // still accepts additions to them.
        modEventBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey() == Registries.TICKET_TYPE) {
                SoulLink.registerTicketTypes();
            }
        });
        SoulLinkRuntime.register(new NeoForgePlatform());
    }

    private static final class NeoForgePlatform implements ModPlatform {
        @Override
        public Path configFile(String name) {
            return FMLPaths.CONFIGDIR.get().resolve(name);
        }

        @Override
        public void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
            NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> callback.accept(event.getDispatcher()));
        }

        @Override
        public void onServerStarted(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
                runningServer = event.getServer();
                callback.accept(event.getServer());
            });
        }

        @Override
        public void onServerStopping(Runnable callback) {
            NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> callback.run());
        }

        @Override
        public void onServerStopped(Runnable callback) {
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
                if (runningServer == event.getServer()) {
                    runningServer = null;
                }
                callback.run();
            });
        }

        @Override
        public void onDataPacksReloaded(Consumer<MinecraftServer> callback) {
            // NeoForge has no END_DATA_PACK_RELOAD. TagsUpdatedEvent is posted by
            // ReloadableServerResources.updateComponentsAndStaticRegistryTags(), which runs after the
            // reloadable resources have been committed to the server and the postponed tags have been
            // applied - the same point in the sequence as Fabric's hook. The event also fires during
            // the reload that loads a world, where Soul Link has no run state yet, so the coordinator
            // ignores it there exactly as it does on Fabric. A failed reload never reaches this point,
            // which is Fabric's `success` check.
            //
            // Two subclasses exist: the server data load and a client's packet-driven tag update. A
            // listener registered for the base class receives both, and shouldUpdateStaticData()
            // separates them: it is true for the server data load and false for the packet update of an
            // integrated server's own client. A remote vanilla client that has Soul Link installed sees
            // no coordinator and is therefore inert.
            NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, event -> {
                MinecraftServer server = runningServer;
                if (!event.shouldUpdateStaticData() || server == null) {
                    return;
                }
                callback.accept(server);
            });
        }

        @Override
        public void onEndServerTick(Consumer<MinecraftServer> callback) {
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> callback.accept(event.getServer()));
        }

        @Override
        public void onPlayerJoin(Consumer<ServerPlayer> callback) {
            NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class,
                    event -> callback.accept(player(event.getEntity())));
        }

        @Override
        public void onPlayerLeave(Consumer<ServerPlayer> callback) {
            // PlayerLoggedOutEvent fires at the top of PlayerList.remove, before the player's data is
            // saved; Fabric's LEAVE fires at the end of the same method. Soul Link only forgets the
            // player's vitality pool here, so the earlier instant is harmless.
            NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                    event -> callback.accept(player(event.getEntity())));
        }

        @Override
        public void onPlayerRespawn(Consumer<ServerPlayer> callback) {
            // PlayerEvent.Clone is fired inside ServerPlayer.restoreFrom, before the new player is
            // added to the player list and the level, while Fabric's AFTER_RESPAWN runs after both.
            // PlayerRespawnEvent fires at exactly Fabric's place and carries the new player, so it
            // maps to that hook. Only Clone distinguishes a death from the End portal (isWasDeath),
            // and the Fabric registration handled both paths identically, as this does.
            NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerRespawnEvent.class,
                    event -> callback.accept(player(event.getEntity())));
        }

        @Override
        public void onAllowDamage(BiPredicate<LivingEntity, DamageSource> callback) {
            // LivingDamageEvent is not cancellable on current NeoForge: neither the event nor its
            // Pre variant implements a cancel interface, so there is no setCanceled to mirror
            // returning `false` from Fabric's ALLOW_DAMAGE. LivingIncomingDamageEvent is fired in
            // LivingEntity.hurt after the invulnerability checks and before any damage processing,
            // which is exactly where Fabric injects its hook, and it is cancellable.
            NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, event -> {
                if (!callback.test(event.getEntity(), event.getSource())) {
                    event.setCanceled(true);
                }
            });
        }

        @Override
        public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> callback) {
            // LivingDeathEvent is fired at the top of LivingEntity.die, where Fabric's AFTER_DEATH is
            // injected. Both run before drops, the death message and the respawn screen.
            NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, event -> callback.accept(event.getEntity(), event.getSource()));
        }

        @Override
        public void beforeWorldReplacement() {
            // WTHIT is a Fabric mod, so its server-side data providers have no counterpart here.
            // Jade, NeoForge's equivalent tooltip mod, does not need a matching adapter: its data
            // providers take the hit-result accessor as a per-request argument
            // (FluidStorageProvider.streamData(RequestBlockPacket) rebuilds it from the player's
            // current level), and the caches Jade keeps (ItemStorageProvider.containerCache) are
            // keyed by the target object and re-updated with the new accessor on every use. That is
            // the shape WTHIT deviated from when its singleton fluid provider dereferenced the
            // previous request's level-bound cache before replacing it, so the request-path hazard
            // the WTHIT adapter addresses has no Jade analogue. See docs/reference.md.
        }

        /** The player events carry a vanilla {@link Player}, which is a {@link ServerPlayer} here. */
        private static ServerPlayer player(Player player) {
            return (ServerPlayer) player;
        }
    }
}
