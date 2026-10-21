package com.fragmentation.combat.penetration;

import com.fragmentation.FragmentationMod;
import java.util.Optional;

public class TaczPenetrationExtractor implements PenetrationExtractor {

    private static final String TACZ_ARMOR_IGNORE_KEY = "armor_ignore";
    private static final String TACZ_AMMO_KEY = "tacz:ammo_id";
    private static final String TACZ_DAMAGE_KEY = "damage";

    @Override
    public Optional<Float> tryExtract(PenetrationResolveContext context) {
        Optional<Float> fromProjectile = context.getAttachment("projectile")
            .flatMap(this::extractFromProjectile);
        
        if (fromProjectile.isPresent()) {
            FragmentationMod.LOGGER.info("[TACZ Extractor] Got penetration from projectile: {}", fromProjectile.get());
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
                FragmentationMod.LOGGER.debug("[TACZ Extractor] Projectile is null");
                return Optional.empty();
            }
            
            FragmentationMod.LOGGER.info("[TACZ Extractor] Processing projectile: {}", projectile.getType().toString());
            FragmentationMod.LOGGER.info("[TACZ Extractor] Projectile class: {}", projectile.getClass().getName());
            
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
                            FragmentationMod.LOGGER.info("[TACZ Extractor] Found armorIgnore in parent class: {}", parentClass.getSimpleName());
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
                        FragmentationMod.LOGGER.info("[TACZ Extractor] Successfully read armorIgnore field: {} -> penetration: {}", 
                            armorIgnoreValue, penetrationValue);
                        
                        return Optional.of(penetrationValue);
                    }
                }
            } catch (Exception e) {
                FragmentationMod.LOGGER.error("[TACZ Extractor] Error accessing armorIgnore field: {}", e.getMessage());
            }
            
            net.minecraft.nbt.CompoundTag persistentData = projectile.getPersistentData();
            net.minecraft.nbt.CompoundTag fullTag = projectile.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            
            if (fullTag != null && fullTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(fullTag, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                FragmentationMod.LOGGER.info("[TACZ Extractor] Found armor_ignore at FULL NBT root: {}, converted to: {}", 
                    armorIgnore, penetrationValue);
                return Optional.of(penetrationValue);
            }
            
            if (persistentData != null && persistentData.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(persistentData, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                FragmentationMod.LOGGER.info("[TACZ Extractor] Found armor_ignore at PERSISTENT root: {}, converted to: {}", 
                    armorIgnore, penetrationValue);
                return Optional.of(penetrationValue);
            }
            
            if (fullTag != null && !fullTag.isEmpty()) {
                FragmentationMod.LOGGER.info("[TACZ Extractor] Searching full NBT recursively...");
                Optional<Float> result = findArmorIgnoreInTag(fullTag, "FullNBT");
                if (result.isPresent()) {
                    return result;
                }
            }
            
            if (persistentData != null && !persistentData.isEmpty()) {
                FragmentationMod.LOGGER.info("[TACZ Extractor] Searching persistentData recursively...");
                Optional<Float> result = findArmorIgnoreInTag(persistentData, "PersistentData");
                if (result.isPresent()) {
                    return result;
                }
            }
            
            if (persistentData != null && persistentData.contains(TACZ_AMMO_KEY, net.minecraft.nbt.Tag.TAG_STRING)) {
                String ammoId = persistentData.getString(TACZ_AMMO_KEY);
                FragmentationMod.LOGGER.info("[TACZ Extractor] Found ammo ID: {}, using fallback config", ammoId);
                if (ammoId != null && !ammoId.isEmpty()) {
                    float penetration = TaczAmmoPenetrationConfig.getPenetrationForAmmo(ammoId);
                    FragmentationMod.LOGGER.info("[TACZ Extractor] Using configured penetration for {}: {}", ammoId, penetration);
                    return Optional.of(penetration);
                }
            }
            
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[TACZ Extractor] Exception: {}", e.getMessage());
            return Optional.empty();
        }
        
        FragmentationMod.LOGGER.warn("[TACZ Extractor] No penetration value extracted from projectile, returning empty");
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
            
            FragmentationMod.LOGGER.info("[TACZ Extractor] Weapon item: {}", weaponStack.getItem().toString());
            
            net.minecraft.nbt.CompoundTag weaponTag = weaponStack.getTag();
            
            if (weaponTag == null || weaponTag.isEmpty()) {
                return Optional.empty();
            }
            
            Float finalArmorIgnore = getArmorIgnoreFromTaczRuntime(weaponStack);
            if (finalArmorIgnore != null) {
                float penetration = finalArmorIgnore * 100.0F;
                FragmentationMod.LOGGER.info("[TACZ Extractor] Got final armor_ignore from TACZ runtime: {} -> penetration: {}", 
                    finalArmorIgnore, penetration);
                return Optional.of(penetration);
            }
            
            if (weaponTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                float armorIgnore = getFloatFromTag(weaponTag, TACZ_ARMOR_IGNORE_KEY);
                float penetrationValue = armorIgnore * 100.0F;
                FragmentationMod.LOGGER.info("[TACZ Extractor] Found armor_ignore in weapon root: {}, converted to: {}", 
                    armorIgnore, penetrationValue);
                return Optional.of(penetrationValue);
            }
            
            FragmentationMod.LOGGER.info("[TACZ Extractor] Searching weapon NBT recursively...");
            Optional<Float> result = findArmorIgnoreInTag(weaponTag, "WeaponNBT");
            if (result.isPresent()) {
                return result;
            }
            
            if (weaponTag.contains("GunId", net.minecraft.nbt.Tag.TAG_STRING)) {
                String gunId = weaponTag.getString("GunId");
                FragmentationMod.LOGGER.info("[TACZ Extractor] Found GunId: {}, querying TACZ config...", gunId);
                
                Float basePenetration = TaczAmmoPenetrationConfig.getPenetrationForGunId(gunId);
                if (basePenetration != null) {
                    FragmentationMod.LOGGER.info("[TACZ Extractor] Base penetration from GunId config: {}", basePenetration);
                    float attachmentBonus = 0.0F;
                    
                    for (String key : weaponTag.getAllKeys()) {
                        if (key.startsWith("Attachment")) {
                            String attachmentId = weaponTag.getString(key);
                            if (attachmentId.contains("AP") || attachmentId.contains("ArmorPiercing") || attachmentId.contains("穿甲")) {
                                attachmentBonus += 37.0F;
                                FragmentationMod.LOGGER.info("[TACZ Extractor] AP ammo detected, bonus: +{}", attachmentBonus);
                            }
                        }
                    }
                    
                    float totalPenetration = basePenetration + attachmentBonus;
                    FragmentationMod.LOGGER.info("[TACZ Extractor] Total penetration: {} (base: {} + attachments: {})", 
                        totalPenetration, basePenetration, attachmentBonus);
                    return Optional.of(totalPenetration);
                }
            }
            
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[TACZ Extractor] Exception extracting from shooter: {}", e.getMessage());
        }
        
        return Optional.empty();
    }
    
    private Float getArmorIgnoreFromTaczRuntime(net.minecraft.world.item.ItemStack weaponStack) {
        try {
            Class<?> itemClass = weaponStack.getItem().getClass();
            
            String[] possibleMethodNames = {
                "getArmorIgnore", 
                "getFinalArmorIgnore",
                "getArmorIgnoreValue",
                "getCurrentArmorIgnore",
                "getBulletArmorIgnore",
                "getArmorPenetration",
                "getPenetration"
            };
            
            for (String methodName : possibleMethodNames) {
                try {
                    java.lang.reflect.Method method = itemClass.getMethod(methodName, net.minecraft.world.item.ItemStack.class);
                    Object result = method.invoke(weaponStack.getItem(), weaponStack);
                    if (result instanceof Number) {
                        float value = ((Number) result).floatValue();
                        FragmentationMod.LOGGER.info("[TACZ Extractor] Found method {}(ItemStack): {}", methodName, value);
                        return value;
                    }
                } catch (NoSuchMethodException e) {
                }
                
                try {
                    java.lang.reflect.Method method = itemClass.getMethod(methodName);
                    Object result = method.invoke(weaponStack.getItem());
                    if (result instanceof Number) {
                        float value = ((Number) result).floatValue();
                        FragmentationMod.LOGGER.info("[TACZ Extractor] Found method {}: {}", methodName, value);
                        return value;
                    }
                } catch (NoSuchMethodException e) {
                }
            }
            
            java.lang.reflect.Field[] fields = itemClass.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                String fieldName = field.getName().toLowerCase();
                if (fieldName.contains("armorignore") || fieldName.contains("armor_ignore") || 
                    fieldName.contains("penetration")) {
                    field.setAccessible(true);
                    Object value = field.get(weaponStack.getItem());
                    if (value instanceof Number) {
                        float floatValue = ((Number) value).floatValue();
                        FragmentationMod.LOGGER.info("[TACZ Extractor] Found field {}: {}", field.getName(), floatValue);
                        return floatValue;
                    }
                }
            }
            
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[TACZ Extractor] Error accessing TACZ runtime: {}", e.getMessage());
        }
        
        return null;
    }
    
    private Optional<Float> findArmorIgnoreInTag(net.minecraft.nbt.CompoundTag tag, String source) {
        if (tag.contains(TACZ_ARMOR_IGNORE_KEY)) {
            float armorIgnore = getFloatFromTag(tag, TACZ_ARMOR_IGNORE_KEY);
            float penetrationValue = armorIgnore * 100.0F;
            FragmentationMod.LOGGER.info("[TACZ Extractor] Found armor_ignore in {} at root: {}, converted to: {}", 
                source, armorIgnore, penetrationValue);
            return Optional.of(penetrationValue);
        }
        
        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag subTag = tag.getCompound(key);
                
                if (subTag.contains(TACZ_ARMOR_IGNORE_KEY)) {
                    float armorIgnore = getFloatFromTag(subTag, TACZ_ARMOR_IGNORE_KEY);
                    float penetrationValue = armorIgnore * 100.0F;
                    FragmentationMod.LOGGER.info("[TACZ Extractor] Found armor_ignore in {} > {}: {}, converted to: {}", 
                        source, key, armorIgnore, penetrationValue);
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