package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Боевка путешественника: комбо из 5 ударов мечом по ЛКМ, как у Итэра/Люмин
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack.
 *
 * Анимации — клипы, вручную сгенерированные по описанию обычных атак
 * путешественника из Genshin (scripts/gen_combo.py): удар 1 — широкий
 * горизонтальный слева направо, удар 2 — длинный апперкот справа снизу
 * вверх влево, удар 3 — разворот через левое плечо на 360° с рубящим ударом
 * по диагонали, удар 4 — горизонтальный справа налево, удар 5 — очень
 * широкий замах, удар справа налево и увод клинка за спину (с прокатом).
 * Принципы качества: голова ВСЕГДА смотрит строго вперёд (принудительно
 * после любой позы — не следит за камерой); удары МГНОВЕННЫЕ — t=0 клипа
 * это уже замах/нейтраль, свинг стартует с первого кадра, пик скорости
 * (момент урона) ~0.40-0.48 клипа; удары МАКСИМАЛЬНО ШИРОКИЕ — клинок
 * выписывает широкие дуги (клинок уводится далеко в замах и далеко в
 * сопровождение); КОРПУС НЕ ОТРЫВАЕТСЯ ОТ НОГ — углы корпуса малые
 * (bYaw ±12°, bPitch ≤8°, у тела ванильный пивот на уровне шеи), наклон
 * в удар делает root целиком (root.pitch до 10°) с выпадом ног; левая
 * рука — естественный противовес без резких смен направления; ноги
 * переступают с выпадом в момент урона. Каждый клип непрерывен
 * (t=0 = финал предыдущего удара); разворот на 360° делает root ванильной
 * модели (getRootPart().yaw): торс, голова, руки и клинок поворачиваются
 * как единое целое. Полный круг ≈ 3.5 с, серия идёт по КЛИКАМ (удержание
 * ЛКМ НЕ цепляет следующий удар): пока с последнего клика прошло ≤ ~1 с,
 * комбо продолжается со следующего удара (после пятого — снова первый);
 * пауза дольше секунды сбрасывает серию (следующий клик — первый удар).
 * После пятого удара — обязательная пауза ~1 сек (клики глотаются).
 * Клинок вырисовывает разрез: во время свинга
 * рисуется светящаяся дуга-полумесяц (трейл по траектории клинка +
 * усиленные частицы). Между анимациями — плавные переходы (первые 12%
 * каждого удара смешиваются с предыдущей позой через prevAppliedPose).
 * Во время ударов движение клавишами блокируется, герой двигается только
 * микро-рывком по направлению атаки. В первом лице отрисовывается
 * собственное тело (FirstPersonBody): видны все анимации персонажа,
 * «глазами модельки», голова скрыта (см. PlayerEntityModelMixin). После
 * тапа — короткая фаза восстановления в нейтраль. Вне комбо — чистый
 * цикл бега: руки машут противофазно ногам, корпус наклонён и покачивается,
 * лёгкий подскок; в покое — АФК-покачивание (перекат с ноги на ногу,
 * мягкие волны корпуса и рук). После пятого удара — пауза ~1 секунда:
 * клики ЛКМ в неё глотаются, новое комбо начинается только с первого удара.
 */
public final class CombatController {
    /** Текущий удар комбо: 0..HIT_COUNT-1, -1 = атака не идёт. */
    private static int comboStep = -1;
    /** Тики с начала текущего удара. */
    private static int hitTicks;
    /** Счётчик клиентских тиков (для окна комбо от последнего клика). */
    private static int tickCount;
    /** Тик последнего обработанного клика ЛКМ (-1 = кликов ещё не было). */
    private static int lastClickTick = -1;
    /** Последний сыгранный удар (для продолжения серии в окне от последнего клика). */
    private static int lastStep = -1;
    /** Клик уже зацепил следующий удар: серия не обрывается. */
    private static boolean bufferedNext;
    /** Урон по текущему удару отправлен серверу (один раз за удар). */
    private static boolean sentHit;
    /** Кик от удара 0..1: пульс на момент разреза, затухает за ~0.5 сек.
     *  Двигает FOV и наклон камеры (как отдача в Genshin). */
    private static float impactKick;
    /** Тики фазы восстановления после одиночного удара (поза тает в боевую стойку). */
    private static int recoveryTicks;
    /** Тики плавного выхода из комбо в стойку бега/покоя (после recovery). */
    private static int exitBlendTicks;
    /** Тики паузы после 5-го удара: пока > 0, клики ЛКМ глотаются. */
    private static int finalCooldownTicks;

    /** Тиков восстановления после одиночного удара: поза плавно уходит в
     *  боевую стойку READY_POSE (клинок остаётся горизонтальным — никакого
     *  «дефолтного майнкрафт» с вертикальным мечом). */
    private static final int RECOVERY_TICKS = 10;
    /** Тиков смешивания последней позы удара с локомоцией (бег/шаг/АФК). */
    private static final int EXIT_BLEND_TICKS = 6;

    /** Последняя наложенная поза: из неё плавно «въезжаем» в следующий удар
     *  (первые ~12% клипа смешиваются с предыдущей позой — без рывков между
     *  анимациями). */
    private static Pose prevAppliedPose;
    private static boolean hasPrevAppliedPose;
    /** Последнее состояние root от локомоции (бег/шаг/АФК): подскок
     *  originY, наклон и покачивание yaw. На старте НОВОГО комбо плавно
     *  гасятся до нуля, чтобы «въезд» из бега/шага/покоя в первый удар
     *  был непрерывным (без щелчка по вертикали и поворота корпуса). */
    private static float lastLocoRootY;
    private static float lastLocoRootPitch;
    private static float lastLocoRootYaw;
    /** Последнее состояние root во время удара (для плавного выхода в локомоцию). */
    private static float lastCombatRootY;
    private static float lastCombatRootPitch;
    private static float lastCombatRootYaw;

    /** Доля клипа, на которой работает смешивание перехода между ударами. */
    private static final float TRANSITION_BLEND = 0.12f;
    /** Старт НОВОГО комбо после паузы: более широкое окно смешивания с
     *  нейтралью, чтобы первый удар плавно «выезжал» из покоя, без рывка
     *  из стойки (используется, пока идёт удар 1 свежего комбо). */
    private static final float COLD_START_BLEND = 0.30f;
    /** Хват меча в беге: рука вытянута вперёд, клинок горизонтально. */
    private static final float RUN_ARM_PITCH = (float) Math.toRadians(-60f);
    private static final float RUN_ARM_YAW = (float) Math.toRadians(6f);
    private static final float RUN_ARM_ROLL = (float) Math.toRadians(8f);
    /** Боевая стойка в покое: клинок горизонтально слева (ready pose). */
    private static final float READY_RYAW = (float) Math.toRadians(22f);
    private static final float READY_RPITCH = (float) Math.toRadians(-46f);
    private static final float READY_RROLL = (float) Math.toRadians(22f);
    private static final float READY_LPITCH = (float) Math.toRadians(-15f);
    private static final float READY_BYAW = (float) Math.toRadians(8f);
    private static final float READY_BPITCH = (float) Math.toRadians(3f);

