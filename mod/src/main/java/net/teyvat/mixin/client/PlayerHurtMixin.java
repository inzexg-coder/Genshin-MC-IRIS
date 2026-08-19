package net.teyvat.mixin.client;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.network.ClientPlayerEntity;
import net.teyvat.client.CombatController;

/**
 * Игрок получил урон на клиенте — прерываем комбо (как в Genshin).
 */
@Mixin(LivingEntity.class)
public abstract class PlayerHurtMixin {
    @Inject(method = "damage", at = @At("HEAD"))
    private void teyvat$interruptComboOnHurt(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (self instanceof ClientPlayerEntity) {
            CombatController.onPlayerHurt();
        }
    }
}
