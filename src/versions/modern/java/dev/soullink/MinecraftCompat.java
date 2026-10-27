package dev.soullink;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.gamerules.GameRules;

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

    public static boolean hasOperatorPermission(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean naturalRegeneration(MinecraftServer server) {
        return server.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
    }

    public static void setNaturalRegeneration(MinecraftServer server, boolean enabled) {
        server.getGameRules().set(GameRules.NATURAL_HEALTH_REGENERATION, enabled, server);
    }

    public static int aliveDragonCrystals(Object fight) {
        return ((EnderDragonFight) fight).aliveCrystals();
    }

    public static int aliveDragonCrystals(ServerLevel level) {
        return aliveDragonCrystals(level.getDragonFight());
    }
}
