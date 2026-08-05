package net.teyvat.client;

import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Клиентское состояние выбора путешественника: UUID игрока → «lumine»/«aether». */
public final class TravelerChoiceClient {
    private static final Map<UUID, String> CHOICES = new ConcurrentHashMap<>();

    private TravelerChoiceClient() {}

    public static void set(UUID playerId, String choice) {
        if (choice == null) {
            CHOICES.remove(playerId);
        } else {
            CHOICES.put(playerId, choice);
        }
    }

    public static String get(UUID playerId) {
        return CHOICES.get(playerId);
    }

    /** SkinTextures для локальных скинов мода (Люмин — slim, Итэр — classic). */
    public static SkinTextures skinTextures(String choice) {
        boolean slim = "lumine".equals(choice);
        Identifier tex = Identifier.of("teyvat", "skin/" + (slim ? "lumine" : "aether"));
        return SkinTextures.create(
                new AssetInfo.TextureAssetInfo(tex),
                null,
                null,
                slim ? PlayerSkinType.SLIM : PlayerSkinType.WIDE);
    }
}
