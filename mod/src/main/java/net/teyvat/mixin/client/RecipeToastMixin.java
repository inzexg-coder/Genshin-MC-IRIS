package net.teyvat.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Оповещения «открыты новые рецепты» отключены: тост не показывается. */
@Mixin(RecipeToast.class)
public abstract class RecipeToastMixin {
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void teyvat$noRecipeToast(CallbackInfoReturnable<Toast.Visibility> cir) {
        cir.setReturnValue(Toast.Visibility.HIDE);
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void teyvat$noRecipeToastDraw(DrawContext context, TextRenderer textRenderer, long startTime, CallbackInfo ci) {
        ci.cancel();
    }
}
