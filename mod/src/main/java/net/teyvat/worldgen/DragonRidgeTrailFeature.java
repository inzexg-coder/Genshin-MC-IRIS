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

/** Тропа: ровная, без выступающих краёв. Заменяет блоки на тропе и рядом. */
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

        // 1) Эталонная высота тропы: берём высоту поверхности в точке НА центральной
        //    линии тропы (максимальный fade) — все блоки тропы в чанке ровняем на неё,
        //    чтобы стыки между чанками совпадали (долина непрерывна вдоль тропы).
        int targetY = -1;
        double bestFade = -1.0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;
                if (fade > bestFade) {
                    bestFade = fade;
                    targetY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                }
            }
        }
        if (targetY < 0) return false;

        // 2) Подровнять блоки тропы под целевую высоту + поставить dirt_path.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                // Срезаем выступы выше целевого уровня
                if (surfaceY > targetY) {
                    for (int dy = targetY + 1; dy <= surfaceY; dy++) {
                        setBlockState(world, new BlockPos(x, dy, z), Blocks.AIR.getDefaultState());
                        changed = true;
                    }
                } else if (surfaceY < targetY) {
                    // Заполняем ямы до целевого уровня
                    for (int dy = surfaceY + 1; dy <= targetY; dy++) {
                        setBlockState(world, new BlockPos(x, dy, z), Blocks.DIRT.getDefaultState());
                        changed = true;
                    }
                }

                BlockPos surface = new BlockPos(x, targetY, z);
                var block = world.getBlockState(surface).getBlock();
                if (block != Blocks.DIRT_PATH && block != Blocks.AIR) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        return changed;
    }

}
