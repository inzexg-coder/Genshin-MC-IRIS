package net.teyvat.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.teyvat.client.DialogueOverlay;
import net.teyvat.client.paimon.PaimonManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Пока Паймон представляется, весь HUD скрыт: на экране только мир и диалоговый оверлей.
 *  В обычной игре оверлей рисуется поверх HUD, когда активен диалог. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void teyvat$hideHudDuringPaimonIntro(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (PaimonManager.isIntroActive() || PaimonManager.isTutorialActive()) {
            ci.cancel();
            DialogueOverlay.render(context);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void teyvat$dialogueOverlay(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        DialogueOverlay.render(context);
    }
}
