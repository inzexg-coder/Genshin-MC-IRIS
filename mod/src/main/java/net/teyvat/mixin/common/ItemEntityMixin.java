package net.teyvat.mixin.common;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.teyvat.server.AutoPickupStats;
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
        AutoPickupStats.onBlocked();
        // Диагностика: если миксин реально работает, при подходе к предмету
        // игрок видит это уведомление (не чаще раза в 5 секунд). Если предмет
        // всё равно попадает в инвентарь БЕЗ этого уведомления — игра запущена
        // со старым jar, где автоподбор ещё не отключался.
        if (player instanceof ServerPlayerEntity serverPlayer) {
            long now = System.currentTimeMillis();
            if (now - AutoPickupStats.lastNoticeMillis() > 5000) {
                AutoPickupStats.touchNotice(now);
                serverPlayer.sendMessage(Text.literal(
                        "§8[§7Teyvat§8] §fАвтоподбор заблокирован — подними предмет на §eF§f."), false);
            }
        }
        ci.cancel();
    }
}
