package net.teyvat.mixin.client;

import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import net.teyvat.client.CombatController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Во время удара клавиши движения не работают: герой двигается только
 *  микро-рывком по направлению атаки (как в Genshin). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Shadow
    public PlayerInput playerInput;

    @Shadow
    protected Vec2f movementVector;

    @Inject(method = "tick", at = @At("TAIL"))
    private void teyvat$lockDuringAttack(CallbackInfo ci) {
        if (CombatController.lockInputDuringAttack()) {
            this.playerInput = new PlayerInput(false, false, false, false, false, false, false);
            this.movementVector = Vec2f.ZERO;
        }
    }
}
