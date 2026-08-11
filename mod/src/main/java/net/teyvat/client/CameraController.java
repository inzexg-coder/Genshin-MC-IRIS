package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.teyvat.TeyvatClient;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.mixin.client.CameraAccessor;

/**
 * Teyvat Camera — кастомная камера от 3-го лица в стиле Genshin.
 * Плечевое смещение, плавное догоняние, умная коллизия, дистанция колесиком
 * и свободная камера (мышь вращает камеру вокруг героя, герой поворачивается при движении).
 */
public final class CameraController {
    /** Сглаженная позиция камеры в мире. */
    private static double camX;
    private static double camY;
    private static double camZ;
    /** Углы орбиты свободной камеры (независимы от героя). */
    private static float orbitYaw;
    private static float orbitPitch;
    /** Свободная камера активна прямо сейчас. */
    private static boolean active;
    /** Позиция камеры уже инициализирована (чтобы не было рывка на первом кадре). */
    private static boolean initialized;
    /** Глаза героя в предыдущем кадре: телепорт/смена измерения сбрасывает догоняние. */
    private static double lastEyeX;
    private static double lastEyeY;
    private static double lastEyeZ;
    /** Текущая дистанция (меняется колесиком). <=0 = брать из конфига. */
    private static double currentDistance;
    /** Оставшееся время плавного выезда из первого лица, секунды. */
    private static float blendTime;
    /** Сглаженная «безопасная» дистанция камеры (коллизия): не дёргается на стыках блоков. */
    private static double safeDist;
    private static boolean safeDistReady;
    /** Сглаженное время кадра: неравномерный FPS (шейдеры, GC) не дёргает камеру. */
    private static float smoothDt = 0.0167f;

    private CameraController() {}

    /** Активна ли свободная камера (миксин мыши перенаправляет вращение). */
    public static boolean isActive() {
        return active;
    }

