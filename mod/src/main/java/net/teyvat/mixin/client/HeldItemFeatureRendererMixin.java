package net.teyvat.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.teyvat.client.CombatController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Система меча»: предмет доворачивается в локальных осях клинка
 * (CombatController.currentBladeFrameRotation), чтобы лезвие было направлено
 * ТОЧНО по предплечью — «продолжение руки» (грип-корректировка BLADE_GRIP_C,
 * угол к руке 0° вместо ванильных ~6.3°), а плоскость лезвия жила по
 * кейфрейм-кривым BLADE_DEG (замах -> удар -> перехлёст). Работает и в 3-м
 * лице (обычный рендер сущности), и в 1-м (FirstPersonBody рисует собственное
 * тело тем же HeldItemFeatureRenderer). Применяется только к мечу в правой
 * руке локального игрока; левая рука (щит и т.п.) и другие игроки не
 * трогаются.
 */
@Mixin(HeldItemFeatureRenderer.class)
public abstract class HeldItemFeatureRendererMixin {
    /** true, когда сейчас рендерится меч локального игрока в правой руке —
     *  к нему применяется доворот лезвия. */
    private static boolean teyvat$bladeActive;

    @Inject(method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
            at = @At("HEAD"))
    private void teyvat$captureBladeState(ArmedEntityRenderState entityState, ItemRenderState itemRenderState, Arm arm,
                                          MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        teyvat$bladeActive = false;
        if (arm != Arm.RIGHT) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        if (!(entityState instanceof PlayerEntityRenderState playerState) || playerState.id != player.getId()) {
            return;
        }
        if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) {
            return;
        }
        teyvat$bladeActive = true;
    }

    @Redirect(method = "renderItem(Lnet/minecraft/client/render/entity/state/ArmedEntityRenderState;Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/util/Arm;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"))
    private void teyvat$renderItemWithBlade(ItemRenderState itemRenderState, MatrixStack matrices,
                                            OrderedRenderCommandQueue queue, int light, int overlay, int color) {
        if (teyvat$bladeActive) {
            matrices.multiply(CombatController.currentBladeFrameRotation());
        }
        itemRenderState.render(matrices, queue, light, overlay, color);
    }
}
