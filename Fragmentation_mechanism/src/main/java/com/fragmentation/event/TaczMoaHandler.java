package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.ballistics.BallisticsMath;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.config.GunConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public final class TaczMoaHandler {

    private static final String TACZ_BULLET_CLASS = "com.tacz.guns.entity.EntityKineticBullet";
    private static final String TACZ_GUN_OPERATOR_CLASS = "com.tacz.guns.api.entity.IGunOperator";
    private static final String MOA_APPLIED_KEY = "fragmentation_moa_applied";
    private static final String GUN_DURABILITY_KEY = "Durability";
    private static final String GUN_ID_KEY = "GunId";
    private static final String GUN_DURABILITY_CONFIG_CLASS =
        "com.corrinedev.gundurability.config.Config";
    private static final String GUN_INACCURACY_RATE_FIELD = "INACCURACYRATE";
    private static final int GUNDURABILITY_DEFAULT_MAX_DURABILITY = 2000;
    private static final int GUNDURABILITY_DEFAULT_INACCURACY_RATE = 500;
    private static final String WEAPON_CONDITION_KEY = "tacz_tactical_breaching:weapon_condition";
    private static final double WEAPON_CONDITION_FULL = 100.0D;
    private static final int WEAPON_CONDITION_INACCURACY_RATE = 25;
    private static final float DEFAULT_HIP_FIRE_MOA = 200.0F;
    private static final Random RANDOM = new Random();

    private static Class<?> kineticBulletClass;
    private static Class<?> gunOperatorClass;
    private static Method getGunIdMethod;
    private static Method getAmmoIdMethod;
    private static Method getSynIsAimingMethod;
    private static boolean reflectionInitialized;
    private static Method getConfiguredDurabilityMethod;
    private static boolean durabilityReflectionInitialized;
    private static Integer cachedInaccuracyRate;
    private static boolean inaccuracyRateReflectionInitialized;

    private TaczMoaHandler() {
    }

    /**
     * 计算
     * 供 TooltipEventHandler 在枪械 tooltip 中显示，使显示的 MOA 与
     * 实际弹道散布一致。
     *
     * @param stack  枪械物品栈
     * @param gunId TACZ 枪械 ID（如 "tacz:ak47"）
     * @return 调整后的 ADS MOA 值（已乘以耐久度倍率并 clamp）
     */
    public static float getAdjustedAdsMoa(ItemStack stack, String gunId) {
        float baseMoa = GunConfig.getInstance().getMoa(gunId);
        double multiplier = computeDurabilityMoaMultiplier(stack, gunId);
        return Math.max(
            GunConfig.MIN_MOA,
            Math.min(GunConfig.MAX_MOA, (float) (baseMoa * multiplier))
        );
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide || !initializeReflection() || !kineticBulletClass.isInstance(entity)) {
            return;
        }
        if (entity.getPersistentData().getBoolean(MOA_APPLIED_KEY) || !(entity instanceof Projectile projectile)) {
            return;
        }
        if (!(projectile.getOwner() instanceof LivingEntity shooter)) {
            return;
        }

        try {
            Object gunIdValue = getGunIdMethod.invoke(entity);
            if (!(gunIdValue instanceof ResourceLocation gunId)) {
                return;
            }

            ResourceLocation ammoId = null;
            if (getAmmoIdMethod != null) {
                Object ammoIdValue = getAmmoIdMethod.invoke(entity);
                if (ammoIdValue instanceof ResourceLocation resourceLocation) {
                    ammoId = resourceLocation;
                }
            }
            BulletConfig bulletConfig = FragmentationMod.getBulletConfig();
            float bulletMoaModifier = bulletConfig != null && ammoId != null
                ? bulletConfig.getMoaModifier(ammoId.toString())
                : 0.0f;
            ItemStack gunStack = findGunStack(shooter, gunId.toString());
            String gunIdStr = gunId.toString();
            double moaMultiplier = computeDurabilityMoaMultiplier(gunStack, gunIdStr);
            float baseMoa = GunConfig.getInstance().getMoa(gunIdStr);
            float ammoAdjustedMoa = Math.max(
                GunConfig.MIN_MOA,
                Math.min(GunConfig.MAX_MOA, baseMoa + bulletMoaModifier)
            );
            boolean aiming = isAiming(shooter);
            float moa;
            if (aiming) {
                moa = Math.max(
                    GunConfig.MIN_MOA,
                    Math.min(
                        GunConfig.MAX_MOA,
                        (float) (ammoAdjustedMoa * moaMultiplier)
                    )
                );
            } else {
                float hipFireBase = GunConfig.getInstance().getHipFireMoa(gunIdStr);
                float hipFireMoa = Math.max(
                    GunConfig.MIN_MOA,
                    Math.min(
                        GunConfig.MAX_MOA,
                        (float) (hipFireBase * moaMultiplier)
                    )
                );
                moa = Math.max(ammoAdjustedMoa, hipFireMoa);
            }
            Vec3 originalMovement = entity.getDeltaMovement();
            Vec3 shooterMovement = shooter.getDeltaMovement();
            Vec3 inheritedMovement = new Vec3(
                shooterMovement.x,
                shooter.onGround() ? 0.0D : shooterMovement.y,
                shooterMovement.z
            );
            Vec3 ballisticMovement = originalMovement.subtract(inheritedMovement);
            double speed = ballisticMovement.length();
            if (speed <= 1.0E-8D) {
                speed = originalMovement.length();
                inheritedMovement = Vec3.ZERO;
            }
            if (speed <= 1.0E-8D) {
                return;
            }

            Vec3 forward = shooter.getViewVector(1.0F).normalize();
            Vec3 direction = applyMoaSpread(forward, moa);
            Vec3 adjustedMovement = direction.scale(speed).add(inheritedMovement);

            entity.setDeltaMovement(adjustedMovement);
            entity.setYRot((float) Math.toDegrees(Math.atan2(adjustedMovement.x, adjustedMovement.z)));
            entity.setXRot((float) Math.toDegrees(Math.atan2(
                adjustedMovement.y,
                adjustedMovement.horizontalDistance()
            )));
            entity.getPersistentData().putBoolean(MOA_APPLIED_KEY, true);

            FragmentationMod.LOGGER.debug(
                "[TaczMOA] Applied {} MOA to {} in {} mode with ammo={} (base={}, ammoModifier={}, multiplier={}), speed={}, movement={}",
                moa,
                gunId,
                aiming ? "ADS" : "HIP_FIRE",
                ammoId,
                baseMoa,
                bulletMoaModifier,
                moaMultiplier,
                speed,
                adjustedMovement
            );
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn("[TaczMOA] Failed to read TACZ gun ID: {}", e.getMessage());
        }
    }

    private static boolean isAiming(LivingEntity shooter) {
        if (gunOperatorClass == null
            || getSynIsAimingMethod == null
            || !gunOperatorClass.isInstance(shooter)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(getSynIsAimingMethod.invoke(shooter));
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.debug(
                "[TaczMOA] Unable to read TACZ aiming state: {}",
                e.getMessage()
            );
            return true;
        }
    }

    private static Vec3 applyMoaSpread(Vec3 forward, float moa) {
        if (moa <= GunConfig.MIN_MOA) {
            return forward;
        }

        double radiusAt100Meters = GunConfig.calculateSpreadRadiusCm(moa, 100.0F) / 100.0D;
        double maxSlope = radiusAt100Meters / 100.0D;
        double radius = Math.sqrt(RANDOM.nextDouble()) * maxSlope;
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;

        Vec3 referenceUp = Math.abs(forward.y) > 0.999D
            ? new Vec3(1.0D, 0.0D, 0.0D)
            : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(referenceUp).normalize();
        Vec3 up = right.cross(forward).normalize();

        return forward
            .add(right.scale(Math.cos(angle) * radius))
            .add(up.scale(Math.sin(angle) * radius))
            .normalize();
    }

    private static ItemStack findGunStack(LivingEntity shooter, String gunId) {
        ItemStack mainHand = shooter.getMainHandItem();
        if (matchesGunId(mainHand, gunId)) return mainHand;

        ItemStack offHand = shooter.getOffhandItem();
        if (matchesGunId(offHand, gunId)) return offHand;

        return !mainHand.isEmpty() ? mainHand : offHand;
    }

    private static boolean matchesGunId(ItemStack stack, String gunId) {
        if (stack == null || stack.isEmpty() || gunId == null) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && gunId.equals(tag.getString(GUN_ID_KEY));
    }

    private static double getGunDurabilityRatio(ItemStack stack, String gunId) {
        if (stack == null || stack.isEmpty()) return 1.0D;

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(GUN_DURABILITY_KEY, Tag.TAG_ANY_NUMERIC)) {
            int maximum = getMaximumGunDurability(stack, tag, gunId);
            if (maximum > 0) {
                int current = Math.max(0, Math.min(maximum, tag.getInt(GUN_DURABILITY_KEY)));
                return (double) current / maximum;
            }
        }

        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            int maximum = stack.getMaxDamage();
            int current = Math.max(0, maximum - stack.getDamageValue());
            return (double) current / maximum;
        }
        return 1.0D;
    }

    /**
     * Decide which MOA multiplier formula to apply.
     *
     * Priority:
     * 1. tacz_tactical_breaching:weapon_condition (double, 0-100 percentage)
     *    Used by the Tactical Breaching addon's weapon condition system.
     *    Formula: multiplier = (100 - condition) / 25 + 1
     *    Result: 100% -> 1.0x, 75% -> 2.0x, 50% -> 3.0x, 25% -> 4.0x, 0% -> 5.0x
     *
     * 2. Durability (int, 0-maxDurability) from the gundurability addon
     *    Mirrors the addon's inaccuracy formula exactly.
     *
     * 3. Fallback to vanilla Damage tag via {@link BallisticsMath#durabilityMoaMultiplier}
     */
    private static double computeDurabilityMoaMultiplier(ItemStack stack, String gunId) {
        if (stack == null || stack.isEmpty()) {
            return 1.0D;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return 1.0D;
        }

        // Priority 1: tacz_tactical_breaching:weapon_condition (double percentage)
        // The tag may be stored at root level, inside ForgeCaps, or in a nested compound.
        Double weaponCondition = findWeaponCondition(tag);
        if (weaponCondition != null) {
            int current = (int) Math.round(weaponCondition);
            double multiplier = BallisticsMath.gundurabilityMoaMultiplier(
                current,
                (int) WEAPON_CONDITION_FULL,
                WEAPON_CONDITION_INACCURACY_RATE
            );
            FragmentationMod.LOGGER.debug(
                "[TaczMOA] weapon_condition: {}%, multiplier={} for gunId={}",
                weaponCondition, multiplier, gunId
            );
            return multiplier;
        }

        // Debug: dump full NBT when weapon_condition is not found (rate-limited)
        FragmentationMod.LOGGER.warn(
            "[TaczMOA] weapon_condition NOT found in NBT for gunId={}, dumping tag: {}",
            gunId, tag
        );

        // Priority 2: gundurability's Durability (int)
        if (tag.contains(GUN_DURABILITY_KEY, Tag.TAG_ANY_NUMERIC)) {
            int current = Math.max(0, tag.getInt(GUN_DURABILITY_KEY));
            int maximum = getMaximumGunDurability(stack, tag, gunId);
            if (maximum > 0) {
                int inaccuracyRate = getInaccuracyRate();
                return BallisticsMath.gundurabilityMoaMultiplier(
                    current,
                    maximum,
                    inaccuracyRate
                );
            }
        }

        // Priority 3: vanilla Damage tag fallback
        double ratio = getGunDurabilityRatio(stack, gunId);
        return BallisticsMath.durabilityMoaMultiplier(ratio);
    }

    /**
     * Recursively search the NBT tree for the "tacz_tactical_breaching:weapon_condition"
     * tag. TACZ/Tactical Breaching may store the value at the root level, inside
     * ForgeCaps, or in any nested compound. Returns null when not found.
     */
    private static Double findWeaponCondition(CompoundTag tag) {
        if (tag == null) return null;

        // Check root level directly (works for any numeric type)
        if (tag.contains(WEAPON_CONDITION_KEY, Tag.TAG_ANY_NUMERIC)) {
            return tag.getDouble(WEAPON_CONDITION_KEY);
        }

        // Check ForgeCaps (Forge capabilities/attachments are stored here)
        if (tag.contains("ForgeCaps", Tag.TAG_COMPOUND)) {
            CompoundTag caps = tag.getCompound("ForgeCaps");
            if (caps.contains(WEAPON_CONDITION_KEY, Tag.TAG_ANY_NUMERIC)) {
                return caps.getDouble(WEAPON_CONDITION_KEY);
            }
            // Recursively search inside ForgeCaps sub-compounds
            Double nested = searchCompoundChildren(caps);
            if (nested != null) return nested;
        }

        // Check PublicBukkitValues (Bukkit persistent data container)
        if (tag.contains("PublicBukkitValues", Tag.TAG_COMPOUND)) {
            CompoundTag bukkit = tag.getCompound("PublicBukkitValues");
            if (bukkit.contains(WEAPON_CONDITION_KEY, Tag.TAG_ANY_NUMERIC)) {
                return bukkit.getDouble(WEAPON_CONDITION_KEY);
            }
        }

        // Recursively search all sub-compounds at root level
        Double nested = searchCompoundChildren(tag);
        if (nested != null) return nested;

        return null;
    }

    /**
     * Iterate all keys in a compound and recurse into child compounds to find
     * the weapon_condition tag.
     */
    private static Double searchCompoundChildren(CompoundTag tag) {
        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag child = tag.getCompound(key);
                if (child.contains(WEAPON_CONDITION_KEY, Tag.TAG_ANY_NUMERIC)) {
                    return child.getDouble(WEAPON_CONDITION_KEY);
                }
                Double deeper = searchCompoundChildren(child);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    /**
     * Read gundurability's INACCURACYRATE config value via reflection.
     * The field is a static {@code ConfigValue<Integer>} on
     * {@code com.corrinedev.gundurability.config.Config}, and the integer
     * value is exposed by calling {@code get()} on the ConfigValue.
     *
     * @return configured inaccuracy rate, or {@link #GUNDURABILITY_DEFAULT_INACCURACY_RATE}
     *         when the addon is missing or reflection fails.
     */
    private static int getInaccuracyRate() {
        if (inaccuracyRateReflectionInitialized) {
            return cachedInaccuracyRate != null
                ? cachedInaccuracyRate
                : GUNDURABILITY_DEFAULT_INACCURACY_RATE;
        }
        inaccuracyRateReflectionInitialized = true;
        try {
            Class<?> configClass = Class.forName(GUN_DURABILITY_CONFIG_CLASS);
            Field field = configClass.getField(GUN_INACCURACY_RATE_FIELD);
            Object configValue = field.get(null);
            if (configValue != null) {
                Method getMethod = configValue.getClass().getMethod("get");
                Object value = getMethod.invoke(configValue);
                if (value instanceof Number number) {
                    cachedInaccuracyRate = number.intValue();
                    return cachedInaccuracyRate;
                }
            }
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.debug(
                "[TaczMOA] gundurability INACCURACYRATE unavailable; using default: {}",
                e.getMessage()
            );
        }
        cachedInaccuracyRate = GUNDURABILITY_DEFAULT_INACCURACY_RATE;
        return cachedInaccuracyRate;
    }

    private static int getMaximumGunDurability(
        ItemStack stack,
        CompoundTag tag,
        String gunId
    ) {
        for (String key : new String[]{"MaxDurability", "maxDurability", "GunMaxDurability"}) {
            if (tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
                return Math.max(0, tag.getInt(key));
            }
        }

        Method method = getConfiguredDurabilityMethod();
        if (method != null) {
            try {
                Object value = method.invoke(null, gunId);
                if (value instanceof Number number) {
                    return Math.max(0, number.intValue());
                }
            } catch (ReflectiveOperationException e) {
                FragmentationMod.LOGGER.debug(
                    "[TaczMOA] Unable to read configured gun durability: {}",
                    e.getMessage()
                );
            }
        }

        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            return stack.getMaxDamage();
        }
        return GUNDURABILITY_DEFAULT_MAX_DURABILITY;
    }

    private static Method getConfiguredDurabilityMethod() {
        if (durabilityReflectionInitialized) return getConfiguredDurabilityMethod;
        durabilityReflectionInitialized = true;
        try {
            Class<?> configClass = Class.forName(GUN_DURABILITY_CONFIG_CLASS);
            getConfiguredDurabilityMethod = configClass.getMethod("getDurability", String.class);
        } catch (ReflectiveOperationException ignored) {
            getConfiguredDurabilityMethod = null;
        }
        return getConfiguredDurabilityMethod;
    }

    private static boolean initializeReflection() {
        if (reflectionInitialized) {
            return kineticBulletClass != null && getGunIdMethod != null;
        }

        reflectionInitialized = true;
        try {
            kineticBulletClass = Class.forName(TACZ_BULLET_CLASS);
            getGunIdMethod = kineticBulletClass.getMethod("getGunId");
            try {
                getAmmoIdMethod = kineticBulletClass.getMethod("getAmmoId");
            } catch (NoSuchMethodException ignored) {
                getAmmoIdMethod = null;
            }
            try {
                gunOperatorClass = Class.forName(TACZ_GUN_OPERATOR_CLASS);
                getSynIsAimingMethod = gunOperatorClass.getMethod("getSynIsAiming");
            } catch (ReflectiveOperationException e) {
                gunOperatorClass = null;
                getSynIsAimingMethod = null;
                FragmentationMod.LOGGER.warn(
                    "[TaczMOA] TACZ aiming API unavailable; preserving configured MOA: {}",
                    e.getMessage()
                );
            }
            return true;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn("[TaczMOA] TACZ bullet API unavailable: {}", e.getMessage());
            return false;
        }
    }
}
