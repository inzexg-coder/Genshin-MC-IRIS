package net.teyvat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public Spawn spawn = new Spawn();
    /** Телепортировать новых игроков (без кровати) на пляж при входе. */
    public boolean teleport_new_players = true;

    /** Паймон-компаньон. */
    public Paimon paimon = new Paimon();

    /** Зум по кнопке (клавиша C по умолчанию). */
    public Zoom zoom = new Zoom();

    /** Teyvat Camera — кастомная камера от 3-го лица в стиле Genshin. */
    public Camera camera = new Camera();

    /** Настройки зума. */
    public static class Zoom {
        /** Множитель FOV при полном зуме: меньше = сильнее (0.2 = пятикратный зум). */
        public float fov = 0.2f;
    }

    /** Настройки Teyvat Camera. */
    public static class Camera {
        /** Включить кастомную камеру в 3-м лице (вид сзади). */
        public boolean enabled = true;
        /** Базовая дистанция камеры от героя (блоки). */
        public float distance = 4.0f;
        public float min_distance = 1.5f;
        public float max_distance = 10.0f;
        /** Плечевое смещение: вбок (положительное — направо от героя) и вверх. */
        public float side = 0.6f;
        public float up = 0.3f;
        /** Плавность догоняния позиции (1–30, больше = плавнее). */
        public float smoothness = 8.0f;
        /** Плавность возврата орбиты к герою после свободной камеры (1–30). */
        public float return_smoothness = 10.0f;
        /** Умная коллизия: камера не вжимается в блоки. */
        public boolean collision = true;
        /** Колесико мыши меняет дистанцию камеры в 3-м лице. */
        public boolean scroll_controls_distance = true;
        /** Чувствительность колесика (блоки за один щелчок). */
        public float scroll_sensitivity = 0.8f;
        /** Режим свободной камеры: disabled / hold (удержание) / toggle (переключатель). */
        public String free_look_mode = "hold";
    }

    /** Настройки Паймон. */
    public static class Paimon {
        /** Показывать Паймон после выбора путешественника. */
        public boolean enabled = true;
        /** Длительность знакомства после появления, тики. */
        public int intro_ticks = 600;
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
                    if (cfg.zoom == null) {
                        cfg.zoom = new Zoom();
                    }
                    if (cfg.camera == null) {
                        cfg.camera = new Camera();
                    }
                }
            } catch (Exception ignored) {
                // повреждённый конфиг — используем значения по умолчанию
            }
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cfg));
        } catch (IOException ignored) {
        }
        return cfg;
    }
}
