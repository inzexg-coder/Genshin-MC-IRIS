package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;


/**
 * Плод Закатника, растёт в кроне дерева (на листве/стволе).
 * При добыче выпадает 1-2 Закатника. Сам по себе не растёт и не сыпется.
 */
public class SunsettiaFruitBlock extends Block {
    private static final VoxelShape SHAPE = VoxelShapes.cuboid(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

    public SunsettiaFruitBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    /** Плод не падает: он держится в кроне, даже если опора исчезла. */
    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockPos down = pos.down();
        BlockState below = world.getBlockState(down);
        if (below.isAir()) return false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (!neighbor.isOf(Blocks.AIR)) {
                return true;
            }
        }
        return false;
    }
}
