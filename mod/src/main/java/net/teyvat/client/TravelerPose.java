package net.teyvat.client;

import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;

/** Позы превью-моделей в экране выбора путешественника.
 *  Итэр — глубокий вежливый поклон с ладонями вместе, Люмин — лёгкий грациозный книксен.
 *  Углы — в радианах и соответствуют поворотам частей ванильной модели
 *  (положительный pitch корпуса = наклон вперёд, отрицательный pitch головы = вниз). */
public final class TravelerPose {
    private static final Identifier LUMINE_SKIN = Identifier.of("teyvat", "skin/lumine");
    private static final Identifier AETHER_SKIN = Identifier.of("teyvat", "skin/aether");

    private static final TravelerPose[] PREVIEW = { new TravelerPose(), new TravelerPose() };

    /** Ключевые кадры: {тик, pitch, yaw, roll}. Кости: торс, голова, левая рука, правая рука, левая нога, правая нога. */
    private static final float[][][][] BOWS = {
        { // Люмин — книксен, ~34 тика (1.7 c)
            {{0, 0, 0, 0}, {10, 0.30f, 0, 0}, {22, 0.30f, 0, 0}, {34, 0, 0, 0}},    // торс
            {{0, 0, 0, 0}, {10, -0.32f, 0, 0}, {22, -0.32f, 0, 0}, {34, 0, 0, 0}},  // голова
            {{0, 0, 0, 0}, {10, -0.10f, 0.18f, -0.15f}, {22, -0.10f, 0.18f, -0.15f}, {34, 0, 0, 0}}, // левая рука
            {{0, 0, 0, 0}, {10, -0.10f, -0.18f, 0.15f}, {22, -0.10f, -0.18f, 0.15f}, {34, 0, 0, 0}}, // правая рука
            {{0, 0, 0, 0}, {10, -0.22f, 0, 0}, {22, -0.22f, 0, 0}, {34, 0, 0, 0}},  // левая нога
            {{0, 0, 0, 0}, {10, -0.22f, 0, 0}, {22, -0.22f, 0, 0}, {34, 0, 0, 0}}   // правая нога
        },
        { // Итэр — глубокий поклон с ладонями вместе, ~36 тиков (1.8 c)
            {{0, 0, 0, 0}, {10, 0.50f, 0, 0}, {24, 0.50f, 0, 0}, {36, 0, 0, 0}},    // торс
            {{0, 0, 0, 0}, {10, -0.55f, 0, 0}, {24, -0.55f, 0, 0}, {36, 0, 0, 0}},  // голова
            {{0, 0, 0, 0}, {10, -0.80f, 1.05f, -0.26f}, {24, -0.80f, 1.05f, -0.26f}, {36, 0, 0, 0}}, // левая рука
            {{0, 0, 0, 0}, {10, -0.80f, -1.05f, 0.26f}, {24, -0.80f, -1.05f, 0.26f}, {36, 0, 0, 0}}, // правая рука
            {{0, 0, 0, 0}}, // левая нога — прямые
            {{0, 0, 0, 0}}  // правая нога
        }
    };

    /** Насколько поза применена (0..1) — плавное включение и выключение. */
    public float blend;
    public float headPitch, headYaw, headRoll;
    public float torsoPitch, torsoYaw, torsoRoll;
    public float leftArmPitch, leftArmYaw, leftArmRoll;
    public float rightArmPitch, rightArmYaw, rightArmRoll;
    public float leftLegPitch, leftLegYaw, leftLegRoll;
    public float rightLegPitch, rightLegYaw, rightLegRoll;

    private TravelerPose() {}

    /** Текущая поза превью-модели по её скину, или null вне экрана выбора. */
    public static TravelerPose forSkin(SkinTextures skin) {
        if (skin == null) {
            return null;
        }
        Identifier tex = skin.body().id();
        if (LUMINE_SKIN.equals(tex)) {
            return PREVIEW[0];
        }
        if (AETHER_SKIN.equals(tex)) {
            return PREVIEW[1];
        }
        return null;
    }

    /** Пересчитать позу превью для текущего кадра: index 0 = Люмин, 1 = Итэр. */
    public static void update(int index, float tick, float blend) {
        TravelerPose p = PREVIEW[index];
        p.blend = blend;
        float[][][] src = BOWS[index];
        p.torsoPitch = sample(src[0], tick, 1);
        p.torsoYaw = sample(src[0], tick, 2);
        p.torsoRoll = sample(src[0], tick, 3);
        p.headPitch = sample(src[1], tick, 1);
        p.headYaw = sample(src[1], tick, 2);
        p.headRoll = sample(src[1], tick, 3);
        p.leftArmPitch = sample(src[2], tick, 1);
        p.leftArmYaw = sample(src[2], tick, 2);
        p.leftArmRoll = sample(src[2], tick, 3);
        p.rightArmPitch = sample(src[3], tick, 1);
        p.rightArmYaw = sample(src[3], tick, 2);
        p.rightArmRoll = sample(src[3], tick, 3);
        p.leftLegPitch = sample(src[4], tick, 1);
        p.leftLegYaw = sample(src[4], tick, 2);
        p.leftLegRoll = sample(src[4], tick, 3);
        p.rightLegPitch = sample(src[5], tick, 1);
        p.rightLegYaw = sample(src[5], tick, 2);
        p.rightLegRoll = sample(src[5], tick, 3);
    }

    /** Интерполяция ключевых кадров одной оси с плавным ускорением и замедлением. */
    private static float sample(float[][] keys, float tick, int axis) {
        if (tick <= keys[0][0]) {
            return keys[0][axis];
        }
        if (tick >= keys[keys.length - 1][0]) {
            return keys[keys.length - 1][axis];
        }
        for (int i = 0; i < keys.length - 1; i++) {
            if (tick <= keys[i + 1][0]) {
                float t0 = keys[i][0];
                float t1 = keys[i + 1][0];
                float f = (tick - t0) / (t1 - t0);
                float e = f * f * (3f - 2f * f); // smoothstep
                return keys[i][axis] + (keys[i + 1][axis] - keys[i][axis]) * e;
            }
        }
        return keys[keys.length - 1][axis];
    }
}
