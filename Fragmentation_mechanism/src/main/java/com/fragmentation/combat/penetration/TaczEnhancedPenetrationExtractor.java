package com.fragmentation.combat.penetration;

import com.fragmentation.FragmentationMod;
import java.util.Optional;

public class TaczEnhancedPenetrationExtractor implements PenetrationExtractor {
    private static final String TACZ_ARMOR_IGNORE_KEY = "armor_ignore";
    private static final String TACZ_AMMO_KEY = "tacz:ammo_id";
    private static final String TACZ_DAMAGE_KEY = "damage";

    @Override
    public Optional<Float> tryExtract(PenetrationResolveContext context) {
        Optional<Float> fromProjectile = context.getAttachment("projectile")
            .flatMap(this::extractFromProjectile);

        if (fromProjectile.isPresent()) {
            return fromProjectile;
        }

        return context.getAttachment("shooter")
            .flatMap(this::extractFromShooter);
    }

    private Optional<Float> extractFromProjectile(Object projectileObj) {
        try {
            net.minecraft.world.entity.Entity projectile =
                (net.minecraft.world.entity.Entity) projectileObj;

            if (projectile == null) {
                return Optional.empty();
            }

            try {
                Class<?> bulletClass = projectile.getClass();
                java.lang.reflect.Field armorIgnoreField = null;
                try {
                    armorIgnoreField = bulletClass.getDeclaredField("armorIgnore");
                } catch (NoSuchFieldException e) {
                    Class<?> parentClass = bulletClass.getSuperclass();
                    while (parentClass != null && !parentClass.getName().equals("java.lang.Object")) {
                        try {
                            armorIgnoreField = parentClass.getDeclaredField("armorIgnore");
                            break;
                        } catch (NoSuchFieldException ex) {
                            parentClass = parentClass.getSuperclass();
                        }
                    }
                }

                if (armorIgnoreField != null) {
                    armorIgnoreField.setAccessible(true);
                    Object value = armorIgnoreField.get(projectile);
                    if (value instanceof Number) {
                        float armorIgnoreValue = ((Number) value).floatValue();
                        float penetrationValue = armorIgnoreValue * 100.0F;
                        return Optional.of(penetrationValue);
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            net.minecraft.nbt.CompoundTag persistentData = projectile.getPersistentData();
            net.minecraft.nbt.CompoundTag fullTag = projectile.saveWithoutId(new net.minecraft.nbt.CompoundTag());

            if (fullTag != null && fullTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(fullTag, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                return Optional.of(penetrationValue);
            }

            if (persistentData != null && persistentData.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(persistentData, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                return Optional.of(penetrationValue);
            }

            if (fullTag != null && !fullTag.isEmpty()) {
                Optional<Float> result = findArmorIgnoreInTag(fullTag, "FullNBT");
                if (result.isPresent()) {
                    return result;
                }
            }

            if (persistentData != null && !persistentData.isEmpty()) {
                Optional<Float> result = findArmorIgnoreInTag(persistentData, "PersistentData");
                if (result.isPresent()) {
                    return result;
                }
            }

        } catch (Exception e) {
            // ignore
        }

        return Optional.empty();
    }

    private float getFloatFromTag(net.minecraft.nbt.CompoundTag tag, String key) {
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_FLOAT)) {
            return tag.getFloat(key);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
            return (float) tag.getDouble(key);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_INT)) {
            return (float) tag.getInt(key);
        }
        return 0.0F;
    }

    private Optional<Float> extractFromShooter(Object shooterObj) {
        try {
            if (!(shooterObj instanceof net.minecraft.world.entity.LivingEntity shooter)) {
                return Optional.empty();
            }

            net.minecraft.world.item.ItemStack weaponStack = shooter.getMainHandItem();
            if (weaponStack.isEmpty()) {
                return Optional.empty();
            }

            net.minecraft.nbt.CompoundTag weaponTag = weaponStack.getTag();
            if (weaponTag == null || weaponTag.isEmpty()) {
                return Optional.empty();
            }

            if (weaponTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(weaponTag, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                return Optional.of(penetrationValue);
            }

            Optional<Float> result = findArmorIgnoreInTag(weaponTag, "WeaponNBT");
            if (result.isPresent()) {
                return result;
            }

        } catch (Exception e) {
            // ignore
        }

        return Optional.empty();
    }

    private Optional<Float> findArmorIgnoreInTag(net.minecraft.nbt.CompoundTag tag, String source) {
        if (tag.contains(TACZ_ARMOR_IGNORE_KEY)) {
            float armorIgnore = getFloatFromTag(tag, TACZ_ARMOR_IGNORE_KEY);
            float penetrationValue = armorIgnore * 100.0F;
            return Optional.of(penetrationValue);
        }

        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag subTag = tag.getCompound(key);

                if (subTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                    float armorIgnore = getFloatFromTag(subTag, TACZ_ARMOR_IGNORE_KEY);
                    float penetrationValue = armorIgnore * 100.0F;
                    return Optional.of(penetrationValue);
                }

                Optional<Float> result = findArmorIgnoreInTag(subTag, source + " > " + key);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        return Optional.empty();
    }
}