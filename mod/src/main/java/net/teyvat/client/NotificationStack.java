package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.Toast;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Стек уведомлений справа сверху, одной колонкой сверху вниз:
 *  задание (его рисует ToastManager), под ним опыт, под опытом — ресурсы.
 *  Ничего не наслаивается: каждый блок знает своё место. */
public final class NotificationStack {
    /** Отступ между блоками колонки. */
    private static final int GAP = 6;
    /** Отступ от правого края экрана. */
    private static final int RIGHT_MARGIN = 10;
    /** Сколько уведомлений ресурсов показывать одновременно. */
    private static final int MAX_RESOURCES = 4;

    private static ProgressionToast expToast;
    private static long expStartMs;
    private static final List<ResourceToast> resources = new ArrayList<>();

    private NotificationStack() {}

    /** Показать уведомление опыта (вместо ToastManager — своя колонка). */
    public static void showExp(long amount, boolean rankUp) {
        expToast = new ProgressionToast(amount, rankUp);
        expStartMs = Util.getMeasuringTimeMs();
        playSound(expToast.getSoundEvent());
    }

    /** Показать уведомление о получении ресурса. */
    public static void showResource(String itemId, int count) {
        if (count <= 0) {
            return;
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return;
        }
        Item item = Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return;
        }
        ItemStack stack = new ItemStack(item, count);
        for (ResourceToast t : resources) {
            if (t.matches(stack)) {
                t.add(count);
                return;
            }
        }
        if (resources.size() >= MAX_RESOURCES) {
            resources.remove(0);
        }
        resources.add(new ResourceToast(stack));
    }

    /** Каждый тик: обновляем таймеры (на паузе всё замирает). */
    public static void tick() {
        long now = Util.getMeasuringTimeMs();
        if (expToast != null) {
            expToast.update(null, now - expStartMs);
            if (expToast.getElapsed() >= ProgressionToast.VISIBLE_MS + 600) {
                expToast = null;
            }
        }
        Iterator<ResourceToast> it = resources.iterator();
        while (it.hasNext()) {
            ResourceToast t = it.next();
            t.update(now);
            if (t.isFinished()) {
                it.remove();
            }
        }
    }

    /** Рисует колонку под уведомлением задания. */
    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        int screenW = context.getScaledWindowWidth();
        float y = QuestToast.isAnyVisible() ? QuestToast.HEIGHT + GAP : 0.0f;
        if (expToast != null) {
            y += drawToast(context, expToast, screenW, y);
        }
        for (ResourceToast t : resources) {
            y += t.draw(context, screenW, y);
        }
    }

    /** Рисует тост (его draw рисует в локальных координатах 0..w) в точке (x, y). */
    private static float drawToast(DrawContext context, Toast toast, int screenW, float y) {
        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate(screenW - toast.getWidth() - RIGHT_MARGIN, y);
        toast.draw(context, MinecraftClient.getInstance().textRenderer, 0);
        m.popMatrix();
        return toast.getHeight() + GAP;
    }

    private static void playSound(SoundEvent sound) {
        if (sound == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0f, 1.0f));
    }
}
