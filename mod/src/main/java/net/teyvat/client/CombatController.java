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
 * Анимации — готовые ключевые кадры из BetterCombat (scripts/port_bc_combo.py,
 * исходники в scripts/anim_sources), пересэмплированные в data-driven клипы
 * (record Clip/Keyframe) и склеенные в серию: удар 1 — горизонтальный разрез
 * справа налево, удар 2 — обратный восходящий, удар 3 — восходящий разрез,
 * удар 4 — выпад-укол, удар 5 — разворот над головой с обрушением вниз.
 * Начало каждого удара совпадает с концом предыдущего (бленд в конвертере),
 * финал 5-го плавно возвращается в нейтральную стойку. Удержание ЛКМ
 * продолжает серию, тап — один удар.
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
    // Анимации — клипы ключевых кадров (time + поза + кривая). Данные
    // сгенерированы из готовых анимаций BetterCombat (scripts/port_bc_combo.py):
    // сэмплы на равномерной сетке с точным воспроизведением исходных кривых,
    // стыки ударов склеены блендом, финал 5-го уходит в нейтральную стойку.

    /** Кривые интерполяции сегмента (у сгенерированных клипов — E_LINEAR между сэмплами). */
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

    /** Позы пяти ударов, сгенерированы из анимаций BetterCombat
     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources).
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,
     *  голова y/p/r (NaN — не трогаем, голова за взглядом), прав. нога y/p/r,
     *  лев. нога y/p/r. Углы в радианах, как у ModelPart (pitch -> yaw -> roll). */
    // Удар 1: hit1_slash_r2l.json
    private static final Pose hit1_00 = new Pose(0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(0.956147f, 0.383536f, 1.512854f, 0.002012f, -0.395345f, -0.193437f, -0.459073f, 0.208042f, -0.000000f, -0.442740f, 0.022581f, -0.100256f, 0.530467f, 0.212328f, 0.217910f, -0.488093f, 0.207120f, -0.141800f);
    private static final Pose hit1_02 = new Pose(0.947461f, 0.170636f, 1.366530f, -0.005953f, -0.292680f, -0.196538f, 0.265448f, -0.029857f, -0.037675f, 0.264201f, -0.017201f, 0.028019f, 0.587857f, 0.199789f, 0.229462f, -0.199124f, 0.224970f, -0.115307f);
    private static final Pose hit1_03 = new Pose(0.921403f, -0.468063f, 0.927559f, -0.029849f, 0.015316f, -0.205841f, 0.622172f, -0.140446f, -0.152763f, 0.642813f, -0.087833f, 0.031990f, 0.760027f, 0.162170f, 0.264119f, 0.523299f, 0.269595f, -0.049075f);
    private static final Pose hit1_04 = new Pose(0.877973f, -1.532561f, 0.195940f, -0.069675f, 0.528643f, -0.221346f, 0.978897f, -0.251035f, -0.267850f, 1.021426f, -0.158466f, 0.035960f, 1.046977f, 0.099473f, 0.321880f, 0.812269f, 0.287445f, -0.022582f);
    private static final Pose hit1_05 = new Pose(0.206221f, -1.471380f, 0.188703f, -0.274284f, 0.560782f, -0.246123f, 0.978897f, -0.251035f, -0.253988f, 1.021461f, -0.152545f, 0.036231f, 1.043958f, 0.061916f, 0.273386f, 0.780447f, 0.185424f, -0.082044f);
    private static final Pose hit1_06 = new Pose(-0.273602f, -1.427679f, 0.183535f, -0.420433f, 0.583739f, -0.263821f, 0.978897f, -0.251035f, -0.244087f, 1.021565f, -0.134785f, 0.037043f, 1.041802f, 0.035090f, 0.238748f, 0.757717f, 0.112551f, -0.124518f);
    private static final Pose hit1_07 = new Pose(-0.561496f, -1.401458f, 0.180433f, -0.508122f, 0.597513f, -0.274440f, 0.978897f, -0.251035f, -0.238146f, 1.021669f, -0.117024f, 0.037856f, 1.040508f, 0.018994f, 0.217965f, 0.744079f, 0.068828f, -0.150001f);
    private static final Pose hit1_08 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit1_09 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit1_10 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit1_11 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit1_12 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit1_13 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    // Удар 2: hit2_slash_l2r.json
    private static final Pose hit2_00 = new Pose(-0.657461f, -1.392718f, 0.179400f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021704f, -0.111104f, 0.038127f, 1.040077f, 0.013629f, 0.211038f, 0.739533f, 0.054253f, -0.158496f);
    private static final Pose hit2_01 = new Pose(-0.697755f, -1.408193f, 0.182683f, -0.535252f, 0.598676f, -0.281401f, 0.954452f, -0.171463f, -0.163040f, 0.993057f, -0.066804f, 0.040087f, 1.014526f, 0.000716f, 0.182759f, 0.772692f, 0.075858f, -0.136502f);
    private static final Pose hit2_02 = new Pose(-0.562487f, -1.526551f, 0.150606f, -0.512152f, 0.560955f, -0.319035f, 0.665975f, -0.086614f, -0.033926f, 0.680830f, -0.046921f, 0.032549f, 0.699788f, 0.049959f, 0.096763f, 0.590532f, -0.057071f, -0.060950f);
    private static final Pose hit2_03 = new Pose(-0.051145f, -1.765404f, 0.055136f, -0.461754f, 0.478656f, -0.401146f, 0.029255f, -0.196907f, -0.004596f, 0.000684f, -0.178228f, 0.005228f, 0.000496f, 0.228886f, -0.006747f, -0.011312f, -0.485601f, 0.041706f);
    private static final Pose hit2_04 = new Pose(0.398701f, -1.987833f, -0.252593f, -0.439130f, 0.441713f, -0.411893f, -0.079603f, -0.196365f, -0.004723f, -0.100016f, -0.183626f, 0.027050f, -0.100293f, 0.220517f, 0.025056f, -0.118782f, -0.476311f, 0.050577f);
    private static final Pose hit2_05 = new Pose(0.748582f, -2.160833f, -0.491939f, -0.371260f, 0.330883f, -0.444133f, -0.175654f, -0.195887f, -0.004836f, -0.188869f, -0.188388f, 0.046304f, -0.189225f, 0.213133f, 0.053118f, -0.213608f, -0.468113f, 0.058405f);
    private static final Pose hit2_06 = new Pose(0.998496f, -2.284404f, -0.662900f, -0.269454f, 0.164639f, -0.492493f, -0.258898f, -0.195473f, -0.004934f, -0.265875f, -0.192516f, 0.062992f, -0.266300f, 0.206733f, 0.077438f, -0.295791f, -0.461009f, 0.065189f);
    private static final Pose hit2_07 = new Pose(1.148445f, -2.358547f, -0.765476f, -0.201584f, 0.053809f, -0.524734f, -0.329335f, -0.195122f, -0.005017f, -0.331034f, -0.196009f, 0.077112f, -0.331516f, 0.201318f, 0.098017f, -0.365330f, -0.454997f, 0.070930f);
    private static final Pose hit2_08 = new Pose(1.198428f, -2.383261f, -0.799668f, -0.178960f, 0.016866f, -0.535480f, -0.386966f, -0.194836f, -0.005084f, -0.384346f, -0.198866f, 0.088664f, -0.384875f, 0.196887f, 0.114854f, -0.422225f, -0.450079f, 0.075627f);
    private static final Pose hit2_09 = new Pose(1.224356f, -2.439909f, -0.856132f, -0.163281f, -0.008738f, -0.539861f, -0.431790f, -0.194613f, -0.005137f, -0.425811f, -0.201089f, 0.097650f, -0.426377f, 0.193441f, 0.127949f, -0.466478f, -0.446253f, 0.079280f);
    private static final Pose hit2_10 = new Pose(1.302139f, -2.609852f, -1.025521f, -0.116242f, -0.085551f, -0.553003f, -0.463807f, -0.194453f, -0.005175f, -0.455428f, -0.202676f, 0.104068f, -0.456021f, 0.190979f, 0.137303f, -0.498086f, -0.443521f, 0.081889f);
    private static final Pose hit2_11 = new Pose(1.379923f, -2.779795f, -1.194910f, -0.069203f, -0.162363f, -0.566145f, -0.483017f, -0.194358f, -0.005197f, -0.473199f, -0.203629f, 0.107919f, -0.473807f, 0.189503f, 0.142916f, -0.517052f, -0.441881f, 0.083454f);
    private static final Pose hit2_12 = new Pose(1.405851f, -2.836443f, -1.251374f, -0.053523f, -0.187967f, -0.570525f, -0.489420f, -0.194326f, -0.005205f, -0.479122f, -0.203946f, 0.109202f, -0.479736f, 0.189010f, 0.144786f, -0.523373f, -0.441335f, 0.083976f);
    // Удар 3: hit3_uppercut.json
    private static final Pose hit3_00 = new Pose(1.405851f, -2.836443f, -1.251374f, -0.053523f, -0.187967f, -0.570525f, -0.489420f, -0.194326f, -0.005205f, -0.479122f, -0.203946f, 0.109202f, -0.479736f, 0.189010f, 0.144786f, -0.523373f, -0.441335f, 0.083976f);
    private static final Pose hit3_01 = new Pose(1.109715f, -2.076162f, -0.867097f, -0.041631f, -0.214145f, -0.477028f, -0.300703f, -0.153209f, -0.013322f, -0.299328f, -0.155854f, 0.093224f, -0.330753f, 0.238577f, 0.117548f, -0.328604f, -0.283587f, 0.075204f);
    private static final Pose hit3_02 = new Pose(0.731811f, -1.183762f, -0.094455f, -0.035767f, -0.035505f, -0.297012f, 0.352454f, -0.091194f, -0.060379f, 0.353276f, -0.114752f, 0.057768f, 0.228649f, 0.259704f, 0.143699f, 0.267403f, 0.028168f, 0.055997f);
    private static final Pose hit3_03 = new Pose(0.792954f, -1.470419f, 0.296637f, -0.069675f, 0.528643f, -0.221346f, 1.000709f, -0.083777f, -0.119866f, 1.021426f, -0.158466f, 0.035960f, 1.045748f, 0.109924f, 0.322506f, 0.806288f, 0.179682f, 0.044454f);
    private static final Pose hit3_04 = new Pose(0.787992f, -1.715854f, 0.512870f, -0.274284f, 0.560782f, -0.246123f, 0.991166f, -0.156952f, -0.170747f, 1.021426f, -0.158466f, 0.035960f, 1.041525f, 0.087540f, 0.296649f, 0.808461f, 0.218171f, 0.030270f);
    private static final Pose hit3_05 = new Pose(0.784448f, -1.891165f, 0.667322f, -0.420433f, 0.583739f, -0.263821f, 0.984350f, -0.209220f, -0.207091f, 1.021426f, -0.158466f, 0.035960f, 1.038508f, 0.071552f, 0.278179f, 0.810013f, 0.245664f, 0.020137f);
    private static final Pose hit3_06 = new Pose(0.782322f, -1.996351f, 0.759993f, -0.508122f, 0.597513f, -0.274440f, 0.980260f, -0.240581f, -0.228897f, 1.021426f, -0.158466f, 0.035960f, 1.036697f, 0.061959f, 0.267097f, 0.810945f, 0.262159f, 0.014058f);
    private static final Pose hit3_07 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.811255f, 0.267658f, 0.012032f);
    private static final Pose hit3_08 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.811214f, 0.266125f, 0.012553f);
    private static final Pose hit3_09 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.811091f, 0.261526f, 0.014115f);
    private static final Pose hit3_10 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.810905f, 0.254627f, 0.016459f);
    private static final Pose hit3_11 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.810782f, 0.250028f, 0.018022f);
    private static final Pose hit3_12 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.810740f, 0.248495f, 0.018543f);
    // Удар 4: hit4_stab.json
    private static final Pose hit4_00 = new Pose(0.781613f, -2.031413f, 0.790884f, -0.537352f, 0.602105f, -0.277980f, 0.978897f, -0.251035f, -0.236166f, 1.021426f, -0.158466f, 0.035960f, 1.036094f, 0.058761f, 0.263403f, 0.810740f, 0.248495f, 0.018543f);
    private static final Pose hit4_01 = new Pose(0.451547f, -1.881181f, 0.599069f, -0.346692f, 0.374020f, -0.492060f, 0.601611f, -0.199231f, -0.186993f, 0.631552f, -0.087343f, 0.002199f, 0.652161f, 0.086127f, 0.202829f, 0.467960f, 0.178521f, 0.027994f);
    private static final Pose hit4_02 = new Pose(0.535027f, -1.716560f, 0.300536f, -0.040405f, 0.247144f, -0.836841f, 0.362887f, -0.226189f, -0.232541f, 0.610809f, -0.071601f, 0.082353f, 0.699170f, -0.040810f, 0.160907f, 0.568017f, 0.242831f, 0.122320f);
    private static final Pose hit4_03 = new Pose(0.849424f, -1.684059f, 0.182472f, 0.085227f, 0.304447f, -0.978659f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021463f, -0.170891f, 0.168995f, 0.906105f, 0.349882f, 0.197139f);
    private static final Pose hit4_04 = new Pose(0.848657f, -1.685198f, 0.182158f, 0.079800f, 0.299135f, -0.965396f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021468f, -0.170488f, 0.169070f, 0.906114f, 0.344992f, 0.197140f);
    private static final Pose hit4_05 = new Pose(0.846356f, -1.688616f, 0.181217f, 0.063519f, 0.283199f, -0.925610f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021483f, -0.169280f, 0.169295f, 0.906142f, 0.330322f, 0.197145f);
    private static final Pose hit4_06 = new Pose(0.842520f, -1.694312f, 0.179648f, 0.036385f, 0.256639f, -0.859299f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021508f, -0.167266f, 0.169669f, 0.906189f, 0.305873f, 0.197153f);
    private static final Pose hit4_07 = new Pose(0.837150f, -1.702287f, 0.177452f, -0.001604f, 0.219455f, -0.766465f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021542f, -0.164448f, 0.170194f, 0.906255f, 0.271645f, 0.197163f);
    private static final Pose hit4_08 = new Pose(0.830629f, -1.711971f, 0.174785f, -0.047733f, 0.174303f, -0.653736f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021584f, -0.161025f, 0.170831f, 0.906334f, 0.230082f, 0.197177f);
    private static final Pose hit4_09 = new Pose(0.825259f, -1.719946f, 0.172588f, -0.085721f, 0.137118f, -0.560902f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021619f, -0.158206f, 0.171355f, 0.906400f, 0.195853f, 0.197188f);
    private static final Pose hit4_10 = new Pose(0.821423f, -1.725642f, 0.171019f, -0.112856f, 0.110558f, -0.494591f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021644f, -0.156192f, 0.171730f, 0.906446f, 0.171404f, 0.197195f);
    private static final Pose hit4_11 = new Pose(0.819122f, -1.729060f, 0.170078f, -0.129137f, 0.094622f, -0.454805f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021659f, -0.154984f, 0.171954f, 0.906475f, 0.156735f, 0.197200f);
    private static final Pose hit4_12 = new Pose(0.818354f, -1.730199f, 0.169764f, -0.134563f, 0.089310f, -0.441542f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021664f, -0.154582f, 0.172029f, 0.906484f, 0.151845f, 0.197202f);
    // Удар 5: hit5_spin+slam
    private static final Pose hit5_00 = new Pose(0.818354f, -1.730199f, 0.169764f, -0.134563f, 0.089310f, -0.441542f, 0.845348f, -0.391996f, -0.423193f, 0.878740f, -0.110114f, 0.176577f, 1.021664f, -0.154582f, 0.172029f, 0.906484f, 0.151845f, 0.197202f);
    private static final Pose hit5_01 = new Pose(0.583310f, -1.856877f, 0.611221f, -0.059643f, -0.320998f, 0.073205f, 0.878090f, -0.277638f, -0.252079f, 0.890253f, -0.160121f, 0.047125f, 0.942984f, -0.190435f, 0.097076f, 0.544743f, 0.094728f, 0.131262f);
    private static final Pose hit5_02 = new Pose(0.143255f, -2.085634f, 1.416598f, 0.079465f, -1.066888f, 0.999105f, 0.530400f, -0.100765f, -0.002834f, 0.526303f, -0.106404f, -0.076616f, 0.249146f, -0.346334f, 0.018966f, -0.080789f, 0.204806f, 0.125944f);
    private static final Pose hit5_03 = new Pose(-0.014172f, -2.154434f, 1.671975f, 0.127432f, -1.299167f, 1.271725f, 0.345057f, -0.045461f, 0.076441f, 0.411452f, -0.089259f, -0.114165f, 0.036629f, -0.414410f, -0.000983f, -0.267886f, 0.216724f, 0.107367f);
    private static final Pose hit5_04 = new Pose(-0.028510f, -2.153427f, 1.676968f, 0.130798f, -1.301043f, 1.263853f, -0.128794f, -0.039506f, 0.093275f, 0.398662f, -0.087342f, -0.112535f, 0.047341f, -0.518519f, 0.019638f, -0.240668f, 0.099769f, 0.017609f);
    private static final Pose hit5_05 = new Pose(-0.032851f, -2.148921f, 1.678175f, 0.132212f, -1.298698f, 1.262501f, -0.839442f, -0.030416f, 0.116124f, 0.374244f, -0.083683f, -0.109425f, 0.051754f, -0.558110f, 0.027127f, -0.231463f, 0.060125f, -0.013108f);
    private static final Pose hit5_06 = new Pose(-0.061240f, -1.999905f, 1.677398f, 0.152708f, -1.200430f, 1.282996f, -1.588858f, -0.015422f, 0.058880f, 0.338199f, -0.078282f, -0.104833f, 0.078572f, -0.703328f, 0.043577f, -0.233582f, 0.066078f, -0.018765f);
    private static final Pose hit5_07 = new Pose(-0.061240f, -1.999905f, 1.677398f, 0.152708f, -1.200430f, 1.282996f, -1.588858f, -0.015422f, 0.058880f, Float.NaN, Float.NaN, Float.NaN, 0.078572f, -0.703328f, 0.043577f, -0.233582f, 0.066078f, -0.018765f);
    private static final Pose hit5_08 = new Pose(-0.249505f, -1.784714f, 0.997633f, -0.403340f, -0.525802f, 0.086286f, -0.872056f, -0.446445f, 0.030645f, Float.NaN, Float.NaN, Float.NaN, 0.035159f, -0.429921f, 0.009601f, -0.133995f, -0.424075f, -0.003212f);
    private static final Pose hit5_09 = new Pose(-0.466420f, -1.536031f, 0.216246f, -1.043075f, 0.250574f, -1.289921f, -0.048143f, -0.945800f, -0.001689f, Float.NaN, Float.NaN, Float.NaN, -0.015099f, -0.121428f, -0.029468f, -0.019583f, -0.990605f, 0.014668f);
    private static final Pose hit5_10 = new Pose(-0.461032f, -1.508351f, 0.204639f, -1.032624f, 0.254492f, -1.283040f, -0.038843f, -0.944977f, -0.001621f, Float.NaN, Float.NaN, Float.NaN, -0.016448f, -0.133220f, -0.029379f, -0.018233f, -0.988447f, 0.014597f);
    private static final Pose hit5_11 = new Pose(-0.365325f, -1.195227f, 0.162157f, -0.818258f, 0.201661f, -1.016690f, -0.030778f, -0.761474f, -0.000784f, Float.NaN, Float.NaN, Float.NaN, -0.014471f, -0.128215f, -0.023267f, -0.014651f, -0.794187f, 0.011544f);
    private static final Pose hit5_12 = new Pose(-0.204380f, -0.668668f, 0.090718f, -0.457773f, 0.112819f, -0.568786f, -0.017217f, -0.435966f, -0.000045f, Float.NaN, Float.NaN, Float.NaN, -0.009226f, -0.089540f, -0.013006f, -0.008357f, -0.452905f, 0.006441f);
    private static final Pose hit5_13 = new Pose(-0.058362f, -0.190943f, 0.025905f, -0.130721f, 0.032216f, -0.162421f, -0.004916f, -0.128159f, 0.000132f, Float.NaN, Float.NaN, Float.NaN, -0.003050f, -0.032122f, -0.003710f, -0.002445f, -0.132494f, 0.001833f);
    private static final Pose hit5_14 = new Pose(0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    /** Клипы пяти ударов: начало N = конец N-1 (склейка в конвертере). */
    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // удар 1: hit1_slash_r2l.json
                new Keyframe(0.000f, E_LINEAR, hit1_00),
                new Keyframe(0.150f, E_LINEAR, hit1_01),
                new Keyframe(0.221f, E_LINEAR, hit1_02),
                new Keyframe(0.292f, E_LINEAR, hit1_03),
                new Keyframe(0.362f, E_LINEAR, hit1_04),
                new Keyframe(0.433f, E_LINEAR, hit1_05),
                new Keyframe(0.504f, E_LINEAR, hit1_06),
                new Keyframe(0.575f, E_LINEAR, hit1_07),
                new Keyframe(0.646f, E_LINEAR, hit1_08),
                new Keyframe(0.717f, E_LINEAR, hit1_09),
                new Keyframe(0.787f, E_LINEAR, hit1_10),
                new Keyframe(0.858f, E_LINEAR, hit1_11),
                new Keyframe(0.929f, E_LINEAR, hit1_12),
                new Keyframe(1.000f, E_LINEAR, hit1_13),
        }),
        new Clip(new Keyframe[] { // удар 2: hit2_slash_l2r.json
                new Keyframe(0.000f, E_LINEAR, hit2_00),
                new Keyframe(0.083f, E_LINEAR, hit2_01),
                new Keyframe(0.167f, E_LINEAR, hit2_02),
                new Keyframe(0.250f, E_LINEAR, hit2_03),
                new Keyframe(0.333f, E_LINEAR, hit2_04),
                new Keyframe(0.417f, E_LINEAR, hit2_05),
                new Keyframe(0.500f, E_LINEAR, hit2_06),
                new Keyframe(0.583f, E_LINEAR, hit2_07),
                new Keyframe(0.667f, E_LINEAR, hit2_08),
                new Keyframe(0.750f, E_LINEAR, hit2_09),
                new Keyframe(0.833f, E_LINEAR, hit2_10),
                new Keyframe(0.917f, E_LINEAR, hit2_11),
                new Keyframe(1.000f, E_LINEAR, hit2_12),
        }),
        new Clip(new Keyframe[] { // удар 3: hit3_uppercut.json
                new Keyframe(0.000f, E_LINEAR, hit3_00),
                new Keyframe(0.083f, E_LINEAR, hit3_01),
                new Keyframe(0.167f, E_LINEAR, hit3_02),
                new Keyframe(0.250f, E_LINEAR, hit3_03),
                new Keyframe(0.333f, E_LINEAR, hit3_04),
                new Keyframe(0.417f, E_LINEAR, hit3_05),
                new Keyframe(0.500f, E_LINEAR, hit3_06),
                new Keyframe(0.583f, E_LINEAR, hit3_07),
                new Keyframe(0.667f, E_LINEAR, hit3_08),
                new Keyframe(0.750f, E_LINEAR, hit3_09),
                new Keyframe(0.833f, E_LINEAR, hit3_10),
                new Keyframe(0.917f, E_LINEAR, hit3_11),
                new Keyframe(1.000f, E_LINEAR, hit3_12),
        }),
        new Clip(new Keyframe[] { // удар 4: hit4_stab.json
                new Keyframe(0.000f, E_LINEAR, hit4_00),
                new Keyframe(0.083f, E_LINEAR, hit4_01),
                new Keyframe(0.167f, E_LINEAR, hit4_02),
                new Keyframe(0.250f, E_LINEAR, hit4_03),
                new Keyframe(0.333f, E_LINEAR, hit4_04),
                new Keyframe(0.417f, E_LINEAR, hit4_05),
                new Keyframe(0.500f, E_LINEAR, hit4_06),
                new Keyframe(0.583f, E_LINEAR, hit4_07),
                new Keyframe(0.667f, E_LINEAR, hit4_08),
                new Keyframe(0.750f, E_LINEAR, hit4_09),
                new Keyframe(0.833f, E_LINEAR, hit4_10),
                new Keyframe(0.917f, E_LINEAR, hit4_11),
                new Keyframe(1.000f, E_LINEAR, hit4_12),
        }),
        new Clip(new Keyframe[] { // удар 5: hit5_spin+slam
                new Keyframe(0.000f, E_LINEAR, hit5_00),
                new Keyframe(0.092f, E_LINEAR, hit5_01),
                new Keyframe(0.183f, E_LINEAR, hit5_02),
                new Keyframe(0.275f, E_LINEAR, hit5_03),
                new Keyframe(0.367f, E_LINEAR, hit5_04),
                new Keyframe(0.458f, E_LINEAR, hit5_05),
                new Keyframe(0.550f, E_LINEAR, hit5_06),
                new Keyframe(0.550f, E_LINEAR, hit5_07),
                new Keyframe(0.614f, E_LINEAR, hit5_08),
                new Keyframe(0.679f, E_LINEAR, hit5_09),
                new Keyframe(0.743f, E_LINEAR, hit5_10),
                new Keyframe(0.807f, E_LINEAR, hit5_11),
                new Keyframe(0.871f, E_LINEAR, hit5_12),
                new Keyframe(0.936f, E_LINEAR, hit5_13),
                new Keyframe(1.000f, E_LINEAR, hit5_14),
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
