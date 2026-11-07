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
import net.minecraft.world.damagesource.DamageSource;

/**
 * The loader hooks Soul Link needs, expressed with vanilla types only.
 *
 * <p>Each loader implements this once in its entrypoint overlay
 * ({@code src/loader/<loader>/java}) and hands it to {@link SoulLinkRuntime#register}, so the run
 * coordinator, lobby service, commands, mixins and the shared-vitality rules never reference a loader
 * type. All callbacks run on the server thread, at the point vanilla itself runs them.
 */
public interface ModPlatform {

    /** This mod's config file under the loader's config directory. */
    Path configFile(String name);

    /** The command dispatcher is being built: register Soul Link's commands. */
    void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback);

    /** The server started and every dimension is live: the run state may be adopted. */
    void onServerStarted(Consumer<MinecraftServer> callback);

    /** The server is stopping but levels are still open: flush state and prepared runs. */
    void onServerStopping(Runnable callback);

    /** The server has fully stopped: shared state may be cleared. */
    void onServerStopped(Runnable callback);

    /**
     * Every data pack and tag set has been loaded after a successful reload. This is the point where
     * the fingerprints of prepared runs are rechecked, and where the initial reload happens as well
     * (before the server starts, so no run state exists yet).
     */
    void onDataPacksReloaded(Consumer<MinecraftServer> callback);

    /** End of a server tick: outside level iteration and packet handling. */
    void onEndServerTick(Consumer<MinecraftServer> callback);

    /** A player joined the server. */
    void onPlayerJoin(Consumer<ServerPlayer> callback);

    /** A player left the server. */
    void onPlayerLeave(Consumer<ServerPlayer> callback);

    /** A player respawned, whether from a death or from the End portal. */
    void onPlayerRespawn(Consumer<ServerPlayer> callback);

    /**
     * Whether the named living entity may take this damage. Lobby players are protected here, so a
     * false result prevents the damage exactly as returning {@code false} from Fabric's
     * {@code ALLOW_DAMAGE} callback does.
     */
    void onAllowDamage(BiPredicate<LivingEntity, DamageSource> callback);

    /** A living entity died. Players are handled as run participants, dragons as a victory. */
    void onEntityDeath(BiConsumer<LivingEntity, DamageSource> callback);

    /** Optional integrations that must run while the pre-swap levels are still live. */
    void beforeWorldReplacement();
}
