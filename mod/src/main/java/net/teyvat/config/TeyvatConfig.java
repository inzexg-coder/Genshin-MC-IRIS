package net.teyvat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Конфиг мода: config/teyvat.json (создаётся автоматически при первом запуске).
 * Настройки спавна игроков на пляже.
 */
public final class TeyvatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Настройки спавна на пляже. */
    public static class Spawn {
        /** Точка-якорь: вокруг неё ищется пляж (обычно центр мира / будущий город). */
        public int anchor_x = 0;
        public int anchor_z = 0;
        /** Радиус поиска пляжа вокруг якоря (блоки). */
        public int search_radius = 200;
        /** Использовать точную фиксированную точку вместо поиска. */
        public boolean use_fixed_position = false;
        public int fixed_x = 0;
        /** -1 = подобрать верхнюю точку поверхности автоматически. */
        public int fixed_y = -1;
        public int fixed_z = 0;
        /** Угол поворота игрока при спавне, градусы. -1 = автоматически (от воды к суше). */
        public float yaw = -1.0f;
    }

    /** Версия конфига: при повышении мигрирауются значения в load(). */
    public int config_version = 3;

    public Spawn spawn = new Spawn();
    /** Телепортировать новых игроков (без кровати) на пляж при входе. */
    public boolean teleport_new_players = true;

    /** Паймон-компаньон. */
    public Paimon paimon = new Paimon();

    /** Здоровье как в Genshin: 912 HP, реген только во сне, урон от падения по высоте. */
    public Health health = new Health();

    /** Зум по кнопке (клавиша C по умолчанию). */
    public Zoom zoom = new Zoom();

    /** Teyvat Camera — кастомная камера от 3-го лица в стиле Genshin. */
    public Camera camera = new Camera();

    /** Прогрессия как в Genshin: Ранг Приключений, уровни персонажей, анти-фарм. */
    public Progression progression = new Progression();

    /** Уровни мобов: растут с расстоянием от спавна мира. */
    public MobLevels mob_levels = new MobLevels();

    /** Ограничения мира на тестовый период: без ломания блоков, без атак
     *  мирных мобов, без спавна монстров. */
    public World world = new World();

    /** Настройки зума. */
    public static class Zoom {
        /** Множитель FOV при полном зуме: меньше = сильнее (0.2 = пятикратный зум). */
        public float fov = 0.2f;
    }

    /** Настройки Teyvat Camera. */
    public static class Camera {
        /** Включить кастомную камеру в 3-м лице (вид сзади). */
        public boolean enabled = false;
        /** Базовая дистанция камеры от героя (блоки). */
        public float distance = 4.0f;
        public float min_distance = 1.5f;
        public float max_distance = 10.0f;
        /** Плечевое смещение: вбок (положительное — направо от героя) и вверх. */
        public float side = 0.6f;
        public float up = 0.3f;
        /** Плавность догоняния позиции (1–30, больше = плавнее). */
        public float smoothness = 8.0f;
        /** Скорость плавного выезда камеры из первого лица в третье (0.5–10, меньше = медленнее). */
        public float blend_smoothness = 1.2f;
        /** Плавность возврата орбиты к герою после свободной камеры (1–30). */
        public float return_smoothness = 10.0f;
        /** Умная коллизия: камера не вжимается в блоки. */
        public boolean collision = true;
        /** Плавность подъезда камеры к стене при коллизии (1–30, больше = быстрее). */
        public float collision_smoothness = 6.0f;
        /** Колесико мыши меняет дистанцию камеры в 3-м лице. */
        public boolean scroll_controls_distance = true;
        /** Чувствительность колесика (блоки за один щелчок). */
        public float scroll_sensitivity = 0.8f;
        /** Режим свободной камеры: disabled / hold (удержание) / toggle (переключатель). */
        public String free_look_mode = "hold";
        /** Первое лицо: рисовать собственное тело и все анимации персонажа
         *  («глазами модельки»). Выключи, чтобы вернуть ванильный пустой вид. */
        public boolean first_person_body = true;
    }

    /** Настройки здоровья как в Genshin. */
    public static class Health {
        /** Максимальное здоровье героя: как у путешественника в Genshin. */
        public float max_health = 912f;
        /** Высота падения, с которой начинается урон (блоки, ~7–8 м безопасны). */
        public float fall_damage_threshold = 7f;
        /** Урон за каждый блок выше порога (очков HP). */
        public float fall_damage_per_block = 35f;
        /** Показывать полоски HP над противниками. */
        public boolean show_mob_bars = true;
        /** Показывать числа урона при атаке противников. */
        public boolean show_damage_numbers = true;
    }

    /** Настройки Паймон. */
    public static class Paimon {
        /** Показывать Паймон после выбора путешественника. */
        public boolean enabled = true;
        /** Длительность знакомства после появления, тики. */
        public int intro_ticks = 600;
        /** После монолога Паймон плавно перевести камеру из первого лица в третье. */
        public boolean third_person_after_intro = true;
    }

    /** Прогрессия игрока: Ранг Приключений, опыт, ростера персонажей. */
    public static class Progression {
        /** Потолок Ранга Приключений. */
        public int max_ar = 60;
        /** Потолок уровня персонажа. */
        public int max_char_level = 90;
        /** Опыт на следующий ранг: expNeed(ar) = round(base * ar^power) — с каждым уровнем тяжелее. */
        public float ar_exp_base = 100f;
        public float ar_exp_power = 1.6f;
        /** Опыт на следующий уровень персонажа: expNeed(lvl) = round(base * lvl^power). */
        public float char_exp_base = 200f;
        public float char_exp_power = 1.5f;
        /** Анти-фарм: первые N убийств одного типа моба за сессию дают полный опыт. */
        public boolean antifarm_enabled = true;
        public int antifarm_first_kills = 10;
        /** На сколько падает множитель опыта за каждое убийство сверх лимита. */
        public float antifarm_decay_per_kill = 0.1f;
        /** Ниже этого множителя опыт не опускается. */
        public float antifarm_min_multiplier = 0.1f;
        /** Базовый опыт за убийство (если тип не задан в mob_xp). */
        public int mob_xp_default = 5;
        /** Опыт по типам мобов: "minecraft:zombie": 8 (каждого пропишем позже). */
        public Map<String, Integer> mob_xp = new HashMap<>();
    }

    /** Уровни мобов: назначаются при спавне, растут от расстояния до спавна мира. */
    public static class MobLevels {
        /** Включает назначение уровней мобам (отключить для тестов). */
        public boolean enabled = true;
        /** Базовый уровень моба у спавна мира. */
        public int base = 1;
        /** Прирост уровня за блок расстояния от спавна. */
        public double per_block = 0.002;
        /** Потолок уровня моба. */
        public int cap = 90;
    }

    /** Ограничения мира (временные, для тестов). */
    public static class World {
        /** Запретить игрокам ломать блоки вообще. */
        public boolean no_block_breaking = false;
        /** Запретить игрокам атаковать мирных мобов. */
        public boolean no_attack_peaceful = true;
        /** Спавнить только мирных мобов (без враждебных и нейтральных). */
        public boolean only_peaceful_spawns = true;
    }

    /** Обучение: игрок не выпускается с пляжа, пока не пройдены все задания. */
    public Tutorial tutorial = new Tutorial();

    public static class Tutorial {
        /** Не выпускать игрока за пределы пляжа до конца обучения. */
        public boolean lock_beach = true;
        /** Радиус (блоки) вокруг точки спавна, за который нельзя выходить. */
        public double beach_radius = 75.0;
        /** Сообщение при попытке выйти за границу. */
        public String message = "Твоё путешествие начнётся, когда обучение будет пройдено.";
        /** Как часто показывать сообщение (тики). */
        public int message_cooldown_ticks = 100;
    }

    private static TeyvatConfig instance;

    public static TeyvatConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static TeyvatConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("teyvat.json");
        TeyvatConfig cfg = new TeyvatConfig();
        if (Files.exists(path)) {
            try {
                TeyvatConfig parsed = GSON.fromJson(Files.readString(path), TeyvatConfig.class);
                if (parsed != null) {
                    cfg = parsed;
                    if (cfg.spawn == null) {
                        cfg.spawn = new Spawn();
                    }
                    if (cfg.paimon == null) {
                        cfg.paimon = new Paimon();
                    }
                    if (cfg.health == null) {
                        cfg.health = new Health();
                    }
                    if (cfg.zoom == null) {
                        cfg.zoom = new Zoom();
                    }
                    if (cfg.camera == null) {
                        cfg.camera = new Camera();
                    }
                    if (cfg.progression == null) {
                        cfg.progression = new Progression();
                    }
                    if (cfg.mob_levels == null) {
                        cfg.mob_levels = new MobLevels();
                    }
                    if (cfg.world == null) {
                        cfg.world = new World();
                    }
                    if (cfg.tutorial == null) {
                        cfg.tutorial = new Tutorial();
                    }
                }
            } catch (Exception ignored) {
                // повреждённый конфиг — используем значения по умолчанию
            }

        }
        // Версия 2: пользователь вернул возможность ломать блоки — снимаем
        // тестовое ограничение независимо от сохранённого значения.
        if (cfg.config_version < 2) {
            cfg.world.no_block_breaking = false;
            cfg.config_version = 2;
        }
        // Версия 3: возвращён вид от первого лица — кастомная камера отключена.
        if (cfg.config_version < 3) {
            cfg.camera.enabled = false;
            cfg.config_version = 3;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cfg));
        } catch (IOException ignored) {
        }
        return cfg;
    }
}
