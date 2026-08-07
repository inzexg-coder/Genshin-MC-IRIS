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
 * Всплывающее окно квестов в стиле заметок Тейвата. Выполненное задание —
 * золотая тема с заполненным ромбом и искрой; новое задание — небесно-голубая
 * тема с пустым ромбом (только окантовка, без искры). Если новое задание
 * выполнено сразу, окно превращается в «Задание выполнено» на том же месте,
 * а не появляется новым снизу. Окно компактное: ширина подстраивается под
 * текст, справа отступ от края экрана, чтобы ничего не обрезалось.
 */
public class QuestToast implements Toast {
    /** Сколько миллисекунд окно полностью видно до начала скрытия. */
    private static final long VISIBLE_MS = 3600;
    /** Длительность анимации появления внутренних элементов. */
    private static final long POP_MS = 520;
    /** Минимальная ширина окна (под компактный текст). */
    private static final int MIN_WIDTH = 150;
    /** Высота окна. */
    private static final int HEIGHT = 30;
    /** Отступ от правого края экрана, чтобы окно не прилипало к границе. */
    private static final int RIGHT_MARGIN = 8;

    /** Текущее видимое окно «Новое задание»: при выполнении превращается
     *  в «Задание выполнено» на том же месте, а не добавляется снизу. */
    private static QuestToast activeNewQuest;

    private String title;
    private final String questName;
    /** true = «новое задание»: пустой ромб без искры, небесная тема. */
    private boolean newQuest;
    /** Объявленное задание висит в углу, пока не выполнится (снимается при
     *  замене на «Задание выполнено»). */
    private boolean persistent;
    private int width;
    private long showTime;

    public QuestToast(String title, String questName) {
        this(title, questName, false);
    }

    public QuestToast(String title, String questName, boolean newQuest) {
        this.title = title;
        this.questName = questName;
        this.newQuest = newQuest;
        this.persistent = newQuest;
        if (newQuest) {
            activeNewQuest = this;
        }
        recomputeWidth();
    }

    /** Превращает видимое окно «Новое задание» в «Задание выполнено».
     *  Возвращает true, если замена произошла (новое окно добавлять не нужно). */
    public static boolean replaceActiveNewQuest(String title) {
        QuestToast t = activeNewQuest;
        if (t == null) {
            return false;
        }
        activeNewQuest = null;
        t.title = title;
        t.newQuest = false;
        t.persistent = false;
        t.recomputeWidth();
        t.showTime = 0;
        return true;
    }

