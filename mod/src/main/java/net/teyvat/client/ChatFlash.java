package net.teyvat.client;

/** Короткая яркая вспышка в чате (например, при выполнении квеста). */
public final class ChatFlash {
    private static final int MAX_TICKS = 22;
    private static int ticks;

    private ChatFlash() {}

    /** Запускает вспышку. */
    public static void trigger() {
        ticks = MAX_TICKS;
    }

    public static boolean isActive() {
        return ticks > 0;
    }

    /** 1.0 в начале вспышки, 0.0 в конце. */
    public static float progress() {
        return ticks / (float) MAX_TICKS;
    }

    public static void tick() {
        if (ticks > 0) {
            ticks--;
        }
    }
}
