package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: игрок выбрал персонажа («lumine» или «aether»). */
public record TravelerChoicePayload(String choice) implements CustomPayload {
    public static final CustomPayload.Id<TravelerChoicePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "traveler_choice"));
    public static final PacketCodec<RegistryByteBuf, TravelerChoicePayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeString(value.choice()),
            buf -> new TravelerChoicePayload(buf.readString(16)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
