package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
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
 * Здоровье как в Genshin: внизу по центру видна полоса HP героя (в ширину
 * диалоговых окон, появляется после обучения с Паймон), над головами
 * противников — полоски их здоровья, а при ударе всплывают числа урона.
 * Всё рисуется поверх мира, панель в стиле заметок Тейвата (тёмно-синяя с золотом).
 */
public final class HealthOverlay {
    /** Полоса HP героя — тонкая, внизу по центру, в ширину диалогового окна. */
    private static final int BAR_H = 4;
    /** Отступ полосы от низа экрана: стоит под окнами диалогов и на одной
     *  высоте с нижними точками дуги стамины (StaminaOverlay.ARC_BOTTOM_OFFSET). */
    private static final int BAR_FROM_BOTTOM = 10;

    /** Радиус, в котором видны полоски HP противников. */
    private static final double MOB_RANGE = 48.0;
    /** Полоска HP противника — маленькая, над головой, в золотой рамке:
     *  в полтора раза короче и вдвое тоньше полосы героя. */
    private static final int MOB_BAR_W = 23;
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

    /** Полоса HP героя: появляется только после завершения обучения с Паймон
     *  и выплывает попапом. Стоит внизу по центру экрана — под окнами диалогов,
     *  в их ширину. Тёмно-синяя как в заметках, с тонкой золотой рамкой. */
    private static boolean barRevealed;
    private static long revealStartTick;
    /** Мир, для которого уже сыгран попап появления: при новом спавне играем снова. */
    private static ClientWorld revealWorld;

    private static void renderPlayerBar(DrawContext context, MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return;
        }
        // Новый мир (первый спавн или перезаход) — попап появления играется заново.
        if (world != revealWorld) {
            revealWorld = world;
            barRevealed = false;
        }
        long now = world.getTime();
        if (!barRevealed) {
            barRevealed = true;
            revealStartTick = now;
        }
        int age = (int) (now - revealStartTick);
        // Попап: полоса вырастает от центра с лёгким «перелётом» и подъёмом вверх.
        double p = Math.min(1.0, age / 14.0);
        double scale = 0.7 + 0.3 * p;
        if (p > 0.6) {
            scale += 0.08 * Math.sin((p - 0.6) / 0.4 * Math.PI);
        }
        float alpha = Math.min(1f, age / 5f);

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int barW = dialogueWidth(w);
        int cx = w / 2;
        // Низ окна диалога на h-18: полоса стоит под ним, у самого низа экрана.
        int cy = h - BAR_FROM_BOTTOM + (int) ((1 - p) * 6);

        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale((float) scale, (float) scale);

        float health = client.player.getHealth();
        float maxHealth = client.player.getMaxHealth();
        int halfW = barW / 2;
        int halfH = BAR_H / 2;
        int fill = (int) (barW * Math.max(0f, Math.min(1f, health / maxHealth)));

        // Тёмно-синяя подложка (глубже цвета панели заметок).
        context.fill(-halfW, -halfH, halfW, halfH, withAlpha(0xFF070B14, alpha));
        if (fill > 0) {
            // Заполнение в цвете заметок: чуть светлее сверху, темнее снизу.
            context.fill(-halfW, -halfH, -halfW + fill, 0, withAlpha(0xE61B2338, alpha));
            context.fill(-halfW, 0, -halfW + fill, halfH, withAlpha(0xE614202E, alpha));
            // Тонкий стальной блик по верхнему краю заполнения.
            context.fill(-halfW, -halfH, -halfW + fill, -halfH + 1, withAlpha(0x50A8C4E8, alpha));
        }

        // Витиеватая золотая рамка: штрихи по верху и низу, сплошные бока.
        for (int x = -halfW; x < halfW; x += 4) {
            context.fill(x, -halfH - 1, Math.min(x + 3, halfW + 1), -halfH, withAlpha(0xDCE8C86A, alpha));
            context.fill(x, halfH, Math.min(x + 3, halfW + 1), halfH + 1, withAlpha(0xDCE8C86A, alpha));
        }
        context.fill(-halfW - 1, -halfH, -halfW, halfH, withAlpha(0xDCE8C86A, alpha));
        context.fill(halfW, -halfH, halfW + 1, halfH, withAlpha(0xDCE8C86A, alpha));

        // Позолота: ромбики по краям и в углах панели.
        drawDiamond(context, -halfW + 3, 0, 2, withAlpha(0xE6E8C86A, alpha));
        drawDiamond(context, halfW - 3, 0, 2, withAlpha(0xE6E8C86A, alpha));
        drawDiamond(context, -halfW + 3, -halfH + 1, 1, withAlpha(0xE6FFE9A0, alpha));
        drawDiamond(context, halfW - 3, -halfH + 1, 1, withAlpha(0xE6FFE9A0, alpha));
        drawDiamond(context, -halfW + 3, halfH - 1, 1, withAlpha(0xE6FFE9A0, alpha));
        drawDiamond(context, halfW - 3, halfH - 1, 1, withAlpha(0xE6FFE9A0, alpha));

        // Число HP — справа от полосы, на её высоте (по центру полосы).
        TextRenderer tr = client.textRenderer;
        String text = Math.round(health) + "/" + Math.round(maxHealth);
        context.drawText(tr, text, halfW + 10, -4, withAlpha(0xFFE8C86A, alpha), true);
        m.popMatrix();
    }

    /** Ширина полосы HP — как ширина текста окна диалога (та же формула). */
    private static int dialogueWidth(int screenW) {
        return Math.max(160, Math.min(440, (int) (screenW * 0.62f) - 44));
    }

    /** Наложить прозрачность на цвет (ARGB). */
    private static int withAlpha(int argb, float alpha) {
        int a = (int) ((argb >>> 24) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Цвет полоски HP противника: тёмно-синий при полном здоровье,
     *  ярко голубеет по мере потери HP (синяя => голубая). */
    private static int hpBlue(float frac) {
        float t = 1f - Math.max(0f, Math.min(1f, frac));
        int r = (int) (0x1B + (0x66 - 0x1B) * t);
        int g = (int) (0x23 + (0xD9 - 0x23) * t);
        int b = (int) (0x38 + (0xFF - 0x38) * t);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
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
                // Золотая рамка с ромбиками по краям — витиеватая позолота.
                context.fill(x0 - 1, y0 - 1, x0 + MOB_BAR_W + 1, y0 + MOB_BAR_H + 1, 0xCCE8C86A);
                context.fill(x0, y0, x0 + MOB_BAR_W, y0 + MOB_BAR_H, 0xCC070B14);
                drawDiamond(context, x0, y0 + MOB_BAR_H / 2, 1, 0xDCE8C86A);
                drawDiamond(context, x0 + MOB_BAR_W, y0 + MOB_BAR_H / 2, 1, 0xDCE8C86A);
                int fillW = (int) ((MOB_BAR_W - 2) * frac);
                if (fillW > 0) {
                    // Полное HP — тёмно-синий как в заметках, с потерей HP голубеет.
                    context.fill(x0 + 1, y0 + 1, x0 + 1 + fillW, y0 + MOB_BAR_H, hpBlue(frac));
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
