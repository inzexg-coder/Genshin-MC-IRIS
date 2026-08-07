package net.teyvat.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.teyvat.TeyvatMod;

/**
 * Кастомная density-функция teyvat:x_edge — «удаление от центра пляжа по X».
 * Возвращает -1 у западного края пляжевой зоны и +1 у восточного.
 * Вместе с teyvat:z_edge задаёт форму пляжа: широкая бухта у моря (полоса
 * ~180 блоков), с трёх сторон окружённая полями с обрывами.
 *
 * Константы обязаны совпадать с data-паком:
 *  - teyvat_beach_weight.json (сплайн «веса пляжа» по x_edge)
 */
public final class TeyvatXEdge {
    public static final Identifier TYPE_ID = Identifier.of(TeyvatMod.MOD_ID, "x_edge");

    /** Полуширина зоны перехода пляж/поля: x_edge = ±1 на |x| = BEACH_HALF.
     *  У воды пляж сужается (вес умножается на z-фактор из teyvat_beach_weight.json). */
    public static final int BEACH_HALF = 115; // пляж широкий, форма — бухта, не выдается в море

    private static final DensityFunction INSTANCE = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double xe = pos.blockX() / (double) BEACH_HALF;
            return Math.max(-1.0, Math.min(1.0, xe));
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

    private TeyvatXEdge() {
    }

    /** Регистрирует тип density-функции, чтобы JSON вида {"type":"teyvat:x_edge"} декодировался. */
    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, TYPE_ID, MapCodec.unit(INSTANCE));
    }
}
