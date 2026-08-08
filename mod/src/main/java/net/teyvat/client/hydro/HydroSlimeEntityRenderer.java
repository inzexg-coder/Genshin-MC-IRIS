package net.teyvat.client.hydro;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.teyvat.entity.HydroSlimeEntity;

/** Рендер Гидро слайма: полупрозрачное тело, squash-and-stretch при прыжках. */
public class HydroSlimeEntityRenderer extends EntityRenderer<HydroSlimeEntity, HydroSlimeRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/hydro_slime.png");
    private static final float SCALE = 1.1f;

    private final HydroSlimeEntityModel model;

    public HydroSlimeEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new HydroSlimeEntityModel(HydroSlimeEntityModel.getTexturedModelData().createModel());
    }

    @Override
    public HydroSlimeRenderState createRenderState() {
        return new HydroSlimeRenderState();
    }

    @Override
    public void updateRenderState(HydroSlimeEntity entity, HydroSlimeRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.light = this.getLight(entity, tickDelta);
        state.yaw = entity.getLerpedYaw(tickDelta);
        Vec3d velocity = entity.getVelocity();
        float vy = (float) velocity.y;
        float stretch = 1.0f + MathHelper.clamp(vy * 0.07f, -0.12f, 0.18f);
        state.stretch = stretch;
        state.squash = 1.0f / stretch;
        state.bob = 0.035f * (float) Math.sin(entity.age * 0.22f);
    }

    @Override
    public void render(HydroSlimeRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        matrices.translate(0.0f, state.bob, 0.0f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - state.yaw));
        matrices.scale(state.squash * SCALE, state.stretch * SCALE, state.squash * SCALE);
        RenderLayer layer = RenderLayer.getEntityTranslucent(TEXTURE);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
