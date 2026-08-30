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
 * Водопад 3×5 в Звездопадной Долине: каменная скала, падающая вода, бассейн.
 * Только в только в чанке, не выходит за границы.
 */
public final class StarfallValleyWaterfallFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "starfall_valley_waterfall");

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

        // Водопад только рядом с тропой (не дальше 40 блоков).
        double trailDist = TeyvatStarfallValley.trailDistancePublic(x, z);
        if (trailDist > 40.0 || trailDist < 3.0) return false;

        int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
        if (surfaceY <= world.getBottomY() + 8) return false;

        // Проверяем, что вся конструкция укладывается в чанк.
        if (x - 1 < cMinX || x + 3 > cMaxX || z - 3 < cMinZ || z + 1 > cMaxZ) return false;

        boolean changed = false;
        int baseY = surfaceY - 1;

        // Каменная скала (спинка): 3 блока в ширину, 5 в высоту, 1 блок глубиной.
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                BlockPos p = new BlockPos(x + dx, baseY + 1 + dy, z + 1);
                setBlockState(world, p, Blocks.STONE.getDefaultState());
                changed = true;
            }
        }

        // Источники воды на вершине (3 блока).
        for (int dx = 0; dx < 3; dx++) {
            BlockPos p = new BlockPos(x + dx, baseY + 5, z);
            setBlockState(world, p, Blocks.WATER.getDefaultState());
            changed = true;
        }

        // Падающая вода вдоль лица скалы (5 блоков в высоту, 3 в ширину).
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                BlockPos p = new BlockPos(x + dx, baseY + dy, z);
                if (world.getBlockState(p).isAir()) {
                    setBlockState(world, p, Blocks.WATER.getDefaultState());
                    changed = true;
                }
            }
        }

        // Бассейн у подножия (5×3, дно из булыжника, вода).
        for (int dx = -1; dx < 4; dx++) {
            for (int dz = -3; dz < 0; dz++) {
                BlockPos p = new BlockPos(x + dx, baseY - 1, z + dz);
                BlockPos above = new BlockPos(x + dx, baseY, z + dz);
                setBlockState(world, p, Blocks.COBBLESTONE.getDefaultState());
                if (world.getBlockState(above).isAir()) {
                    setBlockState(world, above, Blocks.WATER.getDefaultState());
                }
                changed = true;
            }
        }

        return changed;
    }
}
