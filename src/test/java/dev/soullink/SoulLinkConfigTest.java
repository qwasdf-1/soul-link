package dev.soullink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoulLinkConfigTest {
    @TempDir
    Path temp;

    @Test
    void loadWritesDefaultTomlAndRoundTripsAllSettings() throws IOException {
        Path toml = this.temp.resolve("soullink.toml");
        SoulLinkConfig defaults = SoulLinkConfig.load(toml);
        assertTrue(Files.isRegularFile(toml));
        assertTrue(defaults.sharedVitality);
        assertTrue(defaults.naturalRegeneration);
        assertTrue(defaults.timerVisible);
        assertEquals(SoulLinkConfig.DamageReportMode.NORMAL, defaults.damageReport);
        assertTrue(defaults.runStats);

        defaults.sharedVitality = false;
        defaults.soulLinkHardcore = true;
        defaults.sharedPotions = true;
        defaults.halfHeart = true;
        defaults.naturalRegeneration = false;
        defaults.timerVisible = false;
        defaults.damageReport = SoulLinkConfig.DamageReportMode.MINIMAL;
        defaults.runStats = false;
        defaults.preparedRunPoolSize = 2;
        defaults.pregeneration = Map.of("minecraft:overworld", 250);
        defaults.save(toml);

        SoulLinkConfig reloaded = SoulLinkConfig.load(toml);
        reloaded.normalize();
        reloaded.validate();
        assertFalse(reloaded.sharedVitality);
        assertTrue(reloaded.soulLinkHardcore);
        assertTrue(reloaded.sharedPotions);
        assertTrue(reloaded.halfHeart);
        assertFalse(reloaded.naturalRegeneration);
        assertFalse(reloaded.timerVisible);
        assertEquals(SoulLinkConfig.DamageReportMode.MINIMAL, reloaded.damageReport);
        assertFalse(reloaded.runStats);
        assertEquals(2, reloaded.preparedRunPoolSize);
        assertEquals(Map.of("minecraft:overworld", 250), reloaded.pregeneration);
    }

    @Test
    void partialTomlPreservesDefaultsAndExplicitEmptyPregeneration() throws IOException {
        Path toml = this.temp.resolve("soullink.toml");
        Files.writeString(toml, """
                preloadRadius = 2
                damageReport = "off"

                [pregeneration]
                """);

        SoulLinkConfig loaded = SoulLinkConfig.load(toml);
        loaded.normalize();
        loaded.validate();
        assertEquals(2, loaded.preloadRadius);
        assertEquals(SoulLinkConfig.DamageReportMode.OFF, loaded.damageReport);
        assertTrue(loaded.sharedVitality);
        assertTrue(loaded.timerVisible);
        assertTrue(loaded.runStats);
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                loaded.resettableDimensions);
        assertTrue(loaded.pregeneration.isEmpty());
    }

    @Test
    void migratesLegacyJsonToTomlWhenTomlIsAbsent() throws IOException {
        Path legacyJson = this.temp.resolve("soullink.json");
        Path toml = this.temp.resolve("soullink.toml");
        Files.writeString(legacyJson, """
                {
                  "preloadRadius": 3,
                  "sharedPotions": true,
                  "halfHeart": true,
                  "pregeneration": {}
                }
                """);

        SoulLinkConfig loaded = SoulLinkConfig.load(toml, legacyJson);
        loaded.normalize();
        loaded.validate();
        assertTrue(Files.isRegularFile(toml));
        assertEquals(3, loaded.preloadRadius);
        assertTrue(loaded.sharedPotions);
        assertTrue(loaded.halfHeart);
        assertTrue(loaded.pregeneration.isEmpty());
        assertTrue(loaded.naturalRegeneration);
        assertEquals(SoulLinkConfig.DamageReportMode.NORMAL, loaded.damageReport);
    }

    @Test
    void rejectsUnknownDamageReportMode() {
        assertThrows(IllegalArgumentException.class, () -> SoulLinkConfig.DamageReportMode.parse("verbose"));
    }
}
