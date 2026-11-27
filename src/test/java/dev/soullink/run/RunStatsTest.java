package dev.soullink.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RunStatsTest {
    @Test
    void formatsConcisePerPlayerStatsUsingOnlineAndFallbackNames() {
        SoulLinkState state = new SoulLinkState();
        assertNull(RunCoordinator.formatRunStats(state, Map.of()));

        String alice = "de01a8ed-a44b-4b73-a67e-1fc64ac2df19";
        String bob = "e78e67b5-80b2-49ea-b584-c83b81053b90";
        state.participants.add(alice);
        state.participants.add(bob);
        state.playerNames.put(alice, "AliceOld");
        state.playerNames.put(bob, "Bob");
        state.playerPlayTicks.put(alice, 20L * 65L + 10L);
        state.playerPlayTicks.put(bob, 20L * 12L);
        state.playerDamageTaken.put(alice, 6.0);
        state.playerDamageTaken.put(bob, 2.5);

        assertEquals("Stats: Alice 01:05.5 (6 dmg), Bob 00:12.0 (2.5 dmg)",
                RunCoordinator.formatRunStats(state, Map.of(alice, "Alice")));
    }
}
