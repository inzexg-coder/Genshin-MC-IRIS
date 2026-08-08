package net.teyvat.mixin.common;

import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Голод отключён полностью: истощение не копится, сытость держится полной,
 * урон от голода невозможен. Есть можно, но это не обязательно.
 * Здоровье само не восстанавливается — только сон (как в Genshin).
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

    /** Естественный реген по сытости отключён: здоровье лечится только сном. */
    @Redirect(method = "update",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;heal(F)V"))
    private void teyvat$noNaturalRegen(ServerPlayerEntity player, float amount) {
        // намеренно ничего не делаем
    }
}
