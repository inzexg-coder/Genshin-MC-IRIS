package net.teyvat.client.hydro;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.teyvat.entity.HydroSlimeEntity;

/**
 * Рендер Гидро слайма: кастомная модель (два куба 14×10×14 и 10×8×10)
 * в стиле Hoyocraft, squash-and-stretch при прыжках по формуле ванильного рендера.
 */
public class HydroSlimeEntityRenderer extends EntityRenderer<HydroSlimeEntity, HydroSlimeRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/hydro_slime.png");

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
        // Слайм всегда повёрнут лицом к игроку: серверный yaw часто перебивается
        // прыжком (движение по velocity), поэтому считаем поворот на клиенте.
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && !player.isRemoved()
                && entity.squaredDistanceTo(player) < 40.0 * 40.0) {
            double dx = player.getX() - entity.getX();
            double dz = player.getZ() - entity.getZ();
            state.yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
        } else {
            state.yaw = entity.getLerpedYaw(tickDelta);
        }
        float vy = (float) entity.getVelocity().y;
        state.stretch = MathHelper.clamp(vy * 1.1f, 0.0f, 1.0f);
        // Анимация смерти: тело набухает перед взрывом в фонтан воды.
        int deathAnim = entity.getDeathAnimTicks();
        state.scale = deathAnim >= 0
                ? 1.4f + 0.35f * Math.min(deathAnim / 8.0f, 1.0f)
                : 1.4f;
    }

    @Override
    public void render(HydroSlimeRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - state.yaw));
        // Ванильная формула масштаба слайма: при прыжке тело вытягивается вверх.
        float i = state.scale;
        float f = state.stretch / (i * 0.5f + 1.0f);
        float g = 1.0f / (f + 1.0f);
        matrices.scale(g * i, (1.0f / g) * i, g * i);
        RenderLayer layer = RenderLayer.getEntityTranslucent(TEXTURE);
        queue.submitModel(this.model, state, matrices, layer, state.light,
                OverlayTexture.DEFAULT_UV, -1, null, 0, null);
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }
}
