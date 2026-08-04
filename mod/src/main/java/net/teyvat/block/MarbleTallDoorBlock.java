package net.teyvat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SideShapeType;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.block.WireOrientation;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.tick.ScheduledTickView;

import net.teyvat.TeyvatBlockEntities;
import net.teyvat.block.entity.MarbleTallDoorBlockEntity;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Мраморная дверь Тейвата: 3 блока в высоту, толстое полотно, медленное открытие.
 * Каждый из трёх сегментов — отдельный блок с общими свойствами FACING/HINGE/OPEN/THIRD.
 * Визуал рисует MarbleTallDoorRenderer (плавная анимация через BlockEntity).
 */
public class MarbleTallDoorBlock extends Block implements BlockEntityProvider {
    public static final float THICKNESS = 5.0f;

    public static final EnumProperty<Direction> FACING = HorizontalFacingBlock.FACING;
    public static final BooleanProperty OPEN = Properties.OPEN;
    public static final EnumProperty<DoorHinge> HINGE = Properties.DOOR_HINGE;
    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final EnumProperty<Third> THIRD = EnumProperty.of("third", Third.class);

    private static final VoxelShape NORTH_SHAPE = Block.createCuboidShape(0, 0, 0, 16, 16, THICKNESS);
    private static final VoxelShape SOUTH_SHAPE = Block.createCuboidShape(0, 0, 16 - THICKNESS, 16, 16, 16);
    private static final VoxelShape EAST_SHAPE = Block.createCuboidShape(16 - THICKNESS, 0, 0, 16, 16, 16);
    private static final VoxelShape WEST_SHAPE = Block.createCuboidShape(0, 0, 0, THICKNESS, 16, 16);
    private static final Map<Direction, VoxelShape> SHAPES_BY_DIRECTION = Map.of(
            Direction.NORTH, NORTH_SHAPE, Direction.SOUTH, SOUTH_SHAPE,
            Direction.EAST, EAST_SHAPE, Direction.WEST, WEST_SHAPE);

    public enum Third implements StringIdentifiable {
        LOWER("lower"), MIDDLE("middle"), UPPER("upper");

        private final String name;

        Third(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        public Third previous() {
            return switch (this) {
                case MIDDLE -> Third.LOWER;
                case UPPER -> Third.MIDDLE;
                default -> null;
            };
        }
    }

