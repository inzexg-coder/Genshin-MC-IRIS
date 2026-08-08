package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/** S2C-пакет: сервер сообщает клиенту уровень моба для подписи «Ур. X» над головой.
 *  Уровень стабилен (хранится в NBT сущности), поэтому шлётся один раз — при загрузке
 *  моба в мир и при входе игрока для уже загруженных мобов рядом. */
public record MobLevelSyncPayload(int entityId, int level) implements CustomPayload {
    public static final CustomPayload.Id<MobLevelSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "mob_level_sync"));
    public static final PacketCodec<RegistryByteBuf, MobLevelSyncPayload> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeVarInt(value.entityId());
                buf.writeVarInt(value.level());
            },
            buf -> new MobLevelSyncPayload(buf.readVarInt(), buf.readVarInt()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
