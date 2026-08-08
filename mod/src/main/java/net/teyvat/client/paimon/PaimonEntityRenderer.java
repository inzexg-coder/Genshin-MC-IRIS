package net.teyvat.client.paimon;

import net.minecraft.client.MinecraftClient;
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
    /** Мягкая светящаяся текстура для шлейфа. */
    private static final Identifier TRAIL_GLOW = Identifier.of("teyvat", "textures/misc/trail_glow.png");
    private static final float SCALE = 0.65f;

    /** День — золотой шлейф. */
    private static final float[] COLOR_DAY = {1.0f, 0.92f, 0.70f};
    /** Ночь — голубой шлейф. */
    private static final float[] COLOR_NIGHT = {0.45f, 0.68f, 1.0f};
    /** Закат и рассвет — розовый шлейф. */
    private static final float[] COLOR_DUSK = {1.0f, 0.55f, 0.75f};

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
        // Шлейф: точки пути в локальных координатах относительно ног сущности.
        state.trailBase = entity.getLerpedPos(tickDelta);
        state.trail.clear();
        for (Vec3d point : entity.getTrail()) {
            state.trail.add(point.subtract(state.trailBase));
        }
    }

    @Override
    public void render(PaimonRenderState state, MatrixStack matrices,
                       OrderedRenderCommandQueue queue, CameraRenderState cameraState) {
        // Золотой шлейф рисуем в мировых координатах — ДО трансформаций модели
        // (без отражения и масштаба 0.65), поэтому он лежит точно там, где Паймон
        // реально пролетала, и не зависит от настроек частиц в игре.
        if (state.trailBase != null && !state.trail.isEmpty()) {
            Vec3d camLocal = cameraState.pos.subtract(state.trailBase);
            RenderLayer trailLayer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
            float[] rgb = trailColor();
            queue.submitCustom(matrices, trailLayer, (entry, consumer) ->
                    drawTrailRibbon(entry, consumer, camLocal, state.trail, rgb));
        }
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
        // Мягкое золотое сияние вокруг Паймон: видно всегда, даже когда она висит на месте.
        if (state.trailBase != null) {
            Vec3d camLocal = cameraState.pos.subtract(state.trailBase);
            RenderLayer glowLayer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
            queue.submitCustom(matrices, glowLayer, (entry, consumer) ->
                    drawBillboard(entry, consumer, camLocal, new Vec3d(0.0, 0.45, 0.0), 0.45f, 0.20f));
        }
        super.render(state, matrices, queue, cameraState);
    }

    /** Цвет шлейфа по времени суток: день — золотой, закат/рассвет — розовый, ночь — голубой. */
    private static float[] trailColor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return COLOR_DAY;
        }
        long t = ((client.world.getTimeOfDay() % 24000L) + 24000L) % 24000L;
        if (t >= 13500L && t < 22500L) {
            return COLOR_NIGHT;
        }
        if (t >= 11500L && t < 13500L) {
            return COLOR_DUSK;
        }
        if (t >= 22500L || t < 1000L) {
            return COLOR_DUSK;
        }
        return COLOR_DAY;
    }

    /** Непрерывный шлейф-лента: точки пути соединяются целостной полосой,
     *  повёрнутой к камере, с плавным сужением от хвоста к Паймон.
     *  Длинные сегменты подразбиваются, чтобы лента была гладкой. */
    private static void drawTrailRibbon(MatrixStack.Entry entry, VertexConsumer consumer,
                                        Vec3d camLocal, List<Vec3d> trail, float[] rgb) {
        int count = trail.size();
        if (count < 2) {
            return;
        }
        for (int i = 0; i < count - 1; i++) {
            Vec3d a = trail.get(i);
            Vec3d b = trail.get(i + 1);
            Vec3d seg = b.subtract(a);
            double len = seg.length();
            if (len < 1.0e-5) {
                continue;
            }
            Vec3d dir = seg.multiply(1.0 / len);
            Vec3d right = dir.crossProduct(camLocal.subtract(a));
            if (right.lengthSquared() < 1.0e-6) {
                right = new Vec3d(0.0, 0.0, 1.0);
            } else {
                right = right.normalize();
            }
            int steps = Math.max(1, Math.min(8, (int) Math.ceil(len / 0.12)));
            for (int s = 0; s < steps; s++) {
                double k0 = s / (double) steps;
                double k1 = (s + 1) / (double) steps;
                Vec3d p0 = a.add(seg.multiply(k0));
                Vec3d p1 = a.add(seg.multiply(k1));
                float t0 = (float) ((i + k0) / (count - 1));
                float t1 = (float) ((i + k1) / (count - 1));
                float w0 = 0.05f + 0.25f * t0;
                float w1 = 0.05f + 0.25f * t1;
                float a0 = 0.06f + 0.30f * t0;
                float a1 = 0.06f + 0.30f * t1;
                ribbonQuad(entry, consumer, p0, p1, right, w0, w1, a0, a1, t0, t1, rgb);
            }
        }
    }

    /** Один сегмент ленты: четыре вершины, ширина перпендикулярно направлению и взгляду. */
    private static void ribbonQuad(MatrixStack.Entry entry, VertexConsumer consumer,
                                   Vec3d p0, Vec3d p1, Vec3d right,
                                   float w0, float w1, float a0, float a1,
                                   float u0, float u1, float[] rgb) {
        Vec3d r0 = right.multiply(w0);
        Vec3d r1 = right.multiply(w1);
        vertex(entry, consumer, (float) (p0.x - r0.x), (float) (p0.y - r0.y), (float) (p0.z - r0.z), 0f, u0, a0, rgb);
        vertex(entry, consumer, (float) (p0.x + r0.x), (float) (p0.y + r0.y), (float) (p0.z + r0.z), 1f, u0, a0, rgb);
        vertex(entry, consumer, (float) (p1.x + r1.x), (float) (p1.y + r1.y), (float) (p1.z + r1.z), 1f, u1, a1, rgb);
        vertex(entry, consumer, (float) (p1.x - r1.x), (float) (p1.y - r1.y), (float) (p1.z - r1.z), 0f, u1, a1, rgb);
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
        // Цвет шлейфа по времени суток, прозрачный по краям пути.
        quad(entry, consumer, x, y, z, rx, ry, rz, ux, uy, uz, alpha, COLOR_DAY);
    }

    private static void quad(MatrixStack.Entry entry, VertexConsumer consumer,
                             float x, float y, float z, float rx, float ry, float rz,
                             float ux, float uy, float uz, float alpha, float[] rgb) {
        vertex(entry, consumer, x - rx - ux, y - ry - uy, z - rz - uz, 0f, 0f, alpha, rgb);
        vertex(entry, consumer, x + rx - ux, y + ry - uy, z + rz - uz, 1f, 0f, alpha, rgb);
        vertex(entry, consumer, x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, alpha, rgb);
        vertex(entry, consumer, x - rx + ux, y - ry + uy, z - rz + uz, 0f, 1f, alpha, rgb);
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer consumer,
                               float x, float y, float z, float u, float v, float alpha, float[] rgb) {
        consumer.vertex(entry, x, y, z)
                .color(rgb[0], rgb[1], rgb[2], alpha)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF0, 0xF0)
                .normal(0.0f, 1.0f, 0.0f);
    }

}
