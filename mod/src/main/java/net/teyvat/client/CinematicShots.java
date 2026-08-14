package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Автоскриншоты ударов комбо (/cinema shots): на тике урона каждого удара
 * следующий кадр рендерится с кинокамеры сбоку (без HUD) и сохраняется
 * в screenshots/ как teyvat_attack_N_*.png. Пока включено, герой доворачивается
 * к ближайшему врагу, чтобы каждый удар попадал в кадр со стороны меча.
 */
public final class CinematicShots {
    private static boolean enabled;
    /** Кадр захвата запрошен: следующий отрендеренный кадр = кинокамера + скриншот. */
    private static boolean pendingCapture;
    private static int captureCounter = 1;
    private static double distance = 5.5;
    private static double height = 0.4;
    private static boolean leftSide;

    private CinematicShots() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void arm(double dist, double extraHeight, boolean left) {
        distance = Math.max(2.0, dist);
        height = extraHeight;
        leftSide = left;
        enabled = true;
        pendingCapture = false;
        captureCounter = 1;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§b[Teyvat] §fСъёмка комбо включена: на каждом ударе — скриншот"
                    + " (дист. " + distance + ", высота " + height + (left ? ", слева). " : ", справа). ")
                    + "Герой доворачивается к врагу. /cinema off — выключить."), false);
        }
    }

    public static void disarm() {
        enabled = false;
        pendingCapture = false;
    }

    /** Вызывается из CombatController на тике урона удара: просим кадр сбоку. */
    public static void onDamageTick() {
        if (enabled) {
            pendingCapture = true;
        }
    }

    /** true — CameraController в этом кадре должен поставить камеру сбоку. */
    public static boolean isCaptureFrame() {
        return enabled && pendingCapture;
    }

    /** Кадр сбоку для автоскриншота (как SIDE у CinematicCamera), null если не нужен. */
    public static double[] frame(Vec3d eye) {
        if (!isCaptureFrame()) {
            return null;
        }
        return CinematicCamera.sideFrame(eye, distance, height, leftSide ? 1.0 : -1.0);
    }

    /** Вызывается на рендер-потоке (WorldRenderEvents.END): снимает кадр, если запрошен. */
    public static void renderCapture() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || !pendingCapture || client.getFramebuffer() == null) {
            return;
        }
        pendingCapture = false;
        String name = "teyvat_attack_" + captureCounter++;
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1,
                text -> client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(text, false);
                    }
                }));
    }
}
