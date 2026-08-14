package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: управление уроками Паймон из команды /teyvat lessons.
 *  action: reset (заново с знакомства), complete (все уроки пройдены),
 *  jump (сразу к уроку lesson). */
public record TutorialControlPayload(String action, String lesson) implements CustomPayload {
    public static final CustomPayload.Id<TutorialControlPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "tutorial_control"));
    public static final PacketCodec<RegistryByteBuf, TutorialControlPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeString(value.action());
                buf.writeString(value.lesson());
            },
            buf -> new TutorialControlPayload(buf.readString(32), buf.readString(32)));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
