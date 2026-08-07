package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.teyvat.client.ChatFlash;
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
 *  фразой (целиком, без обрезания по строкам) и оранжевой вспышкой при выполнении квеста;
 *  полный чат — шире, по центру, с пробелами между сообщениями. */
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
            return;
        }
        ci.cancel();
        if (this.messages.isEmpty()) {
            return;
        }
        // В списке чата новейшее сообщение стоит в начале (messages.add(0, ...)).
        ChatHudLine latest = this.messages.get(0);
        // Свернутый чат исчезает через 3 секунды (60 тиков) после последнего сообщения.
        if (this.client.inGameHud.getTicks() - latest.creationTick() > 60) {
            return;
        }
        String phrase = latest.content().getString().trim();
        if (phrase.isEmpty()) {
            return;
        }
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int pad = 14;
        int innerW = Math.max(120, Math.min(420, (int) (w * 0.6f) - pad * 2));
        List<String> lines = teyvat$wrap(phrase, innerW);
        int lineH = 12;
        int panelW = innerW + pad * 2;
        int panelH = lines.size() * lineH + pad * 2 + 10;
        int x0 = (w - panelW) / 2;
        int x1 = x0 + panelW;
        int y1 = h - 38;
        int y0 = y1 - panelH;

        // Панель в стиле заметок: тёмно-синяя, золотая рамка и акцентная полоска.
        context.fill(x0, y0, x1, y1, 0xF21B2338);
        // Тонкая золотая рамка (1px) и аккуратная акцентная линия сверху.
        context.fill(x0, y0, x1, y0 + 1, 0xFFE8C86A);
        context.fill(x0, y1 - 1, x1, y1, 0xFFE8C86A);
        context.fill(x0, y0, x0 + 1, y1, 0xFFE8C86A);
        context.fill(x1 - 1, y0, x1, y1, 0xFFE8C86A);
        context.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, 0xFFE8C86A);
        int ty = y0 + 15;
        TextRenderer tr = this.client.textRenderer;
        for (String line : lines) {
            context.drawText(tr, line, x0 + pad, ty, 0xFFD8D2C4, true);
            ty += lineH;
        }

        // Выполнение задания: свёрнутый чат вспыхивает оранжевым как вспышка света
        // (белое ядро, оранжевое сияние, расширяющийся ореол) и плавно гаснет.
        ChatFlash.render(context, x0, y0, x1, y1);
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
