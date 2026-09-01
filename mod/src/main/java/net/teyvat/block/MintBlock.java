package net.teyvat.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;

/**
 * Мята — декоративный цветок. Растёт на траве/земле, ломается мгновенно.
 * Модель — cross (как у ванильных цветов: poppy, dandelion).
 */
public class MintBlock extends Block {
    private static final VoxelShape SHAPE = VoxelShapes.cuboid(0.1875, 0, 0.1875, 0.8125, 0.625, 0.8125);

    public MintBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState soil = world.getBlockState(below);
        return soil.isIn(BlockTags.DIRT) || soil.isIn(BlockTags.SAND)
                || soil.isIn(BlockTags.BASE_STONE_OVERWORLD);
    }
}