    private void recomputeWidth() {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int textW = Math.max(tr.getWidth(title), tr.getWidth(questName));
        this.width = Math.max(MIN_WIDTH, 38 + textW + 8);
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
        // Объявленное задание не исчезает, пока не выполнено.
        if (this.persistent) {
            return Visibility.SHOW;
        }
        return this.showTime >= VISIBLE_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void update(ToastManager manager, long showTime) {
        // На паузе мир стоит — уведомление о квесте тоже замирает.
        if (!MinecraftClient.getInstance().isPaused()) {
            this.showTime = showTime;
        }
        // Окно «Новое задание» отслужило — больше некому превращаться в «выполнено».
        if (newQuest && activeNewQuest == this && this.showTime >= VISIBLE_MS && !persistent) {
            activeNewQuest = null;
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
        float fadeOut = persistent ? 1.0f
                : MathHelper.clamp((VISIBLE_MS + 600 - st) / 600.0f, 0.0f, 1.0f);
        float alpha = fadeIn * fadeOut;
        if (alpha <= 0.01f) {
            return;
        }

        // Темы: выполнено — золото, новое задание — небесная лазурь.
        int panel = newQuest ? 0x14202E : 0x1B2338;
        int border = newQuest ? 0xFF8EC8F0 : 0xFFE8C86A;
        int titleCol = newQuest ? 0xFFD3EAFF : 0xFFE8C86A;
        int nameCol = newQuest ? 0xFFB8CCDA : 0xFFD8D2C4;
        int glowCol = newQuest ? 0xFF8FC0E8 : 0xFFFFD97A;
        int diamondCol = newQuest ? 0xFFB8DCFF : 0xFFE8C86A;

        int panelA = (int) (0xF2 * alpha);
        int accentA = (int) (255 * alpha);
        // Панель в стиле заметок: тёмно-синяя, цветная рамка и акцентная линия.
        context.fill(0, 0, w, h, (panelA << 24) | panel);
        context.fill(0, 0, w, 1, (accentA << 24) | border);
        context.fill(0, h - 1, w, h, (accentA << 24) | border);
        context.fill(0, 0, 1, h, (accentA << 24) | border);
        context.fill(w - 1, 0, w, h, (accentA << 24) | border);
        context.fill(1, 1, w - 1, 2, (accentA << 24) | border);

        int badgeCx = 20;
        int badgeCy = h / 2;
        float badgeScale = easeOutBack(popT);
        float pulse = 0.55f + 0.45f * (float) Math.sin(st / 150.0 * Math.PI * 2.0);
        Matrix3x2fStack m = context.getMatrices();

        // Мягкое свечение позади ромба — пульсирует, пока окно видно.
        m.pushMatrix();
        m.translate(badgeCx, badgeCy);
        m.scale(badgeScale, badgeScale);
        if (newQuest) {
            // Новое задание: пустой ромб — только окантовка, внутри панель.
            int softGlowA = (int) (40.0f * alpha * pulse);
            context.fill(-14, -14, 14, 14, (softGlowA << 24) | glowCol);
            m.rotate((float) Math.PI / 4.0f);
            context.fill(-10, -10, 10, 10, (accentA << 24) | diamondCol);
            context.fill(-8, -8, 8, 8, (panelA << 24) | panel);
        } else {
            int glowA = (int) (70.0f * alpha * pulse);
            context.fill(-14, -14, 14, 14, (glowA << 24) | glowCol);
            context.fill(-10, -10, 10, 10, ((int) (glowA * 1.5f) << 24) | diamondCol);
            // Золотой ромб с тёмной сердцевиной — символ выполненного задания.
            m.rotate((float) Math.PI / 4.0f);
            context.fill(-10, -10, 10, 10, (accentA << 24) | diamondCol);
            context.fill(-8, -8, 8, 8, (panelA << 24) | panel);
        }
        m.popMatrix();

        // Блик в центре ромба — только для выполненного задания.
        // Четырёхлучевая искра, как отблеск грани алмаза: лучи мягко
        // раздуваются, сердцевина вспыхивает в такт (период ~1 с).
        if (!newQuest) {
            float starPulse = 0.8f + 0.2f * (float) Math.sin(st / 1040.0 * Math.PI * 2.0 + 0.7);
            m.pushMatrix();
            m.translate(badgeCx, badgeCy);
            m.scale(badgeScale * starPulse, badgeScale * starPulse);
            int starA = (int) (255 * alpha);
            int starCol = (starA << 24) | 0xFFFFE9A0;
            // Вертикальный луч (ромб, вытянутый вверх-вниз)
            m.pushMatrix();
            m.scale(0.55f, 1.8f);
            m.rotate((float) Math.PI / 4.0f);
            context.fill(-4, -4, 4, 4, starCol);
            m.popMatrix();
            // Горизонтальный луч (ромб, вытянутый влево-вправо)
            m.pushMatrix();
            m.scale(1.8f, 0.55f);
            m.rotate((float) Math.PI / 4.0f);
            context.fill(-4, -4, 4, 4, starCol);
            m.popMatrix();
            // Яркая сердцевина искры — вспыхивает в такт пульсу
            m.pushMatrix();
            m.rotate((float) Math.PI / 4.0f);
            int coreA = (int) (255 * alpha * starPulse);
            context.fill(-2, -2, 2, 2, (coreA << 24) | 0xFFFFFFFF);
            m.popMatrix();
            m.popMatrix();
        }

        // Заголовок и имя задания мягко всплывают снизу.
        float slide = (1.0f - easeOutCubic(popT)) * 4.0f;
        int textA = (int) (255 * alpha);
        context.drawText(textRenderer, this.title, 38, (int) (6 + slide), (textA << 24) | titleCol, true);
        context.drawText(textRenderer, this.questName, 38, (int) (16 + slide), (textA << 24) | nameCol, true);
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
