package net.teyvat.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.teyvat.TeyvatMod;

/**
 * Кольцо Драконьего хребта вокруг пляжа и широкая серпантинная тропа.
 * Все функции зависят только от X/Z и кэшируются в JSON как 2D.
 */
public final class TeyvatDragonRidge {
    public static final Identifier ZONE_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_zone_raw");
    public static final Identifier PATH_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_path_raw");
    public static final Identifier HEIGHT_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_height_raw");

    private static final int INNER_RADIUS = 72;
    private static final int OUTER_RADIUS = 235;
    private static final double SUMMIT_X = 0.0;
    private static final double SUMMIT_Z = -1165.0;

    /** Фиксированный вход на серпантин для структур и серверного поиска. */
    public static final int TRAILHEAD_X = 0;
    public static final int TRAILHEAD_Z = -1295;

    /** Центральная линия серпантина от границы пляжа к смотровой вершине. */
    private static final double[][] TRAIL = {
            {0, -1295},
            {-27, -1279},
            {-42, -1248},
            {-19, -1226},
            {23, -1217},
            {46, -1191},
            {21, -1169},
            {SUMMIT_X, SUMMIT_Z}
    };

    private static final DensityFunction ZONE = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double dx = pos.blockX();
            double dz = pos.blockZ() - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dx, dz);
            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(dx * 0.019 - dz * 0.016);

            double inner = smoothstep(INNER_RADIUS + 4, INNER_RADIUS + 26, warpedRadius);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 34, OUTER_RADIUS, warpedRadius);
            double land = smoothstep(18.0, 52.0, dz);
            double value = inner * outer * land;
            return Math.max(-1.0, Math.min(1.0, value * 2.0 - 1.0));
        }

        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(ZONE)); }
    };

    private static final DensityFunction PATH = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double x = pos.blockX();
            double z = pos.blockZ();
            double distance = trailDistance(x, z);
            double edge = (distance - 1.0) / 1.75;
            double mask = 1.0 - smooth01(edge);
            return Math.max(-1.0, Math.min(1.0, mask * 2.0 - 1.0));
        }

        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(PATH)); }
    };

    private static final DensityFunction HEIGHT = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double x = pos.blockX();
            double dz = pos.blockZ() - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(x * x + dz * dz);
            double angle = Math.atan2(x, dz);

            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(x * 0.019 - dz * 0.016);
            double inner = smoothstep(INNER_RADIUS + 4, INNER_RADIUS + 30, warpedRadius);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 38, OUTER_RADIUS, warpedRadius);
            double land = smoothstep(20.0, 55.0, dz);
            double envelope = inner * outer * land;
            if (envelope <= 0.001) {
                return 0.0;
            }

            double progress = Math.max(0.0, Math.min(1.0,
                    (radius - INNER_RADIUS) / (OUTER_RADIUS - INNER_RADIUS)));
            double climb = progress * 3.2;

            double waves = Math.sin(x * 0.036 + Math.sin(dz * 0.028) * 1.8)
                    * Math.sin(dz * 0.042 + Math.sin(x * 0.024) * 1.6);
            double ridged = 1.0 - Math.abs(waves);
            double hills = (ridged - 0.56) * 2.6;

            double summitDx = x - SUMMIT_X;
            double summitDz = pos.blockZ() - SUMMIT_Z;
            double summitDistanceSquared = summitDx * summitDx + summitDz * summitDz;
            double summit = Math.exp(-summitDistanceSquared / 900.0) * 1.15;

            double hillsHeight = envelope * (climb + hills + summit);

            double trailDistance = trailDistance(x, pos.blockZ());
            double trailBlend = 1.0 - smooth01((trailDistance - 2.0) / 5.0);
            double trailTarget = envelope * (climb + 0.06 + summit * 0.75);

            double plateauBlend = 1.0 - smooth01((Math.sqrt(summitDistanceSquared) - 9.0) / 13.0);
            double plateauTarget = envelope * (progress * 3.2 + 1.05);
            double blended = hillsHeight * (1.0 - trailBlend) + trailTarget * trailBlend;
            return blended * (1.0 - plateauBlend) + plateauTarget * plateauBlend;
        }

        @Override public double minValue() { return -1.5; }
        @Override public double maxValue() { return 5.5; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(HEIGHT)); }
    };

    private TeyvatDragonRidge() {}

    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, ZONE_ID, MapCodec.unit(ZONE));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, PATH_ID, MapCodec.unit(PATH));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, HEIGHT_ID, MapCodec.unit(HEIGHT));
    }

    private static double trailDistance(double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < TRAIL.length - 1; i++) {
            best = Math.min(best, segmentDistanceSquared(x, z, TRAIL[i], TRAIL[i + 1]));
        }
        return Math.sqrt(best);
    }

    private static double segmentDistanceSquared(double px, double pz, double[] a, double[] b) {
        double ax = a[0];
        double az = a[1];
        double bx = b[0];
        double bz = b[1];
        double abx = bx - ax;
        double abz = bz - az;
        double apx = px - ax;
        double apz = pz - az;
        double lengthSquared = abx * abx + abz * abz;
        double fraction = lengthSquared < 1.0E-8 ? 0.0 : clamp01((apx * abx + apz * abz) / lengthSquared);
        double cx = ax + abx * fraction - px;
        double cz = az + abz * fraction - pz;
        return cx * cx + cz * cz;
    }

    private static double smooth01(double value) {
        return smoothstep(0.0, 1.0, value);
    }

    private static double smoothstep(double from, double to, double value) {
        double fraction = clamp01((value - from) / (to - from));
        return fraction * fraction * (3.0 - 2.0 * fraction);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