    public MarbleTallDoorBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(OPEN, false)
                .with(HINGE, DoorHinge.LEFT)
                .with(POWERED, false)
                .with(THIRD, Third.LOWER));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, HINGE, POWERED, THIRD);
    }

    // ---------- placement ----------

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        if (pos.getY() >= world.getTopYInclusive() - 1) {
            return null;
        }
        BlockState above = world.getBlockState(pos.up());
        BlockState above2 = world.getBlockState(pos.up(2));
        if (!above.canReplace(ctx) || !above2.canReplace(ctx)) {
            return null;
        }
        boolean powered = world.isReceivingRedstonePower(pos)
                || world.isReceivingRedstonePower(pos.up())
                || world.isReceivingRedstonePower(pos.up(2));
        return this.getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing())
                .with(HINGE, this.getHinge(ctx))
                .with(OPEN, powered)
                .with(POWERED, powered)
                .with(THIRD, Third.LOWER);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        world.setBlockState(pos.up(), state.with(THIRD, Third.MIDDLE), 3);
        world.setBlockState(pos.up(2), state.with(THIRD, Third.UPPER), 3);
    }

    private DoorHinge getHinge(ItemPlacementContext ctx) {
        BlockView world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        Direction facing = ctx.getHorizontalPlayerFacing();
        BlockPos upPos = pos.up();
        Direction ccw = facing.rotateYCounterclockwise();
        BlockPos ccwPos = pos.offset(ccw);
        BlockState ccwState = world.getBlockState(ccwPos);
        BlockPos ccwUpPos = upPos.offset(ccw);
        BlockState ccwUpState = world.getBlockState(ccwUpPos);
        Direction cw = facing.rotateYClockwise();
        BlockPos cwPos = pos.offset(cw);
        BlockState cwState = world.getBlockState(cwPos);
        BlockPos cwUpPos = upPos.offset(cw);
        BlockState cwUpState = world.getBlockState(cwUpPos);
        int i = (ccwState.isFullCube(world, ccwPos) ? -1 : 0)
                + (ccwUpState.isFullCube(world, ccwUpPos) ? -1 : 0)
                + (cwState.isFullCube(world, cwPos) ? 1 : 0)
                + (cwUpState.isFullCube(world, cwUpPos) ? 1 : 0);
        boolean ccwDoor = ccwState.getBlock() instanceof DoorBlock && ccwState.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        boolean cwDoor = cwState.getBlock() instanceof DoorBlock && cwState.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        if ((!ccwDoor || cwDoor) && i <= 0) {
            if ((!ccwDoor && cwDoor) || i < 0) {
                return DoorHinge.RIGHT;
            }
        } else {
            return DoorHinge.RIGHT;
        }
        int ox = facing.getOffsetX();
        int oz = facing.getOffsetZ();
        Vec3d hitPos = ctx.getHitPos();
        double dx = hitPos.x - (double) pos.getX();
        double dz = hitPos.z - (double) pos.getZ();
        double d = oz < 0 ? dx : (oz > 0 ? 1.0 - dx : (ox < 0 ? dz : 1.0 - dz));
        return d < 0.5 ? DoorHinge.LEFT : DoorHinge.RIGHT;
    }

    // ---------- structure integrity (3 сегмента) ----------

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, WorldView world, ScheduledTickView tickView,
            BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, Random random) {
        Third third = state.get(THIRD);
        if (direction.getAxis() == Direction.Axis.Y) {
            boolean valid = switch (third) {
                case LOWER -> direction == Direction.UP && isPart(neighborState, Third.MIDDLE);
                case MIDDLE -> (direction == Direction.DOWN && isPart(neighborState, Third.LOWER))
                        || (direction == Direction.UP && isPart(neighborState, Third.UPPER));
                case UPPER -> direction == Direction.DOWN && isPart(neighborState, Third.MIDDLE);
            };
            if (!valid) {
                return Blocks.AIR.getDefaultState();
            }
            return state;
        }
        if (third == Third.LOWER && direction == Direction.DOWN && !state.canPlaceAt(world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, world, tickView, pos, direction, neighborPos, neighborState, random);
    }

    private static boolean isPart(BlockState state, Third third) {
        return state.getBlock() instanceof MarbleTallDoorBlock && state.get(THIRD) == third;
    }

    @Override
    protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Third third = state.get(THIRD);
        if (third != Third.LOWER) {
            BlockState below = world.getBlockState(pos.down());
            return below.getBlock() == this && below.get(THIRD) == third.previous();
        }
        BlockPos belowPos = pos.down();
        return world.getBlockState(belowPos).isSideSolid(world, belowPos, Direction.UP, SideShapeType.FULL);
    }

    // ---------- breaking ----------

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient() && !player.shouldSkipBlockDrops() && !player.canHarvest(state)) {
            this.breakWholeDoor(world, pos);
        }
        return super.onBreak(world, pos, state, player);
    }

    @Override
    protected void onExploded(BlockState state, ServerWorld world, BlockPos pos, Explosion explosion,
            BiConsumer<ItemStack, BlockPos> stackMerger) {
        super.onExploded(state, world, pos, explosion, stackMerger);
        this.breakWholeDoor(world, pos);
    }

    private void breakWholeDoor(World world, BlockPos pos) {
        BlockPos lower = getLowerPos(world, pos);
        for (int i = 0; i < 3; i++) {
            BlockPos p = lower.up(i);
            if (world.getBlockState(p).getBlock() == this) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
    }

    private BlockPos getLowerPos(World world, BlockPos pos) {
        Third third = world.getBlockState(pos).get(THIRD);
        return switch (third) {
            case MIDDLE -> pos.down();
            case UPPER -> pos.down(2);
            default -> pos;
        };
    }

    // ---------- opening / closing ----------

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        this.setOpen(player, world, state, pos, !state.get(OPEN));
        return ActionResult.SUCCESS;
    }

    @Override
    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block block,
            WireOrientation wireOrientation, boolean moved) {
        boolean powered = world.isReceivingRedstonePower(pos);
        if (!this.getDefaultState().isOf(block) && powered != state.get(POWERED)) {
            BlockPos lower = this.getLowerPos(world, pos);
            for (int i = 0; i < 3; i++) {
                BlockPos p = lower.up(i);
                BlockState part = world.getBlockState(p);
                if (part.isOf(this)) {
                    world.setBlockState(p, part.with(POWERED, powered).with(OPEN, powered), 10);
                }
            }
            this.playOpenCloseSound(world, lower, powered);
            world.emitGameEvent(null, powered ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, lower);
        }
    }

    public void setOpen(Entity entity, World world, BlockState state, BlockPos pos, boolean open) {
        if (state.isOf(this) && state.get(OPEN) != open) {
            BlockPos lower = getLowerPos(world, pos);
            for (int i = 0; i < 3; i++) {
                BlockPos p = lower.up(i);
                BlockState partState = world.getBlockState(p);
                if (partState.isOf(this) && partState.get(OPEN) != open) {
                    world.setBlockState(p, partState.with(OPEN, open), 10);
                }
            }
            this.playOpenCloseSound(world, lower, open);
            world.emitGameEvent(entity, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, lower);
        }
    }

    private void playOpenCloseSound(World world, BlockPos pos, boolean open) {
        BlockSetType type = BlockSetType.STONE;
        world.playSound(null, pos, open ? type.doorOpen() : type.doorClose(),
                SoundCategory.BLOCKS, 1.0f, world.getRandom().nextFloat() * 0.1f + 0.9f);
    }

    // ---------- shapes / misc ----------

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction facing = state.get(FACING);
        Direction dir;
        if (state.get(OPEN)) {
            // открытая дверь лежит вдоль боковой стены: left hinge -> левее по ходу взгляда
            dir = state.get(HINGE) == DoorHinge.LEFT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
        } else {
            // закрытое полотно стоит на дальней стороне дверного проёма
            dir = facing.getOpposite();
        }
        return SHAPES_BY_DIRECTION.get(dir);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return this.getOutlineShape(state, world, pos, context);
    }

    @Override
    protected boolean canPathfindThrough(BlockState state, NavigationType type) {
        return !state.get(OPEN);
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    // ---------- block entity ----------

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new MarbleTallDoorBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (type != TeyvatBlockEntities.MARBLE_TALL_DOOR) {
            return null;
        }
        return world.isClient() ? (BlockEntityTicker<T>) (BlockEntityTicker<MarbleTallDoorBlockEntity>) MarbleTallDoorBlockEntity::clientTick : null;
    }
}
