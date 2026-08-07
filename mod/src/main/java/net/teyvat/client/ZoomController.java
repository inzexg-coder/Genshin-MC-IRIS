package net.teyvat.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.teyvat.TeyvatClient;
import net.teyvat.client.paimon.PaimonManager;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.quest.Quests;

/**
 * Зум по кнопке (как в OptiFine): пока клавиша зажата, камера плавно приближается,
 * подзорная труба не нужна. Множитель FOV настраивается в config/teyvat.json → zoom.fov.
 * Во время зума засчитывается поворот головы: квест «Попробуй приблизить мир»
 * выполняется, когда игрок приблизил мир и осмотрелся вокруг.
 */
public final class ZoomController {
    /** Текущая плавная позиция зума: 0 = обычный FOV, 1 = полный зум. */
    private static float progress = 0f;
    /** Зум считается активным, когда набран больше половины пути к полному. */
    private static final float ZOOM_ACTIVE = 0.5f;
    /** Сколько градусов нужно «осмотреться» при зуме, чтобы задание выполнилось. */
    private static final float LOOK_AROUND_DEGREES = 60.0f;

    /** Отслеживание поворота головы во время зума. */
    private static boolean zoomTracking;
    private static float lastYaw;
    private static float lastPitch;
    private static float lookAccum;

    private ZoomController() {}

    /** Вызывается каждый клиентский тик: клавиша зажата — зум плавно включается. */
    public static void tick() {
        boolean holding = TeyvatClient.ZOOM.isPressed();
        float target = holding ? 1f : 0f;
        // ~0.4 за тик: полный зум набирается за ~0.3 сек, возврат такой же плавный.
        progress += (target - progress) * Math.min(1f, 0.4f);
        trackLookAround();
    }

    /** Множитель FOV текущего кадра: 1.0 в покое, zoom.fov при полном зуме. */
    public static float fovFactor() {
        return 1f - progress * (1f - TeyvatConfig.get().zoom.fov);
    }

    /** Зум сейчас реально приближает мир. */
    private static boolean isZooming() {
        return progress > ZOOM_ACTIVE;
    }

    /** Накопление поворота головы при зуме: игрок приблизил мир и осматривается. */
    private static void trackLookAround() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            zoomTracking = false;
            lookAccum = 0f;
            return;
        }
        if (!isZooming()) {
            zoomTracking = false;
            lookAccum = 0f;
            return;
        }
        if (!zoomTracking) {
            zoomTracking = true;
            lookAccum = 0f;
            lastYaw = client.player.getYaw();
            lastPitch = client.player.getPitch();
            return;
        }
        // Суммируем кратчайшие повороты по горизонтали и вертикали.
        lookAccum += Math.abs(MathHelper.subtractAngles(lastYaw, client.player.getYaw()));
        lookAccum += Math.abs(client.player.getPitch() - lastPitch);
        lastYaw = client.player.getYaw();
        lastPitch = client.player.getPitch();
        // Квест доступен только после задания с колесом мыши.
        if (lookAccum >= LOOK_AROUND_DEGREES
                && QuestStateClient.isCompleted(Quests.TRY_SCROLL)
                && !QuestStateClient.isCompleted(Quests.TRY_ZOOM)
                && PaimonManager.isQuestAnnounced(Quests.TRY_ZOOM)) {
            QuestClient.complete(Quests.TRY_ZOOM, Quests.TRY_ZOOM_TITLE);
            PaimonManager.onZoomQuestCompleted();
        }
    }
}
