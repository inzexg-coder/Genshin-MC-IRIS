package net.teyvat.mixin.client;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.teyvat.client.CombatController;

/**
 * Игрок получил урон на клиенте — прерываем комбо (как в Genshin).
 */
@Mixin(ClientPlayerEntity.class)
public abstract class PlayerHurtMixin {
    @Inject(method = "damage", at = @At("HEAD"))
    private void teyvat$interruptComboOnHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        CombatController.onPlayerHurt();
    }
}
