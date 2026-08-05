package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.teyvat.network.TravelerChoicePayload;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static net.teyvat.client.TravelerNotesContent.C_GOLD;
import static net.teyvat.client.TravelerNotesContent.C_HINT;

/**
 * Экран первого входа: фон-скриншот Селестии без затемнения и выбор путешественника
 * (Люмин / Итэр) с живыми 3D-моделями в полный рост. Контент отступает от границ экрана.
 * Показывается один раз на сервере (тег teyvat:traveler_* сохраняет выбор) и работает
 * и в одиночной игре, и на сервере. Выбор косметический: скин увидят все игроки с модом.
 */
public class TravelerChoiceScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.of("teyvat", "textures/gui/spawn_background.png");
    private static final int CARD_GAP = 26;

    private record Card(String name, String desc, String button, String choice) {}

    private final List<Card> cards = List.of(
            new Card("Люмин", "Путешественница, что ищет своего брата на дорогах Тейвата.",
                    "Выбрать Люмин", "lumine"),
            new Card("Итэр", "Путешественник, что ищет свою сестру на дорогах Тейвата.",
                    "Выбрать Итэра", "aether"));

    private final TravelerPreviewPlayer[] previews = new TravelerPreviewPlayer[2];
    private long age = 0;

    public TravelerChoiceScreen() {
        super(Text.literal("Выбор путешественника"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mx = click.x();
        double my = click.y();
        if (click.button() == 0) {
            int[] box = cardBox();
            for (int i = 0; i < cards.size(); i++) {
                int cx = box[0] + i * (box[2] + CARD_GAP);
                int cy = box[1];
                if (mx >= cx && mx < cx + box[2] && my >= cy && my < cy + box[3]) {
                    choose(cards.get(i));
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

    private int pad() {
        return Math.max(12, Math.min(28, this.width / 32));
    }

    /** [x0, y0, cardW, cardH] — карточки вписаны в экран с отступами со всех сторон. */
    private int[] cardBox() {
        int pad = pad();
        int top = pad + 48;
        int bottom = this.height - pad - 20;
        int availW = this.width - pad * 2;
        int availH = Math.max(120, bottom - top);
        int cardW = Math.min(252, (availW - CARD_GAP) / 2);
        int cardH = Math.min(332, availH);
        int totalW = cardW * 2 + CARD_GAP;
        int x0 = (this.width - totalW) / 2;
        int y0 = top + (availH - cardH) / 2;
        return new int[]{x0, y0, cardW, cardH};
    }

    private TravelerPreviewPlayer preview(Card card, int index) {
        if (previews[index] == null && this.client != null && this.client.world instanceof ClientWorld world) {
            previews[index] = new TravelerPreviewPlayer(world, card.choice());
        }
        return previews[index];
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.age++;

        // Фон-скриншот во весь экран, без затемнения (сохраняем пропорции).
        float scale = Math.max(this.width / 1024f, this.height / 576f);
        int bw = (int) (1024 * scale);
        int bh = (int) (576 * scale);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                (this.width - bw) / 2, (this.height - bh) / 2, 0f, 0f, bw, bh, 1024, 576);

        int pad = pad();
        int[] box = cardBox();

        // Заголовок.
        String title = "«Выбор путешественника»";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, pad + 8, C_GOLD, true);
        String sub = "Селестия ждёт путника. Кто отправится в путь?";
        context.drawText(this.textRenderer, sub,
                (this.width - this.textRenderer.getWidth(sub)) / 2, pad + 26, C_HINT, true);

        for (int i = 0; i < cards.size(); i++) {
            int cx = box[0] + i * (box[2] + CARD_GAP);
            drawCard(context, i, cx, box[1], box[2], box[3], mouseX, mouseY, delta);
        }

        String hint = "Пока что выбор косметический: скин увидят все игроки с модом · 1 / 2 — выбрать · Esc — закрыть";
        context.drawText(this.textRenderer, hint,
                (this.width - this.textRenderer.getWidth(hint)) / 2, this.height - pad - 12, C_HINT, true);
    }

    private void drawCard(DrawContext context, int i, int cx, int cy, int cardW, int cardH,
                          int mouseX, int mouseY, float delta) {
        Card card = cards.get(i);
        boolean hover = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH;

        context.fill(cx, cy, cx + cardW, cy + cardH, 0xC21B2338);
        context.fill(cx, cy, cx + cardW, cy + 1, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy, cx + 1, cy + cardH, hover ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx + cardW - 1, cy, cx + cardW, cy + cardH, hover ? C_GOLD : 0xFF3A4A6A);

        // Живая 3D-модель персонажа.
        int mh = cardH - 92;
        int mx1 = cx + 10;
        int mx2 = cx + cardW - 10;
        int my1 = cy + 10;
        int my2 = cy + 10 + mh;
        TravelerPreviewPlayer player = preview(card, i);
        if (player != null) {
            player.age = (int) this.age;
            drawPlayerModel(context, player, mx1, my1, mx2, my2, delta);
        }

        // Имя и описание.
        int nameY = my2 + 10;
        context.drawText(this.textRenderer, card.name(),
                cx + (cardW - this.textRenderer.getWidth(card.name())) / 2, nameY, C_GOLD, true);
        int descY = nameY + 17;
        for (String line : wrap(card.desc(), cardW - 22)) {
            context.drawText(this.textRenderer, line,
                    cx + (cardW - this.textRenderer.getWidth(line)) / 2, descY, 0xFFD8D2C4, true);
            descY += 10;
        }

        // Кнопка.
        int by = cy + cardH - 36;
        int bw = cardW - 36;
        int bx = cx + (cardW - bw) / 2;
        boolean bh = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 26;
        context.fill(bx, by, bx + bw, by + 26, hover ? 0xFF2A3654 : 0xFF222C44);
        context.fill(bx, by, bx + bw, by + 1, 0xFFE8C86A);
        context.fill(bx, by + 25, bx + bw, by + 26, 0xFFE8C86A);
        context.drawText(this.textRenderer, card.button(),
                bx + (bw - this.textRenderer.getWidth(card.button())) / 2, by + 8, bh ? C_GOLD : C_HINT, true);
    }

    /** Рендер модели персонажа в GUI: лёгкий разворот, шаг на месте, скин из мода. */
    private void drawPlayerModel(DrawContext context, AbstractClientPlayerEntity player,
                                 int x1, int y1, int x2, int y2, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        float time = (this.age + tickDelta) / 20.0f;
        drawPlayerModel(context, client.getEntityRenderDispatcher().getRenderer(player),
                player, x1, y1, x2, y2, tickDelta, time);
    }

    private static <T extends Entity, S extends EntityRenderState> void drawPlayerModel(
            DrawContext context, EntityRenderer<? super T, S> renderer, T entity,
            int x1, int y1, int x2, int y2, float tickDelta, float time) {
        S state = renderer.getAndUpdateRenderState(entity, tickDelta);
        state.light = 0xF000F0;
        state.hitbox = null;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState living) {
            living.limbSwingAmplitude = 1.0f;
            living.limbSwingAnimationProgress = time * 3.0f;
        }
        float eyeHeight = entity.getStandingEyeHeight();
        float scale = (y2 - y1) / eyeHeight;
        Vector3f camera = new Vector3f(0.0f, entity.getHeight() / 2.0f + 0.35f * eyeHeight, 0.0f);
        float yaw = time * 0.5f;
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateY(yaw);
        Quaternionf look = new Quaternionf();

        context.enableScissor(x1, y1, x2, y2);
        context.addEntity(state, scale, camera, rotation, look, x1, y1, x2, y2);
        context.disableScissor();
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
