package net.teyvat.client;

import net.teyvat.quest.Quests;

/** Состояние квестов на клиенте: приходит с сервера при входе в мир,
 *  чтобы мини-уроки и уведомления не повторялись после выполнения. */
public final class QuestStateClient {
    private static boolean meetPaimon;
    private static boolean tryScroll;
    private static boolean tryZoom;
    private static boolean trySprint;
    private static boolean tryDash;
    private static boolean tryAttack;

    private QuestStateClient() {}

    public static void set(boolean meetPaimonCompleted, boolean tryScrollCompleted, boolean tryZoomCompleted,
            boolean trySprintCompleted, boolean tryDashCompleted, boolean tryAttackCompleted) {
        meetPaimon = meetPaimonCompleted;
        tryScroll = tryScrollCompleted;
        tryZoom = tryZoomCompleted;
        trySprint = trySprintCompleted;
        tryDash = tryDashCompleted;
        tryAttack = tryAttackCompleted;
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
        if (Quests.TRY_SPRINT.equals(questId)) {
            return trySprint;
        }
        if (Quests.TRY_DASH.equals(questId)) {
            return tryDash;
        }
        if (Quests.TRY_ATTACK.equals(questId)) {
            return tryAttack;
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
        } else if (Quests.TRY_SPRINT.equals(questId)) {
            trySprint = true;
        } else if (Quests.TRY_DASH.equals(questId)) {
            tryDash = true;
        } else if (Quests.TRY_ATTACK.equals(questId)) {
            tryAttack = true;
        }
    }
}
