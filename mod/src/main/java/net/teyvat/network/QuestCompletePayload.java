package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: сервер отметил квест выполненным (например, победа над слаймами
 *  в обучении). Клиент показывает тост и завершает урок Паймон. */
public record QuestCompletePayload(String questId, String questTitle) implements CustomPayload {
    public static final CustomPayload.Id<QuestCompletePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "quest_complete"));
    public static final PacketCodec<RegistryByteBuf, QuestCompletePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.questId());
                buf.writeString(value.questTitle());
            },
            buf -> new QuestCompletePayload(buf.readString(64), buf.readString(128)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
