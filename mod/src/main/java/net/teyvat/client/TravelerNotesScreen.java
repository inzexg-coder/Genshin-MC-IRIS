package net.teyvat.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.teyvat.TeyvatMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

import static net.teyvat.client.TravelerGuideContent.*;

/**
 * «Заметки путешественника» — гид по игре. Вёрстка по образцу книг-гидов
 * (Patchouli): фиксированные поля, единая сетка отступов, текст стандартного
 * размера, перенос строго по колонке — ничего не выходит за рамки. Слева список
 * вкладок с эмблемами и акцентными цветами, справа страница-карточка: заголовок,
 * текст урока и скриншот (assets/teyvat/textures/gui/notes/<id>.png, добавим позже).
 * Длинный текст прокручивается, но не уменьшается.
 * Открывается клавишей N. «О сборке» — отдельный экран для администраторов (Shift+N).
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
    private static final int IMG_W = 210;       // скриншоты горизонтальные
    private static final int IMG_H = 120;
    private static final int IMG_GAP = 14;
    private static final float TEXT_SCALE = 1.0f;
    private static final float TITLE_SCALE = 1.25f;

    private static final int TAB_H = 30;
    private static final int TAB_GAP = 4;
    private static final long SLIDE_MS = 220;

    private static final int BG = 0xFF0F1420;
    private static final int PANEL = 0xFF12182A;
    private static final int CARD = 0xFF151C31;
    private static final int CARD_INNER = 0xFF11172A;
    private static final int GOLD = 0xFFE8C86A;
    private static final int GOLD_DIM = 0xFF8C7440;
    private static final int LINE = 0xFF3A4A6A;

    private final List<GuideTab> tabs = tabs();
    private int selected = 0;
    private int sidebarScroll = 0;
    private float scroll = 0;
    private boolean dragging = false;
    private long slideStart = 0;

    private int sidebarW;
    private int cardX0, cardX1, cardY0, cardY1;
    private int ix0, ix1, iy0, iy1;
    private int bodyTop, bodyBottom, bodyH, bodyW;
    private int imgW, imgH;

    private final List<Row> rows = new ArrayList<>();
    private float rowsH;
    private static final Map<String, NativeImage> shots = new HashMap<>();

    private interface Row {
        int height();
        void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip);
    }

    private record TextRow(String text, int color) implements Row {
        public int height() { return LINE_H; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {
            drawScaled(ctx, text, x, y, TEXT_SCALE, color);
        }
    }

    private record GapRow(int h) implements Row {
        public int height() { return h; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {}
    }

    /** Скриншот: в колонку текста (высота ограничена, см. rebuild), сверху страницы. */
    private final class ShotRow implements Row {
        private final String id;

        ShotRow(String id) {
            this.id = id;
        }

        public int height() {
            return imgH;
        }

        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {
            drawShot(ctx, x + (bodyW - imgW) / 2, y, id, imgW, imgH);
        }
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
        rebuild();
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

        GuideTab t = tabs.get(selected);

        // Скриншот всегда на всю ширину колонки текста, высота строго по пропорции
        // картинки — рамка совпадает с колонкой, без letterbox-полей.
        imgW = bodyW;
        NativeImage shot = screenshot(t.id());
        // Ширина скриншота — колонка текста, но высота ограничена половиной видимой
        // области: иначе картинка (0.57 высоты при горизонтальных кадрах) вытесняет
        // весь текст за нижний край и экран выглядит пустым.
        float aspect = shot == null ? IMG_W / (float) IMG_H : shot.getWidth() / (float) shot.getHeight();
        int naturalH = Math.max(1, Math.round(imgW / aspect));
        int maxH = Math.max(48, Math.round(bodyH * 0.45f));
        if (naturalH > maxH) {
            imgH = maxH;
            imgW = Math.max(1, Math.round(maxH * aspect));
        } else {
            imgH = naturalH;
        }
        int wrapW = Math.max(40, (int) (bodyW / TEXT_SCALE));

        rows.clear();
        rows.add(new ShotRow(t.id()));
        rows.add(new GapRow(IMG_GAP));
        for (int pi = 0; pi < t.paragraphs().size(); pi++) {
            if (pi > 0) {
                rows.add(new GapRow(PARA_GAP));
            }
            for (String line : wrap(t.paragraphs().get(pi), wrapW)) {
                rows.add(new TextRow(line, C_BODY));
            }
        }
        rows.add(new GapRow(6));

        rowsH = 0;
        for (Row r : rows) {
            rowsH += r.height();
        }
        clampScroll();
    }

    /** Название вкладки: до двух строк мелким шрифтом; вторая строка с «…», если длиннее. */
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

    private int maxSidebarScroll() {
        return Math.max(0, tabs.size() * (TAB_H + TAB_GAP) - (sidebarBottom() - sidebarTop()));
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
        for (int i = 0; i < tabs.size(); i++) {
            int ty = sidebarTop() + i * (TAB_H + TAB_GAP) - sidebarScroll;
            if (mouseX >= sbX0 && mouseX <= sbX1 && mouseY >= ty && mouseY <= ty + TAB_H) {
                if (selected != i) {
                    selected = i;
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
        String hint = "Колесо / стрелки — прокрутка · N — заметки · Shift+N — «О сборке» (только админ)";
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

    /** Список вкладок: единые отступы, эмблема + название, акцентный цвет. */
    private void drawSidebar(DrawContext ctx, int mouseX, int mouseY) {
        int sbX0 = PAD;
        int sbX1 = PAD + sidebarW;
        ctx.fill(sbX0, HEADER_H + PAD, sbX1, sidebarBottom(), PANEL);

        // Список вкладок не выходит за пределы панели (и золотой границы) при прокрутке.
        ctx.enableScissor(PAD, sidebarTop(), PAD + sidebarW, sidebarBottom());
        for (int i = 0; i < tabs.size(); i++) {
            int ty = sidebarTop() + i * (TAB_H + TAB_GAP) - sidebarScroll;
            if (ty + TAB_H < sidebarTop() || ty > sidebarBottom()) {
                continue;
            }
            GuideTab t = tabs.get(i);
            boolean sel = i == selected;
            boolean hover = mouseX >= sbX0 && mouseX <= sbX1 && mouseY >= ty && mouseY <= ty + TAB_H;
            ctx.fill(sbX0 + 6, ty, sbX1 - 6, ty + TAB_H, sel ? 0xFF222C44 : (hover ? 0xFF1A2338 : 0x00000000));
            if (sel) {
                ctx.fill(sbX0 + 6, ty, sbX0 + 8, ty + TAB_H, GOLD);
            } else if (hover) {
                ctx.fill(sbX0 + 6, ty, sbX0 + 7, ty + TAB_H, 0x668C7440);
            }
            int iconColor = sel ? t.color() : (t.color() & 0xFFFFFF) | 0x99000000;
            drawIcon(ctx, sbX0 + 21, ty + TAB_H / 2, t.icon(), iconColor);
            float ts = 0.85f;
            int logical = Math.max(30, (int) ((sbX1 - sbX0 - 33) / ts));
            List<String> tl = tabLines(t.title(), logical);
            int textH = tl.size() * 8;
            int ly = ty + (TAB_H - textH) / 2;
            for (String ln : tl) {
                drawScaled(ctx, ln, sbX0 + 32, ly, ts, sel ? t.color() : C_HINT);
                ly += 8;
            }
        }
        ctx.disableScissor();
        // Золотая граница поверх вкладок при прокрутке.
        ctx.fill(sbX0, HEADER_H + PAD, sbX1, HEADER_H + PAD + 1, GOLD_DIM);
        int max = maxSidebarScroll();
        if (max > 0) {
            int trackH = sidebarBottom() - sidebarTop();
            int thumbH = Math.max(14, trackH * trackH / (tabs.size() * (TAB_H + TAB_GAP)));
            int thumbY = sidebarTop() + (trackH - thumbH) * sidebarScroll / max;
            ctx.fill(sbX1 - 6, thumbY, sbX1 - 4, thumbY + thumbH, GOLD_DIM);
        }
    }

    /** Страница-карточка: рамка, заголовок с линией, текст и скриншот, номер страницы. */
    private void drawPage(DrawContext ctx, int mouseX, int mouseY) {
        GuideTab t = tabs.get(selected);
        int color = t.color();

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

        drawPageTitle(ctx, t, color);

        ctx.disableScissor();
        ctx.enableScissor(ix0, bodyTop, ix1, bodyBottom);
        int y = bodyTop - (int) scroll;
        for (Row r : rows) {
            if (y + r.height() >= bodyTop && y <= bodyBottom) {
                r.draw(ctx, ix0, y, mouseX, mouseY, tt -> {});
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

        String pageNo = (selected + 1) + " / " + tabs.size();
        ctx.drawText(this.textRenderer, pageNo, cardX1 - CARD_PAD - this.textRenderer.getWidth(pageNo),
                cardY1 - CARD_PAD + 2, GOLD_DIM, true);
    }

    /** Заголовок: по центру, масштаб ограничен шириной карточки, линия-разделитель. */
    private void drawPageTitle(DrawContext ctx, GuideTab t, int color) {
        String title = t.title();
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
            default -> drawCircle(ctx, cx, cy, 6, color);
        }
    }

    private static void drawCircle(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** Копия картинки для вкладки (кэш на время жизни экрана); null — скриншота нет. */
    private static NativeImage screenshot(String id) {
        return shots.computeIfAbsent(id, TravelerNotesScreen::loadScreenshot);
    }

    private static NativeImage loadScreenshot(String id) {
        Identifier res = Identifier.of(TeyvatMod.MOD_ID, "textures/gui/notes/" + id + ".png");
        Optional<Resource> r = MinecraftClient.getInstance().getResourceManager().getResource(res);
        if (r.isEmpty()) {
            return null;
        }
        try (InputStream in = r.get().getInputStream()) {
            return NativeImage.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Рисует скриншот в рамке; если файла нет — заглушка. */
    private static void drawShot(DrawContext ctx, int x, int y, String id, int w, int h) {
        MinecraftClient client = MinecraftClient.getInstance();
        NativeImage img = screenshot(id);
        ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, GOLD);
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B0F1C);
        if (img == null) {
            ctx.fill(x, y, x + w, y + h, 0xE60B0F1C);
            String t = "Скриншот скоро появится";
            int tw = client.textRenderer.getWidth(t);
            float s = Math.min(1.0f, (w - 8f) / tw);
            var ms = ctx.getMatrices();
            ms.pushMatrix();
            ms.translate(x, y);
            ms.scale(s, s);
            ctx.drawText(client.textRenderer, t, (int) ((w / s - tw) / 2f), (int) ((h / s - 9f) / 2f), C_HINT, true);
            ms.popMatrix();
            return;
        }
        float f = Math.min(w / (float) img.getWidth(), h / (float) img.getHeight());
        int dw = (int) (img.getWidth() * f);
        int dh = (int) (img.getHeight() * f);
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED,
                Identifier.of(TeyvatMod.MOD_ID, "textures/gui/notes/" + id + ".png"),
                dx, dy, 0.0f, 0.0f, dw, dh, img.getWidth(), img.getHeight(), img.getWidth(), img.getHeight());
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
