package net.teyvat.quest;

/** Определения квестов Тейвата: id и названия. Общие для клиента и сервера. */
public final class Quests {
    /** «Познакомиться с Паймон» — выполняется, когда Паймон заканчивает знакомство. */
    public static final String MEET_PAIMON = "meet_paimon";
    public static final String MEET_PAIMON_TITLE = "Познакомиться с Паймон";
    /** «Попробуй приблизить камеру» — выполняется первой прокруткой колеса мыши в 3-м лице. */
    public static final String TRY_SCROLL = "try_scroll";
    public static final String TRY_SCROLL_TITLE = "Попробуй приблизить камеру";
    /** «Попробуй приблизить мир» — зажать C и осмотреться вокруг. */
    public static final String TRY_ZOOM = "try_zoom";
    public static final String TRY_ZOOM_TITLE = "Попробуй приблизить мир";
    /** «Попробуй побежать» — первое двойное нажатие W (бег с выносливостью). */
    public static final String TRY_SPRINT = "try_sprint";
    public static final String TRY_SPRINT_TITLE = "Попробуй побежать";
    /** «Попробуй сделать рывок» — первый рывок вперёд по Ctrl. */
    public static final String TRY_DASH = "try_dash";
    public static final String TRY_DASH_TITLE = "Попробуй сделать рывок";

    private Quests() {}
}
