package net.teyvat.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.SlabBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.teyvat.TeyvatBlocks;
import net.teyvat.network.TeleportActivatePayload;

import java.util.HashSet;
import java.util.Set;

/**
 * Клиентская часть активации точек телепортации.
 * Хранит локальный набор активированных позиций и заменяет красные блоки
 * на синие только на стороне этого клиента — поэтому у каждого игрока
 * своя «картина мира».
 */
public final class TeleportActivationClient {
    private static final int INTERACT_RANGE = 5;
    private static final int HINT_RANGE = 7;
    private static final int SCAN_RANGE = 12;

    /** Активированные позиции (запакованные long). */
    private static final Set<Long> activatedPositions = new HashSet<>();

    /** Ближайшая неактивированная красная плита (для подсказки). */
    private static BlockPos nearestRedSlab = null;

    /** Таймер всплывающего уведомления. */
    private static int notificationTimer = 0;
    private static final int NOTIFICATION_TICKS = 80;

    /** Плавность появления/исчезновения подсказки. */
    private static float hintAlpha = 0.0f;

    /** Таймер анимации активации (тики обратного отсчёта). */
    private static int animationTimer = 0;
    private static final int ANIMATION_TICKS = 30;

    /** Таймер для повторного нанесения синих блоков (после загрузки чанков). */
    private static int reapplyTimer = 0;

    private TeleportActivationClient() {}

    // ──────────────────── state ────────────────────

    public static void setActivatedPositions(Set<BlockPos> positions) {
        // Находим новые позиции (которых ещё не было)
        Set<Long> old = new HashSet<>(activatedPositions);
        activatedPositions.clear();
        boolean hasNew = false;
        for (BlockPos pos : positions) {
            long packed = pos.asLong();
            activatedPositions.add(packed);
            if (!old.contains(packed)) {
                hasNew = true;
                // Запускаем анимацию для новой точки
                animationTimer = ANIMATION_TICKS;
                swapToBlue(pos);
            }
        }
        if (!hasNew && !activatedPositions.isEmpty()) {
            // Полная перезаливка (при входе в мир)
            applyAllSwaps();
        }
        // Уведомление о новой активации (на экране)
        if (hasNew) {
            notificationTimer = NOTIFICATION_TICKS;
        }
    }

    public static void addActivatedPosition(BlockPos pos) {
        if (activatedPositions.add(pos.asLong())) {
            animationTimer = ANIMATION_TICKS;
            swapToBlue(pos);
        }
    }

    public static boolean isActivated(BlockPos pos) {
        return activatedPositions.contains(pos.asLong());
    }

    // ──────────────────── block swapping ────────────────────

    /**
     * Заменить блоки колонны + плиту с красных на синие (только на клиенте).
     * Структура: плита на pos, основание +1, ствол +2, капитель +3.
     */
    private static void swapToBlue(BlockPos slabPos) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;

