package dev.soullink;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.dimension.end.EndDragonFight;

/** Small adapter for vanilla APIs that changed between the supported obfuscated and unobfuscated targets. */
public final class MinecraftCompat {
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
        return player.hasPermissions(2);
    }

    public static boolean naturalRegeneration(MinecraftServer server) {
        return server.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
    }

    public static void setNaturalRegeneration(MinecraftServer server, boolean enabled) {
        server.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(enabled, server);
    }

    public static int aliveDragonCrystals(Object fight) {
        return ((EndDragonFight) fight).getCrystalsAlive();
    }

    public static int aliveDragonCrystals(ServerLevel level) {
        return aliveDragonCrystals(level.getDragonFight());
    }
}
