package net.teyvat.client;

import net.teyvat.quest.Quests;

/** Состояние квестов на клиенте: приходит с сервера при входе в мир,
 *  чтобы мини-уроки и уведомления не повторялись после выполнения. */
public final class QuestStateClient {
    private static boolean meetPaimon;
    private static boolean tryScroll;
    private static boolean tryZoom;

    private QuestStateClient() {}

    public static void set(boolean meetPaimonCompleted, boolean tryScrollCompleted, boolean tryZoomCompleted) {
        meetPaimon = meetPaimonCompleted;
        tryScroll = tryScrollCompleted;
        tryZoom = tryZoomCompleted;
    }

    public static boolean isCompleted(String questId) {
        if (Quests.MEET_PAIMON.equals(questId)) {
            return meetPaimon;
        }
        if (Quests.TRY_SCROLL.equals(questId)) {
            return tryScroll;
        }
        if (Quests.TRY_ZOOM.equals(questId)) {
            return tryZoom;
        }
        return false;
    }

    public static void markCompleted(String questId) {
        if (Quests.MEET_PAIMON.equals(questId)) {
            meetPaimon = true;
        } else if (Quests.TRY_SCROLL.equals(questId)) {
            tryScroll = true;
        } else if (Quests.TRY_ZOOM.equals(questId)) {
            tryZoom = true;
        }
    }
}
