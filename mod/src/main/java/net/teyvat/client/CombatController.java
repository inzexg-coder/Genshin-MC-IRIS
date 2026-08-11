package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;

/**
 * Боевка путешественника: комбо из 5 ударов мечом по ЛКМ, как у Итэра/Люмин
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack.
 *
 * Анимация построена как непрерывная серия фаз: каждый удар = антиципация →
 * быстрый разрез (easeInOutCubic) → плавный возврат, который одновременно
 * является замахом следующего удара. Поэтому стыки между ударами не «прыгают»:
 * поза конца удара N совпадает с позой начала удара N+1. Удар 5 — круговой
 * замах над головой с обрушением. Удержание ЛКМ продолжает серию, тап — один удар.
 */
public final class CombatController {
    /** Текущий удар комбо: 0..HIT_COUNT-1, -1 = атака не идёт. */
    private static int comboStep = -1;
    /** Тики с начала текущего удара. */
    private static int hitTicks;
    /** Тики с конца последнего удара (окно продолжения серии, как в Genshin). */
    private static int idleTicks;
    /** Последний сыгранный удар (для продолжения серии после короткой паузы). */
    private static int lastStep = -1;
    /** Клик (или удержание) уже зацепил следующий удар: серия не обрывается. */
    private static boolean bufferedNext;
    /** Урон по текущему удару отправлен серверу (один раз за удар). */
    private static boolean sentHit;
    /** Кик от удара 0..1: пульс на момент разреза, затухает за ~0.5 сек.
     *  Двигает FOV и наклон камеры (как отдача в Genshin). */
    private static float impactKick;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        if (comboStep >= 0) {
            hitTicks++;
            if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                sentHit = true;
                sendHit(comboStep);
                spawnSlashEffects(client, client.player);
                applyLunge(client.player);
                impactKick = 1f;
            }
            if (hitTicks >= SwordCombo.DURATION_TICKS[comboStep]) {
                if (bufferedNext) {
                    // Серия продолжается: следующий удар (после пятого — снова первый).
                    comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
                    hitTicks = 0;
                    bufferedNext = false;
                    sentHit = false;
                } else {
                    // Удар закончился, серия держится ещё RESET_TICKS тиков:
                    // клик в этом окне продолжает комбо со следующего удара.
                    lastStep = comboStep;
                    comboStep = -1;
                    idleTicks = 0;
                }
            } else if (client.options.attackKey.isPressed()
                    && hitTicks >= SwordCombo.DAMAGE_TICKS[comboStep] + SwordCombo.CHAIN_INPUT_TICKS) {
                // Удержание ЛКМ: следующий удар цепляется автоматически (как в Genshin).
                bufferedNext = true;
            }
        } else if (idleTicks <= SwordCombo.RESET_TICKS) {
            idleTicks++;
        }
        // Отдача затухает сама: пульс виден пару кадров, затем камера успокаивается.
        impactKick *= 0.84f;
    }

    /** ЛКМ нажат (вместо ванильной атаки). Возвращает true, если клик съеден комбо. */
    public static boolean onAttackClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (comboStep < 0) {
            // Короткая пауза после удара не обрывает серию: продолжаем со следующего.
            if (idleTicks > 0 && idleTicks < SwordCombo.RESET_TICKS
                    && lastStep >= 0 && lastStep < SwordCombo.HIT_COUNT - 1) {
                comboStep = lastStep + 1;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
        } else {
            bufferedNext = true;
        }
        return true;
    }

    /** Пакет урона серверу: сервер сам находит цели в конусе перед игроком. */
    private static void sendHit(int hitIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new PlayerAttackPayload(hitIndex));
        }
    }

    /** Эффекты удара на тике урона: полумесяц-разрез, дуга искр по траектории
     *  меча и пыль из-под ног на «шагающих» ударах. Работают и по врагам,
     *  и по воздуху — свинг всегда анимируется. */
    private static void spawnSlashEffects(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        double yaw = Math.toRadians(player.getYaw());
        double d = -Math.sin(yaw);
        double e = Math.cos(yaw);
        // Полумесяц-разрез (ориентируется по дельтам, как ванильная свип-атака).
        world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                player.getX() + d * 0.6,
                player.getY() + 1.0 + comboStep * 0.06,
                player.getZ() + e * 0.6,
                d, 0.0, e);
        // Дуга размаха: штрихи-искры вдоль траектории меча, шире к пятому удару.
        int count = 9;
        double radius = 1.7;
        double height = 0.95 + comboStep * 0.09;
        float span = 1.05f + comboStep * 0.08f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.4, 0.0, -Math.sin(ang) * 1.4);
        }
        // Пыль из-под ног на ударах с шагом/выпадом.
        if (SwordCombo.LUNGE_STRENGTH[comboStep] >= 0.18f) {
            for (int i = 0; i < 3; i++) {
                world.addParticleClient(ParticleTypes.POOF,
                        player.getX() + (world.random.nextDouble() - 0.5) * 0.4,
                        player.getY() + 0.1,
                        player.getZ() + (world.random.nextDouble() - 0.5) * 0.4,
                        0, 0, 0);
            }
        }
    }

    /** Шаг героя вперёд на момент удара: лёгкий толчок по направлению взгляда,
     *  на пятом ударе — ещё и маленький подброс. Клиентское движение, как у рывка. */
    private static void applyLunge(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep];
        float up = SwordCombo.LUNGE_UP[comboStep];
        if (strength <= 0f && up <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y + up, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Идёт ли сейчас удар (для миксина модели). */
    public static boolean isSwinging() {
        return comboStep >= 0;
    }

    /** Отдача удара 0..1: пульс FOV и наклона камеры на момент разреза. */
    public static float impactKick() {
        return impactKick;
    }

    /** Номер текущего удара (0..4). */
    public static int getHitIndex() {
        return comboStep;
    }

    /** Прогресс текущего удара 0..1 (для анимации). */
    public static float getHitProgress() {
        if (comboStep < 0) {
            return 0f;
        }
        return SwordCombo.progress(hitTicks, comboStep);
    }

    /** Игрок ли это (для миксина: анимируем только локального путешественника). */
    public static boolean isLocalPlayer(int entityId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getId() == entityId;
    }

    /** Наложить позу удара на модель игрока (вызывается в конце setAngles).
     *  Прогресс считается по кадрам (тик + tickDelta), поэтому на высоком FPS
     *  движение остаётся плавным, а не шагает по 20 тикам в секунду. */
    public static void applyPose(PlayerEntityModel model) {
        if (comboStep < 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        float p = Math.min(1f, (hitTicks + tickDelta) / SwordCombo.DURATION_TICKS[comboStep]);
        applyPoseToModel(model, CLIPS[comboStep].at(p));
    }

    // ---------- Позы ----------
    // Анимации собраны как клипы ключевых кадров (time + поза + кривая), как в
    // профессиональных анимационных тулзах. Удар N заканчивается позой замаха
    // удара N+1 — серия льётся без стыков. Разрезы идут с быстрым началом
    // (OUT_CUBIC), обрушение пятого удара — с перелётом (OUT_BACK).

    /** Кривые интерполяции сегмента (задаются на ключевом кадре начала сегмента). */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;

    /** hPitch == NaN — голову не трогаем (она следует за взглядом, как в Genshin). */
    private record Pose(float rYaw, float rPitch, float rRoll,
                        float lYaw, float lPitch, float lRoll,
                        float bYaw, float bPitch, float hPitch,
                        float rLegPitch, float lLegPitch) {}

    private record Keyframe(float t, int easing, Pose pose) {}

    /** Клип удара: непрерывная последовательность ключевых кадров 0..1. */
    private record Clip(Keyframe[] frames) {
        Pose at(float p) {
            if (p <= frames[0].t) {
                return frames[0].pose;
            }
            for (int i = 0; i < frames.length - 1; i++) {
                Keyframe a = frames[i];
                Keyframe b = frames[i + 1];
                if (p <= b.t) {
                    float span = b.t - a.t;
                    float u = span <= 0f ? 1f : (p - a.t) / span;
                    return mix(a.pose, b.pose, ease(a.easing, u));
                }
            }
            return frames[frames.length - 1].pose;
        }
    }

    /** Нейтральная стойка (старт первого удара и конец пятого). */
    private static final Pose NEUTRAL = new Pose(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, Float.NaN, 0f, 0f);

    // Удар 1: горизонтальный разрез справа налево.
    private static final Pose W1 = new Pose(-1.15f, -1.0f, 0.3f, 0.4f, 0.35f, -0.2f, 0.35f, -0.05f, Float.NaN, -0.12f, 0.15f);
    private static final Pose S1 = new Pose(1.05f, -0.4f, -0.35f, -0.15f, 0.5f, -0.1f, -0.5f, 0.1f, Float.NaN, -0.12f, 0.15f);

    // Удар 2: обратный разрез слева направо.
    private static final Pose W2 = new Pose(1.0f, -0.5f, -0.3f, -0.25f, 0.5f, -0.1f, -0.45f, 0.05f, Float.NaN, 0.15f, -0.12f);
    private static final Pose S2 = new Pose(-1.2f, -0.95f, 0.3f, 0.55f, 0.25f, 0.15f, 0.5f, 0.1f, Float.NaN, 0.15f, -0.12f);

    // Удар 3: восходящий диагональный разрез снизу справа вверх налево (обе руки).
    private static final Pose W3 = new Pose(-0.7f, 0.55f, 0.25f, -0.35f, 0.7f, 0.1f, 0.35f, -0.15f, 0.05f, -0.15f, 0.18f);
    private static final Pose S3 = new Pose(0.55f, -2.6f, 0.0f, 0.35f, -2.4f, 0.0f, -0.4f, 0.2f, -0.3f, -0.15f, 0.18f);

    // Удар 4: выпад-укол вперёд (корпус наклонён, нога в шаге).
    private static final Pose W4 = new Pose(0.35f, -0.35f, -0.1f, 0.5f, 0.25f, -0.15f, -0.3f, 0.15f, 0.05f, 0.0f, 0.0f);
    private static final Pose S4 = new Pose(0.0f, -1.62f, 0.0f, 0.3f, -1.5f, -0.1f, 0.1f, 0.6f, 0.35f, -0.65f, 0.5f);

    // Удар 5: круговой замах над головой с обрушением.
    private static final Pose W5 = new Pose(1.25f, -2.65f, 0.15f, 1.05f, -2.5f, 0.1f, 1.15f, -0.15f, -0.05f, -0.2f, 0.22f);
    private static final Pose MID5 = new Pose(-1.2f, -2.15f, -0.1f, -1.0f, -2.0f, -0.05f, -1.15f, -0.1f, -0.05f, -0.2f, 0.22f);
    private static final Pose SLAM5 = new Pose(-0.65f, -0.45f, 0.0f, -0.45f, -0.35f, 0.0f, -0.5f, 0.35f, 0.1f, -0.3f, 0.3f);

    /** Клипы пяти ударов: последняя поза удара N — замах удара N+1 (серия без стыков). */
    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] {
                new Keyframe(0.0f, E_IN_OUT_CUBIC, NEUTRAL), // замах из стойки
                new Keyframe(0.30f, E_OUT_CUBIC, W1),
                new Keyframe(0.70f, E_IN_OUT_SINE, S1),      // разрез
                new Keyframe(1.0f, E_LINEAR, W2),            // возврат в замах 2-го
        }),
        new Clip(new Keyframe[] {
                new Keyframe(0.0f, E_OUT_CUBIC, W2),         // уже в замахе
                new Keyframe(0.45f, E_IN_OUT_SINE, S2),      // обратный разрез
                new Keyframe(1.0f, E_LINEAR, W3),
        }),
        new Clip(new Keyframe[] {
                new Keyframe(0.0f, E_OUT_CUBIC, W3),
                new Keyframe(0.45f, E_IN_OUT_SINE, S3),      // восходящий разрез
                new Keyframe(1.0f, E_LINEAR, W4),
        }),
        new Clip(new Keyframe[] {
                new Keyframe(0.0f, E_OUT_CUBIC, W4),
                new Keyframe(0.45f, E_IN_OUT_SINE, S4),      // выпад-укол
                new Keyframe(1.0f, E_LINEAR, W5),
        }),
        new Clip(new Keyframe[] {
                new Keyframe(0.0f, E_IN_OUT_CUBIC, W5),      // антиципация (меч за головой)
                new Keyframe(0.30f, E_OUT_CUBIC, W5),
                new Keyframe(0.60f, E_OUT_CUBIC, MID5),      // круговой разворот
                new Keyframe(0.86f, E_OUT_BACK, SLAM5),      // обрушение с перелётом
                new Keyframe(1.0f, E_IN_OUT_SINE, NEUTRAL),  // возврат в стойку
        }),
    };

    /** Смешать две позы (голова — NaN-безопасно: NaN «замирает» на другом значении). */
    private static Pose mix(Pose a, Pose b, float t) {
        return new Pose(
                lerp(t, a.rYaw(), b.rYaw()),
                lerp(t, a.rPitch(), b.rPitch()),
                lerp(t, a.rRoll(), b.rRoll()),
                lerp(t, a.lYaw(), b.lYaw()),
                lerp(t, a.lPitch(), b.lPitch()),
                lerp(t, a.lRoll(), b.lRoll()),
                lerp(t, a.bYaw(), b.bYaw()),
                lerp(t, a.bPitch(), b.bPitch()),
                lerpSafe(t, a.hPitch(), b.hPitch()),
                lerp(t, a.rLegPitch(), b.rLegPitch()),
                lerp(t, a.lLegPitch(), b.lLegPitch()));
    }

    /** Наложить позу на модель. Голова трогается, только если задана (не NaN). */
    private static void applyPoseToModel(PlayerEntityModel m, Pose pose) {
        m.rightArm.yaw = pose.rYaw();
        m.rightArm.pitch = pose.rPitch();
        m.rightArm.roll = pose.rRoll();
        m.leftArm.yaw = pose.lYaw();
        m.leftArm.pitch = pose.lPitch();
        m.leftArm.roll = pose.lRoll();
        m.body.yaw = pose.bYaw();
        m.body.pitch = pose.bPitch();
        if (!Float.isNaN(pose.hPitch())) {
            m.head.pitch = pose.hPitch();
        }
        m.rightLeg.pitch = pose.rLegPitch();
        m.leftLeg.pitch = pose.lLegPitch();
    }

    /** Кривые сегментов. OUT_BACK даёт перелёт ~10% — «хлыстовое» движение меча. */
    private static float ease(int kind, float t) {
        switch (kind) {
            case E_IN_OUT_CUBIC -> {
                return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            }
            case E_OUT_CUBIC -> {
                return 1f - (float) Math.pow(1f - t, 3);
            }
            case E_OUT_BACK -> {
                float c1 = 1.1f;
                float c3 = c1 + 1f;
                return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
            }
            case E_IN_OUT_SINE -> {
                return (float) (-(Math.cos(Math.PI * t) - 1.0) / 2.0);
            }
            default -> {
                return t;
            }
        }
    }

    private static float lerp(float t, float a, float b) {
        return MathHelper.lerp(t, a, b);
    }

    /** Lerp, который не даёт NaN: NaN заменяется на другое значение (голова «замирает»). */
    private static float lerpSafe(float t, float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        if (Float.isNaN(b)) {
            return a;
        }
        return MathHelper.lerp(t, a, b);
    }
}
