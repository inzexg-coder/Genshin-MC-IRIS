package net.teyvat.mixin.common;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Второй рубеж против автоподбора: даже если ItemEntity.onPlayerCollision
 * каким-то образом дойдёт до игрока (первый рубеж — ItemEntityMixin),
 * сам вызов коллизии игрока с предметом отменяется на уровне PlayerEntity.
 * XP-орбы не задет — они собираются отдельным ванильным путём и не являются
 * ItemEntity. Добыча поднимается только вручную на F.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityPickupMixin {
    @Inject(method = "collideWithEntity", at = @At("HEAD"), cancellable = true)
    private void teyvat$noItemPickup(Entity entity, CallbackInfo ci) {
        if (entity instanceof ItemEntity) {
            ci.cancel();
        }
    }
}
