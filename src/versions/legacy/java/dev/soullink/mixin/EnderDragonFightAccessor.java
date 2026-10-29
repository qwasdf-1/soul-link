package dev.soullink.mixin;

import java.util.UUID;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of the vanilla fight state for {@code /soullink debug end}. */
@Mixin(EndDragonFight.class)
public interface EnderDragonFightAccessor {
    /** Gateway indices not yet spawned; the next gateway is the last element. Shuffled from the seed. */
    @Accessor("gateways")
    ObjectArrayList<Integer> soullink$gateways();

    @Accessor("portalLocation")
    @Nullable BlockPos soullink$exitPortalLocation();

    @Accessor("dragonKilled")
    boolean soullink$dragonKilled();

    @Accessor("previouslyKilled")
    boolean soullink$previouslyKilled();

    /** The dragon this fight tracks; only its death counts as killing the dragon. */
    @Accessor("dragonUUID")
    @Nullable UUID soullink$dragonUuid();

    @Accessor("dragonEvent")
    ServerBossEvent soullink$bossBar();

    @Accessor("origin")
    BlockPos soullink$origin();
}
