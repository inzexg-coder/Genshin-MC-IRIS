package net.teyvat.player;

import java.util.UUID;

import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.teyvat.TeyvatMod;
import net.teyvat.combat.SwordCombo;

/** Различия боевых и двигательных характеристик двух Путешественников. */
public enum TravelerProfile {
    UNCHOSEN(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f),
    LUMINE(0.93f, 1.115f, 1.0f, 1.0f, 1.0f, 1.0f),
    AETHER(1.0f, 1.0f, 1.05f, 1.08f, 1.08f, 1.05f);

    @FunctionalInterface
    public interface ChoiceLookup {
        String get(UUID playerId);
    }

    private static final String TAG_PREFIX = "teyvat:traveler_";
    private static volatile ChoiceLookup clientChoiceLookup;
    private static final Identifier ATTACK_SPEED_ID = id("traveler_attack_speed");
    private static final Identifier MOVEMENT_SPEED_ID = id("traveler_movement_speed");
    private static final Identifier WATER_SPEED_ID = id("traveler_water_speed");
    private static final Identifier SNEAK_SPEED_ID = id("traveler_sneak_speed");
    private static final Identifier BLOCK_BREAK_ID = id("traveler_block_break_speed");

    private final float normalAttackDurationScale;
    private final float chargedDamageMultiplier;
    private final float movementMultiplier;
    private final float waterMovementMultiplier;
    private final float climbingSpeedPlaceholder;
    private final float utilityActionMultiplier;

    TravelerProfile(
            float normalAttackDurationScale,
            float chargedDamageMultiplier,
            float movementMultiplier,
            float waterMovementMultiplier,
            float climbingSpeedPlaceholder,
            float utilityActionMultiplier) {
        this.normalAttackDurationScale = normalAttackDurationScale;
        this.chargedDamageMultiplier = chargedDamageMultiplier;
        this.movementMultiplier = movementMultiplier;
        this.waterMovementMultiplier = waterMovementMultiplier;
        this.climbingSpeedPlaceholder = climbingSpeedPlaceholder;
        this.utilityActionMultiplier = utilityActionMultiplier;
    }

    public static void setClientChoiceLookup(ChoiceLookup lookup) {
        clientChoiceLookup = lookup;
    }

    public static TravelerProfile fromPlayer(PlayerEntity player) {
        if (player.getEntityWorld().isClient() && clientChoiceLookup != null) {
            return fromChoice(clientChoiceLookup.get(player.getUuid()));
        }
        for (String tag : player.getCommandTags()) {
            if (!tag.startsWith(TAG_PREFIX)) {
                continue;
            }
            TravelerProfile profile = fromChoice(tag.substring(TAG_PREFIX.length()));
            if (profile != UNCHOSEN) {
                return profile;
            }
        }
        return UNCHOSEN;
    }

    public static TravelerProfile fromChoice(String choice) {
        if ("lumine".equals(choice)) {
            return LUMINE;
        }
        if ("aether".equals(choice)) {
            return AETHER;
        }
        return UNCHOSEN;
    }

    public int normalAttackDurationTicks(int baseTicks) {
        return Math.max(1, Math.round(baseTicks * normalAttackDurationScale));
    }

    public float attackDamageMultiplier(int hitIndex) {
        return hitIndex == SwordCombo.CHARGE_INDEX ? chargedDamageMultiplier : 1.0f;
    }

    public float dashSpeedMultiplier() {
        return movementMultiplier;
    }

    public float climbingSpeedPlaceholder() {
        return climbingSpeedPlaceholder;
    }

    public void applyAttributes(ServerPlayerEntity player) {
        apply(player.getAttributeInstance(EntityAttributes.ATTACK_SPEED), ATTACK_SPEED_ID,
                this == LUMINE ? 0.07 : 0.0);
        apply(player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED), MOVEMENT_SPEED_ID,
                (movementMultiplier - 1.0f));
        apply(player.getAttributeInstance(EntityAttributes.WATER_MOVEMENT_EFFICIENCY), WATER_SPEED_ID,
                (waterMovementMultiplier - 1.0f));
        apply(player.getAttributeInstance(EntityAttributes.SNEAKING_SPEED), SNEAK_SPEED_ID,
                (utilityActionMultiplier - 1.0f));
        apply(player.getAttributeInstance(EntityAttributes.BLOCK_BREAK_SPEED), BLOCK_BREAK_ID,
                (utilityActionMultiplier - 1.0f));
    }

    private static void apply(EntityAttributeInstance instance, Identifier id, double bonus) {
        if (instance == null) {
            return;
        }
        if (bonus == 0.0) {
            instance.removeModifier(id);
            return;
        }
        instance.overwritePersistentModifier(new EntityAttributeModifier(
                id, bonus, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static Identifier id(String path) {
        return Identifier.of(TeyvatMod.MOD_ID, path);
    }
}
