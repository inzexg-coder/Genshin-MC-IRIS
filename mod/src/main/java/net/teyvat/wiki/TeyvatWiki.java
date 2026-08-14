package net.teyvat.wiki;

import net.teyvat.quest.Quests;

import java.util.List;

/**
 * «Энциклопедия путешественника» — каталог записей заметок. Общий для клиента
 * и сервера: клиент рисует страницы, сервер валидирует id открытий.
 *
 * Запись открывается при первой встрече (короткая версия), а после урока
 * Паймон дополняется полной версией. Разделы — как в Genshin:
 * Мир / Битва / Сокровища / Приключения.
 */
public final class TeyvatWiki {
    /** Раздел вики: заголовок и акцентный цвет. */
    public enum Section {
        WORLD("Мир", 0xFF7FE8D2),
        BATTLE("Битва", 0xFFFF8A8A),
        TREASURE("Сокровища", 0xFFF2C94C),
        ADVENTURE("Приключения", 0xFF8FB8FF);

        public final String title;
        public final int color;

        Section(String title, int color) {
            this.title = title;
            this.color = color;
        }
    }

    /** Запись вики. lessonQuestId — квест-урок Паймон, после которого запись
     *  становится полной; если null — запись полная сразу после открытия. */
    public record Entry(String id, Section section, String title, String icon,
                        List<String> shortParas, List<String> fullParas, String lessonQuestId) {
        public boolean hasLesson() {
            return lessonQuestId != null;
        }
    }

    private TeyvatWiki() {}

    public static final String ID_WORLD_QUESTS = "world_quests";
    public static final String ID_WHEEL_ZOOM = "wheel_zoom";
    public static final String ID_KEY_C = "key_c";
    public static final String ID_SPRINT = "sprint";
    public static final String ID_EXP = "exp";
    public static final String ID_BEACH = "beach";
    public static final String ID_CELESTIA = "celestia";
    public static final String ID_HYDRO_SLIME = "hydro_slime";
    public static final String ID_HYDRO_PROJECTILE = "hydro_slime_projectile";
    public static final String ID_COMBAT = "combat";
    public static final String ID_DASH = "dash";
    public static final String ID_FALL_DAMAGE = "fall_damage";
    public static final String ID_MORA = "mora";
    public static final String ID_SLIME_CONDENSATE = "slime_condensate";
    public static final String ID_SLIME_SECRETIONS = "slime_secretions";
    public static final String ID_SLIME_CONCENTRATE = "slime_concentrate";
    public static final String ID_PICKUP = "pickup";

