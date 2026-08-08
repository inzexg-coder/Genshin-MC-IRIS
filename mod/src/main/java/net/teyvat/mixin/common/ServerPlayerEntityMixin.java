package net.teyvat.mixin.common;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Сон лечит до полного здоровья: в Genshin здоровье не восстанавливается
 * само собой, единственный способ — отдых. Проснувшись, герой снова в силе.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "wakeUp", at = @At("TAIL"))
    private void teyvat$sleepHeals(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        self.setHealth(self.getMaxHealth());
    }
}
