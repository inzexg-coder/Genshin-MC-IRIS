package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Чат Тейвата: шире (анимешный шрифт) и по центру снизу. Скрытая панель-«заметки» убрана:
 *  диалоги НПС рисуются отдельным экранным оверлеем (DialogueOverlay), а не в чате. */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Shadow @Final
    private MinecraftClient client;

    @Shadow
    public abstract int getWidth();

    @Shadow
    public abstract double getChatScale();

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

    /** Левый край чата при центрировании (в чат-координатах). */
    private float teyvat$chatLeft() {
        double scale = this.getChatScale();
        double panel = this.getWidth();
        double window = this.client.getWindow().getScaledWidth();
        return (float) ((window / scale - panel) / 2.0);
    }
}
