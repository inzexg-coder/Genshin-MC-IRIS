package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.teyvat.TeyvatClient;
import net.teyvat.quest.Quests;
import net.teyvat.client.paimon.PaimonManager;
import net.teyvat.client.QuestStateClient;
import net.teyvat.network.PickupRequestPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Подбор предметов на F (как в Genshin): рядом с игроком показываются вкладки
 * до трёх ближайших лежащих предметов (внизу по центру), по F поднимается
 * ближайший. Тап — один предмет, удержание F — повтор каждые несколько тиков.
 * Вкладки исчезают, когда предметы уходят из радиуса.
 */
public final class PickupController {
    /** Радиус показа вкладок и подбора (должен совпадать с ItemPickup.RANGE). */
    private static final double RANGE = 2.8;
    private static final double DOWN = 1.5;
    private static final double UP = 0.6;
    /** Сколько ближайших предметов показывать одновременно. */
    private static final int MAX_TABS = 3;
    /** Повтор подбора при удержании F (тиков на один предмет). */
    private static final int HOLD_REPEAT_TICKS = 4;

    private static final int TAB_H = 20;
    private static final int GAP = 4;

    private static final List<Tab> tabs = new ArrayList<>();
    private static float alpha;
    private static int holdTicks;

    private PickupController() {}

    private static final class Tab {
        final ItemStack stack;
        final double distSq;

        Tab(ItemStack stack, double distSq) {
            this.stack = stack;
            this.distSq = distSq;
        }
    }

    /** Каждый клиентский тик: сканируем землю рядом и обрабатываем F. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.currentScreen != null) {
            tabs.clear();
            holdTicks = 0;
            return;
        }
        scan(client);
        handleKey(client);
    }

    private static void scan(MinecraftClient client) {
        var player = client.player;
        Box box = new Box(player.getX() - RANGE, player.getY() - DOWN, player.getZ() - RANGE,
                player.getX() + RANGE, player.getY() + UP, player.getZ() + RANGE);
        List<ItemEntity> items = new ArrayList<>(client.world.getEntitiesByType(
                TypeFilter.instanceOf(ItemEntity.class), box,
                e -> !e.isRemoved() && !e.cannotPickup() && !e.getStack().isEmpty()));
        items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)));
        tabs.clear();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (int i = 0; i < items.size() && i < MAX_TABS; i++) {
            ItemEntity e = items.get(i);
            double dx = e.getX() - px;
            double dy = e.getY() - py;
            double dz = e.getZ() - pz;
            tabs.add(new Tab(e.getStack().copy(), dx * dx + dy * dy + dz * dz));
        }
    }

    private static void handleKey(MinecraftClient client) {
        // F заблокирован только до того, как Паймон объявила задание про подбор.
        // После объявления (даже если квест ещё не завершён) и после выполнения — F работает.
        // Проверяем isCompleted первым: если квест выполнен (локально или с сервера) — всегда разрешаем.
        if (QuestStateClient.isCompleted(Quests.TRY_PICKUP) || QuestStateClient.isCompleted(Quests.MEET_PAIMON)) {
            // Квест выполнен — F всегда работает, независимо от состояния урока.
        } else if (!PaimonManager.isQuestAnnounced(Quests.TRY_PICKUP)) {
            // Квест ещё не объявлен — блокируем.
            holdTicks = 0;
            return;
        }
        if (TeyvatClient.PICKUP.wasPressed()) {
            sendRequest();
            return;
        }
        if (TeyvatClient.PICKUP.isPressed() && !tabs.isEmpty()) {
            holdTicks++;
            if (holdTicks >= HOLD_REPEAT_TICKS) {
                holdTicks = 0;
                sendRequest();
            }
        } else {
            holdTicks = 0;
        }
    }

    private static void sendRequest() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (tabs.isEmpty() || client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new PickupRequestPayload());
    }

    /** Вкладки предметов внизу по центру (над полосой HP, под диалогом Паймон). */
    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null) {
            return;
        }
        float target = tabs.isEmpty() ? 0.0f : 1.0f;
        alpha += (target - alpha) * 0.25f;
        if (alpha < 0.02f) {
            return;
        }
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int a = (int) (255 * alpha);
        int bottomY = h - 34;
        int dialogueTop = DialogueOverlay.getBoxTop();
        if (dialogueTop > 0 && dialogueTop < bottomY) {
            bottomY = dialogueTop - 6;
        }
        TextRenderer tr = client.textRenderer;
        int n = tabs.size();
        for (int i = 0; i < n; i++) {
            Tab tab = tabs.get(i);
            String text = tab.stack.getName().getString();
            if (tab.stack.getCount() > 1) {
                text += " x" + tab.stack.getCount();
            }
            int textW = tr.getWidth(text);
            int badgeW = 22;
            int tabW = 18 + 6 + textW + 12 + badgeW;
            int x = w / 2 - tabW / 2;
            int y = bottomY - (n - i) * (TAB_H + GAP);
            int panelCol = (a * 0xC2 / 255) << 24 | 0x0E1320;
            int borderCol = (a * 0xDC / 255) << 24 | 0xE8C86A;
            int textCol = (a << 24) | 0xFFE8D9A0;
            int badgeCol = (a << 24) | 0xE8C86A;
            int badgeTextCol = (a << 24) | 0x1B2338;
            context.fill(x, y, x + tabW, y + TAB_H, panelCol);
            context.fill(x, y, x + tabW, y + 1, borderCol);
            context.fill(x, y + TAB_H - 1, x + tabW, y + TAB_H, borderCol);
            context.drawItem(tab.stack, x + 2, y + 2);
            context.drawText(tr, text, x + 20, y + 6, textCol, true);
            int bx = x + tabW - badgeW - 4;
            context.fill(bx, y + 3, bx + badgeW, y + TAB_H - 3, badgeCol);
            context.drawText(tr, "F", bx + (badgeW - tr.getWidth("F")) / 2, y + 6, badgeTextCol, true);
        }
    }
}
