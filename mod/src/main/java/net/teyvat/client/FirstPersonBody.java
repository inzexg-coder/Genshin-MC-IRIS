package net.teyvat.client;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.teyvat.config.TeyvatConfig;

import java.util.Deque;

/**
 * Первое лицо «глазами модельки»: рисует собственное тело (голова скрыта —
 * камера внутри неё) со всеми анимациями ударов/бега и светящуюся дугу-
 * разрез меча (трейл по траектории клинка). Вешается на
 * WorldRenderEvents.BEFORE_ENTITIES — в ту же очередь команд, что и сущности.
 */
public final class FirstPersonBody {
    private static final Identifier TRAIL_GLOW = Identifier.of("teyvat", "textures/misc/trail_glow.png");
    private static final float[] SLASH_COLOR = {1.0f, 1.0f, 1.0f};

    /** true только на время отрисовки собственной модели (миксин прячет голову). */
    private static boolean selfRendering;

    private FirstPersonBody() {}

    /** Включён ли режим «глазами модельки»: конфиг + первое лицо. */
    public static boolean active() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return false;
        }
        return TeyvatConfig.get().camera.first_person_body
                && client.options.getPerspective().isFirstPerson();
    }

    /** true во время отрисовки собственной модели (для миксина PlayerEntityModel). */
    public static boolean selfRendering() {
        return selfRendering;
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Сфера заряда/разлёта и «шерстяная дуга» заряженной атаки.
        renderChargeFx(ctx, client);
        if (!active()) {
            CombatController.clearSlashTrail();
            return;
        }
        ClientPlayerEntity player = client.player;
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        CameraRenderState camera = client.gameRenderer.getEntityRenderStates().cameraRenderState;
        MatrixStack matrices = ctx.matrices();

        // Собственное тело: рендерим модель игрока в его позиции (как сущность),
        // голова скрывается миксином. Позиция ОБЯЗАТЕЛЬНО интерполированная
        // (getLerpedPos), как у камеры: иначе на высоком FPS тело «шагает» по
        // 20 тикам в секунду и дёргается относительно плавной камеры.
        EntityRenderManager erm = client.getEntityRenderDispatcher();
        EntityRenderState state = erm.getAndUpdateRenderState(player, tickDelta);
        Vec3d bodyPos = player.getLerpedPos(tickDelta);
        Vec3d camPos = camera.pos;
        selfRendering = true;
        try {
            erm.render(state, camera,
                    bodyPos.x - camPos.x, bodyPos.y - camPos.y, bodyPos.z - camPos.z,
                    matrices, ctx.commandQueue());
        } finally {
            selfRendering = false;
        }

        // Разрез: пока идёт свинг, семплируем траекторию клинка и рисуем дугу
        // поверх тела — светящийся полумесяц по пути меча. Вне свинга точки
        // стареют и дуга плавно тает.
        sampleTrail(player, tickDelta);
        CombatController.ageSlashTrail();
        Deque<CombatController.SlashPoint> trail = CombatController.slashTrail();
        if (trail.size() >= 2) {
            RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
            ctx.commandQueue().submitCustom(matrices, layer, (entry, consumer) ->
                    drawSlashRibbon(entry, consumer, camera.pos, trail));
        }
    }

    private static void sampleTrail(ClientPlayerEntity player, float tickDelta) {
        CombatController.sampleCurrentSlashTrail(player.getBodyYaw(), player.getLerpedPos(tickDelta));
    }

    /** Растущая светлая сфера В КИСТЯХ, где меч, во время накопления заряда.
     *  Разлёт сферы и «шерстяная дуга» спина рисуются частицами (в tick) —
     *  вокруг камеры ленты не строятся, чтобы ничего не уходило «за облака». */
    private static void renderChargeFx(WorldRenderContext ctx, MinecraftClient client) {
        RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
        CameraRenderState camera = client.gameRenderer.getEntityRenderStates().cameraRenderState;
        ctx.commandQueue().submitCustom(ctx.matrices(), layer, (entry, consumer) -> {
            Vec3d cam = camera.pos;
            CombatController.ChargeSphere cs = CombatController.chargeSphere();
            if (cs != null) {
                drawSphereWireframe(entry, consumer, cam, cs.center().subtract(cam), cs.radius(), cs.alpha(), 6);
            }
        });
    }

    /** Светящаяся «клетка» сферы: кольца в наклонных плоскостях — издалека
     *  читается как светлый шар, растёт с радиусом. */
    private static void drawSphereWireframe(MatrixStack.Entry entry, VertexConsumer consumer,
                                            Vec3d cam, Vec3d center, float radius,
                                            float alpha, int rings) {
        if (radius <= 0.02f || alpha <= 0.01f) {
            return;
        }
        for (int k = 0; k < rings; k++) {
            double az = k * Math.PI * 2.0 / rings;
            float tilt = (float) Math.toRadians((k % 3) * 60.0 - 60.0);
            Vec3d dir = new Vec3d(Math.cos(az), 0, Math.sin(az)).rotateX(tilt);
            Vec3d up = new Vec3d(0, 1, 0).rotateX(tilt);
            drawRingArc(entry, consumer, cam, center, dir, up, radius, alpha);
        }
    }

    /** Перпендикуляр к сегменту ленты, повёрнутый к камере (в camera-space
     *  камера — в начале координат). */
    private static Vec3d ribbonRight(Vec3d sdir, Vec3d p0) {
        Vec3d right = sdir.crossProduct(p0.negate());
        if (right.lengthSquared() < 1.0e-6) {
            return new Vec3d(0.0, 1.0, 0.0);
        }
        return right.normalize();
    }

    /** Кольцо-лента вокруг центра в плоскости (dir, up). */
    private static void drawRingArc(MatrixStack.Entry entry, VertexConsumer consumer,
                                    Vec3d cam, Vec3d center, Vec3d dir, Vec3d up,
                                    float radius, float alpha) {
        int steps = 16;
        float w = 0.07f;
        for (int i = 0; i < steps; i++) {
            float t0 = (float) (Math.PI * 2.0 * i / steps);
            float t1 = (float) (Math.PI * 2.0 * (i + 1) / steps);
            Vec3d p0 = center.add(dir.multiply(radius * Math.cos(t0)))
                    .add(up.multiply(radius * Math.sin(t0)));
            Vec3d p1 = center.add(dir.multiply(radius * Math.cos(t1)))
                    .add(up.multiply(radius * Math.sin(t1)));
            Vec3d seg = p1.subtract(p0);
            double slen = seg.length();
            if (slen < 1.0e-5) {
                continue;
            }
            Vec3d sdir = seg.multiply(1.0 / slen);
            ribbonQuad(entry, consumer, p0, p1, ribbonRight(sdir, p0), w, w, alpha, alpha, 0f, 1f);
        }
    }

    /** Дуга-лента разреза: полоса вдоль траектории клинка, повёрнутая к камере,
     *  сужается и тает к хвосту (как шлейф Паймон). */
    private static void drawSlashRibbon(MatrixStack.Entry entry, VertexConsumer consumer,
                                        Vec3d camLocal, Deque<CombatController.SlashPoint> trail) {
        CombatController.SlashPoint[] pts = trail.toArray(new CombatController.SlashPoint[0]);
        int count = pts.length;
        if (count < 2) {
            return;
        }
        int maxAge = CombatController.slashTrailAge();
        for (int i = 0; i < count - 1; i++) {
            // Координаты относительно камеры (как у сущностей в очереди рендера).
            Vec3d a = pts[i].pos().subtract(camLocal);
            Vec3d b = pts[i + 1].pos().subtract(camLocal);
            Vec3d seg = b.subtract(a);
            double len = seg.length();
            if (len < 1.0e-5) {
                continue;
            }
            Vec3d dir = seg.multiply(1.0 / len);
            Vec3d right = dir.crossProduct(a.negate());
            if (right.lengthSquared() < 1.0e-6) {
                right = new Vec3d(0.0, 0.0, 1.0);
            } else {
                right = right.normalize();
            }
            int steps = Math.max(1, Math.min(8, (int) Math.ceil(len / 0.10)));
            for (int sIdx = 0; sIdx < steps; sIdx++) {
                double k0 = sIdx / (double) steps;
                double k1 = (sIdx + 1) / (double) steps;
                Vec3d p0 = a.add(seg.multiply(k0));
                Vec3d p1 = a.add(seg.multiply(k1));
                float t0 = (float) ((i + k0) / (count - 1));
                float t1 = (float) ((i + k1) / (count - 1));
                // Сужение к хвосту и таяние по возрасту точки.
                float age0 = 1f - pts[i].age() / (float) maxAge;
                float age1 = 1f - pts[i + 1].age() / (float) maxAge;
                float w0 = 0.06f + 0.34f * t0;
                float w1 = 0.06f + 0.34f * t1;
                float a0 = 0.25f + 0.70f * t0;
                float a1 = 0.25f + 0.70f * t1;
                a0 *= age0;
                a1 *= age1;
                ribbonQuad(entry, consumer, p0, p1, right, w0, w1, a0, a1, t0, t1);
            }
        }
    }

    private static void ribbonQuad(MatrixStack.Entry entry, VertexConsumer consumer,
                                   Vec3d p0, Vec3d p1, Vec3d right,
                                   float w0, float w1, float a0, float a1,
                                   float u0, float u1) {
        Vec3d r0 = right.multiply(w0);
        Vec3d r1 = right.multiply(w1);
        vertex(entry, consumer, (float) (p0.x - r0.x), (float) (p0.y - r0.y), (float) (p0.z - r0.z), 0f, u0, a0);
        vertex(entry, consumer, (float) (p0.x + r0.x), (float) (p0.y + r0.y), (float) (p0.z + r0.z), 1f, u0, a0);
        vertex(entry, consumer, (float) (p1.x + r1.x), (float) (p1.y + r1.y), (float) (p1.z + r1.z), 1f, u1, a1);
        vertex(entry, consumer, (float) (p1.x - r1.x), (float) (p1.y - r1.y), (float) (p1.z - r1.z), 0f, u1, a1);
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer consumer,
                               float x, float y, float z, float u, float v, float alpha) {
        consumer.vertex(entry, x, y, z)
                .color(SLASH_COLOR[0], SLASH_COLOR[1], SLASH_COLOR[2], alpha)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF0, 0xF0)
                .normal(0.0f, 1.0f, 0.0f);
    }
}
