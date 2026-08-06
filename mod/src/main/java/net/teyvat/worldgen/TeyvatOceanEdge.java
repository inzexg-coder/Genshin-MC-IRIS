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
 * Возвращает -1 у дна моря и +1 на суше пляжа. Зависит только от blockZ,
 * поэтому безопасно кэшируется как 2D-функция.
 *
 * Мир: один большой пляж, а с севера — море до границы карты.
 *   - северный край карты (мировой бордер, z = -2000) — дно моря;
 *   - пологий спуск длиной SEA_WIDTH*2 от дна (z = -1690) до пляжа (z = -1510);
 *   - южнее — ровный пляж (поверхность y = 65-66, песок).
 *
 * Константы обязаны совпадать с data-паком:
 *  - teyvat_beach_height.json (формула рельефа: h = 0.575 * (g - 0.565) + шум)
 *  - TeyvatSpawn (поиск точки спавна у кромки воды)
 */
public final class TeyvatOceanEdge {
    public static final Identifier TYPE_ID = Identifier.of(TeyvatMod.MOD_ID, "z_edge");

    /** Сторона мирового бордера (центр 0,0): края карты на ±BORDER_SIZE/2. Северный край z = -2000 — дно моря. */
    public static final int BORDER_SIZE = 4000;
    /** Центр пологого спуска к морю (g = 0 на этой широте). */
    public static final int SHORE_Z = -1600;
    /** Полуширина спуска: от дна моря (z = -1690) до пляжа (z = -1510). */
    public static final int SEA_WIDTH = 90;
    /** Урез воды: рельеф пересекает уровень моря при h = 0, то есть при g = 0.565. */
    public static final int WATERLINE_Z = SHORE_Z + (int) Math.round(SEA_WIDTH * 0.565);

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
