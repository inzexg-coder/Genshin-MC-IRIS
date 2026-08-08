package net.teyvat.mixin.common;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.teyvat.progression.ProgressionStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Сон лечит до полного здоровья: в Genshin здоровье не восстанавливается
 * само собой, единственный способ — отдых. Проснувшись, герой снова в силе.
 * Плюс прогрессия игрока (ранг, опыт, ростера персонажей) живёт в NBT:
 * переживает смерть, рестарт мира и работает одинаково в соло и на сервере.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "wakeUp", at = @At("TAIL"))
    private void teyvat$sleepHeals(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        self.setHealth(self.getMaxHealth());
    }

    @Inject(method = "readCustomData", at = @At("HEAD"))
    private void teyvat$readProgression(ReadView view, CallbackInfo ci) {
        ProgressionStore.onRead((ServerPlayerEntity) (Object) this, view);
    }

    @Inject(method = "writeCustomData", at = @At("HEAD"))
    private void teyvat$writeProgression(WriteView view, CallbackInfo ci) {
        ProgressionStore.onWrite((ServerPlayerEntity) (Object) this, view);
    }

    @Inject(method = "copyFrom", at = @At("HEAD"))
    private void teyvat$copyProgression(ServerPlayerEntity oldPlayer, boolean alive, CallbackInfo ci) {
        ProgressionStore.onCopy((ServerPlayerEntity) (Object) this, oldPlayer);
    }
}
