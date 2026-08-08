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
 * Тост прогрессии в стиле заметок: «+X опыта», при повышении ранга —
 * «Ранг Приключений повышен!». Золотая тема с ромбом, как у заданий.
 */
public class ProgressionToast implements Toast {
    private static final long VISIBLE_MS = 3000;
    private static final long POP_MS = 520;
    private static final int HEIGHT = 30;
    private static final int RIGHT_MARGIN = 8;

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
        this.width = Math.max(150, 46 + w + 12);
    }

    /** Показать тост получения опыта. */
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
        // На паузе мир стоит — тост замирает.
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
        int w = getWidth();
        int h = getHeight();
        float popT = MathHelper.clamp((float) st / POP_MS, 0.0f, 1.0f);
        float fadeIn = MathHelper.clamp((float) st / 180.0f, 0.0f, 1.0f);
        float fadeOut = MathHelper.clamp((VISIBLE_MS + 600 - st) / 600.0f, 0.0f, 1.0f);
        float alpha = fadeIn * fadeOut;
        if (alpha <= 0.01f) {
            return;
        }

        int panelA = (int) (0xF2 * alpha);
        int accentA = (int) (255 * alpha);
        context.fill(0, 0, w, h, (panelA << 24) | 0x1B2338);
        context.fill(0, 0, w, 1, (accentA << 24) | 0xFFE8C86A);
        context.fill(0, h - 1, w, h, (accentA << 24) | 0xFFE8C86A);
        context.fill(0, 0, 1, h, (accentA << 24) | 0xFFE8C86A);
        context.fill(w - 1, 0, w, h, (accentA << 24) | 0xFFE8C86A);
        context.fill(1, 1, w - 1, 2, (accentA << 24) | 0xFFE8C86A);

        // Золотой ромб со свечением и пульсом, как у выполненного задания.
        int badgeCx = 20;
        int badgeCy = h / 2;
        float badgeScale = easeOutBack(popT);
        float pulse = 0.55f + 0.45f * (float) Math.sin(st / 150.0 * Math.PI * 2.0);
        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale, badgeScale);
        int glowA = (int) (70.0f * alpha * pulse);
        context.fill(-14, -14, 14, 14, (glowA << 24) | 0xFFFFD97A);
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-10, -10, 10, 10, (accentA << 24) | 0xFFE8C86A);
        context.fill(-8, -8, 8, 8, (panelA << 24) | 0x1B2338);
        m.popMatrix();

        float slide = (1.0f - easeOutCubic(popT)) * 4.0f;
        int textA = (int) (255 * alpha);
        context.drawText(textRenderer, this.title, 38, (int) (6 + slide), (textA << 24) | 0xFFE8C86A, true);
        if (!this.subtitle.isEmpty()) {
            context.drawText(textRenderer, this.subtitle, 38, (int) (16 + slide), (textA << 24) | 0xFFD8D2C4, true);
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
