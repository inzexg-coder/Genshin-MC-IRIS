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
 * Водопад в Звездопадной Долине: каменная скала у тропы, вода стекает
 * в микро-озерцо. Самодостаточный: если естественного склона нет,
 * досыпает каменную возвышенность позади — поэтому генерируется
 * на любом сиде (рельеф долины почти не зависит от сида).
 */
public final class StarfallValleyWaterfallFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_waterfall");

    private static final int ROCK_W = 3;
    private static final int ROCK_H = 5;
    private static final int LAKE_W = 5;
    private static final int LAKE_D = 3;
    private static final int LAKE_DEPTH = 2;
    /** Позади скалы досыпаем землю/камень, чтобы вода вытекала "из холма". */
    private static final int BACKFILL = 4;

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

        int x = origin.getX();
        int z = origin.getZ();

        double trailDist = TeyvatStarfallValley.trailDistancePublic(x, z);
        if (trailDist > 40.0 || trailDist < 3.0) return false;

        int yC = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (yC <= world.getBottomY() + 8) return false;

        // Мини-склон ≥2 блоков: помогает найти край холма, но не обязателен —
        // фича самодостаточна и встанет и на ровный участок у тропы.
        int yN = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z - 4);
        int yS = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z + 4);
        int yE = world.getTopY(Heightmap.Type.WORLD_SURFACE, x + 4, z);
        int yW = world.getTopY(Heightmap.Type.WORLD_SURFACE, x - 4, z);
        int minH = Math.min(yC, Math.min(Math.min(yN, yS), Math.min(yE, yW)));
        int maxH = Math.max(yC, Math.max(Math.max(yN, yS), Math.max(yE, yW)));
        int slope = maxH - minH;
        // Нижняя точка как основание, но не глубже 64 (над морем).
        int baseY = Math.max(minH, 66);

        if (x - LAKE_W / 2 < cMinX || x + LAKE_W / 2 > cMaxX
                || z - LAKE_D - 2 < cMinZ || z + ROCK_H + BACKFILL > cMaxZ) return false;

        boolean changed = false;

        // ── СКАЛА: 3×5 каменная стена, фронт лицом к игроку (−Z) ──
        for (int dx = 0; dx < ROCK_W; dx++) {
            for (int dy = 0; dy < ROCK_H; dy++) {
                BlockPos p = new BlockPos(x + dx, baseY + dy, z);
                setBlockState(world, p, Blocks.STONE.getDefaultState());
                changed = true;
            }
        }

        // ── ЗАСЫПКА ПОЗАДИ: земля/камень, чтобы скала сливалась с холмом ──
        for (int dx = 0; dx < ROCK_W; dx++) {
            for (int dz = 1; dz <= BACKFILL; dz++) {
                int fillTop = world.getTopY(Heightmap.Type.WORLD_SURFACE, x + dx, z + dz);
                int fillLow = Math.min(baseY + ROCK_H - 1, fillTop);
                for (int dy = baseY; dy <= fillLow; dy++) {
                    BlockPos p = new BlockPos(x + dx, dy, z + dz);
                    setBlockState(world, p, Blocks.STONE.getDefaultState());
                    changed = true;
                }
            }
        }

        // ── ВОДА ИЗ ВЕРХА СКАЛЫ (из расщелины) ──
        for (int dx = 0; dx < ROCK_W; dx++) {
            BlockPos p = new BlockPos(x + dx, baseY + 4, z);
            setBlockState(world, p, Blocks.WATER.getDefaultState());
            changed = true;
        }

        // ── СТЕКАЮЩАЯ ВОДА по лицу скалы порциями от расщелины до низа ──
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
