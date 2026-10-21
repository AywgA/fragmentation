package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.PlateConfig;
import com.fragmentation.config.ShieldConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public class DurabilityUtils {

    private static final String VANILLA_DAMAGE_TAG = "Damage";
    private static final String FRACTUREPOINT_DURABILITY_TAG = "fragmentation_durability";
    private static final String FRACTUREPOINT_MAX_DURABILITY_TAG = "fragmentation_max_durability";

    public static int getMaxDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(FRACTUREPOINT_MAX_DURABILITY_TAG)) {
            return tag.getInt(FRACTUREPOINT_MAX_DURABILITY_TAG);
        }

        try {
            ArmorConfig config = FragmentationMod.getArmorConfig();
            if (config == null) {
                return 0;
            }
            ArmorConfig.ArmorEntry entry = config.getArmorForItem(stack);
            if (entry != null) {
                return entry.maxDurability;
            }
        } catch (Throwable ignored) {
        }

        try {
            PlateConfig plateConfig = FragmentationMod.getPlateConfig();
            if (plateConfig != null) {
                PlateConfig.PlateEntry plateEntry =
                    plateConfig.getPlateForItem(stack);
                if (plateEntry != null) {
                    return plateEntry.maxDurability;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            ShieldConfig shieldConfig = FragmentationMod.getShieldConfig();
            if (shieldConfig == null) {
                return 0;
            }
            ShieldConfig.ShieldEntry shieldEntry = shieldConfig.getShieldForItem(stack);
            if (shieldEntry != null) {
                return shieldEntry.maxDurability;
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    public static int getCurrentDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        int maxDurability = getMaxDurability(stack);
        if (maxDurability <= 0) {
            return 0;
        }

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(FRACTUREPOINT_DURABILITY_TAG)) {
            int fpDurability = tag.getInt(FRACTUREPOINT_DURABILITY_TAG);
            return Math.max(0, Math.min(maxDurability, fpDurability));
        }

        int vanillaDamage = 0;
        if (tag != null && tag.contains(VANILLA_DAMAGE_TAG)) {
            vanillaDamage = tag.getInt(VANILLA_DAMAGE_TAG);
        }

        return Math.max(0, Math.min(maxDurability, maxDurability - vanillaDamage));
    }

    public static boolean isBroken(ItemStack stack) {
        return getCurrentDurability(stack) <= 0;
    }

    public static void setCurrentDurability(ItemStack stack, int durability) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        int maxDurability = getMaxDurability(stack);
        if (maxDurability <= 0) {
            return;
        }

        int clampedDurability = Math.max(0, Math.min(maxDurability, durability));
        int damageValue = maxDurability - clampedDurability;

        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(VANILLA_DAMAGE_TAG, damageValue);
        tag.putInt(FRACTUREPOINT_DURABILITY_TAG, clampedDurability);
        tag.putInt(FRACTUREPOINT_MAX_DURABILITY_TAG, maxDurability);

        if (tag.contains("Unbreakable")) {
            tag.putBoolean("Unbreakable", false);
        }

        ArmorSoundManager.checkAndResetPenetrationState(stack);
    }

    public static int getDamageValue(ItemStack stack) {
        int maxDurability = getMaxDurability(stack);
        if (maxDurability <= 0) {
            return 0;
        }
        return maxDurability - getCurrentDurability(stack);
    }

    public static boolean isConfiguredArmor(ItemStack stack) {
        return getMaxDurability(stack) > 0;
    }
}
