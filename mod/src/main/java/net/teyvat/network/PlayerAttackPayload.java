package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: клиент сообщает серверу удар комбо (номер удара 0..4) или
 *  заряженный спин (SwordCombo.CHARGE_INDEX = 5). chargeLevel (0..1) — уровень
 *  заряда: урон спина зависит от него (ранний отпуск = слабее). Сервер сам
 *  ищет цели по хитбоксу удара и наносит урон. */
public record PlayerAttackPayload(int hitIndex, float chargeLevel) implements CustomPayload {
    public static final CustomPayload.Id<PlayerAttackPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "player_attack"));
    public static final PacketCodec<RegistryByteBuf, PlayerAttackPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.hitIndex());
                buf.writeFloat(value.chargeLevel());
            },
            buf -> new PlayerAttackPayload(buf.readVarInt(), buf.readFloat()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
