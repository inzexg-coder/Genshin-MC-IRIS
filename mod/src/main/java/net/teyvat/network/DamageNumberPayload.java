package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: сервер сообщает клиенту, какой урон нанесён сущности (для чисел урона). */
public record DamageNumberPayload(int entityId, float amount) implements CustomPayload {
    public static final CustomPayload.Id<DamageNumberPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "damage_number"));
    public static final PacketCodec<RegistryByteBuf, DamageNumberPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.entityId());
                buf.writeFloat(value.amount());
            },
            buf -> new DamageNumberPayload(buf.readVarInt(), buf.readFloat()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
