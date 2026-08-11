package net.teyvat.mixin.client;

import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.teyvat.client.CombatController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Анимация боевки путешественника: после ванильной постановки поз модель
 * локального игрока доворачивается по ключевым кадрам комбо (руки, корпус,
 * голова, ноги; разворот крутит root модели). Вне ударов root сбрасывается.
 * Видно в 3-м лице (Teyvat Camera) — как в Genshin.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles", at = @At("TAIL"))
    private void teyvat$applySwordCombo(PlayerEntityRenderState state, CallbackInfo ci) {
        // Вызывается всегда: вне ударов root модели сбрасывается, чтобы
        // прерванный в воздухе разворот не оставил модель повёрнутой.
        CombatController.applyPose((PlayerEntityModel) (Object) this, state.id);
    }
}
