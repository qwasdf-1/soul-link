package dev.soullink.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every statistic a player has, for {@code /soullink debug player}. */
@Mixin(StatsCounter.class)
public interface StatsCounterAccessor {
    @Accessor("stats")
    Object2IntMap<Stat<?>> soullink$stats();
}
