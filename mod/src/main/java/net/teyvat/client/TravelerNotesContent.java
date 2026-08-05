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
                d("Говорят, на самом краю неба стоит Селестия — дворец из белого камня, что помнит облака. Когда-то от него к земле спускалась лестница, по которой ходили первые жители Тейвата. Лестница пала, но её обломки остались в мире — белые, гладкие, тёплые. Мастера научились вплетать в них золотые нити, ловящие свет звёзд, и зажигать светокамни, упавшие с небес. Так родился набор «Селестия»."),
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
                d("Это обломки небесной лестницы, упавшей на землю. Каждый блок хранит память Селестии: белый камень, золотые нити и тёплый свет упавших звёзд."),
                b("Ниже все 30 блоков по порядку: от самого простого крафта к самому сложному — большое изображение, сказка о камне и рецепт."),
                b("Совет: почти всё можно нарезать в камнерезе — иконка «Камнерез» показывает короткий путь.")
        );
    }

    public static List<BlockEntry> blocks() {
        List<BlockEntry> list = new ArrayList<>();
        String T = "teyvat:", M = "minecraft:";

        list.add(new BlockEntry("marble", "Мрамор", List.of(
                "Первый камень Селестии: белый, как облака над Мондштадтом. Говорят, он помнит небесную лестницу, по которой уходили души.",
                "Крафтится из кварца с золотом — будто сами звёзды склеивают обломок неба."),
                List.of(craft(new String[]{"qqq", "qgq", "qqq"},
                        keys("q", S(M + "quartz"), "g", S(M + "gold_ingot")), T + "marble", 1))));

        list.add(new BlockEntry("polished_marble", "Полированный мрамор", List.of(
                "Отполирован дыханием ветра: гладкий, как зеркало небесного озера. Если долго смотреть, в глубине виден отсвет заоблачных дворцов."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "polished_marble", 4),
                        stonecut(T + "marble", T + "polished_marble"))));

        list.add(new BlockEntry("marble_bricks", "Мраморная кирпичная кладка", List.of(
                "Такой кладкой небесные мастера скрепляли ступени Селестии: каждый кирпич лёг так, чтобы держать небо."),
                List.of(craft(new String[]{"pp", "pp"}, keys("p", S(T + "polished_marble")), T + "marble_bricks", 4),
                        stonecut(T + "marble", T + "marble_bricks"))));

        list.add(new BlockEntry("marble_tiles", "Мраморная плитка", List.of(
                "Полы заоблачных залов: в частых швах застревает свет звёзд, и по ночам плитка тихо мерцает."),
                List.of(craft(new String[]{"bb", "bb"}, keys("b", S(T + "marble_bricks")), T + "marble_tiles", 4),
                        stonecut(T + "marble", T + "marble_tiles"))));

        list.add(new BlockEntry("marble_pillar", "Рифлёная мраморная колонна", List.of(
                "Струны ветра, застывшие в камне: на таких пилонах держался балкон Селестии. Из него делают ствол и малую колонну."),
                List.of(craft(new String[]{"m", "m"}, keys("m", S(T + "marble")), T + "marble_pillar", 1),
                        stonecut(T + "marble", T + "marble_pillar"))));

        list.add(new BlockEntry("marble_column_small", "Малая мраморная колонна", List.of(
                "Тонкая колонна для перил той самой лестницы, по которой ходили первые жители Тейвата."),
                List.of(craft(new String[]{"p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_small", 1),
                        stonecut(T + "marble", T + "marble_column_small"))));

        list.add(new BlockEntry("marble_beam", "Мраморная балка", List.of(
                "Небесная балка: на таких мостили мосты между облаками. Шесть балок за один крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_beam", 6),
                        stonecut(T + "marble", T + "marble_beam"))));

        list.add(new BlockEntry("marble_slab", "Мраморная плита", List.of(
                "Плита-ступенька: каждая из них — один шаг по лестнице к небесам. Шесть штук за крафт."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_slab", 6),
                        stonecut(T + "marble", T + "marble_slab"))));

        list.add(new BlockEntry("marble_stairs", "Мраморные ступени", List.of(
                "Ступени Селестии: белые и ровные, будто их обтёсывал сам ветер. Четыре штуки за крафт."),
                List.of(craft(new String[]{"m  ", "mm ", "mmm"}, keys("m", S(T + "marble")), T + "marble_stairs", 4),
                        stonecut(T + "marble", T + "marble_stairs"))));

        list.add(new BlockEntry("marble_wall", "Мраморная стена", List.of(
                "Ограда небесного сада с золотой нитью — чтобы звёзды не уходили гулять по земле."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_wall", 6),
                        stonecut(T + "marble", T + "marble_wall"))));

        list.add(new BlockEntry("marble_fence", "Мраморная ограда", List.of(
                "Резная ограда с золотой нитью: её ставят там, где нужно оградить сон или тайну."),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_fence", 6),
                        stonecut(T + "marble", T + "marble_fence"))));

        list.add(new BlockEntry("marble_column_base", "База мраморной колонны", List.of(
                "Корень колонны: база впивается в землю, чтобы колонна могла держать небо."),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_column_base", 1),
                        stonecut(T + "marble", T + "marble_column_base"))));

        list.add(new BlockEntry("marble_column_capital", "Капитель мраморной колонны", List.of(
                "Ладонь камня: именно на капитель опирается перекрытие небесных залов. Можно вырезать из золотой окантовки."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_column_capital", 1),
                        stonecut(T + "marble", T + "marble_column_capital"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_column_capital"))));

        list.add(new BlockEntry("polished_marble_stairs", "Ступени из полированного мрамора", List.of(
                "Парадные ступени: по таким входят в залы, где решают судьбы. Камнерезом — напрямую из полированного мрамора."),
                List.of(craft(new String[]{"p  ", "pp ", "ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_stairs", 4),
                        stonecut(T + "polished_marble", T + "polished_marble_stairs"))));

        list.add(new BlockEntry("polished_marble_slab", "Плита из полированного мрамора", List.of(
                "Зеркало небесных чертогов: в него, говорят, смотрелись сами богини."),
                List.of(craft(new String[]{"ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_slab", 6),
                        stonecut(T + "polished_marble", T + "polished_marble_slab"))));

        list.add(new BlockEntry("marble_brick_stairs", "Ступени из мраморной кладки", List.of(
                "Ступени из небесной кладки — для тех, кто не торопится к небесам."),
                List.of(craft(new String[]{"b  ", "bb ", "bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_stairs", 4),
                        stonecut(T + "marble_bricks", T + "marble_brick_stairs"))));

        list.add(new BlockEntry("marble_brick_slab", "Плита из мраморной кладки", List.of(
                "Ещё один кирпичик небесной лестницы: положи его — и небо станет чуть ближе."),
                List.of(craft(new String[]{"bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_slab", 6),
                        stonecut(T + "marble_bricks", T + "marble_brick_slab"))));

        list.add(new BlockEntry("marble_tile_stairs", "Ступени из мраморной плитки", List.of(
                "Ступени, выложенные плиткой, — как страницы каменной книги небес."),
                List.of(craft(new String[]{"t  ", "tt ", "ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_stairs", 4),
                        stonecut(T + "marble_tiles", T + "marble_tile_stairs"))));

        list.add(new BlockEntry("marble_tile_slab", "Плита из мраморной плитки", List.of(
                "Плита-страница: на ней можно записать обет, и он не сотрётся."),
                List.of(craft(new String[]{"ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_slab", 6),
                        stonecut(T + "marble_tiles", T + "marble_tile_slab"))));

        list.add(new BlockEntry("marble_side_stairs", "Горизонтальные мраморные ступени", List.of(
                "Обломок лестницы, упавшей набок: по таким можно обойти стену небес и подняться не спеша."),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_side_stairs", 2),
                        stonecut(T + "marble", T + "marble_side_stairs"))));

        list.add(new BlockEntry("marble_fence_gate", "Мраморная калитка", List.of(
                "Калитка небесного сада: страж её не запирает — золотая нить сама решает, кого впустить."),
                List.of(craft(new String[]{"fmf", "fmf"},
                        keys("f", S(T + "marble_fence"), "m", S(T + "marble")), T + "marble_fence_gate", 1))));

        list.add(new BlockEntry("marble_arch", "Мраморная арка", List.of(
                "Арка Селестии: под такой проходили, оставляя тревоги на пороге. Проём поворачивается к тому, кто входит."),
                List.of(craft(new String[]{"m m", "mmm"}, keys("m", S(T + "marble")), T + "marble_arch", 1),
                        stonecut(T + "marble", T + "marble_arch"))));

        list.add(new BlockEntry("marble_gate", "Мраморные ворота", List.of(
                "Ворота небесных залов: в окнах-выемках отражаются звёзды, поэтому они одинаковы с обеих сторон."),
                List.of(craft(new String[]{"mmm", "m m"}, keys("m", S(T + "marble")), T + "marble_gate", 1),
                        stonecut(T + "marble", T + "marble_gate"))));

        list.add(new BlockEntry("marble_column_mid", "Ствол мраморной колонны", List.of(
                "Середина лестницы: на стволе небесные мастера вырезали узоры ветра. Соединяет базу и капитель."),
                List.of(craft(new String[]{"p", "p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_mid", 1),
                        stonecut(T + "marble", T + "marble_column_mid"))));

        list.add(new BlockEntry("marble_column", "Мраморная колонна", List.of(
                "Полная колонна Селестии: база, ствол и капитель, собранные вместе, держат целый этаж неба."),
                List.of(craft(new String[]{"c", "m", "b"},
                        keys("c", S(T + "marble_column_capital"), "m", S(T + "marble_column_mid"), "b", S(T + "marble_column_base")),
                        T + "marble_column", 1),
                        stonecut(T + "marble", T + "marble_column"))));

        list.add(new BlockEntry("marble_door", "Мраморная дверь", List.of(
                "Дверь из небесного камня: открывается мгновенно, стоит лишь пожелать. Две штуки за крафт."),
                List.of(craft(new String[]{"mm", "mm", "mm"}, keys("m", S(T + "marble")), T + "marble_door", 2))));

        list.add(new BlockEntry("marble_lamp", "Мраморный светильник", List.of(
                "Светильник на светокамне — осколке упавшей звезды. Тёплый свет, который любят золотые нити (уровень 13)."),
                List.of(craft(new String[]{" m ", "mfm", " m "},
                        keys("m", S(T + "marble"), "f", S(M + "glowstone")), T + "marble_lamp", 1))));

        list.add(new BlockEntry("marble_pedestal", "Мраморный пьедестал", List.of(
                "Трон для статуи: на него становились те, кто хотел быть ближе к небу. Самый «дорогой» крафт."),
                List.of(craft(new String[]{"mmm", "mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_pedestal", 1),
                        stonecut(T + "marble", T + "marble_pedestal"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_pedestal"))));

        list.add(new BlockEntry("chiseled_marble", "Резной мрамор", List.of(
                "Узор вплетён в камень, как песня в память: в завитках решётки спрятаны имена первых мастеров. Делается из двух плит."),
                List.of(craft(new String[]{"s", "s"}, keys("s", S(T + "marble_slab")), T + "chiseled_marble", 1),
                        stonecut(T + "marble", T + "chiseled_marble"),
                        stonecut(T + "polished_marble", T + "chiseled_marble"))));

        list.add(new BlockEntry("gold_trimmed_marble", "Мрамор с золотой окантовкой", List.of(
                "Самый дорогой дар Селестии: золотая нить ловит свет, как память ловит лучшие дни.",
                "Один слиток окантовывает сразу четыре блока."),
                List.of(craft(new String[]{"mmm", "mgm", "mmm"},
                        keys("m", S(T + "marble"), "g", S(M + "gold_ingot")), T + "gold_trimmed_marble", 4))));

        return list;
    }
}
