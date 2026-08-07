package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Диалоговый оверлей в стиле Genshin: реплика рисуется прямо на экране внизу по центру,
 * чуть выше текста — золотой ник говорящего, над ним золотая орнаментика с ромбом.
 * За текстом — лёгкое затемнение, чтобы реплика читалась на любом фоне.
 * Диалоги больше не пишутся в чат: чат остаётся для обычных сообщений.
 */
public final class DialogueOverlay {
    private static final int LINE_H = 14;
    private static final int FADE_IN_TICKS = 6;
    private static final int FADE_OUT_TICKS = 18;

    private static String speaker = "";
    private static String line = "";
    private static int ageTicks;
    /** Счётчик плавного скрытия: -1 = оверлей не затухает. */
    private static int fadeOutTicks = -1;

    private DialogueOverlay() {}

    /** Показать реплику: новая фраза мягко проявляется, окно не пересоздаётся. */
    public static void show(String newSpeaker, String newLine) {
        if (!newSpeaker.equals(speaker) || !newLine.equals(line)) {
            speaker = newSpeaker;
            line = newLine;
            ageTicks = 0;
            fadeOutTicks = -1;
        }
    }

    /** Плавно скрыть оверлей (диалог закончился). */
    public static void end() {
        if (fadeOutTicks < 0) {
            fadeOutTicks = 0;
        }
    }

    /** Каждый клиентский тик (замирает вместе с миром на паузе). */
    public static void tick() {
        if (fadeOutTicks >= 0) {
            fadeOutTicks++;
            if (fadeOutTicks >= FADE_OUT_TICKS) {
                fadeOutTicks = -1;
                line = "";
                speaker = "";
            }
        } else if (!line.isEmpty()) {
            ageTicks++;
        }
    }

    /** Рисуется поверх мира (и поверх HUD), когда активен диалог. */
    public static void render(DrawContext context) {
        if (line == null || line.isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null) {
            return;
        }
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        float alpha = Math.min(1.0f, ageTicks / (float) FADE_IN_TICKS);
        if (fadeOutTicks >= 0) {
            alpha = Math.max(0.0f, 1.0f - fadeOutTicks / (float) FADE_OUT_TICKS);
        }
        if (alpha <= 0.01f) {
            return;
        }
        TextRenderer tr = client.textRenderer;
        int pad = 22;
        int innerW = Math.max(160, Math.min(440, (int) (w * 0.62f) - pad * 2));
        List<String> lines = wrap(line, innerW);
        int textH = lines.size() * LINE_H;
        int nickW = tr.getWidth(speaker);
        int cx = w / 2;
        int textY1 = h - 24;
        int textY0 = textY1 - textH;
        int nickY = textY0 - 16;
        int lineY = nickY - 8;

        // Ширина окна — по самой длинной строке реплики.
        int maxLineW = nickW;
        for (String l : lines) {
            maxLineW = Math.max(maxLineW, tr.getWidth(l));
        }
        int boxW = Math.max(maxLineW, 120) + pad * 2;
        int boxX0 = cx - boxW / 2;
        int boxX1 = cx + boxW / 2;
        int boxY0 = lineY - 12;
        int boxY1 = textY1 + 6;

        // Лёгкое затемнение позади окна диалога — текст читается на любом фоне.
        context.fill(boxX0, boxY0, boxX1, boxY1, withAlpha(0x660B1020, alpha));

        // Золотая орнаментика: линия с ромбом по центру и короткими штрихами по краям.
        int gold = 0xFFE8C86A;
        int half = Math.min(90, maxLineW / 2 + 20);
        context.fill(cx - half, lineY, cx + half, lineY + 1, withAlpha(gold, alpha));
        diamond(context, cx, lineY, 3, withAlpha(0xFFFFF2B8, alpha));
        context.fill(cx - half - 14, lineY - 1, cx - half - 8, lineY + 2, withAlpha(gold, alpha));
        context.fill(cx + half + 8, lineY - 1, cx + half + 14, lineY + 2, withAlpha(gold, alpha));

        // Золотой ник говорящего.
        context.drawText(tr, speaker, cx - nickW / 2, nickY, withAlpha(gold, alpha), true);

        // Текст реплики — с мягкой ванильной тенью, без обводки.
        int ty = textY0;
        for (String l : lines) {
            int lw = tr.getWidth(l);
            context.drawText(tr, l, cx - lw / 2, ty, withAlpha(0xFFF0EADA, alpha), true);
            ty += LINE_H;
        }
    }

    /** Маленький ромб из двух пересекающихся полос. */
    private static void diamond(DrawContext context, int cx, int cy, int r, int color) {
        context.fill(cx, cy - r, cx + 1, cy + r + 1, color);
        context.fill(cx - r, cy, cx + r + 1, cy + 1, color);
    }

    private static int withAlpha(int argb, float alpha) {
        int a = (int) ((argb >>> 24) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Перенос строк по ширине оверлея. */
    private static List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        if (tr.getWidth(text) <= maxWidth) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.isEmpty()) {
                cur.append(word);
            } else if (tr.getWidth(cur + " " + word) <= maxWidth) {
                cur.append(' ').append(word);
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
