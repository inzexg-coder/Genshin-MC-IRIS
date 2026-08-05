package net.teyvat.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Вся информация о моде для экрана «Заметки путешественника»: вкладка о сборке и набор «Селестия». */
public final class TravelerNotesContent {
    public static final int C_HEADER = 0xFFFFE9A8;
    public static final int C_BODY = 0xFFE8E4D8;
    public static final int C_GOLD = 0xFFE8C86A;
    public static final int C_COMMAND = 0xFFA8D8FF;
    public static final int C_HINT = 0xFF9AA5B8;
    public static final int C_DESC = 0xFFD8D2C4;

    public record Line(String text, int color, boolean wrap) {}
    public record Slot(String item, int count) {}
    public record CraftGrid(String title, String[] pattern, Map<Character, Slot> keys, String result, int resultCount) {}
    public record BlockEntry(String id, String name, List<String> description, List<CraftGrid> crafts) {}

    private TravelerNotesContent() {}

    private static Line h(String t) { return new Line(t, C_HEADER, false); }
    private static Line g(String t) { return new Line(t, C_GOLD, false); }
    private static Line b(String t) { return new Line(t, C_BODY, true); }
    private static Line c(String t) { return new Line(t, C_COMMAND, true); }
    private static Line d(String t) { return new Line(t, C_DESC, true); }
    private static Line empty() { return new Line("", C_BODY, false); }

    private static Slot S(String item) { return new Slot(item, 1); }

