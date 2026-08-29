package net.teyvat.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;

/**
 * C2S-пакет: клиент передаёт серверу текущую выносливость (клиентская
 * система бега/рывка остаётся локальной). Сервер использует это значение
 * как базу на время карабканья — дальше стамина авторитетна на сервере.
 */
public record ClimbStaminaPayload(float stamina) implements CustomPayload {
    public static final CustomPayload.Id<ClimbStaminaPayload> ID =
            new CustomPayload.Id<>(Identifier.of(TeyvatMod.MOD_ID, "climb_stamina"));
    public static final PacketCodec<RegistryByteBuf, ClimbStaminaPayload> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeFloat(value.stamina()),
            buf -> new ClimbStaminaPayload(buf.readFloat()));

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
