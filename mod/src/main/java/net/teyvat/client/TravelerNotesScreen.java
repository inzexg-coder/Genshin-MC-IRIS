package net.teyvat.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

import static net.teyvat.client.TravelerNotesContent.*;

/** «Заметки путешественника»: полная информация о моде, доступна в любом режиме игры. */
public class TravelerNotesScreen extends Screen {
    private static final int SIDEBAR_W = 172;
    private static final int PAD = 12;
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 26;
    private static final int LINE_H = 11;
    private static final int CH_H = 24; // высота кнопки главы

    private final List<Chapter> chapters = build();
    private int selected = 0;

    private int contentX0;
    private int contentX1;
    private int contentY0;
    private int contentY1;
    private int contentW;
    private int viewH;

    private final List<Line> renderLines = new ArrayList<>();
    private int contentH = 0;
    private double scroll = 0;
    private boolean dragging = false;

    private record Wrapped(String text, int color) {}

    public TravelerNotesScreen() {
        super(Text.literal("Заметки путешественника"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        contentX0 = SIDEBAR_W + 16;
        contentX1 = this.width - PAD - 8;
        contentY0 = HEADER_H + 8;
        contentY1 = this.height - FOOTER_H - 4;
        contentW = Math.max(120, contentX1 - contentX0 - 14);
        viewH = contentY1 - contentY0;
        rebuild();
    }

    private void rebuild() {
        renderLines.clear();
        Chapter ch = chapters.get(selected);
        renderLines.add(new Line(ch.title(), C_HEADER, false));
        renderLines.add(new Line("", C_BODY, false));
        for (Line l : ch.lines()) {
            renderLines.add(l);
        }
        contentH = renderLines.size() * LINE_H;
        clampScroll();
    }

    private void clampScroll() {
        double max = Math.max(0, contentH - viewH);
        scroll = Math.max(0, Math.min(scroll, max));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= verticalAmount * 18;
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        // Кнопки глав в сайдбаре
        for (int i = 0; i < chapters.size(); i++) {
            int y = contentY0 + i * CH_H;
            if (mouseX >= 8 && mouseX <= SIDEBAR_W - 4 && mouseY >= y && mouseY < y + CH_H - 4) {
                selected = i;
                scroll = 0;
                rebuild();
                return true;
            }
        }
        // Скроллбар
        int sbX = contentX1 + 4;
        if (button == 0 && mouseX >= sbX && mouseX <= sbX + 5 && mouseY >= contentY0 && mouseY <= contentY1) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            dragging = false;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (dragging) {
            scroll += deltaY * (contentH / (double) Math.max(1, viewH));
            clampScroll();
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyInput key) {
        switch (key.key()) {
            case 264: // Down
                scroll += 18;
                clampScroll();
                return true;
            case 265: // Up
                scroll -= 18;
                clampScroll();
                return true;
            case 266: // PageUp
                scroll -= viewH;
                clampScroll();
                return true;
            case 267: // PageDown
                scroll += viewH;
                clampScroll();
                return true;
            case 268: // Home
                scroll = 0;
                return true;
            case 269: // End
                scroll = Double.MAX_VALUE;
                clampScroll();
                return true;
            default:
                return super.keyPressed(key);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xEE0F1420);

        // Заголовок
        context.fill(0, 0, this.width, HEADER_H, 0xF21B2338);
        context.fill(0, HEADER_H, this.width, HEADER_H + 1, 0xFF3A4A6A);
        String title = "「Заметки путешественника」";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2,
                (HEADER_H - 9) / 2, C_GOLD, true);
        String ver = "Teyvat 0.8.8";
        context.drawText(this.textRenderer, ver, this.width - this.textRenderer.getWidth(ver) - 10,
                (HEADER_H - 9) / 2, C_HINT, true);

        // Сайдбар с главами
        context.fill(0, HEADER_H + 1, SIDEBAR_W, this.height, 0xEE141A2A);
        for (int i = 0; i < chapters.size(); i++) {
            int y = contentY0 + i * CH_H;
            boolean sel = i == selected;
            boolean hover = mouseX >= 8 && mouseX <= SIDEBAR_W - 4 && mouseY >= y && mouseY < y + CH_H - 4;
            int bg = sel ? 0xFF3A4A6A : (hover ? 0xFF222C44 : 0x00000000);
            context.fill(8, y, SIDEBAR_W - 4, y + CH_H - 4, bg);
            if (sel) {
                context.fill(8, y, 10, y + CH_H - 4, C_GOLD);
            }
            String t = chapters.get(i).title();
            int color = sel ? C_GOLD : (hover ? 0xFFE8E4D8 : C_HINT);
            context.drawText(this.textRenderer, t, 16, y + 6, color, true);
        }
        context.fill(SIDEBAR_W, HEADER_H + 1, SIDEBAR_W + 1, this.height, 0xFF3A4A6A);

        // Контент с прокруткой
        context.enableScissor(contentX0, contentY0, contentX1 - contentX0, contentY1 - contentY0);
        for (int i = 0; i < renderLines.size(); i++) {
            int ly = contentY0 + 4 + i * LINE_H - (int) scroll;
            if (ly > contentY1 - LINE_H) {
                break;
            }
            Line l = renderLines.get(i);
            if (l.text().isEmpty()) {
                continue;
            }
            if (l.wrap()) {
                for (Wrapped w : wrap(l.text(), contentW, l.color())) {
                    if (ly + LINE_H >= contentY0 && ly <= contentY1) {
                        context.drawText(this.textRenderer, w.text(), contentX0, ly, w.color(), true);
                    }
                    ly += LINE_H;
                }
            } else {
                context.drawText(this.textRenderer, l.text(), contentX0, ly, l.color(), true);
            }
        }
        context.disableScissor();

        // Скроллбар
        int sbX = contentX1 + 4;
        context.fill(sbX, contentY0, sbX + 5, contentY1, 0x66222C44);
        if (contentH > viewH) {
            int thumbH = Math.max(18, viewH * viewH / contentH);
            int thumbY = contentY0 + (int) (scroll / (contentH - viewH) * (viewH - thumbH));
            context.fill(sbX, thumbY, sbX + 5, thumbY + thumbH, 0xFFE8C86A);
        }

        // Подвал
        context.fill(0, this.height - FOOTER_H, this.width, this.height, 0xF21B2338);
        context.fill(0, this.height - FOOTER_H - 1, this.width, this.height - FOOTER_H, 0xFF3A4A6A);
        String hint = "Колесо / стрелки — прокрутка · Esc — закрыть · N — открыть · /teyvat notes";
        context.drawText(this.textRenderer, hint, 12, this.height - 18, C_HINT, true);
    }

    private List<Wrapped> wrap(String text, int maxWidth, int color) {
        List<Wrapped> out = new ArrayList<>();
        if (textRenderer.getWidth(text) <= maxWidth) {
            out.add(new Wrapped(text, color));
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.isEmpty()) {
                cur.append(word);
            } else if (textRenderer.getWidth(cur + " " + word) <= maxWidth) {
                cur.append(' ').append(word);
            } else {
                out.add(new Wrapped(cur.toString(), color));
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (!cur.isEmpty()) {
            out.add(new Wrapped(cur.toString(), color));
        }
        return out;
    }
}
