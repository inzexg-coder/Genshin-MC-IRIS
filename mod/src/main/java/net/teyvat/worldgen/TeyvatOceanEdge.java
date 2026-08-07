package net.teyvat.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.teyvat.TeyvatMod;

/**
 * Кастомная density-функция teyvat:z_edge — «широта относительно моря».
 * Возвращает -1 у северного края мира и +1 на суше. Зависит только от blockZ,
 * поэтому безопасно кэшируется как 2D-функция.
 *
 * Мир: море с севера, пляж — полукругом врезан в сушу, вокруг него поля.
 *   - северный край карты (мировой бордер, z = -2000) — дно моря (10 блоков воды);
 *   - урез воды пляжа около z = -1365 — плоская сторона полукруга;
 *   - пляж лежит в низине: полукруг песка, окружённый травой с пологими
 *     подъёмами по бокам и сзади, без обрывов.
 *
 * Константы обязаны совпадать с data-паком:
 *  - teyvat_beach_profile.json / teyvat_cliff_profile.json (сплайны по g)
 *  - TeyvatSpawn (поиск точки спавна у кромки воды)
 */
public final class TeyvatOceanEdge {
    public static final Identifier TYPE_ID = Identifier.of(TeyvatMod.MOD_ID, "z_edge");

    /** Сторона мирового бордера (центр 0,0): края карты на ±BORDER_SIZE/2. Северный край z = -2000 — дно моря. */
    public static final int BORDER_SIZE = 4000;
    /** Центр спуска: g = 0 на этой широте (середина между дном моря и окраинами). */
    public static final int SHORE_Z = -1565;
    /** Полуширина профиля: g = -1 на z = -2000 (дно моря), g = +1 на z = -1130 (край пляжа). */
    public static final int SEA_WIDTH = 435;
    /** Номинальный урез воды: профиль пляжа пересекает уровень моря при g = 0.46
     *  (реально извивается шумом) — плоская сторона пляжа-полукруга. */
    public static final int WATERLINE_Z = SHORE_Z + (int) Math.round(SEA_WIDTH * 0.46);

    /** Центр пляжа-полукруга: лежит на урезе воды (x = 0, z = WATERLINE_Z). */
    public static final int BEACH_CENTER_Z = WATERLINE_Z;

    /** Радиус пляжа-полукруга в блоках (пляж ~124 блока в ширину и ~62 вглубь). */
    public static final int BEACH_RADIUS = 62;

    private static final DensityFunction INSTANCE = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double g = (pos.blockZ() - SHORE_Z) / (double) SEA_WIDTH;
            return Math.max(-1.0, Math.min(1.0, g));
        }

        @Override
        public double minValue() {
            return -1.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(INSTANCE));
        }
    };

    private TeyvatOceanEdge() {
    }

    /** Регистрирует тип density-функции, чтобы JSON вида {"type":"teyvat:z_edge"} декодировался. */
    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, TYPE_ID, MapCodec.unit(INSTANCE));
    }
}
