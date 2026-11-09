package dev.soullink;

import java.util.Set;

import dev.soullink.lobby.LobbyService;
import dev.soullink.run.RunCoordinator;
import dev.soullink.setup.SetupMenu;
import dev.soullink.setup.SetupMenuView;
import dev.terrarasa.ResourceIdentifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** Vanilla adapter for 1.21.11. */
public final class MinecraftCompat {
    public record WorldSpawn(ResourceKey<Level> dimension, BlockPos pos, float angle) {
    }

    private MinecraftCompat() {
    }

    public static long globalSeed(MinecraftServer server) {
        return server.getWorldData().worldGenOptions().seed();
    }

    public static ChunkPos chunkPos(BlockPos position) {
        return new ChunkPos(position);
    }

    public static int chunkX(ChunkPos position) {
        return position.x;
    }

    public static int chunkZ(ChunkPos position) {
        return position.z;
    }

    public static Set<String> entityTags(Entity entity) {
        return entity.getTags();
    }

    public static boolean hasOperatorPermission(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    public static boolean naturalRegeneration(MinecraftServer server) {
        return server.overworld().getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
    }

    public static void setNaturalRegeneration(MinecraftServer server, boolean enabled) {
        server.overworld().getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, enabled, server);
    }

    public static void openSetupMenu(ServerPlayer player, RunCoordinator coordinator, SetupMenuView.Page page, int pageOffset) {
        SetupMenu.open(player, coordinator, page, pageOffset);
    }

    public static void closeSetupMenu(ServerPlayer player) {
        player.closeContainer();
    }

    public static int aliveDragonCrystals(Object fight) {
        return ((EndDragonFight) fight).getCrystalsAlive();
    }

    public static int aliveDragonCrystals(ServerLevel level) {
        return aliveDragonCrystals(level.getDragonFight());
    }

    public static void registerTicketTypes() {
        LobbyService.TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(SoulLink.MOD_ID, "lobby"), new TicketType(0L, TicketType.FLAG_LOADING));
    }

    public static void addRegionTicket(ServerLevel level, TicketType type, ChunkPos pos, int radius) {
        level.getChunkSource().addTicketWithRadius(type, pos, radius);
    }

    public static void kill(ServerPlayer player) {
        player.kill(player.level());
    }

    public static ClickEvent runCommandClickEvent(String command) {
        return new ClickEvent.RunCommand(command);
    }

    public static HoverEvent showTextHoverEvent(Component text) {
        return new HoverEvent.ShowText(text);
    }

    public static ResourceKey<Level> dimensionKey(ResourceIdentifier id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(id.namespace(), id.path()));
    }

    public static ResourceIdentifier dimensionId(ResourceKey<Level> key) {
        return ResourceIdentifier.of(key.identifier().getNamespace(), key.identifier().getPath());
    }

    public static String resourceLocationString(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    public static String playerName(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    public static String playerGameModeName(ServerPlayer player) {
        return player.gameMode().getName();
    }

    public static int getMinY(LevelHeightAccessor accessor) {
        return accessor.getMinY();
    }

    public static int getMaxY(LevelHeightAccessor accessor) {
        return accessor.getMaxY();
    }

    public static ServerPlayer teleport(ServerPlayer player, ServerLevel destination, Vec3 position, float yRot, float xRot) {
        ServerPlayer moved = player.teleport(new TeleportTransition(destination, position, Vec3.ZERO, yRot, xRot,
                TeleportTransition.DO_NOTHING));
        return moved != null ? moved : player;
    }

    public static WorldSpawn getInitialSpawn(MinecraftServer server) {
        LevelData.RespawnData respawn = server.getRespawnData();
        if (respawn == null) {
            respawn = server.overworld().getRespawnData();
        }
        return respawn != null ? new WorldSpawn(respawn.dimension(), respawn.pos(), respawn.yaw())
                : new WorldSpawn(Level.OVERWORLD, BlockPos.ZERO, 0.0F);
    }

    public static void setServerRespawn(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos, float angle) {
        server.setRespawnData(LevelData.RespawnData.of(dimension, pos, angle, 0.0F));
    }

    public static <T> Registry<T> lookupOrThrow(RegistryAccess access, ResourceKey<? extends Registry<T>> registryKey) {
        return access.lookupOrThrow(registryKey);
    }
}
