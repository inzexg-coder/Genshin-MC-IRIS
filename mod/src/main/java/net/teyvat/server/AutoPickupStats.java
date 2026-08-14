package net.teyvat.server;

/**
 * Счётчики заблокированных ванильных попыток автоподбора.
 * Отдельный класс, потому что в миксинах нельзя объявлять публичные
 * статические методы — Mixin пытается применить их к целевому классу
 * и падает на этапе трансформации.
 */
public final class AutoPickupStats {
    private AutoPickupStats() {}

    private static long blockedCount;
    private static long guardCount;
    private static long lastNoticeMillis;

    public static long blockedCount() {
        return blockedCount;
    }

    public static long guardCount() {
        return guardCount;
    }

    public static long lastNoticeMillis() {
        return lastNoticeMillis;
    }

    public static void onBlocked() {
        blockedCount++;
    }

    public static void onGuarded() {
        guardCount++;
    }

    public static void touchNotice(long now) {
        lastNoticeMillis = now;
    }
}
