package net.teyvat.worldgen;

/**
 * Single analytic surface for the ridge biome. Produces a continuous height and a
 * deterministic trail path so block-level features can smooth terrain without
 * relying on the vanilla 4x4 density grid.
 */
public final class RidgeHeightField {
    private RidgeHeightField() {}

    private static final double INNER_RADIUS = 72.0;
    private static final double OUTER_RADIUS = 235.0;
    private static final double TRAIL_START_RADIUS = 70.0;
    private static final double TRAIL_END_RADIUS = 200.0;
    private static final double TRAIL_HALF_WIDTH = 2.8;
    private static final double CLIMB = 2.35;
    private static final double SHOULDER = 0.55;
    private static final double SUMMIT_X = 0.0;
    private static final double SUMMIT_Z = -1165.0;

    private static final int TRAIL_SEGMENTS = 257;
    private static final double[][] TRAIL_CURVE = buildTrailCurve();

    public static int targetY(int x, int z) {
        double density = rawDensity(x, z);
        return (int)Math.round(64.0 + 6.0 * density);
    }

    public static boolean isTrail(int x, int z) {
        return TeyvatDragonRidge.isTrailSurface(x, z);
    }

    private static double rawDensity(int x, int z) {
        double progress = TeyvatDragonRidge.progress((double) x, (double) z);
        double climb = progress * CLIMB;
        double trailDist = TeyvatDragonRidge.trailDistance((double) x, (double) z);
        double opening = smoothstep(TRAIL_HALF_WIDTH, TRAIL_HALF_WIDTH + 24.0, trailDist);
        double shoulder = SHOULDER * opening;
        double waves = Math.sin(x * 0.017 + Math.sin(z * 0.009) * 1.2)
                * Math.cos(z * 0.014 + Math.sin(x * 0.008));
        double hills = waves * 0.32 * opening;
        double summitDist = (x - SUMMIT_X) * (x - SUMMIT_X) + (z - SUMMIT_Z) * (z - SUMMIT_Z);
        double summit = Math.exp(-summitDist / 2025.0) * 0.65;
        double envelope = envelope(x, z);
        return envelope * (climb + shoulder + hills + summit);
    }

    private static double envelope(int x, int z) {
        double dz = (double) z - TeyvatOceanEdge.BEACH_CENTER_Z;
        double radius = Math.sqrt((double) x * x + dz * dz);
        double angle = Math.atan2((double) x, dz);
        double warped = radius
                + 7.0 * Math.sin(angle * 3.0 + radius * 0.024)
                + 4.0 * Math.sin(x * 0.019 - dz * 0.016);
        double inner = smoothstep(INNER_RADIUS + 4.0, INNER_RADIUS + 30.0, warped);
        double outer = 1.0 - smoothstep(OUTER_RADIUS - 38.0, OUTER_RADIUS, warped);
        double land = smoothstep(20.0, 55.0, dz);
        return Math.max(0.0, inner * outer * land);
    }

    private static double[][] buildTrailCurve() {
        double[][] points = new double[TRAIL_SEGMENTS][];
        for (int i = 0; i < points.length; i++) {
            double t = i / (double) (points.length - 1);
            double eased = t * t * (3.0 - 2.0 * t);
            double radius = TRAIL_START_RADIUS + (TRAIL_END_RADIUS - TRAIL_START_RADIUS) * eased;
            double angle = 0.42 * Math.sin(3.0 * Math.PI * t);
            points[i] = new double[]{radius * Math.sin(angle),
                    TeyvatOceanEdge.BEACH_CENTER_Z + radius * Math.cos(angle)};
        }
        return points;
    }

    private static double smoothstep(double from, double to, double value) {
        double f = Math.max(0.0, Math.min(1.0, (value - from) / (to - from)));
        return f * f * (3.0 - 2.0 * f);
    }
}
