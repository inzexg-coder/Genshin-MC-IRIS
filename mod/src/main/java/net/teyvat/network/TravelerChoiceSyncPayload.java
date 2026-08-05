package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

import java.util.UUID;

/** S2C-пакет: игрок с данным UUID выбрал персонажа («lumine» или «aether»). */
public record TravelerChoiceSyncPayload(UUID playerId, String choice) implements CustomPayload {
    public static final CustomPayload.Id<TravelerChoiceSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "traveler_choice_sync"));
    public static final PacketCodec<RegistryByteBuf, TravelerChoiceSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeUuid(value.playerId());
                buf.writeString(value.choice());
            },
            buf -> new TravelerChoiceSyncPayload(buf.readUuid(), buf.readString(16)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
