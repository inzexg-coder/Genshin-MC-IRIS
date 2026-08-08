package net.teyvat.mixin.client;

import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Достижения майнкрафта отключены: тосты не показываются и не звучат. */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementToast(CallbackInfoReturnable<Toast.Visibility> cir) {
        cir.setReturnValue(Toast.Visibility.HIDE);
    }

    @Inject(method = "getSoundEvent", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementSound(CallbackInfoReturnable<SoundEvent> cir) {
        cir.setReturnValue(null);
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void teyvat$noAdvancementDraw(CallbackInfo ci) {
        ci.cancel();
    }
}
