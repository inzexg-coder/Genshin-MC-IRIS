package net.teyvat.mixin.common;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Зелье регенерации не работает: здоровье в мире Тейвата восстанавливается
 * только сном. Убийца паймонских уроков — никаких скрытых исцелений.
 */
@Mixin(targets = "net.minecraft.entity.effect.RegenerationStatusEffect")
public abstract class RegenerationStatusEffectMixin {
    @Inject(method = "applyUpdateEffect", at = @At("HEAD"), cancellable = true)
    private void teyvat$noRegenerationPotion(ServerWorld world, LivingEntity entity,
                                             int amplifier, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
