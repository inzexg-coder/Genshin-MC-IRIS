package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
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

/** Чат Тейвата: пробелы между сообщениями, чат снизу по центру и яркая вспышка при квестах. */
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

    /** Яркая золотая вспышка в чате (например, при выполнении квеста). */
    @Inject(method = "render", at = @At("RETURN"))
    private void teyvat$chatFlash(DrawContext context, int scaledHeight, int mouseX, int mouseY,
                                  boolean captureMouse, CallbackInfo ci) {
        if (!ChatFlash.isActive()) {
            return;
        }
        ChatFlash.tick();
        float t = ChatFlash.progress();
        int w = context.getScaledWindowWidth();
        int panel = (int) Math.ceil(this.getWidth() / this.getChatScale());
        int left = (w - panel) / 2;
        int right = left + panel;
        int bottom = context.getScaledWindowHeight() - 26;
        int top = bottom - (int) (120.0f * t + 30);
        int alpha = (int) (200.0f * t);
        // Белый центр → золотая кромка: яркая вспышка, плавно гаснущая.
        context.fillGradient(left, top, right, bottom,
                (alpha << 24) | 0xFFFFFF, (alpha << 24) | 0xFFD760);
        context.fillGradient(left + 16, top + 16, right - 16, bottom - 16,
                ((int) (alpha * 0.7f) << 24) | 0xFFF2C9, ((int) (alpha * 0.5f) << 24) | 0xFFE08A);
    }

    /** Левый край чата при центрировании (в чат-координатах). */
    private float teyvat$chatLeft() {
        double scale = this.getChatScale();
        double panel = this.getWidth();
        double window = this.client.getWindow().getScaledWidth();
        return (float) ((window / scale - panel) / 2.0);
    }
}
