package net.teyvat.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Доступ к приватному getFov, чтобы оверлей использовал ту же проекцию, что и мир. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("getFov")
    float teyvat$callGetFov(Camera camera, float tickDelta, boolean changingFov);
}