    private static final List<Entry> ENTRIES = List.of(
            // ---- Мир ----
            new Entry(ID_BEACH, Section.WORLD, "Пляж", "drop", List.of(),
                    List.of(
                            "Пляж — родина героя. Здесь начинается путь: Паймон встречает тебя у воды, а слаймы не дают заскучать.",
                            "Граница пляжа откроется, когда Паймон проведёт первые уроки: приближение, клавиша C, бег, рывок и бой."),
                    null),
            new Entry(ID_CELESTIA, Section.WORLD, "Селестия", "diamond", List.of(),
                    List.of(
                            "Селестия — парящий остров богов, белый мрамор и золото. Архитектура набора «Селестия» складывается из мраморных блоков.",
                            "Мрамор добывается и обрабатывается: колонны, балки, плиты, ступени, стены и капители — всё для небесных залов."),
                    null),
            // ---- Битва ----
            new Entry(ID_HYDRO_SLIME, Section.BATTLE, "Гидро слайм", "drop",
                    List.of("Прозрачный слайм с каплей на макушке — первая преграда на пляже."),
                    List.of(
                            "Слайм целится в путника и выплёвывает сферу воды. Уворачивайтесь, ловите миг, когда он на земле, и бейте.",
                            "На гибели слайм рассыпается всплеском — и оставляет слизь с морой. Чем сильнее слайм, тем ценнее слизь."),
                    Quests.TRY_ATTACK),
            new Entry(ID_HYDRO_PROJECTILE, Section.BATTLE, "Водяной снаряд", "circle",
                    List.of("Сфера воды, которую выплёвывает гидро слайм."),
                    List.of(
                            "Снаряд летит точно в цель. Уворачивайтесь — попадание отбрасывает и сбивает хватку.",
                            "Когда слайм выплёвывает сферу, он на миг замирает — это лучший миг для удара."),
                    null),
            new Entry(ID_COMBAT, Section.BATTLE, "Бой мечом", "sword",
                    List.of("ЛКМ — серия из пяти ударов, как у Итэра и Люмин: широкий удар, диагональ снизу вверх, круговой разворот, удар справа налево и финальный очень широкий."),
                    List.of(
                            "Свинг анимируется всегда — по врагу, по воздуху и даже по блоку. Герой шагает вперёд с каждым ударом, а пятый — заметным прокатом.",
                            "Удерживайте ЛКМ — серия продолжается сама; тап — одиночный удар. Враг в радиусе размаха получит урон и отлетит назад.",
                            "X — пропустить обучение Паймон: знакомство и реплики уроков заканчиваются сразу."),
                    Quests.TRY_ATTACK),
            new Entry(ID_DASH, Section.BATTLE, "Рывок", "triangle",
                    List.of("Рывок — Ctrl: короткий бросок вперёд с наклоном тела, как в бою."),
                    List.of(
                            "Он стоит дороже бега. Если дуга выносливости пуста, рывок не начнётся — сначала восстановите её.",
                            "Рывок проходит сквозь брызги слайма — используйте его, чтобы сократить дистанцию."),
                    Quests.TRY_DASH),
            new Entry(ID_FALL_DAMAGE, Section.BATTLE, "Урон от падения", "chevrons", List.of(),
                    List.of(
                            "Урон от падения как в Genshin: первые ~7 блоков безопасны, дальше урон растёт быстро и становится смертельным.",
                            "Вода отменяет урон от падения — выбирайте путь с обрыва через воду."),
                    null),
            // ---- Сокровища ----
            new Entry(ID_MORA, Section.TREASURE, "Мора", "coin", List.of(),
                    List.of(
                            "Мора — монеты Тейвата, звонкие и тяжёлые. Их приносят слаймы и задания.",
                            "Копите мора: впереди лавки и мастера, которым понадобится каждая монета."),
                    null),
            new Entry(ID_SLIME_CONDENSATE, Section.TREASURE, "Слизь слайма", "vial", List.of(),
                    List.of(
                            "Слайм до 40 уровня роняет слизь слайма.",
                            "Слизь хранится в сумке и пригодится в ремесле."),
                    null),
            new Entry(ID_SLIME_SECRETIONS, Section.TREASURE, "Выделения слайма", "vial", List.of(),
                    List.of(
                            "Слайм от 40 уровня роняет выделения — более плотную и ценную слизь.",
                            "Чем дольше слайм живёт в мире, тем выше его уровень и тем ценнее добыча."),
                    null),
            new Entry(ID_SLIME_CONCENTRATE, Section.TREASURE, "Концентрат слайма", "vial", List.of(),
                    List.of(
                            "Слайм от 60 уровня роняет концентрат — редчайшую слизь, за которую многое дадут."),
                    null),
            new Entry(ID_PICKUP, Section.TREASURE, "Подбор на F", "square",
                    List.of("Добыча не подбирается сама: подойди к предмету и нажми F."),
                    List.of(
                            "Рядом с лежащим предметом появляется вкладка — по F он поднимается. Если предметов много, видны максимум три ближайших друг под другом.",
                            "Вкладки пропадают при отходе. Добыча никогда не попадёт в выбранный слот хотбара — она заполняет остальные ячейки."),
                    Quests.TRY_PICKUP),
            // ---- Приключения ----
            new Entry(ID_WHEEL_ZOOM, Section.ADVENTURE, "Приближение и отдаление", "circle",
                    List.of("Колесо мыши приближает взгляд к миру, как подзорная труба, и отдаляет обратно."),
                    List.of(
                            "Колесо мыши приближает взгляд к миру, как подзорная труба, и отдаляет обратно. Предмет в руке не нужен.",
                            "Осматривайте дальние обрывы и долины: за холмом может прятаться лагерь или стадо гидро слаймов."),
                    Quests.TRY_SCROLL),
            new Entry(ID_KEY_C, Section.ADVENTURE, "Клавиша C", "square",
                    List.of("Зажмите C — камера плавно плывёт к земле и разглядывает местность вблизи."),
                    List.of(
                            "Зажмите C — камера плавно плывёт к земле и разглядывает местность вблизи. Отпустите — взгляд вернётся.",
                            "Удобно прицеливаться в брызги слайма и искать тропу среди скал."),
                    Quests.TRY_ZOOM),
            new Entry(ID_SPRINT, Section.ADVENTURE, "Бег и стамина", "chevrons",
                    List.of("Бег начинается с двойного нажатия W и тянет выносливость — дугу в левом нижнем углу."),
                    List.of(
                            "Бег начинается с двойного нажатия W и тянет выносливость — дугу в левом нижнем углу.",
                            "Дуга пустеет — бег обрывается. Дайте ей набраться: двойное нажатие W, пока она не полна, забудется.",
                            "Выносливость возвращается сама, стоит замедлиться."),
                    Quests.TRY_SPRINT),
            new Entry(ID_EXP, Section.ADVENTURE, "Опыт и Ранг Приключений", "star", List.of(),
                    List.of(
                            "Опыт дают победы над врагами и выполненные задания.",
                            "Полоса опыта растёт и, заполнившись, повышает Ранг Приключений. Золотое уведомление возвестит о новом ранге."),
                    null),
            new Entry(ID_WORLD_QUESTS, Section.ADVENTURE, "Мировые задания", "diamond",
                    List.of("Задания рождаются в пути. Паймон подскажет, куда ступить, а мир ответит историей."),
                    List.of(
                            "Задания рождаются в пути. Паймон подскажет, куда ступить, а мир ответит историей — наградой станут опыт и мора.",
                            "Следите за углом экрана: объявленное задание держится там, пока не выполнено. Золотой ромб с искрой возвещает о награде."),
                    Quests.MEET_PAIMON)
    );

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Entry entry(String id) {
        for (Entry e : ENTRIES) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    public static boolean isKnown(String id) {
        return entry(id) != null;
    }

    /** Страница, которую открывает квест-урок (для синхронизации «встреча → урок»). */
    public static String pageForQuest(String questId) {
        if (Quests.MEET_PAIMON.equals(questId)) {
            return ID_WORLD_QUESTS;
        }
        if (Quests.TRY_SCROLL.equals(questId)) {
            return ID_WHEEL_ZOOM;
        }
        if (Quests.TRY_ZOOM.equals(questId)) {
            return ID_KEY_C;
        }
        if (Quests.TRY_SPRINT.equals(questId)) {
            return ID_SPRINT;
        }
        if (Quests.TRY_DASH.equals(questId)) {
            return ID_DASH;
        }
        if (Quests.TRY_ATTACK.equals(questId)) {
            return ID_COMBAT;
        }
        if (Quests.TRY_PICKUP.equals(questId)) {
            return ID_PICKUP;
        }
        return null;
    }

    /** Страница, которую открывает подбор предмета (по id предмета). */
    public static String pageForItem(String itemId) {
        if ("teyvat:mora".equals(itemId)) {
            return ID_MORA;
        }
        if ("teyvat:slime_condensate".equals(itemId)) {
            return ID_SLIME_CONDENSATE;
        }
        if ("teyvat:slime_secretions".equals(itemId)) {
            return ID_SLIME_SECRETIONS;
        }
        if ("teyvat:slime_concentrate".equals(itemId)) {
            return ID_SLIME_CONCENTRATE;
        }
        if (itemId != null && itemId.startsWith("teyvat:marble")) {
            return ID_CELESTIA;
        }
        return null;
    }
}
