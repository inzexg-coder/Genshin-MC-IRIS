package net.teyvat.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.teyvat.wiki.TeyvatWiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * «Заметки путешественника» — глобальная энциклопедия Тейвата (как архив в Genshin).
 * Слева разделы Мир / Битва / Сокровища / Приключения и открытые записи,
 * справа страница. Запись открывается при первой встрече (короткая версия),
 * после урока Паймон дополняется полной. Неоткрытые записи не показываются.
 * Открывается клавишей N.
 */
public class TravelerNotesScreen extends Screen {
    // --- Единая сетка: все отступы кратны 2, поля одинаковы со всех сторон. ---
    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 24;
    private static final int PAD = 12;          // отступ области от краёв экрана
    private static final int CARD_MARGIN = 6;   // рамка карточки внутри области
    private static final int CARD_PAD = 14;     // единый внутренний отступ карточки
    private static final int TITLE_H = 22;      // строка заголовка
    private static final int TITLE_GAP = 8;     // заголовок -> контент
    private static final int LINE_H = 12;       // шаг строки текста
    private static final int PARA_GAP = 6;      // между абзацами
    private static final int PAGE_NUM_H = 12;   // полоса номера страницы внизу
    private static final float TEXT_SCALE = 1.0f;
    private static final float TITLE_SCALE = 1.25f;

    private static final int TAB_H = 30;        // высота строки записи в списке
    private static final int SECTION_H = 20;    // высота заголовка раздела
    private static final int TAB_GAP = 4;
    private static final long SLIDE_MS = 220;

    private static final int BG = 0xFF0F1420;
    private static final int PANEL = 0xFF12182A;
    private static final int CARD = 0xFF151C31;
    private static final int CARD_INNER = 0xFF11172A;
    private static final int GOLD = 0xFFE8C86A;
    private static final int GOLD_DIM = 0xFF8C7440;
    private static final int LINE = 0xFF3A4A6A;
    private static final int C_HINT = 0xFF9AA5B8;
    private static final int C_BODY = 0xFFE8E4D8;

    /** Элемент сайдбара: 0 — заголовок раздела, 1 — запись. */
    private record SideItem(int kind, TeyvatWiki.Section section, TeyvatWiki.Entry entry, int entryIdx) {}

    private final List<TeyvatWiki.Entry> entries = new ArrayList<>();
    private final List<SideItem> sideItems = new ArrayList<>();
    private int selected = 0;
    private int sidebarScroll = 0;
    private float scroll = 0;
    private boolean dragging = false;
    private long slideStart = 0;

    private int sidebarW;
    private int cardX0, cardX1, cardY0, cardY1;
    private int ix0, ix1, iy0, iy1;
    private int bodyTop, bodyBottom, bodyH, bodyW;

    private final List<Row> rows = new ArrayList<>();
    private float rowsH;

