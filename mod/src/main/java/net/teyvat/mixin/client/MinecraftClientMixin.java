package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Экран достижений майнкрафта не открывается (кнопка в меню паузы просто не работает). */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementsScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof AdvancementsScreen) {
            ci.cancel();
        }
    }
}
