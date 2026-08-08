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
    /** Отступ полосы HP от левого края экрана. */
    private static final int BAR_X = 24;
    /** Нижний край полосы HP (от низа экрана). Над дугой стамины. */
    private static final int BAR_BOTTOM_MARGIN = 78;
    private static final int BAR_W = 152;
    private static final int BAR_H = 12;

    /** Радиус, в котором видны полоски HP противников. */
    private static final double MOB_RANGE = 48.0;
    /** Ширина полоски HP противника. */
    private static final int MOB_BAR_W = 34;
    private static final int MOB_BAR_H = 3;

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

    /** Полоса HP героя: тёмно-синяя панель с золотой окантовкой и красным заполнением. */
    private static void renderPlayerBar(DrawContext context, MinecraftClient client) {
        int h = context.getScaledWindowHeight();
        int y = h - BAR_BOTTOM_MARGIN - BAR_H;
        float health = client.player.getHealth();
        float maxHealth = client.player.getMaxHealth();
        int fill = (int) (BAR_W * Math.max(0f, Math.min(1f, health / maxHealth)));

        // Панель с золотой окантовкой (как заметки путешественника).
        context.fill(BAR_X - 2, y - 2, BAR_X + BAR_W + 2, y + BAR_H + 2, 0xC81B2338);
        context.fill(BAR_X - 1, y - 1, BAR_X + BAR_W + 1, y + BAR_H + 1, 0xCCE8C86A);
        context.fill(BAR_X, y, BAR_X + BAR_W, y + BAR_H, 0xE614202E);

        // Заполнение: красный градиент (светлее сверху).
        if (fill > 0) {
            context.fill(BAR_X, y, BAR_X + fill, y + BAR_H / 2, 0xE6FF6A52);
            context.fill(BAR_X, y + BAR_H / 2, BAR_X + fill, y + BAR_H, 0xE6D93A2E);
        }
        // Тонкая золотая линия по верхнему краю.
        context.fill(BAR_X, y, BAR_X + fill, y + 1, 0xA0FFFFFF);

        TextRenderer tr = client.textRenderer;
        String text = Math.round(health) + "/" + Math.round(maxHealth);
        context.drawText(tr, text, BAR_X + BAR_W + 10, y + (BAR_H - 9) / 2, 0xFFE8C86A, true);
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
                Vec3d head = mob.getLerpedPos(tickDelta).add(0.0, mob.getHeight() + 0.45, 0.0);
                Vec3d screen = project(viewProj, head, sw, sh);
                if (screen == null) {
                    continue;
                }
                float frac = Math.max(0f, Math.min(1f, mob.getHealth() / mob.getMaxHealth()));
                int x0 = (int) (screen.x - MOB_BAR_W / 2.0);
                int y0 = (int) screen.y;
                context.fill(x0 - 1, y0 - 1, x0 + MOB_BAR_W + 1, y0 + MOB_BAR_H + 1, 0xB0000000);
                context.fill(x0, y0, x0 + MOB_BAR_W, y0 + MOB_BAR_H, 0xC01B2338);
                int fillW = (int) (MOB_BAR_W * frac);
                if (fillW > 0) {
                    context.fill(x0, y0, x0 + fillW, y0 + MOB_BAR_H, 0xE6E0453A);
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
