package dev.soullink;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

/** Small adapter for vanilla APIs that changed between the supported obfuscated and unobfuscated targets. */
public final class MinecraftCompat {
    private MinecraftCompat() {
    }

    public static long globalSeed(MinecraftServer server) {
        return server.getWorldGenSettings().options().seed();
    }

    public static ChunkPos chunkPos(BlockPos position) {
        return ChunkPos.containing(position);
    }

    public static int chunkX(ChunkPos position) {
        return position.x();
    }

    public static int chunkZ(ChunkPos position) {
        return position.z();
    }

    public static Set<String> entityTags(Entity entity) {
        return entity.entityTags();
    }

    public static int aliveDragonCrystals(Object fight) {
        return ((EnderDragonFight) fight).aliveCrystals();
    }

    public static int aliveDragonCrystals(ServerLevel level) {
        return aliveDragonCrystals(level.getDragonFight());
    }
}
