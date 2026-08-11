package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.util.hit.HitResult;
import net.teyvat.client.CombatController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Экран достижений майнкрафта не открывается (кнопка в меню паузы просто не работает),
 * а ЛКМ-атака заменена комбо путешественника (кроме добычи блоков — она остаётся ванильной).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementsScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AdvancementsScreen) {
            ci.cancel();
        }
    }

    /** ЛКМ по существу (или пустоте) не бьёт ванильно: клик уходит в комбо путешественника.
     *  Прицел в блок не трогаем — ломание блоков остаётся обычным. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void teyvat$swordComboAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.crosshairTarget == null || client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (CombatController.onAttackClick()) {
            cir.setReturnValue(true);
        }
    }
}
