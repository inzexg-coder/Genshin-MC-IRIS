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

    private TeyvatQuests() {}

    public static boolean isCompleted(ServerPlayerEntity player, String questId) {
        return player.getCommandTags().contains(TAG_PREFIX + questId);
    }

    /** Помечает квест выполненным. Уведомление игроку — только клиентский попап
     *  «Задание выполнено» (справа сверху), в чат ничего не пишется. */
    public static void complete(ServerPlayerEntity player, String questId) {
        if (!Quests.MEET_PAIMON.equals(questId) && !Quests.TRY_SCROLL.equals(questId)
                && !Quests.TRY_ZOOM.equals(questId) && !Quests.TRY_SPRINT.equals(questId)
                && !Quests.TRY_DASH.equals(questId) && !Quests.TRY_ATTACK.equals(questId)
                && !Quests.TRY_PICKUP.equals(questId)) {
            return;
        }
        if (isCompleted(player, questId)) {
            return;
        }
        player.addCommandTag(TAG_PREFIX + questId);
    }
}
