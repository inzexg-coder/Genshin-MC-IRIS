package net.teyvat.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.CodecHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.teyvat.TeyvatMod;

/**
 * Чистая реализация рельефа хребта вокруг пляжа.
 * Один аналитический HEIGHT даёт плавные холмы и естественную тропу.
 */
public final class TeyvatDragonRidge {
    public static final Identifier ZONE_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_zone_raw");
    public static final Identifier PATH_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_path_raw");
    public static final Identifier HEIGHT_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_height_raw");

    private static final int INNER_RADIUS = 72;
    private static final int OUTER_RADIUS = 235;
    private static final double SUMMIT_X = 0.0;
    private static final double SUMMIT_Z = -1165.0;

    public static final int TRAILHEAD_X = 0;
    public static final int TRAILHEAD_Z = -1295;

    private static final double TRAIL_START_RADIUS = 70.0;
    private static final double TRAIL_END_RADIUS = 200.0;
    private static final double TRAIL_HALF_WIDTH = 2.8;
    private static final int SPATIAL_CELL_SIZE = 32;

    private static final double[][] TRAIL_CURVE = buildTrailCurve();
    private static final Map<Long, int[]> TRAIL_SEGMENTS_BY_CELL =
            buildTrailSegmentIndex();

    /* ---- Zone (biome selection) ---- */
    private static final DensityFunction ZONE = new DensityFunction.Base() {
        @Override public double sample(NoisePos pos) {
            double dx = pos.blockX();
            double dz = pos.blockZ() - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dx, dz);
            double warped = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(dx * 0.019 - dz * 0.016);
            double inner = smoothstep(INNER_RADIUS + 4, INNER_RADIUS + 26, warped);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 34, OUTER_RADIUS, warped);
            double land = smoothstep(18.0, 52.0, dz);
            return clampD(-1.0, 1.0, inner * outer * land * 2.0 - 1.0);
        }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(ZONE));
        }
    };

    /* ---- Path (biome path mask) ---- */
    private static final DensityFunction PATH_FUNC = new DensityFunction.Base() {
        @Override public double sample(NoisePos pos) {
            double distance = trailDistance(pos.blockX(), pos.blockZ());
            return clampD(-1.0, 1.0, (1.0 - smoothstep(5.0, 10.0, distance)) * 2.0 - 1.0);
        }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(PATH_FUNC));
        }
    };

    /* ---- Height: один источник правды для всего рельефа ---- */
    private static final DensityFunction HEIGHT = new DensityFunction.Base() {
        @Override public double sample(NoisePos pos) {
            double x = pos.blockX();
            double z = pos.blockZ();
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(x * x + dz * dz);
            double angle = Math.atan2(x, dz);

            /* envelope: плавный переход от пляжа к холмам и обратно */
            double warped = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(x * 0.019 - dz * 0.016);
            double inner = smoothstep(INNER_RADIUS + 4, INNER_RADIUS + 30, warped);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 38, OUTER_RADIUS, warped);
            double land = smoothstep(20.0, 55.0, dz);
            double envelope = inner * outer * land;
            if (envelope < 0.001) return 0.0;

            /* progress: 0 на внутреннем краю, 1 на внешнем */
            double progress = clamp01((radius - INNER_RADIUS) / (OUTER_RADIUS - INNER_RADIUS));

            /* базовый подъём: плавное восхождение от края к вершине */
            double climb = progress * 1.8;

            /* холмы: две низкочастотные волны для естественного рельефа */
            double hills = envelope * (
                    Math.sin(x * 0.012 + Math.sin(dz * 0.007) * 1.0) * 0.45
                    + Math.sin(dz * 0.009 + Math.sin(x * 0.006) * 0.8) * 0.35
            );

            /* вершина: широкий плавный бугор */
            double summitDist = (x - SUMMIT_X) * (x - SUMMIT_X)
                    + (z - SUMMIT_Z) * (z - SUMMIT_Z);
            double summit = envelope * Math.exp(-summitDist / 2500.0) * 1.2;

            /* итоговая высота холмов без тропы */
            double ridgeHeight = envelope * (climb + 0.3) + hills + summit;

            /* тропа: плоский коридор с пологими склонами */
            double trailDist = trailDistance(x, z);
            double corridorBlend = smoothstep(TRAIL_HALF_WIDTH, TRAIL_HALF_WIDTH + 22.0, trailDist);
            double localRidgeAtTrail = envelope * (climb + 0.3)
                    + envelope * (Math.sin(x * 0.012 + Math.sin(dz * 0.007) * 1.0) * 0.45
                    + Math.sin(dz * 0.009 + Math.sin(x * 0.006) * 0.8) * 0.35)
                    + summit;
            double trailY = localRidgeAtTrail - 0.12 * envelope;

            /* финальное смешивание: внутри коридора — тропа, за пределами — холмы */
            double result = trailY * (1.0 - corridorBlend) + ridgeHeight * corridorBlend;
            return result;
        }
        @Override public double minValue() { return -1.5; }
        @Override public double maxValue() { return 5.5; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(HEIGHT));
        }
    };

    private TeyvatDragonRidge() {}

    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, ZONE_ID, MapCodec.unit(ZONE));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, PATH_ID, MapCodec.unit(PATH_FUNC));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, HEIGHT_ID, MapCodec.unit(HEIGHT));
    }

    public static boolean isTrailSurface(int x, int z) {
        return trailDistance(x, z) <= TRAIL_HALF_WIDTH;
    }

    public static double trailDistance(double x, double z) {
        int[] candidates = TRAIL_SEGMENTS_BY_CELL.get(cellKey((int)x, (int)z));
        if (candidates == null) return 999.0;
        double best = Double.MAX_VALUE;
        for (int i : candidates) {
            best = Math.min(best, segmentDistanceSquared(x, z,
                    TRAIL_CURVE[i], TRAIL_CURVE[i + 1]));
        }
        return Math.sqrt(best);
    }

    public static boolean chunkMayContainTrail(int minX, int minZ, int maxX, int maxZ) {
        for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
            double[] a = TRAIL_CURVE[i];
            double[] b = TRAIL_CURVE[i + 1];
            if (Math.max(a[0], b[0]) + 3.0 >= minX
                    && Math.min(a[0], b[0]) - 3.0 <= maxX
                    && Math.max(a[1], b[1]) + 3.0 >= minZ
                    && Math.min(a[1], b[1]) - 3.0 <= maxZ) {
                return true;
            }
        }
        return false;
    }

    /* ---- trail curve ---- */
    private static double[][] buildTrailCurve() {
        double[][] p = new double[257][];
        for (int i = 0; i < p.length; i++) {
            double t = i / (double) (p.length - 1);
            double eased = t * t * (3.0 - 2.0 * t);
            double r = TRAIL_START_RADIUS + (TRAIL_END_RADIUS - TRAIL_START_RADIUS) * eased;
            double a = 0.35 * Math.sin(3.0 * Math.PI * t);
            p[i] = new double[]{r * Math.sin(a),
                    TeyvatOceanEdge.BEACH_CENTER_Z + r * Math.cos(a)};
        }
        return p;
    }

    /* ---- spatial index ---- */
    private static Map<Long, int[]> buildTrailSegmentIndex() {
        Map<Long, List<Integer>> map = new HashMap<>();
        for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
            double[] a = TRAIL_CURVE[i];
            double[] b = TRAIL_CURVE[i + 1];
            int x0 = cellCoord(Math.min(a[0], b[0]) - 3.0);
            int x1 = cellCoord(Math.max(a[0], b[0]) + 3.0);
            int z0 = cellCoord(Math.min(a[1], b[1]) - 3.0);
            int z1 = cellCoord(Math.max(a[1], b[1]) + 3.0);
            for (int cx = x0 - 1; cx <= x1 + 1; cx++) {
                for (int cz = z0 - 1; cz <= z1 + 1; cz++) {
                    map.computeIfAbsent(cellKey(cx * SPATIAL_CELL_SIZE,
                            cz * SPATIAL_CELL_SIZE), k -> new ArrayList<>()).add(i);
                }
            }
        }
        Map<Long, int[]> result = new HashMap<>();
        map.forEach((k, v) -> result.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return Map.copyOf(result);
    }

    private static int cellCoord(double v) {
        return Math.floorDiv((int) Math.floor(v), SPATIAL_CELL_SIZE);
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /* ---- geometry helpers ---- */
    private static double segmentDistanceSquared(double px, double pz, double[] a, double[] b) {
        double abx = b[0] - a[0];
        double abz = b[1] - a[1];
        double apx = px - a[0];
        double apz = pz - a[1];
        double len2 = abx * abx + abz * abz;
        double t = len2 < 1.0E-8 ? 0.0 : clamp01((apx * abx + apz * abz) / len2);
        double dx = a[0] + abx * t - px;
        double dz = a[1] + abz * t - pz;
        return dx * dx + dz * dz;
    }

    private static double smoothstep(double from, double to, double value) {
        double f = clamp01((value - from) / (to - from));
        return f * f * (3.0 - 2.0 * f);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clampD(double min, double max, double v) {
        return Math.max(min, Math.min(max, v));
    }
}
