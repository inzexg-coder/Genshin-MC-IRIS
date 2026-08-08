package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/**
 * Уведомление об опыте: без панели-фона — только значок опыта (маленькая
 * золотая сфера с бликом) и текст. Намеренно не похоже на остальные
 * уведомления (задания и заметки с ромбами на тёмных панелях).
 */
public class ProgressionToast implements Toast {
    private static final long VISIBLE_MS = 3000;
    private static final long POP_MS = 520;
    private static final int HEIGHT = 20;
    private static final int RIGHT_MARGIN = 10;

    private final String title;
    private final String subtitle;
    private final int width;
    private long showTime;

    public ProgressionToast(long amount, boolean rankUp) {
        if (rankUp && amount > 0) {
            this.title = "+" + amount + " опыта";
            this.subtitle = "Ранг Приключений повышен!";
        } else if (rankUp) {
            this.title = "Ранг Приключений повышен!";
            this.subtitle = "";
        } else {
            this.title = "+" + amount + " опыта";
            this.subtitle = "";
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int w = tr != null ? tr.getWidth(this.title) : 80;
        this.width = Math.max(130, 26 + w + 14);
    }

    /** Показать уведомление о получении опыта. */
    public static void show(long amount, boolean rankUp) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getToastManager() != null) {
            client.getToastManager().add(new ProgressionToast(amount, rankUp));
        }
    }

    @Override
    public Visibility getVisibility() {
        return this.showTime >= VISIBLE_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long showTime) {
        // На паузе мир стоит — уведомление замирает.
        if (!MinecraftClient.getInstance().isPaused()) {
            this.showTime = showTime;
        }
    }

    @Override
    public SoundEvent getSoundEvent() {
        return SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public float getXPos(int screenWidth, float portion) {
        return screenWidth - this.width * portion - RIGHT_MARGIN * portion;
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long showTime) {
        long st = this.showTime;
        float popT = MathHelper.clamp((float) st / POP_MS, 0.0f, 1.0f);
        float fadeIn = MathHelper.clamp((float) st / 180.0f, 0.0f, 1.0f);
        float fadeOut = MathHelper.clamp((VISIBLE_MS + 600 - st) / 600.0f, 0.0f, 1.0f);
        float alpha = fadeIn * fadeOut;
        if (alpha <= 0.01f) {
            return;
        }

        // Значок опыта: золотая сфера с тёмным ободом и бликом (без панели).
        int iconCx = 9;
        int iconCy = HEIGHT / 2;
        float iconScale = easeOutBack(popT);
        int a = (int) (255 * alpha);
        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate(iconCx, iconCy);
        m.scale(iconScale, iconScale);
        drawOrb(context, 0, 0, 6, (a << 24) | 0xFF9A7B2D);
        drawOrb(context, 0, 0, 4, (a << 24) | 0xFFE8C86A);
        drawOrb(context, -1, -1, 1, (a << 24) | 0xFFFFF3C4);
        m.popMatrix();

        float slide = (1.0f - easeOutCubic(popT)) * 5.0f;
        int textA = (int) (255 * alpha);
        context.drawText(textRenderer, this.title, 22, (int) (4 + slide), (textA << 24) | 0xFFE8C86A, true);
        if (!this.subtitle.isEmpty()) {
            context.drawText(textRenderer, this.subtitle, 22, (int) (14 + slide), (textA << 24) | 0xFFD8D2C4, true);
        }
    }

    /** Маленький круг заливкой по строкам — для значка опыта. */
    private static void drawOrb(DrawContext context, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            context.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1;
        return (float) (1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2));
    }

    private static float easeOutCubic(float t) {
        return 1 - (float) Math.pow(1 - t, 3);
    }
}
