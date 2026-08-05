package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.teyvat.network.TravelerChoicePayload;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static net.teyvat.client.TravelerNotesContent.C_GOLD;
import static net.teyvat.client.TravelerNotesContent.C_HINT;

/**
 * Экран первого входа: фон-скриншот Селестии и выбор путешественника (Люмин / Итэр).
 * Показывается один раз на сервере (тег teyvat:traveler_* сохраняет выбор) и работает
 * и в одиночной игре, и на сервере. Выбор косметический: скин увидят все игроки с модом.
 */
public class TravelerChoiceScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.of("teyvat", "textures/gui/spawn_background.png");
    private static final Identifier SKIN_LUMINE = Identifier.of("teyvat", "textures/skin/lumine.png");
    private static final Identifier SKIN_AETHER = Identifier.of("teyvat", "textures/skin/aether.png");

    private static final int CARD_W = 250;
    private static final int CARD_H = 292;
    private static final int CARD_GAP = 30;
    private static final int HEAD = 104;
    private static final int BTN_H = 30;

    private record Card(String name, String desc, String button, Identifier skin, String choice) {}

    private final List<Card> cards = List.of(
            new Card("Люмин", "Путешественница, что ищет своего брата на дорогах Тейвата.",
                    "Выбрать Люмин", SKIN_LUMINE, "lumine"),
            new Card("Итэр", "Путешественник, что ищет свою сестру на дорогах Тейвата.",
                    "Выбрать Итэра", SKIN_AETHER, "aether"));

    public TravelerChoiceScreen() {
        super(Text.literal("Выбор путешественника"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int cardX(int i) {
        int total = cards.size() * CARD_W + (cards.size() - 1) * CARD_GAP;
        return (this.width - total) / 2 + i * (CARD_W + CARD_GAP);
    }

    private int cardY() {
        return (this.height - CARD_H) / 2 + 18;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            for (int i = 0; i < cards.size(); i++) {
                Card card = cards.get(i);
                int cx = cardX(i);
                int cy = cardY();
                if (mx >= cx && mx < cx + CARD_W && my >= cy && my < cy + CARD_H) {
                    choose(card);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    private void choose(Card card) {
        ClientPlayNetworking.send(new TravelerChoicePayload(card.choice()));
        if (this.client != null && this.client.player != null) {
            TravelerChoiceClient.set(this.client.player.getUuid(), card.choice());
            this.client.player.sendMessage(Text.literal(
                    "§e[Teyvat] §fПутешественник выбран: §b" + card.name() + "§f."), false);
        }
        this.close();
    }

    @Override
    public boolean keyPressed(KeyInput key) {
        if (key.key() == GLFW.GLFW_KEY_1 && cards.size() > 0) {
            choose(cards.get(0));
            return true;
        }
        if (key.key() == GLFW.GLFW_KEY_2 && cards.size() > 1) {
            choose(cards.get(1));
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Фон-скриншот во весь экран (сохраняем пропорции).
        float scale = Math.max(this.width / 1024f, this.height / 576f);
        int bw = (int) (1024 * scale);
        int bh = (int) (576 * scale);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                (this.width - bw) / 2, (this.height - bh) / 2, 0f, 0f, bw, bh, 1024, 576);
        context.fill(0, 0, this.width, this.height, 0xAA141B2E);

        // Заголовок.
        String title = "«Выбор путешественника»";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, 26, C_GOLD, true);
        String sub = "Селестия ждёт путника. Кто отправится в путь?";
        context.drawText(this.textRenderer, sub,
                (this.width - this.textRenderer.getWidth(sub)) / 2, 42, C_HINT, true);

        for (int i = 0; i < cards.size(); i++) {
            drawCard(context, i, mouseX, mouseY);
        }

        String hint = "Пока что выбор косметический: скин увидят все игроки с модом · 1 / 2 — выбрать · Esc — закрыть";
        context.drawText(this.textRenderer, hint,
                (this.width - this.textRenderer.getWidth(hint)) / 2, this.height - 24, C_HINT, true);
    }

    private void drawCard(DrawContext context, int i, int mouseX, int mouseY) {
        Card card = cards.get(i);
        int cx = cardX(i);
        int cy = cardY();
        boolean hover = mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H;

        context.fill(cx, cy, cx + CARD_W, cy + CARD_H, 0xE61B2338);
        context.fill(cx, cy, cx + CARD_W, cy + 1, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy + CARD_H - 1, cx + CARD_W, cy + CARD_H, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy, cx + 1, cy + CARD_H, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx + CARD_W - 1, cy, cx + CARD_W, cy + CARD_H, hover ? C_GOLD : 0xFF3A4A6A);

        int hx = cx + (CARD_W - HEAD) / 2;
        int hy = cy + 22;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, card.skin(), hx, hy, 8f, 8f, HEAD, HEAD, 64, 64);

        int nameY = hy + HEAD + 16;
        context.drawText(this.textRenderer, card.name(),
                cx + (CARD_W - this.textRenderer.getWidth(card.name())) / 2, nameY, C_GOLD, true);

        int descY = nameY + 20;
        for (String line : wrap(card.desc(), CARD_W - 24)) {
            context.drawText(this.textRenderer, line,
                    cx + (CARD_W - this.textRenderer.getWidth(line)) / 2, descY, 0xFFD8D2C4, true);
            descY += 11;
        }

        int by = cy + CARD_H - BTN_H - 14;
        int bw = CARD_W - 40;
        int bx = cx + (CARD_W - bw) / 2;
        boolean bh = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + BTN_H;
        context.fill(bx, by, bx + bw, by + BTN_H, hover ? 0xFF2A3654 : 0xFF222C44);
        context.fill(bx, by, bx + bw, by + 1, 0xFFE8C86A);
        context.fill(bx, by + BTN_H - 1, bx + bw, by + BTN_H, 0xFFE8C86A);
        context.drawText(this.textRenderer, card.button(),
                bx + (bw - this.textRenderer.getWidth(card.button())) / 2, by + 9, bh ? C_GOLD : C_HINT, true);
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new java.util.ArrayList<>();
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
}
