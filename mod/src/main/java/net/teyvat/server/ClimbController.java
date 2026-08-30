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
    /** Вертикаль прыжка от стены (вперёд-вверх, невысокий подскок). */
    private static final double CLIMB_JUMP_UP = 0.5;
    /** Горизонталь прыжка от стены вперёд — короткий бросок. */
    private static final double CLIMB_JUMP_SPEED = 0.32;
    /** Отталкивание назад при спрыгивании: лишь чуть отойти от стены. */
    private static final double BACK_JUMP_SPEED = 0.22;
    /** Вертикаль при отталкивании назад (лёгкий прыжок, не падение). */
    private static final double BACK_JUMP_UP = 0.3;
    /** Длительность бокового рывка вдоль стены, тиков. */
    private static final int SIDESTEP_TICKS = 5;
    /** Скорость бокового рывка вдоль стены, блоков/тик. */
    private static final double SIDESTEP_SPEED = 0.35;
    /** Вертикаль бокового рывка (лёгкая дуга, не подскок). */
    private static final double SIDESTEP_UP = 0.25;
    /** Скорость подъёма, блоков/тик (чуть медленнее бега). */
    private static final double CLIMB_SPEED = 0.38;
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
        boolean paused;
        int sidestepTicks;
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

        // Рядом со стеной (для продолжения подъёма/спуска).
        boolean onWall = wall != null
                && !player.isSneaking()
                && !player.hasVehicle()
                && !player.getAbilities().flying
                && !player.isTouchingWater()
                && !player.isSubmergedInWater();
        // Старт карабканья — только стена высотой >= 2 блоков. Один блок
        // игрок перешагивает обычным бегом (ванильный авто-прыжок).
        boolean canClimb = onWall && jumpPressed && isStartableWall(world, feet, wall);

        if (d.climbing) {
            if (d.sidestepTicks > 0) {
                tickSidestep(player, d, onWall, jumpPressed);
            } else {
                tickClimbing(player, d, onWall, wall, jumpPressed);
            }
        } else if (d.sliding) {
            tickSliding(player, d, onWall);
        } else if (canClimb && d.stamina > 0f) {
            d.climbing = true;
            d.wallDir = wall;
            // Space уже зажат (он запустил карабканье) — повторное нажатие
            // считается только когда игрок отпустит и нажмёт снова.
            d.wasJumping = true;
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
                                     boolean onWall, Direction wall, boolean jumpPressed) {
        if (!onWall || wall == null) {
            // Отошли от стены, сели/сел в воду — отпустить.
            release(player, d);
            return;
        }
        if (!jumpPressed) {
            // Отпустили Space — зависаем на стене на текущей высоте.
            // Стамина в паузе не тратится и не восстанавливается.
            d.paused = true;
            d.wasJumping = false;
            // Никакого бокового смещения в паузе — висим на месте.
            player.setVelocity(0, 0, 0);
            player.velocityModified = true;
            return;
        }
        if (d.paused) {
            // Снова нажали Space после паузы.
            d.paused = false;
            // Если при этом движемся — рывок от стены (20 стамины), как в Genshin.
            // Без движения — просто продолжаем подъём.
            if (d.stamina >= CLIMB_JUMP_COST && hasMovementInput(player)) {
                d.stamina -= CLIMB_JUMP_COST;
                if (isStrafeOnly(player)) {
                    // Вбок: небольшой отрыв от стены и прилипание уже в стороне.
                    Vec3d j = sidestepVelocity(player, d);
                    d.sidestepTicks = SIDESTEP_TICKS;
                    player.setVelocity(j.x, j.y, j.z);
                    player.velocityModified = true;
                    return;
                }
                Vec3d j = climbJumpVelocity(player);
                player.setVelocity(j.x, j.y, j.z);
                player.velocityModified = true;
                release(player, d);
                return;
            }
            // Продолжаем подъём: следующий ход вниз уходит в активное карабканье.
            d.wasJumping = true;
        }

        // Стамина кончилась — плавный спуск.
        if (d.stamina <= 0f) {
            d.climbing = false;
            d.sliding = true;
            d.slideTicksLeft = SLIDE_TICKS;
            return;
        }

        // Подъём: тратим стамину и двигаем строго вверх. Горизонталь обнуляем —
        // вдоль стены двигаться нельзя (только рывок вбок, см. sidestep).
        d.wasJumping = jumpPressed;
        d.stamina = Math.max(0f, d.stamina - CLIMB_DRAIN_PER_TICK);
        player.setVelocity(0, CLIMB_SPEED, 0);
        player.velocityModified = true;
    }

    /** Боковой рывок вдоль стены: короткий полёт, затем прилипание в стороне. */
    private static void tickSidestep(ServerPlayerEntity player, ClimbData d,
                                     boolean onWall, boolean jumpPressed) {
        if (--d.sidestepTicks <= 0) {
            d.sidestepTicks = 0;
            if (onWall && d.stamina > 0f) {
                // Прилипаем обратно к стене и продолжаем карабкаться.
                d.paused = false;
                d.wasJumping = jumpPressed;
                if (d.wallDir != null) {
                    // Лёгкий толчок вплотную к стене (коллизия доведёт).
                    Vec3d v = player.getVelocity();
                    double ax = d.wallDir.getOffsetX();
                    double az = d.wallDir.getOffsetZ();
                    player.setVelocity(v.x + ax * 0.25, v.y, v.z + az * 0.25);
                    player.velocityModified = true;
                }
            } else {
                release(player, d);
            }
        }
    }

    private static void tickSliding(ServerPlayerEntity player, ClimbData d, boolean onWall) {
        if (onWall && d.stamina > 0f) {
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
        // Сползаем строго вниз вдоль стены, без бокового смещения.
        player.setVelocity(0, SLIDE_SPEED, 0);
        player.velocityModified = true;
    }

    /** Остановить карабканье/спуск: обычная физика дальше. */
    private static void release(ServerPlayerEntity player, ClimbData d) {
        d.climbing = false;
        d.sliding = false;
        d.paused = false;
        d.slideTicksLeft = 0;
        sync(player, d);
    }

    /** Зажато ли какое-то движение (для рывка от стены по направлению). */
    private static boolean hasMovementInput(ServerPlayerEntity player) {
        PlayerInput in = player.getPlayerInput();
        return in != null && (in.forward() || in.backward() || in.left() || in.right());
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

    /** Направление прыжка от стены, как в Genshin: по движению игрока.
     *  - Вперёд — короткий прыжок в сторону движения с невысоким подъёмом.
     *  - Назад — небольшое отталкивание от стены (спрыгивание).
     *  - Вбок не попадает сюда: для этого есть sidestep (рывок вдоль стены). */
    private static Vec3d climbJumpVelocity(ServerPlayerEntity player) {
        PlayerInput in = player.getPlayerInput();
        if (in == null) {
            return new Vec3d(0, CLIMB_JUMP_UP, 0);
        }
        double forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        double strafe = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        if (forward == 0 && strafe == 0) {
            return new Vec3d(0, CLIMB_JUMP_UP, 0);
        }
        double yaw = Math.toRadians(player.getYaw());
        double dx = -Math.sin(yaw) * forward - Math.cos(yaw) * strafe;
        double dz = Math.cos(yaw) * forward - Math.sin(yaw) * strafe;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return new Vec3d(0, CLIMB_JUMP_UP, 0);
        }
        dx /= len;
        dz /= len;
        if (forward < 0) {
            // Назад — лишь чуть оттолкнуться от стены (спрыгивание).
            return new Vec3d(dx * BACK_JUMP_SPEED, BACK_JUMP_UP, dz * BACK_JUMP_SPEED);
        }
        return new Vec3d(dx * CLIMB_JUMP_SPEED, CLIMB_JUMP_UP, dz * CLIMB_JUMP_SPEED);
    }

    /** Только влево/вправо без вперёд/назад (боковой рывок вдоль стены). */
    private static boolean isStrafeOnly(ServerPlayerEntity player) {
        PlayerInput in = player.getPlayerInput();
        return in != null && !in.forward() && !in.backward() && (in.left() || in.right());
    }

    /** Скорость бокового рывка: вдоль стены + малый отрыв от неё.
     *  Потом игрок снова прилипает к стене, уже дальше по стороне. */
    private static Vec3d sidestepVelocity(ServerPlayerEntity player, ClimbData d) {
        PlayerInput in = player.getPlayerInput();
        double strafe = 0;
        if (in != null) {
            strafe = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        }
        double yaw = Math.toRadians(player.getYaw());
        double sx = -Math.cos(yaw) * strafe;
        double sz = -Math.sin(yaw) * strafe;
        // Направление вдоль стены (~90%) + малый отрыв в сторону от стены (~10%),
        // чтобы прыжок выглядел как отлипание, но игрок не улетал от стены.
        double ax = 0;
        double az = 0;
        if (d.wallDir != null) {
            ax = -d.wallDir.getOffsetX();
            az = -d.wallDir.getOffsetZ();
        }
        double hx = sx * 0.9 + ax * 0.1;
        double hz = sz * 0.9 + az * 0.1;
        double len = Math.hypot(hx, hz);
        if (len < 1e-6) {
            return new Vec3d(sx * SIDESTEP_SPEED, SIDESTEP_UP, sz * SIDESTEP_SPEED);
        }
        hx /= len;
        hz /= len;
        return new Vec3d(hx * SIDESTEP_SPEED, SIDESTEP_UP, hz * SIDESTEP_SPEED);
    }

    /** Карабкается или сползает ли игрок прямо сейчас (для блокировки атак). */
    public static boolean isClimbing(ServerPlayerEntity player) {
        ClimbData d = DATA.get(player.getUuid());
        return d != null && (d.climbing || d.sliding);
    }

    /** Ищет рядом сплошной блок-стену на уровне тела (feet или feet+1).
     *  Проверка на старт разрешена только для стен высотой >= 2 блоков
     *  (см. isStartableWall), а во время подъёма стена ищется так, чтобы
     *  игрок добрался до самого верха. */
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

    /** Можно ли начать карабканье: стена высотой не меньше двух блоков
     *  (блок на уровне ног и блок над ним). Одиночный блок не считается. */
    private static boolean isStartableWall(World world, BlockPos feet, Direction wall) {
        if (wall == null) {
            return false;
        }
        BlockPos side = feet.offset(wall);
        return isWallBlock(world, side) && isWallBlock(world, side.up());
    }

    private static boolean isWallBlock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && state.isOpaqueFullCube() && state.getFluidState().isEmpty();
    }
}
