package net.teyvat.mixin.common;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Мгновенное исцеление (зелья/стрелы) отключено: здоровье лечится только сном.
 * Ветка урона (магия против нежити) не трогается.
 */
@Mixin(targets = "net.minecraft.entity.effect.InstantHealthOrDamageStatusEffect")
public abstract class InstantHealthOrDamageStatusEffectMixin {
    @Shadow
    private boolean damage;

    @Inject(method = "applyUpdateEffect", at = @At("HEAD"), cancellable = true)
    private void teyvat$noInstantHealTick(ServerWorld world, LivingEntity entity,
                                          int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (damage == entity.hasInvertedHealingAndHarm()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "applyInstantEffect", at = @At("HEAD"), cancellable = true)
    private void teyvat$noInstantHealApply(ServerWorld world, Entity source, Entity attacker,
                                           LivingEntity target, int amplifier, double proximity,
                                           CallbackInfo ci) {
        if (damage == target.hasInvertedHealingAndHarm()) {
            ci.cancel();
        }
    }
}
