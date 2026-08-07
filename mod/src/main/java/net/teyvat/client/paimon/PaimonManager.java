package net.teyvat.client.paimon;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TintedParticleEffect;
import net.teyvat.client.TravelerChoiceScreen;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.CameraController;
import net.teyvat.client.DialogueOverlay;
import net.teyvat.client.QuestClient;
import net.teyvat.client.QuestStateClient;
import net.teyvat.client.QuestToast;
import net.teyvat.client.StaminaController;
import net.teyvat.client.TravelerChoiceClient;
import net.teyvat.network.QuestEventPayload;
import net.teyvat.quest.Quests;
import net.teyvat.config.TeyvatConfig;

/**
 * Жизненный цикл Паймон на клиенте: одна сущность на игрока.
 * Появляется после выбора героя (или при повторном входе), сама чинит себя:
 * если сущность пропала или игрок сменил измерение — создаёт заново.
 * Вся клиентская логика (цели полёта, реплики) живёт здесь, чтобы PaimonEntity
 * оставалась сервер-совместимой.
 */
public final class PaimonManager {
    /** Как далеко сбоку (справа) от героя держится Паймон — как в Genshin, всегда в поле зрения.
     *  Боковая позиция не зависит от того, куда герой бежит: она не попадает в лицо и не прячется за спину. */
    private static final double FOLLOW_SIDE = 1.0;
    /** Высота полёта: на уровне головы героя. */
    private static final double FOLLOW_UP = 1.6;
    /** Сглаживание угла позиции: быстрые повороты мыши не дёргают Паймон. */
    private static final float REF_YAW_LERP = 0.05f;
    /** Плавность полёта к цели (доля оставшегося пути за тик) — без рывков и тряски. */
    private static final double FOLLOW_EASE = 0.16;
    /** Если Паймон отстала дальше этого расстояния — телепорт к игроку. */
    private static final double TELEPORT_DIST = 16.0;
    /** Фразы Паймон: короткое введение в мир, представление Паймон и призыв в путь. */
    private static final String[] PHRASES = {
        "Путешественник! Ты наконец-то открыл глаза!",
        "Паймон ждала тебя!",
        "Этот мир называется Тейват, но только не тот, который ты знаешь.",
        "Здесь всё смешалось: и древние руины, и летающие острова, и подземные города...",
        "Паймон сама иногда теряется!",
        "Но не бойся — я буду твоим гидом!",
        "Правда, если проголодаюсь, то буду требовать жареную птицу! Запомни это сразу!"
    };
    /** Тик первой фразы знакомства: игрок сперва приходит в себя (4 секунды). */
    private static final int PHRASE_START_TICK = 80;
    /** Каждая реплика держится 4 секунды (80 тиков). */
    private static final int PHRASE_GAP_TICKS = 80;
    /** Пауза после последней фразы, перед тем как Паймон летит за спину героя. */
    private static final int PHRASE_END_GAP = 30;
    /** Задержка отчёта о выполненном задании после последней фразы Паймон (тики). */
    private static final int QUEST_REPORT_TICKS = 80;
    /** Фразы мини-урока: Паймон подсказывает про колесо мыши после знакомства. */
    private static final String[] TUTOR_PHRASES = {
        "Кстати! Камеру можно приближать и отдалять колесом мыши.",
        "Покрути его — тебя уже ждёт новое задание!"
    };
    /** Пауза перед первой фразой урока после отчёта о первом задании (тики). */
    private static final int TUTOR_START_TICK = 40;
    /** Каждая фраза урока держится 4 секунды (80 тиков). */
    private static final int TUTOR_GAP_TICKS = 80;
    /** Пауза после последней фразы перед уведомлением о новом задании (тики). */
    private static final int TUTOR_END_GAP = 30;
    /** Фразы урока про кнопку C: Паймон учит приближать мир и осматриваться. */
    private static final String[] ZOOM_PHRASES = {
        "А ещё зажми кнопку C — и мир приблизится к тебе!",
        "Попробуй! Осмотрись вокруг — вблизи видно даже самое далёкое."
    };
    /** Пауза перед первой фразой урока про C (тики). */
    private static final int ZOOM_TUTOR_START_TICK = 40;
    /** Каждая фраза урока про C держится 4 секунды (80 тиков). */
    private static final int ZOOM_TUTOR_GAP_TICKS = 80;
    /** Пауза после последней фразы перед уведомлением о новом задании (тики). */
    private static final int ZOOM_TUTOR_END_GAP = 30;
    /** Фразы мини-урока про бег: Паймон учит двойному W и объясняет выносливость. */
    private static final String[] SPRINT_PHRASES = {
        "А ещё путешественник умеет бегать! Два раза нажми вперёд (W).",
        "Смотри в левый нижний угол: дуга — твоя выносливость. Бег тратит её.",
        "Передохнёшь — она снова наполнится. Попробуй!"
    };
    /** Пауза перед первой фразой урока про бег (тики). */
    private static final int SPRINT_TUTOR_START_TICK = 40;
    /** Каждая фраза урока про бег держится 4 секунды (80 тиков). */
    private static final int SPRINT_TUTOR_GAP_TICKS = 80;
    /** Пауза после последней фразы перед уведомлением о новом задании (тики). */
    private static final int SPRINT_TUTOR_END_GAP = 30;
    /** Фразы мини-урока про рывок: Паймон учит кнопке Ctrl. */
    private static final String[] DASH_PHRASES = {
        "А теперь — рывок! Короткий бросок вперёд на Ctrl.",
        "Он тратит больше выносливости, зато мгновенно уводит из-под удара.",
        "Попробуй — тебя уже ждёт новое задание!"
    };
    /** Пауза перед первой фразой урока про рывок (тики). */
    private static final int DASH_TUTOR_START_TICK = 40;
    /** Каждая фраза урока про рывок держится 4 секунды (80 тиков). */
    private static final int DASH_TUTOR_GAP_TICKS = 80;
    /** Пауза после последней фразы перед уведомлением о новом задании (тики). */
    private static final int DASH_TUTOR_END_GAP = 30;
    /** Дистанция до игрока во время знакомства. */
    private static final double INTRO_DIST = 2.4;
    /** Высота над игроком во время знакомства (чуть выше линии взгляда). */
    private static final double INTRO_UP = 1.4;

