package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
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
 * (Люмин / Итэр). В покое модели стоят лицом к игроку; при наведении начинают шагать и
 * покачиваться, при уходе курсора плавно возвращаются лицом и замирают. Контент отступает
 * от границ экрана с одинаковыми промежутками.
 */
public class TravelerChoiceScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.of("teyvat", "textures/gui/spawn_background.png");
    private static final int BG_W = 924;
    private static final int BG_H = 526; // оригинальное разрешение скриншота, без увеличения

    private static final int CARD_GAP = 26;
    private static final int CARD_PAD = 10;      // внутренний отступ карточки
    private static final int GAP = 12;           // одинаковый отступ между элементами текста
    private static final int NAME_H = 9;
    private static final int DESC_LINE_H = 10;
    private static final int BTN_H = 26;
    private static final int BTN_MARGIN = 14;

    private record Card(String name, String desc, String button, String choice) {}

    private final List<Card> cards = List.of(
            new Card("Люмин", "Путешественница, что ищет своего брата на дорогах Тейвата.",
                    "Выбрать Люмин", "lumine"),
            new Card("Итэр", "Путешественник, что ищет свою сестру на дорогах Тейвата.",
                    "Выбрать Итэра", "aether"));

    private final TravelerPreviewPlayer[] previews = new TravelerPreviewPlayer[2];
    private final float[] hover = new float[2];   // 0..1, плавно к цели
    private final float[] yaw = new float[2];     // текущий угол поворота модели
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
                if (mx >= cx && mx < cx + box[2] && my >= box[1] && my < box[1] + box[3]) {
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
        int bottom = this.height - pad - 12;
        int availW = this.width - pad * 2;
        int availH = Math.max(150, bottom - top);
        int cardW = Math.min(252, (availW - CARD_GAP) / 2);
        int cardH = Math.min(336, availH);
        int totalW = cardW * 2 + CARD_GAP;
        int x0 = (this.width - totalW) / 2;
        int y0 = top + (availH - cardH) / 2;
        return new int[]{x0, y0, cardW, cardH};
    }

    private boolean isOver(int i, int mouseX, int mouseY) {
        int[] box = cardBox();
        int cx = box[0] + i * (box[2] + CARD_GAP);
        return mouseX >= cx && mouseX < cx + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3];
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
        float time = (this.age + delta) / 20.0f;
        float frameSec = delta * 0.05f;
        int[] box = cardBox();

        // Плавная анимация наведения: шаг и покачивание включаются/гаснут плавно.
        for (int i = 0; i < cards.size(); i++) {
            boolean over = isOver(i, mouseX, mouseY);
            float target = over ? 1f : 0f;
            hover[i] += (target - hover[i]) * Math.min(1f, frameSec * 7f);
            float targetYaw = over ? (float) Math.sin(time * 1.1f) * 0.45f : 0f;
            yaw[i] += (targetYaw - yaw[i]) * Math.min(1f, frameSec * 7f);
        }

        // Фон-скриншот: без приближения, вписан целиком, подложка по краям.
        context.fill(0, 0, this.width, this.height, 0xFF0B0F1A);
        float scale = Math.min(1.0f, Math.min(this.width / (float) BG_W, this.height / (float) BG_H));
        int bw = (int) (BG_W * scale);
        int bh = (int) (BG_H * scale);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                (this.width - bw) / 2, (this.height - bh) / 2, 0f, 0f, bw, bh, BG_W, BG_H);

        // Заголовок.
        String title = "«Выбор путешественника»";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, pad() + 8, C_GOLD, true);
        String sub = "Селестия ждёт путника. Кто отправится в путь?";
        context.drawText(this.textRenderer, sub,
                (this.width - this.textRenderer.getWidth(sub)) / 2, pad() + 26, C_HINT, true);

        for (int i = 0; i < cards.size(); i++) {
            int cx = box[0] + i * (box[2] + CARD_GAP);
            drawCard(context, i, cx, box[1], box[2], box[3], mouseX, mouseY, delta);
        }
    }

    private void drawCard(DrawContext context, int i, int cx, int cy, int cardW, int cardH,
                          int mouseX, int mouseY, float delta) {
        Card card = cards.get(i);
        boolean hovered = isOver(i, mouseX, mouseY);
        List<String> descLines = wrap(card.desc(), cardW - CARD_PAD * 2);
        int descH = descLines.size() * DESC_LINE_H;
        int modelH = cardH - (CARD_PAD + GAP + NAME_H + GAP + descH + GAP + BTN_H + BTN_MARGIN);

        context.fill(cx, cy, cx + cardW, cy + cardH, 0xC21B2338);
        context.fill(cx, cy, cx + cardW, cy + 1, hovered ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, hovered ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx, cy, cx + 1, cy + cardH, hovered ? C_GOLD : 0xFF3A4A6A);
        context.fill(cx + cardW - 1, cy, cx + cardW, cy + cardH, hovered ? C_GOLD : 0xFF3A4A6A);

        // Живая 3D-модель персонажа.
        int mx1 = cx + CARD_PAD;
        int mx2 = cx + cardW - CARD_PAD;
        int my1 = cy + CARD_PAD;
        int my2 = my1 + modelH;
        TravelerPreviewPlayer player = preview(card, i);
        if (player != null) {
            player.age = (int) this.age;
            float time = (this.age + delta) / 20.0f;
            drawPlayerModel(context, player, mx1, my1, mx2, my2, delta, hover[i], yaw[i], time * 3.0f);
        }

        // Имя.
        int y = my2 + GAP;
        context.drawText(this.textRenderer, card.name(),
                cx + (cardW - this.textRenderer.getWidth(card.name())) / 2, y, C_GOLD, true);

        // Описание.
        y += NAME_H + GAP;
        for (String line : descLines) {
            context.drawText(this.textRenderer, line,
                    cx + (cardW - this.textRenderer.getWidth(line)) / 2, y, 0xFFD8D2C4, true);
            y += DESC_LINE_H;
        }

        // Кнопка.
        int by = cy + cardH - BTN_MARGIN - BTN_H;
        int bw = cardW - 36;
        int bx = cx + (cardW - bw) / 2;
        boolean bh = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + BTN_H;
        context.fill(bx, by, bx + bw, by + BTN_H, hovered ? 0xFF2A3654 : 0xFF222C44);
        context.fill(bx, by, bx + bw, by + 1, 0xFFE8C86A);
        context.fill(bx, by + BTN_H - 1, bx + bw, by + BTN_H, 0xFFE8C86A);
        context.drawText(this.textRenderer, card.button(),
                bx + (bw - this.textRenderer.getWidth(card.button())) / 2, by + 8, bh ? C_GOLD : C_HINT, true);
    }

    /** Рендер модели в GUI: в покое лицом к зрителю, при наведении — шаг и покачивание. */
    private void drawPlayerModel(DrawContext context, AbstractClientPlayerEntity player,
                                 int x1, int y1, int x2, int y2, float tickDelta,
                                 float hoverAmount, float yawAmount, float walkPhase) {
        MinecraftClient client = MinecraftClient.getInstance();
        drawPlayerModel(context, client.getEntityRenderDispatcher().getRenderer(player),
                player, x1, y1, x2, y2, tickDelta, hoverAmount, yawAmount, walkPhase);
    }

    private static <T extends Entity, S extends EntityRenderState> void drawPlayerModel(
            DrawContext context, EntityRenderer<? super T, S> renderer, T entity,
            int x1, int y1, int x2, int y2, float tickDelta,
            float hoverAmount, float yawAmount, float walkPhase) {
        S state = renderer.getAndUpdateRenderState(entity, tickDelta);
        state.light = 0xF000F0;
        state.hitbox = null;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState living) {
            living.limbSwingAmplitude = hoverAmount;
            living.limbSwingAnimationProgress = walkPhase;
        }

        float entityH = entity.getHeight();
        float scale = (y2 - y1) / entityH * 0.92f;
        Vector3f camera = new Vector3f(0.0f, entityH * 0.5f, 0.0f);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateY((float) Math.PI + yawAmount);
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
