package net.teyvat.mixin.client;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.teyvat.client.CombatController;
import net.teyvat.client.PickupController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Экран достижений майнкрафта не открывается (кнопка в меню паузы просто не работает),
 * а ЛКМ-атака заменена комбо путешественника: свинг идёт по врагу и воздуху.
 * Ванильная добыча блоков (ЛКМ по блоку) работает во всех режимах. F — подбор.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementsScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AdvancementsScreen) {
            ci.cancel();
        }
    }

    /** Плод Закатника внутри кроны. */
    private static final Block SUNSETTIA_FRUIT =
            Registries.BLOCK.get(Identifier.of("teyvat", "sunnsettia_fruit"));

    /** ЛКМ по блоку — ванильная добыча/ломка блоков во всех режимах; прицельно
     *  по плоду Закатника — мгновенно ломаем его (шлём START_DESTROY, сервер
     *  даёт 1-2 Закатника). По врагу или воздуху — комбо путешественника. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void teyvat$swordComboAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.crosshairTarget == null) {
            return;
        }
        if (client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
            if (client.world != null
                    && client.world.getBlockState(hit.getBlockPos()).isOf(SUNSETTIA_FRUIT)) {
                // Удар по плоду: гарантированно отправляем START_DESTROY на сервер.
                client.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());
                cir.setReturnValue(true);
                return;
            }
            return; // обычный блок — ванильная добыча
        }
        if (CombatController.onAttackPress()) {
            cir.setReturnValue(true);
        }
    }

    /** F — только подбор предметов (PickupController). «Смена руки» на F
     *  убрана полностью: перехватываем все wasPressed() в handleInputEvents
     *  и гасим swapHandsKey всегда. */
    @Redirect(method = "handleInputEvents",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/KeyBinding;wasPressed()Z"))
    private boolean teyvat$pickupInsteadOfSwap(KeyBinding keyBinding) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (keyBinding == client.options.swapHandsKey) {
            return false;
        }
        return keyBinding.wasPressed();
    }
}
