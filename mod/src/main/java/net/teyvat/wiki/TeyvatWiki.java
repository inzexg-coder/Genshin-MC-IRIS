package net.teyvat.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.teyvat.quest.Quests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * «Энциклопедия путешественника» — каталог записей заметок. Общий для клиента
 * и сервера: клиент рисует страницы, сервер валидирует id открытий.
 *
 * Контент (тексты, названия, иконки) лежит в отдельном файле
 * mod/src/main/resources/teyvat_wiki.json — его можно править без кода.
 * Запись открывается при первой встрече (короткая версия), а после урока
 * Паймон дополняется полной. Разделы — как в Genshin:
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

    /** Запись вики. lesson — id квеста-урока Паймон, после которого запись
     *  становится полной; null — запись полная сразу после открытия. */
    public record Entry(String id, Section section, String title, String icon,
                        List<String> shortParas, List<String> fullParas, String lessonQuestId) {
        public boolean hasLesson() {
            return lessonQuestId != null;
        }
    }

    public static final String ID_WORLD_QUESTS = "world_quests";
    public static final String ID_WHEEL_ZOOM = "wheel_zoom";
    public static final String ID_KEY_C = "key_c";
    public static final String ID_SPRINT = "sprint";
    public static final String ID_EXP = "exp";
    public static final String ID_BEACH = "beach";
    public static final String ID_CELESTIA = "celestia";
    public static final String ID_HYDRO_SLIME = "hydro_slime";
    public static final String ID_HYDRO_PROJECTILE = "hydro_slime_projectile";
    public static final String ID_COMBAT_HITS = "combat_hits";
    public static final String ID_COMBAT_COMBO = "combat_combo";
    public static final String ID_COMBAT_CHARGED = "combat_charged";
    public static final String ID_DULL_BLADE = "dull_blade";
    public static final String ID_DASH = "dash";
    public static final String ID_FALL_DAMAGE = "fall_damage";
    public static final String ID_MORA = "mora";
    public static final String ID_SLIME_CONDENSATE = "slime_condensate";
    public static final String ID_SLIME_SECRETIONS = "slime_secretions";
    public static final String ID_SLIME_CONCENTRATE = "slime_concentrate";
    public static final String ID_PICKUP = "pickup";
    public static final String ID_TELEPORT = "teleport_waypoint";

    private static final Logger LOGGER = LoggerFactory.getLogger("teyvat-wiki");

    /** Записи в порядке файла — этот же порядок в списке вкладок. */
    private static List<Entry> ENTRIES = load();

    private TeyvatWiki() {}

    /** Читает teyvat_wiki.json из ресурсов мода. Ошибка файла не ломает игру —
     *  просто энциклопедия останется пустой (записи появятся после исправления). */
    private static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try (InputStream in = TeyvatWiki.class.getResourceAsStream("/teyvat_wiki.json")) {
            if (in == null) {
                LOGGER.error("teyvat_wiki.json не найден в ресурсах мода — энциклопедия пуста");
                return out;
            }
            // Файл правится вручную (через GitHub), поэтому прощаем висячие запятые
            // перед ] и } — иначе одна опечатка оставит энциклопедию пустой.
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll(",\\s*\\]", "]")
                    .replaceAll(",\\s*\\}", "}");
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id");
                Section section = sectionFor(str(o, "section"));
                String title = str(o, "title");
                if (id == null || section == null || title == null) {
                    continue;
                }
                String icon = str(o, "icon");
                String lesson = o.has("lesson") && !o.get("lesson").isJsonNull() ? o.get("lesson").getAsString() : null;
                out.add(new Entry(id, section, title, icon == null ? "circle" : icon,
                        strings(o, "short"), strings(o, "full"), lesson));
            }
        } catch (Exception e) {
            LOGGER.error("Не удалось прочитать teyvat_wiki.json: {}", e.toString());
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static Section sectionFor(String key) {
        if ("world".equals(key)) {
            return Section.WORLD;
        }
        if ("battle".equals(key)) {
            return Section.BATTLE;
        }
        if ("treasure".equals(key)) {
            return Section.TREASURE;
        }
        if ("adventure".equals(key)) {
            return Section.ADVENTURE;
        }
        return null;
    }

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
            return ID_COMBAT_HITS;
        }
        if (Quests.TRY_PICKUP.equals(questId)) {
            return ID_PICKUP;
        }
        return null;
    }

    /** Страница, которую открывает подбор предмета (по id предмета). */
    public static String pageForItem(String itemId) {
        if ("teyvat:dull_blade".equals(itemId)) {
            return ID_DULL_BLADE;
        }
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
