package net.teyvat.mixin.client;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к защищённым методам Camera для Teyvat Camera. */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setRotation")
    void teyvatSetRotation(float yaw, float pitch);

    @Invoker("setPos")
    void teyvatSetPos(double x, double y, double z);
}
