package dev.soullink.run;

import java.util.Collection;

/** Resolves the run-level outcome after a participant death. */
final class DeathRules {
    enum Outcome {
        CONTINUE,
        TEAM_WIPE,
        END_RUN
    }

    private DeathRules() {
    }

    static Outcome afterDeath(boolean sharedVitality, boolean soulLinkHardcore, boolean vanillaHardcore,
            Collection<String> participants, Collection<String> eliminated) {
        if (sharedVitality || soulLinkHardcore) {
            return Outcome.END_RUN;
        }
        if (vanillaHardcore && !participants.isEmpty() && eliminated.containsAll(participants)) {
            return Outcome.TEAM_WIPE;
        }
        return Outcome.CONTINUE;
    }
}
