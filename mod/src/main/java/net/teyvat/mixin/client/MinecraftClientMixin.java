package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.option.KeyBinding;
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

    /** ЛКМ по блоку — ванильная добыча/ломка блоков во всех режимах; по врагу
     *  или воздуху — комбо путешественника (тап — мгновенный удар, удержание —
     *  заряд 3 сек, см. CombatController.onAttackPress). */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void teyvat$swordComboAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.crosshairTarget != null
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return;
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
