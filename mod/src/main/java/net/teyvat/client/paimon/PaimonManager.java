package net.teyvat.client.paimon;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.TravelerChoiceClient;
import net.teyvat.config.TeyvatConfig;

/**
 * Жизненный цикл Паймон на клиенте: одна сущность на игрока.
 * Появляется после выбора героя (или при повторном входе), сама чинит себя:
 * если сущность пропала или игрок сменил измерение — создаёт заново.
 */
public final class PaimonManager {
    private static PaimonEntity paimon;

    private PaimonManager() {}

    /** Вызывается каждый клиентский тик. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            remove();
            return;
        }
        if (paimon != null && !paimon.isRemoved()) {
            if (paimon.getEntityWorld() != client.world) {
                remove();
            }
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
        Vec3d start = playerPos(client.player).add(forward(client.player, 2.4)).add(0.0, 1.2, 0.0);
        entity.setPosition(start.x, start.y, start.z);
        entity.setYaw(client.player.getYaw());
        world.addEntity(entity);
        paimon = entity;
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

    private static Vec3d playerPos(net.minecraft.client.network.AbstractClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static Vec3d forward(net.minecraft.client.network.AbstractClientPlayerEntity player, double dist) {
        double rad = Math.toRadians(player.getYaw());
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad)).multiply(dist);
    }
}
