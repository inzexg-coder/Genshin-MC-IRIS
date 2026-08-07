package net.teyvat.mixin.common;

import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Голод отключён полностью: истощение не копится, сытость держится полной,
 * урон от голода невозможен, реген здоровья работает всегда. Есть можно,
 * но это не обязательно.
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {
    /** Бег, прыжки и атаки больше не тратят сытость. */
    @Inject(method = "addExhaustion", at = @At("HEAD"), cancellable = true)
    private void teyvat$noExhaustion(float exhaustion, CallbackInfo ci) {
        ci.cancel();
    }

    /** Сытость всегда полная: даже чужие моды не смогут уморить голодом. */
    @Inject(method = "update", at = @At("HEAD"))
    private void teyvat$keepFull(ServerPlayerEntity player, CallbackInfo ci) {
        HungerManager self = (HungerManager) (Object) this;
        self.setFoodLevel(20);
        self.setSaturationLevel(5.0f);
    }
}
