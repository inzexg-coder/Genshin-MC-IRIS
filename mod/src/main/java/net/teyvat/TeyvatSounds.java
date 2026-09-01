package net.teyvat;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Кастомные звуки мода.
 */
public final class TeyvatSounds {
    public static final SoundEvent EAT_CHEW = SoundEvent.of(
            Identifier.of(TeyvatMod.MOD_ID, "eat_chew"));

    public static void register() {
        Registry.register(Registries.SOUND_EVENT, EAT_CHEW.id(), EAT_CHEW);
    }
}