    private static CraftGrid craft(String[] pattern, Map<Character, Slot> keys, String result, int count) {
        // У верстака всегда полная сетка 3x3 — рецепт кладётся сверху слева, остальное пусто.
        String[] padded = new String[3];
        for (int r = 0; r < 3; r++) {
            String src = r < pattern.length ? pattern[r] : "";
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                sb.append(c < src.length() ? src.charAt(c) : ' ');
            }
            padded[r] = sb.toString();
        }
        return new CraftGrid("Верстак", padded, keys, result, count);
    }

    private static CraftGrid stonecut(String input, String result) {
        return new CraftGrid("Камнерез", new String[]{"i"}, Map.of('i', S(input)), result, 1);
    }

    private static Map<Character, Slot> keys(Object... kv) {
        Map<Character, Slot> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(((String) kv[i]).charAt(0), (Slot) kv[i + 1]);
        }
        return m;
    }

    /** Вкладка «О сборке» — текстовая информация. */
    public static List<Line> about() {
        return List.of(
                h("Легенда о Селестии"),
                d("Селестия — парящий остров в небе над Тейватом, обитель богов и ангелов. Считается, что смертные, совершившие великие подвиги, возносятся туда и становятся богами, а обладатели Глаза Бога — «аллогены» — имеют шанс на вознесение. Небесные Принципы молчат со времён Катаклизма, но белый мрамор её шпилей с золотым астролябием до сих пор виден из Мондштадта и Лиюэ. Набор «Селестия» — камень её построек."),
                empty(),
                h("О сборке"),
                b("Teyvat — глобальный мод по Genshin Impact для Minecraft 1.21.10 (Fabric + Sodium + Iris)."),
                b("Сейчас фаза 0 — визуал: набор «Селестия» (30 мраморных блоков), TeyvatShader и ресурспак."),
                empty(),
                h("Состав"),
                b("• Мод: блоки, команды, этот UI."),
                b("• Ресурспак Teyvat: текстуры и модели блоков."),
                b("• TeyvatShader: форк Complementary Reimagined под стиль Genshin."),
                empty(),
                h("Установка и обновление"),
                b("Шейдер TeyvatShader и ресурспак Teyvat — в ~/.minecraft, мод teyvat.jar — в mods/."),
                b("Обновление: scripts/update.sh, затем F3+T (ресурспак) и F3+R (шейдер)."),
                b("После обновления Java-кода мода нужен перезапуск игры."),
                empty(),
                h("Команды"),
                c("/column ~ ~ ~"),
                b("Одна случайная колонна на месте игрока."),
                c("/column 100 64 100 5"),
                b("Пять колонн в ряд (шаг 3–5 блоков, count 1–64)."),
                c("/teyvat notes"),
                b("Открыть эти заметки."),
                c("/give @p teyvat:marble_arch"),
                b("Все 30 блоков — префикс teyvat:, есть в творческой вкладке «Блоки Тейвата»."),
                empty(),
                h("Шейдер"),
                b("Профиль Unbound (SHADER_STYLE 4), небо и туман — палитра Мондштадта, тун-диффуз TOON_BANDING."),
                b("PBR: золото зеркально ловит свет (specular), мрамор матовый, фонари дают тёплый блочный свет."),
                b("Настройки: 13 групп, у каждой один контрол; расширенные — в CUSTOM."),
                empty(),
                h("Что дальше"),
                b("Фаза 1 — стихии и реакции; фаза 2 — персонажи и оружие; фаза 3 — мир Тейвата; фаза 4 — HUD и музыка.")
        );
    }

    /** Вкладка «Селестия» — краткое вступление + все блоки от простого крафта к сложному. */
    public static List<Line> celestiaIntro() {
        return List.of(
                h("Набор «Селестия»"),
                d("Белый мрамор, из которого сложены постройки Селестии — парящего острова богов над Тейватом. У статуи Анемо Архонта в Мондштадте высечено: «Врата в Селестию»."),
                b("Ниже все 30 блоков от простого крафта к сложному: сказка о камне, рецепт, а справа от сеток — как блок выглядит в игре."),
                b("Совет: почти всё можно нарезать в камнерезе — иконка «Камнерез» показывает короткий путь.")
        );
    }

    public static List<BlockEntry> blocks() {
        List<BlockEntry> list = new ArrayList<>();
        String T = "teyvat:", M = "minecraft:";

        list.add(new BlockEntry("marble", "Мрамор", List.of(
                "Белый мрамор, из которого сложены шпили Селестии — острова богов, парящего в небе над Тейватом.",
                "Восемь кварцев и слиток золота — как Гнозис, который Архонты получают от Селестии."),
                List.of(craft(new String[]{"qqq", "qgq", "qqq"},
                        keys("q", S(M + "quartz"), "g", S(M + "gold_ingot")), T + "marble", 1))));

        list.add(new BlockEntry("polished_marble", "Полированный мрамор", List.of(
                "Отполирован до зеркала — как полы Нефритового чертога Нин Гуан над гаванью Лиюэ."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "polished_marble", 4),
                        stonecut(T + "marble", T + "polished_marble"))));

        list.add(new BlockEntry("marble_bricks", "Мраморная кирпичная кладка", List.of(
                "Кладка гавани Лиюэ: каждый кирпич держит слово, как контракт Властелина Камня."),
                List.of(craft(new String[]{"pp", "pp"}, keys("p", S(T + "polished_marble")), T + "marble_bricks", 4),
                        stonecut(T + "marble", T + "marble_bricks"))));

        list.add(new BlockEntry("marble_tiles", "Мраморная плитка", List.of(
                "Плитка залов Ордо Фавония: между швов застревает звон мечей и шёпот книг библиотеки."),
                List.of(craft(new String[]{"bb", "bb"}, keys("b", S(T + "marble_bricks")), T + "marble_tiles", 4),
                        stonecut(T + "marble", T + "marble_tiles"))));

        list.add(new BlockEntry("marble_pillar", "Рифлёная мраморная колонна", List.of(
                "Рифлёные колонны Собора Мондштадта: жёлобы ловят ветер Барбатоса. Из него делают ствол и малую колонну."),
                List.of(craft(new String[]{"m", "m"}, keys("m", S(T + "marble")), T + "marble_pillar", 1),
                        stonecut(T + "marble", T + "marble_pillar"))));

        list.add(new BlockEntry("marble_column_small", "Малая мраморная колонна", List.of(
                "Перила у статуи Семи: на них опираются, когда приносят окулусы и слушают ветер."),
                List.of(craft(new String[]{"p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_small", 1),
                        stonecut(T + "marble", T + "marble_column_small"))));

        list.add(new BlockEntry("marble_beam", "Мраморная балка", List.of(
                "Балки причалов Лиюэ: под ними грузчики спорят, сколько ящиков заказали Цисин. Шесть балок за один крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_beam", 6),
                        stonecut(T + "marble", T + "marble_beam"))));

        list.add(new BlockEntry("marble_slab", "Мраморная плита", List.of(
                "Ступенька у статуи Семи — на неё встают, чтобы дотянуться до окулуса. Шесть штук за крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_slab", 6),
                        stonecut(T + "marble", T + "marble_slab"))));

        list.add(new BlockEntry("marble_stairs", "Мраморные ступени", List.of(
                "Лестница Собора Мондштадта: по ней поднимаются к хору, считая шаги в такт ветру. Четыре штуки за крафт."),
                List.of(craft(new String[]{"m  ", "mm ", "mmm"}, keys("m", S(T + "marble")), T + "marble_stairs", 4),
                        stonecut(T + "marble", T + "marble_stairs"))));

        list.add(new BlockEntry("marble_wall", "Мраморная стена", List.of(
                "Стена сада Ордо Фавония: за ней прячутся от похитителей сокровищ и от лишних поручений рыцарей."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_wall", 6),
                        stonecut(T + "marble", T + "marble_wall"))));

        list.add(new BlockEntry("marble_fence", "Мраморная ограда", List.of(
                "Ограда библиотеки Ордо Фавония: редкий студент Академии Сумеру не пытался через неё перелезть за запретной книгой."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_fence", 6),
                        stonecut(T + "marble", T + "marble_fence"))));

        list.add(new BlockEntry("marble_column_base", "База мраморной колонны", List.of(
                "Подножие колонны, как у статуи Анемо Архонта: на постаменте высечено «Врата в Селестию»."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_column_base", 1),
                        stonecut(T + "marble", T + "marble_column_base"))));

        list.add(new BlockEntry("marble_column_capital", "Капитель мраморной колонны", List.of(
                "Капитель — как чаша для подношений: в неё кладут монеты и просят удачи перед дорогой. Можно вырезать из золотой окантовки."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_column_capital", 1),
                        stonecut(T + "marble", T + "marble_column_capital"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_column_capital"))));

        list.add(new BlockEntry("polished_marble_stairs", "Ступени из полированного мрамора", List.of(
                "Парадная лестница к причалу Лиюэ: по ней спускаются встречать корабль из Инадзумы. Камнерезом — напрямую из полированного мрамора."),
                List.of(craft(new String[]{"p  ", "pp ", "ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_stairs", 4),
                        stonecut(T + "polished_marble", T + "polished_marble_stairs"))));

        list.add(new BlockEntry("polished_marble_slab", "Плита из полированного мрамора", List.of(
                "Зеркальная плита из кабинета Нин Гуан: в неё смотрятся, пока решают судьбу контракта."),
                List.of(craft(new String[]{"ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_slab", 6),
                        stonecut(T + "polished_marble", T + "polished_marble_slab"))));

        list.add(new BlockEntry("marble_brick_stairs", "Ступени из мраморной кладки", List.of(
                "Ступени у Каменных ворот — на границе Мондштадта и Лиюэ, где путники спорят, чей ветер сильнее."),
                List.of(craft(new String[]{"b  ", "bb ", "bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_stairs", 4),
                        stonecut(T + "marble_bricks", T + "marble_brick_stairs"))));

        list.add(new BlockEntry("marble_brick_slab", "Плита из мраморной кладки", List.of(
                "Кирпич из кладки Каменных ворот: каждый подписан именем каменщика, как страница договора."),
                List.of(craft(new String[]{"bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_slab", 6),
                        stonecut(T + "marble_bricks", T + "marble_brick_slab"))));

        list.add(new BlockEntry("marble_tile_stairs", "Ступени из мраморной плитки", List.of(
                "Ступени великого храма Наруками: по ним поднимаются на цыпочках, чтобы не разбудить гром."),
                List.of(craft(new String[]{"t  ", "tt ", "ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_stairs", 4),
                        stonecut(T + "marble_tiles", T + "marble_tile_stairs"))));

        list.add(new BlockEntry("marble_tile_slab", "Плита из мраморной плитки", List.of(
                "Плитка из залов Академии Сумеру: на ней пишут формулы мелом, и дождь их не стирает."),
                List.of(craft(new String[]{"ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_slab", 6),
                        stonecut(T + "marble_tiles", T + "marble_tile_slab"))));

        list.add(new BlockEntry("marble_side_stairs", "Горизонтальные мраморные ступени", List.of(
                "Обломок, упавший набок, как скалы у Каменных ворот: по таким обходят стену, не тревожа стражу."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_side_stairs", 2),
                        stonecut(T + "marble", T + "marble_side_stairs"))));

        list.add(new BlockEntry("marble_fence_gate", "Мраморная калитка", List.of(
                "Калитка у статуи Семи: пропускает того, кто несёт окулус, и молчит перед фатуи."),
                List.of(craft(new String[]{"fmf", "fmf"},
                        keys("f", S(T + "marble_fence"), "m", S(T + "marble")), T + "marble_fence_gate", 1))));

        list.add(new BlockEntry("marble_arch", "Мраморная арка", List.of(
                "Арка городских ворот Мондштадта: проём всегда повёрнут к путнику, как страж у входа."),
                List.of(craft(new String[]{"m m", "mmm"}, keys("m", S(T + "marble")), T + "marble_arch", 1),
                        stonecut(T + "marble", T + "marble_arch"))));

        list.add(new BlockEntry("marble_gate", "Мраморные ворота", List.of(
                "Каменные ворота на границе Мондштадта и Лиюэ: с одной стороны пахнет одуванчиками, с другой — ладаном гавани."),
                List.of(craft(new String[]{"mmm", "m m"}, keys("m", S(T + "marble")), T + "marble_gate", 1),
                        stonecut(T + "marble", T + "marble_gate"))));

        list.add(new BlockEntry("marble_column_mid", "Ствол мраморной колонны", List.of(
                "Ствол с узором ветра — как колонны Собора Мондштадта, где гуляет ветер Барбатоса. Соединяет базу и капитель."),
                List.of(craft(new String[]{"p", "p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_mid", 1),
                        stonecut(T + "marble", T + "marble_column_mid"))));

        list.add(new BlockEntry("marble_column", "Мраморная колонна", List.of(
                "Колонна, на которой держится Нефритовый чертог: база, ствол и капитель парят над гаванью Лиюэ."),
                List.of(craft(new String[]{"c", "m", "b"},
                        keys("c", S(T + "marble_column_capital"), "m", S(T + "marble_column_mid"), "b", S(T + "marble_column_base")),
                        T + "marble_column", 1),
                        stonecut(T + "marble", T + "marble_column"))));

        list.add(new BlockEntry("marble_door", "Мраморная дверь", List.of(
                "Открывается мгновенно, как телепорт между статуями Семи: шаг — и ты уже у цели. Две штуки за крафт."),
                List.of(craft(new String[]{"mm", "mm", "mm"}, keys("m", S(T + "marble")), T + "marble_door", 2))));

        list.add(new BlockEntry("marble_lamp", "Мраморный светильник", List.of(
                "Светит, как Глаз Бога в тёмной Бездне: внутри светокамень, тёплый свет, уровень 13."),
                List.of(craft(new String[]{" m ", "mfm", " m "},
                        keys("m", S(T + "marble"), "f", S(M + "glowstone")), T + "marble_lamp", 1))));

        list.add(new BlockEntry("marble_pedestal", "Мраморный пьедестал", List.of(
                "Пьедестал перед статуей Семи: с него видно весь Мондштадт до Драконьего хребта. Самый «дорогой» крафт."),
                List.of(craft(new String[]{"mmm", "mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_pedestal", 1),
                        stonecut(T + "marble", T + "marble_pedestal"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_pedestal"))));

        list.add(new BlockEntry("chiseled_marble", "Резной мрамор", List.of(
                "Резьба, как на древних фресках Цуруми, где изображена Селестия над островом. Делается из двух плит."),
                List.of(craft(new String[]{"s", "s"}, keys("s", S(T + "marble_slab")), T + "chiseled_marble", 1),
                        stonecut(T + "marble", T + "chiseled_marble"),
                        stonecut(T + "polished_marble", T + "chiseled_marble"))));

        list.add(new BlockEntry("gold_trimmed_marble", "Мрамор с золотой окантовкой", List.of(
                "Золотая кайма, как золотой астролябий на шпиле Селестии: её постройки — из белого мрамора с золотом.",
                "Один слиток окантовывает сразу четыре блока."),
                List.of(craft(new String[]{"mmm", "mgm", "mmm"},
                        keys("m", S(T + "marble"), "g", S(M + "gold_ingot")), T + "gold_trimmed_marble", 4))));

        return list;
    }
}
