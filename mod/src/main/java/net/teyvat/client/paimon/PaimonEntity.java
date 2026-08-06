package net.teyvat.client.paimon;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Сущность Паймон. Тип регистрируется в общем инициализаторе (обе стороны),
 * но спавнится и управляется только на клиенте (см. PaimonManager).
 * В этом классе нет клиентских классов, чтобы он грузился и на выделенном сервере.
 */
public class PaimonEntity extends Entity {
    private static final UUID NO_OWNER = new UUID(0L, 0L);
    private static final Identifier TYPE_ID = Identifier.of("teyvat", "paimon");

    /** Тип зарегистрирован в реестре; спавнится только на клиенте. */
    public static final EntityType<PaimonEntity> TYPE = Registry.register(
            Registries.ENTITY_TYPE,
            TYPE_ID,
            EntityType.Builder.create(PaimonEntity::new, SpawnGroup.MISC)
                    .dimensions(0.4f, 0.9f)
                    .disableSaving()
                    .disableSummon()
                    .maxTrackingRange(64)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, TYPE_ID)));

    /** Дёргает статический инициализатор на обеих сторонах (сервер тоже регистрирует тип). */
    public static void register() {
        if (TYPE == null) {
            throw new IllegalStateException("Paimon entity type not registered");
        }
    }

    private UUID ownerUuid = NO_OWNER;
    private boolean following;
    private int introTicks;
    private final int introTicksLimit;

    public PaimonEntity(EntityType<? extends PaimonEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.setInvulnerable(true);
        this.introTicksLimit = Math.max(20, net.teyvat.config.TeyvatConfig.get().paimon.intro_ticks);
    }

    public void setOwner(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public boolean isFollowing() {
        return this.following;
    }

    public void setFollowing(boolean following) {
        this.following = following;
    }

    public int getIntroTicks() {
        return this.introTicks;
    }

    public void setIntroTicks(int introTicks) {
        this.introTicks = introTicks;
    }

    public int getIntroTicksLimit() {
        return this.introTicksLimit;
    }

    @Override
    public void tick() {
        super.tick();
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
}
