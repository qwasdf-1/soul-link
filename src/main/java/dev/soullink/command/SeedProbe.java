package dev.soullink.command;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.CRC32;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seed-coherence probe: terrain heights, biomes, structure starts and a block-state hash
 * around a point of one level. Two runs prepared with the same seed must agree on the seed-determined
 * layers whether their chunks were generated during staging or after activation.
 */
final class SeedProbe {
    /** Block offsets from the probe center: the center and the preloaded ring. */
    private static final int[][] NEAR_OFFSETS = {{0, 0}, {40, 0}, {0, -40}, {-56, 56}, {24, 24}, {-30, -10}};
    /** Far, ungenerated land. Generating it blocks the server thread for a while on heavy modpacks. */
    private static final int[][] FAR_OFFSETS = {{3000, 0}, {0, -3000}, {-4096, 4096}};
    private static final int BIOME_SAMPLE_Y = 64;
    private static final int STRUCTURE_SCAN_RADIUS = 4;

    private SeedProbe() {
    }

    static JsonObject probe(MinecraftServer server, ServerLevel level, BlockPos center, boolean far) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", level.dimension().identifier().toString());
        json.addProperty("globalSeed", server.getWorldGenSettings().options().seed());
        json.addProperty("levelSeed", level.getSeed());
        json.addProperty("overworldSeed", server.overworld().getSeed());
        json.addProperty("netherSeed", server.getLevel(Level.NETHER).getSeed());
        json.addProperty("endSeed", server.getLevel(Level.END).getSeed());
        json.add("samples", samples(level, center, far));
        json.add("structureStarts", structureStarts(level, center));
        json.addProperty("spawnChunkHash", chunkHash(level, ChunkPos.containing(center)));
        return json;
    }

    private static JsonArray samples(ServerLevel level, BlockPos center, boolean far) {
        var chunkSource = level.getChunkSource();
        JsonArray samples = new JsonArray();
        List<int[]> offsets = new ArrayList<>(List.of(NEAR_OFFSETS));
        if (far) {
            offsets.addAll(List.of(FAR_OFFSETS));
        }
        for (int[] offset : offsets) {
            int x = center.getX() + offset[0];
            int z = center.getZ() + offset[1];
            level.getChunk(x >> 4, z >> 4);
            JsonObject sample = new JsonObject();
            sample.addProperty("x", x);
            sample.addProperty("z", z);
            // Noise terrain: a pure function of seed and generator, independent of generation order.
            sample.addProperty("noiseHeight", chunkSource.getGenerator().getBaseHeight(x, z,
                    Heightmap.Types.OCEAN_FLOOR_WG, level, chunkSource.randomState()));
            sample.addProperty("biome", level.getBiome(new BlockPos(x, BIOME_SAMPLE_Y, z)).unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("?"));
            // Includes decoration (trees, lakes), which vanilla places order-dependently across chunk borders.
            sample.addProperty("surfaceHeight", level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
            samples.add(sample);
        }
        return samples;
    }

    private static JsonArray structureStarts(ServerLevel level, BlockPos center) {
        var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        TreeSet<String> starts = new TreeSet<>();
        ChunkPos origin = ChunkPos.containing(center);
        for (int cx = origin.x() - STRUCTURE_SCAN_RADIUS; cx <= origin.x() + STRUCTURE_SCAN_RADIUS; cx++) {
            for (int cz = origin.z() - STRUCTURE_SCAN_RADIUS; cz <= origin.z() + STRUCTURE_SCAN_RADIUS; cz++) {
                // Structure starts exist from this status on; full generation of 81 chunks can take
                // longer than the watchdog allows in heavy modded dimensions.
                level.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS).getAllStarts().forEach((structure, start) -> {
                    if (start.isValid()) {
                        var box = start.getBoundingBox();
                        starts.add(structures.getKey(structure) + "@" + box.minX() + "," + box.minY() + "," + box.minZ());
                    }
                });
            }
        }
        JsonArray array = new JsonArray();
        starts.forEach(array::add);
        return array;
    }

    private static String chunkHash(ServerLevel level, ChunkPos position) {
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        CRC32 crc = new CRC32();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = level.getMinY(); y < level.getMaxY(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int id = Block.getId(chunk.getBlockState(cursor.set(position.getMinBlockX() + x, y, position.getMinBlockZ() + z)));
                    crc.update(id);
                    crc.update(id >>> 8);
                }
            }
        }
        return Long.toHexString(crc.getValue());
    }
}
