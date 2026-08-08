package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** C2S-пакет: Паймон объявила задание про слаймов — просим сервер подготовить
 *  трёх тренировочных Гидро слаймов вокруг игрока. */
public record SlimeTrainingSpawnPayload() implements CustomPayload {
    public static final CustomPayload.Id<SlimeTrainingSpawnPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "slime_training_spawn"));
    public static final PacketCodec<RegistryByteBuf, SlimeTrainingSpawnPayload> CODEC = PacketCodec.of(
            (value, buf) -> { },
            buf -> new SlimeTrainingSpawnPayload());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
