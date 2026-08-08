package net.teyvat.mixin.common;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.teyvat.config.TeyvatConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Урон от падения как в Genshin: первые ~7 блоков безопасны, дальше урон
 * растёт линейно и быстро становится смертельным. Вода по-прежнему
 * отменяет урон (это делает ваниль до вызова computeFallDamage).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /** Уровень моба: -1 = ещё не назначен (назначается при загрузке в мир). */
    @Unique
    private int teyvat$level = -1;

    @Invoker("computeFallDamage")
    protected abstract int teyvat$invokeComputeFallDamage(double heightDifference, float multiplier);

    @Redirect(method = "handleFallDamage",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;computeFallDamage(DF)I"))
    private int teyvat$genshinFallDamage(LivingEntity self, double heightDifference, float multiplier) {
        if (!(self instanceof PlayerEntity)) {
            return teyvat$invokeComputeFallDamage(heightDifference, multiplier);
        }
        TeyvatConfig.Health h = TeyvatConfig.get().health;
        double over = heightDifference - h.fall_damage_threshold;
        if (over <= 0) {
            return 0;
        }
        return (int) Math.ceil(over * h.fall_damage_per_block);
    }

    @Inject(method = "readCustomData", at = @At("HEAD"))
    private void teyvat$readLevel(ReadView view, CallbackInfo ci) {
        try {
            this.teyvat$level = view.getInt("teyvat_level", -1);
        } catch (Exception ignored) {
            // повреждённый/чужой NBT не должен ломать загрузку сущности
            this.teyvat$level = -1;
        }
    }

    @Inject(method = "writeCustomData", at = @At("HEAD"))
    private void teyvat$writeLevel(WriteView view, CallbackInfo ci) {
        try {
            if (this.teyvat$level >= 0) {
                view.putInt("teyvat_level", this.teyvat$level);
            }
        } catch (Exception ignored) {
            // сохранение уровня не должно ломать сохранение сущности
        }
    }
}
