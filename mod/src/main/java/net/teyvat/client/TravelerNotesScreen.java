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
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.teyvat.client.TravelerNotesContent.*;

/**
 * «Заметки путешественника»: две вкладки — «О сборке» и «Селестия»
 * (блоки от простого крафта к сложному: иконка, описание, сетка рецепта).
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

    private interface Row {
        int height();
        void draw(DrawContext ctx, int x, int y);
    }

    private record TextRow(String text, int color) implements Row {
        public int height() { return LINE_H; }
        public void draw(DrawContext ctx, int x, int y) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer, text, x, y, color, true);
        }
    }

    private record GapRow(int h) implements Row {
        public int height() { return h; }
        public void draw(DrawContext ctx, int x, int y) {}
    }

    private record BlockRow(String itemId, String name) implements Row {
        public int height() { return 36; }
        public void draw(DrawContext ctx, int x, int y) {
            Item item = itemOf(itemId);
            if (item != Items.AIR) {
                Matrix3x2fStack m = ctx.getMatrices();
                m.pushMatrix();
                m.translate(x, y + 1);
                m.scale(2.0f, 2.0f);
                ctx.drawItem(new ItemStack(item), 0, 0);
                m.popMatrix();
            }
            ctx.drawText(MinecraftClient.getInstance().textRenderer, name, x + 42, y + 12, C_GOLD, true);
            ctx.fill(x, y + 33, x + 240, y + 34, 0x443A4A6A);
        }
    }

    private record GridRow(CraftGrid grid) implements Row {
        public int height() {
            return 16 + gridH() + 8;
        }
        private int gridH() {
            return grid.pattern().length * 18 + (grid.pattern().length - 1) * 2;
        }
        public void draw(DrawContext ctx, int x, int y) {
            ctx.drawText(MinecraftClient.getInstance().textRenderer, grid.title(), x, y + 2, C_GOLD, true);
            int gy = y + 14;
            String[] pattern = grid.pattern();
            int cols = pattern[0].length();
            int gx = x;
            for (int r = 0; r < pattern.length; r++) {
                for (int cIdx = 0; cIdx < cols; cIdx++) {
                    char key = pattern[r].charAt(cIdx);
                    Slot slot = grid.keys().get(key);
                    int cx = gx + cIdx * 20;
                    int cy = gy + r * 20;
                    if (slot == null) {
                        ctx.fill(cx, cy, cx + 18, cy + 18, 0xFF12182A);
                    } else {
                        ctx.fill(cx, cy, cx + 18, cy + 18, 0xFF1B2338);
                        ctx.fill(cx, cy, cx + 18, cy + 1, 0xFF3A4A6A);
                        ctx.fill(cx, cy + 17, cx + 18, cy + 18, 0xFF3A4A6A);
                        ctx.fill(cx, cy, cx + 1, cy + 18, 0xFF3A4A6A);
                        ctx.fill(cx + 17, cy, cx + 18, cy + 18, 0xFF3A4A6A);
                        Item item = itemOf(slot.item());
                        if (item != Items.AIR) {
                            ctx.drawItem(new ItemStack(item), cx + 1, cy + 1);
                        }
                        if (slot.count() > 1) {
                            ctx.drawText(MinecraftClient.getInstance().textRenderer, String.valueOf(slot.count()),
                                    cx + 9, cy + 8, 0xFFFFFFFF, true);
                        }
                    }
                }
            }
            int gridW = cols * 20 - 2;
            int gMid = gy + gridH() / 2 - 4;
            ctx.drawText(MinecraftClient.getInstance().textRenderer, "→", gx + gridW + 4, gMid, C_GOLD, true);
            int rx = gx + gridW + 18;
            ctx.fill(rx, gy, rx + 18, gy + 18, 0xFF1B2338);
            ctx.fill(rx, gy, rx + 18, gy + 1, 0xFFE8C86A);
            ctx.fill(rx, gy + 17, rx + 18, gy + 18, 0xFFE8C86A);
            ctx.fill(rx, gy, rx + 1, gy + 18, 0xFFE8C86A);
            ctx.fill(rx + 17, gy, rx + 18, gy + 18, 0xFFE8C86A);
            Item result = itemOf(grid.result());
            if (result != Items.AIR) {
                ctx.drawItem(new ItemStack(result), rx + 1, gy + 1);
                if (grid.resultCount() > 1) {
                    ctx.drawText(MinecraftClient.getInstance().textRenderer, String.valueOf(grid.resultCount()),
                            rx + 9, gy + 8, 0xFFFFFFFF, true);
                }
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
                rows.add(new BlockRow(e.id(), e.name()));
                rows.add(new GapRow(3));
                for (String desc : e.description()) {
                    wrapIntoRows(desc, C_DESC);
                }
                rows.add(new GapRow(6));
                for (CraftGrid g : e.crafts()) {
                    rows.add(new GridRow(g));
                }
                rows.add(new GapRow(14));
            }
        }
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
        // Вкладки
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
        context.fill(0, 0, this.width, this.height, 0xEE0F1420);

        // Заголовок
        context.fill(0, 0, this.width, HEADER_H, 0xF21B2338);
        context.fill(0, HEADER_H, this.width, HEADER_H + 1, 0xFF3A4A6A);
        String title = "「Заметки путешественника」";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2,
                (HEADER_H - 9) / 2, C_GOLD, true);
        String ver = "Teyvat 0.8.9";
        context.drawText(this.textRenderer, ver, this.width - this.textRenderer.getWidth(ver) - 10,
                (HEADER_H - 9) / 2, C_HINT, true);

        // Вкладки
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

        // Контент
        context.enableScissor(contentX0, contentY0, contentX1 - contentX0, contentY1 - contentY0);
        int y = contentY0 - (int) scroll;
        for (Row r : rows) {
            if (y + r.height() >= contentY0 && y <= contentY1) {
                r.draw(context, contentX0, y);
            }
            y += r.height();
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
        context.drawText(this.textRenderer, hint, 12, this.height - 17, C_HINT, true);
    }
}
