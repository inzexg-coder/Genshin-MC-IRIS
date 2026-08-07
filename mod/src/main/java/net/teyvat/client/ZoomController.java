package net.teyvat.client;

import net.teyvat.TeyvatClient;
import net.teyvat.config.TeyvatConfig;

/** Зум по кнопке (как в OptiFine): пока клавиша зажата, камера плавно приближается,
 *  подзорная труба не нужна. Множитель FOV настраивается в config/teyvat.json → zoom.fov. */
public final class ZoomController {
    /** Текущая плавная позиция зума: 0 = обычный FOV, 1 = полный зум. */
    private static float progress = 0f;

    private ZoomController() {}

    /** Вызывается каждый клиентский тик: клавиша зажата — зум плавно включается. */
    public static void tick() {
        boolean holding = TeyvatClient.ZOOM.isPressed();
        float target = holding ? 1f : 0f;
        // ~0.4 за тик: полный зум набирается за ~0.3 сек, возврат такой же плавный.
        progress += (target - progress) * Math.min(1f, 0.4f);
    }

    /** Множитель FOV текущего кадра: 1.0 в покое, zoom.fov при полном зуме. */
    public static float fovFactor() {
        return 1f - progress * (1f - TeyvatConfig.get().zoom.fov);
    }
}
