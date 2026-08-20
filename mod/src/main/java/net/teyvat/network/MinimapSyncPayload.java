package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

import java.util.ArrayList;
import java.util.List;

/** S2C: сервер отправляет клиенту список исследованных чанков миникарты. */
public record MinimapSyncPayload(List<String> mapTags) implements CustomPayload {
    public static final CustomPayload.Id<MinimapSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "minimap_sync"));
    public static final PacketCodec<RegistryByteBuf, MinimapSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.mapTags().size());
                for (String tag : value.mapTags()) {
                    buf.writeString(tag);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<String> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(buf.readString());
                }
                return new MinimapSyncPayload(list);
            });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
