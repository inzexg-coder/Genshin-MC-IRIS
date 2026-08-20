package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

import java.util.ArrayList;
import java.util.List;

/** C2S: клиент сообщает серверу о newly explored chunks для сохранения. */
public record MinimapExplorePayload(List<long[]> chunks) implements CustomPayload {
    public static final CustomPayload.Id<MinimapExplorePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "minimap_explore"));
    public static final PacketCodec<RegistryByteBuf, MinimapExplorePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.chunks().size());
                for (long[] cz : value.chunks()) {
                    buf.writeVarInt((int) cz[0]);
                    buf.writeVarInt((int) cz[1]);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<long[]> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    long cx = buf.readVarInt();
                    long cz = buf.readVarInt();
                    list.add(new long[]{cx, cz});
                }
                return new MinimapExplorePayload(list);
            });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
