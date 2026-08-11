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

    /** ЛКМ всегда уходит в комбо путешественника — по врагу, по воздуху и даже по блоку
     *  (свинг анимируется и двигает героя). Исключение: в креативе по блоку остаётся
     *  ванильная добыча, чтобы строить и разбирать декорации. */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void teyvat$swordComboAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.getAbilities().creativeMode
                && client.crosshairTarget != null
                && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (CombatController.onAttackClick()) {
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
}
