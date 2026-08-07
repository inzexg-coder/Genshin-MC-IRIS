package net.teyvat.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix3x2fStack;

/**
 * Дуга выносливости внизу экрана, как в Genshin: полукруг под игроком.
 * Появляется, когда стамина тратится, плавно тает на полной шкале;
 * при низкой стамине дуга мигает и теплеет.
 */
public final class StaminaOverlay {
    /** Радиус дуги в пикселях. */
    private static final double RADIUS = 30;
    /** Толщина дуги. */
    private static final int THICKNESS = 5;
    /** Смещение от нижнего края экрана. */
    private static final int BOTTOM_OFFSET = 46;
    /** Плавность появления/таяния. */
    private static final float FADE_SPEED = 0.08f;
    /** Порог низкой стамины. */
    private static final float LOW_THRESHOLD = 25f;

    private static float alpha;
    private static int pulseTicks;

    private StaminaOverlay() {}

    /** Каждый тик: дуга проявляется при трате и тает на полной шкале. */
    public static void tick(float stamina) {
        pulseTicks++;
        float target = stamina < StaminaController.MAX_STAMINA ? 1f : 0f;
        alpha += (target - alpha) * FADE_SPEED;
        if (alpha < 0.01f && target == 0f) {
            alpha = 0f;
        }
    }

    /** Рисуется поверх мира (после HUD-слоя) — дуга внизу по центру. */
    public static void render(DrawContext context) {
        if (alpha <= 0.01f) {
            return;
        }
        float stamina = StaminaController.getStamina();
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int cx = w / 2;
        int cy = h - BOTTOM_OFFSET;
        boolean low = stamina < LOW_THRESHOLD;
        // Низкая стамина: дуга мигает и становится тёплой.
        float pulse = low ? 0.6f + 0.4f * (float) Math.sin(pulseTicks / 90.0 * Math.PI * 2.0) : 1f;
        int a = (int) (255 * alpha * pulse);
        int fillCol = low ? ((a << 24) | 0xFFE88A5A) : ((a << 24) | 0xFFE8C86A);
        int trackCol = ((int) (70 * alpha) << 24) | 0x1B2338;
        double filled = MathHelper.clamp(stamina / StaminaController.MAX_STAMINA, 0.0, 1.0);

        // Полукруг снизу: слева (180°) через низ (270°) вправо (360°).
        Matrix3x2fStack m = context.getMatrices();
        int segs = 90;
        for (int seg = 0; seg < segs; seg++) {
            double t = (seg + 0.5) / segs;
            float angle = (float) Math.toRadians(180 + (seg + 0.5) * (180.0 / segs));
            int col = t <= filled ? fillCol : trackCol;
            m.pushMatrix();
            m.translate(cx, cy);
            m.rotate(angle);
            context.fill((int) (RADIUS - 2), -THICKNESS / 2, (int) (RADIUS + 2), (THICKNESS + 1) / 2, col);
            m.popMatrix();
        }
    }
}
