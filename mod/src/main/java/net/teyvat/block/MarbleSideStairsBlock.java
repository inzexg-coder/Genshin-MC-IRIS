package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * Горизонтальные ступени: блок делится на 4 вертикальных столбика 8x8,
 * один столбик удаляется (северо-западный в локальной модели). Получается
 * L-образный блок 3/4, 4 поворота через FACING. Коллизия совпадает с моделью.
 */
public class MarbleSideStairsBlock extends Block {
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    // Три оставшихся столбика (локальная модель, facing=south, y=0):
    // удалён северо-западный столбик [0..8]x[0..8].
    private static final VoxelShape LOCAL_SHAPE = VoxelShapes.union(
            Block.createCuboidShape(8.0, 0.0, 0.0, 16.0, 16.0, 8.0),   // СВ
            Block.createCuboidShape(8.0, 0.0, 8.0, 16.0, 16.0, 16.0), // ЮВ
            Block.createCuboidShape(0.0, 0.0, 8.0, 8.0, 16.0, 16.0)); // ЮЗ

    public MarbleSideStairsBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** Тот же поворот, что у модели в blockstate: south=0, west=90, north=180, east=270. */
    private VoxelShape shapeFor(BlockState state) {
        return rotateShape(Direction.SOUTH, state.get(FACING), LOCAL_SHAPE);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    /** Копия ванильного поворота VoxelShape (StairsBlock.rotateShape): согласовано с моделями. */
    private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        VoxelShape[] buffer = new VoxelShape[]{shape, VoxelShapes.empty()};
        int i = from.getHorizontalQuarterTurns() - to.getHorizontalQuarterTurns();
        int j = Math.floorMod(i, 4);
        for (int k = 0; k < j; k++) {
            buffer[0] = rotate90(buffer[0]);
            buffer[1] = VoxelShapes.empty();
        }
        return buffer[0];
    }

    private static VoxelShape rotate90(VoxelShape shape) {
        VoxelShape[] buffer = new VoxelShape[]{shape, VoxelShapes.empty()};
        shape.forEachBox((x1, y1, z1, x2, y2, z2) -> {
            buffer[1] = VoxelShapes.union(buffer[1],
                    Block.createCuboidShape(16.0 - z2, y1, x1, 16.0 - z1, y2, x2));
        });
        return buffer[1];
    }
}
