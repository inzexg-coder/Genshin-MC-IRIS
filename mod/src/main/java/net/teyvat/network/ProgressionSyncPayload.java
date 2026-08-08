package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: прогрессия игрока — ранг, опыт, примогемы и ростера персонажей (JSON). */
public record ProgressionSyncPayload(int ar, long exp, long expToNext, long primogems, String rosterJson)
        implements CustomPayload {
    public static final CustomPayload.Id<ProgressionSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "progression_sync"));
    public static final PacketCodec<RegistryByteBuf, ProgressionSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.ar());
                buf.writeLong(value.exp());
                buf.writeLong(value.expToNext());
                buf.writeLong(value.primogems());
                buf.writeString(value.rosterJson());
            },
            buf -> new ProgressionSyncPayload(buf.readVarInt(), buf.readLong(), buf.readLong(),
                    buf.readLong(), buf.readString()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
