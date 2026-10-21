package com.fragmentation.combat.penetration;

import com.fragmentation.FragmentationMod;
import java.util.Optional;

public class ArcanaPenetrationExtractor implements PenetrationExtractor {

    private static final String ARCANA_LEVEL_KEY = "arcana:bullet_level";
    private static final String ARCANA_PENETRATION_KEY = "arcana:penetration";
    private static final String ARCANA_DAMAGE_KEY = "arcana:damage";

    private static boolean arcanaAvailable = false;
    private static boolean checkedArcana = false;

    public static boolean isArcanaAvailable() {
        if (!checkedArcana) {
            checkedArcana = true;
            try {
                Class.forName("group.taczexpands.coremod.AmmoItemDataAccessorTransformer");
                arcanaAvailable = true;
                FragmentationMod.LOGGER.info("[ArcanaExtractor] Arcana mod detected");
            } catch (ClassNotFoundException e) {
                arcanaAvailable = false;
                FragmentationMod.LOGGER.info("[ArcanaExtractor] Arcana mod not detected");
            }
        }
        return arcanaAvailable;
    }

    @Override
    public Optional<Float> tryExtract(PenetrationResolveContext context) {
        if (!isArcanaAvailable()) {
            return Optional.empty();
        }

        Optional<Float> fromProjectile = context.getAttachment("projectile")
                .flatMap(this::extractFromProjectile);
        if (fromProjectile.isPresent()) {
            FragmentationMod.LOGGER.info("[ArcanaExtractor] Got penetration from projectile: {}", fromProjectile.get());
            return fromProjectile;
        }

        Optional<Float> fromShooter = context.getAttachment("shooter")
                .flatMap(this::extractFromShooter);
        if (fromShooter.isPresent()) {
            FragmentationMod.LOGGER.info("[ArcanaExtractor] Got penetration from shooter: {}", fromShooter.get());
            return fromShooter;
        }

        return Optional.empty();
    }

    private Optional<Float> extractFromProjectile(Object projectileObj) {
        try {
            net.minecraft.world.entity.Entity projectile = 
                (net.minecraft.world.entity.Entity) projectileObj;
            
            if (projectile == null) {
                return Optional.empty();
            }

            FragmentationMod.LOGGER.info("[ArcanaExtractor] Processing projectile: {}", projectile.getType().toString());

            try {
                Class<?> bulletClass = projectile.getClass();
                
                java.lang.reflect.Field levelField = null;
                try {
                    levelField = bulletClass.getDeclaredField("bulletLevel");
                } catch (NoSuchFieldException e) {
                    Class<?> parentClass = bulletClass.getSuperclass();
                    while (parentClass != null && !parentClass.getName().equals("java.lang.Object")) {
                        try {
                            levelField = parentClass.getDeclaredField("bulletLevel");
                            break;
                        } catch (NoSuchFieldException ex) {
                            parentClass = parentClass.getSuperclass();
                        }
                    }
                }

                if (levelField != null) {
                    levelField.setAccessible(true);
                    Object value = levelField.get(projectile);
                    if (value instanceof Number) {
                        int bulletLevel = ((Number) value).intValue();
                        float penetration = bulletLevel * 10.0F;
                        FragmentationMod.LOGGER.info("[ArcanaExtractor] Found bulletLevel: {} -> penetration: {}", bulletLevel, penetration);
                        return Optional.of(penetration);
                    }
                }
            } catch (Exception e) {
                FragmentationMod.LOGGER.debug("[ArcanaExtractor] Error accessing bulletLevel field: {}", e.getMessage());
            }

            net.minecraft.nbt.CompoundTag persistentData = projectile.getPersistentData();
            net.minecraft.nbt.CompoundTag fullTag = projectile.saveWithoutId(new net.minecraft.nbt.CompoundTag());

            if (fullTag != null && !fullTag.isEmpty()) {
                FragmentationMod.LOGGER.info("[ArcanaExtractor] Scanning projectile NBT for Arcana data...");
                
                Optional<Float> result = findArcanaLevelInTag(fullTag, "FullNBT");
                if (result.isPresent()) {
                    return result;
                }
            }

            if (persistentData != null && !persistentData.isEmpty()) {
                Optional<Float> result = findArcanaLevelInTag(persistentData, "PersistentData");
                if (result.isPresent()) {
                    return result;
                }
            }

        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ArcanaExtractor] Exception: {}", e.getMessage());
        }

        return Optional.empty();
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

            FragmentationMod.LOGGER.info("[ArcanaExtractor] Scanning weapon NBT for Arcana data...");
            
            return findArcanaLevelInTag(weaponTag, "WeaponNBT");

        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ArcanaExtractor] Exception extracting from shooter: {}", e.getMessage());
        }

        return Optional.empty();
    }

    private Optional<Float> findArcanaLevelInTag(net.minecraft.nbt.CompoundTag tag, String source) {
        String[] levelKeys = {
            ARCANA_LEVEL_KEY,
            "arcana_level",
            "bullet_level",
            "ammo_level",
            "level",
            "arcana:level"
        };

        for (String key : levelKeys) {
            if (tag.contains(key)) {
                int level = getIntFromTag(tag, key);
                if (level > 0) {
                    float penetration = level * 10.0F;
                    FragmentationMod.LOGGER.info("[ArcanaExtractor] Found {} in {}: {} -> penetration: {}", 
                            key, source, level, penetration);
                    return Optional.of(penetration);
                }
            }
        }

        String[] penetrationKeys = {
            ARCANA_PENETRATION_KEY,
            "penetration",
            "armor_penetration",
            "penetration_level"
        };

        for (String key : penetrationKeys) {
            if (tag.contains(key)) {
                float penetration = getFloatFromTag(tag, key);
                if (penetration > 0) {
                    FragmentationMod.LOGGER.info("[ArcanaExtractor] Found {} in {}: {}", key, source, penetration);
                    return Optional.of(penetration);
                }
            }
        }

        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag subTag = tag.getCompound(key);
                
                for (String levelKey : levelKeys) {
                    if (subTag.contains(levelKey)) {
                        int level = getIntFromTag(subTag, levelKey);
                        if (level > 0) {
                            float penetration = level * 10.0F;
                            FragmentationMod.LOGGER.info("[ArcanaExtractor] Found {} in {} > {}: {} -> penetration: {}", 
                                    levelKey, source, key, level, penetration);
                            return Optional.of(penetration);
                        }
                    }
                }

                for (String penetrationKey : penetrationKeys) {
                    if (subTag.contains(penetrationKey)) {
                        float penetration = getFloatFromTag(subTag, penetrationKey);
                        if (penetration > 0) {
                            FragmentationMod.LOGGER.info("[ArcanaExtractor] Found {} in {} > {}: {}", 
                                    penetrationKey, source, key, penetration);
                            return Optional.of(penetration);
                        }
                    }
                }

                Optional<Float> result = findArcanaLevelInTag(subTag, source + " > " + key);
                if (result.isPresent()) {
                    return result;
                }
            }
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

    private int getIntFromTag(net.minecraft.nbt.CompoundTag tag, String key) {
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_INT)) {
            return tag.getInt(key);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_SHORT)) {
            return tag.getShort(key);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_FLOAT)) {
            return (int) tag.getFloat(key);
        }
        if (tag.contains(key, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
            return (int) tag.getDouble(key);
        }
        return 0;
    }
}