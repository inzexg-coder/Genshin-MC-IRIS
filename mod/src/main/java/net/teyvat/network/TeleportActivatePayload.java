package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.teyvat.TeyvatMod;

/** C2S-пакет: игрок нажал Q рядом с точкой телепортации и хочет её активировать.
 *  Сервер проверяет расстояние и блок, затем помечает точку как активированную для этого игрока. */
public record TeleportActivatePayload(BlockPos pos) implements CustomPayload {
    public static final CustomPayload.Id<TeleportActivatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "teleport_activate"));
    public static final PacketCodec<RegistryByteBuf, TeleportActivatePayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeBlockPos(value.pos()),
            buf -> new TeleportActivatePayload(buf.readBlockPos()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