    private static PaimonEntity paimon;
    /** Сглаженный угол, от которого зависит позиция Паймон (не дёргается от взгляда). */
    private static float refYaw;
    private static boolean refYawReady;
    /** Счётчик для шлейфа: порция частиц каждый 2-й тик. */
    private static int trailTimer;
    /** Высота середины тела Паймон над ногами — отсюда идёт шлейф. */
    private static final double TRAIL_UP = 0.45;
    /** Абсолютная точка знакомства: Паймон не сдвигается с неё, пока говорит. */
    private static Vec3d introPos;
    /** Таймер отчёта о квесте: -1 = не идёт, 0..QUEST_REPORT_TICKS = отсчёт после фразы. */
    private static int questReportTimer = -1;
    /** Таймер мини-урока про колесо мыши: -1 = не идёт, 0 = запущен после знакомства. */
    private static int tutorTicks = -1;
    /** Показано ли уведомление «есть новое задание» (чтобы не дублировалось). */
    private static boolean tutorPromptShown;
    /** Таймер мини-урока про кнопку C: -1 = не идёт, 0 = запущен после задания с колесом. */
    private static int zoomTutorTicks = -1;
    /** Показано ли уведомление «есть новое задание» урока про C. */
    private static boolean zoomPromptShown;
    /** Таймер мини-урока про бег: -1 = не идёт, 0 = запущен после задания с кнопкой C. */
    private static int sprintTutorTicks = -1;
    /** Показано ли уведомление «есть новое задание» урока про бег. */
    private static boolean sprintPromptShown;
    /** Таймер мини-урока про рывок: -1 = не идёт, 0 = запущен после задания с бегом. */
    private static int dashTutorTicks = -1;
    /** Показано ли уведомление «есть новое задание» урока про рывок. */
    private static boolean dashPromptShown;

    private PaimonManager() {}

