package dev.soullink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.soullink.lobby.LobbyService;
import dev.terrarasa.AtomicFiles;
import dev.terrarasa.DimensionPolicy;
import dev.terrarasa.Json;
import dev.terrarasa.ResourceIdentifier;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Operator configuration, stored as {@code config/soullink.toml}. Missing fields keep their defaults. */
public final class SoulLinkConfig {
    /** 4096 blocks: about 200,000 chunks, hours of generation. */
    private static final int MAX_PREGENERATION_BLOCKS = 4096;

    /** Controls how shared-vitality damage hits are reported in chat. */
    public enum DamageReportMode {
        NORMAL("normal"),
        MINIMAL("minimal"),
        OFF("off");

        private final String id;

        DamageReportMode(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public DamageReportMode next() {
            DamageReportMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public static DamageReportMode parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("damageReport must not be null");
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (DamageReportMode mode : values()) {
                if (mode.id.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Invalid damageReport '" + value + "'; expected normal, minimal, or off");
        }
    }

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
    /** Vanilla natural health regeneration rule default applied at startup and changeable via rules. */
    public boolean naturalRegeneration = true;
    /** Shows the action-bar speedrun timer during active runs. */
    public boolean timerVisible = true;
    /** Controls chat verbosity when participants take shared damage. */
    public DamageReportMode damageReport = DamageReportMode.NORMAL;
    /** Reports concise per-player time played and damage taken when a run ends. */
    public boolean runStats = true;
    /** Enables {@code /soullink debug ...}; still requires operator permission. */
    public boolean debugCommands = true;

    public static SoulLinkConfig load(Path file) {
        return load(file, null);
    }

    public static SoulLinkConfig load(Path file, @Nullable Path legacyJsonFile) {
        SoulLinkConfig config = new SoulLinkConfig();
        try {
            if (Files.isRegularFile(file)) {
                config = parseToml(file);
            } else if (legacyJsonFile != null && Files.isRegularFile(legacyJsonFile)) {
                SoulLinkConfig migrated = parseLegacyJson(legacyJsonFile);
                migrated.normalize();
                config = migrated;
                AtomicFiles.writeString(file, config.toToml());
                SoulLink.LOGGER.info("Migrated Soul Link configuration from {} to {}", legacyJsonFile, file);
            } else {
                AtomicFiles.writeString(file, config.toToml());
            }
        } catch (IOException | IllegalArgumentException | TomlInvalidTypeException | JsonParseException e) {
            SoulLink.LOGGER.error("Could not read {}; using defaults", file, e);
            return new SoulLinkConfig();
        }
        return config;
    }

    public void save(Path file) throws IOException {
        AtomicFiles.writeString(file, toToml());
    }

    public String toToml() {
        StringBuilder out = new StringBuilder();
        out.append("# Soul Link configuration\n");
        out.append("# Rules changed with /soullink rules <rule> <value> apply immediately or from the next run;\n");
        out.append("# add 'default' at the end (e.g. /soullink rules sharedVitality false default) to update defaults here.\n\n");

        out.append("# Gameplay rules (sharedVitality, soulLinkHardcore, sharedPotions, and halfHeart apply from the next run)\n");
        out.append("sharedVitality = ").append(this.sharedVitality).append('\n');
        out.append("soulLinkHardcore = ").append(this.soulLinkHardcore).append('\n');
        out.append("sharedPotions = ").append(this.sharedPotions).append('\n');
        out.append("halfHeart = ").append(this.halfHeart).append('\n');
        out.append("naturalRegeneration = ").append(this.naturalRegeneration).append('\n').append('\n');

        out.append("# Display and end-of-run statistics\n");
        out.append("timerVisible = ").append(this.timerVisible).append('\n');
        out.append("# Damage report verbosity in chat: \"normal\", \"minimal\", or \"off\"\n");
        out.append("damageReport = ").append(quoteTomlString(this.damageReport.id())).append('\n');
        out.append("# Print concise per-player time played and damage taken at the end of a run\n");
        out.append("runStats = ").append(this.runStats).append('\n').append('\n');

        out.append("# Server and lobby behavior\n");
        out.append("allowLateJoin = ").append(this.allowLateJoin).append('\n');
        out.append("autoPrepare = ").append(this.autoPrepare).append('\n');
        out.append("debugCommands = ").append(this.debugCommands).append('\n').append('\n');

        out.append("# World preparation and pool\n");
        out.append("preparedRunPoolSize = ").append(this.preparedRunPoolSize).append('\n');
        out.append("preloadRadius = ").append(this.preloadRadius).append('\n');
        out.append("activeRunConstructionIntervalTicks = ").append(this.activeRunConstructionIntervalTicks).append('\n');
        out.append("preparationTimeoutSeconds = ").append(this.preparationTimeoutSeconds).append('\n');
        out.append("drainTimeoutSeconds = ").append(this.drainTimeoutSeconds).append('\n').append('\n');

        appendStringList(out, "resettableDimensions", this.resettableDimensions);
        appendStringList(out, "persistentDimensions", this.persistentDimensions);
        out.append('\n');
        out.append("# Optional background pregeneration radii in blocks per resettable dimension (0 disables)\n");
        out.append("[pregeneration]\n");
        for (Map.Entry<String, Integer> entry : this.pregeneration.entrySet()) {
            out.append(quoteTomlString(entry.getKey())).append(" = ").append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    private static SoulLinkConfig parseToml(Path file) throws IOException {
        TomlParseResult table = Toml.parse(file);
        if (table.hasErrors()) {
            throw new IllegalArgumentException(table.errors().toString());
        }
        SoulLinkConfig config = new SoulLinkConfig();
        if (table.contains("resettableDimensions")) {
            config.resettableDimensions = readStringList(table, "resettableDimensions");
        }
        if (table.contains("persistentDimensions")) {
            config.persistentDimensions = readStringList(table, "persistentDimensions");
        }
        if (table.contains("preloadRadius")) {
            config.preloadRadius = readInt(table, "preloadRadius");
        }
        if (table.contains("pregeneration")) {
            TomlTable pregen = table.getTable("pregeneration");
            if (pregen == null) {
                throw new IllegalArgumentException("pregeneration must be a table");
            }
            Map<String, Integer> radii = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : pregen.entrySet()) {
                if (!(entry.getValue() instanceof Long value)) {
                    throw new IllegalArgumentException("pregeneration." + entry.getKey() + " must be an integer");
                }
                radii.put(entry.getKey(), Math.toIntExact(value));
            }
            config.pregeneration = radii;
        }
        if (table.contains("preparationTimeoutSeconds")) {
            config.preparationTimeoutSeconds = readInt(table, "preparationTimeoutSeconds");
        }
        if (table.contains("drainTimeoutSeconds")) {
            config.drainTimeoutSeconds = readInt(table, "drainTimeoutSeconds");
        }
        if (table.contains("preparedRunPoolSize")) {
            config.preparedRunPoolSize = readInt(table, "preparedRunPoolSize");
        }
        if (table.contains("activeRunConstructionIntervalTicks")) {
            config.activeRunConstructionIntervalTicks = readInt(table, "activeRunConstructionIntervalTicks");
        }
        if (table.contains("autoPrepare")) {
            config.autoPrepare = readBoolean(table, "autoPrepare");
        }
        if (table.contains("allowLateJoin")) {
            config.allowLateJoin = readBoolean(table, "allowLateJoin");
        }
        if (table.contains("sharedVitality")) {
            config.sharedVitality = readBoolean(table, "sharedVitality");
        }
        if (table.contains("soulLinkHardcore")) {
            config.soulLinkHardcore = readBoolean(table, "soulLinkHardcore");
        }
        if (table.contains("sharedPotions")) {
            config.sharedPotions = readBoolean(table, "sharedPotions");
        }
        if (table.contains("halfHeart")) {
            config.halfHeart = readBoolean(table, "halfHeart");
        }
        if (table.contains("naturalRegeneration")) {
            config.naturalRegeneration = readBoolean(table, "naturalRegeneration");
        }
        if (table.contains("timerVisible")) {
            config.timerVisible = readBoolean(table, "timerVisible");
        }
        if (table.contains("damageReport")) {
            String mode = table.getString("damageReport");
            if (mode == null) {
                throw new IllegalArgumentException("damageReport must be a string");
            }
            config.damageReport = DamageReportMode.parse(mode);
        }
        if (table.contains("runStats")) {
            config.runStats = readBoolean(table, "runStats");
        }
        if (table.contains("debugCommands")) {
            config.debugCommands = readBoolean(table, "debugCommands");
        }
        return config;
    }

    private static SoulLinkConfig parseLegacyJson(Path file) throws IOException {
        JsonObject root = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        SoulLinkConfig config = new SoulLinkConfig();
        if (root == null) {
            return config;
        }
        if (root.has("resettableDimensions") && root.get("resettableDimensions").isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("resettableDimensions")) {
                list.add(el.getAsString());
            }
            config.resettableDimensions = list;
        }
        if (root.has("persistentDimensions") && root.get("persistentDimensions").isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("persistentDimensions")) {
                list.add(el.getAsString());
            }
            config.persistentDimensions = list;
        }
        if (root.has("preloadRadius") && !root.get("preloadRadius").isJsonNull()) {
            config.preloadRadius = root.get("preloadRadius").getAsInt();
        }
        if (root.has("pregeneration") && root.get("pregeneration").isJsonObject()) {
            Map<String, Integer> radii = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("pregeneration").entrySet()) {
                radii.put(entry.getKey(), entry.getValue().getAsInt());
            }
            config.pregeneration = radii;
        }
        if (root.has("preparationTimeoutSeconds") && !root.get("preparationTimeoutSeconds").isJsonNull()) {
            config.preparationTimeoutSeconds = root.get("preparationTimeoutSeconds").getAsInt();
        }
        if (root.has("drainTimeoutSeconds") && !root.get("drainTimeoutSeconds").isJsonNull()) {
            config.drainTimeoutSeconds = root.get("drainTimeoutSeconds").getAsInt();
        }
        if (root.has("preparedRunPoolSize") && !root.get("preparedRunPoolSize").isJsonNull()) {
            config.preparedRunPoolSize = root.get("preparedRunPoolSize").getAsInt();
        }
        if (root.has("activeRunConstructionIntervalTicks") && !root.get("activeRunConstructionIntervalTicks").isJsonNull()) {
            config.activeRunConstructionIntervalTicks = root.get("activeRunConstructionIntervalTicks").getAsInt();
        }
        if (root.has("autoPrepare") && !root.get("autoPrepare").isJsonNull()) {
            config.autoPrepare = root.get("autoPrepare").getAsBoolean();
        }
        if (root.has("allowLateJoin") && !root.get("allowLateJoin").isJsonNull()) {
            config.allowLateJoin = root.get("allowLateJoin").getAsBoolean();
        }
        if (root.has("sharedVitality") && !root.get("sharedVitality").isJsonNull()) {
            config.sharedVitality = root.get("sharedVitality").getAsBoolean();
        }
        if (root.has("soulLinkHardcore") && !root.get("soulLinkHardcore").isJsonNull()) {
            config.soulLinkHardcore = root.get("soulLinkHardcore").getAsBoolean();
        }
        if (root.has("sharedPotions") && !root.get("sharedPotions").isJsonNull()) {
            config.sharedPotions = root.get("sharedPotions").getAsBoolean();
        }
        if (root.has("halfHeart") && !root.get("halfHeart").isJsonNull()) {
            config.halfHeart = root.get("halfHeart").getAsBoolean();
        }
        if (root.has("naturalRegeneration") && !root.get("naturalRegeneration").isJsonNull()) {
            config.naturalRegeneration = root.get("naturalRegeneration").getAsBoolean();
        }
        if (root.has("timerVisible") && !root.get("timerVisible").isJsonNull()) {
            config.timerVisible = root.get("timerVisible").getAsBoolean();
        }
        if (root.has("damageReport") && !root.get("damageReport").isJsonNull()) {
            config.damageReport = DamageReportMode.parse(root.get("damageReport").getAsString());
        }
        if (root.has("runStats") && !root.get("runStats").isJsonNull()) {
            config.runStats = root.get("runStats").getAsBoolean();
        }
        if (root.has("debugCommands") && !root.get("debugCommands").isJsonNull()) {
            config.debugCommands = root.get("debugCommands").getAsBoolean();
        }
        return config;
    }

