package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.teyvat.client.StaminaController;
import net.teyvat.client.ZoomController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Зум по кнопке: плавно уменьшает FOV, пока клавиша зажата (вместо подзорной трубы). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void teyvat$zoomFov(Camera camera, float tickDelta, boolean changingFov,
                                CallbackInfoReturnable<Float> cir) {
        if (MinecraftClient.getInstance().currentScreen != null) {
            return;
        }
        float fov = cir.getReturnValue();
        // Рывок слегка расширяет обзор — ощущение ускорения, как в Genshin.
        cir.setReturnValue(fov * ZoomController.fovFactor() * (1f + 0.05f * StaminaController.dashFactor()));
    }
}
