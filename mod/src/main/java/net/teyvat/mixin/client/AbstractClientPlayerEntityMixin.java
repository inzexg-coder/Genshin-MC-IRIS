package net.teyvat.mixin.client;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.teyvat.client.TravelerChoiceClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Подставляет фейковый PlayerListEntry превью-моделям, чтобы рендер взял локальный скин мода. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Inject(method = "getPlayerListEntry", at = @At("HEAD"), cancellable = true)
    private void teyvat$previewEntry(CallbackInfoReturnable<PlayerListEntry> cir) {
        AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
        PlayerListEntry entry = TravelerChoiceClient.previewEntry(self.getUuid());
        if (entry != null) {
            cir.setReturnValue(entry);
        }
    }
}
