package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.mixin.client.GameRendererAccessor;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Здоровье как в Genshin: слева внизу всегда видна полоса HP героя
 * (над дугой стамины), над головами противников — полоски их здоровья,
 * а при ударе всплывают числа урона. Всё рисуется поверх мира, панель
 * в стиле заметок Тейвата (тёмно-синяя с золотом).
 */
public final class HealthOverlay {
    /** Центр дуги стамины (см. StaminaOverlay: RADIUS 20 + отступ 24). */
    private static final int ARC_CX = 44;
    /** Верх дуги от низа экрана: концы дуги на уровне центра (RADIUS + отступ). */
    private static final int ARC_TOP_FROM_BOTTOM = 44;
    /** Полоса HP — тонкая, по ширине как дуга стамины, прямо над ней. */
    private static final int BAR_W = 44;
    private static final int BAR_H = 6;
    /** Равный зазор между дугой стамины, полосой HP и текстом над ней. */
    private static final int UI_GAP = 10;

    /** Радиус, в котором видны полоски HP противников. */
    private static final double MOB_RANGE = 48.0;
    /** Полоска HP противника — маленькая, над головой. */
    private static final int MOB_BAR_W = 20;
    private static final int MOB_BAR_H = 2;

    /** Время жизни числа урона, тики. */
    private static final long NUMBER_LIFE_TICKS = 70;
    /** Плавное растворение в конце жизни. */
    private static final long NUMBER_FADE_TICKS = 18;
    /** Подъём числа в пикселях за тик. */
    private static final double NUMBER_RISE_PX = 0.55;

    /** Числа урона: id сущности → очередь чисел (снизу вверх, по времени). */
    private static final Map<Integer, ArrayDeque<DamageNumber>> DAMAGE_NUMBERS = new HashMap<>();

    private HealthOverlay() {}

    /** Запись всплывающего числа урона. */
    private record DamageNumber(float amount, long startTick) {}

    /** Сервер прислал урон: кладём число в очередь сущности. */
    public static void addDamageNumber(int entityId, float amount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        ArrayDeque<DamageNumber> queue = DAMAGE_NUMBERS.computeIfAbsent(entityId, k -> new ArrayDeque<>());
        if (queue.size() >= 4) {
            queue.pollFirst();
        }
        queue.addLast(new DamageNumber(amount, client.world.getTime()));
    }

