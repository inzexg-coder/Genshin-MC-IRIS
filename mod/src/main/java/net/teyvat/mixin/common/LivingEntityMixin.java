package net.teyvat.mixin.common;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.server.WikiDiscoveries;
import net.teyvat.wiki.TeyvatWiki;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Урон от падения как в Genshin: первые ~7 блоков безопасны, дальше урон
 * растёт линейно и быстро становится смертельным. Вода по-прежнему
 * отменяет урон (это делает ваниль до вызова computeFallDamage).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
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
        int damage = (int) Math.ceil(over * h.fall_damage_per_block);
        // Вики: первое падение с уроном открывает запись «Урон от падения».
        if (damage > 0 && self instanceof ServerPlayerEntity serverPlayer) {
            WikiDiscoveries.discover(serverPlayer, TeyvatWiki.ID_FALL_DAMAGE);
        }
        return damage;
    }

}
