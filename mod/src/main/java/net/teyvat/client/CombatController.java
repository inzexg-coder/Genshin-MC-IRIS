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
 * Анимации — готовые клипы BetterCombat (автор Daedelus), подогнанные под
 * описание обычных атак путешественника из Genshin (scripts/port_bc_combo.py):
 * удар 1 — широкий слева направо, удар 2 — диагональ снизу справа вверх
 * налево, удар 3 — разворот по кругу, удар 4 — справа налево, удар 5 —
 * очень широкий с замахом за спину и прокатом. Хвосты-«удержания» обрезаны,
 * переходы живут в хвосте предыдущего удара (EASE_IN_OUT_SINE), углы
 * развёрнуты в непрерывную кривую — части тела не проворачиваются на ±360°.
 * Серия идёт в темпе игры (полный круг ~3.5 с): замах, разгон свинга,
 * сопровождение. После тапа — короткая фаза восстановления в нейтраль.
 * Удержание ЛКМ продолжает серию.
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
    /** Тики фазы восстановления после одиночного удара (поза тает в нейтраль). */
    private static int recoveryTicks;

    /** Тиков восстановления после одиночного удара: поза плавно уходит в нейтраль
     *  (без отскока в ванильную стойку между тапами). */
    private static final int RECOVERY_TICKS = 8;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        if (comboStep >= 0 && !client.player.isOnGround()) {
            // В воздухе удары не работают (как в Genshin): прыжок прерывает комбо,
            // следующий клик на земле начнёт серию заново.
            comboStep = -1;
            hitTicks = 0;
            recoveryTicks = 0;
            bufferedNext = false;
            sentHit = false;
            lastStep = -1;
            idleTicks = 0;
        }
        if (comboStep >= 0) {
            if (recoveryTicks > 0) {
                // Восстановление после одиночного удара: поза тает в нейтраль,
                // чтобы между тапами не было отскока в ванильную стойку.
                recoveryTicks--;
                if (recoveryTicks == 0) {
                    comboStep = -1;
                    idleTicks = 0;
                }
            } else {
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
                        // Тап: короткая фаза восстановления, клик в ней продолжает комбо.
                        lastStep = comboStep;
                        recoveryTicks = RECOVERY_TICKS;
                        bufferedNext = false;
                    }
                } else if (client.options.attackKey.isPressed()
                        && hitTicks >= SwordCombo.DAMAGE_TICKS[comboStep] + SwordCombo.CHAIN_INPUT_TICKS) {
                    // Удержание ЛКМ: следующий удар цепляется автоматически (как в Genshin).
                    bufferedNext = true;
                }
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
        if (!client.player.isOnGround()) {
            // В воздухе обычные атаки не работают (только приземление), клик съедается.
            return true;
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
        } else if (recoveryTicks > 0) {
            // Клик в фазе восстановления: сразу начинаем следующий удар серии.
            recoveryTicks = 0;
            comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
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
        Pose pose = CLIPS[comboStep].at(p);
        if (recoveryTicks > 0) {
            // Плавный уход в нейтраль после последнего удара (голова — за взглядом).
            float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
            pose = relax(pose, easeOutCubic(r));
        }
        applyPoseToModel(model, pose);
    }

    /** Смешать позу с нейтралью (голова уходит во взгляд игрока). */
    private static Pose relax(Pose p, float w) {
        return new Pose(
                lerp(w, p.rYaw(), 0f), lerp(w, p.rPitch(), 0f), lerp(w, p.rRoll(), 0f),
                lerp(w, p.lYaw(), 0f), lerp(w, p.lPitch(), 0f), lerp(w, p.lRoll(), 0f),
                lerp(w, p.bYaw(), 0f), lerp(w, p.bPitch(), 0f), lerp(w, p.bRoll(), 0f),
                Float.NaN, Float.NaN, Float.NaN,
                lerp(w, p.rlYaw(), 0f), lerp(w, p.rlPitch(), 0f), lerp(w, p.rlRoll(), 0f),
                lerp(w, p.llYaw(), 0f), lerp(w, p.llPitch(), 0f), lerp(w, p.llRoll(), 0f));
    }

    /** Ease-out cubic для восстановления: быстрое расслабление, мягкий выход. */
    private static float easeOutCubic(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    // ---------- Позы ----------
    // Анимации — клипы ключевых кадров (time + поза + кривая), сгенерированы
    // скриптом scripts/gen_genshin_combo.py по описанию боевки путешественника
    // из Genshin: стыки — EASE_IN_OUT_SINE в хвосте удара, финал 5-го уходит
    // в нейтральную стойку.

    /** Кривые интерполяции сегмента: замах — E_IN_OUT_CUBIC, свинг — E_LINEAR,
     *  сопровождение — E_OUT_CUBIC, переходы — E_IN_OUT_SINE. */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;

    /** NaN-канал не трогаем (у головы NaN = следует за взглядом игрока).
     *  Порядок: правая рука y/p/r, левая рука y/p/r, корпус y/p/r,
     *  голова y/p/r, правая нога y/p/r, левая нога y/p/r (радианы). */
    private record Pose(float rYaw, float rPitch, float rRoll,
                        float lYaw, float lPitch, float lRoll,
                        float bYaw, float bPitch, float bRoll,
                        float hYaw, float hPitch, float hRoll,
                        float rlYaw, float rlPitch, float rlRoll,
                        float llYaw, float llPitch, float llRoll) {}

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

// Стыки между ударами — 0.000 (конец N = начало N+1, переход в хвосте N).
// Момент урона (пик скорости меча, доля клипа, см. scripts/port_bc_combo.py):
//   hit2_slash_l2r (L->R):   u=0.41 -> DAMAGE_TICK=5
//   hit3_uppercut:           u=0.33 -> DAMAGE_TICK=4
//   hit5_spin (круг):        u=0.56 -> DAMAGE_TICK=9
//   hit1_slash_r2l (R->L):   u=0.33 -> DAMAGE_TICK=4
//   hit5_slam:               u=0.33 -> DAMAGE_TICK=6
// Стыки (макс. разница позы между концом N и началом N+1, рад):
//   -> hit3_uppercut (R low -> L up): 0.000
//   -> hit5_spin (circle turn): 0.000
//   -> hit1_slash_r2l (R->L): 0.000
//   -> hit5_slam (wide slam + lunge): 0.000
// Удар/тик урона (пик скорости меча, доля клипа):
//   hit2_slash_l2r (L->R): u=0.407 speed=349.7 -> DAMAGE_TICK=5
//   hit3_uppercut (R low -> L up): u=0.290 speed=219.9 -> DAMAGE_TICK=3
//   hit5_spin (circle turn): u=0.760 speed=296.7 -> DAMAGE_TICK=12
//   hit1_slash_r2l (R->L): u=0.370 speed=286.4 -> DAMAGE_TICK=4
//   hit5_slam (wide slam + lunge): u=0.260 speed=252.0 -> DAMAGE_TICK=5
// Профиль движения на 20 FPS (avg/max скорость позы, замороженных кадров <0.03):
//   hit2_slash_l2r (L->R): dur=12 avg=0.046 max=0.11 frozen=50/240
//   hit3_uppercut (R low -> L up): dur=12 avg=0.034 max=0.10 frozen=97/240
//   hit5_spin (circle turn): dur=16 avg=0.037 max=0.14 frozen=103/320
//   hit1_slash_r2l (R->L): dur=12 avg=0.039 max=0.07 frozen=57/240
//   hit5_slam (wide slam + lunge): dur=18 avg=0.018 max=0.05 frozen=301/360

    /** Позы пяти ударов, сгенерированы из готовых анимаций BetterCombat
     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources, автор
     *  анимаций Daedelus): удар 1 — слева направо (slash horizontal left),
     *  удар 2 — апперкот справа (uppercut right), удар 3 — разворот
     *  по кругу (spin), удар 4 — справа налево (slash horizontal right),
     *  удар 5 — широкий замах за спину и обрушение (slam).
     *  Хвосты-«удержания» обрезаны, стыки живут в хвосте предыдущего удара,
     *  переходные сегменты — EASE_IN_OUT_SINE (плавные развороты корпуса),
     *  углы развёрнуты в непрерывную кривую (без проворотов на ±360°).
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,
     *  голова y/p/r (NaN — не трогаем, голова за взглядом), прав. нога y/p/r,
     *  лев. нога y/p/r. Углы в радианах, как у ModelPart (pitch -> yaw -> roll). */
    // Удар 1: hit2_slash_l2r (L->R)
    private static final Pose hit1_00 = new Pose(0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(-0.914577f, -1.415769f, 0.209705f, -0.537352f, 0.602105f, -0.277980f, 0.987599f, 0.100273f, 0.063954f, 1.020421f, 0.096635f, 0.051059f, 1.055048f, -0.071385f, 0.111006f, 0.982606f, 0.218959f, -0.084547f);
    private static final Pose hit1_02 = new Pose(-0.818640f, -1.454618f, 0.192531f, -0.528952f, 0.588388f, -0.291665f, 0.881116f, 0.067253f, 0.056337f, 0.907117f, 0.066095f, 0.045967f, 0.937875f, -0.038021f, 0.097923f, 0.872171f, 0.140674f, -0.070519f);
    private static final Pose hit1_03 = new Pose(-0.530830f, -1.571163f, 0.141008f, -0.503753f, 0.547239f, -0.332721f, 0.561668f, -0.031807f, 0.033487f, 0.567205f, -0.025526f, 0.030690f, 0.586358f, 0.062069f, 0.058672f, 0.540865f, -0.094179f, -0.028435f);
    private static final Pose hit1_04 = new Pose(-0.051145f, -1.765404f, 0.055136f, -0.461754f, 0.478656f, -0.401146f, 0.029255f, -0.196907f, -0.004596f, 0.000684f, -0.178228f, 0.005228f, 0.000496f, 0.228886f, -0.006747f, -0.011312f, -0.485601f, 0.041706f);
    private static final Pose hit1_05 = new Pose(0.586137f, -2.080511f, -0.380814f, -0.410851f, 0.395534f, -0.425327f, -0.129229f, -0.196118f, -0.004782f, -0.145923f, -0.186087f, 0.036998f, -0.146242f, 0.216702f, 0.039555f, -0.167775f, -0.472075f, 0.054622f);
    private static final Pose hit1_06 = new Pose(0.998496f, -2.284404f, -0.662900f, -0.269454f, 0.164639f, -0.492493f, -0.258898f, -0.195473f, -0.004934f, -0.265875f, -0.192516f, 0.062992f, -0.266300f, 0.206733f, 0.077438f, -0.295791f, -0.461009f, 0.065189f);
    private static final Pose hit1_07 = new Pose(1.185932f, -2.377083f, -0.791120f, -0.184616f, 0.026102f, -0.532794f, -0.359751f, -0.194971f, -0.005052f, -0.359171f, -0.197517f, 0.083209f, -0.359678f, 0.198979f, 0.106903f, -0.395358f, -0.452401f, 0.073409f);
    private static final Pose hit1_08 = new Pose(1.224356f, -2.439909f, -0.856132f, -0.163281f, -0.008738f, -0.539861f, -0.431790f, -0.194613f, -0.005137f, -0.425811f, -0.201089f, 0.097650f, -0.426377f, 0.193441f, 0.127949f, -0.466478f, -0.446253f, 0.079280f);
    private static final Pose hit1_09 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.002012f, -0.395345f, -0.193437f, -0.459073f, 0.208042f, -0.000000f, -0.443140f, 0.230391f, -0.098982f, -0.000000f, 0.421944f, 0.000000f, -0.447994f, 0.169247f, -0.084622f);
    private static final Pose hit1_10 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.002012f, -0.395345f, -0.193437f, -0.459073f, 0.208042f, -0.000000f, -0.443140f, 0.230391f, -0.098982f, -0.000000f, 0.421944f, 0.000000f, -0.447994f, 0.169247f, -0.084622f);
    // Удар 2: hit3_uppercut (R low -> L up)
    private static final Pose hit2_00 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.002012f, -0.395345f, -0.193437f, -0.459073f, 0.208042f, -0.000000f, -0.443140f, 0.230391f, -0.098982f, -0.000000f, 0.421944f, 0.000000f, -0.447994f, 0.169247f, -0.084622f);
    private static final Pose hit2_01 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.002012f, -0.395345f, -0.193437f, -0.459073f, 0.208042f, -0.000000f, -0.443140f, 0.230391f, -0.098982f, -0.000000f, 0.421944f, 0.000000f, -0.447994f, 0.169247f, -0.084622f);
    private static final Pose hit2_02 = new Pose(0.221309f, -6.078504f, 0.285732f, -0.005953f, -0.292680f, -0.196538f, 0.265448f, -0.029857f, -0.037675f, 0.240057f, -0.011576f, 0.045287f, 0.116194f, 0.387275f, 0.035834f, 0.255703f, 0.189656f, 0.048886f);
    private static final Pose hit2_03 = new Pose(0.507131f, -6.916054f, 0.291184f, -0.029849f, 0.015316f, -0.205841f, 0.633078f, -0.056817f, -0.078771f, 0.630742f, -0.085021f, 0.040623f, 0.464777f, 0.283269f, 0.143336f, 0.530995f, 0.184669f, 0.046670f);
    private static final Pose hit2_04 = new Pose(0.792954f, -7.753604f, 0.296637f, -0.069675f, 0.528643f, -0.221346f, 1.000709f, -0.083777f, -0.119866f, 1.021426f, -0.158466f, 0.035960f, 1.045748f, 0.109924f, 0.322506f, 0.806288f, 0.179682f, 0.044454f);
    private static final Pose hit2_05 = new Pose(0.787992f, -7.999039f, 0.512870f, -0.274284f, 0.560782f, -0.246123f, 0.991166f, -0.156952f, -0.170747f, 1.021426f, -0.158466f, 0.035960f, 1.041525f, 0.087540f, 0.296649f, 0.808461f, 0.218171f, 0.030270f);
    private static final Pose hit2_06 = new Pose(0.784448f, -8.174350f, 0.667322f, -0.420433f, 0.583739f, -0.263821f, 0.984350f, -0.209220f, -0.207091f, 1.021426f, -0.158466f, 0.035960f, 1.038508f, 0.071552f, 0.278179f, 0.810013f, 0.245664f, 0.020137f);
    private static final Pose hit2_07 = new Pose(0.782322f, -8.279536f, 0.759993f, -0.508122f, 0.597513f, -0.274440f, 0.980260f, -0.240581f, -0.228897f, 1.021426f, -0.158466f, 0.035960f, 1.036697f, 0.061959f, 0.267097f, 0.810945f, 0.262159f, 0.014058f);
    private static final Pose hit2_08 = new Pose(0.781613f, -8.314598f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.811255f, 0.267658f, 0.012032f);
    private static final Pose hit2_09 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.116713f, -1.293193f, 1.296793f, 1.239308f, 0.014780f, 0.196205f, 1.185642f, -0.379294f, -0.337169f, 1.140377f, -0.212141f, -0.120930f, -0.334243f, -0.189551f, -0.104693f);
    private static final Pose hit2_10 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.116713f, -1.293193f, 1.296793f, 1.239308f, 0.014780f, 0.196205f, 1.185642f, -0.379294f, -0.337169f, 1.140377f, -0.212141f, -0.120930f, -0.334243f, -0.189551f, -0.104693f);
    // Удар 3: hit5_spin (circle turn)
    private static final Pose hit3_00 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.116713f, -1.293193f, 1.296793f, 1.239308f, 0.014780f, 0.196205f, 1.185642f, -0.379294f, -0.337169f, 1.140377f, -0.212141f, -0.120930f, -0.334243f, -0.189551f, -0.104693f);
    private static final Pose hit3_01 = new Pose(-0.000275f, -8.438595f, 1.667136f, 0.124170f, -1.297349f, 1.279355f, 0.422596f, -0.046436f, 0.073686f, 0.413545f, -0.089573f, -0.114431f, 0.034578f, -0.394474f, -0.004931f, -0.273098f, 0.239120f, 0.124554f);
    private static final Pose hit3_02 = new Pose(-0.032040f, -8.436364f, 1.678197f, 0.131626f, -1.301505f, 1.261916f, -0.818030f, -0.030844f, 0.117760f, 0.380058f, -0.084555f, -0.110166f, 0.050988f, -0.553961f, 0.026657f, -0.231403f, 0.059955f, -0.012946f);
    private static final Pose hit3_03 = new Pose(-0.090441f, -8.129817f, 1.676600f, 0.173789f, -1.099354f, 1.304077f, -2.359685f, 0.000000f, 0.000000f, 0.279597f, -0.069501f, -0.097368f, 0.106157f, -0.852696f, 0.060497f, -0.240121f, 0.084449f, -0.036222f);
    private static final Pose hit3_04 = new Pose(-0.088135f, -8.051431f, 1.624424f, 0.155052f, -1.177090f, 1.259403f, -3.939208f, -0.034907f, -0.122173f, 0.128905f, -0.046920f, -0.078173f, 0.248226f, -0.986102f, 0.245824f, -0.248839f, 0.108943f, -0.059498f);
    private static final Pose hit3_05 = new Pose(-0.081217f, -7.816271f, 1.467897f, 0.098843f, -1.410295f, 1.125379f, -5.410521f, -0.087266f, 0.069813f, 0.028443f, -0.031866f, -0.065376f, 0.742532f, -0.446192f, 0.357076f, -0.299005f, 0.073225f, -0.044756f);
    private static final Pose hit3_06 = new Pose(-0.074299f, -7.581112f, 1.311370f, 0.042633f, -1.643501f, 0.991355f, -6.283185f, 0.000000f, 0.000000f, -0.005044f, -0.026848f, -0.061110f, 0.550299f, -0.429929f, 0.200857f, -0.349172f, 0.037506f, -0.030013f);
    private static final Pose hit3_07 = new Pose(0.956147f, -5.899650f, 1.512854f, 0.002012f, -0.395345f, -0.193437f, -6.742259f, 0.208042f, -0.000000f, -0.442740f, 0.022581f, -0.100256f, 0.530467f, 0.212328f, 0.217910f, -0.488093f, 0.207120f, -0.141800f);
    private static final Pose hit3_08 = new Pose(0.956147f, -5.899650f, 1.512854f, 0.002012f, -0.395345f, -0.193437f, -6.742259f, 0.208042f, -0.000000f, -0.442740f, 0.022581f, -0.100256f, 0.530467f, 0.212328f, 0.217910f, -0.488093f, 0.207120f, -0.141800f);
    // Удар 4: hit1_slash_r2l (R->L)
    private static final Pose hit4_00 = new Pose(0.956147f, -5.899650f, 1.512854f, 0.002012f, -0.395345f, -0.193437f, -6.742259f, 0.208042f, -0.000000f, -0.442740f, 0.022581f, -0.100256f, 0.530467f, 0.212328f, 0.217910f, -0.488093f, 0.207120f, -0.141800f);
    private static final Pose hit4_01 = new Pose(0.956147f, -5.899650f, 1.512854f, 0.002012f, -0.395345f, -0.193437f, -6.742259f, 0.208042f, -0.000000f, -0.442740f, 0.022581f, -0.100256f, 0.530467f, 0.212328f, 0.217910f, -0.488093f, 0.207120f, -0.141800f);
    private static final Pose hit4_02 = new Pose(0.947461f, -6.112549f, 1.366530f, -0.005953f, -0.292680f, -0.196538f, -6.017737f, -0.029857f, -0.037675f, 0.264201f, -0.017201f, 0.028019f, 0.587857f, 0.199789f, 0.229462f, -0.199124f, 0.224970f, -0.115307f);
    private static final Pose hit4_03 = new Pose(0.921403f, -6.751248f, 0.927559f, -0.029849f, 0.015316f, -0.205841f, -5.661013f, -0.140446f, -0.152763f, 0.642813f, -0.087833f, 0.031990f, 0.760027f, 0.162170f, 0.264119f, 0.523299f, 0.269595f, -0.049075f);
    private static final Pose hit4_04 = new Pose(0.877973f, -7.815746f, 0.195940f, -0.069675f, 0.528643f, -0.221346f, -5.304289f, -0.251035f, -0.267850f, 1.021426f, -0.158466f, 0.035960f, 1.046977f, 0.099473f, 0.321880f, 0.812269f, 0.287445f, -0.022582f);
    private static final Pose hit4_05 = new Pose(0.206221f, -7.754565f, 0.188703f, -0.274284f, 0.560782f, -0.246123f, -5.304288f, -0.251035f, -0.253988f, 1.021461f, -0.152545f, 0.036231f, 1.043958f, 0.061916f, 0.273386f, 0.780447f, 0.185424f, -0.082044f);
    private static final Pose hit4_06 = new Pose(-0.273602f, -7.710864f, 0.183535f, -0.420433f, 0.583739f, -0.263821f, -5.304288f, -0.251035f, -0.244087f, 1.021565f, -0.134785f, 0.037043f, 1.041802f, 0.035090f, 0.238748f, 0.757717f, 0.112551f, -0.124518f);
    private static final Pose hit4_07 = new Pose(-0.561496f, -7.684644f, 0.180433f, -0.508122f, 0.597513f, -0.274440f, -5.304288f, -0.251035f, -0.238146f, 1.021669f, -0.117024f, 0.037856f, 1.040508f, 0.018994f, 0.217965f, 0.744079f, 0.068828f, -0.150001f);
    private static final Pose hit4_08 = new Pose(-0.657461f, -7.675904f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, -5.304288f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit4_09 = new Pose(0.408970f, -10.091478f, 0.247034f, -0.055411f, -1.310241f, -0.216444f, -6.283185f, 0.513680f, -0.000000f, Float.NaN, Float.NaN, Float.NaN, -0.000000f, 0.417763f, 0.000000f, 0.000000f, -0.174533f, -0.000000f);
    private static final Pose hit4_10 = new Pose(0.408970f, -10.091478f, 0.247034f, -0.055411f, -1.310241f, -0.216444f, -6.283185f, 0.513680f, -0.000000f, Float.NaN, Float.NaN, Float.NaN, -0.000000f, 0.417763f, 0.000000f, 0.000000f, -0.174533f, -0.000000f);
    // Удар 5: hit5_slam (wide slam + lunge)
    private static final Pose hit5_00 = new Pose(0.408970f, -10.091478f, 0.247034f, -0.055411f, -1.310241f, -0.216444f, -6.283185f, 0.513680f, -0.000000f, Float.NaN, Float.NaN, Float.NaN, -0.000000f, 0.417763f, 0.000000f, 0.000000f, -0.174533f, -0.000000f);
    private static final Pose hit5_01 = new Pose(0.408970f, -10.091478f, 0.247034f, -0.055411f, -1.310241f, -0.216444f, -6.283185f, 0.513680f, -0.000000f, Float.NaN, Float.NaN, Float.NaN, -0.000000f, 0.417763f, 0.000000f, 0.000000f, -0.174533f, -0.000000f);
    private static final Pose hit5_02 = new Pose(0.213933f, -9.585952f, 0.238369f, -0.276384f, -0.961582f, -0.458206f, -6.291961f, 0.188873f, -0.000478f, Float.NaN, Float.NaN, Float.NaN, -0.003395f, 0.299891f, -0.006640f, -0.004074f, -0.356611f, 0.003303f);
    private static final Pose hit5_03 = new Pose(-0.273659f, -8.322137f, 0.216706f, -0.828818f, -0.089936f, -1.062609f, -6.313901f, -0.623144f, -0.001673f, Float.NaN, Float.NaN, Float.NaN, -0.011882f, 0.005210f, -0.023241f, -0.014258f, -0.811807f, 0.011559f);
    private static final Pose hit5_04 = new Pose(-0.468696f, -7.816611f, 0.208041f, -1.049791f, 0.258722f, -1.304370f, -6.322677f, -0.947951f, -0.002151f, Float.NaN, Float.NaN, Float.NaN, -0.015277f, -0.112662f, -0.029881f, -0.018332f, -0.993886f, 0.014862f);
    private static final Pose hit5_05 = new Pose(-0.468696f, -7.816611f, 0.208041f, -1.049791f, 0.258722f, -1.304370f, -6.322676f, -0.949399f, -0.002094f, Float.NaN, Float.NaN, Float.NaN, -0.015441f, -0.115251f, -0.029880f, -0.018355f, -0.995136f, 0.014859f);
    private static final Pose hit5_06 = new Pose(-0.351522f, -7.433255f, 0.156030f, -0.787343f, 0.194042f, -0.978278f, -6.312804f, -0.712049f, -0.001570f, Float.NaN, Float.NaN, Float.NaN, -0.011581f, -0.086439f, -0.022410f, -0.013766f, -0.746352f, 0.011144f);
    private static final Pose hit5_07 = new Pose(-0.140609f, -6.743213f, 0.062412f, -0.314937f, 0.077617f, -0.391311f, -6.295033f, -0.284820f, -0.000628f, Float.NaN, Float.NaN, Float.NaN, -0.004632f, -0.034575f, -0.008964f, -0.005507f, -0.298541f, 0.004458f);
    private static final Pose hit5_08 = new Pose(0.000000f, -6.283185f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -6.283185f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    /** Клипы пяти ударов: конец N = начало N+1 (переход в хвосте удара N). */
    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // удар 1: hit2_slash_l2r (L->R)
                new Keyframe(0.000f, 4, hit1_00),
                new Keyframe(0.160f, 4, hit1_01),
                new Keyframe(0.233f, 0, hit1_02),
                new Keyframe(0.307f, 0, hit1_03),
                new Keyframe(0.380f, 0, hit1_04),
                new Keyframe(0.435f, 0, hit1_05),
                new Keyframe(0.490f, 0, hit1_06),
                new Keyframe(0.545f, 0, hit1_07),
                new Keyframe(0.600f, 0, hit1_08),
                new Keyframe(1.006f, 4, hit1_09),
                new Keyframe(1.000f, 4, hit1_10),
        }),
        new Clip(new Keyframe[] { // удар 2: hit3_uppercut (R low -> L up)
                new Keyframe(0.000f, 4, hit2_00),
                new Keyframe(0.040f, 4, hit2_01),
                new Keyframe(0.140f, 0, hit2_02),
                new Keyframe(0.240f, 0, hit2_03),
                new Keyframe(0.340f, 0, hit2_04),
                new Keyframe(0.400f, 0, hit2_05),
                new Keyframe(0.460f, 0, hit2_06),
                new Keyframe(0.520f, 0, hit2_07),
                new Keyframe(0.580f, 0, hit2_08),
                new Keyframe(0.898f, 4, hit2_09),
                new Keyframe(1.000f, 4, hit2_10),
        }),
        new Clip(new Keyframe[] { // удар 3: hit5_spin (circle turn)
                new Keyframe(0.000f, 4, hit3_00),
                new Keyframe(0.060f, 0, hit3_01),
                new Keyframe(0.188f, 0, hit3_02),
                new Keyframe(0.316f, 0, hit3_03),
                new Keyframe(0.444f, 0, hit3_04),
                new Keyframe(0.572f, 0, hit3_05),
                new Keyframe(0.700f, 0, hit3_06),
                new Keyframe(0.820f, 4, hit3_07),
                new Keyframe(1.000f, 4, hit3_08),
        }),
        new Clip(new Keyframe[] { // удар 4: hit1_slash_r2l (R->L)
                new Keyframe(0.000f, 4, hit4_00),
                new Keyframe(0.040f, 4, hit4_01),
                new Keyframe(0.140f, 0, hit4_02),
                new Keyframe(0.240f, 0, hit4_03),
                new Keyframe(0.340f, 0, hit4_04),
                new Keyframe(0.400f, 0, hit4_05),
                new Keyframe(0.460f, 0, hit4_06),
                new Keyframe(0.520f, 0, hit4_07),
                new Keyframe(0.580f, 0, hit4_08),
                new Keyframe(0.959f, 4, hit4_09),
                new Keyframe(1.000f, 4, hit4_10),
        }),
        new Clip(new Keyframe[] { // удар 5: hit5_slam (wide slam + lunge)
                new Keyframe(0.000f, 4, hit5_00),
                new Keyframe(0.080f, 0, hit5_01),
                new Keyframe(0.200f, 0, hit5_02),
                new Keyframe(0.320f, 0, hit5_03),
                new Keyframe(0.440f, 0, hit5_04),
                new Keyframe(0.560f, 0, hit5_05),
                new Keyframe(0.700f, 4, hit5_06),
                new Keyframe(0.860f, 4, hit5_07),
                new Keyframe(1.000f, 4, hit5_08),
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
                lerp(t, a.bRoll(), b.bRoll()),
                lerpSafe(t, a.hYaw(), b.hYaw()),
                lerpSafe(t, a.hPitch(), b.hPitch()),
                lerpSafe(t, a.hRoll(), b.hRoll()),
                lerp(t, a.rlYaw(), b.rlYaw()),
                lerp(t, a.rlPitch(), b.rlPitch()),
                lerp(t, a.rlRoll(), b.rlRoll()),
                lerp(t, a.llYaw(), b.llYaw()),
                lerp(t, a.llPitch(), b.llPitch()),
                lerp(t, a.llRoll(), b.llRoll()));
    }

    /** Наложить позу на модель: руки, корпус, голова (NaN — не трогаем), ноги. */
    private static void applyPoseToModel(PlayerEntityModel m, Pose pose) {
        m.rightArm.yaw = pose.rYaw();
        m.rightArm.pitch = pose.rPitch();
        m.rightArm.roll = pose.rRoll();
        m.leftArm.yaw = pose.lYaw();
        m.leftArm.pitch = pose.lPitch();
        m.leftArm.roll = pose.lRoll();
        m.body.yaw = pose.bYaw();
        m.body.pitch = pose.bPitch();
        m.body.roll = pose.bRoll();
        if (!Float.isNaN(pose.hYaw())) {
            m.head.yaw = pose.hYaw();
        }
        if (!Float.isNaN(pose.hPitch())) {
            m.head.pitch = pose.hPitch();
        }
        if (!Float.isNaN(pose.hRoll())) {
            m.head.roll = pose.hRoll();
        }
        m.rightLeg.yaw = pose.rlYaw();
        m.rightLeg.pitch = pose.rlPitch();
        m.rightLeg.roll = pose.rlRoll();
        m.leftLeg.yaw = pose.llYaw();
        m.leftLeg.pitch = pose.llPitch();
        m.leftLeg.roll = pose.llRoll();
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
