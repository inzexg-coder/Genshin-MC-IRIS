package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;
import net.teyvat.TeyvatClient;

/**
 * Выносливость как в Genshin. Бег тратит стамину, рывок вперёд — короткий
 * бросок по кнопке (тап = рывок, удержание = бег). Ванильный спринт майна
 * выключен миксином ClientPlayerEntityMixin, поэтому бегом управляет
 * только этот класс. Двойное W тоже включает бег, как в майне.
 */
public final class StaminaController {
    /** Полная шкала выносливости. */
    public static final float MAX_STAMINA = 100f;
    /** Трата бега: 15 ед/сек (0.75 за тик при 20 tps). */
    private static final float SPRINT_DRAIN = 15f / 20f;
    /** Стоимость рывка. */
    private static final float DASH_COST = 25f;
    /** Восстановление: 30 ед/сек. */
    private static final float REGEN_RATE = 30f / 20f;
    /** Пауза перед восстановлением после траты (1 сек). */
    private static final int REGEN_DELAY = 20;
    /** Длительность рывка: 0.1 сек. */
    private static final int DASH_TICKS = 2;
    /** Скорость рывка: 2 блока/сек → ~0.6 блока за рывок вместе с шагом. */
    private static final double DASH_SPEED = 2.0;
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
    /** Тик последнего нажатия W. Не MIN_VALUE: вычитание дало бы переполнение
     *  и первое нажатие W считалось бы двойным (бег с первого раза). */
    private static long lastForwardPressTick = -1000;

    private StaminaController() {}

    /** Вызывается каждый клиентский тик. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        ClientPlayerEntity player = client.player;

        // Рывок по нажатию кнопки: тап = рывок, удержание = бег после рывка.
        boolean pressed = TeyvatClient.SPRINT_DASH.isPressed();
        boolean freshPress = pressed && !keyHeld;
        keyHeld = pressed;

        // Двойное W — бег, как в майне.
        KeyBinding forward = client.options.forwardKey;
        if (forward.wasPressed()) {
            long dt = client.world.getTime() - lastForwardPressTick;
            if (dt >= 0 && dt <= DOUBLE_TAP_WINDOW) {
                doubleTapForward = true;
            }
            lastForwardPressTick = client.world.getTime();
        }
        if (!forward.isPressed()) {
            doubleTapForward = false;
        }
        boolean doubleTapHeld = doubleTapForward && forward.isPressed();

        // На транспорте, крадучись или с предметом в руке бег не работает.
        if (player.hasVehicle() || player.isSneaking() || player.isUsingItem()) {
            player.setSprinting(false);
            regen();
            StaminaOverlay.tick(stamina);
            return;
        }

        if (freshPress && !dashing && stamina >= DASH_COST) {
            startDash(player);
        }

        if (dashing) {
            applyDashVelocity(player);
            dashTicksLeft--;
            if (dashTicksLeft <= 0) {
                dashing = false;
                // Гасим остаточную скорость, чтобы рывок не растягивался по инерции.
                Vec3d v = player.getVelocity();
                player.setVelocity(0, v.y, 0);
                player.velocityModified = true;
            }
            player.setSprinting(false);
            regenDelay = REGEN_DELAY;
            StaminaOverlay.tick(stamina);
            return;
        }

        boolean wantSprint = (pressed || doubleTapHeld) && stamina > 0f;
        if (wantSprint) {
            player.setSprinting(true);
            stamina = Math.max(0f, stamina - SPRINT_DRAIN);
            regenDelay = REGEN_DELAY;
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
        stamina = Math.max(0f, stamina - DASH_COST);
        regenDelay = REGEN_DELAY;
        dashDir = dashDirection(player);
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

    /** Поддержание скорости рывка каждый тик (горизонталь, вертикаль сохраняется). */
    private static void applyDashVelocity(ClientPlayerEntity player) {
        Vec3d v = player.getVelocity();
        player.setVelocity(dashDir.x * DASH_SPEED, v.y, dashDir.z * DASH_SPEED);
        player.velocityModified = true;
    }

    /** Текущая выносливость (для дуги на экране). */
    public static float getStamina() {
        return stamina;
    }

    /** Идёт ли сейчас рывок. */
    public static boolean isDashing() {
        return dashing;
    }
}
