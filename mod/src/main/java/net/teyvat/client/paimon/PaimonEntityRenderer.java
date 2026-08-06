package net.teyvat.client.paimon;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * Рендер Паймон: трансформации повторяют оригинальный RenderPaimon
 * (подъём на 1 блок, поворот 180-yaw, отражение и масштаб 0.65).
 */
public class PaimonEntityRenderer extends EntityRenderer<PaimonEntity, PaimonRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/paimon.png");
    private static final float SCALE = 0.65f;

    private final PaimonEntityModel model;

    public PaimonEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new PaimonEntityModel(PaimonEntityModel.getTexturedModelData().createModel());
    }

    @Override
    public PaimonRenderState createRenderState() {
        return new PaimonRenderState();
    }

    @Override
    public void updateRenderState(PaimonEntity entity, PaimonRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.light = this.getLight(entity, tickDelta);
        state.yaw = entity.getLerpedYaw(tickDelta);
        state.pitch = entity.getLerpedPitch(tickDelta);
        state.following = entity.isFollowing();
        state.bob = (float) Math.sin(entity.age * 0.1f) * 0.06f;
    }

    @Override
    public void render(PaimonRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        matrices.translate(0.0f, 1.0f, 0.0f);
        // Лёгкое парение в любом состоянии: Паймон не висит статично.
        matrices.translate(0.0f, state.bob, 0.0f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - state.yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.pitch));
        matrices.scale(-1.0f, -1.0f, 1.0f);
        matrices.scale(SCALE, SCALE, SCALE);
        RenderLayer layer = this.model.getLayer(TEXTURE);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
