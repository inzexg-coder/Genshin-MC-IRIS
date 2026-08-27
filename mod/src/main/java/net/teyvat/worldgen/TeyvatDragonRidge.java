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
    public static final Identifier FLOOR_ID = Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_floor_raw");

    private static final int INNER_RADIUS = 72;
    private static final int OUTER_RADIUS = 235;
    private static final double SUMMIT_X = 0.0;
    private static final double SUMMIT_Z = -1165.0;

    /** Фиксированный вход на серпантин для структур и серверного поиска. */
    public static final int TRAILHEAD_X = 0;
    public static final int TRAILHEAD_Z = -1270;

    /** Единая абсолютная высота всей тропы (в блоках). Тропа жёстко ровняется
     *  на это значение во всех чанках, чтобы ни один блок не был выше/ниже.
     *  Совпадает с плоским плато из density-функций (см. dragon_ridge_floor_raw). */
    public static final int TRAIL_Y = 72;

    private static final double START_RADIUS = 95.0;
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

            // Тропа начинается только в долине (radius >= 95), без выхода на пляж.
            // Постоянная ширина 5 блоков, переход плавный по краю долины.
            double pdz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double pradius = Math.sqrt(x * x + pdz * pdz);
            double edgeIn = smoothstep(90.0, 110.0, pradius); // 0 у пляжа, 1 в долине

            double halfWidth = 5.0 * edgeIn;
            if (halfWidth < 0.5) return -1.0; // ещё не в долине — тропы нет
            double edge = (distance - halfWidth) / halfWidth;
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
            // Быстрый выход: за пределами кольца — 0 без вычислений
            if (x < -240 || x > 240 || z < -1600 || z > -1040) return 0.0;
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(x * x + dz * dz);
            double angle = Math.atan2(x, dz);

            // Внешнее затухание: только на дальних краях кольца
            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(x * 0.019 - dz * 0.016);

            // Расстояние от центра тропы
            double dist = trailDistance(x, z);

            // Кольцо холмов: снимаем у самого пляжа (radius < 68) и на внешнем крае.
            // Внутри кольца холмы стоят по ОБЕ стороны тропы — никакого radius-гейта
            // у самой тропы, который ранее срезал внутреннюю сторону.
            double ringIn = smoothstep(68.0, 84.0, warpedRadius);
            double ringOut = 1.0 - smoothstep(OUTER_RADIUS - 30, OUTER_RADIUS + 50, warpedRadius);
            double ring = ringIn * ringOut;

            // Дно долины: песчаный подъём от пляжа к тропе (radius 60..95),
            // дальше ровное плато под тропой (floorBand=1 → plate = константа).
            double floorBand = 1.0 - smooth01(dist / 12.0);
            double plate = TRAIL_FLOOR_AMP * smoothstep(60.0, 95.0, warpedRadius) * floorBand;

            // Холмы: колокол по dist вокруг тропы — 0 на тропе, пик у ~dist 40-60.
            double hill = linearHillProfile(dist);

            return ring * (plate + hill);
        }

        @Override public double minValue() { return -1.5; }
        @Override public double maxValue() { return 7.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(HEIGHT)); }
    };

    /** Плоское дно долины под тропой: 1.0 внутри всего кольца хребта, 0 на пляже
     *  и за внешним краем. Умножается на teyvat_beach_height, чтобы «вырезать»
     *  естественный рельеф (обрыв пляжа и шум) внутри кольца — тогда дно долины
     *  идеально ровное и вся тропа лежит строго на одной высоте TRAIL_Y,
     *  а у внешнего края плавно сливается с равнинами. */
    private static final DensityFunction FLOOR = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double x = pos.blockX();
            double z = pos.blockZ();
            if (x < -240 || x > 240 || z < -1600 || z > -1040) return 0.0;
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(x * x + dz * dz);
            double angle = Math.atan2(x, dz);
            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(x * 0.019 - dz * 0.016);
            // От края пляжа (62) до внешнего края хребта — плато без естественного рельефа.
            // Пляж (radius < 62) и равнины (radius >= 285) сохраняют обычный рельеф.
            double inner = smoothstep(62.0, 92.0, warpedRadius);
            double outer = 1.0 - smoothstep(205.0, 285.0, warpedRadius);
            return inner * outer;
        }

        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(FLOOR)); }
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
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, FLOOR_ID, MapCodec.unit(FLOOR));
    }

    static boolean isTrailSurface(int x, int z) {
        double dist = trailDistance(x, z);
        if (dist <= TRAIL_HALF_WIDTH + 2.0) return true;
        // Площадка вокруг точки телепортации (radius 6 блоков)
        double tdx = x - TRAILHEAD_X;
        double tdz = z - TRAILHEAD_Z;
        return Math.sqrt(tdx * tdx + tdz * tdz) <= TRAIL_HALF_WIDTH + 0.5;
    }

    /** 1.0 = центр тропы, 0.0 = край, -1.0 = за пределами.
     *  У пляжа (радиус < 75) тропа плавно растекается веером:
     *  ширина растёт в 2.5 раза к выходу на песок. */
    static double trailFadeFactor(int x, int z) {
        double dist = trailDistance(x, z);
        double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
        double radius = Math.sqrt(x * x + dz * dz);
        // Тропа рисуется только в долине (radius >= 95), без спуска на пляж
        double edgeIn = smoothstep(92.0, 112.0, radius);
        double halfWidth = (TRAIL_HALF_WIDTH + 2.0) * edgeIn;
        if (halfWidth < 0.5) return -1.0;
        return 1.0 - dist / halfWidth;
    }

    /**
     * Линейный профиль холма с плавным пиком.
     * Подъём: линейный от dist=0 до dist=70 (постоянный уклон).
     * Пик: smoothstep-crest от dist=70 до dist=100 (плавный переход).
     * Спуск: линейный от dist=100 до dist=170.
     */
    private static final double HILL_END_DIST = 170.0;
    private static final double HILL_AMPLITUDE = 0.85;
    private static final double TRAIL_FLOOR_AMP = 0.1;

    /** Плавный колокол: 0 у тропы, пик в середине, 0 на HILL_END_DIST.
     *  C∞-гладкий — никаких плоских вершин и вертикальных стен. */
    private static double linearHillProfile(double dist) {
        if (dist <= 0.0 || dist >= HILL_END_DIST) return 0.0;
        double s = clamp01(dist / HILL_END_DIST);
        // Колокол: 4*s*(1-s) даёт пик 1.0 в середине, 0 по краям
        return HILL_AMPLITUDE * 4.0 * s * (1.0 - s);
    }

    private static double trailDistance(double x, double z) {
        long key = spatialCellKey(x, z);
        int[] segments = TRAIL_SEGMENTS_BY_CELL.get(key);
        double bestSquared = Double.MAX_VALUE;
        if (segments != null) {
            for (int idx : segments) {
                bestSquared = Math.min(bestSquared, segmentDistanceSquared(x, z,
                        TRAIL_CURVE[idx], TRAIL_CURVE[idx + 1]));
            }
        } else {
            // Фолбэк: проверяем соседние ячейки
            int cx = spatialCellCoordinate(x);
            int cz = spatialCellCoordinate(z);
            for (int ddx = -1; ddx <= 1; ddx++) {
                for (int ddz = -1; ddz <= 1; ddz++) {
                    long nk = ((long)(cx + ddx) << 32) | ((cz + ddz) & 0xFFFFFFFFL);
                    int[] ns = TRAIL_SEGMENTS_BY_CELL.get(nk);
                    if (ns != null) {
                        for (int idx : ns) {
                            bestSquared = Math.min(bestSquared, segmentDistanceSquared(x, z,
                                    TRAIL_CURVE[idx], TRAIL_CURVE[idx + 1]));
                        }
                    }
                }
            }
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
