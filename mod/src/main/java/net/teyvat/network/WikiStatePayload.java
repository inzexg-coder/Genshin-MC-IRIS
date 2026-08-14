package net.teyvat.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

import java.util.List;

/** S2C-пакет: полный список открытых записей вики (при входе в мир).
 *  Неоткрытые записи клиенту не передаются — в заметках их не видно. */
public record WikiStatePayload(List<String> discovered) implements CustomPayload {
    public static final CustomPayload.Id<WikiStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "wiki_state"));
    public static final PacketCodec<RegistryByteBuf, WikiStatePayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeCollection(value.discovered(), PacketByteBuf::writeString),
            buf -> new WikiStatePayload(buf.readList(PacketByteBuf::readString)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
