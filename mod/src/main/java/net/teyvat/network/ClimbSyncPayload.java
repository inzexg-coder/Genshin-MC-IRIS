package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/**
 * S2C-пакет: сервер передаёт клиенту состояние карабканья и серверное
 * значение выносливости. Пока игрок карабкается, стамина авторитетна на
 * сервере; клиент отражает её на дуге выносливости и приостанавливает
 * свою локальную (бег/рывок) во время карабканья.
 */
public record ClimbSyncPayload(boolean climbing, boolean sliding, float stamina) implements CustomPayload {
    public static final CustomPayload.Id<ClimbSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "climb_sync"));
    public static final PacketCodec<RegistryByteBuf, ClimbSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.climbing());
                buf.writeBoolean(value.sliding());
                buf.writeFloat(value.stamina());
            },
            buf -> new ClimbSyncPayload(buf.readBoolean(), buf.readBoolean(), buf.readFloat()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
