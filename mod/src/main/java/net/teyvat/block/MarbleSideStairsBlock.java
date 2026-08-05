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
 * один столбик удаляется. 4 формы предвычислены явно под 4 поворота
 * блок-модели (south=0, west=90, north=180, east=270) — без вращения на лету,
 * коллизия гарантированно совпадает с моделью при любом повороте.
 */
public class MarbleSideStairsBlock extends Block {
    public static final EnumProperty<Direction> FACING = Properties.HORIZONTAL_FACING;

    // Локальная модель (facing=south, y=0): удалён северо-западный столбик [0..8]x[0..8].
    private static final VoxelShape SHAPE_SOUTH = VoxelShapes.union(
            Block.createCuboidShape(8.0, 0.0, 0.0, 16.0, 16.0, 8.0),   // СВ
            Block.createCuboidShape(8.0, 0.0, 8.0, 16.0, 16.0, 16.0), // ЮВ
            Block.createCuboidShape(0.0, 0.0, 8.0, 8.0, 16.0, 16.0)); // ЮЗ

    // facing=west (y=90): удалён северо-восточный столбик.
    private static final VoxelShape SHAPE_WEST = VoxelShapes.union(
            Block.createCuboidShape(0.0, 0.0, 0.0, 8.0, 16.0, 8.0),    // СЗ
            Block.createCuboidShape(0.0, 0.0, 8.0, 8.0, 16.0, 16.0),  // ЮЗ
            Block.createCuboidShape(8.0, 0.0, 8.0, 16.0, 16.0, 16.0));// ЮВ

    // facing=north (y=180): удалён юго-восточный столбик.
    private static final VoxelShape SHAPE_NORTH = VoxelShapes.union(
            Block.createCuboidShape(0.0, 0.0, 0.0, 8.0, 16.0, 8.0),    // СЗ
            Block.createCuboidShape(8.0, 0.0, 0.0, 16.0, 16.0, 8.0),  // СВ
            Block.createCuboidShape(0.0, 0.0, 8.0, 8.0, 16.0, 16.0)); // ЮЗ

    // facing=east (y=270): удалён юго-западный столбик.
    private static final VoxelShape SHAPE_EAST = VoxelShapes.union(
            Block.createCuboidShape(0.0, 0.0, 0.0, 8.0, 16.0, 8.0),    // СЗ
            Block.createCuboidShape(8.0, 0.0, 0.0, 16.0, 16.0, 8.0),  // СВ
            Block.createCuboidShape(8.0, 0.0, 8.0, 16.0, 16.0, 16.0));// ЮВ

    public MarbleSideStairsBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.SOUTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    private VoxelShape shapeFor(BlockState state) {
        return switch (state.get(FACING)) {
            case WEST -> SHAPE_WEST;
            case NORTH -> SHAPE_NORTH;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_SOUTH;
        };
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }
}