    private interface Row {
        int height();
        void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY);
    }

    private record TextRow(String text, int color) implements Row {
        public int height() { return LINE_H; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY) {
            drawScaled(ctx, text, x, y, TEXT_SCALE, color);
        }
    }

    private record GapRow(int h) implements Row {
        public int height() { return h; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY) {}
    }

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
        rebuildSidebar();
        rebuild();
    }

    /** Список записей (только открытые) и элементы сайдбара с заголовками разделов. */
    private void rebuildSidebar() {
        entries.clear();
        entries.addAll(WikiStateClient.visibleEntries());
        if (selected >= entries.size()) {
            selected = Math.max(0, entries.size() - 1);
        }
        sideItems.clear();
        TeyvatWiki.Section cur = null;
        int idx = 0;
        for (TeyvatWiki.Entry e : entries) {
            if (e.section() != cur) {
                cur = e.section();
                sideItems.add(new SideItem(0, cur, null, -1));
            }
            sideItems.add(new SideItem(1, null, e, idx));
            idx++;
        }
    }

    private void rebuild() {
        sidebarW = Math.min(148, Math.max(108, (int) (this.width * 0.18f)));
        cardX0 = PAD + sidebarW + PAD + CARD_MARGIN;
        cardX1 = this.width - PAD - 8 - CARD_MARGIN;
        cardY0 = HEADER_H + PAD + CARD_MARGIN;
        cardY1 = this.height - FOOTER_H - PAD - CARD_MARGIN;
        ix0 = cardX0 + CARD_PAD;
        ix1 = cardX1 - CARD_PAD;
        iy0 = cardY0 + CARD_PAD;
        iy1 = cardY1 - CARD_PAD;
        bodyTop = iy0 + TITLE_H + TITLE_GAP;
        bodyBottom = iy1 - PAGE_NUM_H;
        bodyH = bodyBottom - bodyTop;
        bodyW = ix1 - ix0;

        rows.clear();
        TeyvatWiki.Entry e = entries.isEmpty() ? null : entries.get(selected);
        if (e == null) {
            rowsH = 0;
            clampScroll();
            return;
        }
        int wrapW = Math.max(40, (int) (bodyW / TEXT_SCALE));

        boolean full = isFull(e);
        List<String> paras = full ? e.fullParas() : e.shortParas();
        rows.add(new TextRow("— " + e.section().title + " —", C_HINT));
        rows.add(new GapRow(2));
        for (int pi = 0; pi < paras.size(); pi++) {
            if (pi > 0) {
                rows.add(new GapRow(PARA_GAP));
            }
            for (String line : wrap(paras.get(pi), wrapW)) {
                rows.add(new TextRow(line, C_BODY));
            }
        }
        rows.add(new GapRow(6));
        if (!full) {
            rows.add(new TextRow("Полная запись откроется после урока Паймон.", C_HINT));
        }

        rowsH = 0;
        for (Row r : rows) {
            rowsH += r.height();
        }
        clampScroll();
    }

    /** Полная ли запись: у записей без урока — сразу, иначе после квеста Паймон. */
    private static boolean isFull(TeyvatWiki.Entry e) {
        return !e.hasLesson() || QuestStateClient.isCompleted(e.lessonQuestId());
    }

    /** Название записи: до двух строк мелким шрифтом; вторая строка с «…», если длиннее. */
    private List<String> tabLines(String name, int maxW) {
        List<String> ls = wrap(name, maxW);
        List<String> out = new ArrayList<>();
        out.add(ls.get(0));
        if (ls.size() > 1) {
            String second = ls.get(1);
            if (ls.size() > 2) {
                while (!second.isEmpty() && textRenderer.getWidth(second + "…") > maxW) {
                    second = second.substring(0, second.length() - 1);
                }
                second += "…";
            }
            out.add(second);
        }
        return out;
    }

    /** Перенос с обрезкой длинных слов — строка никогда не шире maxWidth. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.isEmpty()) {
                cur.append(word);
            } else if (textRenderer.getWidth(cur + " " + word) <= maxWidth) {
                cur.append(' ').append(word);
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
            // Слово длиннее колонки — режем по символам.
            while (textRenderer.getWidth(cur.toString()) > maxWidth) {
                int cut = cur.length();
                while (cut > 0 && textRenderer.getWidth(cur.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                if (cut <= 0) {
                    cut = 1;
                }
                out.add(cur.substring(0, cut));
                cur.delete(0, cut);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private void clampScroll() {
        double max = Math.max(0, rowsH - bodyH);
        scroll = (float) Math.max(0, Math.min(scroll, max));
    }

    private int sidebarTop() {
        return HEADER_H + PAD;
    }

    private int sidebarBottom() {
        return this.height - FOOTER_H - PAD;
    }

    private int itemHeight(SideItem it) {
        return it.kind() == 0 ? SECTION_H : TAB_H;
    }

    private int totalSidebarHeight() {
        int h = 0;
        for (SideItem it : sideItems) {
            h += itemHeight(it) + TAB_GAP;
        }
        return h;
    }

    /** Верхняя граница элемента сайдбара с учётом прокрутки. */
    private int itemTop(int idx) {
        int y = sidebarTop();
        for (int i = 0; i < idx; i++) {
            y += itemHeight(sideItems.get(i)) + TAB_GAP;
        }
        return y - sidebarScroll;
    }

    private int maxSidebarScroll() {
        return Math.max(0, totalSidebarHeight() - (sidebarBottom() - sidebarTop()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX >= PAD && mouseX <= PAD + sidebarW) {
            sidebarScroll = (int) Math.max(0, Math.min(sidebarScroll + (int) (verticalAmount * -18), maxSidebarScroll()));
            return true;
        }
        scroll -= (float) (verticalAmount * 18);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        int sbX0 = PAD;
        int sbX1 = PAD + sidebarW;
        for (int i = 0; i < sideItems.size(); i++) {
            SideItem it = sideItems.get(i);
            int ty = itemTop(i);
            if (it.kind() == 1
                    && mouseX >= sbX0 && mouseX <= sbX1
                    && mouseY >= ty && mouseY <= ty + itemHeight(it)) {
                if (selected != it.entryIdx()) {
                    selected = it.entryIdx();
                    scroll = 0;
                    slideStart = Util.getMeasuringTimeMs();
                    rebuild();
                }
                return true;
            }
        }
        int sbX = cardX1 - 8;
        if (button == 0 && mouseX >= sbX && mouseX <= sbX + 3 && mouseY >= bodyTop && mouseY <= bodyBottom) {
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
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging) {
            scroll += (float) (offsetY * (rowsH / (float) Math.max(1, bodyH)));
            clampScroll();
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean keyPressed(KeyInput key) {
        switch (key.key()) {
            case 264: scroll += 18; clampScroll(); return true;
            case 265: scroll -= 18; clampScroll(); return true;
            case 266: scroll -= bodyH; clampScroll(); return true;
            case 267: scroll += bodyH; clampScroll(); return true;
            case 268: scroll = 0; return true;
            case 269: scroll = Float.MAX_VALUE; clampScroll(); return true;
            default: return super.keyPressed(key);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, BG);
        drawStars(context);
        drawHeader(context);
        drawSidebar(context, mouseX, mouseY);
        drawPage(context, mouseX, mouseY);
        drawFooter(context);
    }

    private void drawHeader(DrawContext ctx) {
        ctx.fill(0, 0, this.width, HEADER_H, 0xFF1B2338);
        ctx.fill(0, HEADER_H, this.width, HEADER_H + 1, LINE);
        String title = "「Заметки путешественника」";
        ctx.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2,
                (HEADER_H - 9) / 2, GOLD, true);
        String ver = modVersion();
        ctx.drawText(this.textRenderer, ver, this.width - this.textRenderer.getWidth(ver) - 10,
                (HEADER_H - 9) / 2, C_HINT, true);
    }

    private void drawFooter(DrawContext ctx) {
        ctx.fill(0, this.height - FOOTER_H, this.width, this.height, 0xFF1B2338);
        ctx.fill(0, this.height - FOOTER_H - 1, this.width, this.height - FOOTER_H, LINE);
        String hint = "Колесо / стрелки — прокрутка · N — заметки";
        ctx.drawText(this.textRenderer, hint, 12, this.height - 17, C_HINT, true);
    }

    private void drawStars(DrawContext ctx) {
        Random rnd = new Random(20260810L);
        long now = Util.getMeasuringTimeMs();
        for (int i = 0; i < 70; i++) {
            int x = rnd.nextInt(Math.max(1, this.width));
            int y = rnd.nextInt(Math.max(1, this.height));
            if (y < HEADER_H + 6 || y > this.height - FOOTER_H - 6) {
                continue;
            }
            int size = rnd.nextInt(2) + 1;
            float tw = 0.5f + 0.5f * (float) Math.sin(now / 1100.0 + i * 1.73);
            int a = (int) (14 + 16 * tw);
            ctx.fill(x, y, x + size, y + size, (a << 24) | 0xFFFFFF);
        }
    }

    /** Сайдбар: заголовки разделов и открытые записи с эмблемами. */
    private void drawSidebar(DrawContext ctx, int mouseX, int mouseY) {
        int sbX0 = PAD;
        int sbX1 = PAD + sidebarW;
        ctx.fill(sbX0, HEADER_H + PAD, sbX1, sidebarBottom(), PANEL);

        ctx.enableScissor(PAD, sidebarTop(), PAD + sidebarW, sidebarBottom());
        for (int i = 0; i < sideItems.size(); i++) {
            SideItem it = sideItems.get(i);
            int ty = itemTop(i);
            int h = itemHeight(it);
            if (ty + h < sidebarTop() || ty > sidebarBottom()) {
                continue;
            }
            if (it.kind() == 0) {
                // Заголовок раздела: цвет раздела, мелким.
                drawScaled(ctx, it.section().title.toUpperCase(), sbX0 + 14, ty + 5, 0.72f, it.section().color);
                continue;
            }
            TeyvatWiki.Entry e = it.entry();
            boolean sel = it.entryIdx() == selected;
            boolean hover = mouseX >= sbX0 && mouseX <= sbX1 && mouseY >= ty && mouseY <= ty + h;
            ctx.fill(sbX0 + 6, ty, sbX1 - 6, ty + h, sel ? 0xFF222C44 : (hover ? 0xFF1A2338 : 0x00000000));
            if (sel) {
                ctx.fill(sbX0 + 6, ty, sbX0 + 8, ty + h, GOLD);
            } else if (hover) {
                ctx.fill(sbX0 + 6, ty, sbX0 + 7, ty + h, 0x668C7440);
            }
            int iconColor = sel ? e.section().color : (e.section().color & 0xFFFFFF) | 0x99000000;
            drawIcon(ctx, sbX0 + 21, ty + h / 2, e.icon(), iconColor);
            float ts = 0.85f;
            int textX = sbX0 + 34;
            int textMaxW = Math.max(24, sbX1 - textX - 12);
            int logical = Math.max(30, (int) (textMaxW / ts));
            List<String> tl = tabLines(e.title(), logical);
            int textH = tl.size() * 8;
            int ly = ty + (h - textH) / 2;
            for (String ln : tl) {
                drawScaled(ctx, ln, textX, ly, ts, sel ? e.section().color : C_HINT);
                ly += 8;
            }
        }
        ctx.disableScissor();
        // Золотая граница поверх списка при прокрутке.
        ctx.fill(sbX0, HEADER_H + PAD, sbX1, HEADER_H + PAD + 1, GOLD_DIM);
        int max = maxSidebarScroll();
        if (max > 0) {
            int trackH = sidebarBottom() - sidebarTop();
            int thumbH = Math.max(14, trackH * trackH / Math.max(1, totalSidebarHeight()));
            int thumbY = sidebarTop() + (trackH - thumbH) * sidebarScroll / max;
            ctx.fill(sbX1 - 6, thumbY, sbX1 - 4, thumbY + thumbH, GOLD_DIM);
        }
    }

    /** Страница-карточка: рамка, заголовок с линией, текст, номер страницы. */
    private void drawPage(DrawContext ctx, int mouseX, int mouseY) {
        if (entries.isEmpty()) {
            ctx.fill(cardX0 - 2, cardY0 - 2, cardX1 + 2, cardY1 + 2, GOLD_DIM);
            ctx.fill(cardX0, cardY0, cardX1, cardY1, CARD);
            String msg = "Здесь пока пусто. Исследуй мир — записи появятся сами.";
            drawScaled(ctx, msg, (ix0 + ix1) / 2 - textRenderer.getWidth(msg) / 2,
                    (cardY0 + cardY1) / 2, TEXT_SCALE, C_HINT);
            return;
        }
        TeyvatWiki.Entry e = entries.get(selected);
        int color = e.section().color;

        ctx.fill(cardX0 - 2, cardY0 - 2, cardX1 + 2, cardY1 + 2, GOLD_DIM);
        ctx.fill(cardX0 - 1, cardY0 - 1, cardX1 + 1, cardY1 + 1, GOLD);
        ctx.fill(cardX0, cardY0, cardX1, cardY1, CARD);
        ctx.fill(cardX0 + 3, cardY0 + 3, cardX1 - 3, cardY1 - 3, CARD_INNER);
        drawCornerDots(ctx, color);

        long dt = Util.getMeasuringTimeMs() - slideStart;
        float prog = Math.min(1.0f, dt / (float) SLIDE_MS);
        float reveal = easeOutCubic(prog);
        int clipR = cardX0 + (int) ((cardX1 - cardX0) * reveal);
        ctx.enableScissor(cardX0, cardY0, Math.max(cardX0 + 1, clipR), cardY1);

        drawPageTitle(ctx, e, color);

        ctx.disableScissor();
        ctx.enableScissor(ix0, bodyTop, ix1, bodyBottom);
        int y = bodyTop - (int) scroll;
        for (Row r : rows) {
            if (y + r.height() >= bodyTop && y <= bodyBottom) {
                r.draw(ctx, ix0, y, mouseX, mouseY);
            }
            y += r.height();
        }
        ctx.disableScissor();

        if (rowsH > bodyH) {
            int sbX = cardX1 - 8;
            ctx.fill(sbX, bodyTop, sbX + 3, bodyBottom, 0x26C9A24B);
            int thumbH = Math.max(18, (int) (bodyH * bodyH / rowsH));
            int maxScroll = (int) (rowsH - bodyH);
            int thumbY = bodyTop + (int) (scroll / (float) Math.max(1, maxScroll) * (bodyH - thumbH));
            ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, GOLD);
        }

        String pageNo = (selected + 1) + " / " + entries.size();
        ctx.drawText(this.textRenderer, pageNo, cardX1 - CARD_PAD - this.textRenderer.getWidth(pageNo),
                cardY1 - CARD_PAD + 2, GOLD_DIM, true);
    }

    /** Заголовок: по центру, масштаб ограничен шириной карточки, линия-разделитель. */
    private void drawPageTitle(DrawContext ctx, TeyvatWiki.Entry e, int color) {
        String title = e.title();
        int tw0 = this.textRenderer.getWidth(title);
        float ts = Math.min(TITLE_SCALE, (bodyW - 60f) / Math.max(1, tw0));
        ts = Math.max(0.75f, ts);
        int tw = (int) (tw0 * ts);
        int cx = (ix0 + ix1) / 2;
        int tx0 = cx - tw / 2;
        int tx1 = cx + tw / 2;
        int midY = iy0 + 17;

        if (tx0 - ix0 > 22) {
            ctx.fill(ix0, midY, tx0 - 12, midY + 1, GOLD_DIM);
            drawDiamond(ctx, tx0 - 7, midY, 3, color);
        }
        if (ix1 - tx1 > 22) {
            ctx.fill(tx1 + 12, midY, ix1, midY + 1, GOLD_DIM);
            drawDiamond(ctx, tx1 + 7, midY, 3, color);
        }
        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate(tx0, iy0 + 2);
        ms.scale(ts, ts);
        ctx.drawText(this.textRenderer, title, 0, 0, color, true);
        ms.popMatrix();
    }

    private void drawCornerDots(DrawContext ctx, int color) {
        int r = 3;
        int off = 6;
        drawDiamond(ctx, cardX0 + off, cardY0 + off, r, color);
        drawDiamond(ctx, cardX1 - off, cardY0 + off, r, color);
        drawDiamond(ctx, cardX0 + off, cardY1 - off, r, color);
        drawDiamond(ctx, cardX1 - off, cardY1 - off, r, color);
    }

    private static void drawDiamond(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private static void drawIcon(DrawContext ctx, int cx, int cy, String kind, int color) {
        switch (kind) {
            case "diamond" -> drawDiamond(ctx, cx, cy, 6, color);
            case "circle" -> drawCircle(ctx, cx, cy, 6, color);
            case "square" -> ctx.fill(cx - 5, cy - 5, cx + 6, cy + 6, color);
            case "chevrons" -> {
                for (int k = 0; k < 2; k++) {
                    int bx = cx - 3 + k * 5;
                    for (int dy = -3; dy <= 3; dy++) {
                        int w = 3 - Math.abs(dy);
                        ctx.fill(bx, cy + dy, bx + w + 1, cy + dy + 1, color);
                    }
                }
            }
            case "triangle" -> {
                for (int dy = 0; dy <= 7; dy++) {
                    int w = dy;
                    ctx.fill(cx - w, cy - 3 + dy, cx + w + 1, cy - 3 + dy + 1, color);
                }
            }
            case "drop" -> {
                for (int dy = -4; dy < 0; dy++) {
                    int w = 1 + (dy + 4);
                    ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
                }
                for (int dy = 0; dy <= 5; dy++) {
                    int w = (int) Math.round(Math.sqrt(16.0 - (dy - 1) * (dy - 1)));
                    ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
                }
            }
            case "coin" -> {
                drawCircle(ctx, cx, cy, 6, color);
                ctx.fill(cx - 2, cy - 2, cx + 3, cy + 3, PANEL);
            }
            case "star" -> {
                drawDiamond(ctx, cx, cy, 6, color);
                for (int dx = -6; dx <= 6; dx++) {
                    int w = 6 - Math.abs(dx);
                    ctx.fill(cx + dx, cy - w, cx + dx + 1, cy + w + 1, color);
                }
            }
            case "vial" -> {
                ctx.fill(cx - 3, cy - 4, cx + 4, cy + 6, color);
                ctx.fill(cx - 1, cy - 7, cx + 2, cy - 3, color);
                ctx.fill(cx - 2, cy + 2, cx + 3, cy + 3, PANEL);
            }
            case "sword" -> {
                ctx.fill(cx - 2, cy - 7, cx + 3, cy - 3, color);  // клинок
                ctx.fill(cx, cy - 8, cx + 1, cy - 6, color);      // остриё
                ctx.fill(cx - 4, cy - 3, cx + 5, cy - 2, color);  // гарда
                ctx.fill(cx - 1, cy - 2, cx + 2, cy + 2, color);  // рукоять
                ctx.fill(cx - 1, cy + 2, cx + 2, cy + 4, color);  // навершие
            }
            default -> drawCircle(ctx, cx, cy, 6, color);
        }
    }

    private static void drawCircle(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private static void drawScaled(DrawContext ctx, String text, int x, int y, float s, int color) {
        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate(x, y);
        ms.scale(s, s);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, text, 0, 0, color, true);
        ms.popMatrix();
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    /** Версия мода из fabric.mod.json (для шапки). */
    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("teyvat")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .map(v -> "Teyvat " + v)
                .orElse("Teyvat");
    }
}
