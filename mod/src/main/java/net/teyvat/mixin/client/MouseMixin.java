package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.teyvat.client.CameraController;
import net.teyvat.client.QuestStateClient;
import net.teyvat.client.QuestToast;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.network.QuestEventPayload;
import net.teyvat.quest.Quests;
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
        // Первая прокрутка колеса в 3-м лице выполняет задание «Попробуй приблизить камеру».
        if (!QuestStateClient.isCompleted(Quests.TRY_SCROLL)) {
            QuestStateClient.markCompleted(Quests.TRY_SCROLL);
            if (client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new QuestEventPayload(Quests.TRY_SCROLL));
            }
            client.getToastManager().add(new QuestToast("Задание выполнено", "«" + Quests.TRY_SCROLL_TITLE + "»"));
        }
        ci.cancel();
    }
}
