package net.teyvat.mixin.common;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    /** Сколько ванильных попыток автоподбора заблокировано (для /teyvat pickup). */
    @Unique
    private static long teyvat_blockedCount;
    /** Последний раз, когда игроку показывали уведомление о блокировке. */
    @Unique
    private static long teyvat_lastNoticeMillis;

    @Unique
    public static long blockedCount() {
        return teyvat_blockedCount;
    }

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAutoPickup(PlayerEntity player, CallbackInfo ci) {
        teyvat_blockedCount++;
        // Диагностика: если миксин реально работает, при подходе к предмету
        // игрок видит это уведомление (не чаще раза в 5 секунд). Если предмет
        // всё равно попадает в инвентарь БЕЗ этого уведомления — игра запущена
        // со старым jar, где автоподбор ещё не отключался.
        if (player instanceof ServerPlayerEntity serverPlayer) {
            long now = System.currentTimeMillis();
            if (now - teyvat_lastNoticeMillis > 5000) {
                teyvat_lastNoticeMillis = now;
                serverPlayer.sendMessage(Text.literal(
                        "§8[§7Teyvat§8] §fАвтоподбор заблокирован — подними предмет на §eF§f."), false);
            }
        }
        ci.cancel();
    }
}
