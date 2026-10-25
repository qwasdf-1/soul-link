package dev.soullink.mixin;

import java.util.Collection;

import dev.soullink.run.RunCoordinator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds vanilla's effect callbacks to shared potions; every effect change on a player passes here. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerEffectsMixin {
    @Inject(method = "onEffectAdded", at = @At("TAIL"))
    private void soullink$onEffectAdded(MobEffectInstance effect, @Nullable Entity source, CallbackInfo ci) {
        RunCoordinator coordinator = RunCoordinator.active();
        if (coordinator != null) {
            coordinator.onEffectChanged((ServerPlayer) (Object) this, effect, source);
        }
    }

    /** {@code doRefreshAttributes} is false only for vanilla's periodic resend, which changes nothing. */
    @Inject(method = "onEffectUpdated", at = @At("TAIL"))
    private void soullink$onEffectUpdated(MobEffectInstance effect, boolean doRefreshAttributes, @Nullable Entity source,
            CallbackInfo ci) {
        RunCoordinator coordinator = RunCoordinator.active();
        if (coordinator != null && doRefreshAttributes) {
            coordinator.onEffectChanged((ServerPlayer) (Object) this, effect, source);
        }
    }

    @Inject(method = "onEffectsRemoved", at = @At("TAIL"))
    private void soullink$onEffectsRemoved(Collection<MobEffectInstance> effects, CallbackInfo ci) {
        RunCoordinator coordinator = RunCoordinator.active();
        if (coordinator != null) {
            coordinator.onEffectsRemoved((ServerPlayer) (Object) this, effects);
        }
    }
}
