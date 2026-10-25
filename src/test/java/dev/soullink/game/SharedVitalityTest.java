package dev.soullink.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import dev.soullink.game.SharedVitality.Delta;
import dev.soullink.game.SharedVitality.Pool;
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
}
