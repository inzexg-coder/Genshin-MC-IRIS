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

/** Тропа серпантина: ТОЛЬКО покраска поверхности, без копания. */
public final class DragonRidgeTrailFeature extends Feature<DefaultFeatureConfig> {
    public static final Identifier ID =
            Identifier.of(TeyvatMod.MOD_ID, "dragon_ridge_trail");

    public DragonRidgeTrailFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    public static void register() {
        Registry.register(Registries.FEATURE, ID, new DragonRidgeTrailFeature());
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        ChunkPos chunkPos = new ChunkPos(context.getOrigin());
        if (!TeyvatDragonRidge.chunkMayContainTrail(chunkPos.getStartX(), chunkPos.getStartZ(),
                chunkPos.getEndX(), chunkPos.getEndZ())) {
            return false;
        }

        var world = context.getWorld();
        boolean changed = false;

        for (int x = chunkPos.getStartX(); x <= chunkPos.getEndX(); x++) {
            for (int z = chunkPos.getStartZ(); z <= chunkPos.getEndZ(); z++) {
                if (!TeyvatDragonRidge.isTrailSurface(x, z)) {
                    continue;
                }

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, surfaceY, z);

                if (!isTrailBase(world.getBlockState(surface).getBlock())) {
                    continue;
                }

                // Только замена верхнего блока — никакого копания
                if (world.getBlockState(surface).getBlock() != Blocks.SAND) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                }

                BlockPos below = surface.down();
                if (isTrailBase(world.getBlockState(below).getBlock())) {
                    setBlockState(world, below, Blocks.COARSE_DIRT.getDefaultState());
                }

                changed = true;
            }
        }

        return changed;
    }

    private static boolean isTrailBase(net.minecraft.block.Block block) {
        return block == Blocks.SAND
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.STONE
                || block == Blocks.GRAVEL;
    }
}
