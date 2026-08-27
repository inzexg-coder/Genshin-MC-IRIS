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

/** Тропа: ЛЁГКИЙ фича-генератор. Ровность тропы обеспечивает сама density-функция
 *  (плоское плато на высоте TRAIL_Y), поэтому здесь мы только подменяем поверхностные
 *  блоки на dirt_path. НИКАКИХ тяжёлых циклов по getTopY и выравнивания — именно
 *  они вызывали бесконечную "Загрузку территории"/"чанки не грузят". */
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

        int minX = chunkPos.getStartX();
        int maxX = chunkPos.getEndX();
        int minZ = chunkPos.getStartZ();
        int maxZ = chunkPos.getEndZ();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, surfaceY, z);
                var block = world.getBlockState(surface).getBlock();
                if (block == Blocks.GRASS_BLOCK || block == Blocks.SAND
                        || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        return changed;
    }

}
