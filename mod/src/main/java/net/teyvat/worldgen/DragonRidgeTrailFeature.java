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
 * Укладка блоков тропы: dirt_path сверху, coarse_dirt под ней.
 * Рельеф целиком определяется аналитической density-функцией HEIGHT.
 */
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
        ChunkPos chunk = new ChunkPos(context.getOrigin());
        if (!TeyvatDragonRidge.chunkMayContainTrail(
                chunk.getStartX(), chunk.getStartZ(),
                chunk.getEndX(), chunk.getEndZ())) {
            return false;
        }

        boolean changed = false;

        for (int x = chunk.getStartX(); x <= chunk.getEndX(); x++) {
            for (int z = chunk.getStartZ(); z <= chunk.getEndZ(); z++) {
                if (!TeyvatDragonRidge.isTrailSurface(x, z)) continue;

                var world = context.getWorld();
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                var state = world.getBlockState(pos);

                if (!isNatural(state.getBlock()) || !world.getFluidState(pos).isEmpty()) {
                    continue;
                }

                /* не трогаем песок у начала тропы */
                if (state.getBlock() != Blocks.SAND) {
                    setBlockState(world, pos, Blocks.DIRT_PATH.getDefaultState());
                    BlockPos base = pos.down();
                    if (isNatural(world.getBlockState(base).getBlock())) {
                        setBlockState(world, base, Blocks.COARSE_DIRT.getDefaultState());
                    }
                }
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isNatural(net.minecraft.block.Block block) {
        return block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.STONE
                || block == Blocks.GRAVEL
                || block == Blocks.SAND;
    }
}
