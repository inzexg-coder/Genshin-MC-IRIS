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
import net.teyvat.TeyvatBlocks;
import net.teyvat.TeyvatMod;

/** Тропа: ровная, без выступающих краёв. Заменяет блоки на тропе и рядом.
 *  Также строит точку телепортации, когда генерируется чанк у начала тропы. */
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
                double fade = TeyvatDragonRidge.trailFadeFactor(x, z);
                if (fade <= -0.3) continue;

                int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos surface = new BlockPos(x, surfaceY, z);
                var block = world.getBlockState(surface).getBlock();

                if (block == Blocks.GRASS_BLOCK || block == Blocks.SAND || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS) {
                    setBlockState(world, surface, Blocks.DIRT_PATH.getDefaultState());
                    changed = true;
                }
            }
        }

        // Точка телепортации: строим в чанке, содержащем начало тропы (TRAILHEAD).
        // Feature работает только в пределах текущего чанка — не форсирует генерацию
        // соседних чанков, поэтому безопасно (не вызывает бесконечную загрузку).
        if (chunkPos.x == TeyvatDragonRidge.TRAILHEAD_X >> 4
                && chunkPos.z == TeyvatDragonRidge.TRAILHEAD_Z >> 4) {
            changed |= buildTeleportAt(context, TeyvatDragonRidge.TRAILHEAD_X, TeyvatDragonRidge.TRAILHEAD_Z);
        }

        return changed;
    }

    /** Компактно строит точку телепортации на высоте поверхности текущего чанка.
     *  Не выходит за границы чанка — использует только TOPMOST heightmap. */
    private boolean buildTeleportAt(FeatureContext<DefaultFeatureConfig> context, int x, int z) {
        var world = context.getWorld();
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        BlockPos center = new BlockPos(x, y, z);
        boolean changed = false;

        // Плита (красная) в центре
        setBlockState(world, center, TeyvatBlocks.TELEPORT_SLAB_RED.getDefaultState());
        // Основание, ствол, капитель колонны над плитой
        setBlockState(world, center.up(1), TeyvatBlocks.TELEPORT_COLUMN_BASE_RED.getDefaultState());
        setBlockState(world, center.up(2), TeyvatBlocks.TELEPORT_COLUMN_SHAFT_RED.getDefaultState());
        setBlockState(world, center.up(3), TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED.getDefaultState());
        changed = true;
        return changed;
    }
}
