package net.teyvat.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.item.ItemPlacementContext;

/**
 * Арка: поворачивается по горизонтали при установке, проём всегда смотрит
 * в сторону игрока (как дверь). Модель: проём на грани north, в blockstate
 * повороты y=0/90/180/270 с uvlock.
 */
public class MarbleArchBlock extends HorizontalFacingBlock {
    public MarbleArchBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends HorizontalFacingBlock> getCodec() {
        return createCodec(MarbleArchBlock::new);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
    }
}
