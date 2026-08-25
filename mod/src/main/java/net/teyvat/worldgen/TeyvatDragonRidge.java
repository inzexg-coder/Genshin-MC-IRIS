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
 * Рельеф хребта вокруг пляжа. Вся высота считается одним HEIGHT.
 * Тропа — плоский коридор, естественно лежащий между склонами.
 */
public final class TeyvatDragonRidge {
    public static final Identifier ZONE_ID =
            Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_zone_raw");
    public static final Identifier PATH_ID =
            Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_path_raw");
    public static final Identifier HEIGHT_ID =
            Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_height_raw");

    private static final int INNER = 72;
    private static final int OUTER = 235;
    private static final double CX = 0.0;
    private static final double CZ = -1165.0;

    public static final int TRAILHEAD_X = 0;
    public static final int TRAILHEAD_Z = -1295;

    private static final double T_START = 70.0;
    private static final double T_END = 200.0;
    private static final double T_HALF = 2.8;
    private static final int CELL = 32;

    private static final double[][] CURVE = buildCurve();
    private static final Map<Long, int[]> INDEX = buildIndex();

    /* -------- zone -------- */
    private static final DensityFunction ZONE = new DensityFunction.Base() {
        @Override public double sample(NoisePos p) {
            double dx = p.blockX();
            double dz = p.blockZ() - TeyvatOceanEdge.BEACH_CENTER_Z;
            double r = Math.sqrt(dx * dx + dz * dz);
            double a = Math.atan2(dx, dz);
            double w = r + 7 * Math.sin(a * 3 + r * 0.024)
                    + 4 * Math.sin(dx * 0.019 - dz * 0.016);
            return clampM1P1(
                    env(w, INNER + 4, INNER + 26, OUTER - 34, OUTER)
                    * smooth(18, 52, dz) * 2 - 1);
        }
        @Override public double minValue() { return -1; }
        @Override public double maxValue() { return 1; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(this)); }
    };

    /* -------- path biome mask -------- */
    private static final DensityFunction PATH_F = new DensityFunction.Base() {
        @Override public double sample(NoisePos p) {
            double d = trailDist(p.blockX(), p.blockZ());
            return clampM1P1((1 - smooth(5, 10, d)) * 2 - 1);
        }
        @Override public double minValue() { return -1; }
        @Override public double maxValue() { return 1; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(this)); }
    };

    /* -------- height -------- */
    private static final DensityFunction HEIGHT = new DensityFunction.Base() {
        @Override public double sample(NoisePos p) {
            int bx = p.blockX();
            int bz = p.blockZ();
            double x = bx;
            double z = bz;
            double dz = z - TeyvatOceanEdge.BEACH_CENTER_Z;
            double r = Math.sqrt(x * x + dz * dz);
            double a = Math.atan2(x, dz);

            /* envelope */
            double w = r + 7 * Math.sin(a * 3 + r * 0.024)
                    + 4 * Math.sin(x * 0.019 - dz * 0.016);
            double e = env(w, INNER + 4, INNER + 30, OUTER - 38, OUTER)
                    * smooth(20, 55, dz);
            if (e < 0.001) return 0;

            /* progress: 0 inner → 1 outer */
            double t = clamp01((r - INNER) / (OUTER - INNER));

            /* gentle climb */
            double climb = t * 1.8;

            /* rolling hills */
            double h1 = Math.sin(x * 0.012 + Math.sin(dz * 0.007) * 1.0) * 0.45;
            double h2 = Math.sin(dz * 0.009 + Math.sin(x * 0.006) * 0.8) * 0.35;
            double hills = e * (h1 + h2);

            /* summit bump */
            double sd = (x - CX) * (x - CX) + (z - CZ) * (z - CZ);
            double summit = e * Math.exp(-sd / 2500) * 1.2;

            /* full ridge height */
            double ridge = e * (climb + 0.3) + hills + summit;

            /* trail corridor */
            double td = trailDist(bx, bz);
            double blend = smooth(T_HALF, T_HALF + 22, td);
            double trail = ridge - 0.25 * e;

            return trail * (1 - blend) + ridge * blend;
        }
        @Override public double minValue() { return -1.5; }
        @Override public double maxValue() { return 5; }
        @Override public CodecHolder<? extends DensityFunction> getCodecHolder() {
            return CodecHolder.of(MapCodec.unit(this)); }
    };

    private TeyvatDragonRidge() {}

    public static void register() {
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, ZONE_ID, MapCodec.unit(ZONE));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, PATH_ID, MapCodec.unit(PATH_F));
        Registry.register(Registries.DENSITY_FUNCTION_TYPE, HEIGHT_ID, MapCodec.unit(HEIGHT));
    }

    public static boolean isTrailSurface(int x, int z) {
        return trailDist(x, z) <= T_HALF;
    }

    public static double trailDist(double x, double z) {
        int[] c = INDEX.get(ck((int) x, (int) z));
        if (c == null) return 999;
        double best = Double.MAX_VALUE;
        for (int i : c)
            best = Math.min(best, segDist(x, z, CURVE[i], CURVE[i + 1]));
        return Math.sqrt(best);
    }

    public static boolean chunkMayContainTrail(int x0, int z0, int x1, int z1) {
        for (int i = 0; i < CURVE.length - 1; i++) {
            double[] a = CURVE[i], b = CURVE[i + 1];
            if (Math.max(a[0], b[0]) + 3 >= x0 && Math.min(a[0], b[0]) - 3 <= x1
                    && Math.max(a[1], b[1]) + 3 >= z0 && Math.min(a[1], b[1]) - 3 <= z1)
                return true;
        }
        return false;
    }

    /* curve */
    private static double[][] buildCurve() {
        double[][] p = new double[257][];
        for (int i = 0; i < p.length; i++) {
            double t = i / 256.0;
            double e = t * t * (3 - 2 * t);
            double r = T_START + (T_END - T_START) * e;
            double a = 0.35 * Math.sin(3 * Math.PI * t);
            p[i] = new double[]{r * Math.sin(a),
                    TeyvatOceanEdge.BEACH_CENTER_Z + r * Math.cos(a)};
        }
        return p;
    }

    /* index */
    private static Map<Long, int[]> buildIndex() {
        Map<Long, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < CURVE.length - 1; i++) {
            double[] a = CURVE[i], b = CURVE[i + 1];
            int x0 = cc(Math.min(a[0], b[0]) - 3), x1 = cc(Math.max(a[0], b[0]) + 3);
            int z0 = cc(Math.min(a[1], b[1]) - 3), z1 = cc(Math.max(a[1], b[1]) + 3);
            for (int cx = x0 - 1; cx <= x1 + 1; cx++)
                for (int cz = z0 - 1; cz <= z1 + 1; cz++)
                    m.computeIfAbsent(ck(cx * CELL, cz * CELL), k -> new ArrayList<>()).add(i);
        }
        Map<Long, int[]> r = new HashMap<>();
        m.forEach((k, v) -> r.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return Map.copyOf(r);
    }

    private static int cc(double v) { return Math.floorDiv((int) Math.floor(v), CELL); }
    private static long ck(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    /* geometry */
    private static double segDist(double px, double pz, double[] a, double[] b) {
        double abx = b[0] - a[0], abz = b[1] - a[1];
        double apx = px - a[0], apz = pz - a[1];
        double l2 = abx * abx + abz * abz;
        double f = l2 < 1e-8 ? 0 : clamp01((apx * abx + apz * abz) / l2);
        double dx = a[0] + abx * f - px, dz = a[1] + abz * f - pz;
        return dx * dx + dz * dz;
    }

    private static double env(double warped, double i0, double i1, double o0, double o1) {
        return smooth(i0, i1, warped) * (1 - smooth(o0, o1, warped));
    }
    private static double smooth(double f, double t, double v) {
        double x = clamp01((v - f) / (t - f));
        return x * x * (3 - 2 * x);
    }
    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    private static double clampM1P1(double v) { return Math.max(-1, Math.min(1, v)); }
}
