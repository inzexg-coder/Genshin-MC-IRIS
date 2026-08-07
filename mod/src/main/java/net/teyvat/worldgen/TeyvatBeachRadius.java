package net.teyvat.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.teyvat.TeyvatMod;

/**
 * Кастомная density-функция teyvat:beach_radius — расстояние от центра
 * пляжа-полукруга (урез воды), нормированное на радиус пляжа.
 * Возвращает 0 в центре (x = 0, z = урез воды) и растёт до 1 на границе
 * полукруга; дальше обрезается на 2. Вместе с teyvat:z_edge/teyvat:x_edge
 * задаёт форму пляжа: полукруг, врезанный в сушу, окружённый травой.
 *
 * Константы обязаны совпадать с data-паком:
 *  - teyvat_beach_zone.json / teyvat_beach_weight.json (радиальные зона и вес)
 */
public final class TeyvatBeachRadius {
    public static final Identifier TYPE_ID = Identifier.of(TeyvatMod.MOD_ID, "beach_radius");

    private static final DensityFunction INSTANCE = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double dx = pos.blockX();
            double dz = pos.blockZ() - TeyvatOceanEdge.BEACH_CENTER_Z;
            double r = Math.sqrt(dx * dx + dz * dz) / TeyvatOceanEdge.BEACH_RADIUS;
            return Math.max(0.0, Math.min(2.0, r));
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 2.0;
        }

        @Override
        public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(INSTANCE));
        }
    };

    private TeyvatBeachRadius() {
    }

    /** Регистрирует тип density-функции, чтобы JSON вида {"type":"teyvat:beach_radius"} декодировался. */
    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, TYPE_ID, MapCodec.unit(INSTANCE));
    }
}
