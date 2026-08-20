package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S: игрок нажал X чтобы пропустить обучение. Сервер отмечает все задания выполненными. */
public record SkipTrainingPayload() implements CustomPayload {
    public static final CustomPayload.Id<SkipTrainingPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "skip_training"));
    public static final PacketCodec<RegistryByteBuf, SkipTrainingPayload> CODEC = PacketCodec.of(
            (value, buf) -> {},
            buf -> new SkipTrainingPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
