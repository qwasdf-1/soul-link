package dev.soullink.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import dev.soullink.SoulLinkConfig.DamageReportMode;
import dev.soullink.game.SharedVitality.Delta;
import dev.soullink.game.SharedVitality.Pool;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class SharedVitalityTest {
    private static final Pool FULL = new Pool(20.0F, 20, 5.0F);

    @Test
    void damageFromSeveralPlayersAddsUp() {
        Pool pool = SharedVitality.fold(FULL, 20.0F, List.of(new Delta(-4.0F, 0, 0.0F), new Delta(-3.0F, 0, 0.0F)));
        assertEquals(13.0F, pool.health());
    }

    @Test
    void simultaneousRegenerationHealsOnce() {
        Pool hurt = new Pool(10.0F, 20, 5.0F);
        Pool pool = SharedVitality.fold(hurt, 20.0F, List.of(new Delta(1.0F, 0, -1.0F), new Delta(1.0F, 0, -1.0F)));
        assertEquals(11.0F, pool.health());
        assertEquals(3.0F, pool.saturation());
    }

    @Test
    void poolIsClampedToTheSmallestMaxHealthAndFoodRange() {
        Pool pool = SharedVitality.fold(new Pool(18.0F, 19, 5.0F), 16.0F, List.of(new Delta(4.0F, 6, 2.0F)));
        assertEquals(16.0F, pool.health());
        assertEquals(20, pool.food());
        assertEquals(7.0F, pool.saturation());
        Pool starving = SharedVitality.fold(new Pool(5.0F, 1, 0.5F), 20.0F, List.of(new Delta(-9.0F, -3, -2.0F)));
        assertEquals(0.0F, starving.health());
        assertEquals(0, starving.food());
        assertEquals(0.0F, starving.saturation());
    }

    @Test
    void formatsDamageReportAcrossModesAndCustomMaxHealth() {
        Component normal = SharedVitality.formatDamageReport("Alice", 2.0F, 18.0F, 20.0F, DamageReportMode.NORMAL);
        assertNotNull(normal);
        assertEquals("Alice took 2 damage [\u2665 18/20]", normal.getString());

        Component halfHeart = SharedVitality.formatDamageReport("Alice", 0.5F, 0.5F, 1.0F, DamageReportMode.NORMAL);
        assertNotNull(halfHeart);
        assertEquals("Alice took 0.5 damage [\u2665 0.5/1]", halfHeart.getString());

        Component boostedMax = SharedVitality.formatDamageReport("Bob", 2.5F, 21.5F, 24.0F, DamageReportMode.NORMAL);
        assertNotNull(boostedMax);
        assertEquals("Bob took 2.5 damage [\u2665 21.5/24]", boostedMax.getString());

        Component minimal = SharedVitality.formatDamageReport("Alice", 3.5F, 16.5F, 20.0F, DamageReportMode.MINIMAL);
        assertNotNull(minimal);
        assertEquals("took 3.5 damage", minimal.getString());

        assertNull(SharedVitality.formatDamageReport("Alice", 3.5F, 16.5F, 20.0F, DamageReportMode.OFF));
    }
}
