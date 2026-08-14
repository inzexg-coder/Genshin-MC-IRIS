package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: какие квесты уже выполнены у игрока. Клиент не даёт повторять мини-уроки. */
public record QuestStatePayload(boolean meetPaimon, boolean tryScroll, boolean tryZoom,
        boolean trySprint, boolean tryDash, boolean tryAttack, boolean tryPickup) implements CustomPayload {
    public static final CustomPayload.Id<QuestStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "quest_state"));
    public static final PacketCodec<RegistryByteBuf, QuestStatePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeBoolean(value.meetPaimon());
                buf.writeBoolean(value.tryScroll());
                buf.writeBoolean(value.tryZoom());
                buf.writeBoolean(value.trySprint());
                buf.writeBoolean(value.tryDash());
                buf.writeBoolean(value.tryAttack());
                buf.writeBoolean(value.tryPickup());
            },
            buf -> new QuestStatePayload(buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
