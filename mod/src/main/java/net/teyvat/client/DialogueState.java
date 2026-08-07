package net.teyvat.client;

/**
 * Состояние диалога: пока НПС говорит с героем по сюжету, свёрнутый чат
 * остаётся на экране и не скрывается по таймеру.
 */
public final class DialogueState {
    private static boolean active;

    private DialogueState() {}

    /** Начать диалог — НПС обращается к герою. */
    public static void start() {
        active = true;
    }

    /** Завершить диалог — чат снова живёт по таймеру. */
    public static void end() {
        active = false;
    }

    /** Идёт ли сейчас сюжетный диалог с НПС. */
    public static boolean isActive() {
        return active;
    }
}
