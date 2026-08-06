package net.teyvat.client.paimon;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Рендер Паймон: трансформации повторяют оригинальный RenderPaimon
 * (подъём на 1 блок, поворот 180-yaw, отражение и масштаб 0.65).
 */
public class PaimonEntityRenderer extends EntityRenderer<PaimonEntity, PaimonRenderState> {
    private static final Identifier TEXTURE = Identifier.of("teyvat", "textures/entity/paimon.png");
    /** Мягкая светящаяся текстура для золотого шлейфа. */
    private static final Identifier TRAIL_GLOW = Identifier.of("teyvat", "textures/misc/trail_glow.png");
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
        // Шлейф: позиции в локальных координатах относительно ног сущности.
        Vec3d base = entity.getLerpedPos(tickDelta);
        state.trailBase = base;
        state.trail.clear();
        for (Vec3d point : entity.getTrail()) {
            state.trail.add(point.subtract(base));
        }
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
        // Золотой шлейф: светящиеся билборды вдоль недавнего пути Паймон.
        if (!state.trail.isEmpty() && state.trailBase != null) {
            Vec3d camLocal = cameraState.pos.subtract(state.trailBase);
            RenderLayer trailLayer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
            queue.submitCustom(matrices, trailLayer, (entry, consumer) ->
                    drawGoldenTrail(entry, consumer, camLocal, state.trail));
        }
        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }

    /** Светящиеся золотые пятна вдоль пути: свежие — ярче и крупнее. */
    private static void drawGoldenTrail(MatrixStack.Entry entry, VertexConsumer consumer,
                                        Vec3d camLocal, List<Vec3d> trail) {
        int count = trail.size();
        for (int i = 0; i < count; i++) {
            float t = (i + 1) / (float) count;
            float size = 0.12f + 0.24f * t;
            float alpha = 0.10f + 0.60f * t;
            drawBillboard(entry, consumer, camLocal, trail.get(i), size, alpha);
        }
    }

    /** Один билборд, всегда повёрнутый к камере. */
    private static void drawBillboard(MatrixStack.Entry entry, VertexConsumer consumer,
                                      Vec3d camLocal, Vec3d p, float size, float alpha) {
        Vec3d dir = camLocal.subtract(p);
        double len = dir.length();
        if (len < 1.0e-4) {
            dir = new Vec3d(0.0, 0.0, 1.0);
            len = 1.0;
        }
        Vec3d right = dir.crossProduct(new Vec3d(0.0, 1.0, 0.0)).normalize();
        if (right.lengthSquared() < 1.0e-6) {
            right = new Vec3d(1.0, 0.0, 0.0);
        }
        Vec3d up = right.crossProduct(dir.multiply(1.0 / len)).normalize();
        float x = (float) p.x, y = (float) p.y, z = (float) p.z;
        float rx = (float) (right.x * size), ry = (float) (right.y * size), rz = (float) (right.z * size);
        float ux = (float) (up.x * size), uy = (float) (up.y * size), uz = (float) (up.z * size);
        // Золото (1.0, 0.84, 0.38) с прозрачностью по краям пути.
        quad(entry, consumer, x, y, z, rx, ry, rz, ux, uy, uz, alpha);
    }

    private static void quad(MatrixStack.Entry entry, VertexConsumer consumer,
                             float x, float y, float z, float rx, float ry, float rz,
                             float ux, float uy, float uz, float alpha) {
        vertex(entry, consumer, x - rx - ux, y - ry - uy, z - rz - uz, 0f, 0f, alpha);
        vertex(entry, consumer, x + rx - ux, y + ry - uy, z + rz - uz, 1f, 0f, alpha);
        vertex(entry, consumer, x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, alpha);
        vertex(entry, consumer, x - rx + ux, y - ry + uy, z - rz + uz, 0f, 1f, alpha);
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer consumer,
                               float x, float y, float z, float u, float v, float alpha) {
        consumer.vertex(entry, x, y, z)
                .color(1.0f, 0.84f, 0.38f, alpha)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF0, 0xF0)
                .normal(0.0f, 1.0f, 0.0f);
    }
}
