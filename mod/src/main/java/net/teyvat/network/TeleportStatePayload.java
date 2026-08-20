package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.teyvat.TeyvatMod;

import java.util.ArrayList;
import java.util.List;

/** S2C-пакет: сервер отправляет клиенту полный список активированных точек телепортации.
 *  Отправляется при входе игрока и после каждой новой активации. Клиент локально
 *  заменяет красные блоки на синие (пер-player визуал). */
public record TeleportStatePayload(List<BlockPos> positions) implements CustomPayload {
    public static final CustomPayload.Id<TeleportStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "teleport_state"));
    public static final PacketCodec<RegistryByteBuf, TeleportStatePayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.positions().size());
                for (BlockPos pos : value.positions()) {
                    buf.writeBlockPos(pos);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<BlockPos> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(buf.readBlockPos());
                }
                return new TeleportStatePayload(list);
            });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
