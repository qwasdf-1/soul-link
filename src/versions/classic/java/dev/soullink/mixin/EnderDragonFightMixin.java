package dev.soullink.mixin;

import dev.soullink.run.RunCoordinator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla records every dragon kill here, including {@code /kill}, which bypasses {@code die()} and so
 * never fires Fabric's death event.
 */
@Mixin(EndDragonFight.class)
abstract class EnderDragonFightMixin {
    @Shadow
    private ServerLevel level;

    @Inject(method = "setDragonKilled", at = @At("TAIL"))
    private void soullink$onDragonKilled(EnderDragon dragon, CallbackInfo ci) {
        RunCoordinator coordinator = RunCoordinator.active();
        if (coordinator != null) {
            coordinator.onDragonKilled(this.level);
        }
    }
}
