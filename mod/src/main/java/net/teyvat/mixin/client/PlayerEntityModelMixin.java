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
 * локального игрока: во время комбо — ключевые кадры ударов (руки, корпус,
 * разворот крутит root модели), вне комбо — эпичная ходьба/бег в стиле
 * Origin Animation (широкие махи, наклон и поворот корпуса, подскок).
 * Видно в 3-м лице (Teyvat Camera) — как в Genshin.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles", at = @At("TAIL"))
    private void teyvat$applySwordCombo(PlayerEntityRenderState state, CallbackInfo ci) {
        // Вызывается всегда: во время комбо — поза удара, вне комбо — эпичная
        // ходьба/бег (стиль Origin Animation); root сбрасывается, чтобы
        // прерванный в воздухе разворот не оставил модель повёрнутой.
        CombatController.applyPlayerPose((PlayerEntityModel) (Object) this, state);
    }
}
