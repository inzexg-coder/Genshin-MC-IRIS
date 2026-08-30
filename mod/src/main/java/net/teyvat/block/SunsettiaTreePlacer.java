package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.teyvat.TeyvatWood;

/**
 * Рисует дерево Закатника: средний ствол (4-6 блоков), округлая крона из
 * листвы, 3-5 плодов в кроне. Используется и генерацией мира, и ростом
 * саженца. Плоды кладутся сбоку на ветви кроны.
 */
public final class SunsettiaTreePlacer {
    private SunsettiaTreePlacer() {}

    /**
     * Пытается вырастить дерево с корнем в baseY (низ ствола).
     * Возвращает true, если хотя бы что-то поставили.
     */
    public static boolean grow(StructureWorldAccess world, BlockPos base, Random random) {
        if (world == null || base == null) return false;

        int trunkHeight = 4 + random.nextInt(3); // 4..6
        BlockPos root = base; // нижний блок ствола на уровне земли
        boolean changed = false;
        BlockState log = TeyvatWood.SUNSETTIA_LOG.getDefaultState();
        BlockState leaves = TeyvatWood.SUNSETTIA_LEAVES.getDefaultState();
        BlockState fruit = TeyvatWood.SUNSETTIA_FRUIT.getDefaultState();

        // Ствол
        for (int i = 0; i < trunkHeight; i++) {
            BlockPos p = root.up(i);
            if (world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                world.setBlockState(p, log, Block.NOTIFY_ALL);
                changed = true;
            } else {
                trunkHeight = i;
                break;
            }
        }
        if (trunkHeight < 3) return changed;

        // Крона: сфера вокруг вершины ствола
        BlockPos top = root.up(trunkHeight - 1);
        int radius = 2;
        for (int dy = -1; dy <= 1; dy++) {
            int r = radius + random.nextInt(2) - (dy == 0 ? 0 : 1); // низ/верх чуть уже
            if (r < 1) r = 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    BlockPos p = top.up(dy).add(dx, 0, dz);
                    if (world.isAir(p)) {
                        world.setBlockState(p, leaves, Block.NOTIFY_ALL);
                        changed = true;
                    }
                }
            }
        }

        // Плоды: по бокам кроны на внешних блоках листвы (не в стволе)
        int placed = 0;
        int attempts = 0;
        while (placed < 4 && attempts < 24) {
            attempts++;
            int dx = random.nextInt(5) - 2;
            int dz = random.nextInt(5) - 2;
            int dy = random.nextInt(3) - 1;
            BlockPos p = top.up(dy).add(dx, 0, dz);
            if (dx == 0 && dz == 0) continue;
            if (!world.getBlockState(p).isOf(TeyvatWood.SUNSETTIA_LEAVES)) continue;
            // Вешаем на соседнюю позицию сбоку, где воздух
            boolean hung = false;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos side = p.offset(dir);
                if (world.isAir(side)) {
                    world.setBlockState(side, fruit, Block.NOTIFY_ALL);
                    hung = true;
                    break;
                }
            }
            if (hung) placed++;
        }

        return changed;
    }
}
