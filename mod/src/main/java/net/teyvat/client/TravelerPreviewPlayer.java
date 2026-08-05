package net.teyvat.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/** Клиентская заглушка игрока для превью в экране выбора путешественника. */
public class TravelerPreviewPlayer extends AbstractClientPlayerEntity {
    public TravelerPreviewPlayer(ClientWorld world, String choice) {
        super(world, new GameProfile(TravelerChoiceClient.previewUuid(choice), "Traveler_" + choice));
    }
}