        // Плита — сохраняем тип (bottom/top/double)
        swapSlab(world, slabPos);
        // Колонна
        swapSimple(world, slabPos.up(1), TeyvatBlocks.TELEPORT_COLUMN_BASE_BLUE);
        swapSimple(world, slabPos.up(2), TeyvatBlocks.TELEPORT_COLUMN_SHAFT_BLUE);
        swapSimple(world, slabPos.up(3), TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_BLUE);
    }

    private static void swapSlab(ClientWorld world, BlockPos pos) {
        var current = world.getBlockState(pos);
        var swapped = TeyvatBlocks.TELEPORT_SLAB_BLUE.getDefaultState();
        // Сохраняем тип полублока
        if (current.contains(SlabBlock.TYPE) && swapped.contains(SlabBlock.TYPE)) {
            swapped = swapped.with(SlabBlock.TYPE, current.get(SlabBlock.TYPE));
        }
        world.setBlockState(pos, swapped, 0);
    }

    private static void swapSimple(ClientWorld world, BlockPos pos, net.minecraft.block.Block target) {
        world.setBlockState(pos, target.getDefaultState(), 0);
    }

    /** Применить замены ко всем активированным позициям. */
    private static void applyAllSwaps() {
        for (long packed : activatedPositions) {
            swapToBlue(BlockPos.fromLong(packed));
        }
    }

    /** Периодическая проверка: если чанк перезагрузился, красные блоки вернулись — восстанавливаем синие. */
    private static void reapplyIfNeeded() {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) return;

        for (long packed : activatedPositions) {
            BlockPos pos = BlockPos.fromLong(packed);
            var state = world.getBlockState(pos);
            if (state.isOf(TeyvatBlocks.TELEPORT_SLAB_RED)) {
                swapToBlue(pos);
            }
        }
    }

    // ──────────────────── detection ────────────────────

    private static BlockPos findNearestRedSlab(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -5; dy <= 8; dy++) {
                for (int dz = -SCAN_RANGE; dz <= SCAN_RANGE; dz++) {
                    BlockPos check = playerPos.add(dx, dy, dz);
                    var state = client.world.getBlockState(check);
                    // Ищем КРАСНУЮ плиту (неактивированную)
                    if (state.isOf(TeyvatBlocks.TELEPORT_SLAB_RED) && !isActivated(check)) {
                        double dist = playerPos.getSquaredDistance(check);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = check;
                        }
                    }
                }
            }
        }
        return best;
    }

    // ──────────────────── registration ────────────────────

    /** Состояние клавиши Q для отслеживания нажатия (vanilla Q = drop item消費了wasPressed). */
    private static boolean qWasDown = false;

    /** Вызывается из TeyvatClient.onInitializeClient(). */
    public static void init() {
        try { init0(); } catch (Exception e) {
            net.teyvat.TeyvatMod.LOGGER.error("TeleportActivationClient init failed", e);
        }
    }
    private static void init0() {
        // === Tick-обработчик ===
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Периодическое повторное нанесение синих блоков (раз в 40 тиков)
            reapplyTimer++;
            if (reapplyTimer >= 40) {
                reapplyTimer = 0;
                reapplyIfNeeded();
            }

            // Поиск ближайшей красной плиты
            nearestRedSlab = findNearestRedSlab(client);

            // Прямая проверка GLFW: Q нажата → отправка запроса на сервер.
            // Нельзя использовать KeyBinding.wasPressed() — vanilla DROP_ITEM на Q
            // потребляет нажатие до нашего обработчика.
            long window = client.getWindow().getHandle();
            boolean qDown = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_Q)
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean qJustPressed = qDown && !qWasDown;
            qWasDown = qDown;

            if (qJustPressed && nearestRedSlab != null) {
                if (!isActivated(nearestRedSlab)
                        && client.player.getBlockPos().isWithinDistance(nearestRedSlab, INTERACT_RANGE)) {
                    ClientPlayNetworking.send(new TeleportActivatePayload(nearestRedSlab));
                }
            }

            // Таймер анимации
            if (animationTimer > 0) {
                animationTimer--;
            }
            // Таймер уведомления
            if (notificationTimer > 0) {
                notificationTimer--;
            }

            // Периодические частицы свечения на активированных синих плитах
            if (client.world.getTime() % 20 == 0) {
                spawnAmbientGlow(client);
            }
        });

        // HUD рендер теперь через InGameHudMixin (HudRenderCallback не работает — mixin отменяет vanilla HUD).
    }

    // ──────────────────── HUD rendering ────────────────────

    /** Вызывается из InGameHudMixin — подсказка Q + анимация активации + уведомление. */
    public static void renderHint(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        // Подсказка [Q] при приближении к неактивированной точке
        if (nearestRedSlab != null
                && client.player.getBlockPos().isWithinDistance(nearestRedSlab, HINT_RANGE)
                && !client.player.isSneaking()
                && client.currentScreen == null) {
            renderHintTab(ctx, client);
        }

        // Анимация: синяя вспышка на экране при активации
        if (animationTimer > 0) {
            renderActivationFlash(ctx, client);
        }

        // Всплывающее уведомление об активации
        if (notificationTimer > 0) {
            renderNotification(ctx, client);
        }
    }

    private static void renderHintTab(DrawContext ctx, MinecraftClient client) {
        TextRenderer tr = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        // Плавное появление/исчезновение
        float target = nearestRedSlab != null ? 1.0f : 0.0f;
        hintAlpha += (target - hintAlpha) * 0.2f;
        if (hintAlpha < 0.02f) return;

        int a = (int) (255 * hintAlpha);

        // Вкладка в стиле подбора предметов (внизу по центру)
        String label = "Активировать";
        String badge = "Q";
        int textW = tr.getWidth(label);
        int badgeW = 22;
        int tabW = 18 + 6 + textW + 12 + badgeW;
        int tabH = 20;
        int x = sw / 2 - tabW / 2;
        int bottomY = sh - 34;
        int y = bottomY - tabH - 4;

        // Фон вкладки (тёмно-синий с золотой рамкой)
        int panelCol = (a * 0xC2 / 255) << 24 | 0x0E1320;
        int borderCol = (a * 0xDC / 255) << 24 | 0xE8C86A;
        int textCol = (a << 24) | 0xFFE8D9A0;
        int badgeCol = (a << 24) | 0xE8C86A;
        int badgeTextCol = (a << 24) | 0x1B2338;

        // Панель
        ctx.fill(x, y, x + tabW, y + tabH, panelCol);
        ctx.fill(x, y, x + tabW, y + 1, borderCol);
        ctx.fill(x, y + tabH - 1, x + tabW, y + tabH, borderCol);
        // Иконка (маленький квадрат — символ телепорта)
        ctx.fill(x + 4, y + 4, x + 14, y + 16, (a << 24) | 0x44AAFF);
        ctx.fill(x + 6, y + 6, x + 12, y + 14, (a << 24) | 0x88DDFF);
        // Текст
        ctx.drawText(tr, label, x + 20, y + 6, textCol, true);
        // Бейдж [Q]
        int bx = x + tabW - badgeW - 4;
        ctx.fill(bx, y + 3, bx + badgeW, y + tabH - 3, badgeCol);
        ctx.drawText(tr, badge, bx + (badgeW - tr.getWidth(badge)) / 2, y + 6, badgeTextCol, true);
    }

    /** Всплывающее уведомление внизу экрана. */
    private static void renderNotification(DrawContext ctx, MinecraftClient client) {
        float progress = (float) notificationTimer / NOTIFICATION_TICKS;
        float alpha;
        if (notificationTimer > NOTIFICATION_TICKS - 15) {
            // Появление
            alpha = (float)(NOTIFICATION_TICKS - notificationTimer) / 15f;
        } else if (notificationTimer < 20) {
            // Затухание
            alpha = (float) notificationTimer / 20f;
        } else {
            alpha = 1.0f;
        }
        alpha = Math.max(0, Math.min(1, alpha));

        TextRenderer tr = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        String msg = "✦ Точка телепортации активирована!";
        int textW = tr.getWidth(msg);
        int a = (int)(alpha * 255);

        int x = (sw - textW) / 2;
        int y = sh / 2 + 55;

        // Фон
        int pad = 6;
        ctx.fill(x - pad, y - pad, x + textW + pad, y + tr.fontHeight + pad,
                (int)(alpha * 180) << 24 | 0x0E1320);
        // Золотая рамка
        ctx.fill(x - pad, y - pad, x + textW + pad, y - pad + 1,
                (int)(alpha * 220) << 24 | 0xE8C86A);
        ctx.fill(x - pad, y + tr.fontHeight + pad - 1, x + textW + pad, y + tr.fontHeight + pad,
                (int)(alpha * 220) << 24 | 0xE8C86A);
        // Текст
        ctx.drawTextWithShadow(tr, Text.literal(msg), x, y,
                (a << 24) | 0x44DDFF);
    }

    private static void renderActivationFlash(DrawContext ctx, MinecraftClient client) {
        float progress = (float) animationTimer / ANIMATION_TICKS;
        // Начинается ярко → затухает
        float alpha = progress * progress; // квадратичное затухание
        int a = (int) (alpha * 60); // максимальная прозрачность ~23%
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        // Синяя вспышка
        ctx.fill(0, 0, sw, sh, (a << 24) | 0x0044AAFF);

        // Белая полоса в центре (первые 10 тиков)
        if (animationTimer > ANIMATION_TICKS - 10) {
            float lineAlpha = (float) (animationTimer - (ANIMATION_TICKS - 10)) / 10;
            int la = (int) (lineAlpha * 80);
            int lineH = 4;
            ctx.fill(0, sh / 2 - lineH, sw, sh / 2 + lineH, (la << 24) | 0x00FFFFFF);
        }
    }

    /** Мягкие частицы свечения вокруг активированных синих блоков (ambient). */
    private static void spawnAmbientGlow(MinecraftClient client) {
        try { spawnAmbientGlow0(client); } catch (Exception ignored) {}
    }
    private static void spawnAmbientGlow0(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        BlockPos playerPos = client.player.getBlockPos();

        for (long packed : activatedPositions) {
            BlockPos pos = BlockPos.fromLong(packed);
            if (!playerPos.isWithinDistance(pos, 16)) continue;
            var state = client.world.getBlockState(pos);
            if (!state.isOf(TeyvatBlocks.TELEPORT_SLAB_BLUE)) continue;

            // Мягкие искры enchant над колонной
            for (int dy = 0; dy <= 3; dy++) {
                client.world.addParticleClient(ParticleTypes.ENCHANT,
                        pos.getX() + 0.5 + (client.world.random.nextDouble() - 0.5) * 1.2,
                        pos.getY() + dy + client.world.random.nextDouble(),
                        pos.getZ() + 0.5 + (client.world.random.nextDouble() - 0.5) * 1.2,
                        0.0, 0.01, 0.0);
            }
        }
    }
}
