package net.teyvat.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.SaplingGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Саженец Закатника: растёт в среднее дерево с плодами.
 * Используется тот же пласер, что и при генерации мира.
 */
public class SunsettiaSaplingBlock extends SaplingBlock {
    public SunsettiaSaplingBlock(AbstractBlock.Settings settings) {
        super(SaplingGenerator.OAK, settings);
    }

    @Override
    public void generate(ServerWorld world, BlockPos pos, BlockState state, Random random) {
        // Саженец на земле: удаляем саженец и рисуем дерево от этой точки.
        if (!world.getBlockState(pos.up()).isAir()) {
            return; // некуда расти
        }
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        if (!SunsettiaTreePlacer.grow(world, pos, random)) {
            // не выросло (слишком тесно) — возвращаем саженец
            world.setBlockState(pos, state, 3);
        }
    }
}
