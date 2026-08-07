package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.teyvat.client.DialogueState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.ArrayList;
import java.util.List;

/** Чат Тейвата: свернутый чат — золотисто-синяя панель заметок с последней смысловой
 *  фразой (целиком, без обрезания по строкам); во время диалога с НПС панель не
 *  скрывается, после — плавно тает; полный чат — шире, по центру, с пробелами. */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Shadow @Final
    private MinecraftClient client;

    @Shadow @Final
    private List<ChatHudLine> messages;

    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract double getChatScale();

    @Invoker("isChatHidden")
    protected abstract boolean teyvat$isChatHidden();

    /** Сколько тиков (1/20 сек) панель видна после последнего сообщения
     *  (5 секунд — длиннее паузы между репликами Паймон, чтобы чат не мигал). */
    private static final int HIDE_AFTER_TICKS = 100;
    /** Длительность плавного затухания панели, тики. */
    private static final int FADE_TICKS = 16;
    /** Тик, когда панель появилась в текущей сессии — появление играется один раз,
     *  а не пересоздаётся при каждой новой фразе (иначе окно моргает). */
    private int teyvat$panelStartTick;
    /** creationTick последней показанной фразы. */
    private int teyvat$lastPhraseTick = -1;
    /** Тик, когда сменился текст фразы — новый текст мягко проявляется. */
    private int teyvat$textSwapTick;
    /** Была ли панель видна в прошлом кадре. */
    private boolean teyvat$wasVisible;
    /** Сглаженная высота панели: смена фразы не дёргает окно по вертикали. */
    private float teyvat$smoothPanelH = -1;

    /** Дополнительный пробел между строками чата — сообщения больше не слипаются. */
    @Inject(method = "getLineHeight", at = @At("RETURN"), cancellable = true)
    private void teyvat$chatLineSpacing(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() + 5);
    }

    /** Чат шире: анимешный шрифт шире обычного, текст не должен обрезаться. */
    @Inject(method = "getWidth", at = @At("RETURN"), cancellable = true)
    private void teyvat$chatWidth(CallbackInfoReturnable<Integer> cir) {
        int orig = cir.getReturnValue();
        int wide = (int) (orig * 1.35f);
        int screenW = this.client.getWindow().getScaledWidth();
        cir.setReturnValue(Math.max(orig, Math.min(wide, Math.max(320, (int) (screenW * 0.55f)))));
    }

    /** Сдвигаем чат по горизонтали: снизу по центру экрана, а не слева. */
    @ModifyConstant(method = "render", constant = @Constant(floatValue = 4.0f))
    private float teyvat$centerChatX(float original) {
        return teyvat$chatLeft();
    }

    /** Мышиные координаты считаем от того же центра, чтобы клики по чату не уезжали. */
    @ModifyConstant(method = "toChatLineX", constant = @Constant(doubleValue = 4.0d))
    private double teyvat$centerChatXMouse(double original) {
        return teyvat$chatLeft();
    }

    /** Свернутый чат: вместо списка строк рисуем панель в стиле заметок с последней
     *  смысловой фразой (фраза НПС, выполненное задание и т.п.), целиком, без обрезки. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void teyvat$collapsedChat(DrawContext context, int scaledHeight, int mouseX, int mouseY,
                                      boolean captureMouse, CallbackInfo ci) {
        if (captureMouse || this.teyvat$isChatHidden()) {
            teyvat$wasVisible = false;
            return;
        }
        ci.cancel();
        if (this.messages.isEmpty()) {
            teyvat$wasVisible = false;
            return;
        }
        // В списке чата новейшее сообщение стоит в начале (messages.add(0, ...)).
        ChatHudLine latest = this.messages.get(0);
        // Пока идёт диалог с НПС, панель не скрывается по таймеру.
        boolean dialogue = DialogueState.isActive();
        int now = this.client.inGameHud.getTicks();
        int age = now - latest.creationTick();
        boolean visible = dialogue || age <= HIDE_AFTER_TICKS + FADE_TICKS;

        // Новая фраза — панель не пересоздаётся: меняется только текст.
        if (teyvat$lastPhraseTick != latest.creationTick()) {
            teyvat$lastPhraseTick = latest.creationTick();
            teyvat$textSwapTick = now;
        }
        // Панель только что появилась (новая сессия) — появление играется один раз.
        if (visible && !teyvat$wasVisible) {
            teyvat$panelStartTick = now;
            teyvat$smoothPanelH = -1;
        }
        teyvat$wasVisible = visible;
        if (!visible) {
            return;
        }
        String phrase = latest.content().getString().trim();
        if (phrase.isEmpty()) {
            return;
        }
        // Плавное затухание: после 5 секунд панель мягко тает, а не исчезает резко.
        float fadeAlpha = 1.0f;
        if (!dialogue && age > HIDE_AFTER_TICKS) {
            fadeAlpha = Math.max(0.0f, 1.0f - (age - HIDE_AFTER_TICKS) / (float) FADE_TICKS);
        }
        // Появление: один раз за сессию панель всплывает снизу и проявляется за ~8 тиков.
        float entrance = Math.min(1.0f, (now - teyvat$panelStartTick) / 8.0f);
        float alpha = fadeAlpha * teyvat$easeOutCubic(entrance);
        float lift = (1.0f - entrance) * 8.0f;
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int pad = 14;
        int innerW = Math.max(120, Math.min(420, (int) (w * 0.6f) - pad * 2));
        List<String> lines = teyvat$wrap(phrase, innerW);
        int lineH = 12;
        int panelW = innerW + pad * 2;
        // Высота плавно догоняет целевую: новая фраза не дёргает окно.
        int targetH = lines.size() * lineH + pad * 2 + 10;
        if (teyvat$smoothPanelH < 0) {
            teyvat$smoothPanelH = targetH;
        }
        teyvat$smoothPanelH += (targetH - teyvat$smoothPanelH) * 0.18f;
        int panelH = Math.round(teyvat$smoothPanelH);
        int x0 = (w - panelW) / 2;
        int x1 = x0 + panelW;
        int y1 = h - 38;
        int y0 = y1 - panelH + (int) lift;

        // Переливающееся золото: тон рамки мягко дышит между тёплым и светлым золотом.
        float shimmer = 0.5f + 0.5f * (float) Math.sin(now * 0.22);
        int gold = teyvat$lerpColor(0xFFE8C86A, 0xFFFFF2B8, shimmer);
        // Панель в стиле заметок: тёмно-синяя, золотая рамка и акцентная полоска.
        context.fill(x0, y0, x1, y1, teyvat$withAlpha(0xF21B2338, alpha));
        // Тонкая переливающаяся золотая рамка (1px) и акцентная линия сверху.
        context.fill(x0, y0, x1, y0 + 1, teyvat$withAlpha(gold, alpha));
        context.fill(x0, y1 - 1, x1, y1, teyvat$withAlpha(gold, alpha));
        context.fill(x0, y0, x0 + 1, y1, teyvat$withAlpha(gold, alpha));
        context.fill(x1 - 1, y0, x1, y1, teyvat$withAlpha(gold, alpha));
        context.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, teyvat$withAlpha(gold, alpha));
        // Бегущий золотой блик по акцентной линии — свет переливается вдоль рамки.
        int shineX = x0 + (int) ((now * 5L) % Math.max(1, panelW - 50));
        context.fill(shineX, y0 + 1, Math.min(shineX + 50, x1 - 1), y0 + 2,
                teyvat$withAlpha(0xFFFFFFFF, alpha));
        int ty = y0 + 15;
        TextRenderer tr = this.client.textRenderer;
        // Текст новой фразы мягко проявляется за ~6 тиков, панель при этом не мигает.
        float textAlpha = Math.min(1.0f, (now - teyvat$textSwapTick) / 6.0f);
        for (String line : lines) {
            context.drawText(tr, line, x0 + pad, ty, teyvat$withAlpha(0xFFD8D2C4, alpha * textAlpha), true);
            ty += lineH;
        }
    }

    /** ARGB-цвет с изменённой альфой — для плавного затухания панели. */
    private static int teyvat$withAlpha(int argb, float alpha) {
        int a = (int) ((argb >>> 24) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Плавное ускорение к концу — для появления панели. */
    private static float teyvat$easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    /** Интерполяция между двумя RGB-цветами (альфа сохраняется от первого). */
    private static int teyvat$lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /** Перенос строк по ширине панели (как в заметках). */
    private List<String> teyvat$wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        TextRenderer tr = this.client.textRenderer;
        if (tr.getWidth(text) <= maxWidth) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.isEmpty()) {
                cur.append(word);
            } else if (tr.getWidth(cur + " " + word) <= maxWidth) {
                cur.append(' ').append(word);
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Левый край чата при центрировании (в чат-координатах). */
    private float teyvat$chatLeft() {
        double scale = this.getChatScale();
        double panel = this.getWidth();
        double window = this.client.getWindow().getScaledWidth();
        return (float) ((window / scale - panel) / 2.0);
    }
}
