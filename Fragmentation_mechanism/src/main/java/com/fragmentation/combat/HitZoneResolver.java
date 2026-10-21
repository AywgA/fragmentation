package com.fragmentation.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class HitZoneResolver {
    public static final String IMPACT_PROJECTILE_UUID_KEY = "fragmentation_impact_projectile_uuid";
    public static final String IMPACT_TICK_KEY = "fragmentation_impact_tick";
    public static final String IMPACT_X_KEY = "fragmentation_impact_x";
    public static final String IMPACT_Y_KEY = "fragmentation_impact_y";
    public static final String IMPACT_Z_KEY = "fragmentation_impact_z";
    public static final long IMPACT_CACHE_TICK_WINDOW = 8L;
    private static final double LEGS_MAX_Y = 0.40D;
    private static final double ABDOMEN_MAX_Y = 0.55D;
    private static final double TORSO_MAX_Y = 0.80D;
    private static final double ARM_LATERAL_THRESHOLD = 0.28D;

    private HitZoneResolver() {
    }

    public static HitZone resolve(LivingEntity entity, DamageSource damageSource) {
        Vec3 hitPoint = resolveHitPoint(entity, damageSource);
        if (hitPoint == null) {
            return HitZone.TORSO;
        }
        AABB box = entity.getBoundingBox();
        double normalizedY = resolveHitHeightRatio(entity, hitPoint);

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double relX = hitPoint.x - centerX;
        double relZ = hitPoint.z - centerZ;

        float bodyYaw = entity.yBodyRot;
        double radians = Math.toRadians(-bodyYaw);
        double localX = relX * Math.cos(radians) - relZ * Math.sin(radians);
        double halfWidth = Math.max(entity.getBbWidth() * 0.5D, 0.001D);
        double lateral = localX / halfWidth;

        if (normalizedY >= TORSO_MAX_Y) {
            return HitZone.HEAD;
        }
        if (normalizedY < LEGS_MAX_Y) {
            return lateral < 0.0D ? HitZone.LEFT_LEG : HitZone.RIGHT_LEG;
        }
        if (Math.abs(lateral) >= ARM_LATERAL_THRESHOLD) {
            return lateral < 0.0D ? HitZone.LEFT_ARM : HitZone.RIGHT_ARM;
        }
        if (normalizedY < ABDOMEN_MAX_Y) {
            return HitZone.ABDOMEN;
        }
        return HitZone.TORSO;
    }

    private static Vec3 resolveHitPoint(LivingEntity entity, DamageSource damageSource) {
        Entity directEntity = damageSource != null ? damageSource.getDirectEntity() : null;
        if (directEntity instanceof Projectile projectile) {
            Vec3 cachedImpact = getCachedProjectileImpact(entity, projectile);
            if (cachedImpact != null) {
                return cachedImpact;
            }
            Vec3 end = projectile.position();
            Vec3 start = end.subtract(projectile.getDeltaMovement());
            Vec3 clipped = clipAgainstEntity(entity, start, end);
            if (clipped != null) {
                return clipped;
            }
            return end;
        }

        if (directEntity != null && directEntity != entity) {
            Vec3 start = directEntity.getEyePosition();
            Vec3 end = entity.getBoundingBox().getCenter();
            Vec3 clipped = clipAgainstEntity(entity, start, end);
            if (clipped != null) {
                double hitY = clipped.y;
                double boxMinY = entity.getBoundingBox().minY;
                double boxMaxY = entity.getBoundingBox().maxY;
                double height = boxMaxY - boxMinY;
                double normalizedY = (hitY - boxMinY) / height;
                
                if (normalizedY < 0.3 && height > 1.0) {
                    double adjustedY = boxMinY + height * 0.5;
                    return new Vec3(clipped.x, adjustedY, clipped.z);
                }
                return clipped;
            }
            return directEntity.position();
        }

        Entity attacker = damageSource != null ? damageSource.getEntity() : null;
        if (attacker != null && attacker != entity) {
            Vec3 start = attacker.getEyePosition();
            Vec3 end = entity.getBoundingBox().getCenter();
            Vec3 clipped = clipAgainstEntity(entity, start, end);
            if (clipped != null) {
                double hitY = clipped.y;
                double boxMinY = entity.getBoundingBox().minY;
                double boxMaxY = entity.getBoundingBox().maxY;
                double height = boxMaxY - boxMinY;
                double normalizedY = (hitY - boxMinY) / height;
                
                if (normalizedY < 0.3 && height > 1.0) {
                    double adjustedY = boxMinY + height * 0.5;
                    return new Vec3(clipped.x, adjustedY, clipped.z);
                }
                return clipped;
            }
            return entity.getBoundingBox().getCenter();
        }

        return entity.getBoundingBox().getCenter();
    }

    private static Vec3 getCachedProjectileImpact(LivingEntity entity, Projectile projectile) {
        CompoundTag data = entity.getPersistentData();
        String cachedUuid = data.getString(IMPACT_PROJECTILE_UUID_KEY);
        if (!projectile.getUUID().toString().equals(cachedUuid)) {
            return null;
        }

        long cachedTick = data.getLong(IMPACT_TICK_KEY);
        long currentTick = entity.level().getGameTime();
        if (currentTick - cachedTick < 0L || currentTick - cachedTick > IMPACT_CACHE_TICK_WINDOW) {
            return null;
        }

        return new Vec3(
                data.getDouble(IMPACT_X_KEY),
                data.getDouble(IMPACT_Y_KEY),
                data.getDouble(IMPACT_Z_KEY)
        );
    }

    private static double resolveHitHeightRatio(LivingEntity entity, Vec3 hitPoint) {
        if (hitPoint == null) {
            return 0.5D;
        }

        AABB box = entity.getBoundingBox();
        double height = Math.max(box.maxY - box.minY, 0.001D);
        return clamp01((hitPoint.y - box.minY) / height);
    }

    private static Vec3 clipAgainstEntity(LivingEntity entity, Vec3 start, Vec3 end) {
        AABB box = entity.getBoundingBox();
        Optional<Vec3> clipped = box.clip(start, end);
        return clipped.orElse(null);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
