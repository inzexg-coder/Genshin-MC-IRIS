package net.teyvat.client.hydro;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.teyvat.entity.HydroSlimeProjectileEntity;

/** Рендер водяного шара: маленькая полупрозрачная капля. */
public class HydroSlimeProjectileRenderer extends EntityRenderer<HydroSlimeProjectileEntity, HydroSlimeProjectileRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/hydro_slime_projectile.png");
    private static final float SCALE = 0.32f;

    private final HydroSlimeProjectileModel model;

    public HydroSlimeProjectileRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new HydroSlimeProjectileModel(HydroSlimeProjectileModel.getTexturedModelData().createModel());
    }

    @Override
    public HydroSlimeProjectileRenderState createRenderState() {
        return new HydroSlimeProjectileRenderState();
    }

    @Override
    public void updateRenderState(HydroSlimeProjectileEntity entity, HydroSlimeProjectileRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.light = this.getLight(entity, tickDelta);
        state.yaw = entity.getLerpedYaw(tickDelta);
        state.pitch = entity.getLerpedPitch(tickDelta);
    }

    @Override
    public void render(HydroSlimeProjectileRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - state.yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.pitch));
        matrices.scale(SCALE, SCALE, SCALE);
        RenderLayer layer = RenderLayer.getEntityTranslucent(TEXTURE);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
