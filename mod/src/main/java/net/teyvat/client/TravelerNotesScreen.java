package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static net.teyvat.client.TravelerNotesContent.*;

/**
 * «Заметки путешественника»: две вкладки — «О сборке» и «Селестия»
 * (блоки от простого крафта к сложному: описание, рецепт по центру).
 * Доступно в любом режиме игры: клавиша N или /teyvat notes.
 */
public class TravelerNotesScreen extends Screen {
    private static final int HEADER_H = 34;
    private static final int TAB_H = 26;
    private static final int FOOTER_H = 24;
    private static final int PAD = 14;
    private static final int LINE_H = 11;

    private final List<String> tabs = List.of("О сборке", "Селестия");
    private int tab = 0;

    private int contentX0;
    private int contentX1;
    private int contentY0;
    private int contentY1;
    private int contentW;
    private int viewH;

    private final List<Row> rows = new ArrayList<>();
    private int contentH = 0;
    private double scroll = 0;
    private boolean dragging = false;
    private Text hoverTooltip = null;

    private interface Row {
        int height();
        void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip);
    }

    private record TextRow(String text, int color) implements Row {
        public int height() { return LINE_H; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer, text, x, y, color, true);
        }
    }

    private record GapRow(int h) implements Row {
        public int height() { return h; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {}
    }

    /** Название блока (золотом). Превью теперь справа от сеток крафта. */
    private record NameRow(String name) implements Row {
        public int height() { return LINE_H; }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer, name, x, y, C_GOLD, true);
        }
    }

    /** Ячейка крафта: 26px, шаг 28px — сетка крупнее прежней (18px). */
    private static final int CELL = 26;
    private static final int PITCH = 28;

    /** Все крафты блока: по центру контента, увеличенные. */
    private record CraftSection(List<CraftGrid> grids, String itemId) implements Row {
        public int height() {
            int h = 0;
            for (CraftGrid g : grids) {
                h += 14 + gridH(g.pattern()) + 10;
            }
            return h;
        }
        public void draw(DrawContext ctx, int x, int y, int mouseX, int mouseY, Consumer<Text> tooltip) {
            int availW = MinecraftClient.getInstance().getWindow().getScaledWidth() - 2 * PAD - 8;
            int maxBlockW = 0;
            for (CraftGrid g : grids) {
                int cols = g.pattern()[0].length();
                maxBlockW = Math.max(maxBlockW, cols * PITCH - 2 + 18 + CELL);
            }
            int gx = x + Math.max(0, (availW - maxBlockW) / 2);
            int gy = y;
            for (CraftGrid g : grids) {
                drawGrid(ctx, g, gx, gy, mouseX, mouseY, tooltip, maxBlockW);
                gy += 14 + gridH(g.pattern()) + 10;
            }
        }
    }

    private static int gridH(String[] pattern) {
        return pattern.length * CELL + (pattern.length - 1) * 2;
    }

    private static void cell(DrawContext ctx, int cx, int cy, int borderColor, int bg) {
        ctx.fill(cx, cy, cx + CELL, cy + CELL, bg);
        ctx.fill(cx, cy, cx + CELL, cy + 1, borderColor);
        ctx.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, borderColor);
        ctx.fill(cx, cy, cx + 1, cy + CELL, borderColor);
        ctx.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, borderColor);
    }

    private static void drawGrid(DrawContext ctx, CraftGrid grid, int x, int y, int mouseX, int mouseY,
                                 Consumer<Text> tooltip, int blockW) {
        MinecraftClient client = MinecraftClient.getInstance();
        ctx.drawText(client.textRenderer, grid.title(), x + (blockW - client.textRenderer.getWidth(grid.title())) / 2,
                y + 2, C_GOLD, true);
        int gy = y + 14;
        String[] pattern = grid.pattern();
        int cols = pattern[0].length();
        for (int r = 0; r < pattern.length; r++) {
            for (int cIdx = 0; cIdx < cols; cIdx++) {
                char key = pattern[r].charAt(cIdx);
                Slot slot = grid.keys().get(key);
                int cx = x + cIdx * PITCH;
                int cy = gy + r * PITCH;
                boolean hovered = slot != null && mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
                if (slot == null) {
                    cell(ctx, cx, cy, 0xFF141A2A, 0xFF12182A);
                } else {
                    cell(ctx, cx, cy, hovered ? 0xFFE8C86A : 0xFF3A4A6A, 0xFF1B2338);
                    Item item = itemOf(slot.item());
                    if (item != Items.AIR) {
                        ctx.drawItem(new ItemStack(item), cx + (CELL - 16) / 2, cy + (CELL - 16) / 2);
                    }
                    if (slot.count() > 1) {
                        ctx.drawText(client.textRenderer, String.valueOf(slot.count()),
                                cx + CELL - 12, cy + CELL - 10, 0xFFFFFFFF, true);
                    }
                    if (hovered) {
                        tooltip.accept(item.getName());
                    }
                }
            }
        }
        int gridW = cols * PITCH - 2;
        int gMid = gy + gridH(pattern) / 2 - 4;
        ctx.drawText(client.textRenderer, "→", x + gridW + 6, gMid, C_GOLD, true);
        int rx = x + gridW + 18;
        int ry = gy + gridH(pattern) / 2 - CELL / 2; // ровно напротив среднего ряда
        boolean rh = mouseX >= rx && mouseX < rx + CELL && mouseY >= ry && mouseY < ry + CELL;
        cell(ctx, rx, ry, rh ? 0xFFFFFFFF : 0xFFE8C86A, 0xFF1B2338);
        Item result = itemOf(grid.result());
        if (result != Items.AIR) {
            ctx.drawItem(new ItemStack(result), rx + (CELL - 16) / 2, ry + (CELL - 16) / 2);
            if (grid.resultCount() > 1) {
                ctx.drawText(client.textRenderer, String.valueOf(grid.resultCount()),
                        rx + CELL - 12, ry + CELL - 10, 0xFFFFFFFF, true);
            }
            if (rh) {
                tooltip.accept(result.getName());
            }
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
        contentX0 = PAD;
        contentX1 = this.width - PAD - 8;
        contentY0 = HEADER_H + TAB_H + 6;
        contentY1 = this.height - FOOTER_H - 4;
        contentW = contentX1 - contentX0;
        viewH = contentY1 - contentY0;
        rebuild();
    }

    private static Item itemOf(String id) {
        return Registries.ITEM.get(Identifier.of(id));
    }

    private void rebuild() {
        rows.clear();
        if (tab == 0) {
            for (Line l : about()) {
                addLine(l);
            }
        } else {
            for (Line l : celestiaIntro()) {
                addLine(l);
            }
            rows.add(new GapRow(10));
            for (BlockEntry e : blocks()) {
                rows.add(new NameRow(e.name()));
                rows.add(new GapRow(3));
                for (String desc : e.description()) {
                    wrapIntoRows(desc, C_DESC);
                }
                rows.add(new GapRow(6));
                rows.add(new CraftSection(e.crafts(), e.id()));
                rows.add(new GapRow(14));
            }
        }
        rows.add(new GapRow(18)); // нижний отступ, чтобы контент не упирался в край
        contentH = 0;
        for (Row r : rows) {
            contentH += r.height();
        }
        clampScroll();
    }

    private void addLine(Line l) {
        if (l.wrap()) {
            wrapIntoRows(l.text(), l.color());
        } else {
            rows.add(new TextRow(l.text(), l.color()));
        }
    }

    private void wrapIntoRows(String text, int color) {
        for (String part : wrap(text, contentW)) {
            rows.add(new TextRow(part, color));
        }
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (textRenderer.getWidth(text) <= maxWidth) {
            out.add(text);
            return out;
        }
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
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
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
        for (int i = 0; i < tabs.size(); i++) {
            int tx = 10 + i * 140;
            if (mouseX >= tx && mouseX <= tx + 130 && mouseY >= HEADER_H + 2 && mouseY <= HEADER_H + TAB_H - 2) {
                if (tab != i) {
                    tab = i;
                    scroll = 0;
                    rebuild();
                }
                return true;
            }
        }
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
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging) {
            scroll += offsetY * (contentH / (double) Math.max(1, viewH));
            clampScroll();
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
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
        // Полностью непрозрачный фон — мир/хотбар позади не просвечивают
        context.fill(0, 0, this.width, this.height, 0xFF0F1420);

        context.fill(0, 0, this.width, HEADER_H, 0xFF1B2338);
        context.fill(0, HEADER_H, this.width, HEADER_H + 1, 0xFF3A4A6A);
        String title = "「Заметки путешественника」";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2,
                (HEADER_H - 9) / 2, C_GOLD, true);
        String ver = "Teyvat 0.8.33";
        context.drawText(this.textRenderer, ver, this.width - this.textRenderer.getWidth(ver) - 10,
                (HEADER_H - 9) / 2, C_HINT, true);

        for (int i = 0; i < tabs.size(); i++) {
            int tx = 10 + i * 140;
            boolean sel = tab == i;
            boolean hover = mouseX >= tx && mouseX <= tx + 130 && mouseY >= HEADER_H + 2 && mouseY <= HEADER_H + TAB_H - 2;
            context.fill(tx, HEADER_H + 2, tx + 130, HEADER_H + TAB_H - 2, sel ? 0xFF222C44 : (hover ? 0xFF1A2338 : 0x00000000));
            if (sel) {
                context.fill(tx, HEADER_H + TAB_H - 3, tx + 130, HEADER_H + TAB_H - 2, C_GOLD);
            }
            context.drawText(this.textRenderer, tabs.get(i),
                    tx + (130 - this.textRenderer.getWidth(tabs.get(i))) / 2,
                    HEADER_H + 9, sel ? C_GOLD : C_HINT, true);
        }
        context.fill(0, HEADER_H + TAB_H, this.width, HEADER_H + TAB_H + 1, 0xFF3A4A6A);

        hoverTooltip = null;
        context.enableScissor(contentX0, contentY0, contentX1, contentY1);
        int y = contentY0 - (int) scroll;
        for (Row r : rows) {
            if (y + r.height() >= contentY0 && y <= contentY1) {
                r.draw(context, contentX0, y, mouseX, mouseY, t -> hoverTooltip = t);
            }
            y += r.height();
        }
        context.disableScissor();

        if (hoverTooltip != null) {
            context.drawTooltip(this.textRenderer, hoverTooltip, mouseX + 8, mouseY + 8);
        }

        int sbX = contentX1 + 4;
        context.fill(sbX, contentY0, sbX + 5, contentY1, 0x66222C44);
        if (contentH > viewH) {
            int thumbH = Math.max(18, viewH * viewH / contentH);
            int thumbY = contentY0 + (int) (scroll / (contentH - viewH) * (viewH - thumbH));
            context.fill(sbX, thumbY, sbX + 5, thumbY + thumbH, 0xFFE8C86A);
        }

        context.fill(0, this.height - FOOTER_H, this.width, this.height, 0xFF1B2338);
        context.fill(0, this.height - FOOTER_H - 1, this.width, this.height - FOOTER_H, 0xFF3A4A6A);
        String hint = "Колесо / стрелки — прокрутка · Esc — закрыть · N — открыть · /teyvat notes";
        context.drawText(this.textRenderer, hint, 12, this.height - 17, C_HINT, true);
    }
}
