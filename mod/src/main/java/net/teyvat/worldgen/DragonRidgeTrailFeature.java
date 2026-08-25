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

/** Точная укладка видимой тропы поверх сглаженного склона. */
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

        boolean changed = false;
        boolean isTrail = TeyvatDragonRidge.chunkMayContainTrail(chunkPos.getStartX(), chunkPos.getStartZ(),
                chunkPos.getEndX(), chunkPos.getEndZ());

        for (int x = chunkPos.getStartX(); x <= chunkPos.getEndX(); x++) {
            for (int z = chunkPos.getStartZ(); z <= chunkPos.getEndZ(); z++) {
                if (!TeyvatDragonRidge.isTrailSurface(x, z)) {
                    continue;
                }

                var world = context.getWorld();
                int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos.Mutable mutable = new BlockPos.Mutable(x, topY, z);
                while (mutable.getY() > world.getBottomY() && !isTrailBase(world.getBlockState(mutable).getBlock())) {
                    mutable.move(net.minecraft.util.math.Direction.DOWN);
                }

                if (!isTrailBase(world.getBlockState(mutable).getBlock())
                        || !world.getFluidState(mutable).isEmpty()) {
                    continue;
                }

                int target = TeyvatDragonRidge.isTrailSurface(x, z) ? -1 : 1;
                if (target == -1 && world.getBlockState(mutable).getBlock() != Blocks.SAND) {
                    setBlockState(world, mutable, Blocks.DIRT_PATH.getDefaultState());
                } else if (target == 1 && world.getBlockState(mutable).getBlock() == Blocks.DIRT_PATH) {
                    setBlockState(world, mutable, Blocks.GRASS_BLOCK.getDefaultState());
                }
                BlockPos base = mutable.down();
                if (isTrailBase(world.getBlockState(base).getBlock())) {
                    setBlockState(world, base, Blocks.COARSE_DIRT.getDefaultState());
                }

                int targetY = RidgeHeightField.targetY(x, z);
                while (mutable.getY() > world.getBottomY() && mutable.getY() < targetY) {
                    mutable.move(net.minecraft.util.math.Direction.UP);
                    if (!world.getBlockState(mutable).isAir()) break;
                    setBlockState(world, mutable, Blocks.STONE.getDefaultState());
                }
                while (mutable.getY() > world.getBottomY() && mutable.getY() > targetY) {
                    world.removeBlock(mutable, false);
                    mutable.move(net.minecraft.util.math.Direction.DOWN);
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
