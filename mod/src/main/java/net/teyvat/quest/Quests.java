package net.teyvat.quest;

/** Определения квестов Тейвата: id и названия. Общие для клиента и сервера. */
public final class Quests {
    /** «Познакомиться с Паймон» — выполняется, когда Паймон заканчивает знакомство. */
    public static final String MEET_PAIMON = "meet_paimon";
    public static final String MEET_PAIMON_TITLE = "Познакомиться с Паймон";
    /** «Попробуй приблизить камеру» — выполняется первой прокруткой колеса мыши в 3-м лице. */
    public static final String TRY_SCROLL = "try_scroll";
    public static final String TRY_SCROLL_TITLE = "Попробуй приблизить камеру";

    private Quests() {}
}
