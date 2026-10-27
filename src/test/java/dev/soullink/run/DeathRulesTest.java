package dev.soullink.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeathRulesTest {
    private static final List<String> TEAM = List.of("alice", "bob");

    @Test
    void linkedRunsKeepTheirOneDeathDefeatRule() {
        assertEquals(DeathRules.Outcome.END_RUN,
                DeathRules.afterDeath(true, false, false, TEAM, List.of("alice")));
    }

    @Test
    void soulLinkHardcoreEndsOnTheFirstDeath() {
        assertEquals(DeathRules.Outcome.END_RUN,
                DeathRules.afterDeath(false, true, false, TEAM, List.of("alice")));
    }

    @Test
    void unlinkedNormalWorldUsesVanillaRespawn() {
        assertEquals(DeathRules.Outcome.CONTINUE,
                DeathRules.afterDeath(false, false, false, TEAM, List.of()));
    }

    @Test
    void unlinkedHardcoreWorldEndsOnlyOnTeamWipe() {
        assertEquals(DeathRules.Outcome.CONTINUE,
                DeathRules.afterDeath(false, false, true, TEAM, List.of("alice")));
        assertEquals(DeathRules.Outcome.TEAM_WIPE,
                DeathRules.afterDeath(false, false, true, TEAM, TEAM));
    }
}
