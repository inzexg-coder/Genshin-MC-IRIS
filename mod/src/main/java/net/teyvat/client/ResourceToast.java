package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/** Уведомление о получении ресурса: иконка предмета и «+N Название».
 *  Висит 3 секунды, потом плавно тает. Повторный подбор того же предмета
 *  обновляет количество и перезапускает таймер. */
public final class ResourceToast {
    private static final long VISIBLE_MS = 3000;
    private static final long FADE_MS = 600;
    private static final long POP_MS = 320;
    private static final int HEIGHT = 20;
    private static final int RIGHT_MARGIN = 10;
    private static final int GAP = 6;

    private final ItemStack stack;
    private long startMs;
    private long showTime;
    private int width;

    public ResourceToast(ItemStack stack) {
        this.stack = stack.copy();
        this.startMs = Util.getMeasuringTimeMs();
        this.recomputeWidth();
    }

    /** Каждый тик: время идёт, пока игра не на паузе. */
    public void update(long now) {
        if (!MinecraftClient.getInstance().isPaused()) {
            this.showTime = now - this.startMs;
        }
    }

    /** Полностью отслужило (вместе с затуханием). */
    public boolean isFinished() {
        return this.showTime >= VISIBLE_MS + FADE_MS;
    }

    public boolean matches(ItemStack other) {
        return ItemStack.areItemsAndComponentsEqual(this.stack, other);
    }

    /** Дособрали тот же ресурс: +N и таймер заново. */
    public void add(int count) {
        this.stack.increment(count);
        this.startMs = Util.getMeasuringTimeMs();
        this.showTime = 0;
        this.recomputeWidth();
    }

    private void recomputeWidth() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        this.width = Math.max(110, 24 + tr.getWidth(this.text()));
    }

    private String text() {
        return "+" + this.stack.getCount() + " " + this.stack.getName().getString();
    }

    /** Рисует тост на уровне y (верхний левый угол окна) и возвращает высоту шага. */
    public float draw(DrawContext context, int screenW, float y) {
        long st = this.showTime;
        float popT = MathHelper.clamp((float) st / POP_MS, 0.0f, 1.0f);
        float fadeIn = MathHelper.clamp((float) st / 180.0f, 0.0f, 1.0f);
        float fadeOut = MathHelper.clamp((VISIBLE_MS + FADE_MS - st) / (float) FADE_MS, 0.0f, 1.0f);
        float alpha = fadeIn * fadeOut;
        if (alpha > 0.01f) {
            int x = screenW - this.width - RIGHT_MARGIN;
            float slide = (1.0f - easeOutCubic(popT)) * 4.0f;
            int textA = (int) (255 * alpha);
            Matrix3x2fStack m = context.getMatrices();
            m.pushMatrix();
            m.translate(x, y);
            // Иконка предмета и золотой текст «+N Название» без панели-фона.
            context.drawItem(this.stack, 2, 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, this.text(),
                    22, (int) (4 + slide), (textA << 24) | 0xFFE8C86A, true);
            m.popMatrix();
        }
        return HEIGHT + GAP;
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }
}
