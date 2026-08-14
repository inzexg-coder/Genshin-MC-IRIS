package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: игрок нажал F — подобрать ближайший предмет с земли. */
public record PickupRequestPayload() implements CustomPayload {
    public static final CustomPayload.Id<PickupRequestPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "pickup_request"));
    public static final PacketCodec<RegistryByteBuf, PickupRequestPayload> CODEC = PacketCodec.of(
            (value, buf) -> {},
            buf -> new PickupRequestPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
