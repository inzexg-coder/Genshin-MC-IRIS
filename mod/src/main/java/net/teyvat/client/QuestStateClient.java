package net.teyvat.client;

import net.teyvat.quest.Quests;
import net.teyvat.wiki.TeyvatWiki;

/** Состояние квестов на клиенте: приходит с сервера при входе в мир,
 *  чтобы мини-уроки и уведомления не повторялись после выполнения. */
public final class QuestStateClient {
    private static boolean meetPaimon;
    private static boolean tryScroll;
    private static boolean tryZoom;
    private static boolean trySprint;
    private static boolean tryDash;
    private static boolean tryAttack;
    private static boolean tryPickup;
    /** Статус квестов получен с сервера (при входе в мир). До этого момента
     *  Паймон не начинает уроки — чтобы при перезаходе гайд не проигрывался с нуля. */
    private static boolean loaded;

    private QuestStateClient() {}

    public static void set(boolean meetPaimonCompleted, boolean tryScrollCompleted, boolean tryZoomCompleted,
            boolean trySprintCompleted, boolean tryDashCompleted, boolean tryAttackCompleted,
            boolean tryPickupCompleted) {
        meetPaimon = meetPaimonCompleted;
        tryScroll = tryScrollCompleted;
        tryZoom = tryZoomCompleted;
        trySprint = trySprintCompleted;
        tryDash = tryDashCompleted;
        tryAttack = tryAttackCompleted;
        tryPickup = tryPickupCompleted;
        loaded = true;
    }

    /** Синхронизация квестов с сервера уже получена. */
    public static boolean isLoaded() {
        return loaded;
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
        if (Quests.TRY_PICKUP.equals(questId)) {
            return tryPickup;
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
        } else if (Quests.TRY_PICKUP.equals(questId)) {
            tryPickup = true;
        }
        // Урок открыл страницу вики: показываем её сразу, без ожидания сервера.
        WikiStateClient.discoverLocal(TeyvatWiki.pageForQuest(questId));
    }
}
