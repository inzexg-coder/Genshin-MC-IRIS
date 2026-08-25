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

/** Тропа серпантина: поверхневая укладка без глубокого копания. */
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

                // Стартуем от РЕАЛЬНОЙ поверхности в этой колонке, не от topY чанка
                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos.Mutable pos = new BlockPos.Mutable(x, surfaceY, z);

                // Ищем первый подходящий блок, копая максимум 2 блока вниз
                int dug = 0;
                while (dug < 2 && pos.getY() > world.getBottomY()) {
                    if (isTrailBase(world.getBlockState(pos).getBlock())) {
                        break;
                    }
                    pos.move(net.minecraft.util.math.Direction.DOWN);
                    dug++;
                }

                if (!isTrailBase(world.getBlockState(pos).getBlock())
                        || !world.getFluidState(pos).isEmpty()) {
                    continue;
                }

                // Кладём тропу
                if (world.getBlockState(pos).getBlock() != Blocks.SAND) {
                    setBlockState(world, pos, Blocks.DIRT_PATH.getDefaultState());
                }
                // Подкладка под тропой
                BlockPos below = pos.down();
                if (isTrailBase(world.getBlockState(below).getBlock())) {
                    setBlockState(world, below, Blocks.COARSE_DIRT.getDefaultState());
                }

                // Убираем только нависающие блоки НАД тропой (макс 3)
                int clearLimit = Math.min(pos.getY() + 4, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) + 1);
                for (int clearY = pos.getY() + 1; clearY <= clearLimit; clearY++) {
                    if (!world.getBlockState(new BlockPos(x, clearY, z)).isAir()) {
                        world.removeBlock(new BlockPos(x, clearY, z), false);
                    }
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
