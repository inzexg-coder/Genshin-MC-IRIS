package net.teyvat.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Клиентское состояние выбора путешественника: UUID игрока → «lumine»/«aether». */
public final class TravelerChoiceClient {
    /** Фиксированные UUID для превью-моделей в экране выбора. */
    public static final UUID LUMINE_PREVIEW_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID AETHER_PREVIEW_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Map<UUID, String> CHOICES = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerListEntry> PREVIEW_ENTRIES = new ConcurrentHashMap<>();

    private TravelerChoiceClient() {}

    public static void set(UUID playerId, String choice) {
        if (choice == null) {
            CHOICES.remove(playerId);
        } else {
            CHOICES.put(playerId, choice);
        }
    }

    public static String get(UUID playerId) {
        String choice = CHOICES.get(playerId);
        if (choice != null) {
            return choice;
        }
        if (playerId.equals(LUMINE_PREVIEW_UUID)) {
            return "lumine";
        }
        if (playerId.equals(AETHER_PREVIEW_UUID)) {
            return "aether";
        }
        return null;
    }

    public static UUID previewUuid(String choice) {
        return choice.equals("lumine") ? LUMINE_PREVIEW_UUID : AETHER_PREVIEW_UUID;
    }

    /** Фейковый PlayerListEntry для превью-моделей: скин берётся из локальных текстур мода. */
    public static PlayerListEntry previewEntry(UUID uuid) {
        if (!uuid.equals(LUMINE_PREVIEW_UUID) && !uuid.equals(AETHER_PREVIEW_UUID)) {
            return null;
        }
        return PREVIEW_ENTRIES.computeIfAbsent(uuid,
                u -> new PlayerListEntry(new GameProfile(u, "Traveler"), false));
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
