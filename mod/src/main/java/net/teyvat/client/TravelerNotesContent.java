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
                d("Селестия — парящий остров в небе над Тейватом, обитель богов. Архитектура острова сложена из белого мрамора, а вершину шпиля венчает золотой астролябий. Остров держится в небе по воле Небесного Порядка, который безмолвствует со времён Катаклизма, случившегося пятьсот лет назад. Только смертные, совершившие великие героические подвиги, возносятся на Селестию и достигают божественности. Обладателей Глаза Бога называют аллогенами, и они обладают потенциалом к вознесению. Белый мрамор и золото этих построек собраны в наборе «Селестия»."),
                empty(),
                h("О сборке"),
                b("Teyvat — глобальный мод по Genshin Impact для Minecraft 1.21.10 (Fabric + Sodium + Iris)."),
                empty(),
                h("Состав"),
                b("• Мод Пак: блоки, команды, функционал."),
                b("• Ресурс Пак: текстуры и модели."),
                b("• Шейдер Пак: форк Complementary Reimagined под стиль."),
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
                b("Настройки: 13 групп, у каждой один контрол; расширенные в CUSTOM."),
                empty(),
                h("Автор"),
                b("Автор полной сборки - Amenoke ❤️")
        );
    }

    /** Вкладка «Селестия» — краткое вступление + все блоки от простого крафта к сложному. */
    public static List<Line> celestiaIntro() {
        return List.of(
                h("Набор «Селестия»"),
                d("Набор собран из белого мрамора построек Селестии — парящего острова богов над Тейватом. Каждый блок хранит частицу небесного камня, а золотая окантовка повторяет золото астролябия на вершине шпиля."),
                b("Сказка о камне дополнена рецептом по центру."),
                b("Совет: почти каждый блок можно вырезать в камнерезе, и иконка «Камнерез» показывает короткий путь.")
        );
    }

    public static List<BlockEntry> blocks() {
        List<BlockEntry> list = new ArrayList<>();
        String T = "teyvat:", M = "minecraft:";

        list.add(new BlockEntry("marble", "Мрамор", List.of(
                "Шпили Селестии сложены из этого белого мрамора. Говорят, что камень принесли ангелы, когда поднимали остров богов в небо Тейвата."
        ),
                List.of(craft(new String[]{"qqq", "qgq", "qqq"},
                        keys("q", S(M + "quartz"), "g", S(M + "gold_ingot")), T + "marble", 1))));

        list.add(new BlockEntry("polished_marble", "Полированный мрамор", List.of(
                "Мастера Селестии полируют белый мрамор до зеркального блеска. Отлично подходит для создания монолитных сооружений.",
                "Четыре полированных блока получаются из четырёх блоков обычного мрамора."
        ),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "polished_marble", 4),
                        stonecut(T + "marble", T + "polished_marble"))));

        list.add(new BlockEntry("marble_bricks", "Мраморная кирпичная кладка", List.of(
                "Небесные стены сложены из этой кирпичной кладки.",
                "Четыре кирпича выкладываются из четырёх полированных блоков."
        ),
                List.of(craft(new String[]{"pp", "pp"}, keys("p", S(T + "polished_marble")), T + "marble_bricks", 4),
                        stonecut(T + "marble", T + "marble_bricks"))));

        list.add(new BlockEntry("marble_tiles", "Мраморная плитка", List.of(
                "Полы небесных залов выложены этой плиткой.",
                "Четыре плитки складываются из четырёх кирпичей кладки."
        ),
                List.of(craft(new String[]{"bb", "bb"}, keys("b", S(T + "marble_bricks")), T + "marble_tiles", 4),
                        stonecut(T + "marble", T + "marble_tiles"))));

        list.add(new BlockEntry("marble_pillar", "Рифлёная мраморная колонна", List.of(
                "Рифлёные колонны поддерживают мосты и арки Селестии.",
                "Один столб вырезается из одного блока мрамора."
        ),
                List.of(craft(new String[]{"m", "m"}, keys("m", S(T + "marble")), T + "marble_pillar", 1),
                        stonecut(T + "marble", T + "marble_pillar"))));

        list.add(new BlockEntry("marble_column_small", "Малая мраморная колонна", List.of(
                "Малые колонны стоят у врат небесных залов. Души, ожидающие вознесения, опираются на них.",
                "Малая колонна вырезается из одного рифлёного столба."
        ),
                List.of(craft(new String[]{"p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_small", 1),
                        stonecut(T + "marble", T + "marble_column_small"))));

        list.add(new BlockEntry("marble_beam", "Мраморная балка", List.of(
                "Балки соединяют скалы Селестии и удерживают мосты между островами.",
                "Шесть балок получаются из трёх блоков мрамора."
        ),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_beam", 6),
                        stonecut(T + "marble", T + "marble_beam"))));

        list.add(new BlockEntry("marble_slab", "Мраморная плита", List.of(
                "Плиты образуют ступени у врат Селестии.",
                "Шесть плит вырезаются из трёх блоков мрамора."
        ),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_slab", 6),
                        stonecut(T + "marble", T + "marble_slab"))));

        list.add(new BlockEntry("marble_stairs", "Мраморные ступени", List.of(
                "По этим ступеням поднимаются к шпилям Селестии на пути к вознесению.",
                "Четыре ступени складываются из шести блоков мрамора."
        ),
                List.of(craft(new String[]{"m  ", "mm ", "mmm"}, keys("m", S(T + "marble")), T + "marble_stairs", 4),
                        stonecut(T + "marble", T + "marble_stairs"))));

        list.add(new BlockEntry("marble_wall", "Мраморная стена", List.of(
                "Стены окружают края небесного острова. За ними скрываются сады богов от взглядов смертных.",
                "Шесть секций стены складываются из шести блоков мрамора."
        ),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_wall", 6),
                        stonecut(T + "marble", T + "marble_wall"))));

        list.add(new BlockEntry("marble_fence", "Мраморная ограда", List.of(
                "Ограда протянута вдоль всего края Селестии.",
                "Шесть секций ограды получаются из шести блоков мрамора."
        ),
                List.of(craft(new String[]{"mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_fence", 6),
                        stonecut(T + "marble", T + "marble_fence"))));

        list.add(new BlockEntry("marble_column_base", "База мраморной колонны", List.of(
                "На этих основаниях стоят колонны небесных залов. База принимает на себя вес шпилей и держит его веками.",
                "Одна база вырезается из одного блока мрамора."
        ),
                List.of(craft(new String[]{"mmm"}, keys("m", S(T + "marble")), T + "marble_column_base", 1),
                        stonecut(T + "marble", T + "marble_column_base"))));

        list.add(new BlockEntry("marble_column_capital", "Капитель мраморной колонны", List.of(
                "Капители выполнены в форме чаш для подношений. В них кладут дары небесам перед входом в залы богов.",
                "Четыре блока мрамора складываются в одну капитель."
        ),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_column_capital", 1),
                        stonecut(T + "marble", T + "marble_column_capital"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_column_capital"))));

        list.add(new BlockEntry("polished_marble_stairs", "Ступени из полированного мрамора", List.of(
                "Парадные ступени ведут к трону Селестии. По ним спускаются ангелы, когда остров посещают боги.",
                "Четыре ступени получаются из шести полированных блоков."
        ),
                List.of(craft(new String[]{"p  ", "pp ", "ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_stairs", 4),
                        stonecut(T + "polished_marble", T + "polished_marble_stairs"))));

        list.add(new BlockEntry("polished_marble_slab", "Плита из полированного мрамора", List.of(
                "Зеркальные плиты выстилают галереи Селестии.",
                "Шесть плит вырезаются из трёх полированных блоков."
        ),
                List.of(craft(new String[]{"ppp"}, keys("p", S(T + "polished_marble")), T + "polished_marble_slab", 6),
                        stonecut(T + "polished_marble", T + "polished_marble_slab"))));

        list.add(new BlockEntry("marble_brick_stairs", "Ступени из мраморной кладки", List.of(
                "Ступени из кирпичной кладки ведут вдоль небесных стен.",
                "Четыре ступени складываются из шести кирпичей."
        ),
                List.of(craft(new String[]{"b  ", "bb ", "bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_stairs", 4),
                        stonecut(T + "marble_bricks", T + "marble_brick_stairs"))));

        list.add(new BlockEntry("marble_brick_slab", "Плита из мраморной кладки", List.of(
                "Каждая плита кладки хранит древнее имя, выбитое на камне. Кто оставил эти имена, неизвестно.",
                "Шесть плит получаются из шести кирпичей."
        ),
                List.of(craft(new String[]{"bbb"}, keys("b", S(T + "marble_bricks")), T + "marble_brick_slab", 6),
                        stonecut(T + "marble_bricks", T + "marble_brick_slab"))));

        list.add(new BlockEntry("marble_tile_stairs", "Ступени из мраморной плитки", List.of(
                "Эти ступени поднимаются к трону через залы Селестии.",
                "Четыре ступени складываются из шести плиток."
        ),
                List.of(craft(new String[]{"t  ", "tt ", "ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_stairs", 4),
                        stonecut(T + "marble_tiles", T + "marble_tile_stairs"))));

        list.add(new BlockEntry("marble_tile_slab", "Плита из мраморной плитки", List.of(
                "Полы небесных залов выложены этой плиткой.",
                "Шесть плит получаются из трёх плиток."
        ),
                List.of(craft(new String[]{"ttt"}, keys("t", S(T + "marble_tiles")), T + "marble_tile_slab", 6),
                        stonecut(T + "marble_tiles", T + "marble_tile_slab"))));

        list.add(new BlockEntry("marble_side_stairs", "Горизонтальные мраморные ступени", List.of(
                "Диагональные угловые ступени, поддерживающие платформы в небе и обрамляющие главные ворота.",
                "Четыре блока мрамора образуют две боковые ступени."
        ),
                List.of(craft(new String[]{"mm", "mm"}, keys("m", S(T + "marble")), T + "marble_side_stairs", 2),
                        stonecut(T + "marble", T + "marble_side_stairs"))));

        list.add(new BlockEntry("marble_fence_gate", "Мраморная калитка", List.of(
                "Калитка в ограде Селестии открывается тем, кто несёт Глаз Бога. Она пропускает путника и смыкается за ним.",
                "Две секции ограды и один блок мрамора образуют одну калитку."
        ),
                List.of(craft(new String[]{"fmf", "fmf"},
                        keys("f", S(T + "marble_fence"), "m", S(T + "marble")), T + "marble_fence_gate", 1))));

        list.add(new BlockEntry("marble_arch", "Мраморная арка", List.of(
                "Одна из старинных античных арок, которые часто венчают колонны.",
                "Пять блоков мрамора складываются в одну арку."
        ),
                List.of(craft(new String[]{"m m", "mmm"}, keys("m", S(T + "marble")), T + "marble_arch", 1),
                        stonecut(T + "marble", T + "marble_arch"))));

        list.add(new BlockEntry("marble_gate", "Мраморные ворота", List.of(
                "Часть великих Главных Ворот, скрывающих Селестию от смертных.",
                "Пять блоков мрамора складываются в одни ворота."
        ),
                List.of(craft(new String[]{"mmm", "m m"}, keys("m", S(T + "marble")), T + "marble_gate", 1),
                        stonecut(T + "marble", T + "marble_gate"))));

        list.add(new BlockEntry("marble_column_mid", "Ствол мраморной колонны", List.of(
                "Ствол соединяет базу и капитель колонны.",
                "Два рифлёных столба образуют один ствол."
        ),
                List.of(craft(new String[]{"p", "p"}, keys("p", S(T + "marble_pillar")), T + "marble_column_mid", 1),
                        stonecut(T + "marble", T + "marble_column_mid"))));

        list.add(new BlockEntry("marble_column", "Мраморная колонна", List.of(
                "Полная колонна держит небесные залы Селестии. База, ствол и капитель складываются вместе.",
                "Капитель, ствол и база соединяются в одну колонну."
        ),
                List.of(craft(new String[]{"c", "m", "b"},
                        keys("c", S(T + "marble_column_capital"), "m", S(T + "marble_column_mid"), "b", S(T + "marble_column_base")),
                        T + "marble_column", 1),
                        stonecut(T + "marble", T + "marble_column"))));

        list.add(new BlockEntry("marble_door", "Мраморная дверь", List.of(
                "Двери небесных залов вытесанные из лучшего мрамора Тейвата.",
                "Шесть блоков мрамора образуют две двери."
        ),
                List.of(craft(new String[]{"mm", "mm", "mm"}, keys("m", S(T + "marble")), T + "marble_door", 2))));

        list.add(new BlockEntry("marble_lamp", "Мраморный светильник", List.of(
                "Внутри светильников Селестии горит светокамень, разливая тёплый свет по залам.",
                "Четыре блока мрамора оправляют светокамень, и рождается светильник."
        ),
                List.of(craft(new String[]{" m ", "mfm", " m "},
                        keys("m", S(T + "marble"), "f", S(M + "glowstone")), T + "marble_lamp", 1))));

        list.add(new BlockEntry("marble_pedestal", "Мраморный пьедестал", List.of(
                "Пьедесталы стоят у трона Селестии. С них открывается вид на весь остров богов и его окрестные скалы.",
                "Девять блоков мрамора образуют один пьедестал."
        ),
                List.of(craft(new String[]{"mmm", "mmm", "mmm"}, keys("m", S(T + "marble")), T + "marble_pedestal", 1),
                        stonecut(T + "marble", T + "marble_pedestal"),
                        stonecut(T + "gold_trimmed_marble", T + "marble_pedestal"))));

        list.add(new BlockEntry("chiseled_marble", "Резной мрамор", List.of(
                "На резном мраморе высечены древние письмена Селестии. Узоры повторяют знаки, оставленные первыми создателями острова.",
                "Две плиты мрамора превращаются в один резной блок."
        ),
                List.of(craft(new String[]{"s", "s"}, keys("s", S(T + "marble_slab")), T + "chiseled_marble", 1),
                        stonecut(T + "marble", T + "chiseled_marble"),
                        stonecut(T + "polished_marble", T + "chiseled_marble"))));

        list.add(new BlockEntry("gold_trimmed_marble", "Мрамор с золотой окантовкой", List.of(
                "Золотая кайма огибает белый мрамор, и тот же металл лежит в основе астролябия на вершине шпиля. Кайма ловит свет и отражает его.",
                "Один слиток золота окантовывает четыре блока мрамора."
        ),
                List.of(craft(new String[]{"mmm", "mgm", "mmm"},
                        keys("m", S(T + "marble"), "g", S(M + "gold_ingot")), T + "gold_trimmed_marble", 4))));

        return list;
    }
}
