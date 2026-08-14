package net.teyvat.mixin.common;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Автоподбор отключён навсегда: игрок не подбирает предметы, просто проходя
 * мимо (onPlayerCollision вызывается из PlayerEntity для каждой сущности рядом).
 * Добыча поднимается только вручную на F (ItemPickup). Опыт не задет — XP-орбы
 * собираются отдельным путём.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAutoPickup(PlayerEntity player, CallbackInfo ci) {
        ci.cancel();
    }
}