    /** Вызывается каждый клиентский тик. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            remove();
            return;
        }
        // На паузе мир стоит: знакомство, диалоги и шлейф Паймон замирают вместе с ним.
        if (client.isPaused()) {
            return;
        }
        // Оверлей диалога живёт тиками клиента (и замирает на паузе вместе с миром).
        DialogueOverlay.tick();
        // Пока открыт экран выбора героя, Паймон не появляется: знакомство
        // начнётся заново после выбора, а не проиграется незаметно за меню.
        // Если экран уже закрывается вспышкой — Паймон остаётся: сцена идёт за ней.
        if (client.currentScreen instanceof TravelerChoiceScreen screen && !screen.isClosing()) {
            remove();
            return;
        }
        // События стамины для квестов Паймон: побежал двойным W — задание с бегом,
        // сделал рывок по Ctrl — задание с рывком.
        if (QuestStateClient.isCompleted(Quests.TRY_ZOOM)
                && !QuestStateClient.isCompleted(Quests.TRY_SPRINT)
                && StaminaController.consumeSprintEvent()) {
            QuestClient.complete(Quests.TRY_SPRINT, Quests.TRY_SPRINT_TITLE);
            onSprintQuestCompleted();
        }
        if (QuestStateClient.isCompleted(Quests.TRY_SPRINT)
                && !QuestStateClient.isCompleted(Quests.TRY_DASH)
                && StaminaController.consumeDashEvent()) {
            QuestClient.complete(Quests.TRY_DASH, Quests.TRY_DASH_TITLE);
            onDashQuestCompleted();
        }
        if (paimon != null && !paimon.isRemoved()) {
            if (paimon.getEntityWorld() != client.world) {
                remove();
                return;
            }
            updatePaimon(client, paimon);
            return;
        }
        // Паймон живёт с игроком, у которого уже выбран путешественник.
        if (TeyvatConfig.get().paimon.enabled
                && TravelerChoiceClient.get(client.player.getUuid()) != null) {
            startIntro();
        }
    }

    /** Появление Паймон перед игроком с коротким знакомством с миром. */
    public static void startIntro() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.world instanceof ClientWorld world)) {
            return;
        }
        if (paimon != null && !paimon.isRemoved()) {
            return;
        }
        if (!TeyvatConfig.get().paimon.enabled) {
            return;
        }
        PaimonEntity entity = new PaimonEntity(PaimonEntity.TYPE, world);
        entity.setOwner(client.player.getUuid());
        refYaw = client.player.getYaw();
        refYawReady = true;
        questReportTimer = -1;
        tutorTicks = -1;
        tutorPromptShown = false;
        zoomTutorTicks = -1;
        zoomPromptShown = false;
        // Точка знакомства фиксируется абсолютно: Паймон стоит на месте, пока говорит.
        introPos = playerPos(client.player).add(forwardDeg(refYaw, INTRO_DIST)).add(0.0, INTRO_UP, 0.0);
        entity.setPosition(introPos.x, introPos.y, introPos.z);
        entity.setYaw(client.player.getYaw());
        world.addEntity(entity);
        paimon = entity;
        // Реплики Паймон рисуются оверлеем прямо на экране (не в чате).
    }

    private static void updatePaimon(MinecraftClient client, PaimonEntity entity) {
        AbstractClientPlayerEntity player = client.player;
        if (player == null || !player.getUuid().equals(entity.getOwnerUuid())) {
            remove();
            return;
        }

        // Отчёт о выполненном задании приходит через несколько секунд
        // после последней фразы Паймон, а не сразу.
        if (questReportTimer >= 0) {
            questReportTimer++;
            if (questReportTimer >= QUEST_REPORT_TICKS) {
                questReportTimer = -1;
                reportQuestMeetPaimon();
            }
        }

        if (!refYawReady) {
            refYaw = player.getYaw();
            refYawReady = true;
        }

        if (!entity.isFollowing()) {
            int ticks = entity.getIntroTicks() + 1;
            entity.setIntroTicks(ticks);
            // Реплики в чат, неспешно: каждая держится 3 секунды,
            // сначала игрок приходит в себя, потом Паймон говорит.
            for (int i = 0; i < PHRASES.length; i++) {
                if (ticks == PHRASE_START_TICK + i * PHRASE_GAP_TICKS) {
                    DialogueOverlay.show("Паймон", PHRASES[i]);
                }
            }
            int lastPhraseTick = PHRASE_START_TICK + (PHRASES.length - 1) * PHRASE_GAP_TICKS;
            if (ticks >= Math.max(entity.getIntroTicksLimit(), lastPhraseTick + PHRASE_END_GAP)) {
                // После последней фразы Паймон летит за спину героя.
                entity.setFollowing(true);
                DialogueOverlay.end();
                questReportTimer = 0;
                // Плавный переход камеры из первого лица в третье (как в Genshin).
                if (TeyvatConfig.get().paimon.third_person_after_intro) {
                    CameraController.switchToThirdPerson();
                }
            }
        } else {
            // Плавно доводим угол: быстрые повороты мыши почти не сдвигают позицию Паймон.
            refYaw = MathHelper.lerpAngleDegrees(REF_YAW_LERP, refYaw, player.getYaw());
            tickScrollTutorial();
            // Урок про кнопку C идёт после задания с колесом мыши (не повторяется,
            // если квест уже выполнен; при перезаходе запускается снова до выполнения).
            if (zoomTutorTicks < 0
                    && tutorTicks < 0
                    && questReportTimer < 0
                    && QuestStateClient.isCompleted(Quests.TRY_SCROLL)
                    && !QuestStateClient.isCompleted(Quests.TRY_ZOOM)) {
                zoomTutorTicks = 0;
                zoomPromptShown = false;
            }
            tickZoomTutorial();
            // Урок про бег идёт после задания с кнопкой C (при перезаходе
            // запускается снова, пока квест не выполнен).
            if (sprintTutorTicks < 0 && dashTutorTicks < 0 && zoomTutorTicks < 0
                    && tutorTicks < 0 && questReportTimer < 0
                    && QuestStateClient.isCompleted(Quests.TRY_ZOOM)
                    && !QuestStateClient.isCompleted(Quests.TRY_SPRINT)) {
                sprintTutorTicks = 0;
                sprintPromptShown = false;
            }
            tickSprintTutorial();
            // Урок про рывок идёт после задания с бегом.
            if (dashTutorTicks < 0 && sprintTutorTicks < 0 && zoomTutorTicks < 0
                    && tutorTicks < 0 && questReportTimer < 0
                    && QuestStateClient.isCompleted(Quests.TRY_SPRINT)
                    && !QuestStateClient.isCompleted(Quests.TRY_DASH)) {
                dashTutorTicks = 0;
                dashPromptShown = false;
            }
            tickDashTutorial();
        }

        Vec3d target;
        if (entity.isFollowing()) {
            target = followTarget(player, refYaw, entity);
        } else {
            // Во время знакомства Паймон не двигается: стоит на зафиксированной точке
            // и только поворачивается лицом к игроку.
            target = introPos;
        }
        if (entity.squaredDistanceTo(target) >= TELEPORT_DIST * TELEPORT_DIST) {
            entity.refreshPositionAfterTeleport(target);
            entity.setYaw(faceYaw(entity, player));
            entity.clearTrail();
            entity.pushTrailPoint(entityPos(entity).add(0.0, TRAIL_UP, 0.0));
            return;
        }

        // Плавное сближение: каждый тик проходим долю оставшегося пути,
        // поэтому Паймон замедляется у цели и не дрожит вокруг неё.
        Vec3d delta = target.subtract(entityPos(entity));
        double dist = delta.length();
        if (dist >= 0.01) {
            Vec3d move = delta.multiply(Math.min(1.0, FOLLOW_EASE));
            entity.setPosition(entity.getX() + move.x, entity.getY() + move.y, entity.getZ() + move.z);
        }
        entity.setYaw(faceYaw(entity, player));
        entity.setPitch(0.0f);
        if (entity.isFollowing()) {
            entity.pushTrailPoint(entityPos(entity).add(0.0, TRAIL_UP, 0.0));
            spawnGoldenTrail((ClientWorld) client.world, entity);
        }
    }

    /** Шлейф из частиц фейерверка: яркие золотые искры и крупные вспышки.
     *  FIREWORK — светящаяся искра фейерверка, FLASH — большая яркая вспышка взрыва
     *  (та самая, что вспыхивает, когда фейерверк разрывается). Обе не зависят от
     *  освещения и видны при любом шейдере. */
    private static void spawnGoldenTrail(ClientWorld world, PaimonEntity entity) {
        if (trailTimer++ % 2 != 0) {
            return;
        }
        var random = world.random;
        double x = entity.getX();
        double y = entity.getY() + TRAIL_UP;
        double z = entity.getZ();
        // Искры фейерверка: разлетаются в стороны и вниз, оставляя за собой дорожку.
        for (int i = 0; i < 4; i++) {
            world.addParticleClient(ParticleTypes.FIREWORK,
                    x + (random.nextDouble() - 0.5) * 0.6,
                    y + (random.nextDouble() - 0.5) * 0.6,
                    z + (random.nextDouble() - 0.5) * 0.6,
                    (random.nextDouble() - 0.5) * 0.09, -0.03, (random.nextDouble() - 0.5) * 0.09);
        }
        // Большая золотая вспышка взрыва — яркое золотое свечение вокруг Паймон.
        world.addParticleClient(TintedParticleEffect.create(ParticleTypes.FLASH, 1.0f, 0.85f, 0.35f),
                x + (random.nextDouble() - 0.5) * 0.4,
                y + (random.nextDouble() - 0.5) * 0.4,
                z + (random.nextDouble() - 0.5) * 0.4,
                0.0, -0.02, 0.0);
        // Светящаяся золотая пыль добавляет плотность дорожке.
        for (int i = 0; i < 2; i++) {
            world.addParticleClient(new DustColorTransitionParticleEffect(0xFFE066, 0xFFF7CC, 1.2f),
                    x + (random.nextDouble() - 0.5) * 0.8,
                    y + (random.nextDouble() - 0.5) * 0.8,
                    z + (random.nextDouble() - 0.5) * 0.8,
                    (random.nextDouble() - 0.5) * 0.03, -0.03, (random.nextDouble() - 0.5) * 0.03);
        }
    }

    /** Цель полёта: Паймон держится за спиной героя и очень плавно «переплывает»
     *  то влево, то вправо (медленный синус по бокам), никогда не оказываясь перед
     *  лицом. По вертикали слегка выныривает и ныряет, поэтому не летит по ровной
     *  линии. Точка привязана к сглаженному взгляду, так что при повороте героя
     *  Паймон мягко дрейфует и остаётся за спиной. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player, float yaw, PaimonEntity entity) {
        double orbit = entity.age * 0.045;
        double side = Math.sin(orbit) * FOLLOW_SIDE * 1.05;
        double bob = Math.sin(entity.age * 0.12 + 1.7) * 0.12;
        return playerPos(player)
                .add(sideDeg(yaw, side))
                .add(forwardDeg(yaw, -0.5))
                .add(0.0, FOLLOW_UP + bob, 0.0);
    }

    /** Направление вбок (перпендикулярно взгляду) для позиции сбоку от героя. */
    private static Vec3d sideDeg(float yaw, double dist) {
        return forwardDeg(yaw + 90.0f, dist);
    }

    /** Направление «вперёд» при данном угле. Отрицательная дистанция — за спину. */
    private static Vec3d forwardDeg(float yaw, double dist) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad)).multiply(dist);
    }

    private static Vec3d playerPos(AbstractClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static Vec3d entityPos(PaimonEntity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    /** Minecraft-yaw, при котором сущность смотрит на игрока. */
    private static float faceYaw(PaimonEntity entity, AbstractClientPlayerEntity player) {
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        return (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    /** Квест «Познакомиться с Паймон»: сообщаем серверу, что знакомство завершилось,
     *  и показываем всплывающее окно с золотым текстом и символом выполненного задания. */
    private static void reportQuestMeetPaimon() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new QuestEventPayload(Quests.MEET_PAIMON));
        }
        client.getToastManager().add(new QuestToast("Задание выполнено", "«" + Quests.MEET_PAIMON_TITLE + "»"));
        // После первого задания — короткий урок про колесо мыши (пока не выполнен).
        if (!QuestStateClient.isCompleted(Quests.TRY_SCROLL)) {
            tutorTicks = 0;
            tutorPromptShown = false;
        }
    }

    /** Начать мини-урок про кнопку C: сразу после выполнения задания с колесом мыши. */
    public static void startZoomTutorial() {
        tutorTicks = -1;
        zoomTutorTicks = 0;
        zoomPromptShown = false;
    }

    /** Квест про кнопку C выполнен: урок завершается, запускаем урок про бег. */
    public static void onZoomQuestCompleted() {
        zoomTutorTicks = -1;
        zoomPromptShown = false;
        DialogueOverlay.end();
        // Сразу после задания с кнопкой C — урок про бег (пока не выполнен).
        if (!QuestStateClient.isCompleted(Quests.TRY_SPRINT)) {
            sprintTutorTicks = 0;
            sprintPromptShown = false;
        }
    }

    /** Мини-урок про колесо мыши: Паймон рассказывает, как приблизить камеру,
     *  затем всплывает уведомление о новом задании. Повторяется при перезаходе,
     *  пока квест не выполнен. */
    private static void tickScrollTutorial() {
        if (tutorTicks < 0) {
            return;
        }
        tutorTicks++;
        MinecraftClient client = MinecraftClient.getInstance();
        // Фразы урока, неспешно: каждая держится 4 секунды.
        for (int i = 0; i < TUTOR_PHRASES.length; i++) {
            if (tutorTicks == TUTOR_START_TICK + i * TUTOR_GAP_TICKS) {
                DialogueOverlay.show("Паймон", TUTOR_PHRASES[i]);
            }
        }
        int lastPhraseTick = TUTOR_START_TICK + (TUTOR_PHRASES.length - 1) * TUTOR_GAP_TICKS;
        if (tutorTicks >= lastPhraseTick + TUTOR_END_GAP && !tutorPromptShown) {
            tutorPromptShown = true;
            DialogueOverlay.end();
            // Уведомление о новом задании — небесно-голубой попап с пустым ромбом.
            client.getToastManager().add(new QuestToast("Новое задание", "«" + Quests.TRY_SCROLL_TITLE + "»", true));
        }
    }

    /** Мини-урок про кнопку C: Паймон рассказывает, как приблизить мир,
     *  затем всплывает уведомление о новом задании. Повторяется при перезаходе,
     *  пока квест не выполнен. */
    private static void tickZoomTutorial() {
        if (zoomTutorTicks < 0) {
            return;
        }
        zoomTutorTicks++;
        MinecraftClient client = MinecraftClient.getInstance();
        // Фразы урока про C, неспешно: каждая держится 4 секунды.
        for (int i = 0; i < ZOOM_PHRASES.length; i++) {
            if (zoomTutorTicks == ZOOM_TUTOR_START_TICK + i * ZOOM_TUTOR_GAP_TICKS) {
                DialogueOverlay.show("Паймон", ZOOM_PHRASES[i]);
            }
        }
        int lastPhraseTick = ZOOM_TUTOR_START_TICK + (ZOOM_PHRASES.length - 1) * ZOOM_TUTOR_GAP_TICKS;
        if (zoomTutorTicks >= lastPhraseTick + ZOOM_TUTOR_END_GAP && !zoomPromptShown) {
            zoomPromptShown = true;
            DialogueOverlay.end();
            // Уведомление о новом задании — небесно-голубой попап с пустым ромбом.
            client.getToastManager().add(new QuestToast("Новое задание", "«" + Quests.TRY_ZOOM_TITLE + "»", true));
        }
    }

    /** Квест про бег выполнен: урок завершается, запускаем урок про рывок. */
    private static void onSprintQuestCompleted() {
        sprintTutorTicks = -1;
        sprintPromptShown = false;
        DialogueOverlay.end();
        // Сразу после задания с бегом — урок про рывок (пока не выполнен).
        if (!QuestStateClient.isCompleted(Quests.TRY_DASH)) {
            dashTutorTicks = 0;
            dashPromptShown = false;
        }
    }

    /** Квест про рывок выполнен: урок завершается, диалог плавно гаснет. */
    private static void onDashQuestCompleted() {
        dashTutorTicks = -1;
        dashPromptShown = false;
        DialogueOverlay.end();
    }

    /** Мини-урок про бег: Паймон рассказывает про двойной W и выносливость,
     *  затем всплывает уведомление о новом задании. Повторяется при перезаходе,
     *  пока квест не выполнен. */
    private static void tickSprintTutorial() {
        if (sprintTutorTicks < 0) {
            return;
        }
        sprintTutorTicks++;
        MinecraftClient client = MinecraftClient.getInstance();
        // Фразы урока про бег, неспешно: каждая держится 4 секунды.
        for (int i = 0; i < SPRINT_PHRASES.length; i++) {
            if (sprintTutorTicks == SPRINT_TUTOR_START_TICK + i * SPRINT_TUTOR_GAP_TICKS) {
                DialogueOverlay.show("Паймон", SPRINT_PHRASES[i]);
            }
        }
        int lastPhraseTick = SPRINT_TUTOR_START_TICK + (SPRINT_PHRASES.length - 1) * SPRINT_TUTOR_GAP_TICKS;
        if (sprintTutorTicks >= lastPhraseTick + SPRINT_TUTOR_END_GAP && !sprintPromptShown
                && !QuestStateClient.isCompleted(Quests.TRY_SPRINT)) {
            sprintPromptShown = true;
            DialogueOverlay.end();
            // Уведомление о новом задании — небесно-голубой попап с пустым ромбом.
            client.getToastManager().add(new QuestToast("Новое задание", "«" + Quests.TRY_SPRINT_TITLE + "»", true));
        }
    }

    /** Мини-урок про рывок: Паймон рассказывает про Ctrl, затем всплывает
     *  уведомление о новом задании. Повторяется при перезаходе, пока квест не выполнен. */
    private static void tickDashTutorial() {
        if (dashTutorTicks < 0) {
            return;
        }
        dashTutorTicks++;
        MinecraftClient client = MinecraftClient.getInstance();
        // Фразы урока про рывок, неспешно: каждая держится 4 секунды.
        for (int i = 0; i < DASH_PHRASES.length; i++) {
            if (dashTutorTicks == DASH_TUTOR_START_TICK + i * DASH_TUTOR_GAP_TICKS) {
                DialogueOverlay.show("Паймон", DASH_PHRASES[i]);
            }
        }
        int lastPhraseTick = DASH_TUTOR_START_TICK + (DASH_PHRASES.length - 1) * DASH_TUTOR_GAP_TICKS;
        if (dashTutorTicks >= lastPhraseTick + DASH_TUTOR_END_GAP && !dashPromptShown
                && !QuestStateClient.isCompleted(Quests.TRY_DASH)) {
            dashPromptShown = true;
            DialogueOverlay.end();
            // Уведомление о новом задании — небесно-голубой попап с пустым ромбом.
            client.getToastManager().add(new QuestToast("Новое задание", "«" + Quests.TRY_DASH_TITLE + "»", true));
        }
    }

    public static void remove() {
        if (paimon != null && !paimon.isRemoved()) {
            paimon.discard();
        }
        paimon = null;
    }

    public static boolean isActive() {
        return paimon != null && !paimon.isRemoved();
    }

    /** Идёт ли сейчас знакомство с Паймон: HUD скрыт, пока она представляется. */
    public static boolean isIntroActive() {
        return paimon != null && !paimon.isRemoved() && !paimon.isFollowing();
    }

    /** Идёт ли сейчас один из мини-уроков Паймон (колесо мыши, C, бег, рывок):
     *  HUD скрыт, пока Паймон учит. */
    public static boolean isTutorialActive() {
        return (tutorTicks >= 0 && !tutorPromptShown)
                || (zoomTutorTicks >= 0 && !zoomPromptShown)
                || (sprintTutorTicks >= 0 && !sprintPromptShown)
                || (dashTutorTicks >= 0 && !dashPromptShown);
    }
}
