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
    public static final int TRAILHEAD_Z = -1280;

    private static final double START_RADIUS = 55.0;
    private static final double END_RADIUS = 220.0;
    private static final int SPATIAL_CELL_SIZE = 32;
    private static final double TRAIL_CLIMB = 1.5;
    // HILLS_AMPLITUDE moved to JSON (shifted_noise)
    private static final double TRAIL_HALF_WIDTH = 2.5;
    private static final double SHOULDER_AMPLITUDE = 0.25;

    /** Плавная центральная линия серпантина в полярных координатах кольца. */
    private static final double[][] TRAIL_CURVE = buildTrailCurve();
    private static final Map<Long, int[]> TRAIL_SEGMENTS_BY_CELL =
            buildTrailSegmentIndex();

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
            double edge = (distance - 5.0) / 5.0;
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
            double z = pos.blockZ();
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(x * x + dz * dz);
            double angle = Math.atan2(x, dz);

            // Огибающая кольца: плавный подъём от пляжа, спуск за кольцом
            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(x * 0.019 - dz * 0.016);
            // Плавный подъём от края пляжа (inner) и затухание за пределами кольца (outer)
            double inner = smoothstep(-20.0, 120.0, warpedRadius);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 85, OUTER_RADIUS + 85, warpedRadius);
            double land = smoothstep(5.0, 100.0, dz);
            double envelope = inner * outer * land;
            if (envelope <= 0.001) return 0.0;

            // Расстояние от центра тропы (одинаково для левой и правой стороны)
            double dist = trailDistance(x, z);

            // Сглаживание: усредняем hill-профиль по 5x5 блокам вокруг,
            // чтобы убрать резкие ступеньки на склонах.
            double hillSum = 0.0;
            for (int kx = -2; kx <= 2; kx++) {
                for (int kz = -2; kz <= 2; kz++) {
                    double nd = trailDistance(x + kx, z + kz);
                    hillSum += smoothHillProfile(nd);
                }
            }
            double hill = hillSum / 25.0;

            return envelope * hill;
        }

        @Override public double minValue() { return -1.5; }
        @Override public double maxValue() { return 7.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(HEIGHT)); }
    };

    /** Публичное расстояние от точки до тропы (для поиска точки телепортации). */
    public static double trailDistancePublic(double x, double z) {
        return trailDistance(x, z);
    }

    private TeyvatDragonRidge() {}

    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, ZONE_ID, MapCodec.unit(ZONE));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, PATH_ID, MapCodec.unit(PATH));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, HEIGHT_ID, MapCodec.unit(HEIGHT));
    }

    static boolean isTrailSurface(int x, int z) {
        double dist = trailDistance(x, z);
        if (dist <= TRAIL_HALF_WIDTH + 2.0) return true;
        // Площадка вокруг точки телепортации (radius 6 блоков)
        double tdx = x - TRAILHEAD_X;
        double tdz = z - TRAILHEAD_Z;
        return Math.sqrt(tdx * tdx + tdz * tdz) <= TRAIL_HALF_WIDTH + 0.5;
    }

    /** 1.0 = центр тропы, 0.0 = край, -1.0 = за пределами. */
    static double trailFadeFactor(int x, int z) {
        double dist = trailDistance(x, z);
        return 1.0 - dist / (TRAIL_HALF_WIDTH + 2.0);
    }

    /**
     * Профиль холма по расстоянию от тропы.
     * Raised cosine: C∞-гладкая, f'=0 на обоих концах (тропа и дальний край).
     * Вершина = 1.0 на dist=85, затухает до 0 на dist=0 и dist=170.
     * Максимально плавный подъём без резких стыков.
     */
    private static final double HILL_PEAK_DIST = 85.0;
    private static final double HILL_END_DIST = 170.0;
    private static final double HILL_AMPLITUDE = 2.0;

    private static double smoothHillProfile(double dist) {
        if (dist <= 0.0 || dist >= HILL_END_DIST) return 0.0;
        // Подъём: raised cosine от 0 до PEAK_DIST
        double riseT = dist / HILL_PEAK_DIST;
        double rise = 0.5 - 0.5 * Math.cos(riseT * Math.PI);
        // Спуск: raised cosine от PEAK_DIST до END_DIST
        double fallT = (dist - HILL_PEAK_DIST) / (HILL_END_DIST - HILL_PEAK_DIST);
        double fall = 0.5 + 0.5 * Math.cos(fallT * Math.PI);
        return HILL_AMPLITUDE * Math.min(rise, fall);
    }

    private static double trailDistance(double x, double z) {
        double bestSquared = Double.MAX_VALUE;
        for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
            bestSquared = Math.min(bestSquared, segmentDistanceSquared(x, z,
                    TRAIL_CURVE[i], TRAIL_CURVE[i + 1]));
        }
        return Math.sqrt(bestSquared);
    }

    public static boolean chunkMayContainTrail(int minX, int minZ, int maxX, int maxZ) {
        double margin = TRAIL_HALF_WIDTH + 1.0;
        for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
            double[] start = TRAIL_CURVE[i];
            double[] end = TRAIL_CURVE[i + 1];
            if (Math.max(start[0], end[0]) + margin >= minX
                    && Math.min(start[0], end[0]) - margin <= maxX
                    && Math.max(start[1], end[1]) + margin >= minZ
                    && Math.min(start[1], end[1]) - margin <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private static Map<Long, int[]> buildTrailSegmentIndex() {
        Map<Long, List<Integer>> segmentsByCell = new HashMap<>();
        for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
            double[] start = TRAIL_CURVE[i];
            double[] end = TRAIL_CURVE[i + 1];
            int minCellX = spatialCellCoordinate(Math.min(start[0], end[0]) - TRAIL_HALF_WIDTH);
            int maxCellX = spatialCellCoordinate(Math.max(start[0], end[0]) + TRAIL_HALF_WIDTH);
            int minCellZ = spatialCellCoordinate(Math.min(start[1], end[1]) - TRAIL_HALF_WIDTH);
            int maxCellZ = spatialCellCoordinate(Math.max(start[1], end[1]) + TRAIL_HALF_WIDTH);

            for (int cellX = minCellX - 1; cellX <= maxCellX + 1; cellX++) {
                for (int cellZ = minCellZ - 1; cellZ <= maxCellZ + 1; cellZ++) {
                    segmentsByCell.computeIfAbsent(
                            spatialCellKey(cellX * SPATIAL_CELL_SIZE, cellZ * SPATIAL_CELL_SIZE),
                            key -> new ArrayList<>()).add(i);
                }
            }
        }

        Map<Long, int[]> result = new HashMap<>();
        segmentsByCell.forEach((key, segments) -> result.put(key,
                segments.stream().mapToInt(Integer::intValue).toArray()));
        return Map.copyOf(result);
    }

    private static int spatialCellCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), SPATIAL_CELL_SIZE);
    }

    private static long spatialCellKey(double x, double z) {
        return ((long) spatialCellCoordinate(x) << 32)
                | (spatialCellCoordinate(z) & 0xFFFFFFFFL);
    }

    private static double[][] buildTrailCurve() {
        // Полностью прямая тропа через телепорт, лёгкий серпантин только вдали
        double teleportRadius = 85.0;
        double[][] points = new double[257][];
        for (int i = 0; i < points.length; i++) {
            double progress = i / (double) (points.length - 1);
            double radius = START_RADIUS + (END_RADIUS - START_RADIUS) * progress;
            // Кривизна: 0 рядом с телепортом, нарастает дальше
            double distFromTeleport = Math.abs(radius - teleportRadius);
            double curveFactor = smoothstep(20.0, 80.0, distFromTeleport);
            double angle = 0.18 * curveFactor * Math.sin(2.5 * Math.PI * progress);
            points[i] = new double[] {
                    radius * Math.sin(angle),
                    TeyvatOceanEdge.BEACH_CENTER_Z + radius * Math.cos(angle)
            };
        }
        return points;
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
