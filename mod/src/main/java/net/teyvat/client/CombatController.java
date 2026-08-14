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
    private static final int RECOVERY_TICKS = 6;
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
    /** Хват меча в беге: рука вытянута вперёд, клинок горизонтально.
     *  Углы ЗЕРКАЛЬНЫ по X (правая рука модели — на стороне −X). */
    private static final float RUN_ARM_PITCH = (float) Math.toRadians(-60f);
    private static final float RUN_ARM_YAW = (float) Math.toRadians(-6f);
    private static final float RUN_ARM_ROLL = (float) Math.toRadians(-8f);
    /** Боевая стойка в покое: клинок горизонтально слева (ready pose),
     *  зеркально по X. */
    private static final float READY_RYAW = (float) Math.toRadians(-22f);
    private static final float READY_RPITCH = (float) Math.toRadians(-46f);
    private static final float READY_RROLL = (float) Math.toRadians(-22f);
    private static final float READY_LPITCH = (float) Math.toRadians(-15f);
    private static final float READY_BYAW = (float) Math.toRadians(-8f);
    private static final float READY_BPITCH = (float) Math.toRadians(3f);

    /** Поворот лезвия в ЛОКАЛЬНОМ пространстве предмета (вокруг оси Y — оси
     *  клинка): разворачивается ТОЛЬКО плоскость лезвия (как кисть в запястье),
     *  направление клинка при этом НЕ меняется — лезвие всегда идёт ПО ПРЯМОЙ
     *  «плечо→кисть» (продолжение руки, см. BLADE_GRIP_C). «Физика меча»:
     *  в замахе лезвие слегка отстаёт от
     *  руки (trail), к моменту урона (~0.40-0.45) плоскость выравнивается,
     *  на хвосте перехлёстывает (whip) и мягко возвращается к финалу удара.
     *  Стыки между ударами совпадают (конец удара N = начало N+1); после
     *  комбо лезвие плавно уходит в нейтраль. Работает и в 1-м, и в 3-м лице
     *  (миксин HeldItemFeatureRenderer), трейл разреза и серпы SWEEP_ATTACK
     *  считают ту же цепочку. */
    private static final float[][] BLADE_DEG = {
            {0f, -24f, 0f, 20f, -6f, 0f},     // hit1: плоскость ровно на ударе
            {0f, 12f, 38f, 26f, 14f, 8f},     // hit2: апперкот — лёгкий разворот
            {0f, -18f, -38f, -16f, -4f, 0f},  // hit3: рубящий по диагонали
            {0f, 24f, 0f, -20f, 6f, 0f},      // hit4: зеркало hit1
            {0f, -30f, 0f, 26f, 8f, 0f},      // hit5: мощный свинг — ведущее ребро
    };
    /** Моменты кадров BLADE_DEG (доли клипа). */
    private static final float[] BLADE_T = {0f, 0.12f, 0.42f, 0.60f, 0.82f, 1f};
    private static final float IDLE_BLADE_DEG = 0f;
    /** Последний угол лезвия в бою (для плавного возврата в нейтраль после комбо). */
    private static float lastBladeDeg = IDLE_BLADE_DEG;

    /** Грип-корректировка: доворот клинка в системе руки (вокруг точки хвата),
     *  чтобы лезвие было направлено ТОЧНО по прямой «плечо→кисть» — рука и меч
     *  выглядят одной прямой линией (~180°) при любом сгибе локтя. Без неё
     *  ванильная цепочка (HeldItemFeatureRenderer + display handheld) ведёт
     *  лезвие вдоль предплечья, и на ударах получается тупой угол (~152°)
     *  между рукой и мечом. Числа посчитаны в scripts/blade_geo.py
     *  (JOML-проверка) и совпадают с цепочкой трейла. */
    private static final Quaternionf BLADE_GRIP_C = new Quaternionf()
            .rotationAxis((float) 0.100668672, -0.882085226f, 0f, 0.471089858f);

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
        // Полумесяцы-разрезы: серпы рисуются ТОЧНО по траектории клинка (та же
        // цепочка, что у трейла — bladeTipWorld), поэтому дуга визуала удара
        // совпадает с направлением движения меча (с поворотом лезвия).
        int crescents = 3;
        float rootYaw = currentRootYawRad();
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        for (int i = 0; i < crescents; i++) {
            float t0 = 0.34f + 0.44f * (float) i / crescents;
            float t1 = 0.34f + 0.44f * (float) (i + 1) / crescents;
            Vec3d a = bladeTipWorld(computePose(t0), rootYaw, player.getBodyYaw(), slashPos);
            Vec3d b = bladeTipWorld(computePose(t1), rootYaw, player.getBodyYaw(), slashPos);
            Vec3d delta = b.subtract(a);
            double len = delta.length();
            if (len < 1.0e-4) {
                continue;
            }
            Vec3d dir = delta.multiply(1.0 / len);
            world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                    a.x, a.y, a.z,
                    dir.x, dir.y, dir.z);
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

    /** Идёт ли сейчас комбо (для миксина предмета: поворот клинка нужен даже
     *  для кастомных мечей вне тега ItemTags.SWORDS — комбо работает с любым
     *  предметом в руке). */
    public static boolean comboActive() {
        return comboStep >= 0;
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

    /** Текущий поворот лезвия в локальном пространстве предмета (item-local,
     *  вокруг оси Y — оси клинка; направление клинка не меняется). Угол
     *  берётся из кейфрейм-кривых BLADE_DEG — лезвие слегка отстаёт в замахе,
     *  выравнивается к моменту урона и перехлёстывает на хвосте; после комбо
     *  (восстановление/выход) плавно возвращается в нейтраль. */
    public static Quaternionf currentBladeRotation() {
        float deg = IDLE_BLADE_DEG;
        if (comboStep >= 0) {
            deg = sampleBladeDeg(comboStep, progress());
            if (recoveryTicks > 0) {
                // Восстановление после удара: лезвие возвращается в нейтраль.
                float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
                deg = MathHelper.lerp(easeOutCubic(r), deg, IDLE_BLADE_DEG);
            }
            lastBladeDeg = deg;
        } else if (exitBlendTicks > 0) {
            // Выход из комбо: доворот последнего угла лезвия к нейтрали.
            float w = 1f - exitBlendTicks / (float) EXIT_BLEND_TICKS;
            deg = MathHelper.lerp(w, lastBladeDeg, IDLE_BLADE_DEG);
        }
        return new Quaternionf().rotationY((float) Math.toRadians(deg));
    }

    /** Сэмплировать угол клинка удара (линейно между ключевыми кадрами —
     *  плавная последовательность «задержка -> разворот -> перехлёст ->
     *  фиксация», стык с предыдущим ударом совпадает по углу). */
    private static float sampleBladeDeg(int step, float p) {
        float[] kf = BLADE_DEG[step];
        if (p <= BLADE_T[0]) {
            return kf[0];
        }
        for (int i = 0; i < BLADE_T.length - 1; i++) {
            if (p <= BLADE_T[i + 1]) {
                float span = BLADE_T[i + 1] - BLADE_T[i];
                float u = span <= 0f ? 1f : (p - BLADE_T[i]) / span;
                return MathHelper.lerp(u, kf[i], kf[i + 1]);
            }
        }
        return kf[kf.length - 1];
    }

    /** Тот же поворот, но в системе руки после display-трансформации предмета:
     *  R_frame = D·C·Q·D⁻¹, где D = display rotation (0,-90,55), C =
     *  BLADE_GRIP_C (клинок — прямое продолжение руки, ~180°), Q = поворот
     *  плоскости лезвия.
     *  Применяется миксином HeldItemFeatureRenderer перед рендером предмета —
     *  лезвие доворачивается в локальных осях меча и в 1-м, и в 3-м лице. */
    public static Quaternionf currentBladeFrameRotation() {
        Quaternionf d = new Quaternionf().rotationXYZ(0f, (float) Math.toRadians(-90f), (float) Math.toRadians(55f));
        Quaternionf dc = new Quaternionf(d).conjugate();
        return new Quaternionf(d).mul(BLADE_GRIP_C).mul(currentBladeRotation()).mul(dc).normalize();
    }

    /** Клинок блокируется на время удара: клавиши движения не работают,
     *  герой двигается только микро-рывком по направлению атаки. В фазе
     *  восстановления (после удара) движение уже разблокировано — персонаж
     *  не «замирает» на месте. */
    public static boolean lockInputDuringAttack() {
        return comboStep >= 0 && recoveryTicks == 0;
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
        // Грип-корректировка и поворот плоскости лезвия (item-local, те же,
        // что накладывает миксин HeldItemFeatureRenderer): разрез рисуется
        // точно по видимому мечу — клинок идёт по прямой «плечо→кисть».
        m.rotate(BLADE_GRIP_C);
        m.rotate(currentBladeRotation());
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
    private static final Pose hit1_01 = new Pose(0.165806f, -0.453786f, -0.026180f, 0.000000f, -0.296706f, -0.047997f, 0.027925f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.052360f, 0.000000f, 0.000000f, 0.034907f, 0.000000f);
    private static final Pose hit1_02 = new Pose(0.331613f, -0.558505f, -0.052360f, 0.000000f, -0.418879f, -0.095993f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.104720f, 0.000000f, 0.000000f, 0.069813f, 0.000000f);
    private static final Pose hit1_03 = new Pose(0.497419f, -0.663225f, -0.078540f, 0.000000f, -0.541052f, -0.143990f, 0.083776f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.157080f, 0.000000f, 0.000000f, 0.104720f, 0.000000f);
    private static final Pose hit1_04 = new Pose(0.663225f, -0.767945f, -0.104720f, 0.000000f, -0.663225f, -0.191986f, 0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.209440f, 0.000000f, 0.000000f, 0.139626f, 0.000000f);
    private static final Pose hit1_05 = new Pose(0.668679f, -0.772308f, -0.104447f, 0.000000f, -0.670043f, -0.193622f, 0.112628f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.212167f, 0.000000f, 0.000000f, 0.141535f, 0.000000f);
    private static final Pose hit1_06 = new Pose(0.706858f, -0.802851f, -0.102538f, 0.000000f, -0.717767f, -0.205076f, 0.119119f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.231256f, 0.000000f, 0.000000f, 0.154898f, 0.000000f);
    private static final Pose hit1_07 = new Pose(0.810487f, -0.885755f, -0.097357f, 0.000000f, -0.847303f, -0.236165f, 0.136736f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.283071f, 0.000000f, 0.000000f, 0.191168f, 0.000000f);
    private static final Pose hit1_08 = new Pose(1.012291f, -1.047198f, -0.087266f, 0.000000f, -1.099557f, -0.296706f, 0.171042f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.383972f, 0.000000f, 0.000000f, 0.261799f, 0.000000f);
    private static final Pose hit1_09 = new Pose(1.010723f, -1.047027f, -0.086585f, 0.000000f, -1.098773f, -0.295786f, 0.170776f, 0.000102f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.382541f, 0.000000f, 0.000000f, 0.261118f, 0.000000f);
    private static final Pose hit1_10 = new Pose(0.999746f, -1.045834f, -0.081812f, 0.000000f, -1.093285f, -0.289343f, 0.168915f, 0.000818f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.372519f, 0.000000f, 0.000000f, 0.256345f, 0.000000f);
    private static final Pose hit1_11 = new Pose(0.969953f, -1.042596f, -0.068859f, 0.000000f, -1.078388f, -0.271855f, 0.163863f, 0.002761f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.345316f, 0.000000f, 0.000000f, 0.243392f, 0.000000f);
    private static final Pose hit1_12 = new Pose(0.911935f, -1.036289f, -0.043633f, 0.000000f, -1.049379f, -0.237801f, 0.154025f, 0.006545f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.292343f, 0.000000f, 0.000000f, 0.218166f, 0.000000f);
    private static final Pose hit1_13 = new Pose(0.816282f, -1.025892f, -0.002045f, 0.000000f, -1.001553f, -0.181657f, 0.137806f, 0.012783f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.205008f, 0.000000f, 0.000000f, 0.176578f, 0.000000f);
    private static final Pose hit1_14 = new Pose(0.673588f, -1.010382f, 0.059996f, 0.000000f, -0.930206f, -0.097902f, 0.113610f, 0.022089f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.074722f, 0.000000f, 0.000000f, 0.114537f, 0.000000f);
    private static final Pose hit1_15 = new Pose(0.474443f, -0.988736f, 0.146580f, 0.000000f, -0.830634f, 0.018987f, 0.079842f, 0.035077f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.107106f, 0.000000f, 0.000000f, 0.027953f, 0.000000f);
    private static final Pose hit1_16 = new Pose(0.209440f, -0.959931f, 0.261799f, 0.000000f, -0.698132f, 0.174533f, 0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit1_17 = new Pose(0.205723f, -0.960052f, 0.261476f, 0.000000f, -0.696839f, 0.174210f, 0.034381f, 0.052328f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.350116f, 0.000000f, 0.000000f, -0.087792f, 0.000000f);
    private static final Pose hit1_18 = new Pose(0.179704f, -0.960901f, 0.259214f, 0.000000f, -0.687789f, 0.171947f, 0.030705f, 0.052101f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.357469f, 0.000000f, 0.000000f, -0.091468f, 0.000000f);
    private static final Pose hit1_19 = new Pose(0.109083f, -0.963204f, 0.253073f, 0.000000f, -0.663225f, 0.165806f, 0.020726f, 0.051487f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.377427f, 0.000000f, 0.000000f, -0.101447f, 0.000000f);
    private static final Pose hit1_20 = new Pose(-0.028442f, -0.967688f, 0.241114f, 0.000000f, -0.615390f, 0.153848f, 0.001293f, 0.050291f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.416293f, 0.000000f, 0.000000f, -0.120880f, 0.000000f);
    private static final Pose hit1_21 = new Pose(-0.255174f, -0.975082f, 0.221398f, 0.000000f, -0.536527f, 0.134132f, -0.030745f, 0.048320f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.480370f, 0.000000f, 0.000000f, -0.152918f, 0.000000f);
    private static final Pose hit1_22 = new Pose(-0.593412f, -0.986111f, 0.191986f, 0.000000f, -0.418879f, 0.104720f, -0.078540f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575959f, 0.000000f, 0.000000f, -0.200713f, 0.000000f);
    private static final Pose hit1_23 = new Pose(-0.824607f, -0.983634f, 0.121802f, 0.000000f, -0.356952f, 0.067563f, -0.069457f, 0.041250f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.572656f, 0.000000f, 0.000000f, -0.206493f, 0.000000f);
    private static final Pose hit1_24 = new Pose(-0.968217f, -0.982095f, 0.078206f, 0.000000f, -0.318485f, 0.044483f, -0.063815f, 0.038686f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.570604f, 0.000000f, 0.000000f, -0.210083f, 0.000000f);
    private static final Pose hit1_25 = new Pose(-1.045096f, -0.981272f, 0.054868f, 0.000000f, -0.297892f, 0.032128f, -0.060795f, 0.037313f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.569506f, 0.000000f, 0.000000f, -0.212005f, 0.000000f);
    private static final Pose hit1_26 = new Pose(-1.076098f, -0.980939f, 0.045456f, 0.000000f, -0.289588f, 0.027145f, -0.059577f, 0.036759f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.569063f, 0.000000f, 0.000000f, -0.212780f, 0.000000f);
    private static final Pose hit1_27 = new Pose(-1.082076f, -0.980875f, 0.043642f, 0.000000f, -0.287987f, 0.026184f, -0.059342f, 0.036652f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.568978f, 0.000000f, 0.000000f, -0.212929f, 0.000000f);
    private static final Pose hit1_28 = new Pose(-1.038771f, -0.979431f, 0.025578f, 0.000000f, -0.277146f, 0.015347f, -0.049230f, 0.035930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.564644f, 0.000000f, 0.000000f, -0.211486f, 0.000000f);
    private static final Pose hit1_29 = new Pose(-1.002529f, -0.978223f, 0.010477f, 0.000000f, -0.268086f, 0.006286f, -0.040774f, 0.035326f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.561020f, 0.000000f, 0.000000f, -0.210278f, 0.000000f);
    private static final Pose hit1_30 = new Pose(-0.982287f, -0.977548f, 0.002043f, 0.000000f, -0.263025f, 0.001226f, -0.036050f, 0.034988f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558996f, 0.000000f, 0.000000f, -0.209603f, 0.000000f);
    private static final Pose hit1_31 = new Pose(-0.973847f, -0.977266f, -0.001474f, 0.000000f, -0.260915f, -0.000884f, -0.034081f, 0.034848f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558152f, 0.000000f, 0.000000f, -0.209322f, 0.000000f);
    private static final Pose hit1_32 = new Pose(-0.973015f, -0.977239f, -0.001821f, 0.000000f, -0.260707f, -0.001092f, -0.033887f, 0.034834f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558068f, 0.000000f, 0.000000f, -0.209294f, 0.000000f);
    private static final Pose hit1_33 = new Pose(-0.975593f, -0.977325f, -0.000747f, 0.000000f, -0.261351f, -0.000448f, -0.034489f, 0.034877f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558326f, 0.000000f, 0.000000f, -0.209380f, 0.000000f);
    private static final Pose hit1_34 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_35 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_36 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_37 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_38 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_39 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_40 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);

    private static final Pose hit2_00 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit2_01 = new Pose(-0.933751f, -0.919207f, -0.021817f, 0.000000f, -0.316341f, -0.032725f, -0.039270f, 0.031998f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.493056f, 0.000000f, 0.000000f, -0.173078f, 0.000000f);
    private static final Pose hit2_02 = new Pose(-0.890118f, -0.861029f, -0.043633f, 0.000000f, -0.370882f, -0.065450f, -0.043633f, 0.029089f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.427606f, 0.000000f, 0.000000f, -0.136717f, 0.000000f);
    private static final Pose hit2_03 = new Pose(-0.846485f, -0.802851f, -0.065450f, 0.000000f, -0.425424f, -0.098175f, -0.047997f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.362156f, 0.000000f, 0.000000f, -0.100356f, 0.000000f);
    private static final Pose hit2_04 = new Pose(-0.802851f, -0.744674f, -0.087266f, 0.000000f, -0.479966f, -0.130900f, -0.052360f, 0.023271f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.296706f, 0.000000f, 0.000000f, -0.063995f, 0.000000f);
    private static final Pose hit2_05 = new Pose(-0.767942f, -0.698110f, -0.104710f, 0.000000f, -0.523619f, -0.157083f, -0.055852f, 0.020943f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.244321f, 0.000000f, 0.000000f, -0.034893f, 0.000000f);
    private static final Pose hit2_06 = new Pose(-0.767399f, -0.693496f, -0.102538f, 0.000000f, -0.527962f, -0.157898f, -0.056123f, 0.020835f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.238892f, 0.000000f, 0.000000f, -0.031907f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-0.764584f, -0.669564f, -0.091276f, 0.000000f, -0.550486f, -0.162121f, -0.057531f, 0.020272f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.210737f, 0.000000f, 0.000000f, -0.016422f, 0.000000f);
    private static final Pose hit2_08 = new Pose(-0.757602f, -0.610219f, -0.063349f, 0.000000f, -0.606340f, -0.172594f, -0.061022f, 0.018875f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.140919f, 0.000000f, 0.000000f, 0.021978f, 0.000000f);
    private static final Pose hit2_09 = new Pose(-0.744560f, -0.499362f, -0.011181f, 0.000000f, -0.710676f, -0.192157f, -0.067543f, 0.016267f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.010499f, 0.000000f, 0.000000f, 0.093709f, 0.000000f);
    private static final Pose hit2_10 = new Pose(-0.733062f, -0.401551f, 0.034805f, 0.000000f, -0.802887f, -0.209428f, -0.073307f, 0.013974f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.104762f, 0.000000f, 0.000000f, 0.157101f, 0.000000f);
    private static final Pose hit2_11 = new Pose(-0.734065f, -0.406815f, 0.030544f, 0.000000f, -0.804391f, -0.208926f, -0.073432f, 0.014450f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.106516f, 0.000000f, 0.000000f, 0.157978f, 0.000000f);
    private static final Pose hit2_12 = new Pose(-0.738210f, -0.428575f, 0.012928f, 0.000000f, -0.810608f, -0.206854f, -0.073950f, 0.016419f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.113770f, 0.000000f, 0.000000f, 0.161605f, 0.000000f);
    private static final Pose hit2_13 = new Pose(-0.747741f, -0.478617f, -0.027581f, 0.000000f, -0.824906f, -0.202088f, -0.075142f, 0.020947f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.130450f, 0.000000f, 0.000000f, 0.169945f, 0.000000f);
    private static final Pose hit2_14 = new Pose(-0.764904f, -0.568722f, -0.100524f, 0.000000f, -0.850650f, -0.193506f, -0.077287f, 0.029099f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.160485f, 0.000000f, 0.000000f, 0.184962f, 0.000000f);
    private static final Pose hit2_15 = new Pose(-0.791943f, -0.710676f, -0.215439f, 0.000000f, -0.891209f, -0.179987f, -0.080667f, 0.041942f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.207803f, 0.000000f, 0.000000f, 0.208621f, 0.000000f);
    private static final Pose hit2_16 = new Pose(-0.831102f, -0.916262f, -0.381866f, 0.000000f, -0.949948f, -0.160407f, -0.085562f, 0.060543f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.276332f, 0.000000f, 0.000000f, 0.242886f, 0.000000f);
    private static final Pose hit2_17 = new Pose(-0.872681f, -1.134480f, -0.558499f, 0.000000f, -1.012285f, -0.139620f, -0.090758f, 0.080285f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349056f, 0.000000f, 0.000000f, 0.279247f, 0.000000f);
    private static final Pose hit2_18 = new Pose(-0.876096f, -1.137896f, -0.557218f, 0.000000f, -1.011004f, -0.138339f, -0.090972f, 0.080199f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.346921f, 0.000000f, 0.000000f, 0.277966f, 0.000000f);
    private static final Pose hit2_19 = new Pose(-0.893812f, -1.155611f, -0.550575f, 0.000000f, -1.004361f, -0.131696f, -0.092079f, 0.079756f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.335849f, 0.000000f, 0.000000f, 0.271322f, 0.000000f);
    private static final Pose hit2_20 = new Pose(-0.937743f, -1.199542f, -0.534101f, 0.000000f, -0.987887f, -0.115222f, -0.094825f, 0.078658f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.308392f, 0.000000f, 0.000000f, 0.254848f, 0.000000f);
    private static final Pose hit2_21 = new Pose(-1.019806f, -1.281606f, -0.503327f, 0.000000f, -0.957113f, -0.084448f, -0.099953f, 0.076607f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.257102f, 0.000000f, 0.000000f, 0.224075f, 0.000000f);
    private static final Pose hit2_22 = new Pose(-1.151917f, -1.413717f, -0.453786f, 0.000000f, -0.907571f, -0.034907f, -0.108210f, 0.073304f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit2_23 = new Pose(-1.166623f, -1.163714f, -0.689082f, 0.000000f, -0.848747f, -0.005495f, -0.112622f, 0.065951f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.086297f, 0.000000f, 0.000000f, 0.123062f, 0.000000f);
    private static final Pose hit2_24 = new Pose(-1.176481f, -0.996131f, -0.846808f, 0.000000f, -0.809316f, 0.014221f, -0.115580f, 0.061022f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.027150f, 0.000000f, 0.000000f, 0.088559f, 0.000000f);
    private static final Pose hit2_25 = new Pose(-1.182461f, -0.894481f, -0.942478f, 0.000000f, -0.785398f, 0.026180f, -0.117373f, 0.058032f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.008727f, 0.000000f, 0.000000f, 0.067632f, 0.000000f);
    private static final Pose hit2_26 = new Pose(-1.185531f, -0.842283f, -0.991606f, 0.000000f, -0.773116f, 0.032321f, -0.118295f, 0.056497f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.027150f, 0.000000f, 0.000000f, 0.056885f, 0.000000f);
    private static final Pose hit2_27 = new Pose(-1.186662f, -0.823052f, -1.009705f, 0.000000f, -0.768591f, 0.034583f, -0.118634f, 0.055931f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.033937f, 0.000000f, 0.000000f, 0.052925f, 0.000000f);
    private static final Pose hit2_28 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.767945f, 0.034907f, -0.118682f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.034907f, 0.000000f, 0.000000f, 0.052360f, 0.000000f);
    private static final Pose hit2_29 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.734800f, 0.043193f, -0.120340f, 0.050879f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.101196f, 0.000000f, 0.000000f, 0.019215f, 0.000000f);
    private static final Pose hit2_30 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.713305f, 0.048567f, -0.121414f, 0.047654f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.144187f, 0.000000f, 0.000000f, -0.002280f, 0.000000f);
    private static final Pose hit2_31 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.701101f, 0.051618f, -0.122025f, 0.045824f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.168594f, 0.000000f, 0.000000f, -0.014484f, 0.000000f);
    private static final Pose hit2_32 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.695833f, 0.052934f, -0.122288f, 0.045034f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.179130f, 0.000000f, 0.000000f, -0.019752f, 0.000000f);
    private static final Pose hit2_33 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.695144f, 0.053107f, -0.122322f, 0.044930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.180508f, 0.000000f, 0.000000f, -0.020441f, 0.000000f);
    private static final Pose hit2_34 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.696677f, 0.052723f, -0.122246f, 0.045160f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.177442f, 0.000000f, 0.000000f, -0.018908f, 0.000000f);
    private static final Pose hit2_35 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.698076f, 0.052374f, -0.122176f, 0.045370f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174645f, 0.000000f, 0.000000f, -0.017509f, 0.000000f);
    private static final Pose hit2_36 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.696963f, 0.052360f, -0.122173f, 0.044677f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.176871f, 0.000000f, 0.000000f, -0.019792f, 0.000000f);
    private static final Pose hit2_37 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.692745f, 0.052360f, -0.122173f, 0.042146f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.185307f, 0.000000f, 0.000000f, -0.028227f, 0.000000f);
    private static final Pose hit2_38 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.687146f, 0.052360f, -0.122173f, 0.038787f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.196503f, 0.000000f, 0.000000f, -0.039424f, 0.000000f);
    private static final Pose hit2_39 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.682482f, 0.052360f, -0.122173f, 0.035989f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.205833f, 0.000000f, 0.000000f, -0.048753f, 0.000000f);
    private static final Pose hit2_40 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.680678f, 0.052360f, -0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.209440f, 0.000000f, 0.000000f, -0.052360f, 0.000000f);

    private static final Pose hit3_00 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.680678f, 0.052360f, -0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.209440f, 0.000000f, 0.000000f, -0.052360f, 0.000000f);
    private static final Pose hit3_01 = new Pose(-1.085013f, -1.085740f, -0.837758f, 0.000000f, -0.720676f, 0.026907f, -0.125809f, 0.031270f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.187623f, 0.000000f, 0.000000f, -0.041452f, 0.000000f);
    private static final Pose hit3_02 = new Pose(-0.983202f, -1.351176f, -0.663225f, 0.000000f, -0.760673f, 0.001454f, -0.129445f, 0.027634f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.165806f, 0.000000f, 0.000000f, -0.030543f, 0.000000f);
    private static final Pose hit3_03 = new Pose(-0.881391f, -1.616611f, -0.488692f, 0.000000f, -0.800670f, -0.023998f, -0.133081f, 0.023998f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.143990f, 0.000000f, 0.000000f, -0.019635f, 0.000000f);
    private static final Pose hit3_04 = new Pose(-0.779580f, -1.882047f, -0.314159f, 0.000000f, -0.840667f, -0.049451f, -0.136717f, 0.020362f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122173f, 0.000000f, 0.000000f, -0.008727f, 0.000000f);
    private static final Pose hit3_05 = new Pose(-0.698106f, -2.094433f, -0.174530f, 0.000000f, -0.872682f, -0.069818f, -0.139627f, 0.017452f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.104705f, 0.000000f, 0.000000f, 0.000008f, 0.000000f);
    private static final Pose hit3_06 = new Pose(-0.692678f, -2.102576f, -0.173988f, 0.000000f, -0.876483f, -0.070904f, -0.139790f, 0.017099f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.101447f, 0.000000f, 0.000000f, 0.001636f, 0.000000f);
    private static final Pose hit3_07 = new Pose(-0.664523f, -2.144808f, -0.171172f, 0.000000f, -0.896191f, -0.076535f, -0.140635f, 0.015269f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.084555f, 0.000000f, 0.000000f, 0.010083f, 0.000000f);
    private static final Pose hit3_08 = new Pose(-0.594705f, -2.249535f, -0.164190f, 0.000000f, -0.945063f, -0.090499f, -0.142729f, 0.010731f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.042664f, 0.000000f, 0.000000f, 0.031028f, 0.000000f);
    private static final Pose hit3_09 = new Pose(-0.464285f, -2.445165f, -0.151148f, 0.000000f, -1.036357f, -0.116583f, -0.146642f, 0.002253f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.035588f, 0.000000f, 0.000000f, 0.070154f, 0.000000f);
    private static final Pose hit3_10 = new Pose(-0.349015f, -2.618277f, -0.139586f, 0.000000f, -1.117112f, -0.139667f, -0.150102f, -0.005238f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.104760f, 0.000000f, 0.000000f, 0.104740f, 0.000000f);
    private static final Pose hit3_11 = new Pose(-0.346901f, -2.630119f, -0.137894f, 0.000000f, -1.121341f, -0.141359f, -0.150272f, -0.005323f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.106452f, 0.000000f, 0.000000f, 0.105586f, 0.000000f);
    private static final Pose hit3_12 = new Pose(-0.338158f, -2.679080f, -0.130900f, 0.000000f, -1.138827f, -0.148353f, -0.150971f, -0.005672f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.113446f, 0.000000f, 0.000000f, 0.109083f, 0.000000f);
    private static final Pose hit3_13 = new Pose(-0.318052f, -2.791673f, -0.114815f, 0.000000f, -1.179039f, -0.164438f, -0.152579f, -0.006477f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.129531f, 0.000000f, 0.000000f, 0.117125f, 0.000000f);
    private static final Pose hit3_14 = new Pose(-0.281848f, -2.994411f, -0.085852f, 0.000000f, -1.251446f, -0.193400f, -0.155476f, -0.007925f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.158494f, 0.000000f, 0.000000f, 0.131607f, 0.000000f);
    private static final Pose hit3_15 = new Pose(-0.262375f, -3.098288f, -0.070503f, 0.000000f, -1.289933f, -0.208634f, -0.155929f, -0.008209f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.175453f, 0.000000f, 0.000000f, 0.140317f, 0.000000f);
    private static final Pose hit3_16 = new Pose(-0.272708f, -2.947425f, -0.082903f, 0.000000f, -1.261000f, -0.194168f, -0.135263f, 0.001091f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.191986f, 0.000000f, 0.000000f, 0.152716f, 0.000000f);
    private static final Pose hit3_17 = new Pose(-0.308607f, -2.423293f, -0.125982f, 0.000000f, -1.160482f, -0.143909f, -0.063464f, 0.033400f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.249425f, 0.000000f, 0.000000f, 0.195796f, 0.000000f);
    private static final Pose hit3_18 = new Pose(-0.349098f, -1.832424f, -0.174507f, 0.000000f, -1.047109f, -0.087222f, 0.017476f, 0.069814f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.314096f, 0.000000f, 0.000000f, 0.244308f, 0.000000f);
    private static final Pose hit3_19 = new Pose(-0.350429f, -1.825233f, -0.173442f, 0.000000f, -1.043380f, -0.085358f, 0.018408f, 0.069868f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.311432f, 0.000000f, 0.000000f, 0.242710f, 0.000000f);
    private static final Pose hit3_20 = new Pose(-0.355935f, -1.795501f, -0.169037f, 0.000000f, -1.027963f, -0.077649f, 0.022262f, 0.070088f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.300421f, 0.000000f, 0.000000f, 0.236103f, 0.000000f);
    private static final Pose hit3_21 = new Pose(-0.368597f, -1.727129f, -0.158908f, 0.000000f, -0.992511f, -0.059923f, 0.031125f, 0.070594f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.275098f, 0.000000f, 0.000000f, 0.220909f, 0.000000f);
    private static final Pose hit3_22 = new Pose(-0.391395f, -1.604017f, -0.140669f, 0.000000f, -0.928675f, -0.028005f, 0.047084f, 0.071506f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.229501f, 0.000000f, 0.000000f, 0.193551f, 0.000000f);
    private static final Pose hit3_23 = new Pose(-0.427312f, -1.410065f, -0.111936f, 0.000000f, -0.828108f, 0.022279f, 0.072226f, 0.072943f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.157666f, 0.000000f, 0.000000f, 0.150450f, 0.000000f);
    private static final Pose hit3_24 = new Pose(-0.682199f, -1.153736f, -0.099256f, 0.000000f, -0.682650f, 0.045834f, 0.092199f, 0.061284f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.041280f, 0.000000f, 0.000000f, 0.084989f, 0.000000f);
    private static final Pose hit3_25 = new Pose(-0.909502f, -0.961792f, -0.094205f, 0.000000f, -0.571524f, 0.055936f, 0.104827f, 0.050171f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.049641f, 0.000000f, 0.000000f, 0.034478f, 0.000000f);
    private static final Pose hit3_26 = new Pose(-1.061869f, -0.833126f, -0.090819f, 0.000000f, -0.497033f, 0.062708f, 0.113292f, 0.042722f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.110588f, 0.000000f, 0.000000f, 0.000618f, 0.000000f);
    private static final Pose hit3_27 = new Pose(-1.154289f, -0.755082f, -0.088765f, 0.000000f, -0.451850f, 0.066816f, 0.118426f, 0.038204f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.147556f, 0.000000f, 0.000000f, -0.019920f, 0.000000f);
    private static final Pose hit3_28 = new Pose(-1.201748f, -0.715006f, -0.087711f, 0.000000f, -0.428648f, 0.068925f, 0.121063f, 0.035884f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.166540f, 0.000000f, 0.000000f, -0.030466f, 0.000000f);
    private static final Pose hit3_29 = new Pose(-1.219233f, -0.700241f, -0.087322f, 0.000000f, -0.420100f, 0.069702f, 0.122034f, 0.035029f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.173534f, 0.000000f, 0.000000f, -0.034352f, 0.000000f);
    private static final Pose hit3_30 = new Pose(-1.221730f, -0.698132f, -0.087266f, 0.000000f, -0.418879f, 0.069813f, 0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, -0.034907f, 0.000000f);
    private static final Pose hit3_31 = new Pose(-0.844635f, -0.899249f, -0.087266f, 0.000000f, -0.362315f, 0.044673f, 0.128458f, 0.022337f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.237382f, 0.000000f, 0.000000f, -0.022337f, 0.000000f);
    private static final Pose hit3_32 = new Pose(-0.563253f, -1.049320f, -0.087266f, 0.000000f, -0.320107f, 0.025915f, 0.133148f, 0.012957f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.284279f, 0.000000f, 0.000000f, -0.012957f, 0.000000f);
    private static final Pose hit3_33 = new Pose(-0.364390f, -1.155380f, -0.087266f, 0.000000f, -0.290278f, 0.012657f, 0.136462f, 0.006329f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.317423f, 0.000000f, 0.000000f, -0.006329f, 0.000000f);
    private static final Pose hit3_34 = new Pose(-0.234852f, -1.224467f, -0.087266f, 0.000000f, -0.270847f, 0.004021f, 0.138621f, 0.002011f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.339013f, 0.000000f, 0.000000f, -0.002011f, 0.000000f);
    private static final Pose hit3_35 = new Pose(-0.161443f, -1.263618f, -0.087266f, 0.000000f, -0.259836f, -0.000873f, 0.139845f, -0.000436f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.351248f, 0.000000f, 0.000000f, 0.000436f, 0.000000f);
    private static final Pose hit3_36 = new Pose(-0.130970f, -1.279871f, -0.087266f, 0.000000f, -0.255265f, -0.002904f, 0.140352f, -0.001452f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.356326f, 0.000000f, 0.000000f, 0.001452f, 0.000000f);
    private static final Pose hit3_37 = new Pose(-0.130236f, -1.280262f, -0.087266f, 0.000000f, -0.255155f, -0.002953f, 0.140365f, -0.001477f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.356449f, 0.000000f, 0.000000f, 0.001477f, 0.000000f);
    private static final Pose hit3_38 = new Pose(-0.146049f, -1.271828f, -0.087266f, 0.000000f, -0.257527f, -0.001899f, 0.140101f, -0.000949f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.353813f, 0.000000f, 0.000000f, 0.000949f, 0.000000f);
    private static final Pose hit3_39 = new Pose(-0.165213f, -1.261608f, -0.087266f, 0.000000f, -0.260401f, -0.000621f, 0.139782f, -0.000311f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.350619f, 0.000000f, 0.000000f, 0.000311f, 0.000000f);
    private static final Pose hit3_40 = new Pose(-0.174533f, -1.256637f, -0.087266f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit4_00 = new Pose(-0.174533f, -1.256637f, -0.087266f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(-0.239983f, -1.442078f, -0.076358f, 0.000000f, -0.365428f, -0.054542f, 0.147808f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.305433f, 0.000000f, 0.000000f, 0.016362f, 0.000000f);
    private static final Pose hit4_02 = new Pose(-0.305433f, -1.627520f, -0.065450f, 0.000000f, -0.469057f, -0.109083f, 0.155989f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.032725f, 0.000000f);
    private static final Pose hit4_03 = new Pose(-0.370882f, -1.812961f, -0.054542f, 0.000000f, -0.572686f, -0.163625f, 0.164170f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.218166f, 0.000000f, 0.000000f, 0.049087f, 0.000000f);
    private static final Pose hit4_04 = new Pose(-0.379448f, -1.881724f, -0.053006f, 0.000000f, -0.603755f, -0.176472f, 0.167422f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.201682f, 0.000000f, 0.000000f, 0.056238f, 0.000000f);
    private static final Pose hit4_05 = new Pose(-0.332431f, -2.210841f, -0.059723f, 0.000000f, -0.711222f, -0.196622f, 0.184214f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.121082f, 0.000000f, 0.000000f, 0.096539f, 0.000000f);
    private static final Pose hit4_06 = new Pose(-0.261395f, -2.707119f, -0.069005f, 0.000000f, -0.873634f, -0.226974f, 0.209520f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.001293f, 0.000000f, 0.000000f, 0.157322f, 0.000000f);
    private static final Pose hit4_07 = new Pose(-0.244477f, -2.784941f, -0.035169f, 0.000000f, -0.914237f, -0.230357f, 0.212904f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.055430f, 0.000000f, 0.000000f, 0.167473f, 0.000000f);
    private static final Pose hit4_08 = new Pose(-0.174533f, -3.106686f, 0.104720f, 0.000000f, -1.082104f, -0.244346f, 0.226893f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.279253f, 0.000000f, 0.000000f, 0.209440f, 0.000000f);
    private static final Pose hit4_09 = new Pose(-0.174584f, -3.103536f, 0.104182f, 0.000000f, -1.081541f, -0.243731f, 0.226611f, 0.000077f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.278331f, 0.000000f, 0.000000f, 0.209004f, 0.000000f);
    private static final Pose hit4_10 = new Pose(-0.174943f, -3.081485f, 0.100417f, 0.000000f, -1.077597f, -0.239429f, 0.224639f, 0.000615f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.271877f, 0.000000f, 0.000000f, 0.205956f, 0.000000f);
    private static final Pose hit4_11 = new Pose(-0.175916f, -3.021631f, 0.090198f, 0.000000f, -1.066891f, -0.227750f, 0.219286f, 0.002075f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.254359f, 0.000000f, 0.000000f, 0.197684f, 0.000000f);
    private static final Pose hit4_12 = new Pose(-0.177811f, -2.905075f, 0.070298f, 0.000000f, -1.046044f, -0.205007f, 0.208863f, 0.004917f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.220245f, 0.000000f, 0.000000f, 0.181575f, 0.000000f);
    private static final Pose hit4_13 = new Pose(-0.180936f, -2.712914f, 0.037490f, 0.000000f, -1.011673f, -0.167513f, 0.191677f, 0.009604f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.164002f, 0.000000f, 0.000000f, 0.155016f, 0.000000f);
    private static final Pose hit4_14 = new Pose(-0.185597f, -2.426249f, -0.011452f, 0.000000f, -0.960400f, -0.111578f, 0.166041f, 0.016596f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.080100f, 0.000000f, 0.000000f, 0.115395f, 0.000000f);
    private static final Pose hit4_15 = new Pose(-0.192102f, -2.026177f, -0.079757f, 0.000000f, -0.888842f, -0.033515f, 0.130262f, 0.026354f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.036994f, 0.000000f, 0.000000f, 0.060101f, 0.000000f);
    private static final Pose hit4_16 = new Pose(-0.200759f, -1.493797f, -0.170652f, 0.000000f, -0.793620f, 0.070364f, 0.082651f, 0.039339f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.192812f, 0.000000f, 0.000000f, -0.013480f, 0.000000f);
    private static final Pose hit4_17 = new Pose(-0.209396f, -0.959934f, -0.261796f, 0.000000f, -0.698116f, 0.174529f, 0.034900f, 0.052359f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349079f, 0.000000f, 0.000000f, -0.087273f, 0.000000f);
    private static final Pose hit4_18 = new Pose(-0.200002f, -0.960575f, -0.261156f, 0.000000f, -0.694700f, 0.173675f, 0.033405f, 0.052231f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.351854f, 0.000000f, 0.000000f, -0.088661f, 0.000000f);
    private static final Pose hit4_19 = new Pose(-0.151284f, -0.963896f, -0.257834f, 0.000000f, -0.676984f, 0.169246f, 0.025655f, 0.051567f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.366248f, 0.000000f, 0.000000f, -0.095858f, 0.000000f);
    private static final Pose hit4_20 = new Pose(-0.030474f, -0.972133f, -0.249597f, 0.000000f, -0.633053f, 0.158263f, 0.006435f, 0.049919f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.401942f, 0.000000f, 0.000000f, -0.113705f, 0.000000f);
    private static final Pose hit4_21 = new Pose(0.195200f, -0.987520f, -0.234210f, 0.000000f, -0.550990f, 0.137748f, -0.029468f, 0.046842f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.468618f, 0.000000f, 0.000000f, -0.147043f, 0.000000f);
    private static final Pose hit4_22 = new Pose(0.558505f, -1.012291f, -0.209440f, 0.000000f, -0.418879f, 0.104720f, -0.087266f, 0.041888f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575959f, 0.000000f, 0.000000f, -0.200713f, 0.000000f);
    private static final Pose hit4_23 = new Pose(0.705566f, -1.063762f, -0.180027f, 0.000000f, -0.363731f, 0.071631f, -0.112267f, 0.030123f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.531841f, 0.000000f, 0.000000f, -0.160271f, 0.000000f);
    private static final Pose hit4_24 = new Pose(0.804144f, -1.098265f, -0.160312f, 0.000000f, -0.326764f, 0.049451f, -0.129025f, 0.022237f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.502267f, 0.000000f, 0.000000f, -0.133162f, 0.000000f);
    private static final Pose hit4_25 = new Pose(0.863938f, -1.119192f, -0.148353f, 0.000000f, -0.304342f, 0.035997f, -0.139190f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.484329f, 0.000000f, 0.000000f, -0.116719f, 0.000000f);
    private static final Pose hit4_26 = new Pose(0.894643f, -1.129939f, -0.142212f, 0.000000f, -0.292827f, 0.029089f, -0.144410f, 0.014997f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.475117f, 0.000000f, 0.000000f, -0.108275f, 0.000000f);
    private static final Pose hit4_27 = new Pose(0.905955f, -1.133898f, -0.139950f, 0.000000f, -0.288585f, 0.026544f, -0.146333f, 0.014092f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.471724f, 0.000000f, 0.000000f, -0.105164f, 0.000000f);
    private static final Pose hit4_28 = new Pose(0.907571f, -1.134464f, -0.139626f, 0.000000f, -0.287979f, 0.026180f, -0.146608f, 0.013963f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.471239f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);
    private static final Pose hit4_29 = new Pose(0.973861f, -1.076461f, -0.139626f, 0.000000f, -0.275550f, 0.013751f, -0.156551f, 0.009820f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.396663f, 0.000000f, 0.000000f, -0.071575f, 0.000000f);
    private static final Pose hit4_30 = new Pose(1.016852f, -1.038843f, -0.139626f, 0.000000f, -0.267489f, 0.005690f, -0.163000f, 0.007133f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.348298f, 0.000000f, 0.000000f, -0.050079f, 0.000000f);
    private static final Pose hit4_31 = new Pose(1.041259f, -1.017488f, -0.139626f, 0.000000f, -0.262913f, 0.001114f, -0.166661f, 0.005607f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.320841f, 0.000000f, 0.000000f, -0.037876f, 0.000000f);
    private static final Pose hit4_32 = new Pose(1.051794f, -1.008269f, -0.139626f, 0.000000f, -0.260937f, -0.000862f, -0.168241f, 0.004949f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.308988f, 0.000000f, 0.000000f, -0.032608f, 0.000000f);
    private static final Pose hit4_33 = new Pose(1.053172f, -1.007063f, -0.139626f, 0.000000f, -0.260679f, -0.001120f, -0.168448f, 0.004863f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.307438f, 0.000000f, 0.000000f, -0.031919f, 0.000000f);
    private static final Pose hit4_34 = new Pose(1.050106f, -1.009746f, -0.139626f, 0.000000f, -0.261254f, -0.000545f, -0.167988f, 0.005054f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.310887f, 0.000000f, 0.000000f, -0.033452f, 0.000000f);
    private static final Pose hit4_35 = new Pose(1.047310f, -1.012193f, -0.139626f, 0.000000f, -0.261778f, -0.000021f, -0.167568f, 0.005229f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.314033f, 0.000000f, 0.000000f, -0.034850f, 0.000000f);
    private static final Pose hit4_36 = new Pose(1.026153f, -1.043858f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.168019f, 0.004885f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.310652f, 0.000000f, 0.000000f, -0.032568f, 0.000000f);
    private static final Pose hit4_37 = new Pose(0.950230f, -1.157743f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.169706f, 0.003620f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.297998f, 0.000000f, 0.000000f, -0.024132f, 0.000000f);
    private static final Pose hit4_38 = new Pose(0.849463f, -1.308893f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.171946f, 0.001940f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.281203f, 0.000000f, 0.000000f, -0.012936f, 0.000000f);
    private static final Pose hit4_39 = new Pose(0.765498f, -1.434840f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.173812f, 0.000541f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.267209f, 0.000000f, 0.000000f, -0.003607f, 0.000000f);
    private static final Pose hit4_40 = new Pose(0.733038f, -1.483530f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit5_00 = new Pose(0.733038f, -1.483530f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(0.789761f, -1.592613f, -0.130900f, 0.000000f, -0.370882f, -0.052360f, -0.182387f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.205076f, 0.000000f, 0.000000f, 0.034907f, 0.000000f);
    private static final Pose hit5_02 = new Pose(0.846485f, -1.701696f, -0.122173f, 0.000000f, -0.479966f, -0.104720f, -0.190241f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.148353f, 0.000000f, 0.000000f, 0.069813f, 0.000000f);
    private static final Pose hit5_03 = new Pose(0.903208f, -1.810779f, -0.113446f, 0.000000f, -0.589049f, -0.157080f, -0.198095f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.091630f, 0.000000f, 0.000000f, 0.104720f, 0.000000f);
    private static final Pose hit5_04 = new Pose(0.959931f, -1.919862f, -0.104720f, 0.000000f, -0.698132f, -0.209440f, -0.205949f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.034907f, 0.000000f, 0.000000f, 0.139626f, 0.000000f);
    private static final Pose hit5_05 = new Pose(0.959931f, -1.924064f, -0.104235f, 0.000000f, -0.701849f, -0.210086f, -0.206046f, 0.000242f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.032806f, 0.000000f, 0.000000f, 0.140354f, 0.000000f);
    private static final Pose hit5_06 = new Pose(0.959931f, -1.953476f, -0.100841f, 0.000000f, -0.727867f, -0.214611f, -0.206725f, 0.001939f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.018100f, 0.000000f, 0.000000f, 0.145444f, 0.000000f);
    private static final Pose hit5_07 = new Pose(0.959931f, -2.033309f, -0.091630f, 0.000000f, -0.798488f, -0.226893f, -0.208567f, 0.006545f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.021817f, 0.000000f, 0.000000f, 0.159261f, 0.000000f);
    private static final Pose hit5_08 = new Pose(0.959931f, -2.188772f, -0.073692f, 0.000000f, -0.936014f, -0.250810f, -0.212154f, 0.015514f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.099548f, 0.000000f, 0.000000f, 0.186168f, 0.000000f);
    private static final Pose hit5_09 = new Pose(0.959931f, -2.445077f, -0.044118f, 0.000000f, -1.162745f, -0.290242f, -0.218069f, 0.030301f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.227701f, 0.000000f, 0.000000f, 0.230529f, 0.000000f);
    private static final Pose hit5_10 = new Pose(0.959931f, -2.827433f, 0.000000f, 0.000000f, -1.500983f, -0.349066f, -0.226893f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.418879f, 0.000000f, 0.000000f, 0.296706f, 0.000000f);
    private static final Pose hit5_11 = new Pose(0.959011f, -2.824638f, 0.000341f, 0.000000f, -1.500063f, -0.348452f, -0.226593f, 0.052476f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.417822f, 0.000000f, 0.000000f, 0.296433f, 0.000000f);
    private static final Pose hit5_12 = new Pose(0.952568f, -2.805071f, 0.002727f, 0.000000f, -1.493620f, -0.344157f, -0.224493f, 0.053287f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.410425f, 0.000000f, 0.000000f, 0.294524f, 0.000000f);
    private static final Pose hit5_13 = new Pose(0.935081f, -2.751962f, 0.009204f, 0.000000f, -1.476133f, -0.332499f, -0.218793f, 0.055489f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.390347f, 0.000000f, 0.000000f, 0.289343f, 0.000000f);
    private static final Pose hit5_14 = new Pose(0.901026f, -2.648537f, 0.021817f, 0.000000f, -1.442078f, -0.309796f, -0.207694f, 0.059778f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.351248f, 0.000000f, 0.000000f, 0.279253f, 0.000000f);
    private static final Pose hit5_15 = new Pose(0.844883f, -2.478027f, 0.042611f, 0.000000f, -1.385935f, -0.272367f, -0.189395f, 0.066847f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.286786f, 0.000000f, 0.000000f, 0.262618f, 0.000000f);
    private static final Pose hit5_16 = new Pose(0.761127f, -2.223659f, 0.073631f, 0.000000f, -1.302179f, -0.216530f, -0.162097f, 0.077394f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.190623f, 0.000000f, 0.000000f, 0.237801f, 0.000000f);
    private static final Pose hit5_17 = new Pose(0.644238f, -1.868661f, 0.116923f, 0.000000f, -1.185290f, -0.138604f, -0.124000f, 0.092114f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.056416f, 0.000000f, 0.000000f, 0.203167f, 0.000000f);
    private static final Pose hit5_18 = new Pose(0.488692f, -1.396263f, 0.174533f, 0.000000f, -1.029744f, -0.034907f, -0.073304f, 0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122173f, 0.000000f, 0.000000f, 0.157080f, 0.000000f);
    private static final Pose hit5_19 = new Pose(0.485465f, -1.393533f, 0.175154f, 0.000000f, -1.027138f, -0.033417f, -0.072596f, 0.111838f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.124531f, 0.000000f, 0.000000f, 0.154969f, 0.000000f);
    private static final Pose hit5_20 = new Pose(0.462874f, -1.374417f, 0.179498f, 0.000000f, -1.008891f, -0.022990f, -0.067644f, 0.112793f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.141040f, 0.000000f, 0.000000f, 0.140198f, 0.000000f);
    private static final Pose hit5_21 = new Pose(0.401555f, -1.322532f, 0.191290f, 0.000000f, -0.959364f, 0.005311f, -0.054201f, 0.115388f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.185850f, 0.000000f, 0.000000f, 0.100105f, 0.000000f);
    private static final Pose hit5_22 = new Pose(0.282144f, -1.221492f, 0.214254f, 0.000000f, -0.862917f, 0.060423f, -0.028022f, 0.120440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.273112f, 0.000000f, 0.000000f, 0.022029f, 0.000000f);
    private static final Pose hit5_23 = new Pose(0.085278f, -1.054913f, 0.252112f, 0.000000f, -0.703910f, 0.151284f, 0.015137f, 0.128769f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.416975f, 0.000000f, 0.000000f, -0.106691f, 0.000000f);
    private static final Pose hit5_24 = new Pose(-0.249435f, -0.960593f, 0.223026f, 0.000000f, -0.559828f, 0.148684f, 0.068185f, 0.111513f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.544258f, 0.000000f, 0.000000f, -0.171938f, 0.000000f);
    private static final Pose hit5_25 = new Pose(-0.493090f, -0.916291f, 0.189800f, 0.000000f, -0.471226f, 0.126533f, 0.104180f, 0.094900f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.621785f, 0.000000f, 0.000000f, -0.199626f, 0.000000f);
    private static final Pose hit5_26 = new Pose(-0.637045f, -0.890118f, 0.170170f, 0.000000f, -0.418879f, 0.113446f, 0.125446f, 0.085085f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.667588f, 0.000000f, 0.000000f, -0.215984f, 0.000000f);
    private static final Pose hit5_27 = new Pose(-0.707536f, -0.877301f, 0.160557f, 0.000000f, -0.393246f, 0.107038f, 0.135859f, 0.080279f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.690017f, 0.000000f, 0.000000f, -0.223995f, 0.000000f);
    private static final Pose hit5_28 = new Pose(-0.730799f, -0.873072f, 0.157385f, 0.000000f, -0.384787f, 0.104923f, 0.139296f, 0.078692f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.697419f, 0.000000f, 0.000000f, -0.226638f, 0.000000f);
    private static final Pose hit5_29 = new Pose(-0.793202f, -0.833988f, 0.139890f, 0.000000f, -0.368932f, 0.092902f, 0.143924f, 0.076606f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.680942f, 0.000000f, 0.000000f, -0.214001f, 0.000000f);
    private static final Pose hit5_30 = new Pose(-1.026146f, -0.684238f, 0.073335f, 0.000000f, -0.310695f, 0.047145f, 0.160563f, 0.069118f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.614387f, 0.000000f, 0.000000f, -0.164084f, 0.000000f);
    private static final Pose hit5_31 = new Pose(-1.163196f, -0.596135f, 0.034178f, 0.000000f, -0.276433f, 0.020225f, 0.170352f, 0.064713f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575230f, 0.000000f, 0.000000f, -0.134716f, 0.000000f);
    private static final Pose hit5_32 = new Pose(-1.227839f, -0.554578f, 0.015708f, 0.000000f, -0.260272f, 0.007527f, 0.174969f, 0.062636f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556760f, 0.000000f, 0.000000f, -0.120864f, 0.000000f);
    private static final Pose hit5_33 = new Pose(-1.243566f, -0.544468f, 0.011215f, 0.000000f, -0.256341f, 0.004438f, 0.176093f, 0.062130f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.552267f, 0.000000f, 0.000000f, -0.117494f, 0.000000f);
    private static final Pose hit5_34 = new Pose(-1.233864f, -0.550705f, 0.013986f, 0.000000f, -0.258766f, 0.006343f, 0.175400f, 0.062442f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.555039f, 0.000000f, 0.000000f, -0.119573f, 0.000000f);
    private static final Pose hit5_35 = new Pose(-1.222224f, -0.558188f, 0.017312f, 0.000000f, -0.261676f, 0.008630f, 0.174568f, 0.062816f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558364f, 0.000000f, 0.000000f, -0.122067f, 0.000000f);
    private static final Pose hit5_36 = new Pose(-1.221730f, -0.558505f, 0.016284f, 0.000000f, -0.261799f, 0.008142f, 0.174533f, 0.060961f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556167f, 0.000000f, 0.000000f, -0.121004f, 0.000000f);
    private static final Pose hit5_37 = new Pose(-1.221730f, -0.558505f, 0.012066f, 0.000000f, -0.261799f, 0.006033f, 0.174533f, 0.054212f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.547731f, 0.000000f, 0.000000f, -0.116786f, 0.000000f);
    private static final Pose hit5_38 = new Pose(-1.221730f, -0.558505f, 0.006468f, 0.000000f, -0.261799f, 0.003234f, 0.174533f, 0.045255f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.536535f, 0.000000f, 0.000000f, -0.111188f, 0.000000f);
    private static final Pose hit5_39 = new Pose(-1.221730f, -0.558505f, 0.001803f, 0.000000f, -0.261799f, 0.000902f, 0.174533f, 0.037792f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.527205f, 0.000000f, 0.000000f, -0.106523f, 0.000000f);
    private static final Pose hit5_40 = new Pose(-1.221730f, -0.558505f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.174533f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);

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
