package net.teyvat.client;

import net.minecraft.client.gui.DrawContext;

/** Вспышка в свёрнутом чате при выполнении задания: яркое белое ядро,
 *  оранжевое сияние по панели и расширяющийся ореол — гаснет быстро и плавно. */
public final class ChatFlash {
    private static final int MAX_TICKS = 20;
    private static int ticks;

    private ChatFlash() {}

    /** Запускает вспышку. */
    public static void trigger() {
        ticks = MAX_TICKS;
    }

    public static boolean isActive() {
        return ticks > 0;
    }

    /** 1.0 в начале вспышки, 0.0 в конце. */
    public static float progress() {
        return ticks / (float) MAX_TICKS;
    }

    /** Отрисовка вспышки поверх панели свёрнутого чата; x0..y1 — её прямоугольник. */
    public static void render(DrawContext context, int x0, int y0, int x1, int y1) {
        if (ticks <= 0) {
            return;
        }
        ticks--;
        float t = ticks / (float) MAX_TICKS;
        if (t <= 0.01f) {
            return;
        }
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        int pw = x1 - x0;
        int ph = y1 - y0;

        // Быстрое затухание: первые кадры — яркая вспышка, потом плавно гаснет.
        float fade = t * t;
        // Ореол медленно расширяется наружу, пока сияние гаснет.
        float bloom = 1.0f + (1.0f - t) * 0.9f;
        int bw = (int) (pw * 0.55f * bloom);
        int bh = (int) (ph * 0.55f * bloom);

        // Внешний оранжевый ореол — мягкое свечение за пределами панели.
        int halo = (int) (150.0f * fade);
        context.fill(cx - bw - 10, cy - bh - 10, cx + bw + 10, cy + bh + 10,
                (halo << 24) | 0xFFFF7A00);

        // Основное оранжевое сияние по всей панели (ярче сверху, к низу мягче).
        int glow = (int) (235.0f * fade);
        context.fillGradient(x0, y0, x1, y1,
                (glow << 24) | 0xFFFFA23E,
                ((int) (glow * 0.55f) << 24) | 0xFFFF7A00);

        // Белая сердцевина — самый яркий центр вспышки, гаснет быстрее всего.
        int cw = (int) (pw * 0.30f * bloom);
        int ch = (int) (ph * 0.30f * bloom);
        int core = (int) (255.0f * fade * fade);
        context.fill(cx - cw, cy - ch, cx + cw, cy + ch, (core << 24) | 0xFFFFFFFF);
    }
}
