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

    /** Настройки Паймон. */
    public static class Paimon {
        /** Показывать Паймон после выбора путешественника. */
        public boolean enabled = true;
        /** Длительность знакомства после появления, тики. */
        public int intro_ticks = 300;
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
