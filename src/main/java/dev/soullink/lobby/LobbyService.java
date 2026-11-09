package dev.soullink.lobby;

import dev.soullink.MinecraftCompat;
import dev.soullink.SoulLink;
import dev.terrarasa.ResourceIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The persistent {@code soullink:lobby} dimension: a void world with one platform. */
public final class LobbyService {
    public static final ResourceIdentifier DIMENSION = ResourceIdentifier.of(SoulLink.MOD_ID, "lobby");
    public static final ResourceKey<Level> KEY = MinecraftCompat.dimensionKey(DIMENSION);
    /**
     * Non-persistent: the lobby re-adds it at every startup. Registered by
     * {@link SoulLink#registerTicketTypes()} while the built-in registries still accept additions.
     */
    public static TicketType TICKET;
    private static final int PLATFORM_Y = 64;
    private static final int PLATFORM_RADIUS = 6;
    private static final int BARRIER_RADIUS = PLATFORM_RADIUS + 1;
    private static final int BARRIER_HEIGHT = 2;
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
        MinecraftCompat.addRegionTicket(lobby, TICKET, new ChunkPos(0, 0), 1);
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
        buildBarrierPerimeter(lobby);
    }

    /** Two blocks high, just beyond the platform edge, to catch routine walks off the platform. */
    private static void buildBarrierPerimeter(ServerLevel lobby) {
        for (int y = PLATFORM_Y + 1; y <= PLATFORM_Y + BARRIER_HEIGHT; y++) {
            for (int offset = -BARRIER_RADIUS; offset <= BARRIER_RADIUS; offset++) {
                placeBarrier(lobby, -BARRIER_RADIUS, y, offset);
                placeBarrier(lobby, BARRIER_RADIUS, y, offset);
                if (offset != -BARRIER_RADIUS && offset != BARRIER_RADIUS) {
                    placeBarrier(lobby, offset, y, -BARRIER_RADIUS);
                    placeBarrier(lobby, offset, y, BARRIER_RADIUS);
                }
            }
        }
    }

    private static void placeBarrier(ServerLevel lobby, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!lobby.getBlockState(pos).is(Blocks.BARRIER)) {
            lobby.setBlockAndUpdate(pos, Blocks.BARRIER.defaultBlockState());
        }
    }

    public static BlockPos spawnBlockPos() {
        return BlockPos.containing(SPAWN);
    }

    public static boolean isIn(ServerPlayer player) {
        return player.level().dimension().equals(KEY);
    }

    public static ServerPlayer teleport(MinecraftServer server, ServerPlayer player) {
        return MinecraftCompat.teleport(player, level(server), SPAWN, 0.0F, 0.0F);
    }
}
