package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: игрок подобрал ресурс (слизь, мора и т.п.) — уведомление «+N Название». */
public record ResourceGainPayload(String itemId, int count) implements CustomPayload {
    public static final CustomPayload.Id<ResourceGainPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "resource_gain"));
    public static final PacketCodec<RegistryByteBuf, ResourceGainPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.itemId());
                buf.writeInt(value.count());
            },
            buf -> new ResourceGainPayload(buf.readString(128), buf.readInt()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
