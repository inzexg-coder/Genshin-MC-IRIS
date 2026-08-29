package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.teyvat.TeyvatClient;
import net.teyvat.network.ClimbStaminaPayload;
import net.teyvat.player.TravelerProfile;

/**
 * Выносливость как в Genshin. Бег тратит стамину и включается только
 * двойным нажатием W. Рывок вперёд — короткий бросок по тапу Ctrl.
 * Ванильный спринт майна выключен миксином ClientPlayerEntityMixin,
 * поэтому бегом и рывком управляет только этот класс.
 */
public final class StaminaController {
    /** Полная шкала выносливости: ~10 секунд бега при трате 15/сек. */
    public static final float MAX_STAMINA = 150f;
    /** Трата бега: 15 ед/сек (0.75 за тик при 20 tps). */
    private static final float SPRINT_DRAIN = 15f / 20f;
    /** Стоимость рывка. */
    private static final float DASH_COST = 25f;
    /** Стоимость заряженной атаки (спин): дороже рывка, как в Genshin. */
    public static final float CHARGE_COST = 30f;
    /** Восстановление: 30 ед/сек. */
    private static final float REGEN_RATE = 30f / 20f;
    /** Пауза перед восстановлением после траты (1 сек). */
    private static final int REGEN_DELAY = 20;
    /** Длительность рывка: 0.35 сек — достаточно шагов, чтобы не было рывков. */
    private static final int DASH_TICKS = 7;
    /** Пик скорости рывка: мягкая синусоида без мёртвого старта и резкого стопа,
     *  ~0.38 блока за рывок — бросок стал слабее, не уносит игрока. */
    private static final double DASH_PEAK_SPEED = 1.7;
    /** Окно двойного нажатия W, чтобы побежать (0.5 сек). */
    private static final int DOUBLE_TAP_WINDOW = 10;

    private static float stamina = MAX_STAMINA;
    /** Пауза перед восстановлением: -1 = можно восстанавливать, 0..REGEN_DELAY = ждём. */
    private static int regenDelay = -1;
    private static boolean keyHeld;
    private static boolean dashing;
    private static int dashTicksLeft;
    private static Vec3d dashDir = Vec3d.ZERO;
    private static boolean doubleTapForward;
    /** Был ли W зажат в прошлом тике (для фронта нажатия). */
    private static boolean wasForwardDown;
    /** Тик последнего нажатия W. Не MIN_VALUE: вычитание дало бы переполнение
     *  и первое нажатие W считалось бы двойным (бег с первого раза). */
    private static long lastForwardPressTick = -1000;
    /** Ввод, сохранённый на время рывка (рывок «залочен», ввод движения выключен). */
    private static Input savedInput;
    /** Карабкается ли игрок прямо сейчас (состояние приходит с сервера).
     *  Пока карабкается, стаминой владеет сервер — клиент её не тратит и не копит. */
    private static boolean climbing;
    private static boolean sliding;
    private static int climbSyncCounter;
    /** События для квестов Паймон: побежал двойным W / сделал рывок по Ctrl.
     *  Съедаются клиентом раз за тик (см. consumeSprintEvent/consumeDashEvent). */
    private static boolean sprintEvent;
    private static boolean dashEvent;

    private StaminaController() {}

    /** Сервер передал состояние карабканья и авторитетную стамину. */
    public static void setServerClimbState(boolean climbing, boolean sliding, float stamina) {
        StaminaController.climbing = climbing;
        StaminaController.sliding = sliding;
        if (climbing || sliding) {
            StaminaController.stamina = Math.max(0f, Math.min(MAX_STAMINA, stamina));
        }
    }

    /** Карабкается ли игрок (для анимаций/UI). */
    public static boolean isClimbing() {
        return climbing;
    }

