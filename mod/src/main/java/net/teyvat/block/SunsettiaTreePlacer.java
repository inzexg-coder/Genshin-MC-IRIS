package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.teyvat.TeyvatWood;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Рисует дерево Закатника. 6 стилей (как у дубов обычный/fancy):
 * COMPACT, LARGE, TALL, SPRAWL, TOWER, BABY. Внутри стиля случайность.
 */
public final class SunsettiaTreePlacer {
    private SunsettiaTreePlacer() {}

    public enum Style {
        COMPACT, LARGE, TALL, SPRAWL, TOWER, BABY
    }

    public static boolean grow(StructureWorldAccess world, BlockPos base, Random random) {
        Style style = Style.values()[random.nextInt(Style.values().length)];
        return grow(world, base, random, style);
    }

    public static boolean grow(StructureWorldAccess world, BlockPos base, Random random, Style style) {
        if (world == null || base == null) return false;

        int trunkMin, trunkMax, mid, layersCount;
        float cornerSkip;
        int[][] layers;

        switch (style) {
            case LARGE -> {
                trunkMin = 5; trunkMax = 7; mid = 3;
                layers = new int[][]{{1, 2}, {0, 3}, {-1, 3}, {-2, 2}};
                cornerSkip = 0.1f;
            }
            case TALL -> {
                trunkMin = 7; trunkMax = 9; mid = 2;
                layers = new int[][]{{2, 1}, {1, 1}, {0, 2}, {-1, 2}, {-2, 1}};
                cornerSkip = 0.2f;
            }
            case SPRAWL -> {
                trunkMin = 3; trunkMax = 5; mid = 3;
                layers = new int[][]{{1, 2}, {0, 3}, {-1, 2}};
                cornerSkip = 0.25f;
            }
            case TOWER -> {
                trunkMin = 8; trunkMax = 10; mid = 1;
                layers = new int[][]{{3, 1}, {2, 1}, {1, 2}, {0, 2}, {-1, 1}};
                cornerSkip = 0.15f;
            }
            case BABY -> {
                trunkMin = 3; trunkMax = 4; mid = 1;
                layers = new int[][]{{1, 1}, {0, 1}, {-1, 1}};
                cornerSkip = 0.1f;
            }
            default -> { // COMPACT
                trunkMin = 4; trunkMax = 6; mid = 2;
                layers = new int[][]{{1, 1}, {0, 2}, {-1, 2}, {-2, 1}};
                cornerSkip = 0.15f;
            }
        }

        int trunkHeight = trunkMin + random.nextInt(trunkMax - trunkMin + 1);
        BlockPos root = base;
        boolean changed = false;
        BlockState log = TeyvatWood.SUNSETTIA_LOG.getDefaultState();
        BlockState leaves = TeyvatWood.SUNSETTIA_LEAVES.getDefaultState();
        BlockState fruit = TeyvatWood.SUNSETTIA_FRUIT.getDefaultState();

        for (int i = 0; i < trunkHeight + 1; i++) {
            BlockPos p = root.up(i);
            if (i == 0 || world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                world.setBlockState(p, log, Block.NOTIFY_ALL);
                changed = true;
            } else {
                trunkHeight = i;
                break;
            }
        }
        if (trunkHeight < 3) return changed;

        BlockPos top = root.up(trunkHeight - 1);

        for (int[] layer : layers) {
            int dy = layer[0];
            int r = layer[1];
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    if (dx != 0 && dz != 0 && random.nextFloat() < cornerSkip) continue;
                    BlockPos p = top.up(dy).add(dx, 0, dz);
                    if (world.isAir(p) || world.getBlockState(p).getBlock().getDefaultState().isReplaceable()) {
                        world.setBlockState(p, leaves, Block.NOTIFY_ALL);
                        changed = true;
                    }
                }
            }
        }

        // Плоды растут на нижнем уровне листвы (минимальный dy среди слоёв)
        int bottomDy = Integer.MAX_VALUE;
        for (int[] layer : layers) {
            bottomDy = Math.min(bottomDy, layer[0]);
        }
        BlockPos canopyBottom = top.up(bottomDy);

        int fruitCount = random.nextInt(3);
        List<Direction> dirs = new ArrayList<>(List.of(
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        Collections.shuffle(dirs, new java.util.Random(random.nextLong()));
        int placed = 0;
        for (Direction dir : dirs) {
            if (placed >= fruitCount) break;
            // Плод свисает под нижней листвой, offset = радиус нижнего слоя + 1
            BlockPos p = canopyBottom.offset(dir, mid);
            if (world.isAir(p)) {
                world.setBlockState(p, fruit, Block.NOTIFY_ALL);
                placed++;
                changed = true;
            }
        }

        return changed;
    }
}
