package net.teyvat.client.hydro;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.teyvat.entity.HydroSlimeProjectileEntity;

/** Рендер водяного шара: полупрозрачная сфера-билборд, всегда лицом к камере. */
public class HydroSlimeProjectileRenderer extends EntityRenderer<HydroSlimeProjectileEntity, HydroSlimeProjectileRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/hydro_slime_projectile.png");
    private static final float SCALE = 0.5f;

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
    }

    @Override
    public void render(HydroSlimeProjectileRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        // Билборд: квадрат поворачивается к камере, как частица (сфера всегда круглая).
        matrices.multiply(cameraState.orientation);
        matrices.scale(SCALE, SCALE, SCALE);
        // Без culling: плоский квадрат должен быть виден с обеих сторон.
        RenderLayer layer = RenderLayer.getEntityTranslucent(TEXTURE, false);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
