package net.teyvat.mixin.client;

import net.teyvat.client.FirstPersonBody;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** В первом лице собственное тело (FirstPersonBody) уже рисует руки и меч
 *  вместе с анимациями — ванильную руку/предмет не рисуем, чтобы не было
 *  двух мечей и «оторванной» ванильной руки. */
@Mixin(net.minecraft.client.render.item.HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"), cancellable = true)
    private void teyvat$skipVanillaHand(CallbackInfo ci) {
        if (FirstPersonBody.active()) {
            ci.cancel();
        }
    }
}
