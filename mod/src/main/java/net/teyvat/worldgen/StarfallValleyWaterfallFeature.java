package net.teyvat.worldgen;

import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import net.teyvat.TeyvatMod;

/**
 * Водопад в Звездопадной Долине.
 * Сначала ищет естественный склон ≥4 блоков у тропы в чанке — водопад
 * встраивается в холм. Если подходящего склона нет нигде в чанке —
 * генерирует искусственную скалу, врезанную в склон холма (не поверхность),
 * и микро-озерцо у подножия. Так водопад появляется гарантированно.
 */
public final class StarfallValleyWaterfallFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_waterfall");

    private static final int ROCK_W = 3;
    private static final int ROCK_H = 5;
    private static final int LAKE_W = 5;
    private static final int LAKE_D = 3;
    private static final int LAKE_DEPTH = 2;
    /** Глубина скалы, врезанной в холм (по +Z от фронта). */
    private static final int ROCK_D = 4;
    /** Минимальный естественный склон для "натурального" водопада. */
    private static final int NATURAL_SLOPE = 4;
    /** Шаг сканирования точек в чанке. */
    private static final int SCAN_STEP = 4;

    public StarfallValleyWaterfallFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    public static void register() {
        Registry.register(Registries.FEATURE, ID, new StarfallValleyWaterfallFeature());
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        var world = context.getWorld();
        var origin = context.getOrigin();
        ChunkPos chunkPos = new ChunkPos(origin);
        int cMinX = chunkPos.getStartX();
        int cMinZ = chunkPos.getStartZ();
        int cMaxX = cMinX + 15;
        int cMaxZ = cMinZ + 15;

        // ── ШАГ 1: ищем естественный склон у тропы в чанке ──
        int bestX = Integer.MIN_VALUE;
        int bestZ = Integer.MIN_VALUE;
        int bestSlope = 0;
        for (int sx = cMinX; sx <= cMaxX; sx += SCAN_STEP) {
            for (int sz = cMinZ; sz <= cMaxZ; sz += SCAN_STEP) {
                double trailDist = TeyvatStarfallValley.trailDistancePublic(sx, sz);
                if (trailDist < 6.0 || trailDist > 34.0) continue;

                int slope = slopeAt(world, sx, sz);
                if (slope >= NATURAL_SLOPE && slope > bestSlope) {
                    bestSlope = slope;
                    bestX = sx;
                    bestZ = sz;
                }
            }
        }

        // ── ШАГ 2: естественный склон найден — строим водопад в холме ──
        if (bestX != Integer.MIN_VALUE) {
            return buildWaterfall(world, bestX, bestZ, cMinX, cMinZ, cMaxX, cMaxZ, false);
        }

        // ── ШАГ 3: склона нет — искусственная скала, врезанная в холм ──
        // Ищем самую высокую точку холма в чанке как кандидата для искусственной
        // скалы: вода будет вытекать из врезанного каменного уступа.
        int hillX = Integer.MIN_VALUE;
        int hillZ = Integer.MIN_VALUE;
        int hillY = -1;
        for (int sx = cMinX; sx <= cMaxX; sx += SCAN_STEP) {
            for (int sz = cMinZ; sz <= cMaxZ; sz += SCAN_STEP) {
                double trailDist = TeyvatStarfallValley.trailDistancePublic(sx, sz);
                if (trailDist < 6.0 || trailDist > 40.0) continue;
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, sx, sz);
                if (topY > hillY) {
                    hillY = topY;
                    hillX = sx;
                    hillZ = sz;
                }
            }
        }
        if (hillX == Integer.MIN_VALUE) return false;

        boolean ok = buildWaterfall(world, hillX, hillZ, cMinX, cMinZ, cMaxX, cMaxZ, true);
        return ok;
    }

    /** Перепад высот в квадрате 9×9 с центром (x,z). */
    private int slopeAt(net.minecraft.world.WorldAccess world, int x, int z) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x + dx, z + dz);
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        return max - min;
    }

    /** Строит водопад: скала 3×5, вода из расщелины, стекающая вода,
     *  микро-озерцо у подножия. artificial=true — скала врезается в холм,
     *  досыпая камень позади до высоты скалы ("в холме, не на поверхности"). */
    private boolean buildWaterfall(net.minecraft.world.WorldAccess world,
                                          int x, int z, int cMinX, int cMinZ,
                                          int cMaxX, int cMaxZ, boolean artificial) {
        if (x - LAKE_W / 2 < cMinX || x + LAKE_W / 2 > cMaxX
                || z - LAKE_D - 2 < cMinZ || z + ROCK_D + 1 > cMaxZ) return false;

        int yC = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (yC <= world.getBottomY() + 8) return false;

        // Основание: чуть ниже поверхности в точке фронта, не глубже 66.
        int baseY = Math.max(yC - 1, 66);

        boolean changed = false;

        // ── СКАЛА 3×5 (фронт к −Z), при artificial=врезаем глубже в холм ──
        int depth = artificial ? ROCK_D : 1;
        for (int dx = 0; dx < ROCK_W; dx++) {
            for (int dy = 0; dy < ROCK_H; dy++) {
                for (int dz = 0; dz < depth; dz++) {
                    BlockPos p = new BlockPos(x + dx, baseY + dy, z + dz);
                    setBlockState(world, p, Blocks.STONE.getDefaultState());
                    changed = true;
                }
            }
        }

        // ── ВОДА ИЗ ВЕРХА СКАЛЫ (расщелина, ряд baseY+4) ──
        for (int dx = 0; dx < ROCK_W; dx++) {
            BlockPos p = new BlockPos(x + dx, baseY + 4, z);
            setBlockState(world, p, Blocks.WATER.getDefaultState());
            changed = true;
        }

        // ── СТЕКАЮЩАЯ ВОДА по лицу скалы ──
        for (int dx = 0; dx < ROCK_W; dx++) {
            for (int dy = 1; dy <= 4; dy++) {
                BlockPos p = new BlockPos(x + dx, baseY + 4 - dy, z - 1);
                if (world.getBlockState(p).isAir()) {
                    setBlockState(world, p, Blocks.WATER.getDefaultState());
                    changed = true;
                }
            }
        }

        // ── МИКРО-ОЗЕРЦО У ПОДНОЖИЯ (5×3, глубина 2, дно булыжник) ──
        int lakeZ = z - 1;
        for (int dx = -LAKE_W / 2; dx <= LAKE_W / 2; dx++) {
            for (int dz = 0; dz < LAKE_D; dz++) {
                for (int dy = 0; dy > -LAKE_DEPTH; dy--) {
                    BlockPos p = new BlockPos(x + dx, baseY + dy, lakeZ + dz);
                    setBlockState(world, p, Blocks.COBBLESTONE.getDefaultState());
                    changed = true;
                }
                BlockPos waterPos = new BlockPos(x + dx, baseY, lakeZ + dz);
                if (world.getBlockState(waterPos).isAir()) {
                    setBlockState(world, waterPos, Blocks.WATER.getDefaultState());
                    changed = true;
                }
            }
        }

        return changed;
    }
}
