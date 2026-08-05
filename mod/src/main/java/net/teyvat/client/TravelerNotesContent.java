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
        return new CraftGrid("Верстак", pattern, keys, result, count);
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
                b("30 белоснежных мраморных блоков в стиле античных залов и загрузочного экрана Genshin."),
                b("Ниже каждый блок по порядку: от самого простого крафта к самому сложному — иконка, описание и рецепт."),
                b("Совет: почти всё можно нарезать в камнерезе — иконка «Камнерез» показывает короткий путь.")
        );
    }

    public static List<BlockEntry> blocks() {
        List<BlockEntry> list = new ArrayList<>();
        String T = "teyvat:", M = "minecraft:";

        list.add(new BlockEntry("marble", "Мрамор", List.of(
                "Исходный материал набора. Белоснежный, без пятен и зерна — основа почти всех крафтов.",
                "Крафтится из кварца с золотом; в будущем — жилы мрамора в мире."),
                List.of(craft(new String[]{"qqq", "qgq", "qqq"},
                        keys("q", S(M + "quartz"), "g", S(M + "gold_ingot")), T + "marble", 1))));

        list.add(new BlockEntry("polished_marble", "Полированный мрамор", List.of(
                "Гладкая, слегка глянцевая поверхность. Промежуточный материал для кладки и ступеней."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "polished_marble", 4),
                        stonecut(T + "marble", T + "polished_marble"))));

        list.add(new BlockEntry("marble_bricks", "Мраморная кирпичная кладка", List.of(
                "Ровные ряды со смещением — тёплый «дворцовый» узор для больших стен."),
                List.of(craft(new String[]{"pp", "pp"}, keys("p", S(T + "polished_marble")), T + "marble_bricks", 4),
                        stonecut(T + "marble", T + "marble_bricks"))));

        list.add(new BlockEntry("marble_tiles", "Мраморная плитка", List.of(
                "Частые швы, как в античных залах и храмах. Хорошо смотрится с кладкой."),
                List.of(craft(new String[]{"bb", "bb"}, keys("b", S(T + "marble_bricks")), T + "marble_tiles", 4),
                        stonecut(T + "marble", T + "marble_tiles"))));

        list.add(new BlockEntry("marble_pillar", "Рифлёная мраморная колонна", List.of(
                "Вертикальные каннелюры — базовый пилон. Из него делают ствол и малую колонну."),
                List.of(craft(new String[]{"m", "m"}, keys("m", S(T + "marble")), T + "marble_pillar", 1),
                        stonecut(T + "marble", T + "marble_pillar"))));

        list.add(new BlockEntry("marble_column_small", "Малая мраморная колонна", List.of(
                "Изящная тонкая колонна для балюстрад, перил и мелких деталей."),
                List.of(craft(new String[]{"p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_small", 1),
                        stonecut(T + "marble", T + "marble_column_small"))));

        list.add(new BlockEntry("marble_beam", "Мраморная балка", List.of(
                "Горизонтальный пилон для перекрытий, рам и ворот. Шесть балок за один крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_beam", 6),
                        stonecut(T + "marble", T + "marble_beam"))));

        list.add(new BlockEntry("marble_slab", "Мраморная плита", List.of(
                "Половина блока — для полов, дорожек и тонких перекрытий. Шесть штук за крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_slab", 6),
                        stonecut(T + "marble", T + "marble_slab"))));

        list.add(new BlockEntry("marble_stairs", "Мраморные ступени", List.of(
                "Классическая лестница с ровными гранями. Четыре штуки за крафт."),
                List.of(craft(new String[]{"m  ", "mm ", "mmm"}, keys("m", S(T + "marble")), T + "marble_stairs", 4),
                        stonecut(T + "marble", T + "marble_stairs"))));

        list.add(new BlockEntry("marble_wall", "Мраморная стена", List.of(
                "Невысокое ограждение с бледной золотой окантовкой. Стыкуется с оградой."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_wall", 6),
                        stonecut(T + "marble", T + "marble_wall"))));

        list.add(new BlockEntry("marble_fence", "Мраморная ограда", List.of(
                "Столбики с перекладинами и золотой окантовкой. Прозрачная для взгляда."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_fence", 6),
                        stonecut(T + "marble", T + "marble_fence"))));

        list.add(new BlockEntry("marble_column_base", "База мраморной колонны", List.of(
                "Расширенное основание колонны. Первая деталь сборной колонны."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_column_base", 1),
                        stonecut(T + "marble", T + "marble_column_base"))));

        list.add(new BlockEntry("marble_column_capital", "Капитель мраморной колонны", List.of(
                "Расширенное навершие, венчает колонну. Можно вырезать из золотой окантовки."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_column_capital", 1),
                        stonecut(T + "marble", T + "marble_column_capital"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_column_capital"))));

        list.add(new BlockEntry("polished_marble_stairs", "Ступени из полированного мрамора", List.of(
                "Гладкие парадные ступени. Камнерезом — напрямую из полированного мрамора."),
                List.of(craft(new String[]{"p  ", "pp ", "ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_stairs", 4),
                        stonecut(T + "polished_marble", T + "polished_marble_stairs"))));

        list.add(new BlockEntry("polished_marble_slab", "Плита из полированного мрамора", List.of(
                "Гладкая плита для полов в парадных залах."),
                List.of(craft(new String[]{"ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_slab", 6),
                        stonecut(T + "polished_marble", T + "polished_marble_slab"))));

        list.add(new BlockEntry("marble_brick_stairs", "Ступени из мраморной кладки", List.of(
                "Лестница в тон кирпичных стен."),
                List.of(craft(new String[]{"b  ", "bb ", "bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_stairs", 4),
                        stonecut(T + "marble_bricks", T + "marble_brick_stairs"))));

        list.add(new BlockEntry("marble_brick_slab", "Плита из мраморной кладки", List.of(
                "Плита с узором кладки."),
                List.of(craft(new String[]{"bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_slab", 6),
                        stonecut(T + "marble_bricks", T + "marble_brick_slab"))));

        list.add(new BlockEntry("marble_tile_stairs", "Ступени из мраморной плитки", List.of(
                "Лестница с плиточным узором."),
                List.of(craft(new String[]{"t  ", "tt ", "ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_stairs", 4),
                        stonecut(T + "marble_tiles", T + "marble_tile_stairs"))));

        list.add(new BlockEntry("marble_tile_slab", "Плита из мраморной плитки", List.of(
                "Плита с плиточным узором."),
                List.of(craft(new String[]{"ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_slab", 6),
                        stonecut(T + "marble_tiles", T + "marble_tile_slab"))));

        list.add(new BlockEntry("marble_side_stairs", "Горизонтальные мраморные ступени", List.of(
                "Блок 3/4: делится на четыре столбика, один удалён. Для пандусов и «ступенек вбок»."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_side_stairs", 2),
                        stonecut(T + "marble", T + "marble_side_stairs"))));

        list.add(new BlockEntry("marble_fence_gate", "Мраморная калитка", List.of(
                "Открывается, как ванильная, — с золотой окантовкой."),
                List.of(craft(new String[]{"sms", "sms"},
                        keys("s", S(M + "stick"), "m", S(T + "marble")), T + "marble_fence_gate", 1))));

        list.add(new BlockEntry("marble_arch", "Мраморная арка", List.of(
                "Арочная ниша с теснением. При установке поворачивается проёмом к игроку."),
                List.of(craft(new String[]{"m m", "mmm"}, keys("m", S(T + "marble")), T + "marble_arch", 1),
                        stonecut(T + "marble", T + "marble_arch"))));

        list.add(new BlockEntry("marble_gate", "Мраморные ворота", List.of(
                "Двустворчатый рельеф с выемками-окнами. Симметричный, с обеих сторон одинаковый."),
                List.of(craft(new String[]{"mmm", "m m"}, keys("m", S(T + "marble")), T + "marble_gate", 1),
                        stonecut(T + "marble", T + "marble_gate"))));

        list.add(new BlockEntry("marble_column_mid", "Ствол мраморной колонны", List.of(
                "Резная секция средней высоты — соединяет базу и капитель."),
                List.of(craft(new String[]{"p", "p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_mid", 1),
                        stonecut(T + "marble", T + "marble_column_mid"))));

        list.add(new BlockEntry("marble_column", "Мраморная колонна", List.of(
                "Полная колонна: база + ствол + капитель в столбик. Ставится в один блок."),
                List.of(craft(new String[]{"c", "m", "b"},
                        keys("c", S(T + "marble_column_capital"), "m", S(T + "marble_column_mid"), "b", S(T + "marble_column_base")),
                        T + "marble_column", 1),
                        stonecut(T + "marble", T + "marble_column"))));

        list.add(new BlockEntry("marble_door", "Мраморная дверь", List.of(
                "Двухблочная дверь, открывается мгновенно, как ванильная. Две штуки за крафт."),
                List.of(craft(new String[]{"mm", "mm", "mm"}, keys("m", S(T + "marble")), T + "marble_door", 2))));

        list.add(new BlockEntry("marble_lamp", "Мраморный светильник", List.of(
                "Тёплый свет уровня 13. Золото рядом ловит его блики — красиво в колоннадах."),
                List.of(craft(new String[]{" m ", "mfm", " m "},
                        keys("m", S(T + "marble"), "f", S(M + "torch")), T + "marble_lamp", 1))));

        list.add(new BlockEntry("marble_pedestal", "Мраморный пьедестал", List.of(
                "Массивное трёхступенчатое основание для статуй и колонн. Самый «дорогой» крафт."),
                List.of(craft(new String[]{"mmm", "mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_pedestal", 1),
                        stonecut(T + "marble", T + "marble_pedestal"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_pedestal"))));

        list.add(new BlockEntry("chiseled_marble", "Резной мрамор", List.of(
                "Рельефная решётка: узор вдавлен внутрь на 3px, сквозных дыр нет. Делается из двух плит."),
                List.of(craft(new String[]{"s", "s"}, keys("s", S(T + "marble_slab")), T + "chiseled_marble", 1),
                        stonecut(T + "marble", T + "chiseled_marble"),
                        stonecut(T + "polished_marble", T + "chiseled_marble"))));

        list.add(new BlockEntry("gold_trimmed_marble", "Мрамор с золотой окантовкой", List.of(
                "Бледная золотая рамка — финальный штрих набора. Золото зеркально ловит свет фонарей.",
                "Один слиток золота окантовывает сразу четыре блока."),
                List.of(craft(new String[]{"mmm", "mgm", "mmm"},
                        keys("m", S(T + "marble"), "g", S(M + "gold_ingot")), T + "gold_trimmed_marble", 4))));

        return list;
    }
}
