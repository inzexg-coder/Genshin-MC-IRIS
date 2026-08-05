package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** Пустой S2C-пакет: сервер просит клиент открыть «Заметки путешественника». */
public record NotesOpenPayload() implements CustomPayload {
    public static final CustomPayload.Id<NotesOpenPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "open_notes"));
    public static final PacketCodec<RegistryByteBuf, NotesOpenPayload> CODEC =
            PacketCodec.unit(new NotesOpenPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
