package dev.soullink.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RunTimerTest {
    @Test
    void formatsTicksAsSpeedrunTime() {
        assertEquals("00:00.0", RunTimer.format(0));
        assertEquals("00:01.5", RunTimer.format(30));
        assertEquals("12:34.5", RunTimer.format((12 * 60 + 34) * 20 + 10));
        assertEquals("1:00:00.0", RunTimer.format(72_000));
    }
}
