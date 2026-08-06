package net.teyvat.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.teyvat.client.paimon.PaimonManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Пока Паймон представляется, скрывает весь HUD (здоровье, хотбар, прицел, боссбары и т.д.).
 *  Чат остаётся видимым: именно в нём Паймон пишет свои реплики знакомства. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void teyvat$hideHudDuringPaimonIntro(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (PaimonManager.isIntroActive()) {
            ci.cancel();
            this.teyvat$renderChat(context, tickCounter);
        }
    }

    @Invoker("renderChat")
    protected abstract void teyvat$renderChat(DrawContext context, RenderTickCounter tickCounter);
}