    /** Вызывается каждый клиентский тик. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        ClientPlayerEntity player = client.player;

        // Карабканье: стамина авторитетна на сервере. Пока карабкаемся,
        // клиент не тратит/не копит свою стамину (сервер шлёт актуальную).
        if (climbing || sliding) {
            StaminaOverlay.tick(stamina);
            return;
        }
        // Периодически шлём серверу свою текущую стамину (база для карабканья).
        if (++climbSyncCounter >= 5) {
            climbSyncCounter = 0;
            if (client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new ClimbStaminaPayload(stamina));
            }
        }

        // Рывок по тапу Ctrl: короткий бросок вперёд, удержание ничего не делает.
        boolean pressed = TeyvatClient.SPRINT_DASH.isPressed();
        boolean freshPress = pressed && !keyHeld;
        keyHeld = pressed;

        // Двойное W — бег, как в майне. Считаем по фронту нажатия (isPressed),
        // а не по wasPressed: автоповтор клавиши (GLFW_REPEAT) ложно срабатывал
        // как второе нажатие при простом удержании W.
        KeyBinding forward = client.options.forwardKey;
        boolean forwardDown = forward.isPressed();
        if (forwardDown && !wasForwardDown) {
            long dt = client.world.getTime() - lastForwardPressTick;
            if (dt >= 0 && dt <= DOUBLE_TAP_WINDOW) {
                doubleTapForward = true;
            }
            lastForwardPressTick = client.world.getTime();
        }
        wasForwardDown = forwardDown;
        if (!forwardDown) {
            doubleTapForward = false;
        }
        boolean doubleTapHeld = doubleTapForward && forwardDown;

        // На транспорте, крадучись или с предметом в руке бег не работает.
        if (player.hasVehicle() || player.isSneaking() || player.isUsingItem()) {
            player.setSprinting(false);
            regen();
            StaminaOverlay.tick(stamina);
            return;
        }

        // Рывок только на земле: в воздухе (как в Genshin) рывка нет.
        if (freshPress && !dashing && player.isOnGround() && stamina >= DASH_COST) {
            // Dash-cancel: рывок прерывает анимацию атаки (как в Genshin),
            // серия комбо сохраняется — следующий клик продолжит цепочку.
            CombatController.tryCancelByDash();
            startDash(player);
        }

        if (dashing) {
            if (!player.isOnGround()) {
                // Сорвался с обрыва во время рывка: бросок гасится, ввод возвращается.
                dashing = false;
                dashTicksLeft = 0;
                if (savedInput != null) {
                    player.input = savedInput;
                    savedInput = null;
                }
                player.setSprinting(false);
                regenDelay = REGEN_DELAY;
                StaminaOverlay.tick(stamina);
                return;
            }
            applyDashVelocity(player);
            spawnDashDust(client, player);
            dashTicksLeft--;
            if (dashTicksLeft <= 0) {
                dashing = false;
                if (savedInput != null) {
                    player.input = savedInput;
                    savedInput = null;
                }
            }
            player.setSprinting(false);
            regenDelay = REGEN_DELAY;
            StaminaOverlay.tick(stamina);
            return;
        }

        boolean wantSprint = doubleTapHeld && stamina > 0f;
        if (wantSprint) {
            player.setSprinting(true);
            if (doubleTapHeld) {
                sprintEvent = true;
            }
            stamina = Math.max(0f, stamina - SPRINT_DRAIN);
            if (stamina <= 0f) {
                // Стамина кончилась: двойной клик W «забывается» — держим W,
                // идём обычным шагом, и стамина спокойно восстанавливается.
                if (doubleTapHeld) {
                    doubleTapForward = false;
                }
                player.setSprinting(false);
            } else {
                regenDelay = REGEN_DELAY;
            }
        } else {
            player.setSprinting(false);
        }

        regen();
        StaminaOverlay.tick(stamina);
    }

    /** Восстановление после паузы: ждём REGEN_DELAY тиков, потом копим. */
    private static void regen() {
        if (regenDelay > 0) {
            regenDelay--;
        } else if (stamina < MAX_STAMINA) {
            stamina = Math.min(MAX_STAMINA, stamina + REGEN_RATE);
        }
    }

