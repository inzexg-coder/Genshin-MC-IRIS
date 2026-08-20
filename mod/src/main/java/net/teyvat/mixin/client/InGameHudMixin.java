package net.teyvat.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.teyvat.client.DialogueOverlay;
import net.teyvat.client.HealthOverlay;
import net.teyvat.client.NotificationStack;
import net.teyvat.client.PickupController;
import net.teyvat.client.StaminaOverlay;
import net.teyvat.client.TeleportActivationClient;
import net.teyvat.client.MinimapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Весь ванильный HUD скрыт навсегда: на экране только мир, диалоговый оверлей
 *  и уведомления квестов (тосты рисует GameRenderer отдельно от InGameHud). */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void teyvat$hideHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
        DialogueOverlay.render(context);
        HealthOverlay.render(context, tickCounter);
        StaminaOverlay.render(context);
        NotificationStack.render(context);
        PickupController.render(context);
        TeleportActivationClient.renderHint(context);
        MinimapRenderer.render(context);
    }
}
