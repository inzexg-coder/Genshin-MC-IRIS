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
    private static final float[] SLASH_COLOR = {0.98f, 1.0f, 1.0f};

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
        // Серпы-дуги ударов: рендерим в любом ракурсе (1-е и 3-е лицо) —
        // ванильный SWEEP_ATTACK-билборд заменён на 3D-дуги.
        renderCrescents(ctx, client);
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

    /** Серпы-дуги в 3D: лента-свет по дуге окружности в плоскости (dir, up).
     *  Ориентация полностью 3D (в отличие от ванильного SWEEP_ATTACK,
     *  который всегда «лежит» горизонтально) — серпы наклоняются по
     *  направлению удара и оборачивают героя плотным шаром хитов. */
    private static void renderCrescents(WorldRenderContext ctx, MinecraftClient client) {
        // Возраст серпов наращивает CombatController.tick (по тикам, а не по
        // кадрам), здесь только рисуем активные.
        Deque<CombatController.Crescent> crescents = CombatController.crescents();
        if (crescents.isEmpty()) {
            return;
        }
        RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(TRAIL_GLOW);
        CameraRenderState camera = client.gameRenderer.getEntityRenderStates().cameraRenderState;
        ctx.commandQueue().submitCustom(ctx.matrices(), layer, (entry, consumer) -> {
            Vec3d cam = camera.pos;
            for (CombatController.Crescent c : crescents) {
                float t = c.age / (float) c.maxAge;
                float alpha = (1f - t) * 0.85f;
                float w = 0.04f + 0.07f * (1f - t);
                int steps = 8;
                for (int i = 0; i < steps; i++) {
                    float k0 = c.start + c.span * (float) i / steps;
                    float k1 = c.start + c.span * (float) (i + 1) / steps;
                    Vec3d p0 = arcPoint(c, k0);
                    Vec3d p1 = arcPoint(c, k1);
                    Vec3d seg = p1.subtract(p0);
                    double slen = seg.length();
                    if (slen < 1.0e-5) {
                        continue;
                    }
                    Vec3d sdir = seg.multiply(1.0 / slen);
                    Vec3d right = sdir.crossProduct(cam.subtract(p0));
                    if (right.lengthSquared() < 1.0e-6) {
                        right = new Vec3d(0.0, 1.0, 0.0);
                    } else {
                        right = right.normalize();
                    }
                    float u0 = (float) i / steps;
                    float u1 = (float) (i + 1) / steps;
                    ribbonQuad(entry, consumer, p0, p1, right, w, w, alpha, alpha, u0, u1);
                }
            }
        });
    }

    /** Точка дуги серпа: center + radius*(cos(t)*dir + sin(t)*up). */
    private static Vec3d arcPoint(CombatController.Crescent c, float t) {
        return c.center.add(c.dir.multiply(c.radius * Math.cos(t)))
                .add(c.up.multiply(c.radius * Math.sin(t)));
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
            Vec3d a = pts[i].pos();
            Vec3d b = pts[i + 1].pos();
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
                float w0 = 0.02f + 0.32f * t0;
                float w1 = 0.02f + 0.32f * t1;
                float a0 = 0.08f + 0.60f * t0;
                float a1 = 0.08f + 0.60f * t1;
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
