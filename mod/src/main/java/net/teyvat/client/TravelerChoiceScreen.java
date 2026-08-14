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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.teyvat.network.TravelerChoicePayload;
import net.teyvat.client.paimon.PaimonManager;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;

import static net.teyvat.client.TravelerNotesContent.C_GOLD;
import static net.teyvat.client.TravelerNotesContent.C_HINT;

/**
 * Экран первого входа: фон-скриншот целиком на весь экран (без кадрирования) и выбор
 * путешественника (Люмин / Итэр). Крупные модели персонажей слева, узкий текст — справа
 * от них. Карточки въезжают с боков, заголовок проявляется как арт, фон медленно живёт
 * (зум + параллакс), по экрану плывут золотые частицы. В покое модели покачиваются,
 * при наведении шагают, а панель мягко светится. После выбора — момент героя: имя
 * персонажа крупно в цвете элемента и всплеск искр, затем белая вспышка в мир.
 */
public class TravelerChoiceScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.of("teyvat", "textures/gui/spawn_background.png");
    private static final int BG_W = 924;   // оригинальное разрешение скриншота
    private static final int BG_H = 526;

    private static final int CARD_GAP = 26;
    private static final int CARD_PAD = 6;         // внутренний отступ карточки
    private static final int MODEL_TEXT_GAP = 8;   // зазор между моделью и текстом
    private static final int TEXT_W = 128;         // текстовая колонка справа (анимешный шрифт шире)
    private static final int NAME_H = 20;
    private static final int DESC_LINE_H = 13;
    private static final int BTN_H = 32;
    private static final int GAP = 14;             // одинаковый отступ между элементами текста

    /** Длительность въезда карточек и проявления заголовка. */
    private static final long ENTRANCE_MS = 750;
    /** Длительность «момента героя» после выбора (имя + всплеск) до белой вспышки. */
    private static final float HERO_SEC = 0.65f;

    /** Элемент путешественника: цвет и имя для «момента героя». */
    private record Card(String name, String desc, String button, String choice, int color) {}

    private final List<Card> cards = List.of(
            new Card("Люмин", "Путешественница, что ищет своего брата на дорогах Тейвата.",
                    "Выбрать Люмин", "lumine", 0xFF7FE8D2),
            new Card("Итэр", "Путешественник, что ищет свою сестру на дорогах Тейвата.",
                    "Выбрать Итэра", "aether", 0xFFE8C86A));

    private final TravelerPreviewPlayer[] previews = new TravelerPreviewPlayer[2];
    private final float[] hover = new float[2];   // 0..1, плавно к цели
    private final float[] yaw = new float[2];     // текущий угол поворота модели

    /** Золотые частицы: позиция (0..1), скорость дрейфа, размер, фаза мерцания. */
    private record Particle(float x, float y, float speed, float size, float phase, float freq) {}
    private final Particle[] particles = initParticles();

    private final long openTime = Util.getMeasuringTimeMs();
    private long age = 0;
    private boolean closing = false;              // выбран персонаж, идёт «момент героя» и вспышка
    private boolean dissolving = false;           // вспышка достигла пика и растворяется в мир
    private float flash = 0f;                     // 0..1, белая вспышка
    private float flashHold = 0f;                 // время удержания полного белого
    private int chosen = -1;                      // индекс выбранного персонажа
    private float heroT = 0f;                     // 0..HERO_SEC — момент героя после выбора

    public TravelerChoiceScreen() {
        super(Text.literal("Выбор путешественника"));
    }

    private static Particle[] initParticles() {
        Random rnd = new Random(20260810L);
        Particle[] arr = new Particle[46];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new Particle(rnd.nextFloat(), rnd.nextFloat(),
                    0.006f + rnd.nextFloat() * 0.018f,
                    1.0f + rnd.nextFloat() * 1.4f,
                    rnd.nextFloat() * 6.283f,
                    0.7f + rnd.nextFloat() * 1.5f);
        }
        return arr;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Экран первого входа нельзя закрыть клавишей Escape — выбор обязателен. */
    @Override
    public boolean shouldCloseOnEsc() {
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
                int cx = cardX(i, box);
                if (mx >= cx && mx < cx + box[2] && my >= box[1] && my < box[1] + box[3]) {
                    choose(cards.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, bl);
    }

    /** True, пока экран закрывается белой вспышкой после выбора. */
    public boolean isClosing() {
        return closing;
    }

    private void choose(Card card) {
        if (closing) {
            return;
        }
        chosen = cards.indexOf(card);
        heroT = 0f;
        closing = true;
        flash = 0f;
        ClientPlayNetworking.send(new TravelerChoicePayload(card.choice()));
        if (this.client != null && this.client.player != null) {
            TravelerChoiceClient.set(this.client.player.getUuid(), card.choice());
            this.client.player.sendMessage(Text.literal(
                    "§e[Teyvat] §fПутешественник выбран: §b" + card.name() + "§f."), false);
        }
        // Паймон вылетает поприветствовать путешественника и знакомит его с миром.
        PaimonManager.remove();
        PaimonManager.startIntro();
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

    /** Текущая X-позиция карточки с учётом въезда с боков. */
    private int cardX(int i, int[] box) {
        float enter = easeOutCubic(Math.min(1f, (Util.getMeasuringTimeMs() - openTime) / (float) ENTRANCE_MS));
        float slide = (1f - enter) * this.width * 0.4f;
        int dir = i == 0 ? -1 : 1;
        return box[0] + i * (box[2] + CARD_GAP) + (int) (slide * dir);
    }

    private boolean isOver(int i, int mouseX, int mouseY) {
        int[] box = cardBox();
        int cx = cardX(i, box);
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
            // В покое — лёгкое покачивание из стороны в сторону, при наведении — шире.
            float idle = 0.10f * (float) Math.sin(time * 0.9 + i * 2.3);
            float targetYaw = over ? (float) Math.sin(time * 1.1f) * 0.45f : idle;
            yaw[i] += (targetYaw - yaw[i]) * Math.min(1f, frameSec * 7f);
        }

        // Сценарий после выбора: сначала «момент героя» (имя + всплеск), затем белая
        // вспышка, которая держится (даёт Паймон время появиться) и растворяется в мир.
        if (closing) {
            if (heroT < HERO_SEC) {
                heroT = Math.min(HERO_SEC, heroT + frameSec);
            } else if (!dissolving) {
                flash = Math.min(1f, flash + frameSec * 2.6f);
                if (flash >= 1f) {
                    flashHold += frameSec;
                    if (flashHold >= 0.6f) {
                        dissolving = true;
                    }
                }
            } else {
                flash = Math.max(0f, flash - frameSec * 1.5f);
                if (flash <= 0f) {
                    this.close();
                    return;
                }
            }
        }

        // Во время растворения интерфейс не рисуем — за белой пеленой виден мир.
        if (!(closing && dissolving)) {
            drawBackground(context, mouseX, mouseY);
            drawParticles(context, time);
            drawTitle(context);
            for (int i = 0; i < cards.size(); i++) {
                int cx = cardX(i, box);
                drawCard(context, i, cx, box[1], box[2], box[3], mouseX, mouseY, delta, hover[i]);
            }
            if (chosen >= 0 && heroT < HERO_SEC) {
                drawHeroMoment(context, time);
            }
        }

        if (closing && heroT >= HERO_SEC) {
            int alpha = (int) (flash * 255f);
            context.fill(0, 0, this.width, this.height, (alpha << 24) | 0xFFFFFF);
        }
    }

    /** Фон-скриншот: медленно живёт (зум туда-сюда) и чуть плывёт за мышью (параллакс),
     *  края затемнены виньеткой. Центр картинки совпадает с центром экрана. */
    private void drawBackground(DrawContext context, int mouseX, int mouseY) {
        float time = (this.age) / 20.0f;
        float zoom = 1.0f + 0.055f * (float) Math.sin(time * 0.12);
        float baseScale = Math.max(this.width / (float) BG_W, this.height / (float) BG_H) * zoom;
        int bw = (int) (BG_W * baseScale);
        int bh = (int) (BG_H * baseScale);
        // Параллакс: фон смещается в сторону, противоположную курсору.
        float px = (this.width / 2f - mouseX) * 0.02f;
        float py = (this.height / 2f - mouseY) * 0.02f;
        int x = (this.width - bw) / 2 + (int) px;
        int y = (this.height - bh) / 2 + (int) py;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND,
                x, y, 0f, 0f, bw, bh, BG_W, BG_H, BG_W, BG_H);

        // Виньетка: несколько слоёв затемнения к краям (светлее в центре).
        context.fill(0, 0, this.width, 28, 0x66000000);
        context.fill(0, this.height - 28, this.width, this.height, 0x66000000);
        context.fill(0, 0, 28, this.height, 0x55000000);
        context.fill(this.width - 28, 0, this.width, this.height, 0x55000000);
    }

    /** Золотые частицы: медленно плывут вверх, мягко мерцают, замыкаются по кругу. */
    private void drawParticles(DrawContext context, float time) {
        for (Particle p : particles) {
            float y = (p.y - time * p.speed) % 1f;
            if (y < 0f) {
                y += 1f;
            }
            float tw = 0.5f + 0.5f * (float) Math.sin(time * p.freq + p.phase);
            int a = (int) (22 + 34 * tw);
            int px = (int) (p.x * this.width);
            int py = (int) (y * this.height);
            int size = (int) Math.ceil(p.size);
            context.fill(px, py, px + size, py + size, (a << 24) | 0xE8C86A);
        }
    }

    /** Заголовок-арт: крупная золотая надпись со свечением, проявляется затуханием,
     *  по бокам линии с ромбами; подзаголовок появляется чуть позже. */
    private void drawTitle(DrawContext context) {
        long now = Util.getMeasuringTimeMs();
        float t = easeOutCubic(Math.min(1f, (now - openTime) / 620f));
        float subT = easeOutCubic(Math.min(1f, (now - openTime - 260f) / 620f));
        if (t <= 0f) {
            return;
        }
        String title = "«Выбор путешественника»";
        float scale = 1.3f + 0.35f * (1f - t);
        int tw0 = this.textRenderer.getWidth(title);
        int tw = (int) (tw0 * scale);
        int cx = (this.width - tw) / 2;
        int cy = pad() + 16;

        var ms = context.getMatrices();
        ms.pushMatrix();
        ms.translate(cx, cy);
        ms.scale(scale, scale);
        // Свечение: два слоя по диагоналям в полупрозрачном золоте.
        int glowA = (int) (90 * t);
        context.drawText(this.textRenderer, title, 2, 2, (glowA << 24) | 0xE8C86A, true);
        context.drawText(this.textRenderer, title, -1, 1, (glowA << 24) | 0x8C7440, true);
        // Основная надпись.
        context.drawText(this.textRenderer, title, 0, 0, C_GOLD, true);
        ms.popMatrix();

        // Линии с ромбами по бокам заголовка.
        int midY = cy + (int) (scale * 8) + 2;
        int lineX0 = pad() + 10;
        int lineX1 = cx - 18;
        int lineX2 = cx + tw + 18;
        int lineX3 = this.width - pad() - 10;
        if (lineX1 - lineX0 > 30) {
            context.fill(lineX0, midY, lineX1, midY + 1, 0xFF8C7440);
            drawDiamond(context, (lineX0 + lineX1) / 2, midY, 3, 0xFFE8C86A);
        }
        if (lineX3 - lineX2 > 30) {
            context.fill(lineX2, midY, lineX3, midY + 1, 0xFF8C7440);
            drawDiamond(context, (lineX2 + lineX3) / 2, midY, 3, 0xFFE8C86A);
        }

        if (subT > 0f) {
            String sub = "Селестия ждёт путника. Кто отправится в путь?";
            int subA = (int) (200 * subT);
            context.drawText(this.textRenderer, sub,
                    (this.width - this.textRenderer.getWidth(sub)) / 2, pad() + 42,
                    (subA << 24) | 0x9AA5B8, true);
        }
    }

    /** Момент героя после выбора: имя крупно в цвете элемента со свечением + всплеск искр. */
    private void drawHeroMoment(DrawContext context, float time) {
        float t = heroT / HERO_SEC;
        Card card = cards.get(chosen);
        float ease = easeOutCubic(t);
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Всплеск: расширяющиеся полупрозрачные кольца-диски + разлетающиеся искры.
        for (int k = 1; k <= 3; k++) {
            float r = 30f + (ease * 320f) * k / 3f;
            int a = (int) (90 * (1f - ease) / k);
            drawCircle(context, cx, cy, (int) r, (a << 24) | card.color());
        }
        Random rnd = new Random(chosen * 7919L + 13);
        for (int s = 0; s < 30; s++) {
            float ang = rnd.nextFloat() * 6.283f;
            float dist = 60f + rnd.nextFloat() * 220f;
            float sx = cx + (float) Math.cos(ang) * dist * ease;
            float sy = cy + (float) Math.sin(ang) * dist * ease;
            int a = (int) (200 * (1f - ease));
            context.fill((int) sx, (int) sy, (int) sx + 2, (int) sy + 2, (a << 24) | card.color());
        }

        // Имя: влетает крупным (3x -> 1x), золотистое свечение, к концу гаснет.
        String name = card.name();
        float scale = 1f + 2.2f * (1f - ease);
        float alpha = t < 0.72f ? 1f : Math.max(0f, 1f - (t - 0.72f) / 0.28f);
        int tw0 = this.textRenderer.getWidth(name);
        int tw = (int) (tw0 * scale);
        var ms = context.getMatrices();
        ms.pushMatrix();
        ms.translate(cx - tw / 2f, cy - (int) (scale * 6));
        ms.scale(scale, scale);
        int a1 = (int) (140 * alpha);
        int a2 = (int) (80 * alpha);
        context.drawText(this.textRenderer, name, 3, 3, (a1 << 24) | 0xE8C86A, true);
        context.drawText(this.textRenderer, name, -2, 2, (a2 << 24) | 0x8C7440, true);
        context.drawText(this.textRenderer, name, 0, 0, C_GOLD, true);
        ms.popMatrix();
    }

    private void drawCard(DrawContext context, int i, int cx, int cy, int cardW, int cardH,
                          int mouseX, int mouseY, float delta, float hoverAmount) {
        Card card = cards.get(i);
        boolean hovered = isOver(i, mouseX, mouseY);
        float time = (this.age + delta) / 20.0f;

        // Мягкое голубое свечение вокруг панели при наведении: три расширяющихся слоя.
        if (hoverAmount > 0.01f) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.0);
            float k = hoverAmount * (0.7f + 0.3f * pulse);
            int g1 = (int) (0x30 * k);
            int g2 = (int) (0x50 * k);
            int g3 = (int) (0x78 * k);
            context.fill(cx - 9, cy - 9, cx + cardW + 9, cy + cardH + 9, (g1 << 24) | 0x9FD0FF);
            context.fill(cx - 5, cy - 5, cx + cardW + 5, cy + cardH + 5, (g2 << 24) | 0x9FD0FF);
            context.fill(cx - 2, cy - 2, cx + cardW + 2, cy + cardH + 2, (g3 << 24) | 0x9FD0FF);
        }

        // Полупрозрачная карточка: фон-скриншот остаётся виден. Рамка голубая при наведении.
        context.fill(cx, cy, cx + cardW, cy + cardH, 0x991B2338);
        int border = hovered ? 0xFF9FD0FF : 0xFF3A4A6A;
        context.fill(cx, cy, cx + cardW, cy + 2, border);
        context.fill(cx, cy + cardH - 2, cx + cardW, cy + cardH, border);
        context.fill(cx, cy, cx + 2, cy + cardH, border);
        context.fill(cx + cardW - 2, cy, cx + cardW, cy + cardH, border);

        // Модель занимает почти всю карточку; узкий текст — у правого края.
        int modelW = cardW - CARD_PAD * 2 - MODEL_TEXT_GAP - TEXT_W;
        int mx1 = cx + CARD_PAD;
        int mx2 = mx1 + modelW;
        int my1 = cy + CARD_PAD;
        int my2 = cy + cardH - CARD_PAD;

        TravelerPreviewPlayer player = preview(card, i);
        if (player != null) {
            player.age = (int) this.age;
            drawPlayerModel(context, player, mx1, my1, mx2, my2, delta, hoverAmount, yaw[i],
                    time * 3.0f);
        }

        // Текстовая колонка, выровненная по вертикали относительно карточки.
        int tx = cx + CARD_PAD + modelW + MODEL_TEXT_GAP;
        List<String> descLines = wrap(card.desc(), TEXT_W);
        int descH = descLines.size() * DESC_LINE_H;
        int blockH = NAME_H + GAP + descH + GAP + BTN_H;
        int y = cy + Math.max(CARD_PAD, (cardH - blockH) / 2);

        // Стилизованное имя: ромб элемента над именем, имя крупно со свечением в цвете элемента.
        int nameX = tx + TEXT_W / 2;
        drawDiamond(context, nameX, y + 4, 4, card.color());
        String name = card.name();
        float ns = 1.18f;
        int nw = (int) (this.textRenderer.getWidth(name) * ns);
        var ms = context.getMatrices();
        ms.pushMatrix();
        ms.translate(tx + (TEXT_W - nw) / 2f, y + 8);
        ms.scale(ns, ns);
        context.drawText(this.textRenderer, name, 1, 1, 0x668C7440, true);
        context.drawText(this.textRenderer, name, 0, 0, card.color(), true);
        ms.popMatrix();
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
                tx + (TEXT_W - this.textRenderer.getWidth(card.button())) / 2, by + 11, bh ? C_GOLD : C_HINT, true);
    }

    /** Рендер модели в GUI: в покое — лёгкое покачивание на месте, при наведении — шаг. */
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
        prepareState(state, hoverAmount, walkPhase);

        float entityH = entity.getHeight();
        float visualW = entity.getWidth() * 1.35f; // фигура с руками
        float scale = Math.min((x2 - x1) / visualW, (y2 - y1) / entityH) * 0.98f;
        Vector3f camera = new Vector3f(0.0f, entityH * 0.5f, 0.0f);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateY((float) Math.PI + yawAmount);
        Quaternionf look = new Quaternionf();

        context.enableScissor(x1, y1, x2, y2);
        context.addEntity(state, scale, camera, rotation, look, x1, y1, x2, y2);
        context.disableScissor();
    }

    private static <S extends EntityRenderState> void prepareState(S state, float hoverAmount, float walkPhase) {
        state.light = 0xF000F0;
        state.hitbox = null;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof PlayerEntityRenderState player) {
            // Превью-игроки не имеют сетевых настроек скина (data tracker = 0),
            // поэтому все слои по умолчанию выключены и скин выглядит плоским.
            // Включаем их, чтобы объёмные волосы и одежда рендерились как в игре.
            player.hatVisible = true;
            player.jacketVisible = true;
            player.leftSleeveVisible = true;
            player.rightSleeveVisible = true;
            player.leftPantsLegVisible = true;
            player.rightPantsLegVisible = true;
            player.capeVisible = true;
        }
        if (state instanceof LivingEntityRenderState living) {
            // В покое — лёгкое покачивание (походка на месте), при наведении — полный шаг.
            living.limbSwingAmplitude = 0.12f + hoverAmount * 0.85f;
            living.limbSwingAnimationProgress = walkPhase;
        }
    }

    private static void drawDiamond(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private static void drawCircle(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
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