    /** Светящийся разрез-полумесяц: мировые точки траектории клинка за
     *  последние кадры свинга. Точки стареют и тают (SLASH_TRAIL_AGE),
     *  лента рисуется в FirstPersonBody. */
    record SlashPoint(Vec3d pos, int age) {}
    private static final Deque<SlashPoint> slashTrail = new ArrayDeque<>();
    private static final int SLASH_TRAIL_AGE = 7;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        tickCount++;
        if (finalCooldownTicks > 0) {
            finalCooldownTicks--;
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
            lastClickTick = -1;
            finalCooldownTicks = 0;
            exitBlendTicks = 0;
        }
        if (comboStep >= 0) {
            if (recoveryTicks > 0) {
                // Восстановление после одиночного удара: поза тает в нейтраль,
                // чтобы между тапами не было отскока в ванильную стойку.
                recoveryTicks--;
                if (recoveryTicks == 0) {
                    comboStep = -1;
                    exitBlendTicks = EXIT_BLEND_TICKS;
                }
            } else {
                hitTicks++;
                if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                    sentHit = true;
                    sendHit(comboStep);
                    spawnSlashEffects(client, client.player);
                    applyLunge(client.player);
                    impactKick = 1.6f;
                }
                if (hitTicks >= SwordCombo.DURATION_TICKS[comboStep]) {
                    if (comboStep == SwordCombo.HIT_COUNT - 1) {
                        // После пятого удара — обязательная пауза ~1 сек: клики
                        // в неё глотаются (onAttackClick), серия сбрасывается —
                        // новое комбо начнётся только с первого удара. Поза
                        // тает в нейтраль через recoveryTicks (comboStep ещё
                        // указывает на 5-й удар), затем уходит в -1, а
                        // finalCooldownTicks докручивает оставшуюся паузу.
                        lastStep = -1;
                        lastClickTick = -1;
                        recoveryTicks = RECOVERY_TICKS;
                        finalCooldownTicks = SwordCombo.FINAL_COOLDOWN_TICKS;
                        bufferedNext = false;
                        sentHit = false;
                    } else if (bufferedNext) {
                        // Серия продолжается по КЛИКУ: следующий удар.
                        // lastStep запоминаем и здесь: после цепочки окно продолжения
                        // по паузе тоже должно знать последний сыгранный удар.
                        lastStep = comboStep;
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
                }
            }
        } else if (lastStep >= 0 && tickCount - lastClickTick > SwordCombo.RESET_TICKS) {
            // С последнего клика прошло больше ~1 с: серия сброшена,
            // следующий клик начнёт комбо с первого удара.
            lastStep = -1;
            lastClickTick = -1;
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
        if (finalCooldownTicks > 0) {
            // Пауза после пятого удара: клик съедается, комбо не начинается.
            return true;
        }
        // Окно комбо — от последнего клика: пока прошло ≤ ~1 с, серия
        // продолжается со следующего удара (после пятого — снова первый);
        // пауза дольше секунды сбрасывает комбо (клик начнёт с первого удара).
        boolean windowOpen = lastClickTick < 0
                || tickCount - lastClickTick <= SwordCombo.RESET_TICKS;
        lastClickTick = tickCount;
        if (comboStep < 0) {
            if (windowOpen && lastStep >= 0) {
                comboStep = (lastStep + 1) % SwordCombo.HIT_COUNT;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
            exitBlendTicks = 0;
        } else if (recoveryTicks > 0) {
            // Клик в фазе восстановления: продолжаем серию, если окно открыто.
            recoveryTicks = 0;
            if (windowOpen) {
                comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
            exitBlendTicks = 0;
        } else {
            // Клик во время удара: следующий удар цепляется сразу после его конца.
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
        // Полумесяцы-разрезы: серия ярких серпов вдоль дуги удара (шире к пятому).
        int crescents = 3;
        float arcSpan = 1.45f + comboStep * 0.12f;
        for (int i = 0; i < crescents; i++) {
            float t = (float) i / (crescents - 1);
            double ang = yaw + (t - 0.5f) * arcSpan;
            world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                    player.getX() - Math.sin(ang) * 0.9,
                    player.getY() + 1.05 + comboStep * 0.07,
                    player.getZ() + Math.cos(ang) * 0.9,
                    -Math.cos(ang), 0.0, -Math.sin(ang));
        }
        // Дуга размаха: плотные штрихи-искры вдоль траектории меча.
        int count = 16;
        double radius = 2.4;
        double height = 1.0 + comboStep * 0.09;
        float span = 1.55f + comboStep * 0.12f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.6, 0.0, -Math.sin(ang) * 1.6);
        }
        // Вспышка в точке контакта: белый крест искр прямо перед героем.
        world.addParticleClient(ParticleTypes.END_ROD,
                player.getX() + d * 1.1,
                player.getY() + 1.15 + comboStep * 0.06,
                player.getZ() + e * 1.1,
                d * 0.8, 0.12, e * 0.8);
        world.addParticleClient(ParticleTypes.END_ROD,
                player.getX() + d * 1.1,
                player.getY() + 1.35 + comboStep * 0.06,
                player.getZ() + e * 1.1,
                -d * 0.5, 0.05, -e * 0.5);
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

    /** Единая точка входа из миксина модели: во время комбо — поза удара,
     *  вне комбо — эпичная ходьба/бег в стиле Origin Animation (широкие махи
     *  рук и ног, наклон и поворот корпуса, лёгкий подскок; в покое —
     *  дыхание). root.yaw сбрасывается вне разворота. */
    public static void applyPlayerPose(PlayerEntityModel model, PlayerEntityRenderState state) {
        if (!isLocalPlayer(state.id)) {
            return;
        }
        if (comboStep >= 0) {
            applyPose(model, state.id);
        } else {
            model.getRootPart().yaw = 0f;
            applyLocomotion(model, state);
            if (exitBlendTicks > 0) {
                // Плавный выход из комбо: первые кадры после удара смешиваются
                // с последней позой удара (и root), без щелчка в стойку бега/покоя.
                Pose loco = poseFromModel(model);
                float w = 1f - exitBlendTicks / (float) EXIT_BLEND_TICKS;
                applyPoseToModel(model, mix(prevAppliedPose, loco, w));
                model.getRootPart().originY = MathHelper.lerp(w, lastCombatRootY, model.getRootPart().originY);
                model.getRootPart().pitch = MathHelper.lerp(w, lastCombatRootPitch, model.getRootPart().pitch);
                model.getRootPart().yaw = MathHelper.lerp(w, lastCombatRootYaw, model.getRootPart().yaw);
                exitBlendTicks--;
            }
            // Запоминаем позу бега и состояние root: из них первый удар
            // «въедет» без рывка (и части, и подскок/наклон корпуса).
            prevAppliedPose = poseFromModel(model);
            hasPrevAppliedPose = true;
            lastLocoRootY = model.getRootPart().originY;
            lastLocoRootPitch = model.getRootPart().pitch;
            lastLocoRootYaw = model.getRootPart().yaw;
        }
        // Голова ВСЕГДА смотрит строго вперёд (относительно корпуса): не
        // следит за камерой и не вертится во время ударов и бега.
        model.head.yaw = 0f;
        model.head.pitch = 0f;
        model.head.roll = 0f;
    }

    /** Прочитать текущие углы модели как позу (для плавных переходов). */
    private static Pose poseFromModel(PlayerEntityModel m) {
        return new Pose(
                m.rightArm.yaw, m.rightArm.pitch, m.rightArm.roll,
                m.leftArm.yaw, m.leftArm.pitch, m.leftArm.roll,
                m.body.yaw, m.body.pitch, m.body.roll,
                Float.NaN, Float.NaN, Float.NaN,
                m.rightLeg.yaw, m.rightLeg.pitch, m.rightLeg.roll,
                m.leftLeg.yaw, m.leftLeg.pitch, m.leftLeg.roll);
    }

    /** Эпичная ходьба/бег (стиль Origin Animation): руки машут широко
     *  (до ±63°), ноги поднимаются высоко (до ±52°), корпус наклонён вперёд
     *  и слегка поворачивается на каждый шаг, модель чуть подпрыгивает.
     *  Значения ванильной позы (постановка руки с предметом) смешиваются
     *  с эпичной по мере набора скорости — в покое руки держат меч как в
     *  ванилле, добавляется только лёгкое дыхание. Голова не трогается:
     *  она всегда смотрит туда, куда игрок. */
    private static void applyLocomotion(PlayerEntityModel model, PlayerEntityRenderState state) {
        if (state.isSwimming || state.hasVehicle || state.isGliding || state.isInSneakingPose) {
            return;
        }
        float amt = MathHelper.clamp(state.limbSwingAmplitude, 0f, 1f);
        float move = MathHelper.clamp(amt * 1.3f, 0f, 1f);
        if (move < 0.01f) {
            // АФК-покачивание вокруг БОЕВОЙ СТОЙКИ: клинок держим
            // горизонтально слева (не «дефолтный майнкрафт» с вертикальным
            // мечом), сверху — дыхание, лёгкий перекат с ноги на ногу и
            // покачивание корпуса. Голова не трогается (всегда смотрит вперёд).
            float t = state.age * 0.05f;
            float sway = MathHelper.sin(t);
            float sway2 = MathHelper.sin(t * 0.77f + 1.7f);
            float breath = MathHelper.sin(state.age * 0.09f);
            model.getRootPart().yaw = sway * 0.045f;
            model.getRootPart().pitch = 0.02f + sway2 * 0.02f;
            model.rightArm.yaw = READY_RYAW + sway * 0.01f;
            model.rightArm.pitch = READY_RPITCH + breath * 0.015f + sway2 * 0.012f;
            model.rightArm.roll = READY_RROLL + breath * 0.02f + sway2 * 0.015f;
            model.leftArm.yaw = 0f;
            model.leftArm.pitch = READY_LPITCH + sway2 * 0.02f;
            model.leftArm.roll = breath * 0.04f - sway2 * 0.03f;
            model.body.yaw = READY_BYAW - sway * 0.025f;
            model.body.roll = sway2 * 0.03f;
            model.body.pitch = READY_BPITCH + breath * 0.035f + MathHelper.cos(t) * 0.012f;
            model.rightLeg.roll = sway * 0.012f;
            model.leftLeg.roll = -sway * 0.012f;
            model.rightLeg.yaw = -sway2 * 0.01f;
            model.leftLeg.yaw = sway2 * 0.01f;
            model.getRootPart().originY = 0f;
            return;
        }
        float ph = state.limbSwingAnimationProgress * 0.6662f;
        // Единый чистый цикл бега: правая рука держит меч впереди (стойка с
        // клинком — почти фиксированный хват с лёгким покачиванием в такт
        // шагам), левая рука машет для баланса, корпус слегка наклонён и
        // покачивается, модель чуть подпрыгивает.
        float swing = 0.85f * move;
        model.rightArm.pitch = MathHelper.lerp(move, model.rightArm.pitch, RUN_ARM_PITCH + MathHelper.cos(ph) * 0.06f * move);
        model.leftArm.pitch = MathHelper.lerp(move, model.leftArm.pitch, -MathHelper.cos(ph + (float) Math.PI) * swing);
        model.rightArm.yaw = MathHelper.lerp(move, model.rightArm.yaw, RUN_ARM_YAW);
        model.leftArm.yaw = MathHelper.lerp(move, model.leftArm.yaw, 0f);
        model.rightArm.roll = MathHelper.lerp(move, model.rightArm.roll, RUN_ARM_ROLL + MathHelper.cos(ph + (float) Math.PI) * 0.06f * move);
        model.leftArm.roll = MathHelper.lerp(move, model.leftArm.roll, MathHelper.cos(ph) * 0.12f * move);
        float legSwing = 0.7f * move;
        model.rightLeg.pitch = MathHelper.lerp(move, model.rightLeg.pitch, MathHelper.cos(ph) * legSwing);
        model.leftLeg.pitch = MathHelper.lerp(move, model.leftLeg.pitch, MathHelper.cos(ph + (float) Math.PI) * legSwing);
        model.rightLeg.yaw = MathHelper.lerp(move, model.rightLeg.yaw, 0f);
        model.leftLeg.yaw = MathHelper.lerp(move, model.leftLeg.yaw, 0f);
        model.rightLeg.roll = MathHelper.lerp(move, model.rightLeg.roll, 0f);
        model.leftLeg.roll = MathHelper.lerp(move, model.leftLeg.roll, 0f);
        model.body.pitch = MathHelper.lerp(move, model.body.pitch, 0.3f * move);
        model.body.yaw = MathHelper.lerp(move, model.body.yaw, MathHelper.sin(ph * 2f) * 0.16f * move);
        model.body.roll = MathHelper.lerp(move, model.body.roll, 0f);
        model.getRootPart().pitch = 0.05f * move;
        model.getRootPart().originY = MathHelper.sin(ph * 2f) * 0.8f * move;
    }

    /** Наложить позу удара на модель игрока (вызывается из applyPlayerPose).
     *  Прогресс считается по кадрам (тик + tickDelta), поэтому на высоком FPS
     *  движение остаётся плавным, а не шагает по 20 тикам в секунду. */
    public static void applyPose(PlayerEntityModel model, int entityId) {
        if (!isLocalPlayer(entityId)) {
            return;
        }
        if (comboStep < 0) {
            // Сброс: прерванный в воздухе разворот не оставляет модель
            // повёрнутой (vanilla root.yaw в setAngles не трогает).
            model.getRootPart().yaw = 0f;
            return;
        }
        // Сброс следов бега (наклон/подскок root от applyLocomotion).
        // Наклон root: всё тело (и клинок) ложится в удар — таз остаётся у бёдер,
        // ноги не отрываются (пивот root на уровне шеи, углы малые).
        // На старте НОВОГО комбо подскок/наклон/покачивание root от бега
        // гасятся плавно (то же окно, что и у смешивания поз) — из бега,
        // шага и АФК-покачивания удар «въезжает» без щелчка.
        float p = progress();
        float rootBlend = 1f;
        if (comboStep == 0) {
            float w = MathHelper.clamp(p / COLD_START_BLEND, 0f, 1f);
            rootBlend = ease(E_IN_OUT_SINE, w);
        }
        model.getRootPart().originY = MathHelper.lerp(rootBlend, lastLocoRootY, 0f);
        model.getRootPart().pitch = MathHelper.lerp(rootBlend, lastLocoRootPitch, rootLean(p));
        Pose pose = computePose(p);
        // Плавный переход: первые 12% каждого удара смешиваются с предыдущей
        // позой (бегом или финалом прошлого удара) — никаких рывков на стыках.
        pose = blendTransition(pose, p);
        prevAppliedPose = pose;
        hasPrevAppliedPose = true;
        applyPoseToModel(model, pose);
        // Разворот: полный оборот делает root модели — торс, голова, руки,
        // ноги и клинок в руке поворачиваются как единое целое (никакого
        // «отдельного вращения тела»). 2π ≡ 0, поэтому на стыке с ударами
        // 2 и 4 рывка не видно. Вне разворота root = 0 (сброс stale-угла).
        model.getRootPart().yaw = MathHelper.lerp(rootBlend, lastLocoRootYaw,
                comboStep == 2 ? spinTurn(p) : 0f);
        lastCombatRootY = model.getRootPart().originY;
        lastCombatRootPitch = model.getRootPart().pitch;
        lastCombatRootYaw = model.getRootPart().yaw;
    }

    /** Текущий прогресс удара 0..1 с интерполяцией кадров (как в applyPose). */
    public static float progress() {
        if (comboStep < 0) {
            return 0f;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        return Math.min(1f, (hitTicks + tickDelta) / SwordCombo.DURATION_TICKS[comboStep]);
    }

    /** Чистая поза клипа (без перехода) на текущем прогрессe. */
    private static Pose computePose(float p) {
        Pose pose = CLIPS[comboStep].at(p);
        if (recoveryTicks > 0) {
            // Плавный уход в нейтраль после последнего удара.
            float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
            pose = relax(pose, easeOutCubic(r));
        }
        return pose;
    }

    /** Смешать с предыдущей позой на первых 12% клипа (плавные стыки). */
    private static Pose blendTransition(Pose pose, float p) {
        if (hasPrevAppliedPose) {
            float win = comboStep == 0 ? COLD_START_BLEND : TRANSITION_BLEND;
            if (p < win) {
                float w = MathHelper.clamp(p / win, 0f, 1f);
                return mix(prevAppliedPose, pose, ease(E_IN_OUT_SINE, w));
            }
        }
        return pose;
    }

    /** Текущая итоговая поза (для трейла разреза). */
    private static Pose currentPose() {
        if (comboStep < 0) {
            return null;
        }
        float p = progress();
        return blendTransition(computePose(p), p);
    }

    /** Семплировать траекторию клинка в трейл разреза во время свинга.
     *  Вызывается каждый кадр рендера из FirstPersonBody. Вне свинга точки
     *  не добавляются и трейл тает за SLASH_TRAIL_AGE кадров. */
    public static void sampleCurrentSlashTrail(float bodyYawDeg, Vec3d playerPos) {
        float p = progress();
        if (comboStep < 0 || p < 0.30f || p > 0.95f) {
            return;
        }
        Pose pose = currentPose();
        if (pose == null) {
            return;
        }
        slashTrail.addLast(new SlashPoint(
                bladeTipWorld(pose, currentRootYawRad(), bodyYawDeg, playerPos), 0));
    }

    /** Состарить точки трейла и выкинуть слишком старые (плавное таяние). */
    public static void ageSlashTrail() {
        int size = slashTrail.size();
        for (int i = 0; i < size; i++) {
            SlashPoint pt = slashTrail.removeFirst();
            int age = pt.age() + 1;
            if (age <= SLASH_TRAIL_AGE) {
                slashTrail.addLast(new SlashPoint(pt.pos(), age));
            }
        }
    }

    /** Поворот root модели на текущем прогрессe (удар 3 — полный оборот). */
    public static float currentRootYawRad() {
        if (comboStep != 2) {
            return 0f;
        }
        return spinTurn(progress());
    }

    /** Клинок блокируется на время удара: клавиши движения не работают,
     *  герой двигается только микро-рывком по направлению атаки. */
    public static boolean lockInputDuringAttack() {
        return comboStep >= 0;
    }

    /** Точки трейла разреза (мировые координаты + возраст, для рендера дуги). */
    public static Deque<CombatController.SlashPoint> slashTrail() {
        return slashTrail;
    }

    /** Максимальный возраст точки трейла (для альфы в рендере). */
    public static int slashTrailAge() {
        return SLASH_TRAIL_AGE;
    }

    /** Траектория клинка в мировых координатах: та же цепочка, что у рендера
     *  (LivingEntityRenderer.render -> ModelPart.applyTransform ->
     *  HeldItemFeatureRenderer -> display handheld). Математика проверена
     *  запуском JOML (GeoTest/SwordDir): рука — Quaternionf.rotationZYX,
     *  клинок в системе руки — фиксированные константы из цепочки предмета.
     *  Поэтому разрез совпадает с видимым мечом (scripts/blade_geo.py). */
    public static Vec3d bladeTipWorld(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos) {
        Matrix4f m = new Matrix4f();
        // LivingEntityRenderer.setupTransforms: поворот тела (180 - bodyYaw).
        m.rotate(new Quaternionf().rotationY((float) Math.toRadians(180f - bodyYawDeg)));
        // render: scale(-1,-1,1) и translate(0,-1.501,0) — root модели у ног игрока.
        m.scale(-1f, -1f, 1f);
        m.translate(0f, -1.501f, 0f);
        // Root модели: разворот удара 3 + наклон корпуса в удар.
        m.rotate(new Quaternionf().rotationY(rootYawRad));
        m.rotate(new Quaternionf().rotationX(currentRootPitchRad()));
        // Правое плечо (пивот (-5, 2, 0)) + углы руки (как ModelPart.applyTransform).
        m.translate(-5f / 16f, 2f / 16f, 0f);
        m.rotate(new Quaternionf().rotationZYX(pose.rRoll(), pose.rYaw(), pose.rPitch()));
        // HeldItemFeatureRenderer: rotX(-90), rotY(180), translate(±1/16, 0.125, -0.625).
        m.rotate(new Quaternionf().rotationX((float) Math.toRadians(-90f)));
        m.rotate(new Quaternionf().rotationY((float) Math.toRadians(180f)));
        m.translate(1f / 16f, 0.125f, -0.625f);
        // display handheld (thirdperson_righthand): translate(0, 4, 0.5) в 1/16 блока,
        // rotation (0, -90, 55), scale 0.85.
        m.translate(0f, 4f / 16f, 0.5f / 16f);
        m.rotate(new Quaternionf().rotationXYZ(0f, (float) Math.toRadians(-90f), (float) Math.toRadians(55f)));
        m.scale(0.85f, 0.85f, 0.85f);
        // Кончик клинка — верх модели меча (локальный +Y предмета).
        Vector3f tip = m.transformPosition(new Vector3f(0f, 1f, 0f), new Vector3f());
        return new Vec3d(playerPos.x + tip.x, playerPos.y + tip.y, playerPos.z + tip.z);
    }

    /** Очистить трейл разреза (когда self-body выключен или вышел из свинга). */
    public static void clearSlashTrail() {
        slashTrail.clear();
    }



    /** Наклон всего тела в удар (root.pitch): нарастает к пику скорости
     *  (~0.40 клипа) и спадает к концу. Удар 3 (разворот) — без наклона.
     *  Углы малые (6..10°) — пивот root на уровне шеи, ноги не отрываются. */
    private static final float[] ROOT_LEAN_DEG = {6f, 8f, 0f, 6f, 10f};

    private static float rootLean(float p) {
        if (comboStep < 0 || comboStep == 2) {
            return 0f;
        }
        float peak = 0.40f;
        float up = MathHelper.clamp(p / peak, 0f, 1f);
        float down = 1f - MathHelper.clamp((p - peak) / 0.38f, 0f, 1f);
        float u = Math.min(up, down);
        return (float) Math.toRadians(ROOT_LEAN_DEG[comboStep]) * u;
    }

    /** Наклон root на текущем прогрессе (для трейла разреза). */
    public static float currentRootPitchRad() {
        if (comboStep < 0) {
            return 0f;
        }
        return rootLean(progress());
    }

    /** Прогресс полного оборота разворота 0..1: короткая пауза-замах, затем
     *  плавный разгон -> оборот -> стабилизация (E_IN_OUT_CUBIC). */
    private static float spinTurn(float p) {
        float u = MathHelper.clamp((p - 0.06f) / 0.60f, 0f, 1f);
        return (float) (-Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
    }

    /** Боевая стойка (покой/после удара): клинок держим горизонтально слева —
     *  не «дефолтный майнкрафт» (меч вертикально). В неё уходит восстановление
     *  после удара, из неё же растёт АФК-покачивание и стартует бег. */
    private static final Pose READY_POSE = new Pose(
            READY_RYAW, READY_RPITCH, READY_RROLL,
            0f, READY_LPITCH, 0f,
            READY_BYAW, READY_BPITCH, 0f,
            Float.NaN, Float.NaN, Float.NaN,
            0f, 0f, 0f, 0f, 0f, 0f);

    /** Смешать позу с боевой стойкой (клинок остаётся горизонтальным). */
    private static Pose relax(Pose p, float w) {
        return mix(p, READY_POSE, w);
    }

    /** Ease-out cubic для восстановления: быстрое расслабление, мягкий выход. */
    private static float easeOutCubic(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    // ---------- Позы ----------
    // Анимации — клипы ключевых кадров (time + поза + кривая), сгенерированы
    // скриптом scripts/gen_combo.py (вручную по ударам путешественника):
    // стыки — EASE_IN_OUT_SINE в хвосте удара, голова всегда смотрит строго
    // вперёд, левая рука — живой противовес, ноги переступают с выпадом,
    // разворот крутит root модели (см. spinTurn), финал 5-го уводит
    // клинок за спину.

    /** Кривые интерполяции сегмента: замах — E_IN_OUT_CUBIC, свинг — E_LINEAR,
     *  сопровождение — E_OUT_CUBIC, переходы — E_IN_OUT_SINE. */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;
    private static final int E_IN_CUBIC = 5;

    /** NaN-канал не трогаем (в клипах головы нет: она всегда смотрит по
     *  направлению удара; NaN появляется только в фазе восстановления —
     *  голова следует за взглядом игрока). Порядок: правая рука y/p/r,
     *  левая рука y/p/r, корпус y/p/r, голова y/p/r, правая нога y/p/r,
     *  левая нога y/p/r (радианы). */
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

    /** Позы пяти ударов, запечены скриптом scripts/gen_combo.py (якоря ->
     *  плавные кривые -> 41 линейный кадр на удар):
     *  удар 1 — клинок горизонтально, свинг справа налево, задержка руки
     *  слева; удар 2 — апперкот: замах клинком к земле, финиш рукой вверх;
     *  удар 3 — разворот против часовой стрелки (root −2π, см. spinTurn)
     *  с рубящим ударом клинком сверху; удар 4 — замах слева-сверху,
     *  свинг слева направо, клинок горизонтально на ударе; удар 5 — очень
     *  широкий замах за голову, наклон корпуса и рывок вперёд, задержка
     *  руки слева.
     *  Голова всегда 0 (вперёд). Левая рука — живой противовес, ноги
     *  переступают с выпадом в момент урона, корпус доворачивается
     *  (bYaw ±13°, bPitch ≤7°) — таз не отрывается от ног. Кадры плотные
     *  (макс. дельта между соседними ~21°), траектория клинка непрерывна.
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус
     *  y/p/r, голова y/p/r, прав. нога y/p/r, лев. нога y/p/r. Углы в
     *  радианах, как у ModelPart. */
    // Удар 1: широкий горизонтальный слева направо
    private static final Pose hit1_00 = new Pose(0.000000f, -0.349066f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(-0.116937f, -0.406662f, 0.019199f, 0.000000f, -0.272271f, 0.038397f, -0.022689f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.057596f, 0.000000f, 0.000000f, 0.038397f, 0.000000f);
    private static final Pose hit1_02 = new Pose(-0.361283f, -0.530580f, 0.061087f, 0.000000f, -0.476475f, 0.120428f, -0.071558f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.181514f, 0.000000f, 0.000000f, 0.120428f, 0.000000f);
    private static final Pose hit1_03 = new Pose(-0.518363f, -0.609120f, 0.087266f, 0.000000f, -0.607375f, 0.172788f, -0.102974f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.260054f, 0.000000f, 0.000000f, 0.172788f, 0.000000f);
    private static final Pose hit1_04 = new Pose(-0.570723f, -0.652753f, 0.087266f, 0.000000f, -0.668461f, 0.193732f, -0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.286234f, 0.000000f, 0.000000f, 0.191986f, 0.000000f);
    private static final Pose hit1_05 = new Pose(-0.729548f, -0.794125f, 0.087266f, 0.000000f, -0.869174f, 0.263545f, -0.134390f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.371755f, 0.000000f, 0.000000f, 0.247837f, 0.000000f);
    private static final Pose hit1_06 = new Pose(-0.911062f, -0.956440f, 0.087266f, 0.000000f, -1.096067f, 0.340339f, -0.160570f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.469494f, 0.000000f, 0.000000f, 0.312414f, 0.000000f);
    private static final Pose hit1_07 = new Pose(-1.008800f, -1.043707f, 0.087266f, 0.000000f, -1.218240f, 0.382227f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.521853f, 0.000000f, 0.000000f, 0.347321f, 0.000000f);
    private static final Pose hit1_08 = new Pose(-0.996583f, -1.045452f, 0.080285f, -0.001745f, -1.211259f, 0.373500f, -0.171042f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.506145f, 0.000000f, 0.000000f, 0.340339f, 0.000000f);
    private static final Pose hit1_09 = new Pose(-0.932006f, -1.038471f, 0.052360f, -0.008727f, -1.169371f, 0.328122f, -0.160570f, 0.005236f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.436332f, 0.000000f, 0.000000f, 0.305433f, 0.000000f);
    private static final Pose hit1_10 = new Pose(-0.827286f, -1.027999f, 0.006981f, -0.019199f, -1.101303f, 0.256563f, -0.143117f, 0.012217f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.322886f, 0.000000f, 0.000000f, 0.249582f, 0.000000f);
    private static final Pose hit1_11 = new Pose(-0.696386f, -1.012291f, -0.050615f, -0.034907f, -1.015782f, 0.164061f, -0.120428f, 0.020944f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.179769f, 0.000000f, 0.000000f, 0.178024f, 0.000000f);
    private static final Pose hit1_12 = new Pose(-0.553269f, -0.996583f, -0.111701f, -0.050615f, -0.923279f, 0.064577f, -0.094248f, 0.029671f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.024435f, 0.000000f, 0.000000f, 0.099484f, 0.000000f);
    private static final Pose hit1_13 = new Pose(-0.418879f, -0.982620f, -0.171042f, -0.064577f, -0.834267f, -0.029671f, -0.071558f, 0.038397f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122173f, 0.000000f, 0.000000f, 0.026180f, 0.000000f);
    private static final Pose hit1_14 = new Pose(-0.307178f, -0.970403f, -0.219911f, -0.076794f, -0.762709f, -0.106465f, -0.052360f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.242601f, 0.000000f, 0.000000f, -0.033161f, 0.000000f);
    private static final Pose hit1_15 = new Pose(-0.235619f, -0.963422f, -0.251327f, -0.083776f, -0.713840f, -0.157080f, -0.040143f, 0.050615f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.321141f, 0.000000f, 0.000000f, -0.073304f, 0.000000f);
    private static final Pose hit1_16 = new Pose(-0.209440f, -0.959931f, -0.261799f, -0.087266f, -0.698132f, -0.174533f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit1_17 = new Pose(-0.205949f, -0.959931f, -0.261799f, -0.087266f, -0.696386f, -0.174533f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.352557f, 0.000000f, 0.000000f, -0.089012f, 0.000000f);
    private static final Pose hit1_18 = new Pose(-0.181514f, -0.963422f, -0.268781f, -0.090757f, -0.685914f, -0.181514f, -0.029671f, 0.054105f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.373500f, 0.000000f, 0.000000f, -0.099484f, 0.000000f);
    private static final Pose hit1_19 = new Pose(-0.115192f, -0.972148f, -0.282743f, -0.099484f, -0.656244f, -0.195477f, -0.017453f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434587f, 0.000000f, 0.000000f, -0.129154f, 0.000000f);
    private static final Pose hit1_20 = new Pose(0.012217f, -0.989602f, -0.312414f, -0.116937f, -0.596903f, -0.225147f, 0.005236f, 0.062832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.551524f, 0.000000f, 0.000000f, -0.188496f, 0.000000f);
    private static final Pose hit1_21 = new Pose(0.204204f, -1.010546f, -0.331613f, -0.125664f, -0.506145f, -0.244346f, 0.041888f, 0.066323f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.689405f, 0.000000f, 0.000000f, -0.253073f, 0.000000f);
    private static final Pose hit1_22 = new Pose(0.317650f, -1.003564f, -0.260054f, -0.068068f, -0.434587f, -0.172788f, 0.071558f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.652753f, 0.000000f, 0.000000f, -0.216421f, 0.000000f);
    private static final Pose hit1_23 = new Pose(0.390954f, -0.998328f, -0.212930f, -0.031416f, -0.387463f, -0.125664f, 0.089012f, 0.041888f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.630064f, 0.000000f, 0.000000f, -0.193732f, 0.000000f);
    private static final Pose hit1_24 = new Pose(0.431096f, -0.996583f, -0.188496f, -0.010472f, -0.363028f, -0.101229f, 0.099484f, 0.038397f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.617847f, 0.000000f, 0.000000f, -0.181514f, 0.000000f);
    private static final Pose hit1_25 = new Pose(0.450295f, -0.994838f, -0.178024f, -0.001745f, -0.352557f, -0.090757f, 0.102974f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.612611f, 0.000000f, 0.000000f, -0.176278f, 0.000000f);
    private static final Pose hit1_26 = new Pose(0.453786f, -0.994838f, -0.174533f, 0.000000f, -0.349066f, -0.087266f, 0.104720f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.610865f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit1_27 = new Pose(0.488692f, -0.994838f, -0.129154f, 0.000000f, -0.326377f, -0.064577f, 0.113446f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.596903f, 0.000000f, 0.000000f, -0.183260f, 0.000000f);
    private static final Pose hit1_28 = new Pose(0.534071f, -0.994838f, -0.073304f, 0.000000f, -0.298451f, -0.036652f, 0.125664f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.581195f, 0.000000f, 0.000000f, -0.195477f, 0.000000f);
    private static final Pose hit1_29 = new Pose(0.563741f, -0.994838f, -0.036652f, 0.000000f, -0.279253f, -0.017453f, 0.132645f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.568977f, 0.000000f, 0.000000f, -0.202458f, 0.000000f);
    private static final Pose hit1_30 = new Pose(0.581195f, -0.994838f, -0.013963f, 0.000000f, -0.268781f, -0.006981f, 0.136136f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.563741f, 0.000000f, 0.000000f, -0.205949f, 0.000000f);
    private static final Pose hit1_31 = new Pose(0.589921f, -0.994838f, -0.003491f, 0.000000f, -0.263545f, -0.001745f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.560251f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_32 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_33 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_34 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_35 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_36 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_37 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_38 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_39 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_40 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);

    private static final Pose hit2_00 = new Pose(0.593412f, -0.994838f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit2_01 = new Pose(0.581195f, -0.937242f, 0.033161f, 0.000000f, -0.359538f, 0.076794f, 0.132645f, 0.027925f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.455531f, 0.000000f, 0.000000f, -0.106465f, 0.000000f);
    private static final Pose hit2_02 = new Pose(0.560251f, -0.848230f, 0.082030f, 0.000000f, -0.506145f, 0.195477f, 0.123918f, 0.019199f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.298451f, 0.000000f, 0.000000f, 0.050615f, 0.000000f);
    private static final Pose hit2_03 = new Pose(0.553269f, -0.811578f, 0.099484f, 0.000000f, -0.561996f, 0.219911f, 0.116937f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.228638f, 0.000000f, 0.000000f, 0.092502f, 0.000000f);
    private static final Pose hit2_04 = new Pose(0.532325f, -0.706858f, 0.153589f, 0.000000f, -0.719076f, 0.261799f, 0.095993f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.017453f, 0.000000f, 0.000000f, 0.188496f, 0.000000f);
    private static final Pose hit2_05 = new Pose(0.523599f, -0.661480f, 0.174533f, 0.000000f, -0.787143f, 0.279253f, 0.087266f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.071558f, 0.000000f, 0.000000f, 0.228638f, 0.000000f);
    private static final Pose hit2_06 = new Pose(0.502655f, -0.616101f, 0.181514f, 0.000000f, -0.856957f, 0.293215f, 0.080285f, 0.013963f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.155334f, 0.000000f, 0.000000f, 0.270526f, 0.000000f);
    private static final Pose hit2_07 = new Pose(0.462512f, -0.532325f, 0.195477f, 0.000000f, -0.987856f, 0.319395f, 0.066323f, 0.006981f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.312414f, 0.000000f, 0.000000f, 0.347321f, 0.000000f);
    private static final Pose hit2_08 = new Pose(0.429351f, -0.457276f, 0.205949f, 0.000000f, -1.101303f, 0.342085f, 0.055851f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.448550f, 0.000000f, 0.000000f, 0.417134f, 0.000000f);
    private static final Pose hit2_09 = new Pose(0.418879f, -0.439823f, 0.207694f, 0.000000f, -1.134464f, 0.349066f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.486947f, 0.000000f, 0.000000f, 0.436332f, 0.000000f);
    private static final Pose hit2_10 = new Pose(0.420624f, -0.541052f, 0.174533f, 0.006981f, -1.115265f, 0.329867f, 0.054105f, 0.005236f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.438078f, 0.000000f, 0.000000f, 0.408407f, 0.000000f);
    private static final Pose hit2_11 = new Pose(0.425860f, -0.773181f, 0.095993f, 0.024435f, -1.073377f, 0.287979f, 0.059341f, 0.015708f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.326377f, 0.000000f, 0.000000f, 0.343830f, 0.000000f);
    private static final Pose hit2_12 = new Pose(0.431096f, -1.099557f, -0.013963f, 0.048869f, -1.014036f, 0.228638f, 0.064577f, 0.029671f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.169297f, 0.000000f, 0.000000f, 0.254818f, 0.000000f);
    private static final Pose hit2_13 = new Pose(0.438078f, -1.471313f, -0.139626f, 0.075049f, -0.945968f, 0.160570f, 0.071558f, 0.047124f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.010472f, 0.000000f, 0.000000f, 0.153589f, 0.000000f);
    private static final Pose hit2_14 = new Pose(0.445059f, -1.832596f, -0.260054f, 0.101229f, -0.881391f, 0.095993f, 0.078540f, 0.062832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.183260f, 0.000000f, 0.000000f, 0.055851f, 0.000000f);
    private static final Pose hit2_15 = new Pose(0.450295f, -2.125811f, -0.359538f, 0.122173f, -0.827286f, 0.041888f, 0.083776f, 0.076794f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.326377f, 0.000000f, 0.000000f, -0.024435f, 0.000000f);
    private static final Pose hit2_16 = new Pose(0.453786f, -2.309071f, -0.420624f, 0.136136f, -0.794125f, 0.008727f, 0.087266f, 0.085521f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.413643f, 0.000000f, 0.000000f, -0.075049f, 0.000000f);
    private static final Pose hit2_17 = new Pose(0.453786f, -2.356194f, -0.436332f, 0.139626f, -0.785398f, 0.000000f, 0.087266f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit2_18 = new Pose(0.453786f, -2.356194f, -0.436332f, 0.139626f, -0.783653f, -0.001745f, 0.087266f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.438078f, 0.000000f, 0.000000f, -0.089012f, 0.000000f);
    private static final Pose hit2_19 = new Pose(0.457276f, -2.357940f, -0.438078f, 0.137881f, -0.778417f, -0.005236f, 0.087266f, 0.085521f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.446804f, 0.000000f, 0.000000f, -0.094248f, 0.000000f);
    private static final Pose hit2_20 = new Pose(0.462512f, -2.363176f, -0.443314f, 0.136136f, -0.764454f, -0.017453f, 0.089012f, 0.083776f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.469494f, 0.000000f, 0.000000f, -0.109956f, 0.000000f);
    private static final Pose hit2_21 = new Pose(0.472984f, -2.370157f, -0.450295f, 0.129154f, -0.736529f, -0.040143f, 0.092502f, 0.076794f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.509636f, 0.000000f, 0.000000f, -0.136136f, 0.000000f);
    private static final Pose hit2_22 = new Pose(0.490438f, -2.384120f, -0.464258f, 0.120428f, -0.691150f, -0.075049f, 0.095993f, 0.068068f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575959f, 0.000000f, 0.000000f, -0.181514f, 0.000000f);
    private static final Pose hit2_23 = new Pose(0.516617f, -2.403318f, -0.483456f, 0.108210f, -0.626573f, -0.127409f, 0.102974f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.673697f, 0.000000f, 0.000000f, -0.246091f, 0.000000f);
    private static final Pose hit2_24 = new Pose(0.534071f, -2.419026f, -0.488692f, 0.071558f, -0.528835f, -0.111701f, 0.109956f, 0.036652f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.644026f, 0.000000f, 0.000000f, -0.223402f, 0.000000f);
    private static final Pose hit2_25 = new Pose(0.544543f, -2.429498f, -0.488692f, 0.041888f, -0.453786f, -0.087266f, 0.115192f, 0.020944f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.593412f, 0.000000f, 0.000000f, -0.188496f, 0.000000f);
    private static final Pose hit2_26 = new Pose(0.551524f, -2.436480f, -0.488692f, 0.020944f, -0.403171f, -0.069813f, 0.118682f, 0.010472f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.164061f, 0.000000f);
    private static final Pose hit2_27 = new Pose(0.555015f, -2.439970f, -0.488692f, 0.008727f, -0.371755f, -0.059341f, 0.120428f, 0.005236f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.539307f, 0.000000f, 0.000000f, -0.150098f, 0.000000f);
    private static final Pose hit2_28 = new Pose(0.556760f, -2.441716f, -0.488692f, 0.003491f, -0.356047f, -0.054105f, 0.122173f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.528835f, 0.000000f, 0.000000f, -0.143117f, 0.000000f);
    private static final Pose hit2_29 = new Pose(0.558505f, -2.443461f, -0.488692f, 0.000000f, -0.349066f, -0.052360f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit2_30 = new Pose(0.558505f, -2.443461f, -0.488692f, 0.000000f, -0.349066f, -0.052360f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit2_31 = new Pose(0.567232f, -2.452188f, -0.497419f, 0.000000f, -0.324631f, -0.013963f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.499164f, 0.000000f, 0.000000f, -0.125664f, 0.000000f);
    private static final Pose hit2_32 = new Pose(0.575959f, -2.460914f, -0.506145f, 0.000000f, -0.307178f, 0.015708f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.481711f, 0.000000f, 0.000000f, -0.113446f, 0.000000f);
    private static final Pose hit2_33 = new Pose(0.581195f, -2.466150f, -0.511381f, 0.000000f, -0.291470f, 0.040143f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.466003f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);
    private static final Pose hit2_34 = new Pose(0.586431f, -2.471386f, -0.516617f, 0.000000f, -0.280998f, 0.057596f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.455531f, 0.000000f, 0.000000f, -0.097738f, 0.000000f);
    private static final Pose hit2_35 = new Pose(0.589921f, -2.474877f, -0.520108f, 0.000000f, -0.272271f, 0.069813f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.446804f, 0.000000f, 0.000000f, -0.094248f, 0.000000f);
    private static final Pose hit2_36 = new Pose(0.591667f, -2.476622f, -0.521853f, 0.000000f, -0.267035f, 0.078540f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.441568f, 0.000000f, 0.000000f, -0.090757f, 0.000000f);
    private static final Pose hit2_37 = new Pose(0.591667f, -2.476622f, -0.521853f, 0.000000f, -0.263545f, 0.083776f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.438078f, 0.000000f, 0.000000f, -0.089012f, 0.000000f);
    private static final Pose hit2_38 = new Pose(0.593412f, -2.478368f, -0.523599f, 0.000000f, -0.261799f, 0.085521f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit2_39 = new Pose(0.593412f, -2.478368f, -0.523599f, 0.000000f, -0.261799f, 0.087266f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit2_40 = new Pose(0.593412f, -2.478368f, -0.523599f, 0.000000f, -0.261799f, 0.087266f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);

    private static final Pose hit3_00 = new Pose(0.593412f, -2.478368f, -0.523599f, 0.000000f, -0.261799f, 0.087266f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit3_01 = new Pose(0.577704f, -2.518510f, -0.502655f, 0.000000f, -0.364774f, 0.113446f, 0.116937f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.308923f, 0.000000f, 0.000000f, -0.022689f, 0.000000f);
    private static final Pose hit3_02 = new Pose(0.541052f, -2.617994f, -0.453786f, 0.000000f, -0.610865f, 0.174533f, 0.104720f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.130900f, 0.000000f);
    private static final Pose hit3_03 = new Pose(0.504400f, -2.717478f, -0.404916f, 0.000000f, -0.856957f, 0.235619f, 0.092502f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.308923f, 0.000000f, 0.000000f, 0.284489f, 0.000000f);
    private static final Pose hit3_04 = new Pose(0.488692f, -2.757620f, -0.383972f, 0.000000f, -0.959931f, 0.261799f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.436332f, 0.000000f, 0.000000f, 0.349066f, 0.000000f);
    private static final Pose hit3_05 = new Pose(0.471239f, -2.782055f, -0.366519f, 0.000000f, -1.014036f, 0.274017f, 0.085521f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.427606f, 0.000000f, 0.000000f, 0.363028f, 0.000000f);
    private static final Pose hit3_06 = new Pose(0.424115f, -2.848377f, -0.319395f, 0.000000f, -1.153663f, 0.307178f, 0.080285f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.403171f, 0.000000f, 0.000000f, 0.401426f, 0.000000f);
    private static final Pose hit3_07 = new Pose(0.368264f, -2.926917f, -0.263545f, 0.000000f, -1.321214f, 0.345575f, 0.075049f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.375246f, 0.000000f, 0.000000f, 0.445059f, 0.000000f);
    private static final Pose hit3_08 = new Pose(0.326377f, -2.986258f, -0.221657f, 0.000000f, -1.448623f, 0.375246f, 0.071558f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.354302f, 0.000000f, 0.000000f, 0.479966f, 0.000000f);
    private static final Pose hit3_09 = new Pose(0.314159f, -3.001966f, -0.209440f, 0.000000f, -1.483530f, 0.383972f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.488692f, 0.000000f);
    private static final Pose hit3_10 = new Pose(0.310669f, -2.972296f, -0.207694f, 0.003491f, -1.467822f, 0.373500f, 0.068068f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.333358f, 0.000000f, 0.000000f, 0.478220f, 0.000000f);
    private static final Pose hit3_11 = new Pose(0.303687f, -2.905973f, -0.204204f, 0.010472f, -1.434661f, 0.350811f, 0.064577f, 0.006981f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.300197f, 0.000000f, 0.000000f, 0.453786f, 0.000000f);
    private static final Pose hit3_12 = new Pose(0.293215f, -2.804744f, -0.198968f, 0.020944f, -1.380555f, 0.314159f, 0.059341f, 0.012217f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.246091f, 0.000000f, 0.000000f, 0.417134f, 0.000000f);
    private static final Pose hit3_13 = new Pose(0.280998f, -2.675590f, -0.191986f, 0.033161f, -1.314233f, 0.268781f, 0.052360f, 0.020944f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.179769f, 0.000000f, 0.000000f, 0.370010f, 0.000000f);
    private static final Pose hit3_14 = new Pose(0.265290f, -2.525491f, -0.185005f, 0.048869f, -1.235693f, 0.216421f, 0.045379f, 0.031416f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.101229f, 0.000000f, 0.000000f, 0.315905f, 0.000000f);
    private static final Pose hit3_15 = new Pose(0.247837f, -2.363176f, -0.176278f, 0.066323f, -1.151917f, 0.160570f, 0.036652f, 0.041888f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.017453f, 0.000000f, 0.000000f, 0.256563f, 0.000000f);
    private static final Pose hit3_16 = new Pose(0.230383f, -2.199115f, -0.167552f, 0.083776f, -1.066396f, 0.102974f, 0.027925f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.068068f, 0.000000f, 0.000000f, 0.197222f, 0.000000f);
    private static final Pose hit3_17 = new Pose(0.214675f, -2.042035f, -0.158825f, 0.099484f, -0.984366f, 0.047124f, 0.019199f, 0.062832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.150098f, 0.000000f, 0.000000f, 0.139626f, 0.000000f);
    private static final Pose hit3_18 = new Pose(0.200713f, -1.904154f, -0.151844f, 0.113446f, -0.912807f, -0.001745f, 0.012217f, 0.071558f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.221657f, 0.000000f, 0.000000f, 0.089012f, 0.000000f);
    private static final Pose hit3_19 = new Pose(0.188496f, -1.790708f, -0.146608f, 0.125664f, -0.853466f, -0.040143f, 0.006981f, 0.078540f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.280998f, 0.000000f, 0.000000f, 0.048869f, 0.000000f);
    private static final Pose hit3_20 = new Pose(0.179769f, -1.708677f, -0.143117f, 0.134390f, -0.811578f, -0.069813f, 0.003491f, 0.083776f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.322886f, 0.000000f, 0.000000f, 0.019199f, 0.000000f);
    private static final Pose hit3_21 = new Pose(0.174533f, -1.665044f, -0.139626f, 0.139626f, -0.788889f, -0.085521f, 0.000000f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.345575f, 0.000000f, 0.000000f, 0.003491f, 0.000000f);
    private static final Pose hit3_22 = new Pose(0.174533f, -1.658063f, -0.139626f, 0.139626f, -0.785398f, -0.087266f, 0.000000f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_23 = new Pose(0.172788f, -1.642355f, -0.137881f, 0.136136f, -0.778417f, -0.087266f, -0.001745f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.357792f, 0.000000f, 0.000000f, -0.006981f, 0.000000f);
    private static final Pose hit3_24 = new Pose(0.162316f, -1.581268f, -0.132645f, 0.122173f, -0.752237f, -0.087266f, -0.006981f, 0.083776f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.392699f, 0.000000f, 0.000000f, -0.033161f, 0.000000f);
    private static final Pose hit3_25 = new Pose(0.137881f, -1.441642f, -0.120428f, 0.090757f, -0.692896f, -0.087266f, -0.019199f, 0.075049f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.472984f, 0.000000f, 0.000000f, -0.092502f, 0.000000f);
    private static final Pose hit3_26 = new Pose(0.094248f, -1.186824f, -0.099484f, 0.031416f, -0.582940f, -0.087266f, -0.040143f, 0.061087f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.617847f, 0.000000f, 0.000000f, -0.202458f, 0.000000f);
    private static final Pose hit3_27 = new Pose(0.090757f, -1.071632f, -0.062832f, 0.000000f, -0.472984f, -0.062832f, -0.068068f, 0.036652f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.637045f, 0.000000f, 0.000000f, -0.226893f, 0.000000f);
    private static final Pose hit3_28 = new Pose(0.113446f, -1.103048f, -0.031416f, 0.000000f, -0.411898f, -0.031416f, -0.085521f, 0.019199f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.565487f, 0.000000f, 0.000000f, -0.185005f, 0.000000f);
    private static final Pose hit3_29 = new Pose(0.129154f, -1.120501f, -0.013963f, 0.000000f, -0.375246f, -0.013963f, -0.095993f, 0.008727f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.520108f, 0.000000f, 0.000000f, -0.158825f, 0.000000f);
    private static final Pose hit3_30 = new Pose(0.136136f, -1.130973f, -0.003491f, 0.000000f, -0.357792f, -0.003491f, -0.102974f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.497419f, 0.000000f, 0.000000f, -0.144862f, 0.000000f);
    private static final Pose hit3_31 = new Pose(0.139626f, -1.134464f, 0.000000f, 0.000000f, -0.350811f, 0.000000f, -0.104720f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.490438f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit3_32 = new Pose(0.139626f, -1.134464f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, -0.104720f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488692f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit3_33 = new Pose(0.160570f, -1.204277f, 0.050615f, 0.000000f, -0.298451f, 0.000000f, -0.125664f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.408407f, 0.000000f, 0.000000f, -0.059341f, 0.000000f);
    private static final Pose hit3_34 = new Pose(0.171042f, -1.240929f, 0.076794f, 0.000000f, -0.272271f, 0.000000f, -0.134390f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.366519f, 0.000000f, 0.000000f, -0.017453f, 0.000000f);
    private static final Pose hit3_35 = new Pose(0.174533f, -1.254892f, 0.085521f, 0.000000f, -0.263545f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.350811f, 0.000000f, 0.000000f, -0.001745f, 0.000000f);
    private static final Pose hit3_36 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_37 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_38 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_39 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_40 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit4_00 = new Pose(0.174533f, -1.256637f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(0.232129f, -1.364847f, 0.048869f, 0.000000f, -0.378736f, 0.057596f, -0.155334f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.212930f, 0.000000f, 0.000000f, 0.047124f, 0.000000f);
    private static final Pose hit4_02 = new Pose(0.356047f, -1.595231f, -0.033161f, 0.000000f, -0.623083f, 0.181514f, -0.188496f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.073304f, 0.000000f, 0.000000f, 0.144862f, 0.000000f);
    private static final Pose hit4_03 = new Pose(0.434587f, -1.740093f, -0.085521f, 0.000000f, -0.780162f, 0.260054f, -0.209440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.256563f, 0.000000f, 0.000000f, 0.207694f, 0.000000f);
    private static final Pose hit4_04 = new Pose(0.452040f, -1.820378f, -0.092502f, 0.000000f, -0.842994f, 0.274017f, -0.211185f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.286234f, 0.000000f, 0.000000f, 0.223402f, 0.000000f);
    private static final Pose hit4_05 = new Pose(0.502655f, -2.076942f, -0.109956f, 0.000000f, -1.043707f, 0.314159f, -0.216421f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.371755f, 0.000000f, 0.000000f, 0.268781f, 0.000000f);
    private static final Pose hit4_06 = new Pose(0.560251f, -2.368412f, -0.129154f, 0.000000f, -1.270600f, 0.359538f, -0.223402f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.469494f, 0.000000f, 0.000000f, 0.321141f, 0.000000f);
    private static final Pose hit4_07 = new Pose(0.591667f, -2.525491f, -0.139626f, 0.000000f, -1.392773f, 0.383972f, -0.226893f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.521853f, 0.000000f, 0.000000f, 0.349066f, 0.000000f);
    private static final Pose hit4_08 = new Pose(0.586431f, -2.499311f, -0.130900f, 0.003491f, -1.382301f, 0.373500f, -0.223402f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.506145f, 0.000000f, 0.000000f, 0.340339f, 0.000000f);
    private static final Pose hit4_09 = new Pose(0.555015f, -2.373648f, -0.099484f, 0.013963f, -1.326450f, 0.328122f, -0.207694f, 0.005236f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.436332f, 0.000000f, 0.000000f, 0.305433f, 0.000000f);
    private static final Pose hit4_10 = new Pose(0.504400f, -2.169444f, -0.047124f, 0.031416f, -1.235693f, 0.256563f, -0.183260f, 0.012217f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.322886f, 0.000000f, 0.000000f, 0.249582f, 0.000000f);
    private static final Pose hit4_11 = new Pose(0.441568f, -1.912881f, 0.019199f, 0.054105f, -1.122247f, 0.164061f, -0.151844f, 0.020944f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.179769f, 0.000000f, 0.000000f, 0.178024f, 0.000000f);
    private static final Pose hit4_12 = new Pose(0.373500f, -1.633628f, 0.089012f, 0.080285f, -0.998328f, 0.064577f, -0.116937f, 0.029671f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.024435f, 0.000000f, 0.000000f, 0.099484f, 0.000000f);
    private static final Pose hit4_13 = new Pose(0.308923f, -1.368338f, 0.157080f, 0.102974f, -0.879646f, -0.029671f, -0.085521f, 0.038397f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122173f, 0.000000f, 0.000000f, 0.026180f, 0.000000f);
    private static final Pose hit4_14 = new Pose(0.256563f, -1.151917f, 0.212930f, 0.122173f, -0.783653f, -0.106465f, -0.057596f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.242601f, 0.000000f, 0.000000f, -0.033161f, 0.000000f);
    private static final Pose hit4_15 = new Pose(0.221657f, -1.008800f, 0.249582f, 0.134390f, -0.720821f, -0.157080f, -0.040143f, 0.050615f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.321141f, 0.000000f, 0.000000f, -0.073304f, 0.000000f);
    private static final Pose hit4_16 = new Pose(0.209440f, -0.959931f, 0.261799f, 0.139626f, -0.698132f, -0.174533f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit4_17 = new Pose(0.205949f, -0.959931f, 0.261799f, 0.139626f, -0.696386f, -0.174533f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.352557f, 0.000000f, 0.000000f, -0.089012f, 0.000000f);
    private static final Pose hit4_18 = new Pose(0.179769f, -0.963422f, 0.265290f, 0.137881f, -0.685914f, -0.181514f, -0.029671f, 0.054105f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.373500f, 0.000000f, 0.000000f, -0.099484f, 0.000000f);
    private static final Pose hit4_19 = new Pose(0.106465f, -0.972148f, 0.274017f, 0.130900f, -0.656244f, -0.195477f, -0.017453f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434587f, 0.000000f, 0.000000f, -0.129154f, 0.000000f);
    private static final Pose hit4_20 = new Pose(-0.033161f, -0.989602f, 0.291470f, 0.118682f, -0.596903f, -0.225147f, 0.005236f, 0.062832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.551524f, 0.000000f, 0.000000f, -0.188496f, 0.000000f);
    private static final Pose hit4_21 = new Pose(-0.239110f, -1.019272f, 0.300197f, 0.094248f, -0.506145f, -0.244346f, 0.041888f, 0.066323f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.684169f, 0.000000f, 0.000000f, -0.253073f, 0.000000f);
    private static final Pose hit4_22 = new Pose(-0.352557f, -1.048943f, 0.242601f, 0.050615f, -0.434587f, -0.172788f, 0.071558f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.626573f, 0.000000f, 0.000000f, -0.216421f, 0.000000f);
    private static final Pose hit4_23 = new Pose(-0.425860f, -1.066396f, 0.205949f, 0.022689f, -0.387463f, -0.125664f, 0.089012f, 0.041888f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.589921f, 0.000000f, 0.000000f, -0.193732f, 0.000000f);
    private static final Pose hit4_24 = new Pose(-0.466003f, -1.076868f, 0.185005f, 0.008727f, -0.363028f, -0.101229f, 0.099484f, 0.038397f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.568977f, 0.000000f, 0.000000f, -0.181514f, 0.000000f);
    private static final Pose hit4_25 = new Pose(-0.485202f, -1.080359f, 0.176278f, 0.001745f, -0.352557f, -0.090757f, 0.102974f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.560251f, 0.000000f, 0.000000f, -0.176278f, 0.000000f);
    private static final Pose hit4_26 = new Pose(-0.488692f, -1.082104f, 0.174533f, 0.000000f, -0.349066f, -0.087266f, 0.104720f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit4_27 = new Pose(-0.523599f, -1.136209f, 0.165806f, 0.000000f, -0.326377f, -0.064577f, 0.118682f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.513127f, 0.000000f, 0.000000f, -0.129154f, 0.000000f);
    private static final Pose hit4_28 = new Pose(-0.568977f, -1.202532f, 0.153589f, 0.000000f, -0.298451f, -0.036652f, 0.134390f, 0.013963f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.457276f, 0.000000f, 0.000000f, -0.073304f, 0.000000f);
    private static final Pose hit4_29 = new Pose(-0.598648f, -1.247910f, 0.146608f, 0.000000f, -0.279253f, -0.017453f, 0.146608f, 0.006981f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.420624f, 0.000000f, 0.000000f, -0.036652f, 0.000000f);
    private static final Pose hit4_30 = new Pose(-0.616101f, -1.274090f, 0.143117f, 0.000000f, -0.268781f, -0.006981f, 0.151844f, 0.003491f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.397935f, 0.000000f, 0.000000f, -0.013963f, 0.000000f);
    private static final Pose hit4_31 = new Pose(-0.624828f, -1.286308f, 0.139626f, 0.000000f, -0.263545f, -0.001745f, 0.155334f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.387463f, 0.000000f, 0.000000f, -0.003491f, 0.000000f);
    private static final Pose hit4_32 = new Pose(-0.628319f, -1.291544f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.157080f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.383972f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_33 = new Pose(-0.631809f, -1.296780f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.157080f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.380482f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_34 = new Pose(-0.645772f, -1.322960f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.160570f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.363028f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_35 = new Pose(-0.659734f, -1.350885f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.162316f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.347321f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_36 = new Pose(-0.675442f, -1.377065f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.164061f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.329867f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_37 = new Pose(-0.689405f, -1.403245f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.167552f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.312414f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_38 = new Pose(-0.703368f, -1.429425f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.169297f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.294961f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_39 = new Pose(-0.719076f, -1.457350f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.172788f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.279253f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_40 = new Pose(-0.733038f, -1.483530f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit5_00 = new Pose(-0.733038f, -1.483530f, 0.139626f, 0.000000f, -0.261799f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(-0.795870f, -1.563815f, 0.127409f, 0.000000f, -0.448550f, 0.094248f, 0.186750f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.075049f, 0.000000f, 0.000000f, 0.094248f, 0.000000f);
    private static final Pose hit5_02 = new Pose(-0.856957f, -1.645845f, 0.115192f, 0.000000f, -0.635300f, 0.186750f, 0.198968f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.111701f, 0.000000f, 0.000000f, 0.186750f, 0.000000f);
    private static final Pose hit5_03 = new Pose(-0.923279f, -1.722640f, 0.102974f, 0.000000f, -0.822050f, 0.270526f, 0.211185f, 0.001745f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.280998f, 0.000000f, 0.000000f, 0.270526f, 0.000000f);
    private static final Pose hit5_04 = new Pose(-0.996583f, -1.785472f, 0.089012f, 0.000000f, -1.010546f, 0.314159f, 0.216421f, 0.015708f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.373500f, 0.000000f, 0.000000f, 0.314159f, 0.000000f);
    private static final Pose hit5_05 = new Pose(-1.071632f, -1.848304f, 0.076794f, 0.000000f, -1.197296f, 0.357792f, 0.223402f, 0.027925f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.467748f, 0.000000f, 0.000000f, 0.357792f, 0.000000f);
    private static final Pose hit5_06 = new Pose(-1.134464f, -1.897173f, 0.062832f, 0.000000f, -1.352630f, 0.394444f, 0.226893f, 0.036652f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.541052f, 0.000000f, 0.000000f, 0.394444f, 0.000000f);
    private static final Pose hit5_07 = new Pose(-1.178097f, -1.928589f, 0.045379f, 0.000000f, -1.462586f, 0.420624f, 0.226893f, 0.040143f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.584685f, 0.000000f, 0.000000f, 0.420624f, 0.000000f);
    private static final Pose hit5_08 = new Pose(-1.221730f, -1.958259f, 0.027925f, 0.000000f, -1.570796f, 0.446804f, 0.226893f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.628319f, 0.000000f, 0.000000f, 0.446804f, 0.000000f);
    private static final Pose hit5_09 = new Pose(-1.265364f, -1.989675f, 0.010472f, 0.000000f, -1.679007f, 0.472984f, 0.226893f, 0.050615f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.671952f, 0.000000f, 0.000000f, 0.472984f, 0.000000f);
    private static final Pose hit5_10 = new Pose(-1.288053f, -2.003638f, -0.001745f, 0.000000f, -1.741839f, 0.486947f, 0.226893f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.692896f, 0.000000f, 0.000000f, 0.486947f, 0.000000f);
    private static final Pose hit5_11 = new Pose(-1.244420f, -1.965241f, -0.012217f, 0.006981f, -1.699951f, 0.459022f, 0.216421f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.644026f, 0.000000f, 0.000000f, 0.467748f, 0.000000f);
    private static final Pose hit5_12 = new Pose(-1.155408f, -1.886701f, -0.033161f, 0.017453f, -1.614430f, 0.404916f, 0.198968f, 0.062832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.544543f, 0.000000f, 0.000000f, 0.427606f, 0.000000f);
    private static final Pose hit5_13 = new Pose(-1.029744f, -1.775000f, -0.062832f, 0.033161f, -1.492257f, 0.328122f, 0.172788f, 0.073304f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.403171f, 0.000000f, 0.000000f, 0.370010f, 0.000000f);
    private static final Pose hit5_14 = new Pose(-0.879646f, -1.642355f, -0.099484f, 0.052360f, -1.347394f, 0.235619f, 0.141372f, 0.085521f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.233874f, 0.000000f, 0.000000f, 0.303687f, 0.000000f);
    private static final Pose hit5_15 = new Pose(-0.717330f, -1.497492f, -0.139626f, 0.073304f, -1.190315f, 0.137881f, 0.106465f, 0.099484f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.050615f, 0.000000f, 0.000000f, 0.230383f, 0.000000f);
    private static final Pose hit5_16 = new Pose(-0.558505f, -1.356121f, -0.178024f, 0.094248f, -1.036726f, 0.040143f, 0.073304f, 0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.129154f, 0.000000f, 0.000000f, 0.157080f, 0.000000f);
    private static final Pose hit5_17 = new Pose(-0.417134f, -1.230457f, -0.211185f, 0.113446f, -0.898845f, -0.047124f, 0.043633f, 0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.289725f, 0.000000f, 0.000000f, 0.094248f, 0.000000f);
    private static final Pose hit5_18 = new Pose(-0.305433f, -1.132719f, -0.239110f, 0.127409f, -0.790634f, -0.115192f, 0.020944f, 0.132645f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.415388f, 0.000000f, 0.000000f, 0.043633f, 0.000000f);
    private static final Pose hit5_19 = new Pose(-0.233874f, -1.068142f, -0.256563f, 0.136136f, -0.722566f, -0.158825f, 0.005236f, 0.137881f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.495674f, 0.000000f, 0.000000f, 0.010472f, 0.000000f);
    private static final Pose hit5_20 = new Pose(-0.209440f, -1.047198f, -0.261799f, 0.139626f, -0.698132f, -0.174533f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_21 = new Pose(-0.205949f, -1.047198f, -0.261799f, 0.139626f, -0.696386f, -0.174533f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.527089f, 0.000000f, 0.000000f, -0.003491f, 0.000000f);
    private static final Pose hit5_22 = new Pose(-0.181514f, -1.047198f, -0.265290f, 0.137881f, -0.685914f, -0.181514f, -0.006981f, 0.137881f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.555015f, 0.000000f, 0.000000f, -0.024435f, 0.000000f);
    private static final Pose hit5_23 = new Pose(-0.115192f, -1.047198f, -0.274017f, 0.130900f, -0.656244f, -0.195477f, -0.020944f, 0.136136f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.630064f, 0.000000f, 0.000000f, -0.085521f, 0.000000f);
    private static final Pose hit5_24 = new Pose(0.012217f, -1.047198f, -0.291470f, 0.118682f, -0.596903f, -0.225147f, -0.050615f, 0.129154f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.776672f, 0.000000f, 0.000000f, -0.202458f, 0.000000f);
    private static final Pose hit5_25 = new Pose(0.198968f, -1.043707f, -0.296706f, 0.094248f, -0.506145f, -0.244346f, -0.094248f, 0.116937f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.942478f, 0.000000f, 0.000000f, -0.335103f, 0.000000f);
    private static final Pose hit5_26 = new Pose(0.300197f, -1.029744f, -0.225147f, 0.050615f, -0.434587f, -0.172788f, -0.123918f, 0.095993f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.870919f, 0.000000f, 0.000000f, -0.277507f, 0.000000f);
    private static final Pose hit5_27 = new Pose(0.364774f, -1.019272f, -0.178024f, 0.022689f, -0.387463f, -0.125664f, -0.141372f, 0.082030f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.823795f, 0.000000f, 0.000000f, -0.240855f, 0.000000f);
    private static final Pose hit5_28 = new Pose(0.399680f, -1.015782f, -0.153589f, 0.008727f, -0.363028f, -0.101229f, -0.151844f, 0.073304f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.799361f, 0.000000f, 0.000000f, -0.219911f, 0.000000f);
    private static final Pose hit5_29 = new Pose(0.415388f, -1.012291f, -0.143117f, 0.001745f, -0.352557f, -0.090757f, -0.155334f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.788889f, 0.000000f, 0.000000f, -0.211185f, 0.000000f);
    private static final Pose hit5_30 = new Pose(0.418879f, -1.012291f, -0.139626f, 0.000000f, -0.349066f, -0.087266f, -0.157080f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.785398f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit5_31 = new Pose(0.467748f, -1.012291f, -0.075049f, 0.000000f, -0.308923f, -0.047124f, -0.165806f, 0.061087f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.705113f, 0.000000f, 0.000000f, -0.176278f, 0.000000f);
    private static final Pose hit5_32 = new Pose(0.511381f, -1.012291f, -0.017453f, 0.000000f, -0.272271f, -0.010472f, -0.172788f, 0.054105f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.631809f, 0.000000f, 0.000000f, -0.148353f, 0.000000f);
    private static final Pose hit5_33 = new Pose(0.523599f, -1.012291f, -0.001745f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.612611f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit5_34 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.609120f, 0.000000f, 0.000000f, -0.134390f, 0.000000f);
    private static final Pose hit5_35 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.059341f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.589921f, 0.000000f, 0.000000f, -0.083776f, 0.000000f);
    private static final Pose hit5_36 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.068068f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.565487f, 0.000000f, 0.000000f, -0.020944f, 0.000000f);
    private static final Pose hit5_37 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.068068f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556760f, 0.000000f, 0.000000f, -0.006981f, 0.000000f);
    private static final Pose hit5_38 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.057596f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.544543f, 0.000000f, 0.000000f, -0.038397f, 0.000000f);
    private static final Pose hit5_39 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.534071f, 0.000000f, 0.000000f, -0.071558f, 0.000000f);
    private static final Pose hit5_40 = new Pose(0.523599f, -1.012291f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);

    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // hit1
                new Keyframe(0.000f, 0, hit1_00),
                new Keyframe(0.025f, 0, hit1_01),
                new Keyframe(0.050f, 0, hit1_02),
                new Keyframe(0.075f, 0, hit1_03),
                new Keyframe(0.100f, 0, hit1_04),
                new Keyframe(0.125f, 0, hit1_05),
                new Keyframe(0.150f, 0, hit1_06),
                new Keyframe(0.175f, 0, hit1_07),
                new Keyframe(0.200f, 0, hit1_08),
                new Keyframe(0.225f, 0, hit1_09),
                new Keyframe(0.250f, 0, hit1_10),
                new Keyframe(0.275f, 0, hit1_11),
                new Keyframe(0.300f, 0, hit1_12),
                new Keyframe(0.325f, 0, hit1_13),
                new Keyframe(0.350f, 0, hit1_14),
                new Keyframe(0.375f, 0, hit1_15),
                new Keyframe(0.400f, 0, hit1_16),
                new Keyframe(0.425f, 0, hit1_17),
                new Keyframe(0.450f, 0, hit1_18),
                new Keyframe(0.475f, 0, hit1_19),
                new Keyframe(0.500f, 0, hit1_20),
                new Keyframe(0.525f, 0, hit1_21),
                new Keyframe(0.550f, 0, hit1_22),
                new Keyframe(0.575f, 0, hit1_23),
                new Keyframe(0.600f, 0, hit1_24),
                new Keyframe(0.625f, 0, hit1_25),
                new Keyframe(0.650f, 0, hit1_26),
                new Keyframe(0.675f, 0, hit1_27),
                new Keyframe(0.700f, 0, hit1_28),
                new Keyframe(0.725f, 0, hit1_29),
                new Keyframe(0.750f, 0, hit1_30),
                new Keyframe(0.775f, 0, hit1_31),
                new Keyframe(0.800f, 0, hit1_32),
                new Keyframe(0.825f, 0, hit1_33),
                new Keyframe(0.850f, 0, hit1_34),
                new Keyframe(0.875f, 0, hit1_35),
                new Keyframe(0.900f, 0, hit1_36),
                new Keyframe(0.925f, 0, hit1_37),
                new Keyframe(0.950f, 0, hit1_38),
                new Keyframe(0.975f, 0, hit1_39),
                new Keyframe(1.000f, 0, hit1_40),
        }),
        new Clip(new Keyframe[] { // hit2
                new Keyframe(0.000f, 0, hit2_00),
                new Keyframe(0.025f, 0, hit2_01),
                new Keyframe(0.050f, 0, hit2_02),
                new Keyframe(0.075f, 0, hit2_03),
                new Keyframe(0.100f, 0, hit2_04),
                new Keyframe(0.125f, 0, hit2_05),
                new Keyframe(0.150f, 0, hit2_06),
                new Keyframe(0.175f, 0, hit2_07),
                new Keyframe(0.200f, 0, hit2_08),
                new Keyframe(0.225f, 0, hit2_09),
                new Keyframe(0.250f, 0, hit2_10),
                new Keyframe(0.275f, 0, hit2_11),
                new Keyframe(0.300f, 0, hit2_12),
                new Keyframe(0.325f, 0, hit2_13),
                new Keyframe(0.350f, 0, hit2_14),
                new Keyframe(0.375f, 0, hit2_15),
                new Keyframe(0.400f, 0, hit2_16),
                new Keyframe(0.425f, 0, hit2_17),
                new Keyframe(0.450f, 0, hit2_18),
                new Keyframe(0.475f, 0, hit2_19),
                new Keyframe(0.500f, 0, hit2_20),
                new Keyframe(0.525f, 0, hit2_21),
                new Keyframe(0.550f, 0, hit2_22),
                new Keyframe(0.575f, 0, hit2_23),
                new Keyframe(0.600f, 0, hit2_24),
                new Keyframe(0.625f, 0, hit2_25),
                new Keyframe(0.650f, 0, hit2_26),
                new Keyframe(0.675f, 0, hit2_27),
                new Keyframe(0.700f, 0, hit2_28),
                new Keyframe(0.725f, 0, hit2_29),
                new Keyframe(0.750f, 0, hit2_30),
                new Keyframe(0.775f, 0, hit2_31),
                new Keyframe(0.800f, 0, hit2_32),
                new Keyframe(0.825f, 0, hit2_33),
                new Keyframe(0.850f, 0, hit2_34),
                new Keyframe(0.875f, 0, hit2_35),
                new Keyframe(0.900f, 0, hit2_36),
                new Keyframe(0.925f, 0, hit2_37),
                new Keyframe(0.950f, 0, hit2_38),
                new Keyframe(0.975f, 0, hit2_39),
                new Keyframe(1.000f, 0, hit2_40),
        }),
        new Clip(new Keyframe[] { // hit3
                new Keyframe(0.000f, 0, hit3_00),
                new Keyframe(0.025f, 0, hit3_01),
                new Keyframe(0.050f, 0, hit3_02),
                new Keyframe(0.075f, 0, hit3_03),
                new Keyframe(0.100f, 0, hit3_04),
                new Keyframe(0.125f, 0, hit3_05),
                new Keyframe(0.150f, 0, hit3_06),
                new Keyframe(0.175f, 0, hit3_07),
                new Keyframe(0.200f, 0, hit3_08),
                new Keyframe(0.225f, 0, hit3_09),
                new Keyframe(0.250f, 0, hit3_10),
                new Keyframe(0.275f, 0, hit3_11),
                new Keyframe(0.300f, 0, hit3_12),
                new Keyframe(0.325f, 0, hit3_13),
                new Keyframe(0.350f, 0, hit3_14),
                new Keyframe(0.375f, 0, hit3_15),
                new Keyframe(0.400f, 0, hit3_16),
                new Keyframe(0.425f, 0, hit3_17),
                new Keyframe(0.450f, 0, hit3_18),
                new Keyframe(0.475f, 0, hit3_19),
                new Keyframe(0.500f, 0, hit3_20),
                new Keyframe(0.525f, 0, hit3_21),
                new Keyframe(0.550f, 0, hit3_22),
                new Keyframe(0.575f, 0, hit3_23),
                new Keyframe(0.600f, 0, hit3_24),
                new Keyframe(0.625f, 0, hit3_25),
                new Keyframe(0.650f, 0, hit3_26),
                new Keyframe(0.675f, 0, hit3_27),
                new Keyframe(0.700f, 0, hit3_28),
                new Keyframe(0.725f, 0, hit3_29),
                new Keyframe(0.750f, 0, hit3_30),
                new Keyframe(0.775f, 0, hit3_31),
                new Keyframe(0.800f, 0, hit3_32),
                new Keyframe(0.825f, 0, hit3_33),
                new Keyframe(0.850f, 0, hit3_34),
                new Keyframe(0.875f, 0, hit3_35),
                new Keyframe(0.900f, 0, hit3_36),
                new Keyframe(0.925f, 0, hit3_37),
                new Keyframe(0.950f, 0, hit3_38),
                new Keyframe(0.975f, 0, hit3_39),
                new Keyframe(1.000f, 0, hit3_40),
        }),
        new Clip(new Keyframe[] { // hit4
                new Keyframe(0.000f, 0, hit4_00),
                new Keyframe(0.025f, 0, hit4_01),
                new Keyframe(0.050f, 0, hit4_02),
                new Keyframe(0.075f, 0, hit4_03),
                new Keyframe(0.100f, 0, hit4_04),
                new Keyframe(0.125f, 0, hit4_05),
                new Keyframe(0.150f, 0, hit4_06),
                new Keyframe(0.175f, 0, hit4_07),
                new Keyframe(0.200f, 0, hit4_08),
                new Keyframe(0.225f, 0, hit4_09),
                new Keyframe(0.250f, 0, hit4_10),
                new Keyframe(0.275f, 0, hit4_11),
                new Keyframe(0.300f, 0, hit4_12),
                new Keyframe(0.325f, 0, hit4_13),
                new Keyframe(0.350f, 0, hit4_14),
                new Keyframe(0.375f, 0, hit4_15),
                new Keyframe(0.400f, 0, hit4_16),
                new Keyframe(0.425f, 0, hit4_17),
                new Keyframe(0.450f, 0, hit4_18),
                new Keyframe(0.475f, 0, hit4_19),
                new Keyframe(0.500f, 0, hit4_20),
                new Keyframe(0.525f, 0, hit4_21),
                new Keyframe(0.550f, 0, hit4_22),
                new Keyframe(0.575f, 0, hit4_23),
                new Keyframe(0.600f, 0, hit4_24),
                new Keyframe(0.625f, 0, hit4_25),
                new Keyframe(0.650f, 0, hit4_26),
                new Keyframe(0.675f, 0, hit4_27),
                new Keyframe(0.700f, 0, hit4_28),
                new Keyframe(0.725f, 0, hit4_29),
                new Keyframe(0.750f, 0, hit4_30),
                new Keyframe(0.775f, 0, hit4_31),
                new Keyframe(0.800f, 0, hit4_32),
                new Keyframe(0.825f, 0, hit4_33),
                new Keyframe(0.850f, 0, hit4_34),
                new Keyframe(0.875f, 0, hit4_35),
                new Keyframe(0.900f, 0, hit4_36),
                new Keyframe(0.925f, 0, hit4_37),
                new Keyframe(0.950f, 0, hit4_38),
                new Keyframe(0.975f, 0, hit4_39),
                new Keyframe(1.000f, 0, hit4_40),
        }),
        new Clip(new Keyframe[] { // hit5
                new Keyframe(0.000f, 0, hit5_00),
                new Keyframe(0.025f, 0, hit5_01),
                new Keyframe(0.050f, 0, hit5_02),
                new Keyframe(0.075f, 0, hit5_03),
                new Keyframe(0.100f, 0, hit5_04),
                new Keyframe(0.125f, 0, hit5_05),
                new Keyframe(0.150f, 0, hit5_06),
                new Keyframe(0.175f, 0, hit5_07),
                new Keyframe(0.200f, 0, hit5_08),
                new Keyframe(0.225f, 0, hit5_09),
                new Keyframe(0.250f, 0, hit5_10),
                new Keyframe(0.275f, 0, hit5_11),
                new Keyframe(0.300f, 0, hit5_12),
                new Keyframe(0.325f, 0, hit5_13),
                new Keyframe(0.350f, 0, hit5_14),
                new Keyframe(0.375f, 0, hit5_15),
                new Keyframe(0.400f, 0, hit5_16),
                new Keyframe(0.425f, 0, hit5_17),
                new Keyframe(0.450f, 0, hit5_18),
                new Keyframe(0.475f, 0, hit5_19),
                new Keyframe(0.500f, 0, hit5_20),
                new Keyframe(0.525f, 0, hit5_21),
                new Keyframe(0.550f, 0, hit5_22),
                new Keyframe(0.575f, 0, hit5_23),
                new Keyframe(0.600f, 0, hit5_24),
                new Keyframe(0.625f, 0, hit5_25),
                new Keyframe(0.650f, 0, hit5_26),
                new Keyframe(0.675f, 0, hit5_27),
                new Keyframe(0.700f, 0, hit5_28),
                new Keyframe(0.725f, 0, hit5_29),
                new Keyframe(0.750f, 0, hit5_30),
                new Keyframe(0.775f, 0, hit5_31),
                new Keyframe(0.800f, 0, hit5_32),
                new Keyframe(0.825f, 0, hit5_33),
                new Keyframe(0.850f, 0, hit5_34),
                new Keyframe(0.875f, 0, hit5_35),
                new Keyframe(0.900f, 0, hit5_36),
                new Keyframe(0.925f, 0, hit5_37),
                new Keyframe(0.950f, 0, hit5_38),
                new Keyframe(0.975f, 0, hit5_39),
                new Keyframe(1.000f, 0, hit5_40),
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
        if (!Float.isNaN(pose.rlYaw())) {
            m.rightLeg.yaw = pose.rlYaw();
        }
        if (!Float.isNaN(pose.rlPitch())) {
            m.rightLeg.pitch = pose.rlPitch();
        }
        if (!Float.isNaN(pose.rlRoll())) {
            m.rightLeg.roll = pose.rlRoll();
        }
        if (!Float.isNaN(pose.llYaw())) {
            m.leftLeg.yaw = pose.llYaw();
        }
        if (!Float.isNaN(pose.llPitch())) {
            m.leftLeg.pitch = pose.llPitch();
        }
        if (!Float.isNaN(pose.llRoll())) {
            m.leftLeg.roll = pose.llRoll();
        }
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
            case E_IN_CUBIC -> {
                // Тяжёлый свинг: разгон от замаха к пику скорости (момент урона).
                return t * t * t;
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