    private static List<String> readStringList(TomlTable table, String key) {
        TomlArray array = table.getArray(key);
        if (array == null) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        List<String> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private static int readInt(TomlTable table, String key) {
        Long value = table.getLong(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return Math.toIntExact(value);
    }

    private static boolean readBoolean(TomlTable table, String key) {
        Boolean value = table.getBoolean(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value;
    }

    private static void appendStringList(StringBuilder out, String key, List<String> values) {
        out.append(key).append(" = [\n");
        for (String value : values) {
            out.append("    ").append(quoteTomlString(value)).append(",\n");
        }
        out.append("]\n");
    }

    private static String quoteTomlString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        out.append('"');
        return out.toString();
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
    public Map<ResourceIdentifier, Integer> pregenerationRadii() {
        Map<ResourceIdentifier, Integer> radii = new LinkedHashMap<>();
        this.pregeneration.forEach((dimension, radius) -> radii.put(ResourceIdentifier.parse(dimension), radius));
        return radii;
    }

    private static List<ResourceIdentifier> parse(List<String> ids) {
        return ids.stream().map(ResourceIdentifier::parse).collect(Collectors.toList());
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
        if (this.damageReport == null) {
            throw new IllegalArgumentException("damageReport must be normal, minimal, or off");
        }
    }

    public static SoulLinkConfig defaults() {
        return new SoulLinkConfig();
    }

    /** Never null even if a config file omitted or nulled a collection/enum. */
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
        if (this.damageReport == null) {
            this.damageReport = DamageReportMode.NORMAL;
        }
    }
}
