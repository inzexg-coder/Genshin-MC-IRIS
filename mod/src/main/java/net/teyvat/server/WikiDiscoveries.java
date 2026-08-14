package net.teyvat.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.teyvat.network.WikiDiscoveryPayload;
import net.teyvat.wiki.TeyvatWiki;

import java.util.ArrayList;
import java.util.List;

/**
 * Серверная часть «Энциклопедии путешественника». Открытые записи хранятся
 * в тегах игрока (teyvat:wiki_<id>) — переживают перезаход и работают
 * и в одиночке, и на выделенном сервере. Клиенту открытие уходит тихим
 * пакетом: никаких уведомлений, страница просто появляется в заметках.
 */
public final class WikiDiscoveries {
    private static final String TAG_PREFIX = "teyvat:wiki_";

    private WikiDiscoveries() {}

    public static boolean isDiscovered(ServerPlayerEntity player, String entryId) {
        return player.getCommandTags().contains(TAG_PREFIX + entryId);
    }

    /** Открыть запись игроку. Идемпотентно: повторная встреча ничего не делает. */
    public static void discover(ServerPlayerEntity player, String entryId) {
        if (!TeyvatWiki.isKnown(entryId)) {
            return;
        }
        if (isDiscovered(player, entryId)) {
            return;
        }
        player.addCommandTag(TAG_PREFIX + entryId);
        ServerPlayNetworking.send(player, new WikiDiscoveryPayload(entryId));
    }

    /** Все открытые записи игрока (в порядке каталога). */
    public static List<String> discoveredIds(ServerPlayerEntity player) {
        List<String> ids = new ArrayList<>();
        for (TeyvatWiki.Entry e : TeyvatWiki.entries()) {
            if (isDiscovered(player, e.id())) {
                ids.add(e.id());
            }
        }
        return ids;
    }

    /** Выполнен квест-урок: открываем связанную страницу (урок дополняет её). */
    public static void onQuestCompleted(ServerPlayerEntity player, String questId) {
        String page = TeyvatWiki.pageForQuest(questId);
        if (page != null) {
            discover(player, page);
        }
    }

    /** Игрок подобрал предмет: открываем связанную страницу сокровищ/мира. */
    public static void onItemPickedUp(ServerPlayerEntity player, String itemId) {
        String page = TeyvatWiki.pageForItem(itemId);
        if (page != null) {
            discover(player, page);
        }
    }
}
