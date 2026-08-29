package net.teyvat.client.lizard;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.teyvat.entity.BlueHornedLizardEntity;

/**
 * Рендер синей рогатой ящерицы: кастомная модель в стиле мод-пака
 * Genshin Nature, матовая текстура без прозрачности.
 */
public class BlueHornedLizardEntityRenderer extends EntityRenderer<BlueHornedLizardEntity, BlueHornedLizardRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/blue_horned_lizard.png");

    private final BlueHornedLizardEntityModel model;

    public BlueHornedLizardEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new BlueHornedLizardEntityModel(BlueHornedLizardEntityModel.getTexturedModelData().createModel());
    }

    @Override
    public BlueHornedLizardRenderState createRenderState() {
        return new BlueHornedLizardRenderState();
    }

    @Override
    public void updateRenderState(BlueHornedLizardEntity entity, BlueHornedLizardRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.light = this.getLight(entity, tickDelta);
        state.yaw = entity.getLerpedYaw(tickDelta);
        state.pitch = entity.getLerpedPitch(tickDelta);
        state.headYaw = entity.getHeadYaw() - entity.getYaw(tickDelta);
        state.limbSwing = entity.limbAnimator.getAnimationProgress(tickDelta);
        state.limbSwingAmount = entity.limbAnimator.getSpeed();
    }

    @Override
    public void render(BlueHornedLizardRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - state.yaw));
        RenderLayer layer = RenderLayer.getEntityCutout(TEXTURE);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
