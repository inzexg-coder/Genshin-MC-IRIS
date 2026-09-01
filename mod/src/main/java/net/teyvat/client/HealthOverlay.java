package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
    /** Полоска HP противника — маленькая, над головой, с тонкой золотой рамкой:
     *  непрозрачная тёмная подложка, чтобы читалась на любом фоне (как в Genshin). */
    private static final int MOB_BAR_W = 26;
    private static final int MOB_BAR_H = 3;
    /** Сколько тиков полоска видна после последней атаки моба (5 секунд). */
    private static final long MOB_BAR_VISIBLE_TICKS = 100;
    /** Затухание полоски перед скрытием, тики. */
    private static final long MOB_BAR_FADE_TICKS = 10;
    /** Попап появления полоски после первой атаки, тики. */
    private static final long MOB_BAR_POP_TICKS = 12;

    /** Время жизни числа урона, тики. */
    private static final long NUMBER_LIFE_TICKS = 70;
    /** Плавное растворение в конце жизни. */
    private static final long NUMBER_FADE_TICKS = 18;
    /** Подъём числа в пикселях за тик. */
    private static final double NUMBER_RISE_PX = 0.55;

    /** Числа урона: id сущности → очередь чисел (снизу вверх, по времени). */
    private static final Map<Integer, ArrayDeque<DamageNumber>> DAMAGE_NUMBERS = new HashMap<>();

    /** Эффект лечения: зелёная заливка + "+x" над полоской. */
    private static float lastHealth = -1f;
    private static float healVisualAmount = 0f;
    private static long healVisualStartTick = 0;
    private static float healVisualMaxHP = 20f;
    /** Длительность зелёной заливки (в тиках, 20 тиков = 1 сек). */
    private static final long HEAL_VISUAL_TICKS = 80;

    private HealthOverlay() {}

    /** Запись всплывающего числа урона. */
    private record DamageNumber(float amount, long startTick) {}

    /** Полоска HP моба: тик первой атаки (попап), тик последней атаки (таймаут)
     *  и уровень моба (для надписи «Ур. X» над полоской). */
    private record MobBarState(long firstHitTick, long lastHitTick, int level) {}
    /** id сущности → состояние её полоски HP (появляется только при атаке). */
    private static final Map<Integer, MobBarState> MOB_BARS = new HashMap<>();

    /** id сущности → уровень моба из синка сервера: подпись «Ур. X» видна
     *  над всеми мобами, даже если их ещё не атаковали. */
    private static final Map<Integer, Integer> MOB_LEVELS = new HashMap<>();

    /** Сервер прислал уровень моба (при загрузке моба или входе игрока). */
    public static void setMobLevel(int entityId, int level) {
        if (level >= 1) {
            MOB_LEVELS.put(entityId, level);
        }
    }

    /** Сервер прислал урон: кладём число в очередь сущности и показываем
     *  полоску HP моба с его уровнем. */
    public static void addDamageNumber(int entityId, float amount, int mobLevel) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        long now = client.world.getTime();
        ArrayDeque<DamageNumber> queue = DAMAGE_NUMBERS.computeIfAbsent(entityId, k -> new ArrayDeque<>());
        if (queue.size() >= 4) {
            queue.pollFirst();
        }
        queue.addLast(new DamageNumber(amount, now));
        // Атака моба: полоска HP появляется попапом и держится, пока бой не затих.
        MOB_BARS.compute(entityId, (k, state) -> state == null
                ? new MobBarState(now, now, mobLevel)
                : new MobBarState(state.firstHitTick(), now, mobLevel));
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

        // --- Эффект лечения ---
        if (lastHealth >= 0f && health > lastHealth) {
            healVisualAmount = health - lastHealth;
            healVisualStartTick = now;
            healVisualMaxHP = maxHealth;
        }
        lastHealth = health;

        float healAlpha = 0f;
        int healTargetEnd = 0;
        if (healVisualAmount > 0f) {
            long elapsed = now - healVisualStartTick;
            if (elapsed < HEAL_VISUAL_TICKS) {
                healAlpha = 1f - (float) elapsed / HEAL_VISUAL_TICKS;
                healTargetEnd = (int) (barW * Math.min(1f, (health) / healVisualMaxHP));
            } else {
                healVisualAmount = 0f;
            }
        }

        // Тёмно-синяя подложка.
        context.fill(-halfW, -halfH, halfW, halfH, withAlpha(0xFF070B14, alpha));

        // Зелёная заливка疗法 (под текущим HP, показывает «куда заполнится»).
        if (healAlpha > 0f && healTargetEnd > fill) {
            int gAlpha = (int) (255 * healAlpha);
            int gTop = (gAlpha << 24) | 0x0040D040;
            int gBot = (gAlpha << 24) | 0x0030B030;
            context.fill(-halfW, -halfH, -halfW + healTargetEnd, 0, withAlpha(gTop, alpha));
            context.fill(-halfW, 0, -halfW + healTargetEnd, halfH, withAlpha(gBot, alpha));
        }

        if (fill > 0) {
            // Заполнение HP.
            context.fill(-halfW, -halfH, -halfW + fill, 0, withAlpha(0xE61B2338, alpha));
            context.fill(-halfW, 0, -halfW + fill, halfH, withAlpha(0xE614202E, alpha));
            // Блик.
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
        // "+x" зелёным при лечении (плавное затухание 4 сек).
        if (healAlpha > 0f && healVisualAmount > 0f) {
            String healText = "+" + Math.round(healVisualAmount);
            int gA = (int) (255 * healAlpha);
            int healColor = (gA << 24) | 0x0040FF40;
            context.drawText(tr, healText, halfW + 10, -14, withAlpha(healColor, alpha), true);
        }
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

    /** Прямая видимость от камеры до точки: полоски HP и числа урона
     *  не просвечивают сквозь блоки, стены и тела игроков. */
    private static boolean hasLineOfSight(MinecraftClient client, Vec3d from, Vec3d to) {
        ClientWorld world = client.world;
        BlockHitResult hit = world.raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
        if (hit.getType() != HitResult.Type.MISS
                && hit.getPos().squaredDistanceTo(from) < to.squaredDistanceTo(from) - 0.001) {
            return false;
        }
        // Тела игроков закрывают обзор. Своё тело — только вне первого лица:
        // от первого лица камера внутри своей коробки и не должна закрывать всё.
        boolean firstPerson = client.options.getPerspective().isFirstPerson();
        for (AbstractClientPlayerEntity player : world.getPlayers()) {
            if (player == client.player && firstPerson) {
                continue;
            }
            if (segmentIntersectsBox(from, to, player.getBoundingBox())) {
                return false;
            }
        }
        return true;
    }

    /** Пересекает ли отрезок [a, b] ось-выровненный прямоугольник box. */
    private static boolean segmentIntersectsBox(Vec3d a, Vec3d b, Box box) {
        double t0 = 0.0;
        double t1 = 1.0;
        Vec3d d = b.subtract(a);
        double[] mins = { box.minX, box.minY, box.minZ };
        double[] maxs = { box.maxX, box.maxY, box.maxZ };
        double[] orig = { a.x, a.y, a.z };
        double[] dir = { d.x, d.y, d.z };
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (orig[axis] < mins[axis] || orig[axis] > maxs[axis]) {
                    return false;
                }
            } else {
                double inv = 1.0 / dir[axis];
                double ta = (mins[axis] - orig[axis]) * inv;
                double tb = (maxs[axis] - orig[axis]) * inv;
                if (ta > tb) {
                    double tmp = ta;
                    ta = tb;
                    tb = tmp;
                }
                t0 = Math.max(t0, ta);
                t1 = Math.min(t1, tb);
                if (t0 > t1) {
                    return false;
                }
            }
        }
        return true;
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
            // Чистим записи атак: мёртвые/исчезнувшие мобы и истёкший таймаут.
            long now = client.world.getTime();
            MOB_BARS.entrySet().removeIf(e -> {
                LivingEntity entity = (LivingEntity) client.world.getEntityById(e.getKey());
                return entity == null || !entity.isAlive()
                        || now - e.getValue().lastHitTick() >= MOB_BAR_VISIBLE_TICKS;
            });
            // Чистим синхронизированные уровни мёртвых/исчезнувших сущностей.
            MOB_LEVELS.entrySet().removeIf(e -> {
                LivingEntity entity = (LivingEntity) client.world.getEntityById(e.getKey());
                return entity == null || !entity.isAlive();
            });
            List<LivingEntity> mobs = client.world.getEntitiesByType(
                    TypeFilter.instanceOf(LivingEntity.class),
                    new Box(camPos.x - MOB_RANGE, camPos.y - MOB_RANGE, camPos.z - MOB_RANGE,
                            camPos.x + MOB_RANGE, camPos.y + MOB_RANGE, camPos.z + MOB_RANGE),
                    e -> e != client.player && !e.isPlayer() && e.isAlive()
                            && !(e instanceof ArmorStandEntity));
            // Точка мирового спавна для клиентского расчёта уровня-фолбэка.
            BlockPos worldSpawn = client.world.getSpawnPoint().getPos();
            for (LivingEntity mob : mobs) {
                MobBarState state = MOB_BARS.get(mob.getId());
                // Уровень: из синка сервера (у всех мобов), затем из пакета урона,
                // а если синка ещё нет — считаем по формуле от спавна мира.
                Integer syncedLevel = MOB_LEVELS.get(mob.getId());
                int level = -1;
                if (syncedLevel != null) {
                    level = syncedLevel;
                } else if (state != null) {
                    level = state.level();
                }
                if (level < 0) {
                    level = fallbackLevel(client, worldSpawn, mob.getBlockPos());
                }
                Vec3d head = mob.getLerpedPos(tickDelta).add(0.0, mob.getHeight() + 0.5, 0.0);
                // Не показываем сквозь блоки и тела игроков.
                if (!hasLineOfSight(client, camPos, head)) {
                    continue;
                }
                Vec3d screen = project(viewProj, head, sw, sh);
                if (screen == null) {
                    continue;
                }
                // Подпись «Название Ур. X» — всегда над всеми мобами (мельче и выше полоски HP).
                if (level >= 0) {
                    drawMobLevelLabel(context, client, screen, mob, level);
                }
                // Полоска появляется попапом только при атаке моба и видна,
                // пока бой не затих.
                if (state == null) {
                    continue;
                }
                float frac = Math.max(0f, Math.min(1f, mob.getHealth() / mob.getMaxHealth()));
                // Попап появления после первой атаки: растёт от центра с «перелётом».
                double pop = Math.min(1.0, (now - state.firstHitTick()) / (double) MOB_BAR_POP_TICKS);
                double scale = 0.7 + 0.3 * pop;
                if (pop > 0.6) {
                    scale += 0.08 * Math.sin((pop - 0.6) / 0.4 * Math.PI);
                }
                float alpha = Math.min(1f, (now - state.firstHitTick()) / 5f);
                // Если атак давно не было — полоска плавно гаснет.
                long fade = MOB_BAR_VISIBLE_TICKS - (now - state.lastHitTick());
                if (fade < MOB_BAR_FADE_TICKS) {
                    alpha *= fade / (float) MOB_BAR_FADE_TICKS;
                }
                if (alpha <= 0.01f) {
                    continue;
                }
                // Рисуем вокруг центра полоски, с масштабом попапа.
                Matrix3x2fStack m = context.getMatrices();
                m.pushMatrix();
                m.translate((int) screen.x, (int) screen.y + MOB_BAR_H / 2);
                m.scale((float) scale, (float) scale);
                int halfW = MOB_BAR_W / 2;
                int halfH = MOB_BAR_H / 2;
                // Мягкая тень за полоской, чтобы отделить её от фона.
                context.fill(-halfW - 2, -halfH - 2, halfW + 2, halfH + 2, withAlpha(0x77000000, alpha));
                // Непрозрачная тёмная кайма.
                context.fill(-halfW - 1, -halfH - 1, halfW + 1, halfH + 1, withAlpha(0xFF0A0F1E, alpha));
                // Тонкая золотая рамка (как в Genshin — аккуратно, без ромбиков).
                context.fill(-halfW - 1, -halfH - 1, halfW + 1, -halfH, withAlpha(0xFFE8C86A, alpha));
                context.fill(-halfW - 1, halfH, halfW + 1, halfH + 1, withAlpha(0xFFE8C86A, alpha));
                context.fill(-halfW - 1, -halfH, -halfW, halfH, withAlpha(0xFFE8C86A, alpha));
                context.fill(halfW, -halfH, halfW + 1, halfH, withAlpha(0xFFE8C86A, alpha));
                context.fill(-halfW, -halfH, halfW, halfH, withAlpha(0xE60B1322, alpha));
                int fillW = (int) ((MOB_BAR_W - 2) * frac);
                if (fillW > 0) {
                    // Полное HP — тёмно-синий как в заметках, с потерей HP голубеет.
                    context.fill(-halfW + 1, -halfH + 1, -halfW + 1 + fillW, halfH, withAlpha(hpBlue(frac), alpha));
                    // Тонкий светлый блик по верхнему краю заполнения.
                    context.fill(-halfW + 1, -halfH + 1, -halfW + 1 + fillW, -halfH + 2, withAlpha(0x55FFFFFF, alpha));
                }
                m.popMatrix();
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
            // Числа урона тоже не просвечивают сквозь блоки и тела игроков.
            Camera cam = client.gameRenderer.getCamera();
            if (!hasLineOfSight(client, cam.getPos(), base)) {
                continue;
            }
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

    /** Подпись моба «Свинья Ур. 5»: имя — светлое, уровень — золотой, с чистым
     *  тонким контуром по диагоналям (как имя врага в Genshin). Контур нужен,
     *  чтобы подпись не сливалась с ярким фоном. Рисуется всегда, пока моб в поле зрения. */
    private static void drawMobLevelLabel(DrawContext context, MinecraftClient client, Vec3d screen, LivingEntity mob, int level) {
        String name = mob.getType().getName().getString();
        String levelText = " Ур. " + level;
        TextRenderer tr = client.textRenderer;
        int w = tr.getWidth(name + levelText);
        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate((int) screen.x, (int) screen.y - 20);
        m.scale(0.7f, 0.7f);
        int x = -w / 2;
        drawOutlinedText(context, tr, name, x, 0, 0xFFF0EBDD);
        drawOutlinedText(context, tr, levelText, x + tr.getWidth(name), 0, 0xFFE8C86A);
        m.popMatrix();
    }

    /** Текст с тонким тёмным контуром: 4 прохода по диагоналям, затем цвет. */
    private static void drawOutlinedText(DrawContext context, TextRenderer tr, String text, int x, int y, int color) {
        int outline = 0xFF05070D;
        for (int ox = -1; ox <= 1; ox += 2) {
            for (int oy = -1; oy <= 1; oy += 2) {
                context.drawText(tr, text, x + ox, y + oy, outline, false);
            }
        }
        context.drawText(tr, text, x, y, color, false);
    }

    /** Клиентский фолбэк уровня: та же формула, что на сервере (расстояние от
     *  мирового спавна). Используется, пока синк уровня ещё не пришёл, чтобы
     *  подпись была у всех мобов без исключения. */
    private static int fallbackLevel(MinecraftClient client, BlockPos worldSpawn, BlockPos pos) {
        try {
            TeyvatConfig.MobLevels cfg = TeyvatConfig.get().mob_levels;
            if (cfg == null || !cfg.enabled) {
                return 1;
            }
            double dist = Math.sqrt(pos.getSquaredDistance(worldSpawn));
            int level = cfg.base + (int) Math.floor(dist * cfg.per_block);
            return Math.max(1, Math.min(cfg.cap, level));
        } catch (Exception e) {
            return 1;
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
