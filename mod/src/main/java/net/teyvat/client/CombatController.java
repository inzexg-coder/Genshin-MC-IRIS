package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.teyvat.particle.TeyvatSlashEffect;
import net.teyvat.particle.TeyvatParticles;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
 * после любой позы — не следит за камерой); удары МГНОВЕННЫЕ — замах
 * сжат в первые ~10-12% клипа (очень быстрый размах), пик скорости
 * (момент урона) на 1-2 тике, остаток клипа — широкое сопровождение;
 * удары МАКСИМАЛЬНО ШИРОКИЕ — клинок
 * выписывает широкие дуги (клинок уводится далеко в замах и далеко в
 * сопровождение); КОРПУС НЕ ОТРЫВАЕТСЯ ОТ НОГ — углы корпуса малые
 * (bYaw ±12°, bPitch ≤8°, у тела ванильный пивот на уровне шеи), наклон
 * в удар делает root целиком (root.pitch до 10°) с выпадом ног; левая
 * рука — естественный противовес без резких смен направления; ноги
 * переступают с выпадом в момент урона. Каждый клип непрерывен
 * (t=0 = финал предыдущего удара); разворот на 360° делает root ванильной
 * модели (getRootPart().yaw): торс, голова, руки и клинок поворачиваются
 * как единое целое. Полный круг ≈ 3.5 с, серия идёт по КЛИКАМ: быстрый
 * тап — МГНОВЕННЫЙ удар комбо; если ЛКМ не отпускать, после удара начинается
 * ЗАРЯД — накопление 3 секунды (FULL_CHARGE_TICKS): отпускание в любой
 * момент — спин на 360° с уроном по уровню заряда, не отпускать — через
 * 3 сек автозапуск максимальной (как у путешественника в начале Genshin:
 * хит по полному кругу вокруг тела, «орбиты атомов», полупрозрачная сфера
 * из ударов лезвием, тратит стамину, после неё серия сброшена).
 * Пока с последнего клика прошло ≤ ~1 с, комбо продолжается со следующего
 * удара (после пятого — снова первый); пауза дольше секунды сбрасывает
 * серию (следующий клик — первый удар). После пятого удара — обязательная
 * пауза ~1 сек (клики глотаются).
 * Клинок вырисовывает разрез: во время свинга
 * рисуется светящаяся дуга-полумесяц (трейл по траектории клинка +
 * усиленные частицы). Между анимациями — плавные переходы (первые ~5%
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
    /** Тики хит-стопа (hitlag) при ПОПАДАНИИ: поза и тайминги комбо замерли
     *  на пару кадров, ввод движения заблокирован. Промах хит-стопа не даёт. */
    private static int hitlagTicks;
    /** Заряженная атака (удержание ЛКМ): идёт ли заряд сейчас. */
    private static boolean charging;
    /** Тики с начала заряда (0..FULL_CHARGE_TICKS): урон и индикатор. */
    private static int chargeTicks;
    /** Уровень заряда выстрелившего спина 0..1 (для вихря и серверного урона). */
    private static float chargeLevel;
    /** Тик последнего нажатия ЛКМ, вооружившего удержание (-1 = нет).
     *  Если ЛКМ не отпустить в течение CHARGE_START_TICKS, начнётся заряд. */
    private static int holdStartTick = -1;

    /** Тиков удержания ЛКМ после удара, после которых начинается заряд
     *  (~0.2 сек). Быстрый тап (отпустили раньше) — обычный удар комбо. */
    private static final int CHARGE_START_TICKS = 4;

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

    /** Доля клипа, на которой работает смешивание перехода между ударами.
     *  Маленькая, чтобы не съедать сжатый замах (мгновенный удар). */
    private static final float TRANSITION_BLEND = 0.05f;
    /** Старт НОВОГО комбо после паузы: более широкое окно смешивания с
     *  нейтралью, чтобы первый удар плавно «выезжал» из покоя, без рывка
     *  из стойки (используется, пока идёт удар 1 свежего комбо). */
    private static final float COLD_START_BLEND = 0.08f;
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
     *  в сжатом замахе лезвие слегка отстаёт от
     *  руки (trail), к моменту урона (~0.12) плоскость выравнивается,
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
            {0f, -18f, 0f, 24f, 6f, 0f},      // charged: спин — лезвие вбок, орбита
    };
    /** Моменты кадров BLADE_DEG (доли клипа): замах сжат, выравнивание
     *  лезвия и перехлёст (whip) сдвинуты к мгновенному удару. */
    private static final float[] BLADE_T = {0f, 0.05f, 0.12f, 0.22f, 0.60f, 1f};
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

    // --- Визуал заряженной атаки: растущая сфера в руке, разлёт сферы на
    // отпускании и «шерстяная дуга» по поверхности (1..6 оборотов). ---

    /** Сфера заряда в руке (у клинка): центр, радиус, альфа. */
    public record ChargeSphere(Vec3d center, float radius, float alpha) {}
    private static Vec3d chargeSphereCenter = Vec3d.ZERO;
    private static float chargeSphereRadius = 0f;
    private static float chargeSphereAlpha = 0f;

    /** Разлёт сферы вокруг игрока на отпускании (частицы): сколько тиков
     *  идёт разлёт, текущий радиус, альфа по уровню заряда. */
    private static int burstTicks = -1;
    private static float burstRadius = 0f;
    private static float burstAlpha = 0f;

    /** Шерстяная дуга (частицы по поверхности сферы): 1..6 оборотов. */
    private static Vec3d wrapCenter = Vec3d.ZERO;
    private static float wrapRadius = 0f;
    private static float wrapTurns = 1f;
    private static float wrapTiltYaw = 0f;
    private static float wrapTiltPitch = 0f;
    private static int wrapDrawn = 0;
    private static int wrapTotal = 0;

    /** Предыдущая точка кончика клинка (для цепочки дуг за мечом в вихре). */
    private static Vec3d lastWhirlBlade = null;

    /** Активная сфера заряда (пустая, если заряда нет). */
    public static ChargeSphere chargeSphere() {
        if (chargeSphereRadius <= 0f) {
            return null;
        }
        return new ChargeSphere(chargeSphereCenter, chargeSphereRadius, chargeSphereAlpha);
    }

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        tickCount++;
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
        // --- Заряженная атака: тап — мгновенный удар комбо; если ЛКМ не
        // отпускать, после удара начинается накопление (3 сек). Отпускание
        // в любой момент — спин с уроном по уровню заряда; не отпускать —
        // через 3 сек автозапуск максимальной. ---
        boolean lmbHeld = client.options.attackKey.isPressed();
        if (charging && !client.player.isOnGround()) {
            // Прыжок (или обрыв) прерывает заряд.
            charging = false;
            chargeTicks = 0;
            holdStartTick = -1;
        }
        if (charging) {
            chargeTicks++;
            spawnChargeParticles(client, client.player);
            if (!lmbHeld) {
                // Отпустили: спин с текущим уровнем заряда (ранний отпуск = слабее).
                fireCharged();
            } else if (chargeTicks >= SwordCombo.FULL_CHARGE_TICKS) {
                // 3 секунды заряда — автозапуск максимальной.
                fireCharged();
            }
        } else if (lmbHeld && holdStartTick >= 0
                && tickCount - holdStartTick >= CHARGE_START_TICKS
                && client.player.isOnGround()) {
            // ЛКМ держим после удара: начинаем накопление.
            startCharging(client);
        }
        if (!lmbHeld) {
            holdStartTick = -1;
        }
        if (!charging) {
            // Сфера заряда живёт только пока копим; на спине её сменяет разлёт.
            chargeSphereRadius = 0f;
        }
        if (hitlagTicks > 0) {
            // Хит-стоп: всё замерло — поза удара, тайминги комбо и пауза после
            // 5-го удара. Отдача камеры держится на месте и гаснет после.
            hitlagTicks--;
            return;
        }
        if (finalCooldownTicks > 0) {
            finalCooldownTicks--;
        }
        if (comboStep >= 0) {
            if (comboStep == SwordCombo.CHARGE_INDEX) {
                // Заряженный спин: клинок ведёт орбиту вокруг тела, урон
                // на тике DAMAGE_TICKS по полному кругу (сервер, «орбиты
                // атомов»). Длится ровно 1 секунду (DURATION_TICKS[5] = 20),
                // в течение которой любые действия запрещены; после — серия
                // сброшена и плавный выход.
                hitTicks++;
                if (hitTicks == 1) {
                    applyStep(client.player);
                }
                // Вихрь: серпы и граница шара — «удары лезвием в секунду».
                spawnWhirlwindEffects(client, client.player);
                // Разлёт сферы (частицы): первые тики — расширяющаяся оболочка
                // искр вокруг игрока; плотность зависит от уровня заряда.
                if (burstTicks >= 0) {
                    burstTicks++;
                    burstRadius = 0.8f + (wrapRadius - 0.8f) * Math.min(1f, burstTicks / 4f);
                    int shell = (int) (10 + 18 * burstAlpha);
                    for (int i = 0; i < shell; i++) {
                        double theta = client.world.random.nextDouble() * Math.PI * 2.0;
                        double phi = Math.acos(2.0 * client.world.random.nextDouble() - 1.0);
                        client.world.addParticleClient(ParticleTypes.CRIT,
                                client.player.getX() + burstRadius * Math.sin(phi) * Math.cos(theta),
                                client.player.getY() + 1.0 + burstRadius * Math.cos(phi),
                                client.player.getZ() + burstRadius * Math.sin(phi) * Math.sin(theta),
                                0, 0, 0);
                    }
                    if (burstTicks > 5) {
                        burstTicks = -1;
                    }
                }
                // Шерстяная дуга (частицы): молниеносно летит по поверхности
                // сферы (1..6 оборотов), лёгкий снос наружу — нити клубка.
                wrapCenter = new Vec3d(client.player.getX(), client.player.getY() + 1.0, client.player.getZ());
                int perTick = Math.max(1, (wrapTotal + 19) / 20);
                int target = Math.min(wrapTotal, wrapDrawn + perTick);
                while (wrapDrawn < target) {
                    Vec3d wp = wrapPoint(wrapDrawn);
                    Vec3d out = wp.subtract(wrapCenter).normalize().multiply(0.03);
                    client.world.addParticleClient(ParticleTypes.END_ROD,
                            wp.x, wp.y, wp.z, out.x, out.y + 0.02, out.z);
                    if (wrapDrawn % 3 == 0) {
                        client.world.addParticleClient(ParticleTypes.CRIT,
                                wp.x, wp.y, wp.z, out.x, out.y + 0.02, out.z);
                    }
                    wrapDrawn++;
                }
                if (hitTicks == SwordCombo.DAMAGE_TICKS[SwordCombo.CHARGE_INDEX] && !sentHit) {
                    sentHit = true;
                    sendHit(SwordCombo.CHARGE_INDEX, chargeLevel);
                    CinematicShots.onDamageTick();
                    spawnSlashEffects(client, client.player);
                    applyLunge(client.player);
                }
                if (hitTicks >= SwordCombo.DURATION_TICKS[SwordCombo.CHARGE_INDEX]) {
                    lastStep = -1;
                    lastClickTick = -1;
                    sentHit = false;
                    bufferedNext = false;
                    comboStep = -1;
                    exitBlendTicks = EXIT_BLEND_TICKS;
                    wrapDrawn = 0;
                    wrapTotal = 0;
                    burstTicks = -1;
                    lastWhirlBlade = null;
                }
                impactKick *= 0.84f;
                return;
            }
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
                // След-серп каждый тик: квад у текущей позиции клинка.
                spawnTickSlash(client, client.player);
                // Кинокамера-орбита (/cinema orbit): герой доворачивается к ближайшему
                // врагу, чтобы каждый удар шёл в его сторону — для съёмки боя со стороны.
                faceNearestEnemyDuringCinema(client);
                if (hitTicks == 1) {
                    // Шаг вперёд с началом удара (часть LUNGE_STRENGTH): серия
                    // продвигает героя с каждым ударом, как в Genshin.
                    applyStep(client.player);
                }
                if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                    sentHit = true;
                    sendHit(comboStep, 0f);
                    // Автоскриншоты ударов (/cinema shots): следующий кадр — кинокамера сбоку.
                    CinematicShots.onDamageTick();
                    // Свинг: разрез-дуга и свист всегда; толчок на тике урона
                    // (остаток LUNGE_STRENGTH после шага в начале удара).
                    spawnSlashEffects(client, client.player);
                    applyLunge(client.player);
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

    /** ЛКМ нажат (вместо ванильной атаки). Тап — МГНОВЕННЫЙ удар комбо;
     *  если ЛКМ не отпускать ~0.2 с, после удара начнётся накопление заряда
     *  (3 сек), отпускание — спин с уроном по уровню заряда, как в Genshin.
     *  Возвращает true, если клик съеден (ванильная атака подавлена). */
    public static boolean onAttackPress() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (charging) {
            // Уже заряжаем: повторное нажатие ничего не делает.
            return true;
        }
        if (onAttackClick()) {
            // Удар (или буфер следующего) принят — вооружаем удержание:
            // если кнопку не отпустить, через CHARGE_START_TICKS начнётся заряд.
            holdStartTick = tickCount;
        }
        return true;
    }

    /** Обычный удар комбо (мгновенно, на сам клик). Возвращает true, если
     *  удар/буфер принят; false — клик проглочен (воздух, хит-стоп, пауза). */
    public static boolean onAttackClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (isChargedAttackActive()) {
            // Спин заряженной атаки: клики глотаются — ни удар, ни буфер, ни заряд.
            return false;
        }
        if (!client.player.isOnGround()) {
            // В воздухе обычные атаки не работают (только приземление), клик съедается.
            return false;
        }
        if (hitlagTicks > 0) {
            // Хит-стоп: клики глотаются, следующий удар цепляется после замирания.
            return false;
        }
        if (finalCooldownTicks > 0) {
            // Пауза после пятого удара: клик съедается, комбо не начинается.
            return false;
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

    /** Начать заряд: сброс атаки/комбо, поза заряда, индикатор на дуге стамины. */
    private static void startCharging(MinecraftClient client) {
        if (client.player == null || !client.player.isOnGround()) {
            return;
        }
        cancelAttack();
        charging = true;
        chargeTicks = 0;
        exitBlendTicks = 0;
        // Серия сбрасывается: после заряженного удара обычный клик начнёт
        // комбо с первого удара (как в Genshin).
        lastStep = -1;
        lastClickTick = -1;
        client.world.playSound(null, client.player.getX(), client.player.getY(), client.player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.3f, 1.6f,
                client.world.random.nextLong());
    }

    /** Отпускание/автозапуск заряда: спин с текущим уровнем. */
    private static void fireCharged() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        charging = false;
        int ready = chargeTicks;
        chargeTicks = 0;
        holdStartTick = -1;
        if (client.player.isOnGround()) {
            float level = Math.min(1f, ready / (float) SwordCombo.FULL_CHARGE_TICKS);
            startChargedAttack(client, level);
        }
    }

    /** Выстрел заряженного спина: спин на 360° с хитом по кругу (сервер сам
     *  бьёт всех вокруг), трата стамины. level — уровень заряда 0..1: от него
     *  зависит урон и размер вихря. */
    private static void startChargedAttack(MinecraftClient client, float level) {
        if (client.player == null || client.world == null || !client.player.isOnGround()) {
            return;
        }
        if (!StaminaController.trySpendCharge()) {
            // Стамины не хватило — заряд отменяется, спин не выстрелит.
            return;
        }
        chargeLevel = level;
        comboStep = SwordCombo.CHARGE_INDEX;
        hitTicks = 0;
        sentHit = false;
        bufferedNext = false;
        recoveryTicks = 0;
        finalCooldownTicks = 0;
        exitBlendTicks = 0;
        lastStep = -1;
        lastClickTick = -1;
        // Разлёт сферы вокруг игрока: резкое расширение в первые тики спина,
        // прозрачность зависит от длины удержания (уровня заряда).
        burstTicks = 0;
        burstAlpha = 0.25f + 0.65f * level;
        burstRadius = 0.8f;
        // Шерстяная дуга по поверхности сферы: 1..6 оборотов (от удержания),
        // «плетение» по широте и случайный наклон — витки не наслаиваются.
        wrapCenter = new Vec3d(client.player.getX(), client.player.getY() + 1.0, client.player.getZ());
        wrapRadius = (float) (1.6 + 0.8 * level);
        wrapTurns = 1f + 5f * level;
        wrapTiltYaw = client.world.random.nextFloat() * (float) Math.PI * 2.0f;
        wrapTiltPitch = (client.world.random.nextFloat() - 0.5f) * 1.1f;
        wrapTotal = (int) (wrapTurns * 48f);
        wrapDrawn = 0;
        lastWhirlBlade = null;
        client.world.playSound(null, client.player.getX(), client.player.getY(), client.player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.4f + 0.5f * level, 0.7f,
                client.world.random.nextLong());
        // Вспышка запуска: кольцо-волна по земле.
        if (client.world != null) {
            for (int i = 0; i < 18; i++) {
                double a = i / 18.0 * Math.PI * 2.0;
                double r = 2.0 + 3.5 * level;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        client.player.getX() + Math.cos(a) * r,
                        client.player.getY() + 0.15,
                        client.player.getZ() + Math.sin(a) * r,
                        Math.cos(a) * 1.4, 0.4, Math.sin(a) * 1.4);
            }
        }
    }

    /** Частицы заряда (3 секунды накопления): спираль-«магнит» сходится
     *  к клинку, радиус и плотность растут, на полном заряде — вспышка. */
    private static void spawnChargeParticles(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float level = chargeProgress();
        // Растущая светлая сфера В КИСТЯХ, где меч (не на кончике клинка):
        // центр — origin предмета в руке, радиус компактный, яркость растёт
        // с зарядом.
        chargeSphereRadius = 0.10f + 0.34f * level;
        chargeSphereAlpha = 0.55f + 0.45f * level;
        chargeSphereCenter = bladeHandWorld(CHARGE_POSE, currentRootYawRad(),
                player.getBodyYaw(), new Vec3d(player.getX(), player.getY(), player.getZ()));
        int step = chargeTicks % 2 == 0 ? 1 : 2;
        double baseR = 1.2 + 3.2 * level;
        // Спираль искр, закрученная вокруг героя (радиус растёт с зарядом).
        for (int i = 0; i < 6 / step; i++) {
            double a = client.world.random.nextDouble() * Math.PI * 2.0;
            double r = baseR * (0.5 + client.world.random.nextDouble() * 0.7);
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a) * r,
                    player.getY() + 0.5 + client.world.random.nextDouble() * 1.6,
                    player.getZ() + Math.sin(a) * r,
                    -Math.cos(a) * 0.35, 0.3, -Math.sin(a) * 0.35);
        }
        // Кольцо на земле, расширяющееся с зарядом.
        if (chargeTicks % 4 == 0) {
            int n = 8 + (int) (level * 16);
            for (int i = 0; i < n; i++) {
                double a = i / (double) n * Math.PI * 2.0 + 0.4;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * baseR,
                        player.getY() + 0.1,
                        player.getZ() + Math.sin(a) * baseR,
                        Math.cos(a) * 0.4, 0.35, Math.sin(a) * 0.4);
            }
        }
        // Свечение клинка (рука поднята вверх-вперёд).
        client.world.addParticleClient(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 2.0, player.getZ(), 0, 0.2, 0);
        // Полный заряд: вспышка один раз.
        if (level >= 1f && chargeTicks == SwordCombo.FULL_CHARGE_TICKS) {
            for (int i = 0; i < 26; i++) {
                double a = i / 26.0 * Math.PI * 2.0;
                client.world.addParticleClient(ParticleTypes.CRIT,
                        player.getX() + Math.cos(a) * 4.6,
                        player.getY() + 0.4 + Math.sin(a * 2.0) * 1.4,
                        player.getZ() + Math.sin(a) * 4.6,
                        0, 0, 0);
            }
            client.world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.4f,
                    client.world.random.nextLong());
        }
    }

    /** Вихрь заряженного спина: компактный ПЛОТНЫЙ шар «ударов лезвием»
     *  вокруг героя — меньший радиус, но десятки серпов и искр на каждый
     *  кадр (граница шара + концентрические слои). Радиус зависит от
     *  уровня заряда, но всегда заметно меньше исходной сферы. */
    private static void spawnWhirlwindEffects(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float level = chargeLevel;
        double r = 1.6 + 0.8 * level;
        Vec3d center = new Vec3d(player.getX(), player.getY() + 1.0, player.getZ());
        // Серп-«хвост за клинком»: маленькая дуга-частица РОВНО по траектории
        // кончика меча (удары совпадают с мечом — техника Better Combat).
        float p = Math.min(1f, hitTicks / (float) SwordCombo.DURATION_TICKS[SwordCombo.CHARGE_INDEX]);
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d tip = bladeTipWorld(computePose(p), chargedSpinTurn(p), player.getBodyYaw(), slashPos);
        if (lastWhirlBlade != null && lastWhirlBlade.squaredDistanceTo(tip) > 1.0e-6) {
            spawnSlashArc(client.world, TeyvatParticles.SLASH_90,
                    new Vec3d[]{lastWhirlBlade, tip}, 2.6f, 0x88FFFFFF);
        }
        lastWhirlBlade = tip;
        // Орбиты-атомы: плотный шар из КОЛЕЦ-дуг (360°) в случайных плоскостях
        // вокруг тела — граница вихря читается как светящаяся сфера из лезвий.
        int orbits = (int) (2 + 2 * level);
        for (int i = 0; i < orbits; i++) {
            Vec3d rnd = new Vec3d(
                    client.world.random.nextDouble() - 0.5,
                    client.world.random.nextDouble() - 0.5,
                    client.world.random.nextDouble() - 0.5);
            if (rnd.lengthSquared() < 1.0e-6) {
                rnd = new Vec3d(1.0, 0.0, 0.0);
            }
            double rr = r * (0.72 + client.world.random.nextDouble() * 0.55);
            spawnOrbitArc(client.world, TeyvatParticles.SLASH_360,
                    center, rnd, (float) rr, 0x99FFFFFF);
        }
        // Полупрозрачная граница шара: плотный слой искр на сфере.
        for (int i = 0; i < 48; i++) {
            double theta = client.world.random.nextDouble() * Math.PI * 2.0;
            double phi = Math.acos(2.0 * client.world.random.nextDouble() - 1.0);
            client.world.addParticleClient(ParticleTypes.CRIT,
                    player.getX() + r * Math.sin(phi) * Math.cos(theta),
                    player.getY() + 1.0 + r * Math.cos(phi) * 0.75,
                    player.getZ() + r * Math.sin(phi) * Math.sin(theta),
                    0, 0, 0);
        }
        // Кольца по земле: две орбиты искр (внешняя и внутренняя).
        for (int i = 0; i < 24; i++) {
            double a = i / 24.0 * Math.PI * 2.0;
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a) * r,
                    player.getY() + 0.1,
                    player.getZ() + Math.sin(a) * r,
                    Math.cos(a) * 0.5, 0.4, Math.sin(a) * 0.5);
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a + 0.4) * r * 0.6,
                    player.getY() + 0.35,
                    player.getZ() + Math.sin(a + 0.4) * r * 0.6,
                    Math.cos(a + 0.4) * 0.35, 0.5, Math.sin(a + 0.4) * 0.35);
        }
    }

    /** Прогресс заряда 0..1 (для индикатора на дуге стамины). */
    public static float chargeProgress() {
        if (!charging) {
            return 0f;
        }
        return MathHelper.clamp(chargeTicks / (float) SwordCombo.FULL_CHARGE_TICKS, 0f, 1f);
    }

    /** Радиус поиска ближайшего врага для доворота в кинокамере-орбите. */
    private static final double CINEMA_TARGET_RANGE = 12.0;
    /** Скорость доворота к врагу за тик (20 тиков/сек): видимый плавный поворот,
     *  к концу первого удара герой уже почти смотрит на цель. */
    private static final float CINEMA_TURN_RATE = 0.2f;

    /** В режиме кинокамеры-орбиты плавно доворачивает героя к ближайшему врагу.
     *  Вне орбиты и без врагов рядом ничего не делает — управление не трогается. */
    private static void faceNearestEnemyDuringCinema(MinecraftClient client) {
        if (CinematicCamera.mode() != CinematicCamera.Mode.ORBIT && !CinematicShots.isEnabled()) {
            return;
        }
        LivingEntity target = nearestEnemy(client);
        if (target == null || client.player == null) {
            return;
        }
        double dx = target.getX() - client.player.getX();
        double dz = target.getZ() - client.player.getZ();
        if (dx * dx + dz * dz < 1.0E-8) {
            return;
        }
        float desiredYaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        float smoothed = MathHelper.lerpAngleDegrees(CINEMA_TURN_RATE, client.player.getYaw(), desiredYaw);
        client.player.setYaw(smoothed);
        client.player.setBodyYaw(smoothed);
        client.player.setHeadYaw(smoothed);
    }

    /** Ближайший живой враг в радиусе CINEMA_TARGET_RANGE (без игроков, Паймон
     *  и мирных животных). Возвращает null, если таких рядом нет. */
    private static LivingEntity nearestEnemy(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return null;
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        List<LivingEntity> mobs = world.getEntitiesByType(
                TypeFilter.instanceOf(LivingEntity.class),
                new Box(px - CINEMA_TARGET_RANGE, py - CINEMA_TARGET_RANGE, pz - CINEMA_TARGET_RANGE,
                        px + CINEMA_TARGET_RANGE, py + CINEMA_TARGET_RANGE, pz + CINEMA_TARGET_RANGE),
                e -> e != client.player && !e.isPlayer() && e.isAlive()
                        && e.getType() != PaimonEntity.TYPE
                        && !(e instanceof ArmorStandEntity)
                        && !(e instanceof PassiveEntity)
                        && !(e instanceof FishEntity));
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity mob : mobs) {
            double d = mob.squaredDistanceTo(client.player);
            if (d < best) {
                best = d;
                nearest = mob;
            }
        }
        return nearest;
    }

    /** Пакет урона серверу: сервер сам находит цели по хитбоксу удара.
     *  chargeLevel — уровень заряда спина (у обычных ударов 0). */
    private static void sendHit(int hitIndex, float chargeLevel) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new PlayerAttackPayload(hitIndex, chargeLevel));
        }
    }

    /** Спавн дуги-частицы по плоскости фактического пути клинка (path):
     *  строим ортонормированный базис (sweep, bow, normal), дуга выгибается
     *  к камере — читается из любого ракурса, в 1-м и 3-м лице. */
    private static void spawnSlashArc(ClientWorld world, ParticleType<TeyvatSlashEffect> type,
                                      Vec3d[] path, float scale, int color) {
        if (world == null || path.length < 2) {
            return;
        }
        Vec3d p0 = path[0];
        Vec3d pMid = path[path.length / 2];
        Vec3d pEnd = path[path.length - 1];
        Vec3d v1 = pMid.subtract(p0);
        Vec3d v2 = pEnd.subtract(p0);
        Vec3d n = v1.crossProduct(v2);
        if (n.lengthSquared() < 1.0e-10) {
            n = v2.normalize().crossProduct(new Vec3d(0.0, 1.0, 0.0));
        }
        n = n.normalize();
        Vec3d sweep = v2.normalize();
        Vec3d bow = n.crossProduct(sweep).normalize();
        Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        if (bow.dotProduct(cam.subtract(pMid)) < 0.0) {
            bow = bow.multiply(-1.0);
        }
        Quaternionf q = basisQuat(sweep, bow, n);
        world.addParticleClient(new TeyvatSlashEffect(type, q.x, q.y, q.z, q.w, scale, color, true),
                pMid.x, pMid.y, pMid.z, 0.0, 0.0, 0.0);
    }

    /** Параметры дуги-серпа обычного удара (техника Better Combat): угол серпа
     *  (тип частицы), радиус кольца и наклоны в градусах (pitch/roll/localYaw)
     *  — плоскость дуги подобрана под каждое движение клинка. */
    private record SlashArcSpec(ParticleType<TeyvatSlashEffect> type, float radius,
                                float pitchDeg, float rollDeg, float localYawDeg, double centerY) {}

    /** Дуга-серп обычного удара: кольцо вокруг игрока радиусом ~дистанции
     *  атаки, держится за поворот игрока (yaw), плоскость задаётся
     *  pitch/roll/localYaw на каждый удар — серп «рассекает воздух» ровно
     *  в сторону движения меча (как в Better Combat). */
    private static SlashArcSpec slashArcSpec(int step) {
        return switch (step) {
            // 1: горизонтальный слева направо — полукруг через фронт.
            case 0 -> new SlashArcSpec(TeyvatParticles.SLASH_180, 2.9f, 0f, 0f, 90f, 1.05);
            // 2: апперкот — диагональ вверх-влево за плечо.
            case 1 -> new SlashArcSpec(TeyvatParticles.SLASH_90, 2.6f, -45f, 0f, 60f, 1.15);
            // 3: разворот на 360° — полное кольцо вокруг тела.
            case 2 -> new SlashArcSpec(TeyvatParticles.SLASH_360, 2.4f, 0f, 0f, 0f, 1.0);
            // 4: горизонтальный справа налево — полукруг через фронт (зеркало 1).
            case 3 -> new SlashArcSpec(TeyvatParticles.SLASH_180, 3.0f, 0f, 180f, 90f, 1.05);
            // 5: финальный очень широкий слева направо — 3/4 кольца.
            case 4 -> new SlashArcSpec(TeyvatParticles.SLASH_270, 3.7f, 0f, 0f, 90f, 1.0);
            default -> new SlashArcSpec(TeyvatParticles.SLASH_180, 2.9f, 0f, 0f, 90f, 1.05);
        };
    }

    /** Спавн дуги-серпа вокруг игрока (Better Combat): квад ставится по
     *  кватерниону Ry(-yaw)*Rx(pitch+90)*Ry(roll)*Rz(localYaw). */
    private static void spawnPlayerArc(ClientWorld world, ClientPlayerEntity player, int step) {
        if (world == null) {
            return;
        }
        SlashArcSpec spec = slashArcSpec(step);
        Quaternionf q = slashQuat(player.getYaw(), spec.pitchDeg(), spec.rollDeg(), spec.localYawDeg());
        world.addParticleClient(new TeyvatSlashEffect(spec.type(), q.x, q.y, q.z, q.w,
                        spec.radius() / 0.36f, 0xFFFFFFFF, true),
                player.getX(), player.getY() + spec.centerY(), player.getZ(), 0.0, 0.0, 0.0);
    }

    /** Кватернион дуги-серпа (техника Better Combat):
     *  Ry(-yaw) * Rx(pitch+90) * Ry(roll) * Rz(localYaw). */
    private static Quaternionf slashQuat(float yawDeg, float pitchDeg, float rollDeg, float localYawDeg) {
        Matrix4f m = new Matrix4f();
        m.identity();
        m.rotateY((float) Math.toRadians(-yawDeg));
        m.rotateX((float) Math.toRadians(pitchDeg + 90.0f));
        m.rotateY((float) Math.toRadians(rollDeg));
        m.rotateZ((float) Math.toRadians(localYawDeg));
        return new Quaternionf().setFromNormalized(m);
    }

    /** Спавн КОЛЬЦА-дуги (360°) в плоскости с нормалью normal вокруг центра:
     *  орбита «атома» — для заряженного спина и вихря. Радиус кольца
     *  задаётся scale через spawnOrbitArc. */
    private static void spawnOrbitArc(ClientWorld world, ParticleType<TeyvatSlashEffect> type,
                                      Vec3d center, Vec3d normal, float radius, int color) {
        if (world == null) {
            return;
        }
        Vec3d n = normal.normalize();
        Vec3d ref = Math.abs(n.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
        Vec3d xAxis = n.crossProduct(ref).normalize();
        Vec3d yAxis = n.crossProduct(xAxis).normalize();
        Quaternionf q = basisQuat(xAxis, yAxis, n);
        // Кольцо радиусом radius: квад 360° ставится по диаметру — размер
        // квада = 2 * радиус / 0.72 (радиус дуги в текстуре ≈ 0.36 размера).
        float scale = radius / 0.36f;
        world.addParticleClient(new TeyvatSlashEffect(type, q.x, q.y, q.z, q.w, scale, color, true),
                center.x, center.y, center.z, 0.0, 0.0, 0.0);
    }

    /** Кватернион поворота из ортонормированного базиса (x, y, z) — так
     *  локальная ось X квада ложится вдоль x, ось Y — вдоль y. */
    private static Quaternionf basisQuat(Vec3d x, Vec3d y, Vec3d z) {
        Matrix4f m = new Matrix4f();
        m.m00((float) x.x);
        m.m01((float) x.y);
        m.m02((float) x.z);
        m.m10((float) y.x);
        m.m11((float) y.y);
        m.m12((float) y.z);
        m.m20((float) z.x);
        m.m21((float) z.y);
        m.m22((float) z.z);
        return new Quaternionf().setFromNormalized(m);
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
        boolean charged = comboStep == SwordCombo.CHARGE_INDEX;
        // Обычный удар — ОДИН длинный толстый серп по траектории клинка;
        // заряженный спин — короткие толстые дуги-орбиты (стена лезвий).
        float rootYaw = currentRootYawRad();
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (charged) {
            // Вспышка урона спина: плотный шар КОЛЕЦ-дуг вокруг тела (орбиты
            // атомов) — «стена лезвий» по всей площади спина.
            Vec3d ctr = new Vec3d(player.getX(), player.getY() + 1.0, player.getZ());
            for (int i = 0; i < 6; i++) {
                Vec3d rnd = new Vec3d(
                        world.random.nextDouble() - 0.5,
                        world.random.nextDouble() - 0.5,
                        world.random.nextDouble() - 0.5);
                if (rnd.lengthSquared() < 1.0e-6) {
                    rnd = new Vec3d(1.0, 0.0, 0.0);
                }
                spawnOrbitArc(world, TeyvatParticles.SLASH_360, ctr, rnd, 1.9f, 0xAAFFFFFF);
            }
        } else {
            // Вспышка-серп на тике урона: крупный яркий серп по траектории клинка.
            spawnPlayerArc(world, player, comboStep);
        }
        // Дуга размаха: плотные штрихи-искры вдоль траектории меча.
        // У заряженного спина — полный круг (орбита вокруг тела).
        int count = 16;
        double radius = charged ? 1.9 : 2.4;
        double height = 1.0 + comboStep * 0.09;
        float span = charged ? 6.2f : 1.55f + comboStep * 0.12f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.6, 0.0, -Math.sin(ang) * 1.6);
        }
        // Свист свинга (тихий, всегда): «клац» попадания — только на хите
        // (звук с сервера по AttackResultPayload).
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 1.0f,
                world.random.nextLong());
        // Кольцо «орбит» заряженного спина: искры по кругу вокруг героя
        // (радиус под компактную плотную сферу вихря).
        if (charged) {
            for (int i = 0; i < 24; i++) {
                double a = i / 24.0 * Math.PI * 2.0;
                world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * 2.2,
                        player.getY() + 0.2,
                        player.getZ() + Math.sin(a) * 2.2,
                        Math.cos(a) * 1.2, 0.1, Math.sin(a) * 1.2);
            }
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

    /** Спавн кусочка следа-серпа КАЖДЫЙ тик во время свинга.
     *  Квад ставится по当前位置 клинка (bladeTipWorld), ориентирован
     *  по направлению движения лезвия — получается непрерывный след. */
    private static void spawnTickSlash(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null || comboStep < 0 || comboStep == SwordCombo.CHARGE_INDEX) {
            return;
        }
        int duration = SwordCombo.DURATION_TICKS[comboStep];
        float progress = (float) hitTicks / duration;
        // Позиция клинка в текущий тик
        float rootYaw = currentRootYawRad();
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d tipCurrent = bladeTipWorld(computePose(progress), rootYaw, player.getBodyYaw(), playerPos);
        // Позиция клинка чуть раньше (для направления)
        float prevProgress = Math.max(0f, progress - 0.08f);
        Vec3d tipPrev = bladeTipWorld(computePose(prevProgress), rootYaw, player.getBodyYaw(), playerPos);
        Vec3d delta = tipCurrent.subtract(tipPrev);
        double len = delta.length();
        if (len < 1.0e-4) {
            return;
        }
        Vec3d dir = delta.normalize();
        // Ориентация квада: нормаль плоскости = cross(dir, cameraDir)
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        Vec3d toCam = cam.subtract(tipCurrent).normalize();
        Vec3d normal = dir.crossProduct(toCam);
        if (normal.lengthSquared() < 1.0e-6) {
            normal = dir.crossProduct(new Vec3d(0, 1, 0));
        }
        normal = normal.normalize();
        Vec3d up = normal.crossProduct(dir).normalize();
        Quaternionf q = basisQuat(dir, up, normal);
        // Масштаб: мелкий в начале/конце, нормальный в середине
        float scaleMult = (progress < 0.2f) ? progress / 0.2f
                        : (progress > 0.8f) ? (1.0f - progress) / 0.2f
                        : 1.0f;
        float scale = 2.0f * scaleMult;
        // Спавним серп
        world.addParticleClient(
                new TeyvatSlashEffect(TeyvatParticles.SLASH_90, q.x, q.y, q.z, q.w,
                        scale, 0xCCFFFFFF, true),
                tipCurrent.x, tipCurrent.y, tipCurrent.z,
                0.0, 0.0, 0.0);
    }

    /** Шаг героя вперёд в начале удара: часть LUNGE_STRENGTH, чтобы серия
     *  продвигала героя с каждым ударом (как в Genshin), а не толчком в один
     *  момент. Клиентское движение, как у рывка. */
    private static void applyStep(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep] * SwordCombo.START_STEP_FACTOR;
        if (strength <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Толчок героя вперёд на тике урона: остаток LUNGE_STRENGTH после шага
     *  в начале удара. На пятом ударе — мощный прокат. */
    private static void applyLunge(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep] * (1f - SwordCombo.START_STEP_FACTOR);
        float up = SwordCombo.LUNGE_UP[comboStep];
        if (strength <= 0f && up <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y + up, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Подтверждение попадания от сервера (AttackResultPayload): хит-стоп,
     *  отдача камеры и искры в точке контакта — строго на хите. Промах
     *  (пустой массив) ничего не делает: свинг остаётся визуальным. */
    public static void onHitConfirmed(MinecraftClient client, int[] entityIds) {
        if (entityIds == null || entityIds.length == 0) {
            return;
        }
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        if (comboStep >= 0) {
            hitlagTicks = Math.max(hitlagTicks, SwordCombo.HITLAG_TICKS[comboStep]);
        } else {
            hitlagTicks = Math.max(hitlagTicks, 3);
        }
        // Отдача камеры (FOV/наклон) — только при попадании.
        impactKick = 1.6f;
        // Искры в точке контакта каждой задетой цели.
        for (int id : entityIds) {
            Entity target = world.getEntityById(id);
            if (target == null) {
                continue;
            }
            Vec3d p = target.getBoundingBox().getCenter();
            // Искры-брызги в сторону удара (направление текущего движения клинка).
            Vec3d dir = hitDirection();
            for (int i = 0; i < 10; i++) {
                double vx = (world.random.nextDouble() - 0.5) * 0.9 + dir.x * 0.7;
                double vy = world.random.nextDouble() * 1.5 + 0.2 + dir.y * 0.5;
                double vz = (world.random.nextDouble() - 0.5) * 0.9 + dir.z * 0.7;
                world.addParticleClient(ParticleTypes.CRIT, p.x, p.y, p.z, vx, vy, vz);
            }
            world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0.5, 0);
            // Маленькая дуга-вспышка в точке контакта — строго на хите.
            Vec3d a = p.subtract(dir.multiply(0.6));
            Vec3d b = p.add(dir.multiply(0.6));
            spawnSlashArc(world, TeyvatParticles.SLASH_45, new Vec3d[]{a, p, b}, 2.4f, 0x88FFFFFF);
        }
    }

    /** Направление текущего удара в мире (для искр/вспышки на хите):
     *  от начала замаха к сопровождению по bladeTipWorld. */
    private static Vec3d hitDirection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || comboStep < 0) {
            return new Vec3d(0.0, 0.2, 1.0);
        }
        Vec3d slashPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        float rootYaw = currentRootYawRad();
        Vec3d a = bladeTipWorld(computePose(0.06f), rootYaw, client.player.getBodyYaw(), slashPos);
        Vec3d b = bladeTipWorld(computePose(0.5f), rootYaw, client.player.getBodyYaw(), slashPos);
        Vec3d d = b.subtract(a);
        return d.lengthSquared() < 1.0e-8 ? new Vec3d(0.0, 0.2, 1.0) : d.normalize();
    }

    /** Атака идёт прямо сейчас (включая хит-стоп и фазу восстановления). */
    public static boolean isAttacking() {
        return comboStep >= 0 || finalCooldownTicks > 0;
    }

    /** Заряженный спин идёт прямо сейчас (1 секунда, DURATION_TICKS[5]): в это
     *  время запрещены любые действия — удары, рывок, прыжок, движение. */
    public static boolean isChargedAttackActive() {
        return comboStep == SwordCombo.CHARGE_INDEX;
    }

    /** Dash-cancel: рывок прерывает анимацию атаки, как в Genshin. Серия
     *  комбо при этом сохраняется (lastStep) — следующий клик продолжит цепочку.
     *  Заряженный спин рывком не прерывается (блок на всю секунду). */
    public static boolean tryCancelByDash() {
        if (isChargedAttackActive()) {
            return false;
        }
        return cancelAttack();
    }

    /** Jump-cancel: прыжок прерывает атаку (как в Genshin). Заряженный спин
     *  прыжком не прерывается — герой «заперт» на секунду спина. */
    public static boolean tryCancelByJump() {
        if (isChargedAttackActive()) {
            return false;
        }
        return cancelAttack();
    }

    /** Общая отмена атаки: сбрасываем удар в нейтраль с плавным выходом.
     *  Так же прерывает заряд (dash/jump-cancel заряженной атаки).
     *  Возвращает true, если что-то было отменено. */
    private static boolean cancelAttack() {
        boolean wasActive = comboStep >= 0 || finalCooldownTicks > 0 || charging;
        charging = false;
        chargeTicks = 0;
        holdStartTick = -1;
        if (!wasActive) {
            return false;
        }
        comboStep = -1;
        hitTicks = 0;
        recoveryTicks = 0;
        exitBlendTicks = EXIT_BLEND_TICKS;
        bufferedNext = false;
        sentHit = false;
        finalCooldownTicks = 0;
        hitlagTicks = 0;
        chargeSphereRadius = 0f;
        burstTicks = -1;
        wrapDrawn = 0;
        wrapTotal = 0;
        lastWhirlBlade = null;
        return true;
    }

    /** Отдача удара 0..1: пульс FOV и наклона камеры на момент попадания. */
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
        if (charging) {
            applyChargePose(model, state);
        } else if (comboStep >= 0) {
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

    /** Поза заряда: клинок поднят вверх-вперёд обеими руками, готовность
     *  к спину; лёгкое покачивание и мягкий въезд из предыдущей позы.
     *  Голова не трогается (всегда смотрит вперёд). */
    private static void applyChargePose(PlayerEntityModel model, PlayerEntityRenderState state) {
        float t = state.age * 0.06f;
        float sway = MathHelper.sin(t);
        float breath = MathHelper.sin(state.age * 0.11f);
        float w = MathHelper.clamp(chargeTicks / 6f, 0f, 1f);
        Pose base = hasPrevAppliedPose ? prevAppliedPose : CHARGE_POSE;
        Pose pose = mix(base, CHARGE_POSE, ease(E_IN_OUT_SINE, w));
        pose = new Pose(
                pose.rYaw() + sway * 0.01f,
                pose.rPitch() + breath * 0.012f,
                pose.rRoll() + sway * 0.008f,
                pose.lYaw(),
                pose.lPitch() + sway * 0.008f,
                pose.lRoll(),
                pose.bYaw() + sway * 0.02f,
                pose.bPitch() + breath * 0.02f,
                pose.bRoll(),
                Float.NaN, Float.NaN, Float.NaN,
                pose.rlYaw(),
                pose.rlPitch() + sway * 0.006f,
                pose.rlRoll(),
                pose.llYaw(),
                pose.llPitch() - sway * 0.006f,
                pose.llRoll());
        applyPoseToModel(model, pose);
        model.getRootPart().yaw = sway * 0.03f;
        model.getRootPart().pitch = breath * 0.01f;
        model.getRootPart().originY = 0f;
        prevAppliedPose = poseFromModel(model);
        hasPrevAppliedPose = true;
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
            // Idle: Genshin idle animation (8.333s cycle)
            float t = (state.age % 166.7f) / 166.7f;
            model.rightArm.yaw = LocomotionClips.sample_idle_rYaw(t);
            model.rightArm.pitch = LocomotionClips.sample_idle_rPitch(t);
            model.rightArm.roll = LocomotionClips.sample_idle_rRoll(t);
            model.leftArm.yaw = LocomotionClips.sample_idle_lYaw(t);
            model.leftArm.pitch = LocomotionClips.sample_idle_lPitch(t);
            model.leftArm.roll = LocomotionClips.sample_idle_lRoll(t);
            model.body.yaw = LocomotionClips.sample_idle_bYaw(t);
            model.body.pitch = LocomotionClips.sample_idle_bPitch(t);
            model.body.roll = LocomotionClips.sample_idle_bRoll(t);
            model.rightLeg.yaw = LocomotionClips.sample_idle_rlYaw(t);
            model.rightLeg.pitch = LocomotionClips.sample_idle_rlPitch(t);
            model.rightLeg.roll = LocomotionClips.sample_idle_rlRoll(t);
            model.leftLeg.yaw = LocomotionClips.sample_idle_llYaw(t);
            model.leftLeg.pitch = LocomotionClips.sample_idle_llPitch(t);
            model.leftLeg.roll = LocomotionClips.sample_idle_llRoll(t);
            model.getRootPart().originY = 0f;
            return;
        }

        // Choose animation based on movement speed
        float ph = state.limbSwingAnimationProgress * 0.6662f;
        float p;
        if (move > 0.8f) {
            p = (state.age % 14.0f) / 14.0f;
        } else if (move > 0.4f) {
            p = (state.age % 16.7f) / 16.7f;
        } else {
            p = (state.age % 31.3f) / 31.3f;
        }

        // Sample run locomotion (walk/run/sprint share similar motion)
        model.rightArm.yaw = LocomotionClips.sample_run_rYaw(p);
        model.rightArm.pitch = LocomotionClips.sample_run_rPitch(p);
        model.rightArm.roll = LocomotionClips.sample_run_rRoll(p);
        model.leftArm.yaw = LocomotionClips.sample_run_lYaw(p);
        model.leftArm.pitch = LocomotionClips.sample_run_lPitch(p);
        model.leftArm.roll = LocomotionClips.sample_run_lRoll(p);
        model.body.yaw = LocomotionClips.sample_run_bYaw(p);
        model.body.pitch = LocomotionClips.sample_run_bPitch(p);
        model.body.roll = LocomotionClips.sample_run_bRoll(p);
        model.rightLeg.yaw = LocomotionClips.sample_run_rlYaw(p);
        model.rightLeg.pitch = LocomotionClips.sample_run_rlPitch(p);
        model.rightLeg.roll = LocomotionClips.sample_run_rlRoll(p);
        model.leftLeg.yaw = LocomotionClips.sample_run_llYaw(p);
        model.leftLeg.pitch = LocomotionClips.sample_run_llPitch(p);
        model.leftLeg.roll = LocomotionClips.sample_run_llRoll(p);
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
                comboStep == 2 ? spinTurn(p)
                        : comboStep == SwordCombo.CHARGE_INDEX ? chargedSpinTurn(p) : 0f);
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
        if (comboStep < 0 || p < 0.05f || p > 0.90f) {
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

    /** Поворот root модели на текущем прогрессe: удар 3 и заряженный спин —
     *  полный оборот вокруг своей оси (клинок ведёт орбиту). */
    public static float currentRootYawRad() {
        if (comboStep == 2) {
            return spinTurn(progress());
        }
        if (comboStep == SwordCombo.CHARGE_INDEX) {
            return chargedSpinTurn(progress());
        }
        return 0f;
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
        return bladeItemPoint(pose, rootYawRad, bodyYawDeg, playerPos, 0f, 1f, 0f);
    }

    /** Точка КИСТИ (origin предмета в руке) в мировых координатах — для сферы
     *  заряда «в руках, где меч» (а не на кончике клинка). */
    public static Vec3d bladeHandWorld(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos) {
        return bladeItemPoint(pose, rootYawRad, bodyYawDeg, playerPos, 0f, 0f, 0f);
    }

    /** Промаршировать точку (lx, ly, lz) предмета через ту же цепочку, что у
     *  видимого меча: плечо->рука->display handheld->грип->лезвие. */
    private static Vec3d bladeItemPoint(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos,
                                        float lx, float ly, float lz) {
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
        Vector3f tip = m.transformPosition(new Vector3f(lx, ly, lz), new Vector3f());
        return new Vec3d(playerPos.x + tip.x, playerPos.y + tip.y, playerPos.z + tip.z);
    }

    /** Очистить трейл разреза (когда self-body выключен или вышел из свинга). */
    public static void clearSlashTrail() {
        slashTrail.clear();
    }



    /** Наклон всего тела в удар (root.pitch): нарастает к пику скорости
     *  (мгновенный удар, ~0.12 клипа) и спадает к концу. Удар 3 (разворот) —
     *  без наклона. Углы малые (6..10°) — пивот root на уровне шеи, ноги
     *  не отрываются. */
    private static final float[] ROOT_LEAN_DEG = {6f, 8f, 0f, 6f, 10f, 0f};

    private static float rootLean(float p) {
        if (comboStep < 0 || comboStep == 2 || comboStep == SwordCombo.CHARGE_INDEX) {
            return 0f;
        }
        float peak = 0.10f;
        float up = MathHelper.clamp(p / peak, 0f, 1f);
        float down = 1f - MathHelper.clamp((p - peak) / 0.22f, 0f, 1f);
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

    /** Прогресс полного оборота разворота 0..1: разворот идёт вместе с
     *  сжатым замахом и завершается к мгновенному удару (~0.16), дальше —
     *  стабилизация (E_IN_OUT_CUBIC). */
    private static float spinTurn(float p) {
        float u = MathHelper.clamp(p / 0.16f, 0f, 1f);
        return (float) (-Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
    }

    /** Прогресс оборота заряженного спина 0..1: полный круг к ~0.25 клипа
     *  (урон на 0.077 — в начале орбиты, дальше клинок продолжает круг),
     *  затем стабилизация (E_IN_OUT_CUBIC). */
    private static float chargedSpinTurn(float p) {
        float u = MathHelper.clamp(p / 0.25f, 0f, 1f);
        return (float) (-Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
    }

    /** Точка «шерстяной дуги» на сфере спина: θ — долгота (1..6 оборотов),
     *  φ — широта с некратным «плетением» (0.618 — золотое сечение), чтобы
     *  витки не ложились друг на друга; вся «сфера параллелей» наклонена
     *  на случайный угол — как нити шерстяного клубка. */
    private static Vec3d wrapPoint(int i) {
        double t = i / (double) Math.max(1, wrapTotal);
        double theta = Math.PI * 2.0 * wrapTurns * t;
        double phi = 0.9 * Math.sin(theta * 0.618);
        double x0 = Math.cos(phi) * Math.cos(theta);
        double y0 = Math.sin(phi);
        double z0 = Math.cos(phi) * Math.sin(theta);
        double cy = Math.cos(wrapTiltYaw);
        double sy = Math.sin(wrapTiltYaw);
        double cx = Math.cos(wrapTiltPitch);
        double sx = Math.sin(wrapTiltPitch);
        double x = x0 * cy + z0 * sy;
        double z = -x0 * sy + z0 * cy;
        double y = y0 * cx - z * sx;
        z = y0 * sx + z * cx;
        return wrapCenter.add(x * wrapRadius, y * wrapRadius, z * wrapRadius);
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

    /** Поза заряда (удержание ЛКМ): клинок поднят вверх-вперёд обеими руками,
     *  корпус чуть назад — готовность к спину. Голова — NaN (смотрит вперёд). */
    private static final Pose CHARGE_POSE = new Pose(
            (float) Math.toRadians(-20f), (float) Math.toRadians(-98f), (float) Math.toRadians(-10f),
            0f, (float) Math.toRadians(-72f), (float) Math.toRadians(-6f),
            (float) Math.toRadians(-6f), (float) Math.toRadians(4f), 0f,
            Float.NaN, Float.NaN, Float.NaN,
            0f, (float) Math.toRadians(-8f), 0f,
            0f, (float) Math.toRadians(6f), 0f);

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
    private static final Pose hit1_00 = new Pose(-0.261799f, -0.610865f, -0.139626f, 0.000000f, -0.174533f, 0.000000f, 0.052360f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.087266f, 0.000000f, 0.000000f, 0.087266f, 0.000000f);
    private static final Pose hit1_01 = new Pose(-0.392699f, -0.741765f, -0.174533f, 0.000000f, -0.305433f, -0.069813f, 0.078540f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.148353f, 0.000000f, 0.000000f, 0.130900f, 0.000000f);
    private static final Pose hit1_02 = new Pose(-0.523599f, -0.872665f, -0.209440f, 0.000000f, -0.436332f, -0.139626f, 0.104720f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.209440f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit1_03 = new Pose(-0.491033f, -0.937796f, -0.186643f, 0.000000f, -0.501464f, -0.162422f, 0.111233f, 0.038163f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.235492f, 0.000000f, 0.000000f, 0.190816f, 0.000000f);
    private static final Pose hit1_04 = new Pose(-0.348455f, -1.221643f, -0.087005f, 0.000000f, -0.785573f, -0.261712f, 0.139574f, 0.052377f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.348455f, 0.000000f, 0.000000f, 0.261363f, 0.000000f);
    private static final Pose hit1_05 = new Pose(-0.272708f, -1.210822f, -0.054542f, 0.000000f, -0.807215f, -0.250891f, 0.133081f, 0.054542f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.272708f, 0.000000f, 0.000000f, 0.207258f, 0.000000f);
    private static final Pose hit1_06 = new Pose(0.096255f, -1.158113f, 0.103585f, 0.000000f, -0.912633f, -0.198182f, 0.101456f, 0.065083f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.096255f, 0.000000f, 0.000000f, -0.056287f, 0.000000f);
    private static final Pose hit1_07 = new Pose(0.265988f, -1.133068f, 0.175231f, 0.000000f, -0.957837f, -0.172439f, 0.086289f, 0.069674f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.263196f, 0.000000f, 0.000000f, -0.175231f, 0.000000f);
    private static final Pose hit1_08 = new Pose(0.315013f, -1.116726f, 0.183402f, 0.000000f, -0.933325f, -0.147926f, 0.074850f, 0.068039f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.279537f, 0.000000f, 0.000000f, -0.183402f, 0.000000f);
    private static final Pose hit1_09 = new Pose(0.468291f, -1.065633f, 0.208948f, 0.000000f, -0.856685f, -0.071287f, 0.039085f, 0.062930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.330630f, 0.000000f, 0.000000f, -0.208948f, 0.000000f);
    private static final Pose hit1_10 = new Pose(0.785398f, -0.959931f, 0.261799f, 0.000000f, -0.698132f, 0.087266f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.261799f, 0.000000f);
    private static final Pose hit1_11 = new Pose(0.846317f, -0.917288f, 0.243524f, 0.000000f, -0.606754f, 0.105542f, -0.053182f, 0.046268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.466792f, 0.000000f, 0.000000f, -0.280075f, 0.000000f);
    private static final Pose hit1_12 = new Pose(0.891100f, -0.885939f, 0.230089f, 0.000000f, -0.539578f, 0.118977f, -0.066617f, 0.041790f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.489183f, 0.000000f, 0.000000f, -0.293510f, 0.000000f);
    private static final Pose hit1_13 = new Pose(0.922232f, -0.864147f, 0.220749f, 0.000000f, -0.492881f, 0.128317f, -0.075957f, 0.038676f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.504749f, 0.000000f, 0.000000f, -0.302850f, 0.000000f);
    private static final Pose hit1_14 = new Pose(0.942193f, -0.850174f, 0.214761f, 0.000000f, -0.462939f, 0.134305f, -0.081945f, 0.036680f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.514730f, 0.000000f, 0.000000f, -0.308838f, 0.000000f);
    private static final Pose hit1_15 = new Pose(0.953467f, -0.842283f, 0.211379f, 0.000000f, -0.446029f, 0.137687f, -0.085327f, 0.035553f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.520367f, 0.000000f, 0.000000f, -0.312220f, 0.000000f);
    private static final Pose hit1_16 = new Pose(0.958535f, -0.838735f, 0.209858f, 0.000000f, -0.438427f, 0.139207f, -0.086848f, 0.035046f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.522901f, 0.000000f, 0.000000f, -0.313740f, 0.000000f);
    private static final Pose hit1_17 = new Pose(0.959879f, -0.837794f, 0.209455f, 0.000000f, -0.436410f, 0.139611f, -0.087251f, 0.034912f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523573f, 0.000000f, 0.000000f, -0.314144f, 0.000000f);
    private static final Pose hit1_18 = new Pose(0.942506f, -0.822822f, 0.199482f, 0.000000f, -0.418907f, 0.132159f, -0.084777f, 0.033662f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.518620f, 0.000000f, 0.000000f, -0.306691f, 0.000000f);
    private static final Pose hit1_19 = new Pose(0.912788f, -0.797349f, 0.182500f, 0.000000f, -0.389189f, 0.119422f, -0.080532f, 0.031539f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.510129f, 0.000000f, 0.000000f, -0.293955f, 0.000000f);
    private static final Pose hit1_20 = new Pose(0.889300f, -0.777217f, 0.169079f, 0.000000f, -0.365701f, 0.109356f, -0.077176f, 0.029861f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.503418f, 0.000000f, 0.000000f, -0.283889f, 0.000000f);
    private static final Pose hit1_21 = new Pose(0.871310f, -0.761797f, 0.158799f, 0.000000f, -0.347711f, 0.101646f, -0.074606f, 0.028576f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.498278f, 0.000000f, 0.000000f, -0.276179f, 0.000000f);
    private static final Pose hit1_22 = new Pose(0.858085f, -0.750461f, 0.151242f, 0.000000f, -0.334486f, 0.095978f, -0.072717f, 0.027632f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.494500f, 0.000000f, 0.000000f, -0.270511f, 0.000000f);
    private static final Pose hit1_23 = new Pose(0.848891f, -0.742581f, 0.145988f, 0.000000f, -0.325292f, 0.092038f, -0.071404f, 0.026975f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.491873f, 0.000000f, 0.000000f, -0.266571f, 0.000000f);
    private static final Pose hit1_24 = new Pose(0.842996f, -0.737528f, 0.142620f, 0.000000f, -0.319397f, 0.089511f, -0.070561f, 0.026554f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.490189f, 0.000000f, 0.000000f, -0.264044f, 0.000000f);
    private static final Pose hit1_25 = new Pose(0.839667f, -0.734675f, 0.140717f, 0.000000f, -0.316068f, 0.088085f, -0.070086f, 0.026316f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.489238f, 0.000000f, 0.000000f, -0.262618f, 0.000000f);
    private static final Pose hit1_26 = new Pose(0.838170f, -0.733392f, 0.139862f, 0.000000f, -0.314572f, 0.087443f, -0.069872f, 0.026209f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488810f, 0.000000f, 0.000000f, -0.261976f, 0.000000f);
    private static final Pose hit1_27 = new Pose(0.837773f, -0.733051f, 0.139635f, 0.000000f, -0.314175f, 0.087273f, -0.069815f, 0.026181f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488697f, 0.000000f, 0.000000f, -0.261806f, 0.000000f);
    private static final Pose hit1_28 = new Pose(0.816002f, -0.727599f, 0.131468f, 0.000000f, -0.306001f, 0.073669f, -0.067094f, 0.024820f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.480534f, 0.000000f, 0.000000f, -0.253641f, 0.000000f);
    private static final Pose hit1_29 = new Pose(0.779010f, -0.718351f, 0.117596f, 0.000000f, -0.292129f, 0.050549f, -0.062470f, 0.022508f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.466662f, 0.000000f, 0.000000f, -0.239769f, 0.000000f);
    private static final Pose hit1_30 = new Pose(0.749961f, -0.711089f, 0.106702f, 0.000000f, -0.281235f, 0.032393f, -0.058839f, 0.020693f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.455768f, 0.000000f, 0.000000f, -0.228875f, 0.000000f);
    private static final Pose hit1_31 = new Pose(0.727953f, -0.705587f, 0.098450f, 0.000000f, -0.272982f, 0.018638f, -0.056088f, 0.019317f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.447515f, 0.000000f, 0.000000f, -0.220623f, 0.000000f);
    private static final Pose hit1_32 = new Pose(0.712086f, -0.701620f, 0.092499f, 0.000000f, -0.267032f, 0.008722f, -0.054104f, 0.018325f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.441565f, 0.000000f, 0.000000f, -0.214672f, 0.000000f);
    private static final Pose hit1_33 = new Pose(0.701459f, -0.698964f, 0.088514f, 0.000000f, -0.263047f, 0.002080f, -0.052776f, 0.017661f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.437580f, 0.000000f, 0.000000f, -0.210687f, 0.000000f);
    private static final Pose hit1_34 = new Pose(0.695172f, -0.697392f, 0.086157f, 0.000000f, -0.260690f, -0.001850f, -0.051990f, 0.017268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.435222f, 0.000000f, 0.000000f, -0.208330f, 0.000000f);
    private static final Pose hit1_35 = new Pose(0.692323f, -0.696680f, 0.085088f, 0.000000f, -0.259621f, -0.003630f, -0.051634f, 0.017090f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434154f, 0.000000f, 0.000000f, -0.207261f, 0.000000f);
    private static final Pose hit1_36 = new Pose(0.692012f, -0.696602f, 0.084972f, 0.000000f, -0.259505f, -0.003825f, -0.051595f, 0.017071f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434038f, 0.000000f, 0.000000f, -0.207145f, 0.000000f);
    private static final Pose hit1_37 = new Pose(0.693338f, -0.696933f, 0.085469f, 0.000000f, -0.260002f, -0.002996f, -0.051761f, 0.017154f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434535f, 0.000000f, 0.000000f, -0.207642f, 0.000000f);
    private static final Pose hit1_38 = new Pose(0.695401f, -0.697449f, 0.086242f, 0.000000f, -0.260775f, -0.001707f, -0.052019f, 0.017283f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.435308f, 0.000000f, 0.000000f, -0.208415f, 0.000000f);
    private static final Pose hit1_39 = new Pose(0.697299f, -0.697923f, 0.086954f, 0.000000f, -0.261487f, -0.000521f, -0.052256f, 0.017401f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436020f, 0.000000f, 0.000000f, -0.209127f, 0.000000f);
    private static final Pose hit1_40 = new Pose(0.698132f, -0.698132f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.052360f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);

    private static final Pose hit2_00 = new Pose(0.698132f, -0.698132f, 0.087266f, 0.000000f, -0.261799f, 0.000000f, -0.052360f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit2_01 = new Pose(0.785398f, -0.829031f, 0.113446f, 0.000000f, -0.392699f, -0.043633f, -0.069813f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.375246f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit2_02 = new Pose(0.872665f, -0.959931f, 0.139626f, 0.000000f, -0.523599f, -0.087266f, -0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.314159f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit2_03 = new Pose(0.823816f, -1.025063f, 0.097291f, 0.000000f, -0.588731f, -0.110063f, -0.093780f, 0.038163f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.281593f, 0.000000f, 0.000000f, -0.107060f, 0.000000f);
    private static final Pose hit2_04 = new Pose(0.609993f, -1.308910f, -0.087441f, 0.000000f, -0.872752f, -0.209370f, -0.122138f, 0.052377f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.139347f, 0.000000f, 0.000000f, 0.035081f, 0.000000f);
    private static final Pose hit2_05 = new Pose(0.501782f, -1.298089f, -0.109083f, 0.000000f, -0.883573f, -0.200713f, -0.117810f, 0.054542f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.104720f, 0.000000f, 0.000000f, 0.056723f, 0.000000f);
    private static final Pose hit2_06 = new Pose(-0.025307f, -1.245380f, -0.214501f, 0.000000f, -0.936282f, -0.158546f, -0.096726f, 0.065083f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.063949f, 0.000000f, 0.000000f, 0.162141f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-0.267384f, -1.219636f, -0.261381f, 0.000000f, -0.957837f, -0.137811f, -0.086289f, 0.069674f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.141302f, 0.000000f, 0.000000f, 0.210277f, 0.000000f);
    private static final Pose hit2_08 = new Pose(-0.332750f, -1.195124f, -0.256478f, 0.000000f, -0.933325f, -0.116567f, -0.074850f, 0.068039f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.160912f, 0.000000f, 0.000000f, 0.220082f, 0.000000f);
    private static final Pose hit2_09 = new Pose(-0.537122f, -1.118485f, -0.241150f, 0.000000f, -0.856685f, -0.050147f, -0.039085f, 0.062930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.222223f, 0.000000f, 0.000000f, 0.250738f, 0.000000f);
    private static final Pose hit2_10 = new Pose(-0.959931f, -0.959931f, -0.209440f, 0.000000f, -0.698132f, 0.087266f, 0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.314159f, 0.000000f);
    private static final Pose hit2_11 = new Pose(-1.020850f, -0.899013f, -0.185072f, 0.000000f, -0.606754f, 0.105542f, 0.053182f, 0.046268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.379525f, 0.000000f, 0.000000f, 0.326343f, 0.000000f);
    private static final Pose hit2_12 = new Pose(-1.065633f, -0.854229f, -0.167159f, 0.000000f, -0.539578f, 0.118977f, 0.066617f, 0.041790f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.401917f, 0.000000f, 0.000000f, 0.335300f, 0.000000f);
    private static final Pose hit2_13 = new Pose(-1.096765f, -0.823097f, -0.154706f, 0.000000f, -0.492881f, 0.128317f, 0.075957f, 0.038676f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.417483f, 0.000000f, 0.000000f, 0.341526f, 0.000000f);
    private static final Pose hit2_14 = new Pose(-1.116726f, -0.803136f, -0.146721f, 0.000000f, -0.462939f, 0.134305f, 0.081945f, 0.036680f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.427463f, 0.000000f, 0.000000f, 0.345518f, 0.000000f);
    private static final Pose hit2_15 = new Pose(-1.128000f, -0.791862f, -0.142212f, 0.000000f, -0.446029f, 0.137687f, 0.085327f, 0.035553f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.433100f, 0.000000f, 0.000000f, 0.347773f, 0.000000f);
    private static final Pose hit2_16 = new Pose(-1.133068f, -0.786794f, -0.140185f, 0.000000f, -0.438427f, 0.139207f, 0.086848f, 0.035046f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.435634f, 0.000000f, 0.000000f, 0.348787f, 0.000000f);
    private static final Pose hit2_17 = new Pose(-1.134412f, -0.785450f, -0.139647f, 0.000000f, -0.436410f, 0.139611f, 0.087251f, 0.034912f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.436306f, 0.000000f, 0.000000f, 0.349056f, 0.000000f);
    private static final Pose hit2_18 = new Pose(-1.122018f, -0.772952f, -0.132159f, 0.000000f, -0.418907f, 0.132159f, 0.084777f, 0.033662f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.428864f, 0.000000f, 0.000000f, 0.344087f, 0.000000f);
    private static final Pose hit2_19 = new Pose(-1.100790f, -0.751724f, -0.119422f, 0.000000f, -0.389189f, 0.119422f, 0.080532f, 0.031539f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.416128f, 0.000000f, 0.000000f, 0.335596f, 0.000000f);
    private static final Pose hit2_20 = new Pose(-1.084013f, -0.734947f, -0.109356f, 0.000000f, -0.365701f, 0.109356f, 0.077176f, 0.029861f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.406062f, 0.000000f, 0.000000f, 0.328885f, 0.000000f);
    private static final Pose hit2_21 = new Pose(-1.071163f, -0.722097f, -0.101646f, 0.000000f, -0.347711f, 0.101646f, 0.074606f, 0.028576f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.398352f, 0.000000f, 0.000000f, 0.323745f, 0.000000f);
    private static final Pose hit2_22 = new Pose(-1.061717f, -0.712651f, -0.095978f, 0.000000f, -0.334486f, 0.095978f, 0.072717f, 0.027632f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.392684f, 0.000000f, 0.000000f, 0.319967f, 0.000000f);
    private static final Pose hit2_23 = new Pose(-1.055150f, -0.706084f, -0.092038f, 0.000000f, -0.325292f, 0.092038f, 0.071404f, 0.026975f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.388744f, 0.000000f, 0.000000f, 0.317340f, 0.000000f);
    private static final Pose hit2_24 = new Pose(-1.050939f, -0.701873f, -0.089511f, 0.000000f, -0.319397f, 0.089511f, 0.070561f, 0.026554f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.386217f, 0.000000f, 0.000000f, 0.315656f, 0.000000f);
    private static final Pose hit2_25 = new Pose(-1.048561f, -0.699495f, -0.088085f, 0.000000f, -0.316068f, 0.088085f, 0.070086f, 0.026316f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.384791f, 0.000000f, 0.000000f, 0.314705f, 0.000000f);
    private static final Pose hit2_26 = new Pose(-1.047492f, -0.698426f, -0.087443f, 0.000000f, -0.314572f, 0.087443f, 0.069872f, 0.026209f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.384149f, 0.000000f, 0.000000f, 0.314277f, 0.000000f);
    private static final Pose hit2_27 = new Pose(-1.047208f, -0.698143f, -0.087273f, 0.000000f, -0.314175f, 0.087273f, 0.069815f, 0.026181f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.383979f, 0.000000f, 0.000000f, 0.314164f, 0.000000f);
    private static final Pose hit2_28 = new Pose(-1.033600f, -0.692693f, -0.081828f, 0.000000f, -0.306001f, 0.073669f, 0.067094f, 0.024820f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.378533f, 0.000000f, 0.000000f, 0.306001f, 0.000000f);
    private static final Pose hit2_29 = new Pose(-1.010480f, -0.683445f, -0.072580f, 0.000000f, -0.292129f, 0.050549f, 0.062470f, 0.022508f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.369286f, 0.000000f, 0.000000f, 0.292129f, 0.000000f);
    private static final Pose hit2_30 = new Pose(-0.992324f, -0.676182f, -0.065317f, 0.000000f, -0.281235f, 0.032393f, 0.058839f, 0.020693f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.362023f, 0.000000f, 0.000000f, 0.281235f, 0.000000f);
    private static final Pose hit2_31 = new Pose(-0.978570f, -0.670680f, -0.059815f, 0.000000f, -0.272982f, 0.018638f, 0.056088f, 0.019317f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.356521f, 0.000000f, 0.000000f, 0.272982f, 0.000000f);
    private static final Pose hit2_32 = new Pose(-0.968653f, -0.666714f, -0.055849f, 0.000000f, -0.267032f, 0.008722f, 0.054104f, 0.018325f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.352554f, 0.000000f, 0.000000f, 0.267032f, 0.000000f);
    private static final Pose hit2_33 = new Pose(-0.962011f, -0.664057f, -0.053192f, 0.000000f, -0.263047f, 0.002080f, 0.052776f, 0.017661f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349898f, 0.000000f, 0.000000f, 0.263047f, 0.000000f);
    private static final Pose hit2_34 = new Pose(-0.958081f, -0.662485f, -0.051620f, 0.000000f, -0.260690f, -0.001850f, 0.051990f, 0.017268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.348326f, 0.000000f, 0.000000f, 0.260690f, 0.000000f);
    private static final Pose hit2_35 = new Pose(-0.956301f, -0.661773f, -0.050908f, 0.000000f, -0.259621f, -0.003630f, 0.051634f, 0.017090f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.347614f, 0.000000f, 0.000000f, 0.259621f, 0.000000f);
    private static final Pose hit2_36 = new Pose(-0.956106f, -0.661695f, -0.050830f, 0.000000f, -0.259505f, -0.003825f, 0.051595f, 0.017071f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.347536f, 0.000000f, 0.000000f, 0.259505f, 0.000000f);
    private static final Pose hit2_37 = new Pose(-0.956935f, -0.662027f, -0.051162f, 0.000000f, -0.260002f, -0.002996f, 0.051761f, 0.017154f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.347868f, 0.000000f, 0.000000f, 0.260002f, 0.000000f);
    private static final Pose hit2_38 = new Pose(-0.958224f, -0.662542f, -0.051677f, 0.000000f, -0.260775f, -0.001707f, 0.052019f, 0.017283f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.348383f, 0.000000f, 0.000000f, 0.260775f, 0.000000f);
    private static final Pose hit2_39 = new Pose(-0.959411f, -0.663017f, -0.052152f, 0.000000f, -0.261487f, -0.000521f, 0.052256f, 0.017401f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.348858f, 0.000000f, 0.000000f, 0.261487f, 0.000000f);
    private static final Pose hit2_40 = new Pose(-0.959931f, -0.663225f, -0.052360f, 0.000000f, -0.261799f, 0.000000f, 0.052360f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.261799f, 0.000000f);

    private static final Pose hit3_00 = new Pose(-0.959931f, -0.663225f, -0.052360f, 0.000000f, -0.261799f, 0.000000f, 0.052360f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.261799f, 0.000000f);
    private static final Pose hit3_01 = new Pose(-0.872665f, -0.855211f, -0.095993f, 0.000000f, -0.436332f, -0.087266f, 0.069813f, -0.008727f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.305433f, 0.000000f, 0.000000f, 0.218166f, 0.000000f);
    private static final Pose hit3_02 = new Pose(-0.785398f, -1.047198f, -0.139626f, 0.000000f, -0.610865f, -0.174533f, 0.087266f, -0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit3_03 = new Pose(-0.768643f, -1.091878f, -0.136275f, 0.000000f, -0.633205f, -0.180118f, 0.088383f, -0.037141f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.250629f, 0.000000f, 0.000000f, 0.168948f, 0.000000f);
    private static final Pose hit3_04 = new Pose(-0.651357f, -1.404641f, -0.112818f, 0.000000f, -0.789587f, -0.219213f, 0.096203f, -0.052779f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.172439f, 0.000000f, 0.000000f, 0.129852f, 0.000000f);
    private static final Pose hit3_05 = new Pose(-0.522387f, -1.748561f, -0.087105f, 0.000000f, -0.961143f, -0.261557f, 0.104801f, -0.069732f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.086458f, 0.000000f, 0.000000f, 0.086458f, 0.000000f);
    private static final Pose hit3_06 = new Pose(-0.490874f, -1.832596f, -0.082903f, 0.000000f, -0.992656f, -0.255254f, 0.106901f, -0.067632f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.065450f, 0.000000f, 0.000000f, 0.065450f, 0.000000f);
    private static final Pose hit3_07 = new Pose(-0.372095f, -2.149341f, -0.067066f, 0.000000f, -1.111435f, -0.231499f, 0.114820f, -0.059713f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.013736f, 0.000000f, 0.000000f, -0.013736f, 0.000000f);
    private static final Pose hit3_08 = new Pose(-0.262006f, -2.442634f, -0.052567f, 0.000000f, -1.221317f, -0.209150f, 0.122049f, -0.052153f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.087680f, 0.000000f, 0.000000f, -0.087473f, 0.000000f);
    private static final Pose hit3_09 = new Pose(-0.267384f, -2.421121f, -0.057945f, 0.000000f, -1.210560f, -0.201620f, 0.118822f, -0.046775f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.098437f, 0.000000f, 0.000000f, -0.092852f, 0.000000f);
    private static final Pose hit3_10 = new Pose(-0.287656f, -2.340034f, -0.078217f, 0.000000f, -1.170017f, -0.173240f, 0.106659f, -0.026503f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.138980f, 0.000000f, 0.000000f, -0.113123f, 0.000000f);
    private static final Pose hit3_11 = new Pose(-0.306480f, -2.264739f, -0.097040f, 0.000000f, -1.132370f, -0.146887f, 0.095365f, -0.007679f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.176627f, 0.000000f, 0.000000f, -0.131947f, 0.000000f);
    private static final Pose hit3_12 = new Pose(-0.332750f, -2.159657f, -0.123311f, 0.000000f, -1.079829f, -0.110108f, 0.079603f, 0.018591f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.229168f, 0.000000f, 0.000000f, -0.158217f, 0.000000f);
    private static final Pose hit3_13 = new Pose(-0.398013f, -1.963870f, -0.129837f, 0.000000f, -0.981935f, -0.061162f, 0.076339f, 0.038170f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.294431f, 0.000000f, 0.000000f, -0.190849f, 0.000000f);
    private static final Pose hit3_14 = new Pose(-0.476824f, -1.753707f, -0.114075f, 0.000000f, -0.876853f, -0.019129f, 0.086848f, 0.043424f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.346971f, 0.000000f, 0.000000f, -0.217119f, 0.000000f);
    private static final Pose hit3_15 = new Pose(-0.533295f, -1.603117f, -0.102781f, 0.000000f, -0.801559f, 0.010989f, 0.094377f, 0.047189f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.384619f, 0.000000f, 0.000000f, -0.235943f, 0.000000f);
    private static final Pose hit3_16 = new Pose(-0.571149f, -1.502173f, -0.095210f, 0.000000f, -0.751086f, 0.031178f, 0.099424f, 0.049712f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.409855f, 0.000000f, 0.000000f, -0.248561f, 0.000000f);
    private static final Pose hit3_17 = new Pose(-0.594110f, -1.440944f, -0.090617f, 0.000000f, -0.720472f, 0.043424f, 0.102486f, 0.051243f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.425162f, 0.000000f, 0.000000f, -0.256214f, 0.000000f);
    private static final Pose hit3_18 = new Pose(-0.605901f, -1.409502f, -0.088259f, 0.000000f, -0.704751f, 0.049712f, 0.104058f, 0.052029f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.433023f, 0.000000f, 0.000000f, -0.260145f, 0.000000f);
    private static final Pose hit3_19 = new Pose(-0.610245f, -1.397918f, -0.087391f, 0.000000f, -0.698959f, 0.052029f, 0.104637f, 0.052319f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.435919f, 0.000000f, 0.000000f, -0.261593f, 0.000000f);
    private static final Pose hit3_20 = new Pose(-0.610865f, -1.396263f, -0.087266f, 0.000000f, -0.698132f, 0.052360f, 0.104720f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.261799f, 0.000000f);
    private static final Pose hit3_21 = new Pose(-0.681813f, -1.254368f, -0.077807f, 0.000000f, -0.627184f, 0.061820f, 0.114179f, 0.047630f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.459982f, 0.000000f, 0.000000f, -0.275989f, 0.000000f);
    private static final Pose hit3_22 = new Pose(-0.738623f, -1.140747f, -0.070232f, 0.000000f, -0.570374f, 0.069394f, 0.121754f, 0.043843f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.478918f, 0.000000f, 0.000000f, -0.287351f, 0.000000f);
    private static final Pose hit3_23 = new Pose(-0.782867f, -1.052259f, -0.064333f, 0.000000f, -0.526130f, 0.075294f, 0.127653f, 0.040893f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.493666f, 0.000000f, 0.000000f, -0.296200f, 0.000000f);
    private static final Pose hit3_24 = new Pose(-0.816116f, -0.985762f, -0.059900f, 0.000000f, -0.492881f, 0.079727f, 0.132087f, 0.038676f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.504749f, 0.000000f, 0.000000f, -0.302850f, 0.000000f);
    private static final Pose hit3_25 = new Pose(-0.839940f, -0.938114f, -0.056723f, 0.000000f, -0.469057f, 0.082903f, 0.135263f, 0.037088f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.512690f, 0.000000f, 0.000000f, -0.307614f, 0.000000f);
    private static final Pose hit3_26 = new Pose(-0.855909f, -0.906175f, -0.054594f, 0.000000f, -0.453087f, 0.085032f, 0.137392f, 0.036024f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.518014f, 0.000000f, 0.000000f, -0.310808f, 0.000000f);
    private static final Pose hit3_27 = new Pose(-0.865596f, -0.886802f, -0.053302f, 0.000000f, -0.443401f, 0.086324f, 0.138684f, 0.035378f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.521243f, 0.000000f, 0.000000f, -0.312746f, 0.000000f);
    private static final Pose hit3_28 = new Pose(-0.870570f, -0.876853f, -0.052639f, 0.000000f, -0.438427f, 0.086987f, 0.139347f, 0.035046f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.522901f, 0.000000f, 0.000000f, -0.313740f, 0.000000f);
    private static final Pose hit3_29 = new Pose(-0.872403f, -0.873188f, -0.052395f, 0.000000f, -0.436594f, 0.087232f, 0.139591f, 0.034924f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523512f, 0.000000f, 0.000000f, -0.314107f, 0.000000f);
    private static final Pose hit3_30 = new Pose(-0.872665f, -0.872665f, -0.052360f, 0.000000f, -0.436332f, 0.087266f, 0.139626f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.314159f, 0.000000f);
    private static final Pose hit3_31 = new Pose(-0.841240f, -0.841240f, -0.046075f, 0.000000f, -0.392338f, 0.055842f, 0.133341f, 0.031764f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.511029f, 0.000000f, 0.000000f, -0.295304f, 0.000000f);
    private static final Pose hit3_32 = new Pose(-0.817791f, -0.817791f, -0.041385f, 0.000000f, -0.359510f, 0.032393f, 0.128652f, 0.029419f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.501650f, 0.000000f, 0.000000f, -0.281235f, 0.000000f);
    private static final Pose hit3_33 = new Pose(-0.801220f, -0.801220f, -0.038071f, 0.000000f, -0.336309f, 0.015821f, 0.125337f, 0.027762f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.495021f, 0.000000f, 0.000000f, -0.271292f, 0.000000f);
    private static final Pose hit3_34 = new Pose(-0.790425f, -0.790425f, -0.035912f, 0.000000f, -0.321196f, 0.005027f, 0.123178f, 0.026683f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.490703f, 0.000000f, 0.000000f, -0.264815f, 0.000000f);
    private static final Pose hit3_35 = new Pose(-0.784307f, -0.784307f, -0.034688f, 0.000000f, -0.312632f, -0.001091f, 0.121955f, 0.026071f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488256f, 0.000000f, 0.000000f, -0.261145f, 0.000000f);
    private static final Pose hit3_36 = new Pose(-0.781768f, -0.781768f, -0.034181f, 0.000000f, -0.309077f, -0.003630f, 0.121447f, 0.025817f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.487240f, 0.000000f, 0.000000f, -0.259621f, 0.000000f);
    private static final Pose hit3_37 = new Pose(-0.781707f, -0.781707f, -0.034168f, 0.000000f, -0.308991f, -0.003691f, 0.121435f, 0.025811f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.487216f, 0.000000f, 0.000000f, -0.259585f, 0.000000f);
    private static final Pose hit3_38 = new Pose(-0.783025f, -0.783025f, -0.034432f, 0.000000f, -0.310836f, -0.002374f, 0.121698f, 0.025943f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.487743f, 0.000000f, 0.000000f, -0.260375f, 0.000000f);
    private static final Pose hit3_39 = new Pose(-0.784621f, -0.784621f, -0.034751f, 0.000000f, -0.313072f, -0.000777f, 0.122018f, 0.026102f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488382f, 0.000000f, 0.000000f, -0.261333f, 0.000000f);
    private static final Pose hit3_40 = new Pose(-0.785398f, -0.785398f, -0.034907f, 0.000000f, -0.314159f, 0.000000f, 0.122173f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488692f, 0.000000f, 0.000000f, -0.261799f, 0.000000f);

    private static final Pose hit4_00 = new Pose(-0.785398f, -0.785398f, -0.034907f, 0.000000f, -0.314159f, 0.000000f, 0.122173f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.488692f, 0.000000f, 0.000000f, -0.261799f, 0.000000f);
    private static final Pose hit4_01 = new Pose(-0.872665f, -1.003564f, -0.061087f, 0.000000f, -0.462512f, -0.087266f, 0.139626f, 0.030543f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.418879f, 0.000000f, 0.000000f, -0.218166f, 0.000000f);
    private static final Pose hit4_02 = new Pose(-0.959931f, -1.221730f, -0.087266f, 0.000000f, -0.610865f, -0.174533f, 0.157080f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit4_03 = new Pose(-0.911082f, -1.351994f, -0.080753f, 0.000000f, -0.675997f, -0.190816f, 0.163593f, 0.038163f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.316500f, 0.000000f, 0.000000f, -0.141967f, 0.000000f);
    private static final Pose hit4_04 = new Pose(-0.697259f, -1.920211f, -0.052220f, 0.000000f, -0.960106f, -0.261712f, 0.191934f, 0.052377f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174271f, 0.000000f, 0.000000f, 0.000175f, 0.000000f);
    private static final Pose hit4_05 = new Pose(-0.589049f, -1.963495f, -0.034907f, 0.000000f, -0.981748f, -0.250891f, 0.185441f, 0.054542f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.141808f, 0.000000f, 0.000000f, 0.021817f, 0.000000f);
    private static final Pose hit4_06 = new Pose(-0.061959f, -2.174331f, 0.049428f, 0.000000f, -1.087166f, -0.198182f, 0.153816f, 0.065083f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.016319f, 0.000000f, 0.000000f, 0.127235f, 0.000000f);
    private static final Pose hit4_07 = new Pose(0.180118f, -2.261947f, 0.088244f, 0.000000f, -1.131671f, -0.172439f, 0.138230f, 0.069674f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.085172f, 0.000000f, 0.000000f, 0.172439f, 0.000000f);
    private static final Pose hit4_08 = new Pose(0.245484f, -2.180239f, 0.099683f, 0.000000f, -1.098989f, -0.147926f, 0.121889f, 0.068039f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.060660f, 0.000000f, 0.000000f, 0.147926f, 0.000000f);
    private static final Pose hit4_09 = new Pose(0.449855f, -1.924775f, 0.135448f, 0.000000f, -0.996803f, -0.071287f, 0.070796f, 0.062930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.015979f, 0.000000f, 0.000000f, 0.071287f, 0.000000f);
    private static final Pose hit4_10 = new Pose(0.872665f, -1.396263f, 0.209440f, 0.000000f, -0.785398f, 0.087266f, -0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit4_11 = new Pose(0.933583f, -1.274426f, 0.197256f, 0.000000f, -0.694020f, 0.105542f, -0.059274f, 0.046268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.235451f, 0.000000f, 0.000000f, -0.129909f, 0.000000f);
    private static final Pose hit4_12 = new Pose(0.978367f, -1.184859f, 0.188299f, 0.000000f, -0.626845f, 0.118977f, -0.077188f, 0.041790f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.280235f, 0.000000f, 0.000000f, -0.161258f, 0.000000f);
    private static final Pose hit4_13 = new Pose(1.009498f, -1.122596f, 0.182073f, 0.000000f, -0.580147f, 0.128317f, -0.089640f, 0.038676f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.311367f, 0.000000f, 0.000000f, -0.183050f, 0.000000f);
    private static final Pose hit4_14 = new Pose(1.029460f, -1.082673f, 0.178080f, 0.000000f, -0.550205f, 0.134305f, -0.097625f, 0.036680f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.331328f, 0.000000f, 0.000000f, -0.197023f, 0.000000f);
    private static final Pose hit4_15 = new Pose(1.040733f, -1.060126f, 0.175826f, 0.000000f, -0.533295f, 0.137687f, -0.102134f, 0.035553f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.342602f, 0.000000f, 0.000000f, -0.204915f, 0.000000f);
    private static final Pose hit4_16 = new Pose(1.045801f, -1.049990f, 0.174812f, 0.000000f, -0.525693f, 0.139207f, -0.104161f, 0.035046f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.347670f, 0.000000f, 0.000000f, -0.208462f, 0.000000f);
    private static final Pose hit4_17 = new Pose(1.047146f, -1.047301f, 0.174543f, 0.000000f, -0.523676f, 0.139611f, -0.104699f, 0.034912f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349014f, 0.000000f, 0.000000f, -0.209403f, 0.000000f);
    private static final Pose hit4_18 = new Pose(1.034751f, -1.022305f, 0.164576f, 0.000000f, -0.498706f, 0.132159f, -0.102230f, 0.033662f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.361512f, 0.000000f, 0.000000f, -0.216907f, 0.000000f);
    private static final Pose hit4_19 = new Pose(1.013524f, -0.979850f, 0.147594f, 0.000000f, -0.456251f, 0.119422f, -0.097985f, 0.031539f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.382740f, 0.000000f, 0.000000f, -0.229644f, 0.000000f);
    private static final Pose hit4_20 = new Pose(0.996747f, -0.946296f, 0.134172f, 0.000000f, -0.422697f, 0.109356f, -0.094630f, 0.029861f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.399517f, 0.000000f, 0.000000f, -0.239710f, 0.000000f);
    private static final Pose hit4_21 = new Pose(0.983897f, -0.920596f, 0.123892f, 0.000000f, -0.396997f, 0.101646f, -0.092060f, 0.028576f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.412367f, 0.000000f, 0.000000f, -0.247420f, 0.000000f);
    private static final Pose hit4_22 = new Pose(0.974450f, -0.901703f, 0.116335f, 0.000000f, -0.378104f, 0.095978f, -0.090170f, 0.027632f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.421813f, 0.000000f, 0.000000f, -0.253088f, 0.000000f);
    private static final Pose hit4_23 = new Pose(0.967883f, -0.888569f, 0.111081f, 0.000000f, -0.364970f, 0.092038f, -0.088857f, 0.026975f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.428380f, 0.000000f, 0.000000f, -0.257028f, 0.000000f);
    private static final Pose hit4_24 = new Pose(0.963673f, -0.880148f, 0.107713f, 0.000000f, -0.356549f, 0.089511f, -0.088015f, 0.026554f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.432591f, 0.000000f, 0.000000f, -0.259554f, 0.000000f);
    private static final Pose hit4_25 = new Pose(0.961295f, -0.875392f, 0.105811f, 0.000000f, -0.351793f, 0.088085f, -0.087539f, 0.026316f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.434969f, 0.000000f, 0.000000f, -0.260981f, 0.000000f);
    private static final Pose hit4_26 = new Pose(0.960226f, -0.873254f, 0.104955f, 0.000000f, -0.349655f, 0.087443f, -0.087325f, 0.026209f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436038f, 0.000000f, 0.000000f, -0.261623f, 0.000000f);
    private static final Pose hit4_27 = new Pose(0.959942f, -0.872686f, 0.104728f, 0.000000f, -0.349088f, 0.087273f, -0.087269f, 0.026181f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436321f, 0.000000f, 0.000000f, -0.261793f, 0.000000f);
    private static final Pose hit4_28 = new Pose(0.946334f, -0.859067f, 0.099281f, 0.000000f, -0.335468f, 0.073669f, -0.084547f, 0.024820f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.428174f, 0.000000f, 0.000000f, -0.253641f, 0.000000f);
    private static final Pose hit4_29 = new Pose(0.923214f, -0.835947f, 0.090033f, 0.000000f, -0.312349f, 0.050549f, -0.079923f, 0.022508f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.414302f, 0.000000f, 0.000000f, -0.239769f, 0.000000f);
    private static final Pose hit4_30 = new Pose(0.905058f, -0.817791f, 0.082770f, 0.000000f, -0.294193f, 0.032393f, -0.076292f, 0.020693f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.403408f, 0.000000f, 0.000000f, -0.228875f, 0.000000f);
    private static final Pose hit4_31 = new Pose(0.891303f, -0.804037f, 0.077269f, 0.000000f, -0.280438f, 0.018638f, -0.073541f, 0.019317f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.395155f, 0.000000f, 0.000000f, -0.220623f, 0.000000f);
    private static final Pose hit4_32 = new Pose(0.881386f, -0.794120f, 0.073302f, 0.000000f, -0.270521f, 0.008722f, -0.071557f, 0.018325f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.389205f, 0.000000f, 0.000000f, -0.214672f, 0.000000f);
    private static final Pose hit4_33 = new Pose(0.874744f, -0.787478f, 0.070645f, 0.000000f, -0.263879f, 0.002080f, -0.070229f, 0.017661f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.385220f, 0.000000f, 0.000000f, -0.210687f, 0.000000f);
    private static final Pose hit4_34 = new Pose(0.870815f, -0.783548f, 0.069073f, 0.000000f, -0.259950f, -0.001850f, -0.069443f, 0.017268f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.382863f, 0.000000f, 0.000000f, -0.208330f, 0.000000f);
    private static final Pose hit4_35 = new Pose(0.869034f, -0.781768f, 0.068361f, 0.000000f, -0.258169f, -0.003630f, -0.069087f, 0.017090f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.381794f, 0.000000f, 0.000000f, -0.207261f, 0.000000f);
    private static final Pose hit4_36 = new Pose(0.868840f, -0.781574f, 0.068283f, 0.000000f, -0.257975f, -0.003825f, -0.069048f, 0.017071f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.381678f, 0.000000f, 0.000000f, -0.207145f, 0.000000f);
    private static final Pose hit4_37 = new Pose(0.869669f, -0.782402f, 0.068615f, 0.000000f, -0.258804f, -0.002996f, -0.069214f, 0.017154f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.382175f, 0.000000f, 0.000000f, -0.207642f, 0.000000f);
    private static final Pose hit4_38 = new Pose(0.870958f, -0.783691f, 0.069130f, 0.000000f, -0.260093f, -0.001707f, -0.069472f, 0.017283f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.382948f, 0.000000f, 0.000000f, -0.208415f, 0.000000f);
    private static final Pose hit4_39 = new Pose(0.872144f, -0.784878f, 0.069605f, 0.000000f, -0.261279f, -0.000521f, -0.069709f, 0.017401f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.383660f, 0.000000f, 0.000000f, -0.209127f, 0.000000f);
    private static final Pose hit4_40 = new Pose(0.872665f, -0.785398f, 0.069813f, 0.000000f, -0.261799f, 0.000000f, -0.069813f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.383972f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);

    private static final Pose hit5_00 = new Pose(0.872665f, -0.785398f, 0.069813f, 0.000000f, -0.261799f, 0.000000f, -0.069813f, 0.017453f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.383972f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit5_01 = new Pose(0.959931f, -1.003564f, 0.087266f, 0.000000f, -0.436332f, -0.087266f, -0.087266f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.322886f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit5_02 = new Pose(1.047198f, -1.221730f, 0.104720f, 0.000000f, -0.610865f, -0.174533f, -0.104720f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose hit5_03 = new Pose(1.041612f, -1.277581f, 0.098018f, 0.000000f, -0.644376f, -0.185703f, -0.106954f, 0.037141f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.250629f, 0.000000f, 0.000000f, -0.125105f, 0.000000f);
    private static final Pose hit5_04 = new Pose(1.002517f, -1.668535f, 0.051103f, 0.000000f, -0.878948f, -0.263894f, -0.122592f, 0.052779f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.172439f, 0.000000f, 0.000000f, -0.023457f, 0.000000f);
    private static final Pose hit5_05 = new Pose(0.957507f, -2.096819f, 0.000646f, 0.000000f, -1.135676f, -0.348662f, -0.139384f, 0.069975f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.086054f, 0.000000f, 0.000000f, 0.088074f, 0.000000f);
    private static final Pose hit5_06 = new Pose(0.894481f, -2.159845f, 0.017453f, 0.000000f, -1.167189f, -0.338158f, -0.133081f, 0.074176f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.054542f, 0.000000f, 0.000000f, 0.109083f, 0.000000f);
    private static final Pose hit5_07 = new Pose(0.656923f, -2.397404f, 0.080802f, 0.000000f, -1.285968f, -0.298564f, -0.109325f, 0.090014f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.064238f, 0.000000f, 0.000000f, 0.188269f, 0.000000f);
    private static final Pose hit5_08 = new Pose(0.434884f, -2.615098f, 0.139916f, 0.000000f, -1.395229f, -0.260972f, -0.086977f, 0.104678f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.173706f, 0.000000f, 0.000000f, 0.261386f, 0.000000f);
    private static final Pose hit5_09 = new Pose(0.397237f, -2.539803f, 0.147445f, 0.000000f, -1.368338f, -0.239459f, -0.079447f, 0.103603f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.152193f, 0.000000f, 0.000000f, 0.250629f, 0.000000f);
    private static final Pose hit5_10 = new Pose(0.255335f, -2.256000f, 0.175826f, 0.000000f, -1.266980f, -0.158372f, -0.051067f, 0.099548f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.071106f, 0.000000f, 0.000000f, 0.210086f, 0.000000f);
    private static final Pose hit5_11 = new Pose(0.123569f, -1.992468f, 0.202179f, 0.000000f, -1.172861f, -0.083078f, -0.024714f, 0.095784f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.004189f, 0.000000f, 0.000000f, 0.172439f, 0.000000f);
    private static final Pose hit5_12 = new Pose(-0.060324f, -1.624682f, 0.238958f, 0.000000f, -1.041509f, 0.022004f, 0.012065f, 0.090530f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.109271f, 0.000000f, 0.000000f, 0.119898f, 0.000000f);
    private static final Pose hit5_13 = new Pose(-0.272427f, -1.314685f, 0.252010f, 0.000000f, -0.894669f, 0.103582f, 0.047959f, 0.084003f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.223480f, 0.000000f, 0.000000f, 0.031793f, 0.000000f);
    private static final Pose hit5_14 = new Pose(-0.430049f, -1.183333f, 0.236248f, 0.000000f, -0.789587f, 0.129852f, 0.068975f, 0.078749f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.302291f, 0.000000f, 0.000000f, -0.057526f, 0.000000f);
    private static final Pose hit5_15 = new Pose(-0.542991f, -1.089215f, 0.224954f, 0.000000f, -0.714292f, 0.148676f, 0.084034f, 0.074985f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.358762f, 0.000000f, 0.000000f, -0.121527f, 0.000000f);
    private static final Pose hit5_16 = new Pose(-0.618700f, -1.026124f, 0.217383f, 0.000000f, -0.663820f, 0.161294f, 0.094129f, 0.072461f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.396616f, 0.000000f, 0.000000f, -0.164428f, 0.000000f);
    private static final Pose hit5_17 = new Pose(-0.664621f, -0.987856f, 0.212791f, 0.000000f, -0.633205f, 0.168948f, 0.100252f, 0.070930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.419577f, 0.000000f, 0.000000f, -0.190450f, 0.000000f);
    private static final Pose hit5_18 = new Pose(-0.688203f, -0.968205f, 0.210432f, 0.000000f, -0.617485f, 0.172878f, 0.103396f, 0.070144f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.431368f, 0.000000f, 0.000000f, -0.203813f, 0.000000f);
    private static final Pose hit5_19 = new Pose(-0.696891f, -0.960965f, 0.209564f, 0.000000f, -0.611693f, 0.174326f, 0.104554f, 0.069855f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.435712f, 0.000000f, 0.000000f, -0.208736f, 0.000000f);
    private static final Pose hit5_20 = new Pose(-0.698132f, -0.959931f, 0.209440f, 0.000000f, -0.610865f, 0.174533f, 0.104720f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.436332f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit5_21 = new Pose(-0.816378f, -0.879524f, 0.176331f, 0.000000f, -0.539918f, 0.150884f, 0.118909f, 0.065083f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.483631f, 0.000000f, 0.000000f, -0.237819f, 0.000000f);
    private static final Pose hit5_22 = new Pose(-0.911062f, -0.815139f, 0.149819f, 0.000000f, -0.483107f, 0.131947f, 0.130271f, 0.061296f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.521504f, 0.000000f, 0.000000f, -0.260543f, 0.000000f);
    private static final Pose hit5_23 = new Pose(-0.984802f, -0.764995f, 0.129172f, 0.000000f, -0.438863f, 0.117199f, 0.139120f, 0.058346f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.551000f, 0.000000f, 0.000000f, -0.278240f, 0.000000f);
    private static final Pose hit5_24 = new Pose(-1.040216f, -0.727314f, 0.113656f, 0.000000f, -0.405615f, 0.106116f, 0.145770f, 0.056130f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.573166f, 0.000000f, 0.000000f, -0.291540f, 0.000000f);
    private static final Pose hit5_25 = new Pose(-1.079922f, -0.700313f, 0.102538f, 0.000000f, -0.381791f, 0.098175f, 0.150535f, 0.054542f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.589049f, 0.000000f, 0.000000f, -0.301069f, 0.000000f);
    private static final Pose hit5_26 = new Pose(-1.106539f, -0.682214f, 0.095086f, 0.000000f, -0.365821f, 0.092852f, 0.153729f, 0.053477f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.599695f, 0.000000f, 0.000000f, -0.307457f, 0.000000f);
    private static final Pose hit5_27 = new Pose(-1.122683f, -0.671236f, 0.090565f, 0.000000f, -0.356134f, 0.089623f, 0.155666f, 0.052831f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.606153f, 0.000000f, 0.000000f, -0.311332f, 0.000000f);
    private static final Pose hit5_28 = new Pose(-1.130973f, -0.665599f, 0.088244f, 0.000000f, -0.351160f, 0.087965f, 0.156661f, 0.052500f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.609469f, 0.000000f, 0.000000f, -0.313322f, 0.000000f);
    private static final Pose hit5_29 = new Pose(-1.134028f, -0.663522f, 0.087389f, 0.000000f, -0.349328f, 0.087354f, 0.157027f, 0.052377f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.610691f, 0.000000f, 0.000000f, -0.314055f, 0.000000f);
    private static final Pose hit5_30 = new Pose(-1.134464f, -0.663225f, 0.087266f, 0.000000f, -0.349066f, 0.087266f, 0.157080f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.610865f, 0.000000f, 0.000000f, -0.314159f, 0.000000f);
    private static final Pose hit5_31 = new Pose(-1.165889f, -0.625516f, 0.068412f, 0.000000f, -0.317641f, 0.055842f, 0.163365f, 0.046075f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.592010f, 0.000000f, 0.000000f, -0.301589f, 0.000000f);
    private static final Pose hit5_32 = new Pose(-1.189337f, -0.597377f, 0.054343f, 0.000000f, -0.294193f, 0.032393f, 0.168054f, 0.041385f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.577941f, 0.000000f, 0.000000f, -0.292210f, 0.000000f);
    private static final Pose hit5_33 = new Pose(-1.205909f, -0.577491f, 0.044399f, 0.000000f, -0.277621f, 0.015821f, 0.171369f, 0.038071f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.567998f, 0.000000f, 0.000000f, -0.285581f, 0.000000f);
    private static final Pose hit5_34 = new Pose(-1.216704f, -0.564537f, 0.037923f, 0.000000f, -0.266826f, 0.005027f, 0.173528f, 0.035912f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.561521f, 0.000000f, 0.000000f, -0.281263f, 0.000000f);
    private static final Pose hit5_35 = new Pose(-1.222821f, -0.557196f, 0.034252f, 0.000000f, -0.260709f, -0.001091f, 0.174751f, 0.034688f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.557851f, 0.000000f, 0.000000f, -0.278816f, 0.000000f);
    private static final Pose hit5_36 = new Pose(-1.225361f, -0.554149f, 0.032728f, 0.000000f, -0.258169f, -0.003630f, 0.175259f, 0.034181f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556327f, 0.000000f, 0.000000f, -0.277801f, 0.000000f);
    private static final Pose hit5_37 = new Pose(-1.225422f, -0.554076f, 0.032692f, 0.000000f, -0.258108f, -0.003691f, 0.175271f, 0.034168f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556291f, 0.000000f, 0.000000f, -0.277776f, 0.000000f);
    private static final Pose hit5_38 = new Pose(-1.224104f, -0.555657f, 0.033482f, 0.000000f, -0.259426f, -0.002374f, 0.175008f, 0.034432f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.557081f, 0.000000f, 0.000000f, -0.278303f, 0.000000f);
    private static final Pose hit5_39 = new Pose(-1.222507f, -0.557573f, 0.034441f, 0.000000f, -0.261023f, -0.000777f, 0.174688f, 0.034751f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558039f, 0.000000f, 0.000000f, -0.278942f, 0.000000f);
    private static final Pose hit5_40 = new Pose(-1.221730f, -0.558505f, 0.034907f, 0.000000f, -0.261799f, 0.000000f, 0.174533f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.279253f, 0.000000f);

    private static final Pose charged_00 = new Pose(0.000000f, -0.349066f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_01 = new Pose(-0.279253f, -0.907571f, -0.055851f, 0.000000f, -0.453786f, -0.069813f, -0.041888f, 0.013963f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.069813f, 0.000000f, 0.000000f, 0.055851f, 0.000000f);
    private static final Pose charged_02 = new Pose(-0.558505f, -1.466077f, -0.111701f, 0.000000f, -0.733038f, -0.139626f, -0.083776f, 0.027925f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.139626f, 0.000000f, 0.000000f, 0.111701f, 0.000000f);
    private static final Pose charged_03 = new Pose(-0.697511f, -1.747398f, -0.139502f, 0.000000f, -0.873492f, -0.174740f, -0.104802f, 0.034948f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.174740f, 0.000000f, 0.000000f, 0.139792f, 0.000000f);
    private static final Pose charged_04 = new Pose(-0.681377f, -1.801180f, -0.136275f, 0.000000f, -0.895005f, -0.180118f, -0.106954f, 0.036024f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.180118f, 0.000000f, 0.000000f, 0.144094f, 0.000000f);
    private static final Pose charged_05 = new Pose(-0.620562f, -2.003897f, -0.124112f, 0.000000f, -0.976092f, -0.200390f, -0.115062f, 0.040078f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.200390f, 0.000000f, 0.000000f, 0.160312f, 0.000000f);
    private static final Pose charged_06 = new Pose(-0.485279f, -2.454838f, -0.097056f, 0.000000f, -1.156468f, -0.245484f, -0.133100f, 0.049097f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.245484f, 0.000000f, 0.000000f, 0.196387f, 0.000000f);
    private static final Pose charged_07 = new Pose(-0.433682f, -2.613281f, -0.086383f, 0.000000f, -1.220258f, -0.260327f, -0.138978f, 0.052419f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.260032f, 0.000000f, 0.000000f, 0.208438f, 0.000000f);
    private static final Pose charged_08 = new Pose(-0.402658f, -2.558129f, -0.076042f, 0.000000f, -1.203023f, -0.243092f, -0.131395f, 0.053108f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.239350f, 0.000000f, 0.000000f, 0.196718f, 0.000000f);
    private static final Pose charged_09 = new Pose(-0.305662f, -2.385691f, -0.043710f, 0.000000f, -1.149136f, -0.189205f, -0.107685f, 0.055264f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.174686f, 0.000000f, 0.000000f, 0.160075f, 0.000000f);
    private static final Pose charged_10 = new Pose(-0.104992f, -2.028945f, 0.023180f, 0.000000f, -1.037653f, -0.077722f, -0.058632f, 0.059723f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.040906f, 0.000000f, 0.000000f, 0.084267f, 0.000000f);
    private static final Pose charged_11 = new Pose(0.046000f, -1.760514f, 0.073511f, 0.000000f, -0.953768f, 0.006163f, -0.021723f, 0.063078f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.059756f, 0.000000f, 0.000000f, 0.027225f, 0.000000f);
    private static final Pose charged_12 = new Pose(0.237048f, -1.420873f, 0.137194f, 0.000000f, -0.847630f, 0.112301f, 0.024978f, 0.067324f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.187121f, 0.000000f, 0.000000f, -0.044949f, 0.000000f);
    private static final Pose charged_13 = new Pose(0.349109f, -1.221709f, 0.174529f, 0.000000f, -0.785365f, 0.174522f, 0.052366f, 0.069811f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261821f, 0.000000f, 0.000000f, -0.087277f, 0.000000f);
    private static final Pose charged_14 = new Pose(0.350244f, -1.221141f, 0.174415f, 0.000000f, -0.784515f, 0.174238f, 0.052537f, 0.069754f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.262388f, 0.000000f, 0.000000f, -0.087561f, 0.000000f);
    private static final Pose charged_15 = new Pose(0.354520f, -1.219003f, 0.173988f, 0.000000f, -0.781308f, 0.173169f, 0.053178f, 0.069540f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.264526f, 0.000000f, 0.000000f, -0.088630f, 0.000000f);
    private static final Pose charged_16 = new Pose(0.364032f, -1.214247f, 0.173036f, 0.000000f, -0.774174f, 0.170791f, 0.054605f, 0.069065f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.269282f, 0.000000f, 0.000000f, -0.091008f, 0.000000f);
    private static final Pose charged_17 = new Pose(0.380874f, -1.205826f, 0.171352f, 0.000000f, -0.761542f, 0.166581f, 0.057131f, 0.068223f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.277704f, 0.000000f, 0.000000f, -0.095219f, 0.000000f);
    private static final Pose charged_18 = new Pose(0.407142f, -1.192693f, 0.168725f, 0.000000f, -0.741841f, 0.160014f, 0.061071f, 0.066909f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.290837f, 0.000000f, 0.000000f, -0.101785f, 0.000000f);
    private static final Pose charged_19 = new Pose(0.444928f, -1.173799f, 0.164947f, 0.000000f, -0.713502f, 0.150567f, 0.066739f, 0.065020f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.309730f, 0.000000f, 0.000000f, -0.111232f, 0.000000f);
    private static final Pose charged_20 = new Pose(0.496328f, -1.148099f, 0.159807f, 0.000000f, -0.674952f, 0.137717f, 0.074449f, 0.062450f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.335430f, 0.000000f, 0.000000f, -0.124082f, 0.000000f);
    private static final Pose charged_21 = new Pose(0.563436f, -1.114545f, 0.153096f, 0.000000f, -0.624621f, 0.120940f, 0.084515f, 0.059095f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.368984f, 0.000000f, 0.000000f, -0.140859f, 0.000000f);
    private static final Pose charged_22 = new Pose(0.648346f, -1.072090f, 0.144605f, 0.000000f, -0.560938f, 0.099713f, 0.097252f, 0.054849f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.411440f, 0.000000f, 0.000000f, -0.162087f, 0.000000f);
    private static final Pose charged_23 = new Pose(0.673239f, -1.022305f, 0.132159f, 0.000000f, -0.498706f, 0.074820f, 0.102230f, 0.049871f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.423886f, 0.000000f, 0.000000f, -0.169554f, 0.000000f);
    private static final Pose charged_24 = new Pose(0.630784f, -0.979850f, 0.119422f, 0.000000f, -0.456251f, 0.053593f, 0.097985f, 0.045625f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.402658f, 0.000000f, 0.000000f, -0.161063f, 0.000000f);
    private static final Pose charged_25 = new Pose(0.597230f, -0.946296f, 0.109356f, 0.000000f, -0.422697f, 0.036816f, 0.094630f, 0.042270f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.385881f, 0.000000f, 0.000000f, -0.154353f, 0.000000f);
    private static final Pose charged_26 = new Pose(0.571530f, -0.920596f, 0.101646f, 0.000000f, -0.396997f, 0.023966f, 0.092060f, 0.039700f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.373031f, 0.000000f, 0.000000f, -0.149213f, 0.000000f);
    private static final Pose charged_27 = new Pose(0.552637f, -0.901703f, 0.095978f, 0.000000f, -0.378104f, 0.014519f, 0.090170f, 0.037810f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.363585f, 0.000000f, 0.000000f, -0.145434f, 0.000000f);
    private static final Pose charged_28 = new Pose(0.539503f, -0.888569f, 0.092038f, 0.000000f, -0.364970f, 0.007952f, 0.088857f, 0.036497f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.357018f, 0.000000f, 0.000000f, -0.142807f, 0.000000f);
    private static final Pose charged_29 = new Pose(0.531082f, -0.880148f, 0.089511f, 0.000000f, -0.356549f, 0.003742f, 0.088015f, 0.035655f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.352807f, 0.000000f, 0.000000f, -0.141123f, 0.000000f);
    private static final Pose charged_30 = new Pose(0.526326f, -0.875392f, 0.088085f, 0.000000f, -0.351793f, 0.001364f, 0.087539f, 0.035179f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.350429f, 0.000000f, 0.000000f, -0.140172f, 0.000000f);
    private static final Pose charged_31 = new Pose(0.524188f, -0.873254f, 0.087443f, 0.000000f, -0.349655f, 0.000295f, 0.087325f, 0.034965f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349360f, 0.000000f, 0.000000f, -0.139744f, 0.000000f);
    private static final Pose charged_32 = new Pose(0.523621f, -0.872686f, 0.087273f, 0.000000f, -0.349088f, 0.000011f, 0.087269f, 0.034909f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349077f, 0.000000f, 0.000000f, -0.139631f, 0.000000f);
    private static final Pose charged_33 = new Pose(0.479818f, -0.850774f, 0.078510f, 0.000000f, -0.327176f, 0.000000f, 0.082888f, 0.032718f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.340310f, 0.000000f, 0.000000f, -0.130870f, 0.000000f);
    private static final Pose charged_34 = new Pose(0.413852f, -0.817791f, 0.065317f, 0.000000f, -0.294193f, 0.000000f, 0.076292f, 0.029419f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.327117f, 0.000000f, 0.000000f, -0.117677f, 0.000000f);
    private static final Pose charged_35 = new Pose(0.372337f, -0.797034f, 0.057014f, 0.000000f, -0.273435f, 0.000000f, 0.072140f, 0.027343f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.318813f, 0.000000f, 0.000000f, -0.109374f, 0.000000f);
    private static final Pose charged_36 = new Pose(0.350059f, -0.785895f, 0.052558f, 0.000000f, -0.262296f, 0.000000f, 0.069912f, 0.026230f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.314358f, 0.000000f, 0.000000f, -0.104918f, 0.000000f);
    private static final Pose charged_37 = new Pose(0.341805f, -0.781768f, 0.050908f, 0.000000f, -0.258169f, 0.000000f, 0.069087f, 0.025817f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.312707f, 0.000000f, 0.000000f, -0.103268f, 0.000000f);
    private static final Pose charged_38 = new Pose(0.342364f, -0.782047f, 0.051019f, 0.000000f, -0.258448f, 0.000000f, 0.069143f, 0.025845f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.312819f, 0.000000f, 0.000000f, -0.103379f, 0.000000f);
    private static final Pose charged_39 = new Pose(0.346522f, -0.784126f, 0.051851f, 0.000000f, -0.260527f, 0.000000f, 0.069559f, 0.026053f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.313650f, 0.000000f, 0.000000f, -0.104211f, 0.000000f);
    private static final Pose charged_40 = new Pose(0.349066f, -0.785398f, 0.052360f, 0.000000f, -0.261799f, 0.000000f, 0.069813f, 0.026180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.314159f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);

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
        new Clip(new Keyframe[] { // charged
                new Keyframe(0.000f, 0, charged_00),
                new Keyframe(0.025f, 0, charged_01),
                new Keyframe(0.050f, 0, charged_02),
                new Keyframe(0.075f, 0, charged_03),
                new Keyframe(0.100f, 0, charged_04),
                new Keyframe(0.125f, 0, charged_05),
                new Keyframe(0.150f, 0, charged_06),
                new Keyframe(0.175f, 0, charged_07),
                new Keyframe(0.200f, 0, charged_08),
                new Keyframe(0.225f, 0, charged_09),
                new Keyframe(0.250f, 0, charged_10),
                new Keyframe(0.275f, 0, charged_11),
                new Keyframe(0.300f, 0, charged_12),
                new Keyframe(0.325f, 0, charged_13),
                new Keyframe(0.350f, 0, charged_14),
                new Keyframe(0.375f, 0, charged_15),
                new Keyframe(0.400f, 0, charged_16),
                new Keyframe(0.425f, 0, charged_17),
                new Keyframe(0.450f, 0, charged_18),
                new Keyframe(0.475f, 0, charged_19),
                new Keyframe(0.500f, 0, charged_20),
                new Keyframe(0.525f, 0, charged_21),
                new Keyframe(0.550f, 0, charged_22),
                new Keyframe(0.575f, 0, charged_23),
                new Keyframe(0.600f, 0, charged_24),
                new Keyframe(0.625f, 0, charged_25),
                new Keyframe(0.650f, 0, charged_26),
                new Keyframe(0.675f, 0, charged_27),
                new Keyframe(0.700f, 0, charged_28),
                new Keyframe(0.725f, 0, charged_29),
                new Keyframe(0.750f, 0, charged_30),
                new Keyframe(0.775f, 0, charged_31),
                new Keyframe(0.800f, 0, charged_32),
                new Keyframe(0.825f, 0, charged_33),
                new Keyframe(0.850f, 0, charged_34),
                new Keyframe(0.875f, 0, charged_35),
                new Keyframe(0.900f, 0, charged_36),
                new Keyframe(0.925f, 0, charged_37),
                new Keyframe(0.950f, 0, charged_38),
                new Keyframe(0.975f, 0, charged_39),
                new Keyframe(1.000f, 0, charged_40),
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
