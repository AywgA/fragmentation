package com.fragmentation.combat.penetration;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

public class AttachmentFloatExtractor implements PenetrationExtractor {
    private final String key;

    public AttachmentFloatExtractor(String key) {
        this.key = key;
    }

    @Override
    public Optional<Float> tryExtract(PenetrationResolveContext context) {
        return context.getAttachment("damageSource")
                .flatMap(this::extractFromDamageSource);
    }

    private Optional<Float> extractFromDamageSource(Object source) {
        try {
            Class<?> sourceClass = source.getClass();
            try {
                java.lang.reflect.Method getEntityMethod = sourceClass.getMethod("getDirectEntity");
                Object directEntity = getEntityMethod.invoke(source);
                if (directEntity instanceof net.minecraft.world.entity.Entity entity) {
                    CompoundTag persistentData = entity.getPersistentData();
                    if (persistentData.contains(key)) {
                        return Optional.of(getFloatFromTag(persistentData, key));
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            try {
                java.lang.reflect.Method getEntityMethod = sourceClass.getMethod("getEntity");
                Object shooter = getEntityMethod.invoke(source);
                if (shooter instanceof net.minecraft.world.entity.LivingEntity living) {
                    net.minecraft.world.item.ItemStack weapon = living.getMainHandItem();
                    if (!weapon.isEmpty()) {
                        CompoundTag tag = weapon.getTag();
                        if (tag != null && tag.contains(key)) {
                            return Optional.of(getFloatFromTag(tag, key));
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        } catch (Exception e) {
            // ignore
        }
        return Optional.empty();
    }

    private float getFloatFromTag(CompoundTag tag, String key) {
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
}