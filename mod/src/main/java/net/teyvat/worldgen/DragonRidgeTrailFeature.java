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

        // 1) Собрать поверхностные высоты блоков тропы (центр) для целевого уровня.
        int[] ys = new int[16 * 16];
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;
                if (count < ys.length) {
                    ys[count++] = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                }
            }
        }
        if (count == 0) return false;
        // Медианная высота = целевой уровень тропы
        int[] sample = new int[count];
        System.arraycopy(ys, 0, sample, 0, count);
        java.util.Arrays.sort(sample);
        int targetY = sample[count / 2];

        // 2) Для каждого блока тропы: выровнять поверхность до targetY,
        //    срезать выступающие блоки, поставить dirt_path.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                BlockPos surface = new BlockPos(x, targetY, z);
                // Убираем блоки выше целевого уровня (выступающие края)
                for (int dyUp = 0; dyUp <= 8; dyUp++) {
                    BlockPos up = new BlockPos(x, targetY + 1 + dyUp, z);
                    if (up.getY() > world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z)) break;
                    setBlockState(world, up, Blocks.AIR.getDefaultState());
                    changed = true;
                }

                var block = world.getBlockState(surface).getBlock();
                if (block != Blocks.DIRT_PATH && block != Blocks.AIR) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        return changed;
    }}
