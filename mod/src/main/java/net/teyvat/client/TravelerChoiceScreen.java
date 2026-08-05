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
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;
import net.teyvat.network.TravelerChoicePayload;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;

import static net.teyvat.client.TravelerNotesContent.C_GOLD;
import static net.teyvat.client.TravelerNotesContent.C_HINT;

/**
 * Экран первого входа: фон-скриншот целиком на весь экран (без кадрирования) и выбор
 * путешественника (Люмин / Итэр). Крупные модели персонажей слева, узкий текст — справа
 * от них. В покое модели стоят лицом к игроку; при наведении начинают шагать, покачиваться
 * и светиться золотым силуэтом самой модели. После выбора — белая вспышка, которая плавно
 * растворяется в мир.
 */
public class TravelerChoiceScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.of("teyvat", "textures/gui/spawn_background.png");
    private static final int BG_W = 924;   // оригинальное разрешение скриншота
    private static final int BG_H = 526;

    private static final int CARD_GAP = 26;
    private static final int CARD_PAD = 6;         // внутренний отступ карточки
    private static final int MODEL_TEXT_GAP = 8;   // зазор между моделью и текстом
    private static final int TEXT_W = 96;          // узкая текстовая колонка справа
    private static final int NAME_H = 9;
    private static final int DESC_LINE_H = 10;
    private static final int BTN_H = 26;
    private static final int GAP = 12;             // одинаковый отступ между элементами текста

    /** Золотая текстура-силуэт для подсветки модели (создаётся один раз). */
    private static Identifier goldSkin;

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
    private boolean closing = false;              // выбран персонаж, идёт вспышка
    private boolean dissolving = false;           // вспышка достигла пика и растворяется в мир
    private float flash = 0f;                     // 0..1, белая вспышка

    public TravelerChoiceScreen() {
        super(Text.literal("Выбор путешественника"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        if (closing) {
            return true;
        }
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
        if (closing) {
            return;
        }
        ClientPlayNetworking.send(new TravelerChoicePayload(card.choice()));
        if (this.client != null && this.client.player != null) {
            TravelerChoiceClient.set(this.client.player.getUuid(), card.choice());
            this.client.player.sendMessage(Text.literal(
                    "§e[Teyvat] §fПутешественник выбран: §b" + card.name() + "§f."), false);
        }
        closing = true;
        flash = 0f;
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
        int cardW = Math.min(520, (availW - CARD_GAP) / 2);
        int cardH = availH;   // без потолка: модели максимально крупные
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

        // Плавная анимация наведения: шаг, покачивание и подсветка включаются/гаснут плавно.
        for (int i = 0; i < cards.size(); i++) {
            boolean over = !closing && isOver(i, mouseX, mouseY);
            float target = over ? 1f : 0f;
            hover[i] += (target - hover[i]) * Math.min(1f, frameSec * 7f);
            float targetYaw = over ? (float) Math.sin(time * 1.1f) * 0.45f : 0f;
            yaw[i] += (targetYaw - yaw[i]) * Math.min(1f, frameSec * 7f);
        }

        // Вспышка: растёт до полного белого, затем плавно растворяется, открывая мир.
        if (closing) {
            if (!dissolving) {
                flash = Math.min(1f, flash + frameSec * 3.2f);
                if (flash >= 1f) {
                    dissolving = true;
                }
            } else {
                flash = Math.max(0f, flash - frameSec * 2.2f);
                if (flash <= 0f) {
                    this.close();
                    return;
                }
            }
        }

        // Во время растворения интерфейс не рисуем — за белой пеленой виден мир.
        if (!(closing && dissolving)) {
            drawBackground(context);
            drawTitle(context);
            for (int i = 0; i < cards.size(); i++) {
                int cx = box[0] + i * (box[2] + CARD_GAP);
                drawCard(context, i, cx, box[1], box[2], box[3], mouseX, mouseY, delta);
            }
        }

        if (closing) {
            int alpha = (int) (flash * 255f);
            context.fill(0, 0, this.width, this.height, (alpha << 24) | 0xFFFFFF);
        }
    }

    /** Фон-скриншот: заполняет весь экран, центр картинки совпадает с центром экрана. */
    private void drawBackground(DrawContext context) {
        float scale = Math.max(this.width / (float) BG_W, this.height / (float) BG_H);
        int bw = (int) (BG_W * scale);
        int bh = (int) (BG_H * scale);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                (this.width - bw) / 2, (this.height - bh) / 2, 0f, 0f, bw, bh, BG_W, BG_H);
    }

    private void drawTitle(DrawContext context) {
        String title = "«Выбор путешественника»";
        context.drawText(this.textRenderer, title,
                (this.width - this.textRenderer.getWidth(title)) / 2, pad() + 8, C_GOLD, true);
        String sub = "Селестия ждёт путника. Кто отправится в путь?";
        context.drawText(this.textRenderer, sub,
                (this.width - this.textRenderer.getWidth(sub)) / 2, pad() + 26, C_HINT, true);
    }

    private void drawCard(DrawContext context, int i, int cx, int cy, int cardW, int cardH,
                          int mouseX, int mouseY, float delta) {
        Card card = cards.get(i);
        boolean hovered = isOver(i, mouseX, mouseY);

        // Полупрозрачная карточка: фон-скриншот остаётся виден.
        context.fill(cx, cy, cx + cardW, cy + cardH, 0x991B2338);
        context.fill(cx, cy, cx + cardW, cy + 1, 0xFF3A4A6A);
        context.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, 0xFF3A4A6A);
        context.fill(cx, cy, cx + 1, cy + cardH, 0xFF3A4A6A);
        context.fill(cx + cardW - 1, cy, cx + cardW, cy + cardH, 0xFF3A4A6A);

        // Модель занимает почти всю карточку; узкий текст — у правого края.
        int modelW = cardW - CARD_PAD * 2 - MODEL_TEXT_GAP - TEXT_W;
        int mx1 = cx + CARD_PAD;
        int mx2 = mx1 + modelW;
        int my1 = cy + CARD_PAD;
        int my2 = cy + cardH - CARD_PAD;

        TravelerPreviewPlayer player = preview(card, i);
        if (player != null) {
            player.age = (int) this.age;
            float time = (this.age + delta) / 20.0f;
            float glowPulse = 0.5f + 0.5f * (float) Math.sin(time * 3.0);
            drawPlayerModel(context, player, mx1, my1, mx2, my2, delta, hover[i], yaw[i],
                    time * 3.0f, glowPulse);
        }

        // Текстовая колонка, выровненная по вертикали относительно карточки.
        int tx = cx + CARD_PAD + modelW + MODEL_TEXT_GAP;
        List<String> descLines = wrap(card.desc(), TEXT_W);
        int descH = descLines.size() * DESC_LINE_H;
        int blockH = NAME_H + GAP + descH + GAP + BTN_H;
        int y = cy + Math.max(CARD_PAD, (cardH - blockH) / 2);

        context.drawText(this.textRenderer, card.name(),
                tx + (TEXT_W - this.textRenderer.getWidth(card.name())) / 2, y, C_GOLD, true);
        y += NAME_H + GAP;

        for (String line : descLines) {
            context.drawText(this.textRenderer, line,
                    tx + (TEXT_W - this.textRenderer.getWidth(line)) / 2, y, 0xFFD8D2C4, true);
            y += DESC_LINE_H;
        }

        int by = y + GAP;
        boolean bh = mouseX >= tx && mouseX < tx + TEXT_W && mouseY >= by && mouseY < by + BTN_H;
        context.fill(tx, by, tx + TEXT_W, by + BTN_H, hovered ? 0xFF2A3654 : 0xFF222C44);
        context.fill(tx, by, tx + TEXT_W, by + 1, 0xFFE8C86A);
        context.fill(tx, by + BTN_H - 1, tx + TEXT_W, by + BTN_H, 0xFFE8C86A);
        context.drawText(this.textRenderer, card.button(),
                tx + (TEXT_W - this.textRenderer.getWidth(card.button())) / 2, by + 8, bh ? C_GOLD : C_HINT, true);
    }

    /** Рендер модели в GUI: в покое лицом к зрителю, при наведении — шаг, покачивание и золотое свечение. */
    private void drawPlayerModel(DrawContext context, AbstractClientPlayerEntity player,
                                 int x1, int y1, int x2, int y2, float tickDelta,
                                 float hoverAmount, float yawAmount, float walkPhase, float glowPulse) {
        MinecraftClient client = MinecraftClient.getInstance();
        drawPlayerModel(context, client.getEntityRenderDispatcher().getRenderer(player),
                player, x1, y1, x2, y2, tickDelta, hoverAmount, yawAmount, walkPhase, glowPulse);
    }

    private static <T extends Entity, S extends EntityRenderState> void drawPlayerModel(
            DrawContext context, EntityRenderer<? super T, S> renderer, T entity,
            int x1, int y1, int x2, int y2, float tickDelta,
            float hoverAmount, float yawAmount, float walkPhase, float glowPulse) {
        S state = renderer.getAndUpdateRenderState(entity, tickDelta);
        prepareState(state, hoverAmount, walkPhase);

        float entityH = entity.getHeight();
        float visualW = entity.getWidth() * 1.35f; // фигура с руками
        float scale = Math.min((x2 - x1) / visualW, (y2 - y1) / entityH) * 0.98f;
        Vector3f camera = new Vector3f(0.0f, entityH * 0.5f, 0.0f);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateY((float) Math.PI + yawAmount);
        Quaternionf look = new Quaternionf();

        context.enableScissor(x1, y1, x2, y2);
        if (hoverAmount > 0.01f) {
            // Золотой силуэт самой скин-модели: увеличенная копия в золоте, светится за персонажем.
            S glow = renderer.getAndUpdateRenderState(entity, tickDelta);
            prepareState(glow, hoverAmount, walkPhase);
            if (glow instanceof PlayerEntityRenderState ps) {
                ps.skinTextures = ps.skinTextures.withOverride(new SkinTextures.SkinOverride(
                        Optional.of(new AssetInfo.TextureAssetInfo(goldSkin())),
                        Optional.empty(), Optional.empty(), Optional.empty()));
            }
            float glowScale = scale * (1.03f + 0.04f * hoverAmount * glowPulse);
            context.disableScissor();
            context.enableScissor(x1 - 24, y1 - 24, x2 + 24, y2 + 24);
            context.addEntity(glow, glowScale, camera, rotation, look, x1, y1, x2, y2);
            context.disableScissor();
            context.enableScissor(x1, y1, x2, y2);
        }
        context.addEntity(state, scale, camera, rotation, look, x1, y1, x2, y2);
        context.disableScissor();
    }

    private static <S extends EntityRenderState> void prepareState(S state, float hoverAmount, float walkPhase) {
        state.light = 0xF000F0;
        state.hitbox = null;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState living) {
            living.limbSwingAmplitude = hoverAmount;
            living.limbSwingAnimationProgress = walkPhase;
        }
    }

    /** Твёрдая золотая текстура (полупрозрачная) для силуэтной подсветки модели. */
    private static Identifier goldSkin() {
        if (goldSkin == null) {
            goldSkin = Identifier.of("teyvat", "gui/skin_gold");
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 8, 8, true);
            image.fillRect(0, 0, 8, 8, 0x90E8C86A);
            NativeImageBackedTexture texture =
                    new NativeImageBackedTexture(() -> "teyvat skin glow", image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(goldSkin, texture);
        }
        return goldSkin;
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
