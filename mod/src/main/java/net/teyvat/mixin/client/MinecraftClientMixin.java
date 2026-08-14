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
 * а ЛКМ-атака заменена комбо путешественника: свинг идёт по любому прицелу
 * (враг, воздух, блок). Ванильная добыча блоков остаётся только в креативе.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementsScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AdvancementsScreen) {
            ci.cancel();
        }
    }

    /** ЛКМ всегда уходит в боевку путешественника — по врагу, по воздуху и даже
     *  по блоку (свинг анимируется и двигает героя). Тап — удар комбо, удержание
     *  ~0.3 с — заряд (спин по отпусканию), см. CombatController.onAttackPress.
     *  Исключение: в креативе по блоку остаётся ванильная добыча. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void teyvat$swordComboAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getAbilities().creativeMode
                && client.crosshairTarget != null
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (CombatController.onAttackPress()) {
            cir.setReturnValue(true);
        }
    }

    /** В выживании ЛКМ по блоку не запускает ванильное ломание (только свинг комбо):
     *  на карте блоки не ломаются (world.no_block_breaking), трещины не нужны. */
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void teyvat$noBlockBreakingInSurvival(boolean slowDown, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && !client.player.getAbilities().creativeMode
                && client.crosshairTarget != null
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            ci.cancel();
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
