package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Кинематографичная камера для съёмки боя со стороны: фикс сбоку от героя или
 * медленная орбита вокруг него. Работает поверх Teyvat Camera в 3-м лице:
 * позиция/поворот задаются каждый кадр, вход и выход плавные.
 */
public final class CinematicCamera {
    public enum Mode { OFF, SIDE, ORBIT }

    private static Mode mode = Mode.OFF;
    private static double distance = 6.0;
    private static double height = 0.0;
    /** -1 = справа от героя (со стороны меча), +1 = слева. */
    private static double sideSign = -1.0;
    private static double speed = 0.5;   // рад/сек для орбиты
    private static long startMs = 0;

    private CinematicCamera() {}

    public static boolean isActive() {
        return mode != Mode.OFF;
    }

    public static Mode mode() {
        return mode;
    }

    public static void startSide(double dist, double extraHeight, boolean leftSide) {
        distance = Math.max(2.0, dist);
        height = extraHeight;
        sideSign = leftSide ? 1.0 : -1.0;
        mode = Mode.SIDE;
    }

    public static void startOrbit(double dist, double extraHeight, double radiansPerSecond) {
        distance = Math.max(2.0, dist);
        height = extraHeight;
        speed = Math.max(0.05, radiansPerSecond);
        startMs = Util.getMeasuringTimeMs();
        mode = Mode.ORBIT;
    }

    public static void stop() {
        mode = Mode.OFF;
    }

    /** Кинокамера работает в 3-м лице — переключаем при необходимости. */
    public static void ensureThirdPerson() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options != null && client.options.getPerspective().isFirstPerson()) {
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }
    }

    /** Кадр кинокамеры: [yaw, pitch, x, y, z] относительно глаз героя,
     *  или null, если камера выключена. */
    public static double[] frame(Vec3d eye, double dt) {
        if (mode == Mode.OFF || eye == null) {
            return null;
        }
        if (mode == Mode.ORBIT) {
            double elapsed = (Util.getMeasuringTimeMs() - startMs) / 1000.0;
            double a = elapsed * speed;
            Vec3d camPos = eye.add(Math.cos(a) * distance, height, Math.sin(a) * distance);
            return lookFrame(eye, camPos);
        }
        return sideFrame(eye, distance, height, sideSign);
    }

    /** Кадр сбоку от героя (по его текущему yaw): sign = -1 справа (со стороны
     *  меча), +1 слева. Используется режимом SIDE и автоскриншотами ударов. */
    public static double[] sideFrame(Vec3d eye, double dist, double extraHeight, double sign) {
        if (eye == null) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        float yawRad = client.player == null ? 0f
                : client.player.getYaw() * ((float) Math.PI / 180.0f);
        Vec3d camPos = eye.add(
                -Math.cos(yawRad) * sign * dist,
                extraHeight,
                -Math.sin(yawRad) * sign * dist);
        return lookFrame(eye, camPos);
    }

    private static double[] lookFrame(Vec3d eye, Vec3d camPos) {
        Vec3d look = eye.subtract(camPos);
        double len = look.length();
        if (len < 1.0E-4) {
            return null;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.asin(MathHelper.clamp(-look.y / len, -1.0, 1.0)));
        return new double[]{yaw, pitch, camPos.x, camPos.y, camPos.z};
    }
}
