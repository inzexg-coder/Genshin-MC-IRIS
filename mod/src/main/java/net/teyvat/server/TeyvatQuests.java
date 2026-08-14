package net.teyvat.server;

import net.minecraft.server.network.ServerPlayerEntity;
import net.teyvat.quest.Quests;

/**
 * Серверная часть квестов. Состояние хранится в командах игрока (тег teyvat:quest_<id>):
 * переживает перезаход и работает и в одиночке, и на выделенном сервере.
 * Каждый квест выполняется один раз — фармить его нельзя.
 */
public final class TeyvatQuests {
    private static final String TAG_PREFIX = "teyvat:quest_";
    private static final String[] ALL_QUEST_IDS = {
            Quests.MEET_PAIMON, Quests.TRY_SCROLL, Quests.TRY_ZOOM, Quests.TRY_SPRINT,
            Quests.TRY_DASH, Quests.TRY_ATTACK, Quests.TRY_PICKUP
    };

    private TeyvatQuests() {}

    public static boolean isCompleted(ServerPlayerEntity player, String questId) {
        return player.getCommandTags().contains(TAG_PREFIX + questId);
    }

    /** Помечает квест выполненным. Уведомление игроку — только клиентский попап
     *  «Задание выполнено» (справа сверху), в чат ничего не пишется. */
    public static void complete(ServerPlayerEntity player, String questId) {
        if (!isKnownQuest(questId)) {
            return;
        }
        if (isCompleted(player, questId)) {
            return;
        }
        player.addCommandTag(TAG_PREFIX + questId);
        // Урок Паймон открывает связанную страницу вики (короткая версия уже
        // могла появиться при первой встрече — теперь запись дополняется).
        WikiDiscoveries.onQuestCompleted(player, questId);
    }

    private static boolean isKnownQuest(String questId) {
        for (String id : ALL_QUEST_IDS) {
            if (id.equals(questId)) {
                return true;
            }
        }
        return false;
    }
}