    /** Переключить игрока в вид от 3-го лица с плавным выездом камеры
     *  (используется после знакомства с Паймон). В первом лице ничего не делает. */
    public static void switchToThirdPerson() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options == null || client.player == null) {
            return;
        }
        if (client.options.getPerspective().isFirstPerson()) {
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            initialized = false;
        }
    }

    /** Из MouseMixin вместо поворота игрока: вращаем орбиту камеры (как changeLookDirection). */
    public static void orbit(double dx, double dy) {
        orbitYaw = (float) (orbitYaw + dx * 0.15);
        orbitPitch = MathHelper.clamp(orbitPitch + (float) (dy * 0.15), -89.5f, 89.5f);
    }

    /** Колесико мыши меняет дистанцию камеры. */
    public static void scroll(float amount) {
        TeyvatConfig.Camera cfg = TeyvatConfig.get().camera;
        double min = Math.min(cfg.min_distance, cfg.max_distance);
        double max = Math.max(cfg.min_distance, cfg.max_distance);
        currentDistance = MathHelper.clamp(currentDistance - amount * cfg.scroll_sensitivity, min, max);
    }

    /** Вызывается каждый клиентский тик: клавиша свободной камеры, поворот героя, сброс состояния. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options == null) {
            active = false;
            initialized = false;
            return;
        }
        Perspective perspective = client.options.getPerspective();
        boolean thirdPersonBack = !perspective.isFirstPerson() && !perspective.isFrontView();
        if (!thirdPersonBack) {
            active = false;
            initialized = false;
            orbitYaw = client.player.getYaw();
            orbitPitch = client.player.getPitch();
            return;
        }

        TeyvatConfig.Camera cfg = TeyvatConfig.get().camera;
        String mode = cfg.free_look_mode == null ? "hold" : cfg.free_look_mode;
        if ("toggle".equals(mode)) {
            while (TeyvatClient.FREE_CAM.wasPressed()) {
                active = !active;
                if (active) {
                    snapOrbit(client.player.getYaw(), client.player.getPitch());
                }
            }
        } else if ("hold".equals(mode)) {
            boolean key = TeyvatClient.FREE_CAM.isPressed();
            if (key && !active) {
                active = true;
                snapOrbit(client.player.getYaw(), client.player.getPitch());
            } else if (!key) {
                active = false;
            }
            while (TeyvatClient.FREE_CAM.wasPressed()) {
                // гасим накопленные нажатия
            }
        } else {
            active = false;
            while (TeyvatClient.FREE_CAM.wasPressed()) {
                // режим отключён
            }
        }

        if (active) {
            rotatePlayerToMovement(client.player);
        }

        // Синхронизация дистанции: пока колесико не задействовано, берём значение из конфига.
        if (currentDistance <= 0) {
            currentDistance = cfg.distance;
        } else if (!cfg.scroll_controls_distance && currentDistance != cfg.distance) {
            currentDistance = cfg.distance;
        }
    }

    /** Применяется из CameraMixin в конце Camera.update: полностью задаём камеру от 3-го лица. */
    public static void apply(Camera camera, BlockView area, Entity entity, boolean thirdPerson,
                             boolean behindView, float tickDelta) {
        TeyvatConfig.Camera cfg = TeyvatConfig.get().camera;
        if (!cfg.enabled || !thirdPerson || behindView) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == null || client.player == null || entity != client.player || client.world == null) {
            return;
        }
        if (client.options == null || client.options.getPerspective().isFirstPerson()
                || client.options.getPerspective().isFrontView()) {
            return;
        }
        // Во сне камеру оставляем ванильной (она смотрит по направлению сна).
        if (entity instanceof LivingEntity living && living.isSleeping()) {
            return;
        }

        // Углы камеры: при свободной камере — орбита, иначе орбита плавно догоняет героя.
        float playerYaw = entity.getYaw(tickDelta);
        float playerPitch = entity.getPitch(tickDelta);
        // Сглаженное время кадра — единое для всей камеры (доступно до орбиты).
        float rawDt = Math.min(Math.max(tickDelta, 0.0001f) * 0.05f, 0.1f);
        smoothDt += (rawDt - smoothDt) * 0.3f;
        float dt = Math.max(smoothDt, 0.0005f);
        if (!active) {
            float rate = ratePerSecond(cfg.return_smoothness, dt);
            orbitYaw = MathHelper.lerpAngleDegrees(rate, orbitYaw, playerYaw);
            orbitPitch += (playerPitch - orbitPitch) * rate;
        }
        float camYaw = orbitYaw;
        float camPitch = orbitPitch;

        // Целевая позиция: глаза героя, назад вдоль луча камеры + плечевое смещение.
        Vec3d eye = entity.getCameraPosVec(tickDelta);
        Vec3d fwd = entity.getRotationVector(camPitch, camYaw);
        // Прямой взгляд вверх/вниз: горизонталь почти нулевая — не даём NaN в боковом смещении.
        Vec3d right;
        if (fwd.x * fwd.x + fwd.z * fwd.z < 1.0E-8) {
            right = new Vec3d(1, 0, 0);
        } else {
            right = new Vec3d(-fwd.z, 0, fwd.x).normalize();
        }
        double min = Math.min(cfg.min_distance, cfg.max_distance);
        double max = Math.max(cfg.min_distance, cfg.max_distance);
        double dist = MathHelper.clamp(currentDistance > 0 ? currentDistance : cfg.distance, min, max);
        Vec3d rawTarget = eye.subtract(fwd.multiply(dist)).add(right.multiply(cfg.side)).add(0, cfg.up, 0);

        // Умная коллизия: дистанция до ближайшего блока сглаживается во времени в обе
        // стороны (и «в стену», и «из стены»), поэтому камера не дёргается на стыках
        // блоков и при беге, а мягко останавливается и плавно отъезжает.
        Vec3d dir = rawTarget.subtract(eye);
        double full = dir.length();
        double safe = full;
        if (cfg.collision && full > 1.0E-4) {
            Vec3d start = eye.add(0, 0.1, 0);
            RaycastContext context = new RaycastContext(start, rawTarget, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, entity);
            BlockHitResult hit = area.raycast(context);
            if (hit.getType() != HitResult.Type.MISS) {
                safe = Math.max(0.2, start.distanceTo(hit.getPos()) - 0.25);
                if (safe > full) {
                    safe = full;
                }
            }
        }
        double eyeJump = eye.squaredDistanceTo(lastEyeX, lastEyeY, lastEyeZ);
        double useSafe;
        if (!safeDistReady || !initialized || eyeJump > 30.0 * 30.0) {
            safeDist = safe;
            safeDistReady = true;
            useSafe = safe;
        } else {
            float collideRate = 1f - (float) Math.exp(-cfg.collision_smoothness * dt);
            safeDist += (safe - safeDist) * collideRate;
            useSafe = safeDist;
        }
        Vec3d target = rawTarget;
        if (useSafe < full && full > 1.0E-4) {
            target = eye.add(dir.multiply(useSafe / full));
        }

        // Плавное догоняние позиции (экспоненциальное сглаживание по времени кадра).
        float rate = 1f - (float) Math.exp(-cfg.smoothness * dt);
        if (!initialized || eyeJump > 30.0 * 30.0) {
            // Вход в 3-е лицо (или телепорт): камера стартует с точки глаз и плавно
            // отплывает назад — переход от первого лица к третьему без рывка.
            camX = eye.x;
            camY = eye.y;
            camZ = eye.z;
            initialized = true;
            // Медленный «кинематографичный» выезд из первого лица: ~1.5 секунды.
            blendTime = 1.5f;
        } else {
            // Пока идёт выезд — камера отплывает заметно медленнее обычного догоняния.
            float r = blendTime > 0 ? 1f - (float) Math.exp(-cfg.blend_smoothness * dt) : rate;
            camX += (target.x - camX) * r;
            camY += (target.y - camY) * r;
            camZ += (target.z - camZ) * r;
            if (blendTime > 0) {
                blendTime -= dt;
            }
        }
        lastEyeX = eye.x;
        lastEyeY = eye.y;
        lastEyeZ = eye.z;

        // Рывок: лёгкий наклон камеры вниз — тело «заваливается» вперёд,
        // как в Genshin. Мягкий по профилю рывка, без резких скачков.
        camPitch += 2.5f * StaminaController.dashFactor();
        // Удары мечом: короткий кик камеры вниз на момент разреза — отдача, как в Genshin.
        camPitch += 2.0f * CombatController.impactKick();

        ((CameraAccessor) camera).teyvatSetRotation(camYaw, camPitch);
        ((CameraAccessor) camera).teyvatSetPos(camX, camY, camZ);
    }

    /** Свободная камера: герой плавно поворачивается к направлению движения относительно камеры. */
    private static void rotatePlayerToMovement(net.minecraft.client.network.ClientPlayerEntity player) {
        Vec2f movement = player.input.getMovementInput();
        float forward = movement.y;
        float sideways = movement.x;
        if (forward == 0f && sideways == 0f) {
            return;
        }
        float yawRad = orbitYaw * ((float) Math.PI / 180.0f);
        float sin = MathHelper.sin(yawRad);
        float cos = MathHelper.cos(yawRad);
        double moveX = (double) sideways * cos - (double) forward * sin;
        double moveZ = (double) forward * cos + (double) sideways * sin;
        float desiredYaw = (float) Math.toDegrees(-Math.atan2(moveX, moveZ));
        float smoothed = MathHelper.lerpAngleDegrees(0.3f, player.getYaw(), desiredYaw);
        player.setYaw(smoothed);
        player.setBodyYaw(smoothed);
        player.setHeadYaw(smoothed);
    }

    private static void snapOrbit(float yaw, float pitch) {
        orbitYaw = yaw;
        orbitPitch = pitch;
    }

    /** Коэффициент сглаживания: rate в секунду, dt — время кадра в секундах. */
    private static float ratePerSecond(float perSecond, float dt) {
        return 1f - (float) Math.exp(-perSecond * dt);
    }
}
