package net.teyvat.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import net.teyvat.client.CameraController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Teyvat Camera: после ванильного расчёта камеры полностью задаём свою позицию и поворот
 *  (плечо, догоняние, коллизия, свободная орбита). Первый человек и вид спереди не трогаем. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void teyvat$customCamera(BlockView area, Entity entity,
                                     boolean thirdPerson, boolean behindView, float tickDelta,
                                     CallbackInfo ci) {
        CameraController.apply((Camera) (Object) this, area, entity, thirdPerson, behindView, tickDelta);
    }
}
