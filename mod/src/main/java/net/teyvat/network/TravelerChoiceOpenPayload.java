package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** Пустой S2C-пакет: сервер просит клиент открыть экран выбора путешественника. */
public record TravelerChoiceOpenPayload() implements CustomPayload {
    public static final CustomPayload.Id<TravelerChoiceOpenPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "open_traveler_choice"));
    public static final PacketCodec<RegistryByteBuf, TravelerChoiceOpenPayload> CODEC =
            PacketCodec.unit(new TravelerChoiceOpenPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
