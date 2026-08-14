package net.teyvat.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import net.teyvat.client.CombatController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Во время удара клавиши движения не работают: герой двигается только
 *  микро-рывком по направлению атаки (как в Genshin). Ввод глушится прямо
 *  в Input: getMovementInput вызывается каждый тик движения (tickMovementInput)
 *  ДО чтения playerInput, поэтому обнулённый ввод не доходит до движения. */
@Mixin(Input.class)
public abstract class InputMixin {
    @Shadow
    public PlayerInput playerInput;

    @Inject(method = "getMovementInput", at = @At("HEAD"), cancellable = true)
    private void teyvat$lockDuringAttack(CallbackInfoReturnable<Vec2f> cir) {
        if (CombatController.lockInputDuringAttack()) {
            PlayerInput in = this.playerInput;
            // Jump-cancel: прыжок прерывает атаку (как в Genshin), сам прыжок
            // пропускаем дальше — остальное движение на время удара глушится.
            if (in != null && in.jump() && CombatController.tryCancelByJump()) {
                this.playerInput = new PlayerInput(
                        in.forward(), in.backward(), in.left(), in.right(),
                        true, in.sneak(), in.sprint());
                cir.setReturnValue(Vec2f.ZERO);
                return;
            }
            this.playerInput = new PlayerInput(false, false, false, false, false, false, false);
            cir.setReturnValue(Vec2f.ZERO);
        }
    }
}
