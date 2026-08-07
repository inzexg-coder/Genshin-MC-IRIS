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
 * Всплывающее окно «Задание выполнено» в стиле заметок Тейвата:
 * золотой текст, символ выполненного задания в золотом ромбе и
 * анимации появления — ромб «выпрыгивает» с перелётом, светится и
 * пульсирует, текст мягко всплывает, затем окно плавно уезжает.
 * Окно компактное: ширина подстраивается под текст, справа отступ
 * от края экрана, чтобы ничего не обрезалось.
 */
public class QuestToast implements Toast {
    /** Сколько миллисекунд окно полностью видно до начала скрытия. */
    private static final long VISIBLE_MS = 3600;
    /** Длительность анимации появления внутренних элементов. */
    private static final long POP_MS = 520;
    /** Минимальная ширина окна (под компактный текст). */
    private static final int MIN_WIDTH = 184;
    /** Высота окна. */
    private static final int HEIGHT = 40;
    /** Отступ от правого края экрана, чтобы окно не прилипало к границе. */
    private static final int RIGHT_MARGIN = 8;

    private final String title;
    private final String questName;
    private final int width;
    private long showTime;

    public QuestToast(String title, String questName) {
        this.title = title;
        this.questName = questName;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int textW = Math.max(tr.getWidth(title), tr.getWidth(questName));
        this.width = Math.max(MIN_WIDTH, 46 + textW + 10);
    }

    @Override
    public Object getType() {
        return TYPE;
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    /** Позиция справа сверху: при въезде окно скользит от края и останавливается
     *  с отступом RIGHT_MARGIN, поэтому никогда не обрезается краем экрана. */
    @Override
    public float getXPos(int screenWidth, float portion) {
        return screenWidth - this.width * portion - RIGHT_MARGIN * portion;
    }

    @Override
    public Visibility getVisibility() {
        return this.showTime >= VISIBLE_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long showTime) {
        // На паузе мир стоит — уведомление о выполненном задании тоже замирает.
        if (!MinecraftClient.getInstance().isPaused()) {
            this.showTime = showTime;
        }
    }

    @Override
    public SoundEvent getSoundEvent() {
        return SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long showTime) {
        // Замороженное на паузе время: анимации не идут, пока игрок в меню паузы.
        long st = this.showTime;
        int w = getWidth();
        int h = getHeight();

        // Время идёт с момента полного появления окна; поп-анимации — в начале.
        float popT = MathHelper.clamp((float) st / POP_MS, 0.0f, 1.0f);
        float fadeIn = MathHelper.clamp((float) st / 180.0f, 0.0f, 1.0f);
        float fadeOut = MathHelper.clamp((VISIBLE_MS + 600 - st) / 600.0f, 0.0f, 1.0f);
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

        int badgeCx = 24;
        int badgeCy = h / 2;
        float badgeScale = easeOutBack(popT);
        float pulse = 0.55f + 0.45f * (float) Math.sin(st / 150.0 * Math.PI * 2.0);
        Matrix3x2fStack m = context.getMatrices();

        // Мягкое золотое свечение позади ромба — пульсирует, пока окно видно.
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale, badgeScale);
        int glowA = (int) (80.0f * alpha * pulse);
        context.fill(-17, -17, 17, 17, (glowA << 24) | 0xFFFFD97A);
        context.fill(-12, -12, 12, 12, ((int) (glowA * 1.5f) << 24) | 0xFFE8C86A);
        // Золотой ромб с тёмной сердцевиной — символ выполненного задания.
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-12, -12, 12, 12, (goldA << 24) | 0xFFE8C86A);
        context.fill(-10, -10, 10, 10, ((int) (0xF2 * alpha) << 24) | 0x1B2338);
        m.popMatrix();

        // Блик в центре ромба — четырёхлучевая искра, как отблеск грани алмаза.
        // Искра пульсирует медленно и плавно: лучи мягко раздуваются,
        // сердцевина вспыхивает в такт (период ~0.5 с).
        float starPulse = 0.8f + 0.2f * (float) Math.sin(st / 520.0 * Math.PI * 2.0 + 0.7);
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale * starPulse, badgeScale * starPulse);
        int starA = (int) (255 * alpha);
        int starCol = (starA << 24) | 0xFFFFE9A0;
        // Вертикальный луч (ромб, вытянутый вверх-вниз)
        m.pushMatrix();
        m.scale(0.55f, 1.8f);
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-5, -5, 5, 5, starCol);
        m.popMatrix();
        // Горизонтальный луч (ромб, вытянутый влево-вправо)
        m.pushMatrix();
        m.scale(1.8f, 0.55f);
        m.rotate((float) Math.PI / 4.0f);
        context.fill(-5, -5, 5, 5, starCol);
        m.popMatrix();
        // Яркая сердцевина искры — вспыхивает в такт пульсу
        m.pushMatrix();
        m.rotate((float) Math.PI / 4.0f);
        int coreA = (int) (255 * alpha * starPulse);
        context.fill(-2, -2, 2, 2, (coreA << 24) | 0xFFFFFFFF);
        m.popMatrix();
        m.popMatrix();

        // Золотой заголовок и имя задания мягко всплывают снизу.
        float slide = (1.0f - easeOutCubic(popT)) * 5.0f;
        int textA = (int) (255 * alpha);
        context.drawText(textRenderer, this.title, 46, (int) (8 + slide), (textA << 24) | 0xFFE8C86A, true);
        context.drawText(textRenderer, this.questName, 46, (int) (21 + slide), (textA << 24) | 0xFFD8D2C4, true);
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
