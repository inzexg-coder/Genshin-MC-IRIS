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
 * Кольцо Звездопадной Долины вокруг пляжа и широкая серпантинная тропа.
 * Все функции зависят только от X/Z и кэшируются в JSON как 2D.
 */
public final class TeyvatStarfallValley {
    public static final Identifier ZONE_ID = Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_zone_raw");
    public static final Identifier PATH_ID = Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_path_raw");
    public static final Identifier HEIGHT_ID = Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_height_raw");
    public static final Identifier FLOOR_ID = Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_floor_raw");

    private static final int INNER_RADIUS = 72;
    private static final int OUTER_RADIUS = 235;
    private static final double SUMMIT_X = 0.0;
    private static final double SUMMIT_Z = -1165.0;

    /** Фиксированный вход на серпантин для структур и серверного поиска.
     *  X=2: видимая полоса dirt_path в мире смещена относительно центральной
     *  линии тропы на ~2.5 блока в +X, поэтому точку тeлепортации ставим сюда,
     *  чтобы она была по центру дороги, а не сбоку. */
    public static final int TRAILHEAD_X = 2;
    public static final int TRAILHEAD_Z = -1270;

    private static final double START_RADIUS = 68.0;
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


    /** Предвычисленная сетка расстояний до тропы (ячейка 4×4, билинейная
     *  интерполяция). Инициализируется один раз при загрузке класса —
     *  полностью убирает перебор 256 сегментов на каждый блок. */
    private static final double TRAIL_GRID_MIN_X = -256.0;
    private static final double TRAIL_GRID_MIN_Z = -1664.0;
    private static final double TRAIL_GRID_STEP = 4.0;
    private static final double TRAIL_GRID_INV_STEP = 1.0 / TRAIL_GRID_STEP;
    private static final int TRAIL_GRID_WIDTH = (int) ((256.0 + 256.0) / TRAIL_GRID_STEP) + 1;  // -256..+256
    private static final int TRAIL_GRID_HEIGHT = (int) ((-1050.0 + 1664.0) / TRAIL_GRID_STEP) + 1; // -1664..-1050
    private static final double[] TRAIL_DIST_GRID = buildTrailDistGrid();