    /** Рисуется поверх мира после HUD-слоя. */
    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.gameRenderer == null) {
            return;
        }
        // В меню интерфейс скрыт, как и ванильный HUD.
        if (client.currentScreen != null) {
            return;
        }
        renderPlayerBar(context, client);
        if (TeyvatConfig.get().health.show_mob_bars || !DAMAGE_NUMBERS.isEmpty()) {
            // Та же проекция, что и у мира (tickDelta как в renderWorld).
            renderWorld(context, client, tickCounter.getTickProgress(true));
        }
    }

    /** Полоса HP героя: тёмно-синяя как в заметках путешественника, с тонкой
     *  золотой узорчатой границей. Композиция слева внизу: дуга стамины,
     *  над ней полоса HP, над ней число — все с одинаковым зазором. */
    private static void renderPlayerBar(DrawContext context, MinecraftClient client) {
        int h = context.getScaledWindowHeight();
        int x0 = ARC_CX - BAR_W / 2;
        int y0 = h - ARC_TOP_FROM_BOTTOM - UI_GAP - BAR_H;
        float health = client.player.getHealth();
        float maxHealth = client.player.getMaxHealth();
        int fill = (int) (BAR_W * Math.max(0f, Math.min(1f, health / maxHealth)));

        // Тёмно-синяя подложка (глубже цвета панели заметок).
        context.fill(x0, y0, x0 + BAR_W, y0 + BAR_H, 0xFF070B14);
        if (fill > 0) {
            // Заполнение в цвете заметок: чуть светлее сверху, темнее снизу.
            context.fill(x0, y0, x0 + fill, y0 + BAR_H / 2, 0xE61B2338);
            context.fill(x0, y0 + BAR_H / 2, x0 + fill, y0 + BAR_H, 0xE614202E);
        }
        // Тонкий стальной блик по верхнему краю заполнения.
        context.fill(x0, y0, x0 + fill, y0 + 1, 0x50A8C4E8);

        // Тонкая золотая узорчатая граница: штрихи по верху и низу, сплошные бока.
        for (int x = x0; x < x0 + BAR_W; x += 4) {
            context.fill(x, y0 - 1, Math.min(x + 3, x0 + BAR_W + 1), y0, 0xDCE8C86A);
            context.fill(x, y0 + BAR_H, Math.min(x + 3, x0 + BAR_W + 1), y0 + BAR_H + 1, 0xDCE8C86A);
        }
        context.fill(x0 - 1, y0, x0, y0 + BAR_H, 0xDCE8C86A);
        context.fill(x0 + BAR_W, y0, x0 + BAR_W + 1, y0 + BAR_H, 0xDCE8C86A);

        // Узоры: маленькие золотые ромбики по краям панели.
        drawDiamond(context, x0 + 3, y0 + BAR_H / 2, 2, 0xE6E8C86A);
        drawDiamond(context, x0 + BAR_W - 3, y0 + BAR_H / 2, 2, 0xE6E8C86A);

        // Число HP — ровно над полосой, с тем же зазором, по той же оси.
        TextRenderer tr = client.textRenderer;
        String text = Math.round(health) + "/" + Math.round(maxHealth);
        context.drawText(tr, text, x0 + (BAR_W - tr.getWidth(text)) / 2, y0 - UI_GAP - 9, 0xFFE8C86A, true);
    }

    /** Цвет полоски HP противника: тёмно-синий при полном здоровье,
     *  голубеет по мере потери HP (синяя => голубая). */
    private static int hpBlue(float frac) {
        float t = 1f - Math.max(0f, Math.min(1f, frac));
        int r = (int) (0x1B + (0x79 - 0x1B) * t);
        int g = (int) (0x23 + (0xB8 - 0x23) * t);
        int b = (int) (0x38 + (0xFF - 0x38) * t);
        return (0xE6 << 24) | (r << 16) | (g << 8) | b;
    }

    /** Маленький ромб-орнамент (повёрнутый квадрат) в стиле Селестии. */
    private static void drawDiamond(DrawContext context, int cx, int cy, int half, int color) {
        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-half, -half, half, half, color);
        m.popMatrix();
    }

    /** Мир: полоски HP противников и числа урона, спроецированные на экран. */
    private static void renderWorld(DrawContext context, MinecraftClient client, float tickDelta) {
        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        int sw = context.getScaledWindowWidth();
        int sh = context.getScaledWindowHeight();

        // Вид: поворот камеры (как у ванили) + сдвиг на позицию камеры.
        Quaternionf q = new Quaternionf(camera.getRotation()).conjugate();
        Matrix4f view = new Matrix4f().rotation(q)
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
        // Та же матрица проекции, что у мира (с учётом зума и FOV игрока).
        float fov = ((GameRendererAccessor) client.gameRenderer)
                .teyvat$callGetFov(camera, tickDelta, true);
        Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);
        Matrix4f viewProj = proj.mul(view, new Matrix4f());

        if (TeyvatConfig.get().health.show_mob_bars) {
            List<LivingEntity> mobs = client.world.getEntitiesByType(
                    TypeFilter.instanceOf(LivingEntity.class),
                    new Box(camPos.x - MOB_RANGE, camPos.y - MOB_RANGE, camPos.z - MOB_RANGE,
                            camPos.x + MOB_RANGE, camPos.y + MOB_RANGE, camPos.z + MOB_RANGE),
                    e -> e != client.player && !e.isPlayer() && e.isAlive()
                            && !(e instanceof ArmorStandEntity));
            for (LivingEntity mob : mobs) {
                Vec3d head = mob.getLerpedPos(tickDelta).add(0.0, mob.getHeight() + 0.5, 0.0);
                Vec3d screen = project(viewProj, head, sw, sh);
                if (screen == null) {
                    continue;
                }
                float frac = Math.max(0f, Math.min(1f, mob.getHealth() / mob.getMaxHealth()));
                int x0 = (int) (screen.x - MOB_BAR_W / 2.0);
                int y0 = (int) screen.y;
                context.fill(x0 - 1, y0 - 1, x0 + MOB_BAR_W + 1, y0 + MOB_BAR_H + 1, 0xB0070B14);
                context.fill(x0, y0, x0 + MOB_BAR_W, y0 + MOB_BAR_H, 0xC014202E);
                int fillW = (int) (MOB_BAR_W * frac);
                if (fillW > 0) {
                    // Полное HP — тёмно-синий как в заметках, с потерей HP голубеет.
                    context.fill(x0, y0, x0 + fillW, y0 + MOB_BAR_H, hpBlue(frac));
                }
            }
        }

        if (TeyvatConfig.get().health.show_damage_numbers && !DAMAGE_NUMBERS.isEmpty()) {
            renderDamageNumbers(context, client, viewProj, tickDelta, sw, sh);
        }
    }

    private static void renderDamageNumbers(DrawContext context, MinecraftClient client,
                                            Matrix4f viewProj, float tickDelta, int sw, int sh) {
        long now = client.world.getTime();
        TextRenderer tr = client.textRenderer;
        Iterator<Map.Entry<Integer, ArrayDeque<DamageNumber>>> it = DAMAGE_NUMBERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ArrayDeque<DamageNumber>> entry = it.next();
            LivingEntity entity = (LivingEntity) client.world.getEntityById(entry.getKey());
            if (entity == null || entity.isRemoved()) {
                it.remove();
                continue;
            }
            Vec3d base = entity.getLerpedPos(tickDelta).add(0.0, entity.getHeight() + 0.55, 0.0);
            Vec3d screen = project(viewProj, base, sw, sh);
            if (screen == null) {
                continue;
            }
            int index = 0;
            Iterator<DamageNumber> numbers = entry.getValue().iterator();
            while (numbers.hasNext()) {
                DamageNumber n = numbers.next();
                long age = now - n.startTick();
                if (age > NUMBER_LIFE_TICKS) {
                    numbers.remove();
                    continue;
                }
                float alpha = 1f;
                if (age > NUMBER_LIFE_TICKS - NUMBER_FADE_TICKS) {
                    alpha = (float) (NUMBER_LIFE_TICKS - age) / NUMBER_FADE_TICKS;
                }
                int rise = (int) (age * NUMBER_RISE_PX);
                int x = (int) (screen.x + (index - 1) * 7.0);
                int y = (int) (screen.y - rise + index * 9.0);
                String text = Integer.toString(Math.round(n.amount()));
                int col = n.amount() >= 250f ? 0xFFFFD966 : 0xFFFFFFFF;
                int a = (int) (255 * alpha);
                col = (a << 24) | (col & 0x00FFFFFF);
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(x, y);
                context.getMatrices().scale(1.25f, 1.25f);
                context.drawText(tr, text, -tr.getWidth(text) / 2, 0, col, true);
                context.getMatrices().popMatrix();
                index++;
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /** Мир → экран. null, если точка за камерой или вне экрана (с запасом). */
    private static Vec3d project(Matrix4f viewProj, Vec3d world, int sw, int sh) {
        Vector4f clip = viewProj.transform(new Vector4f(
                (float) world.x, (float) world.y, (float) world.z, 1.0f));
        if (clip.w <= 0.0001f) {
            return null;
        }
        float nx = clip.x / clip.w;
        float ny = clip.y / clip.w;
        if (nx < -1.3f || nx > 1.3f || ny < -1.3f || ny > 1.3f) {
            return null;
        }
        return new Vec3d((nx * 0.5 + 0.5) * sw, (0.5 - ny * 0.5) * sh, clip.w);
    }
}
