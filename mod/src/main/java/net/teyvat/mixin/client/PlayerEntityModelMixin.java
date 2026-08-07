package net.teyvat.mixin.client;

import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.math.MathHelper;
import net.teyvat.client.TravelerPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Применяет позы превью-моделей в экране выбора путешественника (поклоны героев):
 *  поверх ванильной позы мягко смешивает повороты частей тела с позой из TravelerPose. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V",
            at = @At("TAIL"))
    private void teyvat$previewBow(PlayerEntityRenderState state, CallbackInfo ci) {
        TravelerPose pose = TravelerPose.forSkin(state.skinTextures);
        if (pose == null || pose.blend <= 0.001f) {
            return;
        }
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        float b = pose.blend;
        model.head.pitch = MathHelper.lerp(b, model.head.pitch, pose.headPitch);
        model.head.yaw = MathHelper.lerp(b, model.head.yaw, pose.headYaw);
        model.head.roll = MathHelper.lerp(b, model.head.roll, pose.headRoll);
        model.body.pitch = MathHelper.lerp(b, model.body.pitch, pose.torsoPitch);
        model.body.yaw = MathHelper.lerp(b, model.body.yaw, pose.torsoYaw);
        model.body.roll = MathHelper.lerp(b, model.body.roll, pose.torsoRoll);
        model.leftArm.pitch = MathHelper.lerp(b, model.leftArm.pitch, pose.leftArmPitch);
        model.leftArm.yaw = MathHelper.lerp(b, model.leftArm.yaw, pose.leftArmYaw);
        model.leftArm.roll = MathHelper.lerp(b, model.leftArm.roll, pose.leftArmRoll);
        model.rightArm.pitch = MathHelper.lerp(b, model.rightArm.pitch, pose.rightArmPitch);
        model.rightArm.yaw = MathHelper.lerp(b, model.rightArm.yaw, pose.rightArmYaw);
        model.rightArm.roll = MathHelper.lerp(b, model.rightArm.roll, pose.rightArmRoll);
        model.leftLeg.pitch = MathHelper.lerp(b, model.leftLeg.pitch, pose.leftLegPitch);
        model.leftLeg.yaw = MathHelper.lerp(b, model.leftLeg.yaw, pose.leftLegYaw);
        model.leftLeg.roll = MathHelper.lerp(b, model.leftLeg.roll, pose.leftLegRoll);
        model.rightLeg.pitch = MathHelper.lerp(b, model.rightLeg.pitch, pose.rightLegPitch);
        model.rightLeg.yaw = MathHelper.lerp(b, model.rightLeg.yaw, pose.rightLegYaw);
        model.rightLeg.roll = MathHelper.lerp(b, model.rightLeg.roll, pose.rightLegRoll);
    }
}
