package net.teyvat.mixin.client;

import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.teyvat.client.CombatController;
import net.teyvat.client.FirstPersonBody;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Анимация боевки путешественника: после ванильной постановки поз модель
 * локального игрока: во время комбо — ключевые кадры ударов (руки, корпус,
 * разворот крутит root модели), вне комбо — эпичная ходьба/бег в стиле
 * Origin Animation (широкие махи, наклон и поворот корпуса, подскок).
 * Видно в 3-м лице и в первом лице (FirstPersonBody рисует собственное
 * тело «глазами модельки», при этом голова скрывается — камера внутри).
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles", at = @At("TAIL"))
    private void teyvat$applySwordCombo(PlayerEntityRenderState state, CallbackInfo ci) {
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        // Вызывается всегда: во время комбо — поза удара, вне комбо — эпичная
        // ходьба/бег (стиль Origin Animation); root сбрасывается, чтобы
        // прерванный в воздухе разворот не оставил модель повёрнутой.
        CombatController.applyPlayerPose(model, state);
        // Своё тело в первом лице: голова скрыта (камера внутри головы) и
        // самовосстанавливается на каждом следующем setAngles.
        boolean self = FirstPersonBody.selfRendering();
        model.head.visible = !self;
        model.hat.visible = !self;
    }
}
