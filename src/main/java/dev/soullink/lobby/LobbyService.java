package dev.soullink.lobby;

import dev.soullink.SoulLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** The persistent {@code soullink:lobby} dimension: a void world with one platform. */
public final class LobbyService {
    public static final Identifier DIMENSION = Identifier.fromNamespaceAndPath(SoulLink.MOD_ID, "lobby");
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, DIMENSION);
    /** Non-persistent: the lobby re-adds it at every startup. */
    public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath(SoulLink.MOD_ID, "lobby"), new TicketType(0L, TicketType.FLAG_LOADING));
    private static final int PLATFORM_Y = 64;
    private static final int PLATFORM_RADIUS = 6;
    public static final Vec3 SPAWN = new Vec3(0.5, PLATFORM_Y + 1, 0.5);

    private LobbyService() {
    }

    public static ServerLevel level(MinecraftServer server) {
        ServerLevel lobby = server.getLevel(KEY);
        if (lobby == null) {
            throw new IllegalStateException(DIMENSION + " is not loaded; is the Soul Link data pack enabled?");
        }
        return lobby;
    }

    /** Keeps the platform chunks loaded and builds the platform once. */
    public static void initialize(MinecraftServer server) {
        ServerLevel lobby = level(server);
        lobby.getChunkSource().addTicketWithRadius(TICKET, new ChunkPos(0, 0), 1);
        if (lobby.getBlockState(new BlockPos(0, PLATFORM_Y, 0)).isAir()) {
            for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
                boolean edgeColumn = Math.abs(x) == PLATFORM_RADIUS;
                for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                    boolean edge = edgeColumn || Math.abs(z) == PLATFORM_RADIUS;
                    lobby.setBlockAndUpdate(new BlockPos(x, PLATFORM_Y, z),
                            (edge ? Blocks.POLISHED_BLACKSTONE : Blocks.SMOOTH_STONE).defaultBlockState());
                }
            }
        }
    }

    public static LevelData.RespawnData respawnData(MinecraftServer server) {
        return LevelData.RespawnData.of(level(server).dimension(), BlockPos.containing(SPAWN), 0.0F, 0.0F);
    }

    public static boolean isIn(ServerPlayer player) {
        return player.level().dimension().equals(KEY);
    }

    public static ServerPlayer teleport(MinecraftServer server, ServerPlayer player) {
        ServerPlayer moved = player.teleport(new TeleportTransition(level(server), SPAWN, Vec3.ZERO, 0.0F, 0.0F,
                TeleportTransition.DO_NOTHING));
        return moved != null ? moved : player;
    }
}