    /** Начать рывок: короткий бросок по направлению движения. */
    private static void startDash(ClientPlayerEntity player) {
        dashing = true;
        dashTicksLeft = DASH_TICKS;
        dashEvent = true;
        stamina = Math.max(0f, stamina - DASH_COST);
        regenDelay = REGEN_DELAY;
        dashDir = dashDirection(player);
        // Рывок «залочен», как в Genshin: ввод движения выключен на время рывка,
        // поэтому шаг игрока не растягивает бросок и он не меняет направление.
        savedInput = player.input;
        player.input = new Input();
        player.setSprinting(false);
    }

    /** Направление рывка: по движению, иначе вперёд по взгляду. */
    private static Vec3d dashDirection(ClientPlayerEntity player) {
        PlayerInput input = player.input.playerInput;
        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.right() ? 1 : 0) - (input.left() ? 1 : 0);
        double yaw = Math.toRadians(player.getYaw());
        double dx = -Math.sin(yaw) * forward - Math.cos(yaw) * strafe;
        double dz = Math.cos(yaw) * forward - Math.sin(yaw) * strafe;
        Vec3d dir = new Vec3d(dx, 0, dz);
        if (dir.lengthSquared() < 1e-6) {
            dir = Vec3d.fromPolar(0f, player.getYaw());
        }
        return dir.normalize();
    }

    /** Профиль рывка 0..1: синусоида со сдвигом — движение начинается с первого же
     *  тика (без «мёртвой» паузы), плавно разгоняется, идёт на пик и так же плавно
     *  тормозит к нулю. Единый профиль для скорости, FOV-кика и наклона камеры. */
    public static float dashFactor() {
        if (!dashing) {
            return 0f;
        }
        float progress = (float) (DASH_TICKS - dashTicksLeft + 0.5f) / DASH_TICKS;
        return Math.max(0f, Math.min(1f, (float) Math.sin(Math.PI * progress)));
    }

    /** Скорость рывка по профилю. Вертикаль сохраняется. */
    private static void applyDashVelocity(ClientPlayerEntity player) {
        double speed = DASH_PEAK_SPEED * dashFactor()
                * TravelerProfile.fromPlayer(player).dashSpeedMultiplier();
        Vec3d v = player.getVelocity();
        player.setVelocity(dashDir.x * speed, v.y, dashDir.z * speed);
        player.velocityModified = true;
    }

    /** Пыль под ногами: клуб на старте рывка и лёгкий шлейф во время полёта. */
    private static void spawnDashDust(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        int count = dashTicksLeft >= DASH_TICKS - 1 ? 4 : 1;
        for (int i = 0; i < count; i++) {
            client.world.addParticleClient(ParticleTypes.POOF,
                    player.getX() + (client.world.random.nextDouble() - 0.5) * 0.5,
                    player.getY() + 0.1,
                    player.getZ() + (client.world.random.nextDouble() - 0.5) * 0.5,
                    0, 0, 0);
        }
    }

    /** Текущая выносливость (для дуги на экране). */
    public static float getStamina() {
        return stamina;
    }

    /** Съесть событие бега (для квеста Паймон): true один раз после двойного W. */
    public static boolean consumeSprintEvent() {
        boolean event = sprintEvent;
        sprintEvent = false;
        return event;
    }

    /** Съесть событие рывка (для квеста Паймон): true один раз после тапа Ctrl. */
    public static boolean consumeDashEvent() {
        boolean event = dashEvent;
        dashEvent = false;
        return event;
    }

    /** Снять стамину за заряженную атаку. Возвращает false, если не хватило —
     *  заряд не выстрелит (как в Genshin: пустая шкала не даёт заряженный удар). */
    public static boolean trySpendCharge() {
        if (stamina < CHARGE_COST) {
            return false;
        }
        stamina -= CHARGE_COST;
        regenDelay = REGEN_DELAY;
        return true;
    }

    /** Идёт ли сейчас рывок. */
    public static boolean isDashing() {
        return dashing;
    }
}