    private static double[] buildTrailDistGrid() {
        double[] grid = new double[TRAIL_GRID_WIDTH * TRAIL_GRID_HEIGHT];
        for (int gz = 0; gz < TRAIL_GRID_HEIGHT; gz++) {
            for (int gx = 0; gx < TRAIL_GRID_WIDTH; gx++) {
                double x = TRAIL_GRID_MIN_X + gx * TRAIL_GRID_STEP;
                double z = TRAIL_GRID_MIN_Z + gz * TRAIL_GRID_STEP;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < TRAIL_CURVE.length - 1; i++) {
                    best = Math.min(best, segmentDistanceSquared(x, z,
                            TRAIL_CURVE[i], TRAIL_CURVE[i + 1]));
                }
                grid[gz * TRAIL_GRID_WIDTH + gx] = Math.sqrt(best);
            }
        }
        return grid;
    }

    private static final DensityFunction ZONE = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double x = pos.blockX();
            double z = pos.blockZ();
            if (x < -260 || x > 260 || z < TeyvatOceanEdge.BEACH_CENTER_Z - 260
                    || z > TeyvatOceanEdge.BEACH_CENTER_Z + 260) return -1.0;
            double dx = x;
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double radius = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dx, dz);
            double warpedRadius = radius
                    + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                    + 4.0 * Math.sin(dx * 0.019 - dz * 0.016);

            double inner = smoothstep(INNER_RADIUS + 2, INNER_RADIUS + 30, warpedRadius);
            double outer = 1.0 - smoothstep(OUTER_RADIUS - 75, OUTER_RADIUS + 40, warpedRadius);
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
            // Быстрые границы кольца тропы (без вычислений вне зоны).
            double pdz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double pradius = Math.sqrt(x * x + pdz * pdz);
            if (pradius < 60.0 || pradius > 235.0
                    || x < -90.0 || x > 90.0) return -1.0;

            double distance = trailDistance(x, z);
            // Тропа плавно проявляется от края пляжа к долине.
            double edgeIn = smoothstep(64.0, 84.0, pradius); // 0 у кромки песка, 1 в долине
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

            // Кольцо холмов: снимаем у самого пляжа (radius < INNER_RADIUS) и
            // плавно затухаем на внешнем крае (широкий фейд — без вертикальных стен).
            double ringIn = smoothstep(INNER_RADIUS - 18.0, INNER_RADIUS + 30.0, warpedRadius);
            double ringOut = 1.0 - smoothstep(OUTER_RADIUS - 90.0, OUTER_RADIUS + 70.0, warpedRadius);
            double ring = ringIn * ringOut;

            // Ровное плато под самой тропой (floorBand=1 → plate = константа),
            // чтобы дно долины было идеально плоским.
            double floorBand = 1.0 - smooth01(dist / 12.0);
            double plate = TRAIL_FLOOR_AMP * smoothstep(50.0, 95.0, warpedRadius) * floorBand;

            // Долина: тропа слегка утоплена ниже окружающей земли, чтобы линия долины
            // читалась. Локально только рядом с тропой (dist < 28), 0 вне её.
            double valley = -VALLEY_DEPTH * (1.0 - smooth01(dist / 28.0));

            // Холмы по ОБЕ стороны тропы: симметричный купол по dist —
            // 0 на тропе, мягкая округлая вершина, плавный спуск к HILL_END_DIST.
            // Подавляем холмы в самой полосе тропы (dist<10), чтобы ни один блок
            // дороги не возвышался над единым уровнем дна долины.
            double hill = linearHillProfile(dist) * smoothstep(10.0, 25.0, dist);

            // Микро-шум для разбиения 4×4-квантования: полностью убран на самой
            // тропе (floorBand=1), затухает на краях холмов. Многооктавный,
            // чтобы поверхность блоков выглядела естественной, а не сеткой.
            double microFade = smoothstep(15.0, 35.0, dist)
                    * (1.0 - smoothstep(155.0, HILL_END_DIST, dist));
            // Микро-шум для естественности, НО приглушён почти на всей горе:
            // включается лишь у самого подножия и почти полностью выключен на
            // склонах и вершине (dist 30-170), чтобы холм оставался одним
            // гладким округлым куполом, а не распадался на пики и выделяющиеся
            // склоны.
            double crestSuppress = 1.0 - 0.85 * smoothstep(30.0, 50.0, dist)
                    * (1.0 - smoothstep(150.0, 180.0, dist));
            double micro = HILL_MICRO_AMP * crestSuppress * microFade * ring * microNoise(x, z);

            // Плато+долина (ровное дно дороги) НЕ зависят от кольца ring, поэтому
            // дно тропы ровное на любой её длине и не волнуется вместе с холмами.
            // Холмы и микро-шум, наоборот, масштабируются ring'ом.
            return (plate + valley) + ring * (hill + micro);
        }

        @Override public double minValue() { return -4.0; }
        @Override public double maxValue() { return 9.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(HEIGHT)); }
    };

    /** Узкая полоса вдоль тропы: 1.0 на самой тропе (dist < 16), 0 вне её.
     *  Используется как «выключатель шума»: внутри полосы убирается случайная
     *  составляющая teyvat_beach_height (erosion), а детерминированный профиль
     *  (cliff_profile) остаётся. На всей тропе cliff_profile константен (1.35),
     *  поэтому дно долины становится идеально ровным, а море/пляж/равнины
     *  не затрагиваются. */
    private static final DensityFunction FLOOR = new DensityFunction.Base() {
        @Override
        public double sample(NoisePos pos) {
            double x = pos.blockX();
            double z = pos.blockZ();
            if (x < -240 || x > 240 || z < -1600 || z > -1040) return 0.0;
            double dist = trailDistance(x, z);
            double fade = smooth01((dist - 140.0) / 80.0);
            return Math.max(0.0, 1.0 - fade);
        }

        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() { return CodecHolder.of(MapCodec.unit(FLOOR)); }
    };

    /** Публичное расстояние от точки до тропы (для поиска точки телепортации). */
    public static double trailDistancePublic(double x, double z) {
        return trailDistance(x, z);
    }

    private TeyvatStarfallValley() {}

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
        double edgeIn = smoothstep(64.0, 84.0, radius);
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
    private static final double HILL_END_DIST = 230.0;
    private static final double HILL_AMPLITUDE = 1.15;
    private static final double VALLEY_DEPTH = 0.5;
    private static final double TRAIL_FLOOR_AMP = 0.1;
    /** Амплитуда микро-шума на склонах холмов — ломает 4×4-квантование
     *  поверхности. Многократная шумовая рябь с основательным разбросом высот,
     *  чтобы блоки выглядели как живая майнкрафт-terrain, а не сетка. */
    private static final double HILL_MICRO_AMP = 0.04;
    /** Базовая частота микро-шума (период в блоках). */
    private static final double MICRO_BASE_FREQ = 0.25;

    /** Плавный симметричный купол: 0 у тропы, широкая округлая вершина
     *  в середине, плавный спуск в равнины. Профиль — чистый синус
     *  (не косинус-квадрат): склон круче у подножия и максимально
     *  пологий у самой вершины, поэтому холм не «выпирает» резким
     *  пиком из пологого склона, а плавно скругляется.
     *  Производные нулевые на тропе, вершине и крае. */
    private static double linearHillProfile(double dist) {
        if (dist <= 0.0 || dist >= HILL_END_DIST) return 0.0;
        double s = clamp01(dist / HILL_END_DIST);
        return HILL_AMPLITUDE * Math.sin(Math.PI * s);
    }

    /** Мягкий детерминированный value-noise (-1..1) на заданной частоте:
     *  октавная ряд превращает гладкий купол холма в лёгкую неровную
     *  поверхность, плотно ломающую 4×4-квантование. */
    private static double valueNoise(double gx, double gz) {
        int xi = (int) Math.floor(gx);
        int zi = (int) Math.floor(gz);
        double fx = gx - xi;
        double fz = gz - zi;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double n00 = hash01(xi, zi);
        double n10 = hash01(xi + 1, zi);
        double n01 = hash01(xi, zi + 1);
        double n11 = hash01(xi + 1, zi + 1);
        double a = n00 + (n10 - n00) * sx;
        double b = n01 + (n11 - n01) * sx;
        return (a + (b - a) * sz) * 2.0 - 1.0;
    }

    /** Одиноктавный микро-шум: ровно на частоте 4-блочной сетки (freq=0.25),
     *  каждая ячейка 4×4 получает уникальное значение с плавной интерполяцией
     *  между соседями — как ванильные равнины. Без острых ступеней, без ряби. */
    private static double microNoise(double x, double z) {
        return valueNoise(x * MICRO_BASE_FREQ + 7.3, z * MICRO_BASE_FREQ - 13.7);
    }

    private static double hash01(int x, int z) {
        long h = (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Расстояние до тропы: быстрый lookup по предвычисленной сетке расстояний
     *  (ячейка 4x4 + билинейная интерполяция). Никаких переборов сегментов на
     *  каждый блок — чанкогенерация дальних областей больше не тормозит. */
    private static double trailDistance(double x, double z) {
        double gx = (x - TRAIL_GRID_MIN_X) * TRAIL_GRID_INV_STEP;
        double gz = (z - TRAIL_GRID_MIN_Z) * TRAIL_GRID_INV_STEP;
        if (gx < 0.0 || gz < 0.0 || gx > TRAIL_GRID_WIDTH - 1.0 || gz > TRAIL_GRID_HEIGHT - 1.0) {
            return 9999.0;
        }
        int ix = (int) gx;
        int iz = (int) gz;
        if (ix >= TRAIL_GRID_WIDTH - 1) ix = TRAIL_GRID_WIDTH - 2;
        if (iz >= TRAIL_GRID_HEIGHT - 1) iz = TRAIL_GRID_HEIGHT - 2;
        double fx = gx - ix;
        double fz = gz - iz;
        int idx00 = iz * TRAIL_GRID_WIDTH + ix;
        int idx10 = idx00 + 1;
        int idx01 = idx00 + TRAIL_GRID_WIDTH;
        int idx11 = idx01 + 1;
        double d00 = TRAIL_DIST_GRID[idx00];
        double d10 = TRAIL_DIST_GRID[idx10];
        double d01 = TRAIL_DIST_GRID[idx01];
        double d11 = TRAIL_DIST_GRID[idx11];
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double a = d00 + (d10 - d00) * sx;
        double b = d01 + (d11 - d01) * sx;
        return a + (b - a) * sz;
    }

    public static boolean chunkMayContainTrail(int minX, int minZ, int maxX, int maxZ) {
        double margin = TRAIL_HALF_WIDTH + 1.0;
        return maxX + margin >= TRAIL_CURVE[0][0] - 5.0
                && minX - margin <= TRAIL_CURVE[TRAIL_CURVE.length - 1][0] + 5.0
                && maxZ + margin >= -1300.0
                && minZ - margin <= -1149.0;
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
