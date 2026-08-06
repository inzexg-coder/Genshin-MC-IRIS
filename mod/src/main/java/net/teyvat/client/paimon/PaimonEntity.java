package net.teyvat.client.paimon;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Чисто клиентская сущность Паймон: появляется только у своего игрока, в сеть не уходит.
 * Сначала зависает перед игроком и знакомит его с миром, затем летит за ним по миру.
 */
public class PaimonEntity extends Entity {
    private static final UUID NO_OWNER = new UUID(0L, 0L);

    /** Тип нужен только для рендера — в реестр сервера не регистрируется. */
    public static final EntityType<PaimonEntity> TYPE = EntityType.Builder.create(PaimonEntity::new, SpawnGroup.MISC)
            .dimensions(0.4f, 0.9f)
            .disableSaving()
            .disableSummon()
            .maxTrackingRange(64)
            .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of("teyvat", "paimon")));

    /** Длительность знакомства после появления, тики (по умолчанию). */
    public static final int INTRO_TICKS = 140;

    private static final double FOLLOW_DIST = 1.15;
    /** Высота полёта над игроком. */
    private static final double FOLLOW_UP = 1.2;
    /** Сдвиг вбок при полёте за игроком, как в оригинальном моде APaimon. */
    private static final float FOLLOW_SIDE_DEG = 30.0f;
    /** Скорость полёта к цели, блоков/тик. */
    private static final double MOVE_SPEED = 0.22;
    /** Если Паймон отстала дальше этого расстояния — телепорт к игроку. */
    private static final double TELEPORT_DIST = 16.0;

    private UUID ownerUuid = NO_OWNER;
    private boolean following;
    private int introTicks;
    private final int introTicksLimit;
    private int lastMessage = -1;

    public PaimonEntity(EntityType<? extends PaimonEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.introTicksLimit = Math.max(20, net.teyvat.config.TeyvatConfig.get().paimon.intro_ticks);
    }

    public void setOwner(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isFollowing() {
        return this.following;
    }

    @Override
    public void tick() {
        super.tick();
        MinecraftClient client = MinecraftClient.getInstance();
        AbstractClientPlayerEntity player = client.player;
        if (player == null || !player.getUuid().equals(this.ownerUuid)) {
            this.discard();
            return;
        }

        if (!this.following) {
            this.introTicks++;
            if (this.introTicks == 1) {
                this.say("Ой! Ты наконец проснулся, путешественник? Паймон уже думала, ты будешь спать вечно!");
            } else if (this.introTicks == 55) {
                this.say("Это пляж Тейвата. За холмами стоит Мондштадт — город свободы. Оттуда всё и начинается.");
            } else if (this.introTicks >= this.introTicksLimit) {
                this.following = true;
                this.say("Пойдём! Паймон покажет дорогу и будет рядом, куда бы ты ни пошёл.");
            }
        }

        Vec3d target = this.following ? followTarget(player) : introTarget(player);
        if (this.squaredDistanceTo(target) >= TELEPORT_DIST * TELEPORT_DIST) {
            this.refreshPositionAfterTeleport(target);
            this.setYaw(faceYaw(player));
            return;
        }

        Vec3d delta = target.subtract(pos());
        double dist = delta.length();
        if (dist >= 0.05) {
            Vec3d move = delta.multiply(1.0 / dist).multiply(MOVE_SPEED);
            this.setPosition(this.getX() + move.x, this.getY() + move.y, this.getZ() + move.z);
        }
        this.setYaw(faceYaw(player));
        this.setPitch(0.0f);
    }

    /** Цель полёта во время знакомства: чуть перед игроком, на уровне его глаз. */
    private static Vec3d introTarget(AbstractClientPlayerEntity player) {
        return inFront(player, 2.4).add(0.0, 1.2, 0.0);
    }

    /** Цель полёта за игроком: сзади и чуть сбоку, выше головы. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player) {
        double rad = Math.toRadians(player.getYaw());
        Vec3d behind = new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad));
        double sideRad = Math.toRadians(player.getYaw() + FOLLOW_SIDE_DEG);
        Vec3d side = new Vec3d(-Math.sin(sideRad), 0.0, Math.cos(sideRad));
        return playerPos(player)
                .add(behind.multiply(FOLLOW_DIST))
                .add(side.multiply(FOLLOW_DIST * 0.35))
                .add(0.0, FOLLOW_UP, 0.0);
    }

    private static Vec3d inFront(AbstractClientPlayerEntity player, double dist) {
        double rad = Math.toRadians(player.getYaw());
        return playerPos(player).add(new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad)).multiply(dist));
    }

    private static Vec3d playerPos(AbstractClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private Vec3d pos() {
        return new Vec3d(this.getX(), this.getY(), this.getZ());
    }

    @Override
    protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
    }

    @Override
    protected void readCustomData(net.minecraft.storage.ReadView readView) {
    }

    @Override
    protected void writeCustomData(net.minecraft.storage.WriteView writeView) {
    }

    @Override
    public boolean damage(net.minecraft.server.world.ServerWorld world,
                          net.minecraft.entity.damage.DamageSource source, float amount) {
        return false;
    }

    /** Minecraft-yaw, при котором сущность смотрит на игрока. */
    private float faceYaw(AbstractClientPlayerEntity player) {
        double dx = player.getX() - this.getX();
        double dz = player.getZ() - this.getZ();
        return (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    private void say(String text) {
        if (this.lastMessage == this.introTicks) {
            return;
        }
        this.lastMessage = this.introTicks;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§fПаймон§7: §f" + text), false);
        }
    }
}
