package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.teyvat.client.CameraController;
import net.teyvat.config.TeyvatConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Мышь для Teyvat Camera: в свободной камере вращает орбиту вместо героя,
 *  колесико в 3-м лице меняет дистанцию камеры. */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    /** Свободная камера: мышь вращает камеру вокруг героя, сам герой не поворачивается. */
    @Redirect(method = "updateMouse",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void teyvat$freeLook(ClientPlayerEntity player, double dx, double dy) {
        if (CameraController.isActive()) {
            CameraController.orbit(dx, dy);
        } else {
            player.changeLookDirection(dx, dy);
        }
    }

    /** Колесико в 3-м лице меняет дистанцию камеры (вместо выбора слота хотбара). */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void teyvat$cameraScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        TeyvatConfig.Camera cfg = TeyvatConfig.get().camera;
        if (!cfg.scroll_controls_distance || vertical == 0) {
            return;
        }
        if (client.player == null || client.world == null || client.currentScreen != null
                || client.getOverlay() != null || client.options == null) {
            return;
        }
        if (window != client.getWindow().getHandle()) {
            return;
        }
        Perspective perspective = client.options.getPerspective();
        if (perspective.isFirstPerson() || perspective.isFrontView()) {
            return;
        }
        CameraController.scroll((float) vertical);
        ci.cancel();
    }
}
