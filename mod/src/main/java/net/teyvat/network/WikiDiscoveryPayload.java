package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: игрок открыл новую запись вики (первая встреча / урок Паймон).
 *  Клиент молча добавляет страницу в заметки — никаких уведомлений. */
public record WikiDiscoveryPayload(String entryId) implements CustomPayload {
    public static final CustomPayload.Id<WikiDiscoveryPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "wiki_discovery"));
    public static final PacketCodec<RegistryByteBuf, WikiDiscoveryPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeString(value.entryId()),
            buf -> new WikiDiscoveryPayload(buf.readString(64)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
