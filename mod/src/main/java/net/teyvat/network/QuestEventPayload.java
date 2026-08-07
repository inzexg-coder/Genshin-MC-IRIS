package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: клиент сообщает серверу о событии квеста (например, Паймон представилась). */
public record QuestEventPayload(String questId) implements CustomPayload {
    public static final CustomPayload.Id<QuestEventPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "quest_event"));
    public static final PacketCodec<RegistryByteBuf, QuestEventPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeString(value.questId()),
            buf -> new QuestEventPayload(buf.readString(64)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
