package net.teyvat.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/**
 * Всплывающее окно «Задание выполнено» в стиле заметок Тейвата:
 * золотой текст, символ выполненного задания в золотом ромбе и
 * анимации появления — ромб «выпрыгивает» с перелётом, светится и
 * пульсирует, текст мягко всплывает, затем окно плавно уезжает.
 */
public class QuestToast implements Toast {
    /** Сколько миллисекунд окно полностью видно до начала скрытия. */
    private static final long VISIBLE_MS = 3600;
    /** Длительность анимации появления внутренних элементов. */
    private static final long POP_MS = 520;

    private final String title;
    private final String questName;
    private long showTime;

    public QuestToast(String title, String questName) {
        this.title = title;
        this.questName = questName;
    }

    @Override
    public Object getType() {
        return TYPE;
    }

    @Override
    public int getWidth() {
        return 210;
    }

    @Override
    public int getHeight() {
        return 44;
    }

    @Override
    public Visibility getVisibility() {
        return this.showTime >= VISIBLE_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long showTime) {
        this.showTime = showTime;
    }

    @Override
    public SoundEvent getSoundEvent() {
        return SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long showTime) {
        this.showTime = showTime;
        int w = getWidth();
        int h = getHeight();

        // Время идёт с момента полного появления окна; поп-анимации — в начале.
        float popT = MathHelper.clamp((float) showTime / POP_MS, 0.0f, 1.0f);
        float fadeIn = MathHelper.clamp((float) showTime / 180.0f, 0.0f, 1.0f);
        float fadeOut = MathHelper.clamp((VISIBLE_MS + 600 - showTime) / 600.0f, 0.0f, 1.0f);
        float alpha = fadeIn * fadeOut;
        if (alpha <= 0.01f) {
            return;
        }

        // Панель в стиле заметок: тёмно-синяя, золотая рамка и акцентная линия.
        int panelA = (int) (0xF2 * alpha);
        int goldA = (int) (255 * alpha);
        context.fill(0, 0, w, h, (panelA << 24) | 0x1B2338);
        context.fill(0, 0, w, 1, (goldA << 24) | 0xE8C86A);
        context.fill(0, h - 1, w, h, (goldA << 24) | 0xE8C86A);
        context.fill(0, 0, 1, h, (goldA << 24) | 0xE8C86A);
        context.fill(w - 1, 0, w, h, (goldA << 24) | 0xE8C86A);
        context.fill(1, 1, w - 1, 2, (goldA << 24) | 0xE8C86A);

        int badgeCx = 26;
        int badgeCy = h / 2;
        float badgeScale = easeOutBack(popT);
        float pulse = 0.55f + 0.45f * (float) Math.sin(showTime / 150.0 * Math.PI * 2.0);
        Matrix3x2fStack m = context.getMatrices();

        // Мягкое золотое свечение позади ромба — пульсирует, пока окно видно.
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale, badgeScale);
        int glowA = (int) (80.0f * alpha * pulse);
        context.fill(-18, -18, 18, 18, (glowA << 24) | 0xFFFFD97A);
        context.fill(-13, -13, 13, 13, ((int) (glowA * 1.5f) << 24) | 0xFFE8C86A);
        // Золотой ромб с тёмной сердцевиной — символ выполненного задания.
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-13, -13, 13, 13, (goldA << 24) | 0xFFE8C86A);
        context.fill(-11, -11, 11, 11, ((int) (0xF2 * alpha) << 24) | 0x1B2338);
        m.popMatrix();

        // Галочка в центре ромба — крупная, занимает почти весь ромб.
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale * 2.2f, badgeScale * 2.2f);
        String check = "✓";
        int cw = textRenderer.getWidth(check);
        context.drawText(textRenderer, check, -cw / 2, -4, (goldA << 24) | 0xFFE8C86A, true);
        m.popMatrix();

        // Золотой заголовок и имя задания мягко всплывают снизу.
        float slide = (1.0f - easeOutCubic(popT)) * 6.0f;
        int textA = (int) (255 * alpha);
        context.drawText(textRenderer, this.title, 52, (int) (9 + slide), (textA << 24) | 0xFFE8C86A, true);
        context.drawText(textRenderer, this.questName, 52, (int) (23 + slide), (textA << 24) | 0xFFD8D2C4, true);
    }

    /** Затухание с лёгким перелётом за 1.0 (пружинящее появление). */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        return 1.0f + c3 * (float) Math.pow(t - 1.0, 3) + c1 * (float) Math.pow(t - 1.0, 2);
    }

    /** Плавное ускорение к концу. */
    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }
}
