package net.teyvat.mixin.client;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ванильный бег выключен полностью: клавиша спринта и двойное W больше не
 * включают бег майна. Всеми бегом и рывком управляет StaminaController
 * (выносливость как в Genshin, тап = рывок, удержание = бег).
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    /** Клавиша ванильного спринта больше ничего не делает. */
    @Redirect(method = "tickMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/PlayerInput;sprint()Z"))
    private boolean teyvat$noVanillaSprintKey(PlayerInput input) {
        return false;
    }

    /** Двойное W больше не включает ванильный бег (свой детектор в StaminaController). */
    @Redirect(method = "tickMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;canStartSprinting()Z"))
    private boolean teyvat$noDoubleTapSprint(ClientPlayerEntity self) {
        return false;
    }
}
