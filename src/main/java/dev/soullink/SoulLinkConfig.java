package dev.soullink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonParseException;
import dev.soullink.lobby.LobbyService;
import dev.terrarasa.AtomicFiles;
import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.Json;
import net.minecraft.resources.Identifier;

/** Operator configuration, stored as {@code config/soullink.json}. Missing fields keep their defaults. */
public final class SoulLinkConfig {
    /** 4096 blocks: about 200,000 chunks, hours of generation. */
    private static final int MAX_PREGENERATION_BLOCKS = 4096;

    /** Dimensions replaced on every run, in construction order (the Overworld is always first). */
    public List<String> resettableDimensions = new ArrayList<>(List.of(
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
    /** Dimensions never touched by run activation. Must include the lobby. */
    public List<String> persistentDimensions = new ArrayList<>(List.of("soullink:lobby"));
    /** Chunk radius around the prepared spawn generated ahead of activation. */
    public int preloadRadius = 6;
    /**
     * Blocks to pregenerate per run dimension, around the run spawn (scaled 1:8 in the Nether) or the End's
     * main island. Generated in the background once the next run could start; a start never waits for it,
     * and what is left continues in the live world. 0 or a missing dimension: none.
     */
    public Map<String, Integer> pregeneration = defaultPregeneration();
    public int preparationTimeoutSeconds = 900;
    public int drainTimeoutSeconds = 60;
    /** Number of closed future runs to keep prepared; the currently active run is not counted. */
    public int preparedRunPoolSize = 1;
    /** Ticks between staged dimension constructions while a run world is active; lobby preparation uses one tick. */
    public int activeRunConstructionIntervalTicks = 20;
    /** Keep the configured number of prepared runs ready in the background whenever possible. */
    public boolean autoPrepare = true;
    /** Late joiners are synced into a running run; otherwise they wait in the lobby. */
    public boolean allowLateJoin = true;
    /** Shared health, food, and saturation; captured when a run starts. */
    public boolean sharedVitality = true;
    /** When enabled for a run, any participant death ends it even with shared vitality disabled. */
    public boolean soulLinkHardcore;
    /** Shared potions: effects on one participant apply to every participant. Applies from the next run. */
    public boolean sharedPotions = false;
    /** Reduces participants to one health point (half a heart), applying from the next run. */
    public boolean halfHeart = false;
    /** Enables {@code /soullink debug ...}; still requires operator permission. */
    public boolean debugCommands = true;

    public static SoulLinkConfig load(Path file) {
        SoulLinkConfig config = new SoulLinkConfig();
        try {
            if (Files.isRegularFile(file)) {
                SoulLinkConfig loaded = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SoulLinkConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
            AtomicFiles.writeString(file, Json.GSON.toJson(config));
        } catch (IOException | JsonParseException e) {
            SoulLink.LOGGER.error("Could not read {}; using defaults", file, e);
        }
        return config;
    }

    public void save(Path file) throws IOException {
        AtomicFiles.writeString(file, Json.GSON.toJson(this));
    }

    public DimensionPolicy dimensionPolicy() {
        return new DimensionPolicy(parse(this.resettableDimensions), Set.copyOf(parse(this.persistentDimensions)));
    }

    private static Map<String, Integer> defaultPregeneration() {
        Map<String, Integer> radii = new LinkedHashMap<>();
        radii.put("minecraft:overworld", 500);
        // The Overworld area scaled 1:8, plus a margin for where the first portal gets built.
        radii.put("minecraft:the_nether", 96);
        // The main island, its pillars and the gateway ring at 96 blocks.
        radii.put("minecraft:the_end", 160);
        return radii;
    }

    /** Pregeneration radii, keyed by dimension. */
    public Map<Identifier, Integer> pregenerationRadii() {
        Map<Identifier, Integer> radii = new LinkedHashMap<>();
        this.pregeneration.forEach((dimension, radius) -> radii.put(Identifier.parse(dimension), radius));
        return radii;
    }

    private static List<Identifier> parse(List<String> ids) {
        return ids.stream().map(Identifier::parse).collect(Collectors.toList());
    }

    public void validate() {
        if (this.preloadRadius < 0 || this.preloadRadius > 32) {
            throw new IllegalArgumentException("preloadRadius must be between 0 and 32");
        }
        if (!this.persistentDimensions.contains(LobbyService.DIMENSION.toString())) {
            throw new IllegalArgumentException(LobbyService.DIMENSION + " must be listed in persistentDimensions");
        }
        for (Map.Entry<String, Integer> area : this.pregeneration.entrySet()) {
            if (area.getValue() == null || area.getValue() < 0 || area.getValue() > MAX_PREGENERATION_BLOCKS) {
                throw new IllegalArgumentException("pregeneration radius for " + area.getKey() + " must be between 0 and "
                        + MAX_PREGENERATION_BLOCKS);
            }
            if (!this.resettableDimensions.contains(area.getKey())) {
                throw new IllegalArgumentException("pregeneration lists " + area.getKey() + ", which is not a resettable dimension");
            }
        }
        if (this.preparationTimeoutSeconds < 10 || this.drainTimeoutSeconds < 5) {
            throw new IllegalArgumentException("lifecycle timeouts are too small");
        }
        if (this.preparedRunPoolSize < 1 || this.preparedRunPoolSize > dev.terrarasa.WorldPreparationService.MAX_READY_RUNS) {
            throw new IllegalArgumentException("preparedRunPoolSize must be between 1 and "
                    + dev.terrarasa.WorldPreparationService.MAX_READY_RUNS);
        }
        if (this.activeRunConstructionIntervalTicks < 1 || this.activeRunConstructionIntervalTicks > 1200) {
            throw new IllegalArgumentException("activeRunConstructionIntervalTicks must be between 1 and 1200");
        }
    }

    public static SoulLinkConfig defaults() {
        return new SoulLinkConfig();
    }

    /** Never null even if a JSON file listed {@code null}. */
    public void normalize() {
        if (this.resettableDimensions == null) {
            this.resettableDimensions = defaults().resettableDimensions;
        }
        if (this.persistentDimensions == null) {
            this.persistentDimensions = defaults().persistentDimensions;
        }
        if (this.pregeneration == null) {
            this.pregeneration = defaults().pregeneration;
        }
    }
}
