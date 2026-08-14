package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: сервер подтверждает попадание удара комбо — список entity id
 *  задетых целей (пустой массив = промах). Клиент по нему делает hitlag,
 *  звук и искры попадания строго на хите. */
public record AttackResultPayload(int[] entityIds) implements CustomPayload {
    public static final CustomPayload.Id<AttackResultPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "attack_result"));
    public static final PacketCodec<RegistryByteBuf, AttackResultPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.entityIds().length);
                for (int id : value.entityIds()) {
                    buf.writeVarInt(id);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                int[] ids = new int[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = buf.readVarInt();
                }
                return new AttackResultPayload(ids);
            });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
