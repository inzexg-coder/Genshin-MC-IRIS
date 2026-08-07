package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Полный чат (клавиша T) оформлен в стиле «Заметок путешественника» (клавиша N):
 *  непрозрачный тёмный фон, шапка с золотым заголовком и версией, золотая рамка поля ввода. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    private static final int HEADER_H = 34;

    @Inject(method = "render", at = @At("HEAD"))
    private void teyvat$chatScreenHeader(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        // Полностью непрозрачный фон — мир позади не просвечивает.
        context.fill(0, 0, w, h, 0xFF0F1420);
        // Шапка как в заметках: тёмная полоса, золотой заголовок и версия.
        context.fill(0, 0, w, HEADER_H, 0xFF1B2338);
        context.fill(0, HEADER_H, w, HEADER_H + 1, 0xFF3A4A6A);
        String title = "「Чат Тейвата」";
        context.drawText(client.textRenderer, title,
                (w - client.textRenderer.getWidth(title)) / 2, (HEADER_H - 12) / 2, 0xFFE8C86A, true);
        String ver = "Teyvat 0.9.15";
        context.drawText(client.textRenderer, ver, w - client.textRenderer.getWidth(ver) - 10,
                (HEADER_H - 12) / 2, 0xFF9AA5B8, true);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void teyvat$chatScreenField(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        int w = client.getWindow().getScaledWidth();
        int h = client.getWindow().getScaledHeight();
        // Золотая рамка вокруг поля ввода — как у панелей заметок.
        context.fill(2, h - 14, w - 2, h - 13, 0xFFE8C86A);
        context.fill(2, h - 2, w - 2, h - 1, 0xFFE8C86A);
        context.fill(2, h - 14, 3, h - 2, 0xFFE8C86A);
        context.fill(w - 3, h - 14, w - 2, h - 2, 0xFFE8C86A);
    }
}
