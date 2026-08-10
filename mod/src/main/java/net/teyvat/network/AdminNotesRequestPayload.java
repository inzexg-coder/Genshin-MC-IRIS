package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: клиент просит открыть «О сборке» (Shift+N). Сервер проверяет права. */
public record AdminNotesRequestPayload() implements CustomPayload {
    public static final CustomPayload.Id<AdminNotesRequestPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "admin_notes_request"));
    public static final PacketCodec<RegistryByteBuf, AdminNotesRequestPayload> CODEC =
            PacketCodec.unit(new AdminNotesRequestPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
