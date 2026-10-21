package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.ballistics.BallisticsMath;
import com.fragmentation.ballistics.TaczBallisticsHandler;
import com.fragmentation.combat.DetailedHitZone;
import com.fragmentation.combat.HitRecord;
import com.fragmentation.combat.HitRecordCollector;
import com.fragmentation.combat.HitLocator;
import com.fragmentation.combat.HitZoneResolver;
import com.fragmentation.compat.FracturepointHelper;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.ArmorMaterialConfig;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.config.GunConfig;
import com.fragmentation.config.PlateConfig;
import com.fragmentation.config.ProtectionConfig;
import com.fragmentation.config.ShieldConfig;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.shielding.ShieldingFieldManager;
import com.fragmentation.util.ArmorSoundManager;
import com.fragmentation.util.DurabilityUtils;
import com.fragmentation.util.FacewearHelper;
import com.fragmentation.util.PlayerStateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public class TaczGunEventHandler {
    
    private static final Random random = new Random();
    /** Pre 事件已结算标记（tick）：供 ArmorPlateDamageHandler 跳过二次部位解析。 */
    public static final String GUN_SETTLED_TICK_KEY = "fragmentation_gun_settled_tick";
    private static boolean taczAvailable = false;
    private static boolean checkedTacz = false;
    
    private static Class<?> entityHurtByGunPreEventClass;
    private static Method getBulletMethod;
    private static Method getHurtEntityMethod;
    private static Method getAttackerMethod;
    private static Method getGunIdMethod;
    private static Method getBaseAmountMethod;
    private static Method isHeadShotMethod;
    private static Method getHeadshotMultiplierMethod;
    private static Method setBaseAmountMethod;
    /** 事件基类的 isHeadShot 字段（随机部位后需要同步，TACZ 会依据它再次乘爆头倍率并下发客户端标记）。 */
    private static java.lang.reflect.Field isHeadShotField;
    private static Class<?> entityKineticBulletClass;
    private static Method getAmmoIdMethod;
    private static Field ammoIdField;
    private static Method getBulletItemMethod;
    private static Class<?> resourceLocationClass;
    private static Method resourceLocationToStringMethod;
    private static Method getFixedBoundingBoxMethod;
    
    private static boolean hasWarnedAboutAutoConfig = false;
    private static final Map<net.minecraftforge.eventbus.api.Event, Boolean>
        SHIELD_EVENT_DECISIONS = Collections.synchronizedMap(
            new WeakHashMap<>()
        );
    
    public static boolean isTaczAvailable() {
        if (!checkedTacz) {
            checkedTacz = true;
            try {
                entityHurtByGunPreEventClass = Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre");
                entityKineticBulletClass = Class.forName("com.tacz.guns.entity.EntityKineticBullet");
                resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");

                try {
                    Class<?> hitboxHelperClass = Class.forName("com.tacz.guns.util.HitboxHelper");
                    getFixedBoundingBoxMethod = hitboxHelperClass.getMethod(
                        "getFixedBoundingBox",
                        net.minecraft.world.entity.Entity.class,
                        net.minecraft.world.entity.Entity.class
                    );
                } catch (ReflectiveOperationException e) {
                    FragmentationMod.LOGGER.warn("[TaczGun] TACZ HitboxHelper unavailable, using the current entity bounding box");
                }
                
                Class<?> baseEventClass = Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent");
                getBulletMethod = baseEventClass.getMethod("getBullet");
                getHurtEntityMethod = baseEventClass.getMethod("getHurtEntity");
                getAttackerMethod = baseEventClass.getMethod("getAttacker");
                getGunIdMethod = baseEventClass.getMethod("getGunId");
                getBaseAmountMethod = baseEventClass.getMethod("getBaseAmount");
                isHeadShotMethod = baseEventClass.getMethod("isHeadShot");
                getHeadshotMultiplierMethod = baseEventClass.getMethod("getHeadshotMultiplier");

                try {
                    isHeadShotField = baseEventClass.getDeclaredField("isHeadShot");
                    isHeadShotField.setAccessible(true);
                } catch (NoSuchFieldException e) {
                    FragmentationMod.LOGGER.warn("[TaczGun] Could not access isHeadShot field, headshot marker sync disabled");
                }

                try {
                    setBaseAmountMethod = baseEventClass.getMethod("setBaseAmount", float.class);
                    FragmentationMod.LOGGER.info("[TaczGun] Found setBaseAmount method");
                } catch (NoSuchMethodException e) {
                    try {
                        setBaseAmountMethod = baseEventClass.getMethod("setAmount", float.class);
                        FragmentationMod.LOGGER.info("[TaczGun] Found setAmount method");
                    } catch (NoSuchMethodException e2) {
                        FragmentationMod.LOGGER.warn("[TaczGun] Could not find setBaseAmount or setAmount method, bullet damage override will NOT work!");
                    }
                }
                
                try {
                    getAmmoIdMethod = entityKineticBulletClass.getMethod("getAmmoId");
                } catch (NoSuchMethodException ignored) {
                    getAmmoIdMethod = null;
                }
                ammoIdField = accessibleField(entityKineticBulletClass, "ammoId");
                try {
                    getBulletItemMethod = entityKineticBulletClass.getMethod("getBulletItem");
                    FragmentationMod.LOGGER.info("[TaczGun] Found getBulletItem method");
                } catch (NoSuchMethodException e) {
                    try {
                        getBulletItemMethod = entityKineticBulletClass.getMethod("getItem");
                        FragmentationMod.LOGGER.info("[TaczGun] Found getItem method");
                    } catch (NoSuchMethodException e2) {
                        FragmentationMod.LOGGER.warn("[TaczGun] Could not find getBulletItem or getItem method, using ammoId only");
                    }
                }
                resourceLocationToStringMethod = resourceLocationClass.getMethod("toString");
                
                taczAvailable = true;
            } catch (Exception e) {
                taczAvailable = false;
            }
        }
        return taczAvailable;
    }

    private static Field accessibleField(Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn(
                "[TaczGun] TACZ field {} unavailable",
                fieldName
            );
            return null;
        }
    }
    
    @SubscribeEvent(
        priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST,
        receiveCanceled = true
    )
    public static void onEntityHurtByGunPre(net.minecraftforge.eventbus.api.Event event) {
        boolean canceledAtEntry = event.isCanceled();
        try {
            if (!isTaczAvailable()) return;
            if (!entityHurtByGunPreEventClass.isInstance(event)) return;
            
            Object hurtEntityObj = getHurtEntityMethod.invoke(event);
            if (!(hurtEntityObj instanceof LivingEntity hurtEntity)) return;
            if (hurtEntity.level().isClientSide) return;
            
            Object attackerObj = getAttackerMethod.invoke(event);
            LivingEntity attacker = attackerObj instanceof LivingEntity ? (LivingEntity) attackerObj : null;
            
            Object bulletEntity = getBulletMethod.invoke(event);
            if (bulletEntity == null) return;

            // 某些模组的 AI 枪手事件中 attacker getter 可能返回 null，
            // 此时从子弹实体补充判定。TACZ 的 EntityKineticBullet 可能不继承
            // 标准 Projectile，因此除了 instanceof 检查外还通过反射尝试
            // 调用 getOwner() 方法（否则非玩家攻击会被漏判、不触发随机）。
            if (attacker == null && bulletEntity instanceof Entity ownerBullet) {
                Entity bulletOwner = null;
                if (ownerBullet instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                    bulletOwner = projectile.getOwner();
                }
                if (bulletOwner == null) {
                    try {
                        java.lang.reflect.Method getOwnerMethod = ownerBullet.getClass().getMethod("getOwner");
                        Object owner = getOwnerMethod.invoke(ownerBullet);
                        if (owner instanceof Entity entity) bulletOwner = entity;
                    } catch (Exception ignored) {}
                }
                if (bulletOwner instanceof LivingEntity ownerLiving) {
                    attacker = ownerLiving;
                    FragmentationMod.LOGGER.info(
                        "[TaczGun] Event attacker was null, resolved from bullet owner: "
                            + attacker.getName().getString());
                }
            }

            // ===== 三层屏蔽场拦截（沙丘式）=====
            if (ShieldingFieldManager.isFieldActive(hurtEntity)) {
                try {
                    double impactSpeed = 0.0D;
                    if (bulletEntity instanceof Entity fieldBullet) {
                        TaczBallisticsHandler.SpeedSnapshot fieldSnapshot =
                            TaczBallisticsHandler.getSpeedSnapshot(fieldBullet, hurtEntity);
                        impactSpeed = fieldSnapshot.currentSpeed();
                        if (impactSpeed <= 0.0D) {
                            Vec3 fieldDm = fieldBullet.getDeltaMovement();
                            impactSpeed = fieldDm != null ? fieldDm.length() : 0.0D;
                        }
                    }
                    double fieldMass = resolveFieldBulletMass(bulletEntity);
                    double fieldEnergy = BallisticsMath.kineticEnergy(fieldMass, impactSpeed);
                    long fieldTick = hurtEntity.level().getGameTime();
                    // 命中点 = 子弹本 tick 射线段与目标碰撞箱的进入点（高速步进子弹的
                    // position() 可能还在步进起点，直接使用会导致特效落到脚下）
                    Vec3 fieldFxPos = bulletEntity instanceof Entity fieldBe
                        ? resolveBulletContact(fieldBe, hurtEntity)
                        : hurtEntity.position();
                    // onHit 内部广播命中脉冲特效
                    ShieldingFieldManager.HitResult fieldResult =
                        ShieldingFieldManager.onHit(hurtEntity, fieldEnergy, impactSpeed, fieldTick, fieldFxPos);

                    if (fieldResult.absorbed()) {
                        SHIELD_EVENT_DECISIONS.put(event, true);
                        event.setCanceled(true);
                        FragmentationMod.LOGGER.info(
                            "[TaczGun] 屏蔽场吸收动能 {}J（speed={}m/s, mass={}kg），目标 {} 无伤",
                            String.format("%.1f", fieldEnergy),
                            String.format("%.1f", impactSpeed),
                            String.format("%.4f", fieldMass),
                            hurtEntity.getName().getString());
                        return;
                    } else {
                        // 穿透：确保伤害正常进入后续流程
                        SHIELD_EVENT_DECISIONS.put(event, false);
                        event.setCanceled(false);
                    }
                } catch (Throwable fieldError) {
                    FragmentationMod.LOGGER.error("[TaczGun] 屏蔽场拦截异常，放行伤害", fieldError);
                }
            }

            if (getActiveShieldSlot(hurtEntity) != null) {
                SHIELD_EVENT_DECISIONS.put(event, false);
                event.setCanceled(false);
            }
            
            Object gunIdRl = getGunIdMethod.invoke(event);
            String gunId = gunIdRl != null ? (String) resourceLocationToStringMethod.invoke(gunIdRl) : null;
            
            FragmentationMod.LOGGER.info("[TaczGun] gunIdRl=" + gunIdRl + ", gunId=" + gunId);
            
            float baseDamage = (float) getBaseAmountMethod.invoke(event);
            float eventBaseDamage = baseDamage;
            boolean isHeadShot = (boolean) isHeadShotMethod.invoke(event);
            float headshotMultiplier = (float) getHeadshotMultiplierMethod.invoke(event);

            Float damageOverride = GunConfig.getInstanceFresh().getBaseDamageOverride(gunId);
            if (damageOverride != null && damageOverride >= 0) {
                FragmentationMod.LOGGER.info("[TaczGun] Applying custom base damage for " + gunId + ": " + baseDamage + " -> " + damageOverride);
                baseDamage = damageOverride;
            }
            
            String ammoId = null;
            String bulletItemId = null;
            String bulletNbtAmmoId = null;
            
            if (attacker != null) {
                ItemStack heldItem = attacker.getMainHandItem();
                CompoundTag tag = heldItem.getTag();
                if (tag != null) {
                    FragmentationMod.LOGGER.info("[TaczGun] Weapon NBT keys: " + tag.getAllKeys());
                    FragmentationMod.LOGGER.info("[TaczGun] Weapon NBT full: " + tag);
                    
                    if (tag.contains("AmmoId")) {
                        bulletNbtAmmoId = tag.getString("AmmoId");
                        FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in weapon NBT: " + bulletNbtAmmoId);
                    } else if (tag.contains("ammoId")) {
                        bulletNbtAmmoId = tag.getString("ammoId");
                        FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in weapon NBT: " + bulletNbtAmmoId);
                    } else if (tag.contains("Extras")) {
                        CompoundTag extrasTag = tag.getCompound("Extras");
                        FragmentationMod.LOGGER.info("[TaczGun] Extras NBT keys: " + extrasTag.getAllKeys());
                        FragmentationMod.LOGGER.info("[TaczGun] Extras NBT full: " + extrasTag);
                        if (extrasTag.contains("ExtraAmmo")) {
                            bulletNbtAmmoId = extrasTag.getString("ExtraAmmo");
                            FragmentationMod.LOGGER.info("[TaczGun] Found ExtraAmmo in Extras: " + bulletNbtAmmoId);
                        } else if (extrasTag.contains("AmmoId")) {
                            bulletNbtAmmoId = extrasTag.getString("AmmoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in Extras: " + bulletNbtAmmoId);
                        } else if (extrasTag.contains("ammoId")) {
                            bulletNbtAmmoId = extrasTag.getString("ammoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in Extras: " + bulletNbtAmmoId);
                        } else if (extrasTag.contains("Magazine")) {
                            CompoundTag magTag = extrasTag.getCompound("Magazine");
                            FragmentationMod.LOGGER.info("[TaczGun] Extras/Magazine NBT keys: " + magTag.getAllKeys());
                            if (magTag.contains("AmmoId")) {
                                bulletNbtAmmoId = magTag.getString("AmmoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in Extras/Magazine: " + bulletNbtAmmoId);
                            } else if (magTag.contains("ammoId")) {
                                bulletNbtAmmoId = magTag.getString("ammoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in Extras/Magazine: " + bulletNbtAmmoId);
                            } else if (magTag.contains("tag")) {
                                CompoundTag magInnerTag = magTag.getCompound("tag");
                                if (magInnerTag.contains("AmmoId")) {
                                    bulletNbtAmmoId = magInnerTag.getString("AmmoId");
                                    FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in Extras/Magazine/tag: " + bulletNbtAmmoId);
                                } else if (magInnerTag.contains("ammoId")) {
                                    bulletNbtAmmoId = magInnerTag.getString("ammoId");
                                    FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in Extras/Magazine/tag: " + bulletNbtAmmoId);
                                }
                            }
                        }
                    }
                    
                    if (bulletNbtAmmoId == null && tag.contains("AttachmentEXTENDED_MAG")) {
                        CompoundTag magTag = tag.getCompound("AttachmentEXTENDED_MAG");
                        FragmentationMod.LOGGER.info("[TaczGun] AttachmentEXTENDED_MAG NBT keys: " + magTag.getAllKeys());
                        if (magTag.contains("tag")) {
                            CompoundTag tagTag = magTag.getCompound("tag");
                            FragmentationMod.LOGGER.info("[TaczGun] AttachmentEXTENDED_MAG/tag NBT keys: " + tagTag.getAllKeys());
                            if (tagTag.contains("AmmoId")) {
                                bulletNbtAmmoId = tagTag.getString("AmmoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in AttachmentEXTENDED_MAG/tag: " + bulletNbtAmmoId);
                            } else if (tagTag.contains("ammoId")) {
                                bulletNbtAmmoId = tagTag.getString("ammoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in AttachmentEXTENDED_MAG/tag: " + bulletNbtAmmoId);
                            } else if (tagTag.contains("AmmoEntries")) {
                                net.minecraft.nbt.ListTag entries = tagTag.getList("AmmoEntries", 10);
                                if (!entries.isEmpty()) {
                                    CompoundTag firstEntry = entries.getCompound(0);
                                    if (firstEntry.contains("AmmoId")) {
                                        bulletNbtAmmoId = firstEntry.getString("AmmoId");
                                        FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in AttachmentEXTENDED_MAG/tag/AmmoEntries[0]: " + bulletNbtAmmoId);
                                    }
                                }
                            }
                        }
                    } else if (tag.contains("CurrentAmmo")) {
                        CompoundTag ammoTag = tag.getCompound("CurrentAmmo");
                        FragmentationMod.LOGGER.info("[TaczGun] CurrentAmmo NBT keys: " + ammoTag.getAllKeys());
                        if (ammoTag.contains("AmmoId")) {
                            bulletNbtAmmoId = ammoTag.getString("AmmoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in CurrentAmmo: " + bulletNbtAmmoId);
                        } else if (ammoTag.contains("ammoId")) {
                            bulletNbtAmmoId = ammoTag.getString("ammoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in CurrentAmmo: " + bulletNbtAmmoId);
                        } else if (ammoTag.contains("tag")) {
                            // CurrentAmmo 可能是完整物品序列化 {Count,id:"tacz:ammo",tag:{AmmoId:...}}
                            // 必须优先读内部 tag 的 AmmoId；直接取 id 会得到注册ID "tacz:ammo"
                            // （所有 TACZ 弹药共用），导致跨弹药误判
                            CompoundTag ammoInnerTag = ammoTag.getCompound("tag");
                            if (ammoInnerTag.contains("AmmoId")) {
                                bulletNbtAmmoId = ammoInnerTag.getString("AmmoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in CurrentAmmo/tag: " + bulletNbtAmmoId);
                            } else if (ammoInnerTag.contains("ammoId")) {
                                bulletNbtAmmoId = ammoInnerTag.getString("ammoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in CurrentAmmo/tag: " + bulletNbtAmmoId);
                            }
                        } else if (ammoTag.contains("id")) {
                            bulletNbtAmmoId = ammoTag.getString("id");
                            FragmentationMod.LOGGER.info("[TaczGun] Found id in CurrentAmmo: " + bulletNbtAmmoId);
                        }
                    } else if (tag.contains("Magazine")) {
                        CompoundTag magTag = tag.getCompound("Magazine");
                        FragmentationMod.LOGGER.info("[TaczGun] Magazine NBT keys: " + magTag.getAllKeys());
                        if (magTag.contains("AmmoId")) {
                            bulletNbtAmmoId = magTag.getString("AmmoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in Magazine: " + bulletNbtAmmoId);
                        } else if (magTag.contains("ammoId")) {
                            bulletNbtAmmoId = magTag.getString("ammoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in Magazine: " + bulletNbtAmmoId);
                        } else if (magTag.contains("tag")) {
                            CompoundTag magInnerTag = magTag.getCompound("tag");
                            if (magInnerTag.contains("AmmoId")) {
                                bulletNbtAmmoId = magInnerTag.getString("AmmoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in Magazine/tag: " + bulletNbtAmmoId);
                            } else if (magInnerTag.contains("ammoId")) {
                                bulletNbtAmmoId = magInnerTag.getString("ammoId");
                                FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in Magazine/tag: " + bulletNbtAmmoId);
                            }
                        }
                    } else if (tag.contains("tag")) {
                        CompoundTag innerTag = tag.getCompound("tag");
                        FragmentationMod.LOGGER.info("[TaczGun] weapon tag NBT keys: " + innerTag.getAllKeys());
                        if (innerTag.contains("AmmoId")) {
                            bulletNbtAmmoId = innerTag.getString("AmmoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found AmmoId in weapon tag: " + bulletNbtAmmoId);
                        } else if (innerTag.contains("ammoId")) {
                            bulletNbtAmmoId = innerTag.getString("ammoId");
                            FragmentationMod.LOGGER.info("[TaczGun] Found ammoId in weapon tag: " + bulletNbtAmmoId);
                        }
                    }
                }
            }
            
            if (entityKineticBulletClass.isInstance(bulletEntity)) {
                Object ammoIdRl = getAmmoIdMethod != null
                    ? getAmmoIdMethod.invoke(bulletEntity)
                    : ammoIdField != null ? ammoIdField.get(bulletEntity) : null;
                if (ammoIdRl != null) {
                    ammoId = (String) resourceLocationToStringMethod.invoke(ammoIdRl);
                }
            }
            
            FragmentationMod.LOGGER.info("[TaczGun] ammoId=" + ammoId + ", bulletItemId=" + bulletItemId + ", bulletNbtAmmoId=" + bulletNbtAmmoId);
            
            DetailedHitZone detailedZone = determineHitSlot(hurtEntity, bulletEntity, isHeadShot, attacker);
            EquipmentSlot hitSlot = detailedZone.getEquipmentSlot();
            
            BulletConfig.BulletEntry bulletEntry = null;
            
            if (bulletNbtAmmoId != null) {
                bulletEntry = FragmentationMod.getBulletConfig().getBulletByTaczAmmoId(bulletNbtAmmoId);
            }
            
            if (bulletEntry == null && bulletItemId != null) {
                bulletEntry = FragmentationMod.getBulletConfig().getBulletByTaczAmmoId(bulletItemId);
            }
            
            if (bulletEntry == null && ammoId != null) {
                bulletEntry = FragmentationMod.getBulletConfig().getBulletByTaczAmmoId(ammoId);
            }
            
            if (bulletEntry != null && bulletEntry.baseDamage > 0) {
                FragmentationMod.LOGGER.info(
                    "[TaczGun] Applying bullet base damage override for " + ammoId
                        + ": " + baseDamage + " -> " + bulletEntry.baseDamage
                );
                baseDamage = bulletEntry.baseDamage;
                
                if (setBaseAmountMethod != null) {
                    try {
                        setBaseAmountMethod.invoke(event, baseDamage);
                        FragmentationMod.LOGGER.info("[TaczGun] Updated event base amount via setBaseAmountMethod: " + baseDamage);
                    } catch (Exception e) {
                        FragmentationMod.LOGGER.warn("[TaczGun] setBaseAmountMethod failed: " + e.getMessage());
                    }
                } else {
                    try {
                        java.lang.reflect.Field amountField = entityHurtByGunPreEventClass.getSuperclass().getDeclaredField("baseAmount");
                        amountField.setAccessible(true);
                        amountField.setFloat(event, baseDamage);
                        FragmentationMod.LOGGER.info("[TaczGun] Updated event base amount via reflection field: " + baseDamage);
                    } catch (Exception e) {
                        try {
                            java.lang.reflect.Field amountField = entityHurtByGunPreEventClass.getDeclaredField("baseAmount");
                            amountField.setAccessible(true);
                            amountField.setFloat(event, baseDamage);
                            FragmentationMod.LOGGER.info("[TaczGun] Updated event base amount via direct field: " + baseDamage);
                        } catch (Exception e2) {
                            FragmentationMod.LOGGER.warn("[TaczGun] Could not update event base amount: " + e2.getMessage());
                        }
                    }
                }
            }
            
            if (bulletEntry == null) {
                String configId = bulletNbtAmmoId != null ? bulletNbtAmmoId : (bulletItemId != null ? bulletItemId : ammoId);
                if (configId != null) {
                    FragmentationMod.LOGGER.info("[TaczGun] Auto-creating bullet config for: " + configId + " (ammoId=" + ammoId + ", bulletItemId=" + bulletItemId + ", bulletNbtAmmoId=" + bulletNbtAmmoId + ")");
                    FragmentationMod.getBulletConfig().addBulletByTaczAmmoId(configId, 1, 5.0);
                    FragmentationMod.getBulletConfig().saveConfig();
                    bulletEntry = FragmentationMod.getBulletConfig().getBulletByTaczAmmoId(configId);
                    
                    if (!hasWarnedAboutAutoConfig) {
                        hasWarnedAboutAutoConfig = true;
                        FragmentationMod.LOGGER.warn("[TaczGun] 子弹 " + configId + " 未配置，已自动创建默认配置。请在 bullets.json 中修改属性。");
                    }
                }
            }

            TaczBallisticsHandler.SpeedSnapshot speedSnapshot =
                bulletEntity instanceof Entity bullet
                    ? TaczBallisticsHandler.getSpeedSnapshot(bullet, hurtEntity)
                    : new TaczBallisticsHandler.SpeedSnapshot(0.0D, 0.0D);
            double speedRatio = speedSnapshot.speedRatio();
            double energyRetention = speedSnapshot.energyRetention();
            float baseDamageBeforeEnergy = baseDamage;
            double projectileDamageMultiplier =
                bulletEntity instanceof Entity bullet
                    ? TaczBallisticsHandler.getDamageMultiplier(bullet)
                    : 1.0D;
            double projectilePenetrationMultiplier =
                bulletEntity instanceof Entity bullet
                    ? TaczBallisticsHandler.getPenetrationMultiplier(bullet)
                    : 1.0D;
            baseDamage = (float) (
                BallisticsMath.retainedValue(baseDamage, energyRetention)
                    * projectileDamageMultiplier
            );

            FragmentationMod.LOGGER.info(
                "[TaczGun] Speed energy: currentSpeed=" + speedSnapshot.currentSpeed()
                    + ", maximumSpeed=" + speedSnapshot.maximumSpeed()
                    + ", speedRatio=" + speedRatio
                    + ", retention=" + energyRetention
                    + ", damageMultiplier=" + projectileDamageMultiplier
                    + ", penetrationMultiplier=" + projectilePenetrationMultiplier
                    + ", baseDamage=" + baseDamageBeforeEnergy
                    + ", retainedDamage=" + baseDamage
            );
            
            DebugInfo debugInfo = new DebugInfo();
            debugInfo.gunId = gunId;
            debugInfo.ammoId = ammoId;
            debugInfo.hurtEntityName = hurtEntity.getDisplayName().getString();
            debugInfo.bulletEntry = bulletEntry;
            debugInfo.isHeadShot = isHeadShot;
            debugInfo.headshotMultiplier = headshotMultiplier;
            debugInfo.originalDamage = baseDamageBeforeEnergy;
            debugInfo.retainedBaseDamage = baseDamage;
            debugInfo.currentBulletSpeed = speedSnapshot.currentSpeed();
            debugInfo.maximumBulletSpeed = speedSnapshot.maximumSpeed();
            debugInfo.speedRatio = speedRatio;
            debugInfo.energyRetention = energyRetention;
            debugInfo.projectileDamageMultiplier = projectileDamageMultiplier;
            debugInfo.projectilePenetrationMultiplier =
                projectilePenetrationMultiplier;
            debugInfo.hitSlot = hitSlot;
            debugInfo.detailedZone = detailedZone;
            debugInfo.attacker = attacker;

            // 玩家被枪械击中时：在任何护甲计算之前随机重掷伤害部位，
            // 后续的护甲防护、耐久消耗、减伤/穿透全部按重掷后的部位完整结算。
            // 条件：受击者是玩家，且子弹不是该玩家自己发射（非自伤）。
            // 这样即使 TACZ 事件的 attacker getter 返回 null（部分 AI 模组的
            // EntityKineticBullet 不暴露 owner），也能确保随机判定一定执行。
            if (hurtEntity instanceof Player hurtPlayer) {
                boolean isSelfAttack = attacker == hurtPlayer;
                if (!isSelfAttack) {
                    String originalZoneName = detailedZone.getDisplayName();
                    DetailedHitZone rolledZone = rollRandomHitZone();
                    detailedZone = rolledZone;
                    hitSlot = rolledZone.getEquipmentSlot();
                    debugInfo.detailedZone = rolledZone;
                    debugInfo.hitSlot = hitSlot;
                    FragmentationMod.LOGGER.info(
                        "[TaczGun] Damage zone randomized: " + originalZoneName
                            + " -> " + rolledZone.getDisplayName());
                }
            }
        debugInfo.projectile =
            bulletEntity instanceof Entity bullet ? bullet : null;

            float effectiveBulletLevel = (float) BallisticsMath.retainedValue(
                1.0D,
                energyRetention
            );
            if (bulletEntry != null) {
                effectiveBulletLevel = (float) BallisticsMath.retainedValue(
                    bulletEntry.penetrationLevel,
                    energyRetention
                );
            }
            effectiveBulletLevel *= (float) projectilePenetrationMultiplier;
            debugInfo.effectivePenetration = effectiveBulletLevel;
            debugInfo.effectiveArmorDamage = getEffectiveArmorDamage(bulletEntry, debugInfo);

            if (bulletEntity instanceof Entity bullet && bulletEntry != null) {
                DefensiveSurface surface = findDefensiveSurface(
                    bullet,
                    hurtEntity,
                    hitSlot,
                    detailedZone,
                    attacker
                );
                if (surface != null && TaczBallisticsHandler.tryRicochetEntity(
                    bullet,
                    hurtEntity,
                    bulletEntry,
                    surface.ricochetCoefficient(),
                    surface.normal()
                )) {
                    event.setCanceled(true);
                    updateShieldEventDecision(event, true);
                    FragmentationMod.LOGGER.info(
                        "[TaczGun] Ricocheted from " + surface.name()
                            + ", coefficient=" + surface.ricochetCoefficient()
                    );
                    return;
                }
                int fragmentCount = TaczBallisticsHandler.tryFragment(
                    bullet,
                    hurtEntity,
                    bulletEntry,
                    effectiveBulletLevel
                );
                if (fragmentCount > 0) {
                    event.setCanceled(true);
                    updateShieldEventDecision(event, true);
                    return;
                }
            }
            
            GunDamageResult result = calculateGunDamage(hurtEntity, baseDamage, hitSlot, detailedZone, bulletEntry, effectiveBulletLevel, debugInfo, attacker);

            if (result.blockedByShield) {
                debugInfo.finalDamage = 0.0F;
                event.setCanceled(true);
                updateShieldEventDecision(event, true);
                FragmentationMod.LOGGER.info(
                    "[TaczGun] Shield fully blocked the bullet; canceled entity "
                        + "damage and skipped armor durability processing"
                );
                if (attacker instanceof Player player
                    && PlayerStateManager.isDebugMode(player)) {
                    sendDebugMessage(player, debugInfo);
                }
                // 死亡战报埋点：护盾完全挡下 = 未穿透。
                recordForDeathReport(hurtEntity, attacker, result, debugInfo, 0.0F,
                    bulletEntity instanceof Entity be ? be : null);
                return;
            }
            
            float finalDamage = result.finalDamage;

            // 统一的爆头判定：只有最终结算部位确实是头部时才保留爆头。
            // 面部（FACE）以及被随机重掷到非头部的情况均不算爆头。
            boolean effectiveHeadShot = isHeadShot && debugInfo.detailedZone == DetailedHitZone.HEAD;

            // TACZ 在 Pre 事件返回后会依据 isHeadShot 字段再次乘爆头倍率。
            // 不依赖反射回写 isHeadShot（字段名可能不匹配导致失败），
            // 改用预补偿方案：当 isHeadShot=true 时，把 baseAmount 设为
            // finalDamage / headshotMultiplier，TACZ 乘回倍率后刚好是 finalDamage。
            // 这样无论随机到哪个部位，TACZ 最终用于 hurt 的金额都是 finalDamage。
            if (effectiveHeadShot) {
                finalDamage = finalDamage * headshotMultiplier;
            }

            finalDamage = Math.max(finalDamage, 0.5f);

            // 预补偿：TACZ 事件返回后会按 isHeadShot 再次乘 headshotMultiplier，
            // 提前除掉它，使 TACZ 乘回后等于实际应扣的 finalDamage。
            float amountToSet = finalDamage;
            if (isHeadShot && headshotMultiplier > 0.0F) {
                amountToSet = finalDamage / headshotMultiplier;
                FragmentationMod.LOGGER.info("[TaczGun] Pre-compensation: finalDamage=" + finalDamage
                    + ", headshotMultiplier=" + headshotMultiplier
                    + ", amountToSet=" + amountToSet
                    + " (TACZ will multiply back to " + finalDamage + ")");
            }

            FragmentationMod.LOGGER.info("[TaczGun] Final damage calculation: baseDamage=" + baseDamage + 
                ", result.finalDamage=" + result.finalDamage + 
                ", finalDamage=" + finalDamage + 
                ", wasModified=" + result.wasModified + 
                ", condition=" + (result.wasModified || finalDamage != baseDamage));

            if (result.shouldBypassReduction && finalDamage > 0) {
                hurtEntity.getPersistentData().putBoolean("fragmentation_ignore_armor_reduction", true);
                hurtEntity.getPersistentData().putFloat("fragmentation_intended_damage", finalDamage);
                hurtEntity.getPersistentData().putString("fragmentation_hit_zone", debugInfo.detailedZone.name());
                FragmentationMod.LOGGER.info("[TaczGun] Set bypass reduction flag: intendedDamage=" + finalDamage);
            }
            
            FragmentationMod.LOGGER.info("[TaczGun] Using normal damage handling: finalDamage=" + finalDamage + ", isHeadShot=" + isHeadShot);

            boolean shouldUpdateDamage = result.wasModified
                || Float.compare(finalDamage, eventBaseDamage) != 0
                || isHeadShot;

            if (shouldUpdateDamage) {
                boolean reflectionSuccess = false;
                if (setBaseAmountMethod != null) {
                    try {
                        setBaseAmountMethod.invoke(event, amountToSet);
                        debugInfo.finalDamage = finalDamage;
                        reflectionSuccess = true;
                    } catch (ReflectiveOperationException e) {
                        FragmentationMod.LOGGER.warn(
                            "[TaczGun] Failed to update final damage through "
                                + "the TACZ event setter",
                            e
                        );
                    }
                }
                try {
                    if (!reflectionSuccess) {
                        java.lang.reflect.Field amountField = entityHurtByGunPreEventClass
                            .getSuperclass()
                            .getDeclaredField("baseAmount");
                        amountField.setAccessible(true);
                        amountField.setFloat(event, amountToSet);
                        debugInfo.finalDamage = finalDamage;
                        reflectionSuccess = true;
                    }
                } catch (ReflectiveOperationException e) {
                    try {
                        java.lang.reflect.Field amountField =
                            entityHurtByGunPreEventClass.getDeclaredField(
                                "baseAmount"
                            );
                        amountField.setAccessible(true);
                        amountField.setFloat(event, amountToSet);
                        debugInfo.finalDamage = finalDamage;
                        reflectionSuccess = true;
                    } catch (ReflectiveOperationException directError) {
                        FragmentationMod.LOGGER.error(
                            "[TaczGun] Failed to set damage amount via reflection",
                            directError
                        );
                    }
                }
                
                if (!reflectionSuccess) {
                    FragmentationMod.LOGGER.error("[TaczGun] Reflection failed to set damage! This is a critical issue.");
                }
                
                if (result.hasArmorDamage && result.armorHitSlot != null) {
                    FragmentationMod.LOGGER.info("[TaczGun] Armor damage queued: slot=" + result.armorHitSlot +
                        ", amount=" + result.armorDamageAmount + ", finalDamage=" + finalDamage);
                }
            } else {
                debugInfo.finalDamage = baseDamage * (isHeadShot ? headshotMultiplier : 1.0f);
            }
            
            if (isHeadShot && !shouldUpdateDamage) {
                FragmentationMod.LOGGER.warn("[TaczGun] WARNING: isHeadShot=true but shouldUpdateDamage=false! This should not happen.");
                FragmentationMod.LOGGER.warn("[TaczGun]  baseDamage=" + baseDamage + ", finalDamage=" + finalDamage + ", wasModified=" + result.wasModified);
            }
            
            if (attacker instanceof Player player && PlayerStateManager.isDebugMode(player)) {
                sendDebugMessage(player, debugInfo);
            }

            // 死亡战报埋点：记录本次命中（受击者视角 + 攻击者视角）。
            recordForDeathReport(hurtEntity, attacker, result, debugInfo, finalDamage,
                bulletEntity instanceof Entity be ? be : null);

            // 标记本次枪械伤害已在 Pre 事件中完整结算（伤害金额 + 护甲耐久）。
            // 随后 TACZ 的 tacAttackEntity 会真正调用 hurt（可能按 armorIgnore 拆成
            // AP / non-AP 两次），ArmorPlateDamageHandler 检测到本 tick 的标记后会跳过
            // 自己的二次部位解析——否则它会按子弹真实命中点重新判定部位，
            // 导致 NPC 锁头时上面的随机部位结果被覆盖。
            hurtEntity.getPersistentData().putLong(
                GUN_SETTLED_TICK_KEY, hurtEntity.level().getGameTime());

            updateShieldEventDecision(event, false);
        } catch (Throwable e) {
            SHIELD_EVENT_DECISIONS.remove(event);
            if (canceledAtEntry) {
                event.setCanceled(true);
            }
            FragmentationMod.LOGGER.error("[TaczGunEventHandler] Error processing gun damage event", e);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityHurtByGunPreDebugTail(net.minecraftforge.eventbus.api.Event event) {
        try {
            if (!isTaczAvailable() || !entityHurtByGunPreEventClass.isInstance(event)) return;
            Object hurtEntityObj = getHurtEntityMethod.invoke(event);
            Object bulletEntity = getBulletMethod.invoke(event);
            if (!(hurtEntityObj instanceof LivingEntity hurtEntity) || hurtEntity.level().isClientSide) return;
            Boolean configuredDecision = SHIELD_EVENT_DECISIONS.remove(event);
            if (configuredDecision != null) {
                event.setCanceled(configuredDecision);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateShieldEventDecision(
        net.minecraftforge.eventbus.api.Event event,
        boolean canceled
    ) {
        if (SHIELD_EVENT_DECISIONS.containsKey(event)) {
            SHIELD_EVENT_DECISIONS.put(event, canceled);
        }
    }
    
    // ================= 死亡战报埋点 =================

    private static void recordForDeathReport(LivingEntity victim, LivingEntity attacker,
                                             GunDamageResult result, DebugInfo debug,
                                             float finalDamage, Entity bulletEntity) {
        try {
            String attackerName = attacker != null ? attacker.getName().getString() : "未知";
            String victimName = victim.getName().getString();

            if (victim instanceof Player hurtPlayer) {
                HitRecordCollector.addIncoming(hurtPlayer.getUUID(),
                    buildHitRecord(victim, attackerName, result, debug, finalDamage, bulletEntity));
            }
            if (attacker instanceof Player attackPlayer && attackPlayer != victim) {
                HitRecordCollector.addOutgoing(attackPlayer.getUUID(),
                    buildHitRecord(victim, victimName, result, debug, finalDamage, bulletEntity));
            }
        } catch (Throwable t) {
            FragmentationMod.LOGGER.error("[TaczGun] Failed to record death-report hit", t);
        }
    }

    private static HitRecord buildHitRecord(LivingEntity victim, String opponentName,
                                            GunDamageResult result, DebugInfo debug,
                                            float finalDamage, Entity bulletEntity) {
        String penetrationType = classifyPenetration(result, debug);
        // 未穿透 = 子弹被护甲/护盾/插板完全挡下，未对该部位 HP 造成伤害。
        if (HitRecord.NOT_PENETRATED.equals(penetrationType)) {
            finalDamage = 0.0F;
        }
        String zone = debug.detailedZone != null
            ? debug.detailedZone.name() : DetailedHitZone.CHEST.name();

        // 精确落点：子弹与受害者碰撞箱接触点（支持头皮等精确位置）。
        float[] point;
        if (bulletEntity != null) {
            point = HitLocator.projectile(bulletEntity, victim, zone);
        } else {
            point = new float[]{0.5F, 0.5F};
        }

        BulletConfig.BulletEntry bulletEntry = debug.bulletEntry;
        String bulletId = bulletEntry != null
            ? (bulletEntry.itemId != null && !bulletEntry.itemId.isEmpty()
                ? bulletEntry.itemId : (debug.ammoId != null ? debug.ammoId : "unknown"))
            : (debug.ammoId != null ? debug.ammoId : "unknown");
        float bulletPen = bulletEntry != null
            ? bulletEntry.penetrationLevel : debug.effectivePenetration;
        float bulletBase = bulletEntry != null && bulletEntry.baseDamage > 0
            ? bulletEntry.baseDamage : debug.originalDamage;

        String armorName = "";
        int armorLevel = 0;
        int armorDur = 0;
        int armorMax = 0;

        if (!debug.slotInfos.isEmpty()) {
            SlotDebugInfo s = debug.slotInfos.get(debug.slotInfos.size() - 1);
            ItemStack worn = s.slot != null ? victim.getItemBySlot(s.slot) : ItemStack.EMPTY;

            if (s.isShield) {
                armorName = s.shieldName != null && !s.shieldName.isEmpty()
                    ? s.shieldName
                    : (!worn.isEmpty() ? worn.getDisplayName().getString() : "护盾");
                armorLevel = s.shieldLevel;
                armorDur = s.remainingDurability;
                armorMax = s.initialDurability;
            } else if (s.usingPlate) {
                armorName = !worn.isEmpty() ? worn.getDisplayName().getString() : "护甲";
                armorLevel = s.plateLevel;
                armorDur = (int) s.plateRemainingDurability;
                armorMax = resolvePlateMaxDurability(worn, s);
            } else if (s.plateInfo != null
                       && (s.plateInfo.equals("固定面罩") || s.plateInfo.equals("面部配饰"))) {
                armorName = s.plateInfo;
                armorLevel = s.armorEntry != null ? s.armorEntry.armorLevel : 0;
                armorDur = s.remainingDurability;
                armorMax = s.initialDurability;
            } else if (!worn.isEmpty()) {
                armorName = worn.getDisplayName().getString();
                armorLevel = s.armorEntry != null ? s.armorEntry.armorLevel : 0;
                armorDur = s.remainingDurability;
                armorMax = s.initialDurability;
            } else if (s.armorName != null && !s.armorName.isEmpty()) {
                armorName = s.armorName;
                armorLevel = s.armorEntry != null ? s.armorEntry.armorLevel : 0;
                armorDur = s.remainingDurability;
                armorMax = s.initialDurability;
            }

            if (armorMax <= 0) {
                armorMax = armorDur;
            }
        }

        return new HitRecord(zone, bulletId, bulletPen, bulletBase,
            armorName, armorLevel, armorDur, armorMax,
            penetrationType, finalDamage, opponentName, victim.level().getGameTime(),
            point[0], point[1], false);
    }

    /** 从命中侧插板 NBT 读取最大耐久；读不到时用 剩余+本次损伤 估算。 */
    private static int resolvePlateMaxDurability(ItemStack worn, SlotDebugInfo s) {
        try {
            if (FracturepointHelper.hasPlate(worn)) {
                CompoundTag plateNbt = s.isFrontPlate
                    ? (FracturepointHelper.hasFrontPlate(worn)
                        ? FracturepointHelper.getFrontPlate(worn) : null)
                    : (FracturepointHelper.hasBackPlate(worn)
                        ? FracturepointHelper.getBackPlate(worn) : null);
                if (plateNbt != null) {
                    return (int) FracturepointHelper.getPlateMaxDurability(plateNbt);
                }
            }
        } catch (Throwable ignored) {
        }
        return (int) (s.plateRemainingDurability + s.plateDurabilityDamage);
    }

    private static String classifyPenetration(GunDamageResult result, DebugInfo debug) {
        if (result.blockedByShield) {
            return HitRecord.NOT_PENETRATED;
        }
        if (debug.slotInfos.isEmpty()) {
            return HitRecord.OVER_PENETRATED;
        }
        SlotDebugInfo s = debug.slotInfos.get(debug.slotInfos.size() - 1);
        if (s.isShield) {
            return s.penetrated ? HitRecord.PENETRATED : HitRecord.NOT_PENETRATED;
        }
        if (s.usingPlate) {
            if (!s.platePenetrated) {
                return HitRecord.NOT_PENETRATED;
            }
            return s.penetrated ? HitRecord.PENETRATED : HitRecord.NOT_PENETRATED;
        }
        if (s.armorNotConfigured) {
            return HitRecord.OVER_PENETRATED;
        }
        if (s.armorEntry == null || s.initialDurability <= 0) {
            return HitRecord.OVER_PENETRATED;
        }
        if (s.penetrated) {
            return s.damageBlocked > 0
                ? HitRecord.PENETRATED : HitRecord.OVER_PENETRATED;
        }
        return HitRecord.NOT_PENETRATED;
    }

    private static DetailedHitZone determineHitSlot(LivingEntity entity, Object bulletEntity, boolean isHeadShot, LivingEntity attacker) {
        try {
            net.minecraft.world.phys.Vec3 rayStart = null;
            net.minecraft.world.phys.Vec3 rayEnd = null;
            String methodUsed = null;

            if (bulletEntity instanceof net.minecraft.world.entity.Entity bullet) {
                net.minecraft.world.phys.Vec3 movement = bullet.getDeltaMovement();
                if (movement.lengthSqr() > 1.0E-8D) {
                    rayStart = bullet.position();
                    rayEnd = rayStart.add(movement);
                    methodUsed = "TACZ-BULLET-RAY";
                }
            }

            if (rayStart == null && attacker != null) {
                rayStart = attacker.getEyePosition(1.0f);
                rayEnd = rayStart.add(attacker.getLookAngle().scale(100.0D));
                methodUsed = "ATTACKER-RAY-FALLBACK";
            }

            if (rayStart != null && rayEnd != null) {
                net.minecraft.world.phys.AABB entityBox = entity.getBoundingBox();
                boolean usingTaczHitbox = false;

                if (getFixedBoundingBoxMethod != null) {
                    try {
                        Object fixedBox = getFixedBoundingBoxMethod.invoke(null, entity, attacker);
                        if (fixedBox instanceof net.minecraft.world.phys.AABB taczHitbox) {
                            entityBox = taczHitbox;
                            usingTaczHitbox = true;
                            methodUsed += "-TACZ-HITBOX";
                        }
                    } catch (ReflectiveOperationException e) {
                        FragmentationMod.LOGGER.warn("[TaczGun] Failed to resolve TACZ fixed hitbox: " + e.getMessage());
                    }
                }

                java.util.Optional<net.minecraft.world.phys.Vec3> hitPointOpt = entityBox.clip(rayStart, rayEnd);

                if (hitPointOpt.isEmpty() && usingTaczHitbox) {
                    entityBox = entity.getBoundingBox();
                    hitPointOpt = entityBox.clip(rayStart, rayEnd);
                    methodUsed += "-CURRENT-HITBOX-FALLBACK";
                }

                if (hitPointOpt.isEmpty()) {
                    FragmentationMod.LOGGER.warn("[TaczGun] determineHitSlot [" + methodUsed +
                        "]: bullet ray did not intersect the target hitbox");
                    return isHeadShot ? DetailedHitZone.HEAD : DetailedHitZone.CHEST;
                }

                net.minecraft.world.phys.Vec3 hitPoint = hitPointOpt.get();
                if (bulletEntity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                    CompoundTag impactData = entity.getPersistentData();
                    impactData.putString(HitZoneResolver.IMPACT_PROJECTILE_UUID_KEY, projectile.getUUID().toString());
                    impactData.putLong(HitZoneResolver.IMPACT_TICK_KEY, entity.level().getGameTime());
                    impactData.putDouble(HitZoneResolver.IMPACT_X_KEY, hitPoint.x);
                    impactData.putDouble(HitZoneResolver.IMPACT_Y_KEY, hitPoint.y);
                    impactData.putDouble(HitZoneResolver.IMPACT_Z_KEY, hitPoint.z);
                }

                double entityMinY = entityBox.minY;
                double entityMaxY = entityBox.maxY;
                double entityHeight = entityMaxY - entityMinY;
                double ratioY = entityHeight > 1.0E-8D
                    ? (hitPoint.y() - entityMinY) / entityHeight
                    : 0.5D;
                ratioY = Math.max(0.0D, Math.min(1.0D, ratioY));

                if (entityHeight < 1.0) {
                    FragmentationMod.LOGGER.info("[TaczGun] determineHitSlot: Entity appears to be crawling/prone, height=" + entityHeight);
                    
                    net.minecraft.world.phys.Vec3 entityEyePos = entity.getEyePosition(1.0f);
                    
                    if (hitPoint.y() >= entityEyePos.y() - 0.3 && hitPoint.y() <= entityEyePos.y() + 0.3) {
                        ratioY = 0.85;
                        FragmentationMod.LOGGER.info("[TaczGun] determineHitSlot: Hit near eye height, adjusting ratioY to HEAD region");
                    } else if (hitPoint.y() > entityEyePos.y()) {
                        ratioY = 0.85;
                        FragmentationMod.LOGGER.info("[TaczGun] determineHitSlot: Hit above eye level, adjusting ratioY to HEAD region");
                    }
                }

                double entityCenterX = (entityBox.minX + entityBox.maxX) / 2.0;
                double entityCenterZ = (entityBox.minZ + entityBox.maxZ) / 2.0;
                double halfWidth = (entityBox.maxX - entityBox.minX) / 2.0;

                double offsetX = hitPoint.x() - entityCenterX;
                double offsetZ = hitPoint.z() - entityCenterZ;

                double bodyYawRadians = Math.toRadians(entity.yBodyRot);
                double rightX = -Math.cos(bodyYawRadians);
                double rightZ = -Math.sin(bodyYawRadians);
                double dotRight = offsetX * rightX + offsetZ * rightZ;

                double horizRatio = halfWidth > 0 ? Math.abs(dotRight) / halfWidth : 0.0;
                boolean isRightSide = dotRight > 0;

                // 身体正前方方向：right=(rx,rz) 时 forward=(rz,-rx)
                // yBodyRot=0 时 forward=(0,1) 指向 +Z（MC 中 yaw=0 面向 +Z）
                double forwardX = rightZ;
                double forwardZ = -rightX;
                double dotForward = offsetX * forwardX + offsetZ * forwardZ;
                // 命中点在身体箱体中的前后位置：+1 正面边缘，-1 背面边缘，0 正中
                double depthRatio = halfWidth > 0 ? dotForward / halfWidth : 0.0;

                FragmentationMod.LOGGER.info("[TaczGun] determineHitSlot [" + methodUsed + "] attacker=" +
                    (attacker != null ? attacker.getName().getString() : "unknown") +
                    ", rayStart=" + rayStart +
                    ", rayEnd=" + rayEnd +
                    ", hitY=" + String.format("%.2f", hitPoint.y()) +
                    ", ratioY=" + String.format("%.2f", ratioY) +
                    ", entityMinY=" + String.format("%.2f", entityMinY) +
                    ", entityMaxY=" + String.format("%.2f", entityMaxY) +
                    ", horizRatio=" + String.format("%.2f", horizRatio) +
                    ", depthRatio=" + String.format("%.2f", depthRatio) +
                    ", isRight=" + isRightSide);

                DetailedHitZone result;
                if (isHeadShot || ratioY >= 0.75) {
                    // 头部区域细分（塔科夫式面部判定）：
                    // 头部箱体前侧（depthRatio>=0.30，约正面 35%）且非头顶（ratioY<0.965）→ 面部 FACE；
                    // 侧面/后方/头顶 → 颅部 HEAD，由头盔防护。
                    boolean faceHit = ratioY < 0.965 && depthRatio >= 0.30;
                    result = faceHit ? DetailedHitZone.FACE : DetailedHitZone.HEAD;
                } else if (ratioY >= 0.55) {
                    if (horizRatio > 0.55) {
                        result = isRightSide ? DetailedHitZone.RIGHT_ARM : DetailedHitZone.LEFT_ARM;
                    } else {
                        result = DetailedHitZone.CHEST;
                    }
                } else if (ratioY >= 0.40) {
                    if (horizRatio > 0.80) {
                        result = isRightSide ? DetailedHitZone.RIGHT_ARM : DetailedHitZone.LEFT_ARM;
                    } else {
                        result = DetailedHitZone.ABDOMEN;
                    }
                } else if (ratioY >= 0.10) {
                    if (horizRatio > 0.80) {
                        result = isRightSide ? DetailedHitZone.RIGHT_ARM : DetailedHitZone.LEFT_ARM;
                    } else {
                        result = isRightSide ? DetailedHitZone.RIGHT_LEG : DetailedHitZone.LEFT_LEG;
                    }
                } else {
                    result = isRightSide ? DetailedHitZone.RIGHT_LEG : DetailedHitZone.LEFT_LEG;
                }

                FragmentationMod.LOGGER.info("[TaczGun] determineHitSlot [" + methodUsed + "] returning " + result.getDisplayName());
                return result;
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.warn("[TaczGun] determineHitSlot failed: " + e.getMessage());
        }

        DetailedHitZone fallback = isHeadShot ? DetailedHitZone.HEAD : DetailedHitZone.CHEST;
        FragmentationMod.LOGGER.warn("[TaczGun] determineHitSlot: No valid hit detection method succeeded, returning " + fallback);
        return fallback;
    }

    private static DefensiveSurface findDefensiveSurface(
        Entity bullet,
        LivingEntity hurtEntity,
        EquipmentSlot hitSlot,
        DetailedHitZone detailedZone,
        LivingEntity attacker
    ) {
        Vec3 normal = resolveSurfaceNormal(bullet, hurtEntity, attacker);

        ShieldConfig shieldConfig = FragmentationMod.getShieldConfig();
        EquipmentSlot shieldSlot = getActiveShieldSlot(hurtEntity);
        if (shieldSlot != null
            && isShieldFacingAttack(hurtEntity, bullet, attacker)) {
            ItemStack shieldStack = hurtEntity.getItemBySlot(shieldSlot);
            ShieldConfig.ShieldEntry shield =
                shieldConfig.getShieldForItem(shieldStack);
            if (shield != null
                && DurabilityUtils.getCurrentDurability(shieldStack) > 0) {
                DefensiveSurface plateSurface = plateSurface(
                    FracturepointHelper.getFrontPlate(shieldStack),
                    normal,
                    "护盾插板"
                );
                if (plateSurface != null) return plateSurface;
                if (shield.ricochetCoefficient > 0.0D) {
                    return new DefensiveSurface(
                        shield.getDisplayName(),
                        shield.ricochetCoefficient,
                        normal
                    );
                }
            }
        }

        if (hitSlot == EquipmentSlot.CHEST) {
            ItemStack chestStack = hurtEntity.getItemBySlot(
                EquipmentSlot.CHEST
            );
            CompoundTag activePlate = isFrontAttack(hurtEntity, attacker)
                ? FracturepointHelper.getFrontPlate(chestStack)
                : FracturepointHelper.getBackPlate(chestStack);
            DefensiveSurface plateSurface = plateSurface(
                activePlate,
                normal,
                "胸甲插板"
            );
            if (plateSurface != null) return plateSurface;
        }

        ItemStack armorStack = hurtEntity.getItemBySlot(hitSlot);
        ArmorConfig.ArmorEntry armor =
            FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
        if (armor != null
            && armor.ricochetCoefficient > 0.0D
            && DurabilityUtils.getCurrentDurability(armorStack) > 0) {
            return new DefensiveSurface(
                armor.getDisplayName(),
                armor.ricochetCoefficient,
                normal
            );
        }
        return null;
    }

    private static DefensiveSurface plateSurface(
        CompoundTag plateNbt,
        Vec3 normal,
        String surfaceName
    ) {
        if (plateNbt == null
            || FracturepointHelper.getPlateCurrentDurability(plateNbt) <= 0.0F) {
            return null;
        }
        PlateConfig.PlateEntry plate =
            FracturepointHelper.getPlateConfigEntry(plateNbt);
        if (plate == null || plate.ricochetCoefficient <= 0.0D) {
            return null;
        }
        return new DefensiveSurface(
            surfaceName + ": " + plate.getDisplayName(),
            plate.ricochetCoefficient,
            normal
        );
    }

    private static Vec3 resolveSurfaceNormal(
        Entity bullet,
        LivingEntity hurtEntity,
        LivingEntity attacker
    ) {
        if (bullet != null && bullet.getDeltaMovement().lengthSqr() > 1.0E-8D) {
            Vec3 movement = bullet.getDeltaMovement();
            Vec3 current = bullet.position();
            Vec3 start = current.subtract(movement);
            Vec3 end = current.add(movement);
            AABB box = hurtEntity.getBoundingBox();
            java.util.Optional<Vec3> impact = box.clip(start, end);
            if (impact.isPresent()) {
                return nearestBoxFaceNormal(box, impact.get());
            }
        }

        if (attacker != null) {
            Vec3 outward = attacker.getEyePosition().subtract(
                hurtEntity.getBoundingBox().getCenter()
            );
            if (outward.lengthSqr() > 1.0E-8D) {
                return outward.normalize();
            }
        }

        Vec3 movement = bullet != null ? bullet.getDeltaMovement() : Vec3.ZERO;
        return movement.lengthSqr() > 1.0E-8D
            ? movement.normalize().scale(-1.0D)
            : new Vec3(0.0D, 0.0D, 1.0D);
    }

    private static Vec3 nearestBoxFaceNormal(AABB box, Vec3 point) {
        double nearest = Math.abs(point.x - box.minX);
        Vec3 normal = new Vec3(-1.0D, 0.0D, 0.0D);

        double distance = Math.abs(point.x - box.maxX);
        if (distance < nearest) {
            nearest = distance;
            normal = new Vec3(1.0D, 0.0D, 0.0D);
        }
        distance = Math.abs(point.y - box.minY);
        if (distance < nearest) {
            nearest = distance;
            normal = new Vec3(0.0D, -1.0D, 0.0D);
        }
        distance = Math.abs(point.y - box.maxY);
        if (distance < nearest) {
            nearest = distance;
            normal = new Vec3(0.0D, 1.0D, 0.0D);
        }
        distance = Math.abs(point.z - box.minZ);
        if (distance < nearest) {
            nearest = distance;
            normal = new Vec3(0.0D, 0.0D, -1.0D);
        }
        distance = Math.abs(point.z - box.maxZ);
        if (distance < nearest) {
            normal = new Vec3(0.0D, 0.0D, 1.0D);
        }
        return normal;
    }
    
    public static GunDamageResult calculateGunDamage(LivingEntity entity, float baseDamage, EquipmentSlot hitSlot,
                                                      DetailedHitZone detailedZone,
                                                      BulletConfig.BulletEntry bulletEntry,
                                                      float effectiveBulletLevel,
                                                      DebugInfo debugInfo, LivingEntity attacker) {
        GunDamageResult result = new GunDamageResult();
        result.originalDamage = baseDamage;
        result.finalDamage = baseDamage;
        result.wasModified = false;
        
        if (baseDamage <= 0) return result;

        boolean isFrontAttack = isFrontAttack(entity, attacker);
        debugInfo.isFrontAttack = isFrontAttack;

        FragmentationMod.LOGGER.info("[TaczGun] Calculating damage for entity: " + entity.getName().getString() + 
            ", hitSlot: " + hitSlot + ", baseDamage: " + baseDamage + 
            ", isFrontAttack: " + isFrontAttack);

        if (entity instanceof Player || (!detailedZone.isHead() && !detailedZone.isFace() && !detailedZone.isLeg())) {
            GunDamageResult shieldResult = calculateShieldDamage(entity, baseDamage, hitSlot, detailedZone, bulletEntry, 
                effectiveBulletLevel, debugInfo, attacker, isFrontAttack);
            if (shieldResult != null) {
                return shieldResult;
            }
        }

        if (detailedZone.isArm()) {
            GunDamageResult armResult = calculateArmDamage(entity, baseDamage, hitSlot, detailedZone, bulletEntry, 
                effectiveBulletLevel, debugInfo, attacker, isFrontAttack);
            if (armResult != null) {
                return armResult;
            }
        }

        // 面部命中：独立的塔科夫式判定（有防弹面罩→面罩防弹；无面罩→无视头盔直接穿透）
        if (detailedZone.isFace()) {
            return calculateFaceDamage(entity, baseDamage, detailedZone, bulletEntry,
                effectiveBulletLevel, debugInfo, attacker, isFrontAttack);
        }

        ItemStack armorStack = entity.getItemBySlot(hitSlot).copy();
        
        FragmentationMod.LOGGER.info("[TaczGun] getItemBySlot(" + hitSlot + ") = " + 
            (armorStack.isEmpty() ? "EMPTY" : armorStack.getItem().getDescriptionId()));
        
        if (armorStack.isEmpty()) {
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(detailedZone);
            float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.wasModified = true;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;
            SlotDebugInfo slotInfo = new SlotDebugInfo();
            slotInfo.slot = hitSlot;
            slotInfo.penetrated = true;
            slotInfo.damageMultiplier = bodyZoneMultiplier;
            slotInfo.damageToSlot = baseDamage;
            slotInfo.damageBlocked = 0;
            slotInfo.gunDamageMultiplier = gunDamageMultiplier;
            debugInfo.slotInfos.add(slotInfo);
            
            FragmentationMod.LOGGER.info("[TaczGun] Empty armor slot, returning damage with body zone multiplier: " + bodyZoneMultiplier + ", gunDamageMultiplier=" + gunDamageMultiplier + ", finalDamage=" + result.finalDamage);
            return result;
        }
        
        ArmorConfig.ArmorEntry armorEntry = FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
        
        FragmentationMod.LOGGER.info("[TaczGun] Armor entry found: " + (armorEntry != null ? "yes" : "no"));
        
        result.wasModified = true;
        
        if (armorEntry == null) {
            // 护甲存在但未配置：视为子弹直接穿透（伤害部位已在主流程完成随机）
            DetailedHitZone effectiveZone = detailedZone;
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
            float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;
            SlotDebugInfo slotInfo = new SlotDebugInfo();
            slotInfo.slot = hitSlot;
            slotInfo.penetrated = true;
            slotInfo.damageMultiplier = bodyZoneMultiplier;
            slotInfo.damageToSlot = baseDamage;
            slotInfo.damageBlocked = 0;
            slotInfo.armorNotConfigured = true;
            slotInfo.armorName = armorStack.getDisplayName().getString();
            slotInfo.gunDamageMultiplier = gunDamageMultiplier;
            debugInfo.slotInfos.add(slotInfo);
            
            FragmentationMod.LOGGER.info("[TaczGun] Armor not configured, returning damage with body zone multiplier: " + bodyZoneMultiplier + ", gunDamageMultiplier=" + gunDamageMultiplier);
            return result;
        }

        // protection.json 中的可选属性覆盖（护甲等级/耐久/材料）
        armorEntry = effectiveArmorEntry(armorStack, armorEntry);

        // 防护部位检查：该防具不防护命中部位时，子弹无视防具直接穿透（不消耗防具耐久）
        if (!FragmentationMod.getProtectionConfig().coversZone(armorStack, detailedZone)) {
            return penetrateArmorZone(result, entity, baseDamage, hitSlot, detailedZone,
                debugInfo, "armor does not cover zone " + detailedZone.configName(), armorStack);
        }

        int configMax = armorEntry.maxDurability;
        
        net.minecraft.nbt.CompoundTag tag = armorStack.getOrCreateTag();
        tag.putInt("fragmentation_max_durability", configMax);
        
        int currentDurability = DurabilityUtils.getCurrentDurability(armorStack);
        int maxDurability = configMax;

        FragmentationMod.LOGGER.info("[TaczGun] Durability check: stackId=" + System.identityHashCode(armorStack) +
            ", tag=" + (armorStack.getTag() != null ? armorStack.getTag().toString() : "null") +
            ", configMax=" + configMax + ", currentDurability=" + currentDurability);

        if (currentDurability > maxDurability) {
            currentDurability = maxDurability;
        }
        
        // 护甲耐久为 0，但若命中侧插板仍有效（耐久未耗尽），插板应继续提供防护，
        // 不能在此提前返回——否则会出现"护甲耐久0、插板还有耐久却完全不生效"。
        boolean hasValidPlateForHit = false;
        if (hitSlot == EquipmentSlot.CHEST && FracturepointHelper.hasPlate(armorStack)) {
            net.minecraft.nbt.CompoundTag sidePlate = isFrontAttack
                ? (FracturepointHelper.hasFrontPlate(armorStack)
                    ? FracturepointHelper.getFrontPlate(armorStack) : null)
                : (FracturepointHelper.hasBackPlate(armorStack)
                    ? FracturepointHelper.getBackPlate(armorStack) : null);
            float sidePlateDur = sidePlate != null
                ? FracturepointHelper.getPlateCurrentDurability(sidePlate) : 0;
            hasValidPlateForHit = sidePlateDur > FracturepointHelper.DEPLETED_THRESHOLD;
        }

        if (currentDurability <= 0 && !hasValidPlateForHit) {
            // 护甲存在但耐久为 0（且命中侧无有效插板）＝子弹直接穿透（部位已随机）
            DetailedHitZone effectiveZone = detailedZone;
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
            float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;

            // 穿透状态（护甲耐久已为 0）：ct 仅在本周期首次播放，修满才允许再播
            ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, getArmorImpactPos(entity));
            entity.setItemSlot(hitSlot, armorStack);
            if (entity instanceof net.minecraft.server.level.ServerPlayer sp0) {
                sp0.inventoryMenu.broadcastChanges();
            }

            SlotDebugInfo slotInfo = new SlotDebugInfo();
            slotInfo.slot = hitSlot;
            slotInfo.armorEntry = armorEntry;
            slotInfo.initialDurability = configMax;
            slotInfo.damageToSlot = baseDamage;
            slotInfo.damageBlocked = 0;
            slotInfo.durabilityDamage = 0;
            slotInfo.penetrated = true;
            slotInfo.damageMultiplier = bodyZoneMultiplier;
            slotInfo.armorDamageMultiplier = 1.0f;
            slotInfo.penetrationChance = 1.0f;
            slotInfo.remainingDurability = 0;
            slotInfo.plateInfo = "无";
            slotInfo.isFrontAttack = isFrontAttack;
            slotInfo.gunDamageMultiplier = gunDamageMultiplier;
            debugInfo.slotInfos.add(slotInfo);

            FragmentationMod.LOGGER.info("[TaczGun] EARLY CHECK: Armor durability is 0, returning full damage with body zone multiplier: " + bodyZoneMultiplier + ", finalDamage=" + result.finalDamage);
            return result;
        }
        
        float bulletLevel = bulletEntry != null ? bulletEntry.penetrationLevel : 1.0f;
        float armorDamage = getEffectiveArmorDamage(bulletEntry, debugInfo);
        
        GunConfig gunConfig = GunConfig.getInstanceFresh();
        float gunDamageMultiplier = gunConfig.getDamageMultiplier(debugInfo.gunId);
        float gunPenetrationMultiplier = gunConfig.getPenetrationMultiplier(debugInfo.gunId);
        
        FragmentationMod.LOGGER.info("[TaczGun] DEBUG: gunId=" + debugInfo.gunId + 
            ", gunDamageMultiplier=" + gunDamageMultiplier + 
            ", gunPenetrationMultiplier=" + gunPenetrationMultiplier +
            ", powerLevel=" + gunConfig.getPowerLevel(debugInfo.gunId).name() +
            ", bulletLevel=" + bulletLevel + ", effectiveBulletLevel=" + effectiveBulletLevel);
        
        String armorMaterialName = armorEntry.getMaterial();
        ArmorMaterialConfig.MaterialEntry materialEntry = FragmentationMod.getArmorMaterialConfig().getMaterial(armorMaterialName);
        float materialDamageAbsorption = materialEntry != null ? materialEntry.damageAbsorptionRatio : 1.0f;
        float materialArmorDamageRatio = materialEntry != null ? materialEntry.armorDamageRatio : 1.0f;
        
        FragmentationMod.LOGGER.info("[TaczGun] Gun: " + debugInfo.gunId + 
            ", gunDamageMultiplier=" + gunDamageMultiplier + 
            ", gunPenetrationMultiplier=" + gunPenetrationMultiplier + 
            ", bulletLevel=" + bulletLevel + 
            ", effectiveBulletLevel=" + effectiveBulletLevel + 
            ", material=" + armorMaterialName + 
            ", materialDamageAbsorption=" + materialDamageAbsorption + 
            ", materialArmorDamageRatio=" + materialArmorDamageRatio);
        
        float actualArmorLevel = (float) armorEntry.armorLevel * ((float) currentDurability / (float) maxDurability);
        
        float durabilityRatio = (float) currentDurability / (float) maxDurability;
        float factor1 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f + materialDamageAbsorption);
        float factor2 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f / materialDamageAbsorption);
        float penetrationThreshold = (float) armorEntry.armorLevel * 10 * factor1 * factor2;
        
        FragmentationMod.LOGGER.info("[TaczGun] Base armor level: " + armorEntry.armorLevel + 
            ", durability ratio: " + String.format("%.2f", durabilityRatio) +
            ", material absorption: " + materialDamageAbsorption +
            ", penetration threshold: " + String.format("%.2f", penetrationThreshold) +
            ", actual armor level: " + String.format("%.2f", actualArmorLevel));
        
        boolean hasFracturepointPlate = FracturepointHelper.hasPlate(armorStack);
        boolean hasFrontPlate = FracturepointHelper.hasFrontPlate(armorStack);
        boolean hasBackPlate = FracturepointHelper.hasBackPlate(armorStack);
        
        net.minecraft.nbt.CompoundTag activePlateNbt = null;
        int activePlateLevel = 0;
        float activePlateDurability = 0;
        float activePlateMaxDurability = 0;
        boolean isFrontPlate = false;
        
        if (hasFracturepointPlate && hitSlot == EquipmentSlot.CHEST) {
            float frontPlateDurability = hasFrontPlate ? FracturepointHelper.getPlateCurrentDurability(FracturepointHelper.getFrontPlate(armorStack)) : 0;
            float backPlateDurability = hasBackPlate ? FracturepointHelper.getPlateCurrentDurability(FracturepointHelper.getBackPlate(armorStack)) : 0;
            
            if (isFrontAttack && hasFrontPlate
                && frontPlateDurability > FracturepointHelper.DEPLETED_THRESHOLD) {
                activePlateNbt = FracturepointHelper.getFrontPlate(armorStack);
                isFrontPlate = true;
                FragmentationMod.LOGGER.info("[TaczGun] Front attack, using front plate");
            } else if (!isFrontAttack && hasBackPlate
                && backPlateDurability > FracturepointHelper.DEPLETED_THRESHOLD) {
                activePlateNbt = FracturepointHelper.getBackPlate(armorStack);
                isFrontPlate = false;
                FragmentationMod.LOGGER.info("[TaczGun] Back attack, using back plate");
            } else {
                FragmentationMod.LOGGER.info("[TaczGun] Plate on hit side is depleted (<= "
                    + FracturepointHelper.DEPLETED_THRESHOLD + "), skipping plate, bullet hits armor directly");
            }
            
            FragmentationMod.LOGGER.info("[TaczGun] Front plate durability: " + frontPlateDurability + 
                ", back plate durability: " + backPlateDurability + 
                ", activePlateNbt: " + (activePlateNbt != null ? "yes" : "no"));
            
            if (activePlateNbt != null) {
                // 插板等级解析链：plate config → 插板自身 armor config → 弹挂护甲等级
                activePlateLevel = FracturepointHelper.getPlateLevel(activePlateNbt, armorEntry.armorLevel);
                activePlateDurability = FracturepointHelper.getPlateCurrentDurability(activePlateNbt);
                activePlateMaxDurability = FracturepointHelper.getPlateMaxDurability(activePlateNbt);
            }
        }
        
        FragmentationMod.LOGGER.info("[TaczGun] Armor level: " + armorEntry.armorLevel + 
            ", configMax: " + configMax + ", currentDurability: " + currentDurability +
            ", hasPlate: " + hasFracturepointPlate + ", activePlateLevel: " + activePlateLevel + 
            ", activePlateDurability: " + activePlateDurability + ", isFrontPlate: " + isFrontPlate);
        
        if (activePlateNbt != null) {
            float plateDurabilityRatio = activePlateDurability / activePlateMaxDurability;
            float actualPlateLevel = (float) activePlateLevel * plateDurabilityRatio;
            
            FragmentationMod.LOGGER.info("[TaczGun] Base plate level: " + activePlateLevel + 
                ", plate durability ratio: " + String.format("%.2f", plateDurabilityRatio) +
                ", actual plate level: " + String.format("%.2f", actualPlateLevel));
            
            return calculateWithPlate(entity, armorStack, armorEntry, null, baseDamage, hitSlot, detailedZone,
                effectiveBulletLevel, bulletEntry, debugInfo, currentDurability, configMax,
                actualPlateLevel, activePlateDurability, activePlateMaxDurability,
                armorDamage, result, isFrontPlate, activePlateNbt);
        }
        
        if (currentDurability <= 0) {
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(detailedZone);
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;

            FragmentationMod.LOGGER.info("[TaczGun] Armor destroyed, returning damage with body zone multiplier: " + bodyZoneMultiplier + ", gunDamageMultiplier=" + gunDamageMultiplier);
            return result;
        }
        
        float penetrationChance = calculatePenetrationChance(effectiveBulletLevel, penetrationThreshold);

        boolean penetrated = false;
        if (penetrationChance > 0) {
            penetrated = random.nextFloat() < penetrationChance;
        }

        float baseArmorDamageMultiplier = calculateArmorDamageMultiplier(penetrated);
        float finalArmorDamageMultiplier = baseArmorDamageMultiplier * gunPenetrationMultiplier * materialArmorDamageRatio
            * lowLevelArmorDamageFactor(effectiveBulletLevel, actualArmorLevel);

        int durabilityDamage = (int) Math.ceil(armorDamage * finalArmorDamageMultiplier);

        float finalDamage;
        net.minecraft.world.phys.Vec3 impactPos = getArmorImpactPos(entity);
        if (penetrated) {
            float reducedDamage = baseDamage - (actualArmorLevel * FIXED_DAMAGE_REDUCTION);
            finalDamage = reducedDamage / materialDamageAbsorption;
            result.shouldBypassReduction = true;
            // 子弹穿透防弹衣：ct 本周期只播一次（NBT 门控，修满重置）
            ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, impactPos);
        } else {
            finalDamage = baseDamage * 0.01f;
            if (finalDamage > 0) {
                // 未穿透：每发播放一次 wct + 命中点少量白色烟雾
                ArmorSoundManager.playArmorBlockSound(entity, impactPos);
            }
        }
        
        int newDurability = Math.max(0, currentDurability - durabilityDamage);
        
        if (newDurability <= 0 && durabilityDamage > 0) {
            // 护甲耐久归零（被击碎）：ct 一次门控（与上方穿透共用标记，不会重复响）
            ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, impactPos);
        }

        DurabilityUtils.setCurrentDurability(armorStack, newDurability);
        entity.setItemSlot(hitSlot, armorStack);
        
        if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
            serverPlayer.onUpdateAbilities();
        }
        
        FragmentationMod.LOGGER.info("[TaczGun] Applied armor damage directly: slot=" + hitSlot + 
            ", amount=" + durabilityDamage + ", durability " + currentDurability + " -> " + newDurability + "/" + configMax);
        
        result.armorHitSlot = hitSlot;
        result.armorDamageAmount = durabilityDamage;
        result.hasArmorDamage = true;
        
        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = hitSlot;
        slotInfo.armorEntry = armorEntry;
        slotInfo.bulletLevel = effectiveBulletLevel;
        slotInfo.initialDurability = configMax;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = Math.max(0, baseDamage - finalDamage);
        slotInfo.durabilityDamage = durabilityDamage;
        slotInfo.penetrated = penetrated;
        slotInfo.damageMultiplier = penetrated ? (1.0f - penetrationThreshold * FIXED_DAMAGE_REDUCTION / baseDamage) : 0.01f;
        slotInfo.armorDamageMultiplier = finalArmorDamageMultiplier;
        slotInfo.penetrationChance = penetrationChance;
        slotInfo.remainingDurability = newDurability;
        slotInfo.plateInfo = hasFracturepointPlate ? FracturepointHelper.getPlateInfo(armorStack, armorEntry.armorLevel) : "无";
        slotInfo.isFrontAttack = isFrontAttack;
        slotInfo.armorMaterial = armorMaterialName;
        slotInfo.gunDamageMultiplier = gunDamageMultiplier;
        slotInfo.gunArmorDamageMultiplier = gunPenetrationMultiplier;
        debugInfo.slotInfos.add(slotInfo);
        
        result.finalDamage = finalDamage;

        // AI 特殊伤害判定：子弹穿透防弹衣/头盔后，伤害落点已在主流程随机重掷
        DetailedHitZone effectiveZone = detailedZone;
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
        if (bodyZoneMultiplier != 1.0f) {
            result.finalDamage = finalDamage * bodyZoneMultiplier;
            FragmentationMod.LOGGER.info("[TaczGun] Applied body zone multiplier: " + hitSlot + " -> " + bodyZoneMultiplier + ", finalDamage: " + finalDamage + " -> " + result.finalDamage);
        }

        return result;
    }
    
    /** Warborn/Fracturepoint 头盔面甲开合的 NBT 键（true=面甲打开，面部失去防护）。 */
    private static final String HELMET_TOP_OPEN_KEY = "helmet_top_open";

    /**
     * 塔科夫式面部伤害结算。面罩来源按优先级：
     * 1. Curios 面部配饰（facewear 槽，如 mm:black_visor）；
     * 2. 头盔自带固定面罩（/fragmentation visor add 登记，耐久可自定义）。
     * 任一来源有效（耐久&gt;0）→ 面罩按标准护甲公式防弹；
     * 全部无效 → 无视头盔防弹效果，子弹直接穿透面部。
     * 另：头盔面甲打开（helmet_top_open）时面部一律失去防护。
     */
    private static GunDamageResult calculateFaceDamage(
            LivingEntity entity, float baseDamage, DetailedHitZone faceZone,
            BulletConfig.BulletEntry bulletEntry, float effectiveBulletLevel,
            DebugInfo debugInfo, LivingEntity attacker, boolean isFrontAttack) {

        GunDamageResult result = new GunDamageResult();
        result.originalDamage = baseDamage;
        result.finalDamage = baseDamage;
        result.wasModified = true;

        ItemStack helmet = entity.getItemBySlot(EquipmentSlot.HEAD).copy();
        boolean helmetPresent = !helmet.isEmpty();

        // 1) 面甲开合门控（Warborn 原生 NBT 键）：面甲打开 → 面部无任何防护
        if (helmetPresent && helmet.hasTag() && helmet.getTag().getBoolean(HELMET_TOP_OPEN_KEY)) {
            FragmentationMod.LOGGER.info("[TaczGun] Face hit but helmet visor is open, face unprotected");
            return facePenetration(result, entity, helmet, baseDamage, faceZone, debugInfo,
                isFrontAttack, "helmet visor open");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();

        // 2) Curios 面部配饰（facewear 槽优先，其余槽严格匹配）
        FacewearHelper.WornVisor worn = FacewearHelper.findWornVisor(entity,
            s -> pc.findVisorItem(s) != null
                || FragmentationMod.getArmorConfig().getArmorForItem(s) != null,
            s -> pc.findVisorItem(s) != null);
        if (worn != null) {
            ItemStack wornStack = worn.stack();
            // 属性解析：protection.json visor_items 优先，其次 armors.json（如 mm:black_visor 已有配置）
            int visorLevel = 3;
            String materialName = "steel";
            int visorMax = 30;
            boolean resolved;
            ProtectionConfig.VisorItemEntry ve = pc.findVisorItem(wornStack);
            if (ve != null) {
                visorMax = ve.maxDurability != null && ve.maxDurability > 0 ? ve.maxDurability : 30;
                visorLevel = ve.armorLevel != null ? ve.armorLevel : 3;
                materialName = ve.material != null && !ve.material.isBlank() ? ve.material : "steel";
                resolved = true;
            } else {
                ArmorConfig.ArmorEntry ae = FragmentationMod.getArmorConfig().getArmorForItem(wornStack);
                if (ae != null) {
                    visorMax = Math.max(1, ae.maxDurability);
                    visorLevel = ae.armorLevel;
                    materialName = ae.getMaterial();
                    resolved = true;
                } else {
                    FragmentationMod.LOGGER.warn("[TaczGun] Worn facewear matched but has no config: "
                        + wornStack.getItem());
                    resolved = false;
                }
            }

            if (resolved) {
                int visorCur = Math.min(DurabilityUtils.getCurrentDurability(wornStack), visorMax);
                if (visorCur > 0) {
                    return calculateVisorProtectedFace(entity, baseDamage, faceZone, bulletEntry,
                        effectiveBulletLevel, debugInfo, isFrontAttack, wornStack, "面部配饰",
                        visorLevel, materialName, visorMax, visorCur,
                        newDur -> {
                            DurabilityUtils.setCurrentDurability(wornStack, newDur);
                            worn.writeBack(wornStack);
                        });
                }
                return facePenetration(result, entity, ItemStack.EMPTY, baseDamage, faceZone,
                    debugInfo, isFrontAttack, "facewear visor durability 0");
            }
        }

        // 3) 头盔自带固定面罩（/fragmentation visor add 登记，耐久存头盔 NBT）
        if (helmetPresent) {
            ProtectionConfig.HelmetVisorEntry builtin = pc.findBuiltinVisorEntry(helmet);
            if (builtin != null) {
                int bMax = Math.max(1, builtin.builtinMaxDurability);
                CompoundTag hTag = helmet.getOrCreateTag();
                // 初始化耐久到 NBT（旧数据可能没有，tooltip 即可显示）
                if (!hTag.contains(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY)) {
                    hTag.putInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY, bMax);
                    hTag.putInt(ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY, bMax);
                    entity.setItemSlot(EquipmentSlot.HEAD, helmet);
                    if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        serverPlayer.inventoryMenu.broadcastChanges();
                    }
                }
                int bCur = Math.min(hTag.getInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY), bMax);

                if (bCur > 0) {
                    ArmorConfig.ArmorEntry helmetEntry =
                        FragmentationMod.getArmorConfig().getArmorForItem(helmet);
                    int bLevel = builtin.builtinLevel != null
                        ? builtin.builtinLevel
                        : (helmetEntry != null ? helmetEntry.armorLevel : 1);
                    String bMat = builtin.builtinMaterial != null && !builtin.builtinMaterial.isBlank()
                        ? builtin.builtinMaterial
                        : (helmetEntry != null ? helmetEntry.getMaterial() : "steel");

                    return calculateVisorProtectedFace(entity, baseDamage, faceZone, bulletEntry,
                        effectiveBulletLevel, debugInfo, isFrontAttack, helmet, "固定面罩",
                        bLevel, bMat, bMax, bCur,
                        newDur -> {
                            helmet.getOrCreateTag().putInt(
                                ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY, newDur);
                            helmet.getOrCreateTag().putInt(
                                ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY, bMax);
                            entity.setItemSlot(EquipmentSlot.HEAD, helmet);
                            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                                serverPlayer.inventoryMenu.broadcastChanges();
                            }
                        });
                }
                return facePenetration(result, entity, helmet, baseDamage, faceZone, debugInfo,
                    isFrontAttack, "builtin visor durability 0");
            }
        }

        // 4) 无任何面罩来源：无视头盔防弹效果，子弹直接穿透面部
        return facePenetration(result, entity, helmet, baseDamage, faceZone, debugInfo,
            isFrontAttack, "no visor source");
    }

    /** 面部无有效面罩：无视头盔防弹效果，子弹直接穿透（面部部位倍率为 1.0）。 */
    private static GunDamageResult facePenetration(GunDamageResult result, LivingEntity entity,
            ItemStack helmet, float baseDamage, DetailedHitZone faceZone,
            DebugInfo debugInfo, boolean isFrontAttack, String reason) {
        if (!helmet.isEmpty()) {
            ArmorSoundManager.playArmorPenetrateSound(entity, helmet, getArmorImpactPos(entity));
        }
        // 伤害部位已在主流程随机（面部被随机到其他部位时不会进入此路径）
        DetailedHitZone effectiveZone = faceZone;
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
        result.finalDamage = baseDamage * bodyZoneMultiplier;
        result.shouldBypassReduction = true;
        debugInfo.overallDamageMultiplier = bodyZoneMultiplier;

        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = EquipmentSlot.HEAD;
        slotInfo.penetrated = true;
        slotInfo.damageMultiplier = bodyZoneMultiplier;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = 0;
        slotInfo.isFrontAttack = isFrontAttack;
        debugInfo.slotInfos.add(slotInfo);

        FragmentationMod.LOGGER.info("[TaczGun] Face penetration (" + reason + ")"
            + ", finalDamage=" + result.finalDamage);
        return result;
    }

    /**
     * 面罩有效时的弹道结算核心（三种面罩来源共用）：
     * 按标准护甲公式计算穿透概率/减伤/耐久消耗，耐久写回通过 writeDurability 回调完成。
     */
    private static GunDamageResult calculateVisorProtectedFace(
            LivingEntity entity, float baseDamage, DetailedHitZone faceZone,
            BulletConfig.BulletEntry bulletEntry, float effectiveBulletLevel,
            DebugInfo debugInfo, boolean isFrontAttack,
            ItemStack soundStack, String sourceDesc,
            int visorLevel, String materialName, int visorMax, int visorCur,
            java.util.function.IntConsumer writeDurability) {

        GunDamageResult result = new GunDamageResult();
        result.originalDamage = baseDamage;
        result.finalDamage = baseDamage;
        result.wasModified = true;

        ArmorMaterialConfig.MaterialEntry materialEntry =
            FragmentationMod.getArmorMaterialConfig().getMaterial(materialName);
        float materialDamageAbsorption = materialEntry != null
            ? materialEntry.damageAbsorptionRatio : 1.0f;
        float materialArmorDamageRatio = materialEntry != null
            ? materialEntry.armorDamageRatio : 1.0f;

        float durabilityRatio = (float) visorCur / (float) visorMax;
        float actualArmorLevel = visorLevel * durabilityRatio;
        float factor1 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f + materialDamageAbsorption);
        float factor2 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f / materialDamageAbsorption);
        float penetrationThreshold = visorLevel * 10 * factor1 * factor2;

        float armorDamage = getEffectiveArmorDamage(bulletEntry, debugInfo);
        float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
        float gunPenetrationMultiplier = GunConfig.getInstanceFresh().getPenetrationMultiplier(debugInfo.gunId);

        float penetrationChance = calculatePenetrationChance(effectiveBulletLevel, penetrationThreshold);
        boolean penetrated = penetrationChance > 0 && random.nextFloat() < penetrationChance;

        float baseArmorDamageMultiplier = calculateArmorDamageMultiplier(penetrated);
        float finalArmorDamageMultiplier = baseArmorDamageMultiplier
            * gunPenetrationMultiplier * materialArmorDamageRatio
            * lowLevelArmorDamageFactor(effectiveBulletLevel, actualArmorLevel);
        int durabilityDamage = (int) Math.ceil(armorDamage * finalArmorDamageMultiplier);

        Vec3 impactPos = getArmorImpactPos(entity);
        float finalDamage;
        if (penetrated) {
            float reducedDamage = baseDamage - actualArmorLevel * FIXED_DAMAGE_REDUCTION;
            finalDamage = reducedDamage / materialDamageAbsorption;
            result.shouldBypassReduction = true;
            ArmorSoundManager.playArmorPenetrateSound(entity, soundStack, impactPos);
        } else {
            finalDamage = baseDamage * 0.01f;
            ArmorSoundManager.playArmorBlockSound(entity, impactPos);
        }

        int newDurability = Math.max(0, visorCur - durabilityDamage);
        if (newDurability <= 0 && durabilityDamage > 0) {
            ArmorSoundManager.playArmorPenetrateSound(entity, soundStack, impactPos);
        }

        writeDurability.accept(newDurability);

        ArmorConfig.ArmorEntry debugEntry = new ArmorConfig.ArmorEntry();
        debugEntry.itemId = sourceDesc;
        debugEntry.armorLevel = visorLevel;
        debugEntry.maxDurability = visorMax;
        debugEntry.material = materialName;

        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = EquipmentSlot.HEAD;
        slotInfo.armorEntry = debugEntry;
        slotInfo.bulletLevel = effectiveBulletLevel;
        slotInfo.initialDurability = visorMax;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = Math.max(0, baseDamage - finalDamage);
        slotInfo.durabilityDamage = durabilityDamage;
        slotInfo.penetrated = penetrated;
        slotInfo.damageMultiplier = penetrated
            ? (1.0f - penetrationThreshold * FIXED_DAMAGE_REDUCTION / baseDamage) : 0.01f;
        slotInfo.armorDamageMultiplier = finalArmorDamageMultiplier;
        slotInfo.penetrationChance = penetrationChance;
        slotInfo.remainingDurability = newDurability;
        slotInfo.plateInfo = sourceDesc;
        slotInfo.isFrontAttack = isFrontAttack;
        slotInfo.armorMaterial = materialName;
        slotInfo.gunDamageMultiplier = gunDamageMultiplier;
        slotInfo.gunArmorDamageMultiplier = gunPenetrationMultiplier;
        debugInfo.slotInfos.add(slotInfo);

        // 伤害部位已在主流程随机
        DetailedHitZone effectiveZone = faceZone;
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
        result.finalDamage = bodyZoneMultiplier != 1.0f
            ? finalDamage * bodyZoneMultiplier : finalDamage;

        FragmentationMod.LOGGER.info("[TaczGun] Face hit (" + sourceDesc + "), visor "
            + (penetrated ? "penetrated" : "blocked") + ", visorDur " + visorCur
            + " -> " + newDurability + "/" + visorMax + ", finalDamage=" + result.finalDamage);
        return result;
    }

    /**
     * 返回该防具本次命中实际生效的护甲条目：protection.json 中若有属性覆盖，
     * 返回应用覆盖后的副本，否则返回原条目。
     */
    private static ArmorConfig.ArmorEntry effectiveArmorEntry(ItemStack armorStack, ArmorConfig.ArmorEntry base) {
        ProtectionConfig.ProtectionEntry p =
            FragmentationMod.getProtectionConfig().findProtection(armorStack);
        if (p == null) return base;
        boolean override = p.armorLevel != null || p.maxDurability != null
            || (p.material != null && !p.material.isBlank());
        if (!override) return base;

        ArmorConfig.ArmorEntry copy = new ArmorConfig.ArmorEntry();
        copy.itemId = base.itemId;
        copy.itemName = base.itemName;
        copy.armorLevel = base.armorLevel;
        copy.maxDurability = base.maxDurability;
        copy.material = base.material;
        copy.ricochetCoefficient = base.ricochetCoefficient;
        copy.nbt = base.nbt;
        if (p.armorLevel != null) copy.armorLevel = p.armorLevel;
        if (p.maxDurability != null) copy.maxDurability = p.maxDurability;
        if (p.material != null && !p.material.isBlank()) copy.material = p.material;
        return copy;
    }

    /**
     * 防具不防护命中部位：子弹无视防具直接穿透（不消耗防具耐久）。
     * 写入与"空槽/未配置"一致的 debug 信息，并参与 AI 随机落点。
     */
    private static GunDamageResult penetrateArmorZone(GunDamageResult result, LivingEntity entity,
            float baseDamage, EquipmentSlot slot, DetailedHitZone detailedZone,
            DebugInfo debugInfo, String reason, ItemStack armorStack) {
        // 伤害部位已在主流程随机
        DetailedHitZone effectiveZone = detailedZone;
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
        float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
        result.finalDamage = baseDamage * bodyZoneMultiplier;
        result.wasModified = true;
        result.shouldBypassReduction = true;
        debugInfo.overallDamageMultiplier = bodyZoneMultiplier;

        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = slot;
        slotInfo.penetrated = true;
        slotInfo.damageMultiplier = bodyZoneMultiplier;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = 0;
        slotInfo.gunDamageMultiplier = gunDamageMultiplier;
        if (armorStack != null) slotInfo.armorName = armorStack.getDisplayName().getString();
        debugInfo.slotInfos.add(slotInfo);

        FragmentationMod.LOGGER.info("[TaczGun] Armor does not protect zone (" + reason
            + "): multiplier=" + bodyZoneMultiplier + ", finalDamage=" + result.finalDamage);
        return result;
    }

    private static GunDamageResult calculateShieldDamage(LivingEntity entity, float baseDamage, EquipmentSlot hitSlot,
                                                        DetailedHitZone detailedZone, BulletConfig.BulletEntry bulletEntry,
                                                        float effectiveBulletLevel, DebugInfo debugInfo,
                                                        LivingEntity attacker, boolean isFrontAttack) {
        EquipmentSlot shieldSlot = getActiveShieldSlot(entity);
        if (shieldSlot == null) {
            FragmentationMod.LOGGER.info(
                "[TaczGun] No held configured shield found"
            );
            return null;
        }

        ItemStack shieldStack = entity.getItemBySlot(shieldSlot).copy();
        ShieldConfig shieldConfig = FragmentationMod.getShieldConfig();
        ShieldConfig.ShieldEntry shieldEntry =
            shieldConfig.getShieldForItem(shieldStack);
        if (shieldEntry == null) {
            FragmentationMod.LOGGER.info(
                "[TaczGun] Held item is not present in shields.json"
            );
            return null;
        }
        
        FragmentationMod.LOGGER.info("[TaczGun] Shield detected: " + shieldStack.getItem().getDescriptionId() + 
            ", slot=" + shieldSlot + ", level=" + shieldEntry.armorLevel + 
            ", maxDurability=" + shieldEntry.maxDurability);
        
        boolean isShieldFacingAttack = isShieldFacingAttack(
            entity,
            debugInfo.projectile,
            attacker
        );
        
        FragmentationMod.LOGGER.info("[TaczGun] Shield facing attack: " + isShieldFacingAttack + 
            ", isFrontAttack=" + isFrontAttack);
        
        if (!isShieldFacingAttack) {
            FragmentationMod.LOGGER.info("[TaczGun] Bullet is not coming from shield direction, skipping shield protection");
            return null;
        }
        
        GunDamageResult result = new GunDamageResult();
        result.originalDamage = baseDamage;
        result.wasModified = true;
        
        int currentDurability = DurabilityUtils.getCurrentDurability(shieldStack);
        int maxDurability = shieldEntry.maxDurability;
        
        if (currentDurability <= 0) {
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(detailedZone);
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;
            
            FragmentationMod.LOGGER.info("[TaczGun] Shield durability is 0, returning full damage: " + result.finalDamage);
            return result;
        }
        
        float bulletLevel = bulletEntry != null ? bulletEntry.penetrationLevel : 1.0f;
        float armorDamage = getEffectiveArmorDamage(bulletEntry, debugInfo);
        
        GunConfig gunConfig = GunConfig.getInstanceFresh();
        float gunDamageMultiplier = gunConfig.getDamageMultiplier(debugInfo.gunId);
        float gunPenetrationMultiplier = gunConfig.getPenetrationMultiplier(debugInfo.gunId);
        
        float durabilityRatio = (float) currentDurability / (float) maxDurability;
        float penetrationThreshold = (float) shieldEntry.armorLevel * 10 * durabilityRatio;
        
        FragmentationMod.LOGGER.info("[TaczGun] Shield: armorLevel=" + shieldEntry.armorLevel + 
            ", durabilityRatio=" + durabilityRatio + ", penetrationThreshold=" + penetrationThreshold);
        
        boolean hasPlate = FracturepointHelper.hasPlate(shieldStack);
        
        if (hasPlate) {
            FragmentationMod.LOGGER.info("[TaczGun] Shield has plate, checking plate protection");
            net.minecraft.nbt.CompoundTag activePlateNbt = FracturepointHelper.getFrontPlate(shieldStack);
            float plateDurabilityCheck = activePlateNbt != null
                ? FracturepointHelper.getPlateCurrentDurability(activePlateNbt) : 0;
            if (activePlateNbt != null
                && plateDurabilityCheck > FracturepointHelper.DEPLETED_THRESHOLD) {
                // 插板等级解析链：plate config → 插板自身 armor config → 护盾等级
                int plateLevel = FracturepointHelper.getPlateLevel(activePlateNbt, shieldEntry.armorLevel);
                float plateDurability = plateDurabilityCheck;
                float plateMaxDurability = FracturepointHelper.getPlateMaxDurability(activePlateNbt);
                
                float plateDurabilityRatio = plateMaxDurability > 0 ? plateDurability / plateMaxDurability : 0;
                float actualPlateLevel = (float) plateLevel * plateDurabilityRatio;
                
                FragmentationMod.LOGGER.info("[TaczGun] Shield plate: level=" + plateLevel + 
                    ", durability=" + plateDurability + "/" + plateMaxDurability + 
                    ", actualLevel=" + actualPlateLevel);
                
                return calculateWithPlate(entity, shieldStack, null, shieldEntry, baseDamage, shieldSlot, detailedZone,
                    effectiveBulletLevel, bulletEntry, debugInfo, currentDurability, maxDurability,
                    actualPlateLevel, plateDurability, plateMaxDurability,
                    armorDamage, result, true, activePlateNbt);
            }
        }
        
        float penetrationChance = calculatePenetrationChance(effectiveBulletLevel, penetrationThreshold);
        boolean penetrated = false;
        if (penetrationChance > 0) {
            penetrated = random.nextFloat() < penetrationChance;
        }
        result.blockedByShield = !penetrated;
        
        float baseArmorDamageMultiplier = calculateArmorDamageMultiplier(penetrated);
        float effectiveShieldLevel = (float) shieldEntry.armorLevel * durabilityRatio;
        float finalArmorDamageMultiplier = baseArmorDamageMultiplier * gunPenetrationMultiplier
            * lowLevelArmorDamageFactor(effectiveBulletLevel, effectiveShieldLevel);

        int durabilityDamage = (int) Math.ceil(armorDamage * finalArmorDamageMultiplier);
        
        float finalDamage;
        if (penetrated) {
            float reducedDamage = baseDamage - ((float) shieldEntry.armorLevel * durabilityRatio * FIXED_DAMAGE_REDUCTION);
            finalDamage = Math.max(reducedDamage, baseDamage * 0.3f);
            result.shouldBypassReduction = true;
        } else {
            finalDamage = 0.0F;
            ArmorSoundManager.playRicochetSound(entity);
        }
        
        int newDurability = Math.max(0, currentDurability - durabilityDamage);
        
        if (newDurability <= 0 && durabilityDamage > 0) {
            ArmorSoundManager.playPenetrationSound(entity, shieldStack);
        }
        
        DurabilityUtils.setCurrentDurability(shieldStack, newDurability);
        entity.setItemSlot(shieldSlot, shieldStack);
        
        if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
            serverPlayer.onUpdateAbilities();
        }
        
        FragmentationMod.LOGGER.info("[TaczGun] Applied shield damage: amount=" + durabilityDamage + 
            ", durability " + currentDurability + " -> " + newDurability + "/" + maxDurability);
        
        result.armorHitSlot = null;
        result.armorDamageAmount = 0;
        result.hasArmorDamage = false;
        
        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = shieldSlot;
        slotInfo.isShield = true;
        slotInfo.shieldName = shieldEntry.getDisplayName();
        slotInfo.shieldLevel = shieldEntry.armorLevel;
        slotInfo.bulletLevel = effectiveBulletLevel;
        slotInfo.initialDurability = maxDurability;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = Math.max(0, baseDamage - finalDamage);
        slotInfo.durabilityDamage = durabilityDamage;
        slotInfo.penetrated = penetrated;
        slotInfo.damageMultiplier = penetrated ? (1.0f - penetrationThreshold * FIXED_DAMAGE_REDUCTION / baseDamage) : 0.01f;
        slotInfo.armorDamageMultiplier = finalArmorDamageMultiplier;
        slotInfo.penetrationChance = penetrationChance;
        slotInfo.remainingDurability = newDurability;
        slotInfo.plateInfo = hasPlate ? FracturepointHelper.getPlateInfo(shieldStack, shieldEntry.armorLevel) : "无";
        slotInfo.isFrontAttack = isFrontAttack;
        slotInfo.gunDamageMultiplier = gunDamageMultiplier;
        slotInfo.gunArmorDamageMultiplier = gunPenetrationMultiplier;
        debugInfo.slotInfos.add(slotInfo);
        
        result.finalDamage = finalDamage;
        
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(detailedZone);
        if (bodyZoneMultiplier != 1.0f) {
            result.finalDamage = finalDamage * bodyZoneMultiplier;
            FragmentationMod.LOGGER.info("[TaczGun] Applied body zone multiplier for shield: " + bodyZoneMultiplier + 
                ", finalDamage: " + finalDamage + " -> " + result.finalDamage);
        }
        
        return result;
    }
    
    private static GunDamageResult calculateArmDamage(LivingEntity entity, float baseDamage, EquipmentSlot hitSlot,
                                                       DetailedHitZone armZone, BulletConfig.BulletEntry bulletEntry,
                                                       float effectiveBulletLevel, DebugInfo debugInfo,
                                                       LivingEntity attacker, boolean isFrontAttack) {
        FragmentationMod.LOGGER.info("[TaczGun] calculateArmDamage: armZone=" + armZone.getDisplayName() + 
            ", baseDamage=" + baseDamage);
        
        ItemStack chestArmorStack = entity.getItemBySlot(EquipmentSlot.CHEST).copy();
        
        ArmorConfig.ArmorEntry chestArmorEntry = FragmentationMod.getArmorConfig().getArmorForItem(chestArmorStack);
        
        float armDamage = baseDamage * 0.35f;
        float remainingDamage = baseDamage * 0.65f;
        
        SlotDebugInfo armSlotInfo = new SlotDebugInfo();
        armSlotInfo.slot = hitSlot;
        armSlotInfo.penetrated = true;
        armSlotInfo.damageMultiplier = 0.35f;
        armSlotInfo.damageToSlot = armDamage;
        armSlotInfo.damageBlocked = 0;
        armSlotInfo.gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
        debugInfo.slotInfos.add(armSlotInfo);
        
        FragmentationMod.LOGGER.info("[TaczGun] Arm hit: applying " + armDamage + " damage to arm, remaining " + remainingDamage + " to chest");
        
        if (chestArmorStack.isEmpty() || chestArmorEntry == null) {
            // 胸甲为空或未配置：子弹穿透（伤害部位已在主流程随机）
            DetailedHitZone effectiveZone = armZone;
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
            float chestDamage = remainingDamage;
            GunDamageResult result = new GunDamageResult();
            result.originalDamage = baseDamage;
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.wasModified = true;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;
            
            SlotDebugInfo chestSlotInfo = new SlotDebugInfo();
            chestSlotInfo.slot = EquipmentSlot.CHEST;
            chestSlotInfo.penetrated = true;
            chestSlotInfo.damageMultiplier = 1.0f;
            chestSlotInfo.damageToSlot = chestDamage;
            chestSlotInfo.damageBlocked = 0;
            chestSlotInfo.gunDamageMultiplier = armSlotInfo.gunDamageMultiplier;
            debugInfo.slotInfos.add(chestSlotInfo);
            
            FragmentationMod.LOGGER.info("[TaczGun] Arm damage with empty chest armor: armDamage=" + armDamage + ", chestDamage=" + chestDamage + ", total=" + result.finalDamage);
            return result;
        }

        // protection.json 属性覆盖（可选）
        chestArmorEntry = effectiveArmorEntry(chestArmorStack, chestArmorEntry);

        // 防护部位检查：胸甲不防护被命中的手臂时，手臂 35% 与剩余 65% 全部穿透
        // （行为与"胸甲为空/未配置"一致）
        if (!FragmentationMod.getProtectionConfig().coversZone(chestArmorStack, armZone)) {
            FragmentationMod.LOGGER.info("[TaczGun] Chest armor does not protect arm zone "
                + armZone.configName() + ", full damage penetrates");
            DetailedHitZone effectiveZone = armZone;
            float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
            GunDamageResult result = new GunDamageResult();
            result.originalDamage = baseDamage;
            result.finalDamage = baseDamage * bodyZoneMultiplier;
            result.wasModified = true;
            result.shouldBypassReduction = true;
            debugInfo.overallDamageMultiplier = bodyZoneMultiplier;

            SlotDebugInfo chestSlotInfo = new SlotDebugInfo();
            chestSlotInfo.slot = EquipmentSlot.CHEST;
            chestSlotInfo.penetrated = true;
            chestSlotInfo.damageMultiplier = 1.0f;
            chestSlotInfo.damageToSlot = remainingDamage;
            chestSlotInfo.damageBlocked = 0;
            chestSlotInfo.gunDamageMultiplier = armSlotInfo.gunDamageMultiplier;
            debugInfo.slotInfos.add(chestSlotInfo);
            return result;
        }

        int configMax = chestArmorEntry.maxDurability;
        int currentDurability = DurabilityUtils.getCurrentDurability(chestArmorStack);
        int maxDurability = configMax;
        
        if (currentDurability > maxDurability) {
            currentDurability = maxDurability;
        }
        
        float armorDamage = getEffectiveArmorDamage(bulletEntry, debugInfo);
        float gunPenetrationMultiplier = GunConfig.getInstanceFresh().getPenetrationMultiplier(debugInfo.gunId);
        
        String armorMaterialName = chestArmorEntry.getMaterial();
        ArmorMaterialConfig.MaterialEntry materialEntry = FragmentationMod.getArmorMaterialConfig().getMaterial(armorMaterialName);
        float materialDamageAbsorption = materialEntry != null ? materialEntry.damageAbsorptionRatio : 1.0f;
        float materialArmorDamageRatio = materialEntry != null ? materialEntry.armorDamageRatio : 1.0f;
        
        if (currentDurability <= 0) {
            materialDamageAbsorption = 1.0f;
            materialArmorDamageRatio = 1.0f;
            FragmentationMod.LOGGER.info("[TaczGun] Chest armor durability is 0 in calculateArmDamage, material coefficients reset to 1.0");
        }
        
        float actualArmorLevel = (float) chestArmorEntry.armorLevel * ((float) currentDurability / (float) maxDurability);
        
        float durabilityRatio = (float) currentDurability / (float) maxDurability;
        float factor1 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f + materialDamageAbsorption);
        float factor2 = 1.0f - (float) Math.pow(1.0f - durabilityRatio, 1.0f / materialDamageAbsorption);
        float penetrationThreshold = (float) chestArmorEntry.armorLevel * 10 * factor1 * factor2;
        
        float penetrationChance = calculatePenetrationChance(effectiveBulletLevel, penetrationThreshold);
        
        boolean penetrated = false;
        if (penetrationChance > 0) {
            penetrated = random.nextFloat() < penetrationChance;
        }
        
        float baseArmorDamageMultiplier = calculateArmorDamageMultiplier(penetrated);
        float finalArmorDamageMultiplier = baseArmorDamageMultiplier * gunPenetrationMultiplier * materialArmorDamageRatio
            * lowLevelArmorDamageFactor(effectiveBulletLevel, actualArmorLevel);

        int durabilityDamage = (int) Math.ceil(armorDamage * finalArmorDamageMultiplier * 0.65f);
        
        float chestDamage;
        net.minecraft.world.phys.Vec3 armImpactPos = getArmorImpactPos(entity);
        if (penetrated) {
            float reducedDamage = remainingDamage - (actualArmorLevel * FIXED_DAMAGE_REDUCTION);
            chestDamage = reducedDamage / materialDamageAbsorption;
            // 穿透胸甲：ct 本周期一次
            ArmorSoundManager.playArmorPenetrateSound(entity, chestArmorStack, armImpactPos);
        } else {
            chestDamage = remainingDamage * 0.01f;
            if (chestDamage > 0) {
                // 胸甲挡下：wct + 白烟
                ArmorSoundManager.playArmorBlockSound(entity, armImpactPos);
            }
        }
        
        int newDurability = Math.max(0, currentDurability - durabilityDamage);
        
        if (newDurability <= 0 && durabilityDamage > 0) {
            // 胸甲耐久归零：ct 一次门控（标记落在即将写回的 chestArmorStack 副本上）
            ArmorSoundManager.playArmorPenetrateSound(entity, chestArmorStack, armImpactPos);
        }
        
        DurabilityUtils.setCurrentDurability(chestArmorStack, newDurability);
        entity.setItemSlot(EquipmentSlot.CHEST, chestArmorStack);
        
        if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
            serverPlayer.onUpdateAbilities();
        }
        
        SlotDebugInfo chestSlotInfo = new SlotDebugInfo();
        chestSlotInfo.slot = EquipmentSlot.CHEST;
        chestSlotInfo.armorEntry = chestArmorEntry;
        chestSlotInfo.bulletLevel = effectiveBulletLevel;
        chestSlotInfo.initialDurability = configMax;
        chestSlotInfo.damageToSlot = remainingDamage;
        chestSlotInfo.damageBlocked = Math.max(0, remainingDamage - chestDamage);
        chestSlotInfo.durabilityDamage = durabilityDamage;
        chestSlotInfo.penetrated = penetrated;
        chestSlotInfo.damageMultiplier = penetrated ? (1.0f - penetrationThreshold * FIXED_DAMAGE_REDUCTION / remainingDamage) : 0.01f;
        chestSlotInfo.armorDamageMultiplier = finalArmorDamageMultiplier;
        chestSlotInfo.penetrationChance = penetrationChance;
        chestSlotInfo.remainingDurability = newDurability;
        chestSlotInfo.plateInfo = FracturepointHelper.hasPlate(chestArmorStack) ? FracturepointHelper.getPlateInfo(chestArmorStack, chestArmorEntry.armorLevel) : "无";
        chestSlotInfo.isFrontAttack = isFrontAttack;
        chestSlotInfo.armorMaterial = armorMaterialName;
        chestSlotInfo.gunDamageMultiplier = armSlotInfo.gunDamageMultiplier;
        chestSlotInfo.gunArmorDamageMultiplier = gunPenetrationMultiplier;
        debugInfo.slotInfos.add(chestSlotInfo);
        
        GunDamageResult result = new GunDamageResult();
        result.originalDamage = baseDamage;
        result.finalDamage = armDamage + chestDamage;
        result.wasModified = true;
        result.shouldBypassReduction = penetrated;
        result.armorHitSlot = EquipmentSlot.CHEST;
        result.armorDamageAmount = durabilityDamage;
        result.hasArmorDamage = true;

        // 伤害部位已在主流程随机
        DetailedHitZone effectiveZone = armZone;
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(effectiveZone);
        if (bodyZoneMultiplier != 1.0f) {
            result.finalDamage = (armDamage + chestDamage) * bodyZoneMultiplier;
        }
        debugInfo.overallDamageMultiplier = result.finalDamage / baseDamage;
        
        FragmentationMod.LOGGER.info("[TaczGun] Arm damage result: armDamage=" + armDamage + 
            ", chestDamage=" + chestDamage + ", total=" + result.finalDamage + 
            ", penetrated=" + penetrated + ", armorDurability " + currentDurability + " -> " + newDurability);
        
        return result;
    }
    
    private static float getBodyZoneDamageMultiplier(DetailedHitZone detailedZone) {
        return detailedZone.getDamageMultiplier();
    }

    /**
     * 取本次子弹命中点（determineHitSlot 同 tick 写入实体持久数据的射线交点），
     * 用于 wct/ct 音效的 3D 定位与未穿透白烟；取不到时回退到实体胸口位置。
     */
    private static net.minecraft.world.phys.Vec3 getArmorImpactPos(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (data.contains(HitZoneResolver.IMPACT_X_KEY)) {
            long cachedTick = data.getLong(HitZoneResolver.IMPACT_TICK_KEY);
            long delta = entity.level().getGameTime() - cachedTick;
            if (delta >= 0L && delta <= 2L) {
                return new net.minecraft.world.phys.Vec3(
                    data.getDouble(HitZoneResolver.IMPACT_X_KEY),
                    data.getDouble(HitZoneResolver.IMPACT_Y_KEY),
                    data.getDouble(HitZoneResolver.IMPACT_Z_KEY));
            }
        }
        return new net.minecraft.world.phys.Vec3(entity.getX(), entity.getY(0.9D), entity.getZ());
    }

    /**
     * 随机重掷伤害部位（NPC / 非玩家生物攻击玩家，或玩家开启 AI 伤害模式时使用）。
     * 1% 头部；其余 99% 在胸部、腹部、左臂、右臂、左腿、右腿间均匀随机（各 16.5%）。
     * 在护甲计算之前调用，返回的部位将作为唯一权威落点进入完整伤害结算。
     */
    private static DetailedHitZone rollRandomHitZone() {
        if (random.nextFloat() < 0.01F) {
            return DetailedHitZone.HEAD;
        }
        DetailedHitZone[] pool = {
            DetailedHitZone.CHEST, DetailedHitZone.LEFT_ARM, DetailedHitZone.RIGHT_ARM,
            DetailedHitZone.LEFT_LEG, DetailedHitZone.RIGHT_LEG, DetailedHitZone.ABDOMEN
        };
        return pool[random.nextInt(pool.length)];
    }
    
    private static boolean isFrontAttack(LivingEntity hurtEntity, LivingEntity attacker) {
        if (hurtEntity == null || attacker == null) return true;
        
        double dx = attacker.getX() - hurtEntity.getX();
        double dz = attacker.getZ() - hurtEntity.getZ();
        double attackerYaw = Math.toDegrees(Math.atan2(-dx, dz));
        
        double entityYaw = hurtEntity.getYRot();
        
        double yawDiff = attackerYaw - entityYaw;
        while (yawDiff > 180) yawDiff -= 360;
        while (yawDiff < -180) yawDiff += 360;
        
        boolean isFront = Math.abs(yawDiff) <= 90;
        FragmentationMod.LOGGER.info("[TaczGun] Attack direction: attackerYaw=" + String.format("%.1f", attackerYaw) + 
            ", entityYaw=" + String.format("%.1f", entityYaw) + 
            ", yawDiff=" + String.format("%.1f", yawDiff) + 
            ", isFront=" + isFront);
        
        return isFront;
    }

    private static EquipmentSlot getActiveShieldSlot(LivingEntity entity) {
        if (entity == null) return null;

        if (entity.isUsingItem()) {
            EquipmentSlot activeSlot =
                entity.getUsedItemHand()
                    == net.minecraft.world.InteractionHand.MAIN_HAND
                    ? EquipmentSlot.MAINHAND
                    : EquipmentSlot.OFFHAND;
            if (FragmentationMod.getShieldConfig().getShieldForItem(
                entity.getItemBySlot(activeSlot)
            ) != null) {
                return activeSlot;
            }
        }

        if (FragmentationMod.getShieldConfig().getShieldForItem(
            entity.getOffhandItem()
        ) != null) {
            return EquipmentSlot.OFFHAND;
        }
        return FragmentationMod.getShieldConfig().getShieldForItem(
            entity.getMainHandItem()
        ) != null ? EquipmentSlot.MAINHAND : null;
    }

    /**
     * 计算子弹真实命中点：以子弹【上一位置→当前位置】的射线段与目标碰撞箱求交，
     * 返回射线进入点。高速子弹每 tick 步进可达数格，事件触发时 position() 未必是接触点。
     * 求交失败时退回到“碰撞箱上离子弹最近的点”，保证特效始终在目标身上。
     */
    private static Vec3 resolveBulletContact(Entity bullet, LivingEntity victim) {
        Vec3 end = bullet.position();
        Vec3 vel = bullet.getDeltaMovement();
        Vec3 start = end.subtract(vel);
        AABB box = victim.getBoundingBox().inflate(0.1D);

        Vec3 seg = end.subtract(start);
        double len = seg.length();
        if (len > 1.0E-6D) {
            Vec3 dir = seg.scale(1.0D / len);
            double t = rayBoxEntry(start, dir, box);
            if (t >= 0.0D && t <= len + 0.5D) {
                return start.add(dir.scale(t));
            }
        }
        // 兜底：碰撞箱表面离子弹当前位置最近的点（保留真实高度）
        double cx = Math.max(box.minX, Math.min(end.x, box.maxX));
        double cy = Math.max(box.minY, Math.min(end.y, box.maxY));
        double cz = Math.max(box.minZ, Math.min(end.z, box.maxZ));
        return new Vec3(cx, cy, cz);
    }

    /** 射线与 AABB 的进入距离（不相交返回 -1） */
    private static double rayBoxEntry(Vec3 o, Vec3 d, AABB box) {
        double tMin = 0.0D;
        double tMax = Double.MAX_VALUE;
        double[][] axes = {
            {o.x, d.x, box.minX, box.maxX},
            {o.y, d.y, box.minY, box.maxY},
            {o.z, d.z, box.minZ, box.maxZ}
        };
        for (double[] a : axes) {
            if (Math.abs(a[1]) < 1.0E-9D) {
                if (a[0] < a[2] || a[0] > a[3]) return -1.0D;
            } else {
                double t1 = (a[2] - a[0]) / a[1];
                double t2 = (a[3] - a[0]) / a[1];
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return -1.0D;
            }
        }
        return tMin;
    }

    /**
     * 解析 TACZ 子弹实体对应的弹头质量（kg）。
     * 优先子弹物品配置，其次弹药 ID 配置，都取不到时回退 8g（典型步枪弹）。
     */
    private static double resolveFieldBulletMass(Object bulletEntity) {
        try {
            BulletConfig config = FragmentationMod.getBulletConfig();
            if (config != null && getBulletItemMethod != null) {
                Object itemObj = getBulletItemMethod.invoke(bulletEntity);
                if (itemObj instanceof ItemStack bulletStack && !bulletStack.isEmpty()) {
                    BulletConfig.BulletEntry entry = config.getBulletForItem(bulletStack);
                    if (entry != null && entry.bulletMass > 0.0D) {
                        return entry.bulletMass;
                    }
                }
            }
            if (config != null && getAmmoIdMethod != null) {
                Object ammoIdRl = getAmmoIdMethod.invoke(bulletEntity);
                if (ammoIdRl != null) {
                    String ammoIdStr = (String) resourceLocationToStringMethod.invoke(ammoIdRl);
                    BulletConfig.BulletEntry entry = config.getBulletByAmmoId(ammoIdStr);
                    if (entry != null && entry.bulletMass > 0.0D) {
                        return entry.bulletMass;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.008D;
    }
    
    private static boolean isShieldFacingAttack(
        LivingEntity entity,
        Entity projectile,
        LivingEntity attacker
    ) {
        if (getActiveShieldSlot(entity) == null) return false;
        Vec3 targetCenter = entity.getBoundingBox().getCenter();
        Vec3 attackDirection = Vec3.ZERO;
        if (projectile != null) {
            Vec3 movement = projectile.getDeltaMovement();
            attackDirection = new Vec3(
                -movement.x,
                0.0D,
                -movement.z
            );
        }
        if (attackDirection.lengthSqr() <= 1.0E-8D && projectile != null) {
            attackDirection = projectile.position().subtract(targetCenter);
        }
        attackDirection = new Vec3(
            attackDirection.x,
            0.0D,
            attackDirection.z
        );
        if (attackDirection.lengthSqr() <= 1.0E-8D && attacker != null) {
            attackDirection = new Vec3(
                attacker.getX() - entity.getX(),
                0.0D,
                attacker.getZ() - entity.getZ()
            );
        }
        if (attackDirection.lengthSqr() <= 1.0E-8D) return true;

        Vec3 look = entity.getLookAngle();
        Vec3 shieldDirection = new Vec3(look.x, 0.0D, look.z);
        if (shieldDirection.lengthSqr() <= 1.0E-8D) return true;

        double directionDot = shieldDirection.normalize().dot(
            attackDirection.normalize()
        );
        boolean isFacing = directionDot >= 0.0D;

        FragmentationMod.LOGGER.info(
            "[TaczGun] Shield direction check: dot="
                + String.format("%.3f", directionDot)
                + ", halfAngle=90.0, projectile="
                + (projectile != null)
                + ", isFacing=" + isFacing
        );
        return isFacing;
    }
    
    private static GunDamageResult calculateWithPlate(LivingEntity entity, ItemStack armorStack,
                                                       ArmorConfig.ArmorEntry armorEntry,
                                                       ShieldConfig.ShieldEntry shieldEntry, float baseDamage,
                                                       EquipmentSlot hitSlot, DetailedHitZone detailedZone, float bulletLevel,
                                                       BulletConfig.BulletEntry bulletEntry, DebugInfo debugInfo,
                                                       int currentArmorDurability, int configMax,
                                                       float actualPlateLevel, float plateDurability, float plateMaxDurability,
                                                       float armorDamage, GunDamageResult result,
                                                       boolean isFrontPlate, net.minecraft.nbt.CompoundTag plateNbt) {
        boolean shieldSurface = shieldEntry != null;
        if (armorEntry == null && !shieldSurface) {
            throw new IllegalArgumentException(
                "Plate calculation requires armor or shield configuration"
            );
        }
        
        float gunDamageMultiplier = GunConfig.getInstanceFresh().getDamageMultiplier(debugInfo.gunId);
        float gunPenetrationMultiplier = GunConfig.getInstanceFresh().getPenetrationMultiplier(debugInfo.gunId);
        
        String plateMaterial = FracturepointHelper.getPlateMaterial(plateNbt);
        ArmorMaterialConfig.MaterialEntry plateMaterialEntry = FragmentationMod.getArmorMaterialConfig().getMaterial(plateMaterial);
        float plateDamageAbsorption = plateMaterialEntry != null ? plateMaterialEntry.damageAbsorptionRatio : 1.0f;
        float plateArmorDamageRatio = plateMaterialEntry != null ? plateMaterialEntry.armorDamageRatio : 1.0f;
        
        if (plateDurability <= FracturepointHelper.DEPLETED_THRESHOLD) {
            plateDamageAbsorption = 1.0f;
            plateArmorDamageRatio = 1.0f;
            FragmentationMod.LOGGER.info("[TaczGun] Plate durability depleted (<= " + FracturepointHelper.DEPLETED_THRESHOLD + "), material coefficients reset to 1.0");
        }
        
        int armorLevel = shieldSurface
            ? shieldEntry.armorLevel
            : armorEntry.armorLevel;
        String armorMaterialName = shieldSurface
            ? shieldEntry.getMaterial()
            : armorEntry.getMaterial();
        ArmorMaterialConfig.MaterialEntry armorMaterialEntry = FragmentationMod.getArmorMaterialConfig().getMaterial(armorMaterialName);
        float armorDamageAbsorption = armorMaterialEntry != null ? armorMaterialEntry.damageAbsorptionRatio : 1.0f;
        float armorArmorDamageRatio = armorMaterialEntry != null ? armorMaterialEntry.armorDamageRatio : 1.0f;
        
        if (currentArmorDurability <= 0) {
            armorDamageAbsorption = 1.0f;
            armorArmorDamageRatio = 1.0f;
            FragmentationMod.LOGGER.info("[TaczGun] Armor durability is 0 in calculateWithPlate, armor material coefficients reset to 1.0");
        }

        float plateDurabilityRatio = plateDurability / plateMaxDurability;
        float plateFactor1 = 1.0f - (float) Math.pow(1.0f - plateDurabilityRatio, 1.0f + plateDamageAbsorption);
        float plateFactor2 = 1.0f - (float) Math.pow(1.0f - plateDurabilityRatio, 1.0f / plateDamageAbsorption);
        float platePenetrationThreshold = actualPlateLevel * 10 * plateFactor1 * plateFactor2;
        
        float platePenChance = calculatePenetrationChance(bulletLevel, platePenetrationThreshold);

        boolean platePenetrated = false;
        if (platePenChance > 0) {
            platePenetrated = random.nextFloat() < platePenChance;
        }

        float basePlateArmorDamageMultiplier = calculateArmorDamageMultiplier(platePenetrated);
        float finalPlateArmorDamageMultiplier = basePlateArmorDamageMultiplier * gunPenetrationMultiplier * plateArmorDamageRatio
            * lowLevelArmorDamageFactor(bulletLevel, actualPlateLevel);

        int plateDurabilityDamage = (int) Math.ceil(armorDamage * finalPlateArmorDamageMultiplier);
        float newPlateDurability = Math.max(0, plateDurability - plateDurabilityDamage);
        
        if (isFrontPlate) {
            FracturepointHelper.damageFrontPlate(armorStack, plateDurabilityDamage);
        } else {
            FracturepointHelper.damageBackPlate(armorStack, plateDurabilityDamage);
        }
        
        FragmentationMod.LOGGER.info("[TaczGun] Plate hit: " + (isFrontPlate ? "Front" : "Back") + 
            " plate, actualPlateLevel=" + actualPlateLevel + 
            ", bulletLevel=" + bulletLevel + ", platePenChance=" + platePenChance + 
            ", platePenetrated=" + platePenetrated + ", plateDurabilityDamage=" + plateDurabilityDamage + 
            ", newPlateDurability=" + newPlateDurability);
        
        float finalDamage;
        boolean armorDamaged = false;
        int armorDurabilityDamage = 0;
        float armorPenChance = 0;
        boolean armorPenetrated = false;
        float damageMultiplier = 0;
        float armorDamageMultiplier = 0;
        
        if (platePenetrated) {
            float reducedBulletLevel = bulletLevel - 5.0f;
            
            FragmentationMod.LOGGER.info("[TaczGun] Plate penetrated, bullet level reduced from " + bulletLevel + " to " + reducedBulletLevel);
            
            if (currentArmorDurability <= 0) {
                finalDamage = baseDamage;
                result.shouldBypassReduction = true;
                armorPenetrated = true;
                // 插板已穿透且护甲耐久为 0：伤害直达＝穿透，ct 本周期一次（盾牌除外）
                if (!shieldSurface) {
                    ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, getArmorImpactPos(entity));
                }
                FragmentationMod.LOGGER.info("[TaczGun] Plate penetrated and armor durability is 0, full damage applied");
            } else {
                float armorDurabilityRatio = (float) currentArmorDurability / (float) configMax;
                float armorFactor1 = 1.0f - (float) Math.pow(1.0f - armorDurabilityRatio, 1.0f + armorDamageAbsorption);
                float armorFactor2 = 1.0f - (float) Math.pow(1.0f - armorDurabilityRatio, 1.0f / armorDamageAbsorption);
                float armorPenetrationThreshold = (float) armorLevel * 10 * armorFactor1 * armorFactor2;
                float actualArmorLevel = (float) armorLevel * armorDurabilityRatio;
                
                armorPenChance = calculatePenetrationChance(reducedBulletLevel, armorPenetrationThreshold);

                if (armorPenChance > 0) {
                    armorPenetrated = random.nextFloat() < armorPenChance;
                }

                damageMultiplier = calculateDamageMultiplier(reducedBulletLevel, actualArmorLevel, armorPenetrated);
                float baseArmorDamageMultiplierVal = calculateArmorDamageMultiplier(armorPenetrated);
                armorDamageMultiplier = baseArmorDamageMultiplierVal * gunPenetrationMultiplier * armorArmorDamageRatio
                    * lowLevelArmorDamageFactor(reducedBulletLevel, actualArmorLevel);

                armorDurabilityDamage = (int) Math.ceil(armorDamage * armorDamageMultiplier);
                if (!shieldSurface) {
                    result.armorHitSlot = hitSlot;
                    result.armorDamageAmount = armorDurabilityDamage;
                    result.hasArmorDamage = true;
                }
                armorDamaged = true;

                if (armorPenetrated) {
                    float reducedDamage = baseDamage - (actualArmorLevel * FIXED_DAMAGE_REDUCTION);
                    finalDamage = reducedDamage / armorDamageAbsorption;
                    result.shouldBypassReduction = true;
                    // 穿透防弹衣：ct 本周期一次（盾牌路径保持旧穿透音）
                    if (!shieldSurface) {
                        ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, getArmorImpactPos(entity));
                    }
                    FragmentationMod.LOGGER.info("[TaczGun] Armor also penetrated, finalDamage=" + finalDamage);
                } else {
                    finalDamage = shieldSurface ? 0.0F : baseDamage * 0.01f;
                    if (shieldSurface || finalDamage > 0) {
                        if (shieldSurface) {
                            ArmorSoundManager.playRicochetSound(entity);
                        } else {
                            // 防弹衣挡下：wct + 白烟
                            ArmorSoundManager.playArmorBlockSound(entity, getArmorImpactPos(entity));
                        }
                    }
                    FragmentationMod.LOGGER.info("[TaczGun] Armor blocked remaining damage, bluntDamage=" + finalDamage);
                }
            }
            
            int newArmorDurability = Math.max(0, currentArmorDurability - armorDurabilityDamage);
            if (newArmorDurability <= 0 && armorDurabilityDamage > 0) {
                if (shieldSurface) {
                    ArmorSoundManager.playPenetrationSound(entity, armorStack);
                } else {
                    // 防弹衣耐久归零：ct 一次门控
                    ArmorSoundManager.playArmorPenetrateSound(entity, armorStack, getArmorImpactPos(entity));
                }
            }
            
            DurabilityUtils.setCurrentDurability(armorStack, newArmorDurability);
            
            entity.setItemSlot(hitSlot, armorStack);
            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
                serverPlayer.onUpdateAbilities();
            }
            
            FragmentationMod.LOGGER.info("[TaczGun] Plate penetrated, armor also hit: armorPenChance=" + armorPenChance + 
                ", armorPenetrated=" + armorPenetrated + ", armorDurabilityDamage=" + armorDurabilityDamage +
                ", newArmorDurability=" + newArmorDurability);
        } else {
            finalDamage = shieldSurface ? 0.0F : baseDamage * 0.01f;
            if (shieldSurface || finalDamage > 0) {
                if (shieldSurface) {
                    ArmorSoundManager.playRicochetSound(entity);
                } else {
                    // 插板挡下子弹（未穿透防弹衣）：wct + 白烟
                    ArmorSoundManager.playArmorBlockSound(entity, getArmorImpactPos(entity));
                }
            }
            FragmentationMod.LOGGER.info("[TaczGun] Plate blocked, armor safe");
        }
        
        if (!armorDamaged) {
            entity.setItemSlot(hitSlot, armorStack);
            if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
                serverPlayer.onUpdateAbilities();
            }
        }
        
        SlotDebugInfo slotInfo = new SlotDebugInfo();
        slotInfo.slot = hitSlot;
        slotInfo.armorEntry = armorEntry;
        slotInfo.isShield = shieldSurface;
        if (shieldSurface) {
            slotInfo.shieldName = shieldEntry.getDisplayName();
            slotInfo.shieldLevel = shieldEntry.armorLevel;
        }
        slotInfo.bulletLevel = bulletLevel;
        slotInfo.initialDurability = configMax;
        slotInfo.damageToSlot = baseDamage;
        slotInfo.damageBlocked = Math.max(0, baseDamage - finalDamage);
        slotInfo.durabilityDamage = armorDurabilityDamage;
        slotInfo.penetrated = armorPenetrated;
        slotInfo.damageMultiplier = damageMultiplier;
        slotInfo.armorDamageMultiplier = armorDamageMultiplier;
        slotInfo.penetrationChance = platePenChance;
        slotInfo.remainingDurability = armorDamaged ? Math.max(0, currentArmorDurability - armorDurabilityDamage) : currentArmorDurability;
        slotInfo.plateInfo = FracturepointHelper.getPlateInfo(armorStack, armorLevel);
        slotInfo.usingPlate = true;
        slotInfo.isFrontPlate = isFrontPlate;
        slotInfo.plateLevel = (int) actualPlateLevel;
        slotInfo.platePenetrated = platePenetrated;
        slotInfo.platePenetrationChance = platePenChance;
        slotInfo.plateDurabilityDamage = plateDurabilityDamage;
        slotInfo.plateRemainingDurability = newPlateDurability;
        slotInfo.isFrontAttack = debugInfo.isFrontAttack;
        slotInfo.armorMaterial = armorMaterialName;
        slotInfo.gunDamageMultiplier = gunDamageMultiplier;
        slotInfo.gunArmorDamageMultiplier = gunPenetrationMultiplier;
        debugInfo.slotInfos.add(slotInfo);
        
        result.finalDamage = finalDamage;
        if (shieldSurface && (!platePenetrated || !armorPenetrated)) {
            result.blockedByShield = true;
            result.shouldBypassReduction = false;
            result.finalDamage = 0.0F;
        }
        
        float bodyZoneMultiplier = getBodyZoneDamageMultiplier(detailedZone);
        if (bodyZoneMultiplier != 1.0f) {
            result.finalDamage = finalDamage * bodyZoneMultiplier;
            FragmentationMod.LOGGER.info("[TaczGun] Applied body zone multiplier in calculateWithPlate: " + hitSlot + " -> " + bodyZoneMultiplier);
        }
        
        return result;
    }
    
    private static float calculatePenetrationChance(float bulletLevel, float penetrationThreshold) {
        return (float) BallisticsMath.firstHitPenetrationChance(
            bulletLevel,
            penetrationThreshold
        );
    }

    private static final float FIXED_DAMAGE_REDUCTION = 0.04f;

    private static float calculateDamageMultiplier(float bulletLevel, float armorLevel, boolean penetrated) {
        if (penetrated) {
            float damageMultiplier = 1.0f - (armorLevel * FIXED_DAMAGE_REDUCTION);
            return Math.max(0.4f, Math.min(0.6f, damageMultiplier));
        } else {
            return 0.01f;
        }
    }

    private static float getEffectiveArmorDamage(
        BulletConfig.BulletEntry bulletEntry,
        DebugInfo debugInfo
    ) {
        double baseArmorDamage = bulletEntry != null ? bulletEntry.armorDamage : 1.0D;
        double retention = debugInfo != null ? debugInfo.energyRetention : 0.0D;
        double projectileMultiplier = debugInfo != null
            ? debugInfo.projectileDamageMultiplier
            : 1.0D;
        return (float) (
            BallisticsMath.retainedArmorDamage(baseArmorDamage, retention)
                * projectileMultiplier
        );
    }

    private static float calculateArmorDamageMultiplier(boolean penetrated) {
        if (penetrated) {
            return 0.5f + random.nextFloat() * 0.5f;
        } else {
            return 1.1f + random.nextFloat() * 0.9f;
        }
    }

    /**
     * 低级子弹对高级护甲的耐久伤害衰减系数。
     * 子弹等级低于护甲等级时，子弹越弱、对护甲的磨损越小（参考塔科夫：
     * 低穿甲弹很难磨坏高级甲）；等级相当或更高时不衰减，也不额外放大。
     *
     * @param bulletLevel 子弹有效穿甲等级（含速度/能量衰减）
     * @param armorLevel  护甲当前有效等级（含耐久折减）
     * @return 0.25 ~ 1.0：bulletLevel=0 时仍保留 0.25 最低磨损，等级相等时为 1.0
     */
    private static float lowLevelArmorDamageFactor(float bulletLevel, float armorLevel) {
        if (armorLevel <= 0.0F || bulletLevel >= armorLevel) return 1.0F;
        double ratio = Math.max(0.0D, bulletLevel / armorLevel);
        return (float) Math.max(0.25D, 0.25D + 0.75D * ratio);
    }
    
    public static class GunDamageResult {
        public float originalDamage;
        public float finalDamage;
        public boolean wasModified;
        public EquipmentSlot armorHitSlot;
        public int armorDamageAmount;
        public boolean hasArmorDamage;
        public boolean shouldBypassReduction;
        public boolean blockedByShield;
    }
    
    public static class DebugInfo {
        public String gunId;
        public String ammoId;
        public String hurtEntityName;
        public BulletConfig.BulletEntry bulletEntry;
        public boolean isHeadShot;
        public float headshotMultiplier;
        public float originalDamage;
        public float retainedBaseDamage;
        public float finalDamage;
        public double currentBulletSpeed;
        public double maximumBulletSpeed;
        public double speedRatio;
        public double energyRetention;
        public double projectileDamageMultiplier = 1.0D;
        public double projectilePenetrationMultiplier = 1.0D;
        public float effectivePenetration;
        public float effectiveArmorDamage;
        public EquipmentSlot hitSlot;
        public DetailedHitZone detailedZone;
        public float overallDamageMultiplier = 1.0f;
        public LivingEntity attacker;
        public Entity projectile;
        public boolean isFrontAttack;
        public List<SlotDebugInfo> slotInfos = new ArrayList<>();
    }
    
    public static class SlotDebugInfo {
        public EquipmentSlot slot;
        public ArmorConfig.ArmorEntry armorEntry;
        public float bulletLevel;
        public int initialDurability;
        public float damageToSlot;
        public float damageBlocked;
        public int durabilityDamage;
        public boolean penetrated;
        public float damageMultiplier;
        public float armorDamageMultiplier;
        public float penetrationChance;
        public int remainingDurability;
        public String plateInfo;
        public boolean armorNotConfigured;
        public String armorName;
        public boolean usingPlate;
        public boolean isFrontPlate;
        public int plateLevel;
        public boolean platePenetrated;
        public float platePenetrationChance;
        public int plateDurabilityDamage;
        public float plateRemainingDurability;
        public boolean isFrontAttack;
        public String armorMaterial;
        public float gunDamageMultiplier;
        public float gunArmorDamageMultiplier;
        public boolean isShield;
        public String shieldName;
        public int shieldLevel;
    }

    private record DefensiveSurface(
        String name,
        double ricochetCoefficient,
        Vec3 normal
    ) {
    }
    
    private static void sendDebugMessage(Player player, DebugInfo debugInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("§6[伤害调试]§r\n");
        sb.append("枪械: ").append(debugInfo.gunId != null ? debugInfo.gunId : "未知").append("\n");
        sb.append("子弹: ").append(debugInfo.ammoId != null ? debugInfo.ammoId : "未知").append("\n");
        sb.append("目标: ").append(debugInfo.hurtEntityName).append("\n");
        sb.append("命中部位: ").append(debugInfo.detailedZone != null ? debugInfo.detailedZone.getDisplayName() : getSlotName(debugInfo.hitSlot)).append("\n");
        sb.append("攻击方向: ").append(debugInfo.isFrontAttack ? "正面" : "背面").append("\n");
        sb.append("爆头: ").append(debugInfo.isHeadShot ? "是" : "否").append("\n");
        sb.append("原始伤害: ").append(String.format("%.1f", debugInfo.originalDamage)).append("\n");
        sb.append("当前/最大弹速: ")
            .append(String.format("%.1f/%.1f m/s", debugInfo.currentBulletSpeed, debugInfo.maximumBulletSpeed))
            .append("\n");
        sb.append("速度比: ").append(String.format("%.1f%%", debugInfo.speedRatio * 100.0D)).append("\n");
        sb.append("能效保留率: ").append(String.format("%.1f%%", debugInfo.energyRetention * 100.0D)).append("\n");
        sb.append("能效后基础伤害: ").append(String.format("%.2f", debugInfo.retainedBaseDamage)).append("\n");
        
        if (debugInfo.bulletEntry != null) {
            sb.append("子弹穿深: ").append(debugInfo.bulletEntry.penetrationLevel).append("\n");
            sb.append("子弹护甲伤害: ").append(debugInfo.bulletEntry.armorDamage).append("\n");
            sb.append("能效后穿深: ").append(String.format("%.2f", debugInfo.effectivePenetration)).append("\n");
            sb.append("能效后护甲伤害: ").append(String.format("%.2f", debugInfo.effectiveArmorDamage)).append("\n");
        } else {
            sb.append("子弹: 未配置\n");
        }
        
        for (SlotDebugInfo slotInfo : debugInfo.slotInfos) {
            sb.append("\n§7--- ").append(getSlotName(slotInfo.slot)).append(" ---\n§r");
            if (slotInfo.isShield) {
                sb.append("§a[护盾]§r\n");
                sb.append("护盾名称: ").append(slotInfo.shieldName != null ? slotInfo.shieldName : "未知").append("\n");
                sb.append("护盾等级: ").append(slotInfo.shieldLevel).append("\n");
                sb.append("初始耐久: ").append(slotInfo.initialDurability).append("\n");
                sb.append("耐久伤害: ").append(slotInfo.durabilityDamage).append("\n");
                sb.append("剩余耐久: ").append(slotInfo.remainingDurability).append("\n");
                sb.append("有效穿深: ").append(slotInfo.bulletLevel).append("\n");
                sb.append("穿透概率: ").append(String.format("%.2f%%", slotInfo.penetrationChance * 100)).append("\n");
                sb.append("穿透: ").append(slotInfo.penetrated ? "是" : "否").append("\n");
                sb.append("伤害倍率: ").append(slotInfo.damageMultiplier > 0 ? String.format("%.0f%%", slotInfo.damageMultiplier * 100) : "0%").append("\n");
                sb.append("护甲伤害倍率: ").append(slotInfo.armorDamageMultiplier > 0 ? String.format("%.0f%%", slotInfo.armorDamageMultiplier * 100) : "0%").append("\n");
                sb.append("枪械伤害倍率: ").append(String.format("%.0f%%", slotInfo.gunDamageMultiplier * 100)).append("\n");
                sb.append("枪械穿透倍率: ").append(String.format("%.0f%%", slotInfo.gunArmorDamageMultiplier * 100)).append("\n");
            } else if (slotInfo.armorNotConfigured) {
                sb.append("护甲: ").append(slotInfo.armorName).append(" (未配置)\n");
                sb.append("穿透: 是\n");
                sb.append("伤害倍率: 100%\n");
            } else if (slotInfo.armorEntry != null) {
                sb.append("护甲等级: ").append(slotInfo.armorEntry.armorLevel).append("\n");
                sb.append("护甲材料: ").append(slotInfo.armorMaterial != null ? slotInfo.armorMaterial : "steel").append("\n");
                sb.append("初始耐久: ").append(slotInfo.initialDurability).append("\n");
                sb.append("护甲伤害: ").append(slotInfo.durabilityDamage).append("\n");
                sb.append("剩余耐久: ").append(slotInfo.remainingDurability).append("\n");
                
                if (slotInfo.usingPlate) {
                    sb.append("\n§a[防弹插板]§r\n");
                    sb.append("插板等级: ").append(slotInfo.plateLevel).append("\n");
                    sb.append("插板穿透概率: ").append(String.format("%.2f%%", slotInfo.platePenetrationChance * 100)).append("\n");
                    sb.append("插板穿透: ").append(slotInfo.platePenetrated ? "是" : "否").append("\n");
                    sb.append("插板伤害: ").append(slotInfo.plateDurabilityDamage).append("\n");
                    sb.append("插板剩余耐久: ").append(String.format("%.1f", slotInfo.plateRemainingDurability)).append("\n");
                    sb.append("插板信息: ").append(slotInfo.plateInfo).append("\n");
                } else if (slotInfo.plateInfo != null && !slotInfo.plateInfo.equals("无")) {
                    sb.append("插板: ").append(slotInfo.plateInfo).append(" (已损坏)\n");
                }
                
                sb.append("有效穿深: ").append(slotInfo.bulletLevel).append("\n");
                sb.append("穿透概率: ").append(String.format("%.2f%%", slotInfo.penetrationChance * 100)).append("\n");
                sb.append("穿透: ").append(slotInfo.penetrated ? "是" : "否").append("\n");
                sb.append("伤害倍率: ").append(slotInfo.damageMultiplier > 0 ? String.format("%.0f%%", slotInfo.damageMultiplier * 100) : "0%").append("\n");
                sb.append("护甲伤害倍率: ").append(slotInfo.armorDamageMultiplier > 0 ? String.format("%.0f%%", slotInfo.armorDamageMultiplier * 100) : "0%").append("\n");
                sb.append("枪械伤害倍率: ").append(String.format("%.0f%%", slotInfo.gunDamageMultiplier * 100)).append("\n");
                sb.append("枪械穿透倍率: ").append(String.format("%.0f%%", slotInfo.gunArmorDamageMultiplier * 100)).append("\n");
            } else {
                sb.append("无护甲\n");
            }
        }
        
        sb.append("\n最终伤害: ").append(String.format("%.1f", debugInfo.finalDamage));
        player.sendSystemMessage(Component.literal(sb.toString()));
    }
    
    private static String getSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头部";
            case CHEST -> "胸部";
            case LEGS -> "腿部";
            case FEET -> "脚部";
            case MAINHAND -> "主手";
            case OFFHAND -> "副手";
            default -> slot.name();
        };
    }
    
}
