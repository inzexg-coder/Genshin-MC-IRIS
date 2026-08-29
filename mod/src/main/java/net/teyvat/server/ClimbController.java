package net.teyvat.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.teyvat.network.ClimbSyncPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Карабканье по стенам как в Genshin, авторитетное на сервере.
 *
 *  - Зажимается Space (jump) рядом со стеной — игрок цепляется и ползёт вверх.
 *  - Пока игрок карабкается, стаминой владеет сервер: -8/сек непрерывно,
 *    прыжок от стены (ещё раз Space) стоит 20 и даёт рывок вверх.
 *  - Когда стамина кончилась — плавный спуск по стене (~1 сек), затем
 *    отпускание (обычная физика, падение).
 *  - Ввод (PlayerInput) сервер получает сам, поэтому фаервол между клиентом
 *    и сервером обмануть нельзя: скорость и стамина считаются на сервере.
 *
 * Физика не трогается миксинами: движение каждый тик задаётся напрямую
 * (setVelocity + velocityModified), поэтому ванильный прыжок/гравитация
 * остаются нетронутыми для остальных ситуаций.
 */
public final class ClimbController {
    /** Стоимость подъёма: 8 стамины в секунду (0.4 за тик). */
    public static final float CLIMB_DRAIN_PER_TICK = 8f / 20f;
    /** Стоимость прыжка от стены. */
    public static final float CLIMB_JUMP_COST = 20f;
    /** Скорость подъёма, блоков/тик (~0.45 — быстрее бега, но без рывка). */
    private static final double CLIMB_SPEED = 0.45;
    /** Скорость плавного спуска при пустой стамине, блоков/тик. */
    private static final double SLIDE_SPEED = -0.18;
    /** Длительность плавного спуска после срыва, тиков. */
    private static final int SLIDE_TICKS = 20;
    /** Радиус проверки стен (горизонтально). */
    private static final int WALL_SCAN_DIST = 1;
    /** Как часто синхронизировать стамину клиенту (тиков). */
    private static final int SYNC_INTERVAL = 4;
    /** Порог «у стены»: дистанция от центра игрока до блока стены. */
    private static final double WALL_TOUCH_DIST = 0.9;

    private static final Map<UUID, ClimbData> DATA = new HashMap<>();

    private ClimbController() {}

    static final class ClimbData {
        boolean climbing;
        boolean sliding;
        int slideTicksLeft;
        float stamina;
        Direction wallDir;
        int syncCooldown;
        boolean wasJumping;
    }

    /** Серверный тик: обновить карабканье всех игроков. */
    public static void tick(ServerPlayerEntity player) {
        ClimbData d = DATA.computeIfAbsent(player.getUuid(), id -> new ClimbData());
        World world = player.getEntityWorld();
        if (world == null || !player.isAlive()) {
            clear(player);
            return;
        }

        PlayerInput input = player.getPlayerInput();
        boolean jumpPressed = input != null && input.jump();
        BlockPos feet = player.getBlockPos();
        Direction wall = findWall(world, player, feet);

        boolean canClimb = jumpPressed && wall != null
                && !player.isSneaking()
                && !player.hasVehicle()
                && !player.getAbilities().flying
                && !player.isTouchingWater()
                && !player.isSubmergedInWater();

        if (d.climbing) {
            tickClimbing(player, d, canClimb, wall, jumpPressed);
        } else if (d.sliding) {
            tickSliding(player, d, canClimb);
        } else if (canClimb && d.stamina > 0f) {
            d.climbing = true;
            d.wallDir = wall;
            sync(player, d);
        }

        if (d.climbing || d.sliding) {
            if (--d.syncCooldown <= 0) {
                d.syncCooldown = SYNC_INTERVAL;
                sync(player, d);
            }
        }
    }

    private static void tickClimbing(ServerPlayerEntity player, ClimbData d,
                                     boolean canClimb, Direction wall, boolean jumpPressed) {
        if (!canClimb || wall == null) {
            // Отпустили Space, отошли от стены, сели/сел в воду — отпустить.
            release(player, d);
            return;
        }
        // Прыжок от стены: повторное Space во время карабканья (фронт нажатия).
        if (jumpPressed && !d.wasJumping && d.stamina >= CLIMB_JUMP_COST) {
            d.stamina -= CLIMB_JUMP_COST;
            Vec3d v = player.getVelocity();
            player.setVelocity(v.x, 0.9, v.z);
            player.velocityModified = true;
            release(player, d);
            return;
        }
        d.wasJumping = jumpPressed;

        // Стамина кончилась — плавный спуск.
        if (d.stamina <= 0f) {
            d.climbing = false;
            d.sliding = true;
            d.slideTicksLeft = SLIDE_TICKS;
            return;
        }

        // Подъём: тратим стамину и двигаем вверх. Горизонталь гасим (прилипание к стене).
        d.stamina = Math.max(0f, d.stamina - CLIMB_DRAIN_PER_TICK);
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x * 0.2, CLIMB_SPEED, v.z * 0.2);
        player.velocityModified = true;
    }

    private static void tickSliding(ServerPlayerEntity player, ClimbData d, boolean canClimb) {
        if (canClimb && d.stamina > 0f) {
            // Снова зажали Space с достаточной стаминой — снова цепляемся.
            d.sliding = false;
            d.climbing = true;
            d.wasJumping = true;
            return;
        }
        if (--d.slideTicksLeft <= 0) {
            release(player, d);
            return;
        }
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x * 0.2, SLIDE_SPEED, v.z * 0.2);
        player.velocityModified = true;
    }

    /** Остановить карабканье/спуск: обычная физика дальше. */
    private static void release(ServerPlayerEntity player, ClimbData d) {
        d.climbing = false;
        d.sliding = false;
        d.slideTicksLeft = 0;
        sync(player, d);
    }

    private static void sync(ServerPlayerEntity player, ClimbData d) {
        if (player.networkHandler == null) {
            return;
        }
        ServerPlayNetworking.send(player, new ClimbSyncPayload(d.climbing, d.sliding, d.stamina));
    }

    /** Клиент передал свою текущую стамину (база для серверной траты). */
    public static void onClientStamina(ServerPlayerEntity player, float stamina) {
        ClimbData d = DATA.computeIfAbsent(player.getUuid(), id -> new ClimbData());
        d.stamina = Math.max(0f, Math.min(150f, stamina));
    }

    /** Покинул мир / умер — очистить состояние. */
    public static void clear(ServerPlayerEntity player) {
        DATA.remove(player.getUuid());
    }

    /** Ищет рядом сплошной блок-стену на высоте тела (feet..feet+1). */
    private static Direction findWall(World world, ServerPlayerEntity player, BlockPos feet) {
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (isWallBlock(world, side) || isWallBlock(world, side.up())) {
                // Дистанция от центра игрока до боковой грани блока.
                double dx = dir.getOffsetX() * 0.5;
                double dz = dir.getOffsetZ() * 0.5;
                double dist = Math.abs(player.getX() - (feet.getX() + 0.5 + dx))
                        + Math.abs(player.getZ() - (feet.getZ() + 0.5 + dz));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = dir;
                }
            }
        }
        if (bestDist <= WALL_TOUCH_DIST) {
            return best;
        }
        return null;
    }

    private static boolean isWallBlock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.isOpaqueFullCube() && state.getFluidState().isEmpty();
    }
}
