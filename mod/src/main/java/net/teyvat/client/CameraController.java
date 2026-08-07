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
    /** Кадры плавного выезда из первого лица: камера стартует с точки глаз и медленно отплывает. */
    private static int blendFrames;

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
        if (!active) {
            float rate = smoothRate(cfg.return_smoothness, tickDelta);
            orbitYaw = MathHelper.lerpAngleDegrees(rate, orbitYaw, playerYaw);
            orbitPitch += (playerPitch - orbitPitch) * rate;
        }
        float camYaw = orbitYaw;
        float camPitch = orbitPitch;

        // Целевая позиция: глаза героя, назад вдоль луча камеры + плечевое смещение.
        Vec3d eye = entity.getCameraPosVec(tickDelta);
        Vec3d fwd = entity.getRotationVector(camPitch, camYaw);
        double min = Math.min(cfg.min_distance, cfg.max_distance);
        double max = Math.max(cfg.min_distance, cfg.max_distance);
        double dist = MathHelper.clamp(currentDistance > 0 ? currentDistance : cfg.distance, min, max);
        Vec3d target = eye.subtract(fwd.multiply(dist));
        Vec3d right = new Vec3d(-fwd.z, 0, fwd.x).normalize();
        target = target.add(right.multiply(cfg.side)).add(0, cfg.up, 0);

        // Умная коллизия: камера не вжимается в блоки, а плавно останавливается перед ними.
        if (cfg.collision) {
            target = collide(area, entity, eye, target);
        }

        // Плавное догоняние позиции (экспоненциальное сглаживание по времени кадра).
        float dt = Math.max(tickDelta, 0.0001f) * 0.05f;
        float rate = 1f - (float) Math.exp(-cfg.smoothness * dt);
        double eyeJump = eye.squaredDistanceTo(lastEyeX, lastEyeY, lastEyeZ);
        if (!initialized || eyeJump > 30.0 * 30.0) {
            // Вход в 3-е лицо (или телепорт): камера стартует с точки глаз и плавно
            // отплывает назад — переход от первого лица к третьему без рывка.
            camX = eye.x;
            camY = eye.y;
            camZ = eye.z;
            initialized = true;
            blendFrames = 30;
        } else {
            // Первые кадры после входа — более медленный «кинематографичный» выезд.
            float r = blendFrames > 0 ? 1f - (float) Math.exp(-cfg.blend_smoothness * dt) : rate;
            camX += (target.x - camX) * r;
            camY += (target.y - camY) * r;
            camZ += (target.z - camZ) * r;
            if (blendFrames > 0) {
                blendFrames--;
            }
        }
        lastEyeX = eye.x;
        lastEyeY = eye.y;
        lastEyeZ = eye.z;

        ((CameraAccessor) camera).teyvatSetRotation(camYaw, camPitch);
        ((CameraAccessor) camera).teyvatSetPos(camX, camY, camZ);
    }

    /** Луч от глаз героя к цели: при попадании в блок камера встаёт перед ним с запасом. */
    private static Vec3d collide(BlockView area, Entity entity, Vec3d eye, Vec3d target) {
        Vec3d dir = target.subtract(eye);
        double dist = dir.length();
        if (dist < 1.0E-4) {
            return target;
        }
        Vec3d norm = dir.multiply(1.0 / dist);
        Vec3d start = eye.add(0, 0.1, 0);
        RaycastContext context = new RaycastContext(start, target, RaycastContext.ShapeType.VISUAL,
                RaycastContext.FluidHandling.NONE, entity);
        BlockHitResult hit = area.raycast(context);
        if (hit.getType() == HitResult.Type.MISS) {
            return target;
        }
        double hitDist = start.distanceTo(hit.getPos());
        if (hitDist >= dist) {
            return target;
        }
        double margin = 0.25;
        return start.add(norm.multiply(Math.max(0.2, hitDist - margin)));
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

    /** Коэффициент сглаживания для данного кадра (rate в секунду, delta — время кадра). */
    private static float smoothRate(float perSecond, float tickDelta) {
        float dt = Math.max(tickDelta, 0.0001f) * 0.05f;
        return 1f - (float) Math.exp(-perSecond * dt);
    }
}
