package dev.soullink.setup;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;

/** Immutable view of the settings displayed by the operator setup menu. */
public record SetupSnapshot(
        boolean sharedVitality,
        boolean soulLinkHardcore,
        boolean sharedPotions,
        boolean halfHeart,
        boolean naturalRegeneration,
        boolean allowLateJoin,
        boolean autoPrepare,
        int preparedRunPoolSize,
        int readyRunCount,
        List<Identifier> resettableDimensions,
        Map<String, Integer> pregeneration,
        Map<String, Integer> defaultPregeneration,
        List<String> persistentDimensions,
        int preloadRadius,
        int activeRunConstructionIntervalTicks,
        int preparationTimeoutSeconds,
        int drainTimeoutSeconds,
        boolean debugCommands) {
}
