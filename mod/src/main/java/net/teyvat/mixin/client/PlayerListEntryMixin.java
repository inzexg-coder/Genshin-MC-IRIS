package net.teyvat.mixin.client;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import net.teyvat.client.TravelerChoiceClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Подменяет скин игрока, выбравшего Люмин или Итэра, на локальные текстуры мода. */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void teyvat$travelerSkin(CallbackInfoReturnable<SkinTextures> cir) {
        PlayerListEntry entry = (PlayerListEntry) (Object) this;
        String choice = TravelerChoiceClient.get(entry.getProfile().id());
        if (choice != null) {
            cir.setReturnValue(TravelerChoiceClient.skinTextures(choice));
        }
    }
}
