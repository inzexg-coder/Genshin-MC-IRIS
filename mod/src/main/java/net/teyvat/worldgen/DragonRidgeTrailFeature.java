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
 *  (шум вдоль тропы выключен, профиль константен), поэтому здесь мы только
 *  подменяем поверхностные блоки на dirt_path. НИКАКИХ тяжёлых циклов по getTopY
 *  и выравнивания — именно они вызывали бесконечную "Загрузку территории". */
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

        // Точка телепортации: строим в чанке, содержащем начало тропы (TRAILHEAD),
        // прямо при генерации этого чанка. Это надёжный путь — точка появляется
        // сразу, без серверного тика и без ожидания, пока игрок подойдёт вплотную.
        // Работаем только в пределах текущего чанка (без форсирования соседних),
        // поэтому не замедляет загрузку и не вызывает бесконечную генерацию.
        if (chunkPos.x == TeyvatDragonRidge.TRAILHEAD_X >> 4
                && chunkPos.z == TeyvatDragonRidge.TRAILHEAD_Z >> 4) {
            changed |= buildTeleportAt(context, TeyvatDragonRidge.TRAILHEAD_X, TeyvatDragonRidge.TRAILHEAD_Z);
        }

        return changed;
    }

    /** Компактно строит точку телепортации на высоте поверхности текущего чанка.
     *  Использует только TOPMOST heightmap и не выходит за границы чанка. */
    private boolean buildTeleportAt(FeatureContext<DefaultFeatureConfig> context, int x, int z) {
        var world = context.getWorld();
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        BlockPos center = new BlockPos(x, y, z);
        boolean changed = false;

        setBlockState(world, center, net.teyvat.TeyvatBlocks.TELEPORT_SLAB_RED.getDefaultState());
        setBlockState(world, center.up(1), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_BASE_RED.getDefaultState());
        setBlockState(world, center.up(2), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_SHAFT_RED.getDefaultState());
        setBlockState(world, center.up(3), net.teyvat.TeyvatBlocks.TELEPORT_COLUMN_CAPITAL_RED.getDefaultState());
        changed = true;
        return changed;
    }
}
