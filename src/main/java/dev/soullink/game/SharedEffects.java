package dev.soullink.game;

import java.util.Collection;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Shared potions (a chaos option in Soul Link Speedrun): an effect gained, upgraded or lost by one participant is
 * gained, upgraded or lost by every participant.
 *
 * <p>Changes are mirrored from vanilla's own effect callbacks, so every source counts: potions, beacons,
 * mobs, food, milk, totems, commands and expiry. Teammates receive the effect through
 * {@code addEffect}, which applies vanilla's merge rules and start hooks (Absorption, for example) as if
 * they had drunk the potion themselves. Instant effects have no instance; their healing and damage are
 * already shared by {@link SharedVitality}.
 */
public final class SharedEffects {
    private boolean mirroring;

    /** Mirrors a new or upgraded effect onto {@code teammates}. */
    public void onEffectChanged(MobEffectInstance effect, @Nullable Entity source, List<ServerPlayer> teammates) {
        mirror(() -> {
            for (ServerPlayer teammate : teammates) {
                teammate.addEffect(new MobEffectInstance(effect), source);
            }
        });
    }

    /** Mirrors removed effects onto {@code teammates}. */
    public void onEffectsRemoved(Collection<MobEffectInstance> effects, List<ServerPlayer> teammates) {
        mirror(() -> {
            for (MobEffectInstance effect : effects) {
                for (ServerPlayer teammate : teammates) {
                    teammate.removeEffect(effect.getEffect());
                }
            }
        });
    }

    /** Gives a player joining a running run the effects the team already has. */
    public void syncJoiner(ServerPlayer joiner, ServerPlayer teammate) {
        mirror(() -> {
            for (MobEffectInstance effect : List.copyOf(teammate.getActiveEffects())) {
                joiner.addEffect(new MobEffectInstance(effect), null);
            }
        });
    }

    /** Teammates' own callbacks fire while mirroring; they must not mirror back. */
    public boolean isMirroring() {
        return this.mirroring;
    }

    private void mirror(Runnable change) {
        if (this.mirroring) {
            return;
        }
        this.mirroring = true;
        try {
            change.run();
        } finally {
            this.mirroring = false;
        }
    }
}
