package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.*;
import com.fragmentation.combat.penetration.PenetrationResolveContext;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.data.PlateData;
import com.fragmentation.nbt.PlateNbt;
import com.fragmentation.util.DurabilityUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ArmorPlateDamageHandler {
    private static final String PROTECTED_PROJECTILE_UUID_KEY = "fragmentation_protected_projectile_uuid";
    private static final String PROTECTED_PROJECTILE_TICK_KEY = "fragmentation_protected_projectile_tick";
    private static final String IGNORE_ARMOR_REDUCTION_KEY = "fragmentation_ignore_armor_reduction";
    private static final String INTENDED_DAMAGE_KEY = "fragmentation_intended_damage";
    private static final long DUPLICATE_PROJECTILE_TICK_WINDOW = 8L;
    private static final float MIN_PENETRATION_DAMAGE = 1.0F;
    private static final ArmorPlateCombatHandler combatHandler = new ArmorPlateCombatHandler();
    private static final BodyZoneDamageCalculator zoneCalculator = new BodyZoneDamageCalculator();

    private ArmorPlateDamageHandler() {
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        try {
            if (!(event.getRayTraceResult() instanceof EntityHitResult entityHitResult)) {
                return;
            }
            if (!(entityHitResult.getEntity() instanceof LivingEntity livingEntity)) {
                return;
            }
            Projectile projectile = event.getProjectile();

            CompoundTag data = livingEntity.getPersistentData();
            data.putString(HitZoneResolver.IMPACT_PROJECTILE_UUID_KEY, projectile.getUUID().toString());
            data.putLong(HitZoneResolver.IMPACT_TICK_KEY, livingEntity.level().getGameTime());
            data.putDouble(HitZoneResolver.IMPACT_X_KEY, entityHitResult.getLocation().x);
            data.putDouble(HitZoneResolver.IMPACT_Y_KEY, entityHitResult.getLocation().y);
            data.putDouble(HitZoneResolver.IMPACT_Z_KEY, entityHitResult.getLocation().z);
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ArmorPlateDamageHandler] Error processing projectile impact", e);
        }
    }

    /**
     * 每 tick 纠正新装入插板的耐久：Fracturepoint 装入时按其默认耐久写入
     * （如钢III=90），需同步为本模组配置的最大耐久（如155）。
     */
    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        try {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
            Player player = event.player;
            if (player.level().isClientSide) return;

            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (!chest.isEmpty()) {
                com.fragmentation.compat.FracturepointHelper.syncInsertedPlates(chest);
            }
            // 手持护盾若也装载了插板，一并纠正（去重）
            ItemStack mainHand = player.getMainHandItem();
            ItemStack offHand = player.getOffhandItem();
            if (!mainHand.isEmpty() && mainHand != chest) {
                com.fragmentation.compat.FracturepointHelper.syncInsertedPlates(mainHand);
            }
            if (!offHand.isEmpty() && offHand != chest && offHand != mainHand) {
                com.fragmentation.compat.FracturepointHelper.syncInsertedPlates(offHand);
            }
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ArmorPlateDamageHandler] Error syncing inserted plates", e);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide) {
                return;
            }

            if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
                float intendedDamage = entity.getPersistentData().getFloat(INTENDED_DAMAGE_KEY);
                long currentTick = entity.level().getGameTime();
                long settledTick = entity.getPersistentData().getLong(
                    TaczGunEventHandler.GUN_SETTLED_TICK_KEY);

                // 同 tick 存在 GUN_SETTLED 标记：TACZ 的 EntityHurtByGun Pre 事件刚完成
                // 完整结算，本次 hurt 由 TACZ 自己发起（可能按 armorIgnore 拆成两次），
                // 按 intended 金额放行即可。
                if (settledTick != 0L && settledTick == currentTick) {
                    event.setAmount(intendedDamage);

                    String hitZoneName = entity.getPersistentData().getString("fragmentation_hit_zone");
                    if (hitZoneName != null && !hitZoneName.isEmpty()) {
                        try {
                            DetailedHitZone detailedZone = DetailedHitZone.valueOf(hitZoneName);
                            applyArmorDurabilityFromZone(entity, detailedZone, intendedDamage);
                            FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Applied armor durability from zone: " + detailedZone + ", damage=" + intendedDamage);
                        } catch (Exception e) {
                            FragmentationMod.LOGGER.warn("[ArmorPlateDamageHandler] Failed to apply armor durability: " + e.getMessage());
                        }
                        entity.getPersistentData().remove("fragmentation_hit_zone");
                    }

                    FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Bypass flag set, applying armor durability only. intendedDamage=" + intendedDamage);
                    return;
                }

                // 走到这里说明 flag 是上一枪的残留：外部健康系统（如 tarkovmod）
                // 在 LivingHurtEvent 后期取消了伤害，actuallyHurt 未执行，
                // LivingEntityMixin 没机会清 flag。本次 hurt 属于新的一枪，
                // 清掉残留标记后继续向下走完整结算，保证伤害计算与战报记录正确。
                entity.getPersistentData().remove(IGNORE_ARMOR_REDUCTION_KEY);
                entity.getPersistentData().remove(INTENDED_DAMAGE_KEY);
                entity.getPersistentData().remove("fragmentation_hit_zone");
                FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Removed stale bypass flag before new hurt (settledTick="
                    + settledTick + ", currentTick=" + currentTick + ")");
            }

            // 本 tick 的枪械伤害已由 EntityHurtByGun Pre 事件完整结算
            // （最终部位、减伤、护甲耐久都在 TaczGunEventHandler 中处理完毕）。
            // 必须跳过下面的二次部位解析——它按子弹真实命中点重新判定，
            // 会覆盖 NPC 攻击时 Pre 事件里随机重掷的部位（锁头 AI 永远被解析成头部）。
            // TACZ 若按 armorIgnore 把伤害拆成 AP/non-AP 两次 hurt，此处保持各自金额
            // 不变直接放行，两次之和即 Pre 事件结算的最终伤害。
            long settledTick = entity.getPersistentData().getLong(
                TaczGunEventHandler.GUN_SETTLED_TICK_KEY);
            if (settledTick != 0L && settledTick == entity.level().getGameTime()) {
                return;
            }

            DamageSource damageSource = event.getSource();

            if (isProtectedDuplicateProjectileHit(entity, damageSource)) {
                event.setAmount(0.0F);
                event.setCanceled(true);
                return;
            }

            float incomingDamage = event.getAmount();

            if (shouldBypassPlateProtection(damageSource)) {
                return;
            }

            if (!isCombatRelevantDamage(damageSource)) {
                return;
            }

            HitZone hitZone = HitZoneResolver.resolve(entity, damageSource);

            // 非 TACZ 子弹（如 CustomNPCs 投射物 EntityProjectile）的伤害部位随机。
            // TACZ 的 EntityHurtByGunEvent.Pre 不会发布，TaczGunEventHandler 的随机不执行，
            // 锁头 AI 会被 HitZoneResolver 永远解析成头部，因此在此处对玩家受击的
            // 非自伤投射物伤害做部位随机（1% 头部，其余 99% 均匀分布在六个体部）。
            Entity directEntity = damageSource.getDirectEntity();
            Entity shooter = damageSource.getEntity();
            if (entity instanceof Player && directEntity != null && directEntity != entity
                    && (shooter == null || shooter != entity)) {
                HitZone originalZone = hitZone;
                hitZone = rollRandomHitZone();
                FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Randomized hit zone (non-TACZ projectile): "
                    + originalZone + " -> " + hitZone);
            }

            ItemStack headArmor = entity.getItemBySlot(EquipmentSlot.HEAD);
            ItemStack torsoArmor = entity.getItemBySlot(EquipmentSlot.CHEST);

            PlateData headPlateData = getPlateDataForArmor(headArmor);
            PlateData torsoPlateData = getPlateDataForArmor(torsoArmor);

            boolean isMeleeAttack = isMeleeDamage(directEntity, shooter, entity, damageSource);

            float resolvedPenetration = 0.0F;
            float bulletArmorDamage = 1.0F;

            BulletConfig.BulletEntry bulletEntry = null;
            if (!isMeleeAttack) {
                bulletEntry = getBulletEntry(directEntity, shooter);

                if (bulletEntry != null) {
                    resolvedPenetration = bulletEntry.penetrationLevel * 10.0F;
                    bulletArmorDamage = (float) bulletEntry.armorDamage;
                } else {
                    PenetrationResolveContext context = PenetrationResolveContext.builder(incomingDamage)
                            .attach("projectile", directEntity)
                            .attach("shooter", shooter)
                            .attach("damageSource", damageSource)
                            .build();

                    resolvedPenetration = combatHandler.resolvePenetration(context);
                }
            }

            BodyPartDamageResult damageResult = zoneCalculator.resolveDamage(hitZone, headPlateData, torsoPlateData, incomingDamage, resolvedPenetration);

        if (damageResult.isBlockedByPlate()) {
            if (hitZone.isHead() && headPlateData != null) {
                applyDurabilityDamage(headPlateData, incomingDamage, resolvedPenetration, bulletArmorDamage);
                savePlateData(headArmor, headPlateData);
            } else if (hitZone.isTorso() && torsoPlateData != null) {
                applyDurabilityDamage(torsoPlateData, incomingDamage, resolvedPenetration, bulletArmorDamage);
                savePlateData(torsoArmor, torsoPlateData);
            }

            if (damageResult.isPlateBroken()) {
                if (hitZone.isHead() && headPlateData != null) {
                    headPlateData.breakInstantly();
                    savePlateData(headArmor, headPlateData);
                } else if (hitZone.isTorso() && torsoPlateData != null) {
                    torsoPlateData.breakInstantly();
                    savePlateData(torsoArmor, torsoPlateData);
                }
                if (entity instanceof ServerPlayer player) {
                    player.sendSystemMessage(Component.translatable("message.fragmentation.armor_destroyed"));
                }
            }

            if (!isMeleeAttack) {
                event.setAmount(0.0F);
                event.setCanceled(true);
                markProjectileAsProtected(entity, damageSource);
                // 死亡战报埋点：子弹被板完全挡下 = 未穿透。
                recordForDeathReport(entity, hitZone, headArmor, torsoArmor,
                    headPlateData, torsoPlateData, bulletEntry, incomingDamage,
                    resolvedPenetration, damageResult, shooter, directEntity, false);
            } else {
                event.setAmount(incomingDamage * 0.7F);
                // 近战命中但板削减了伤害，同样记录。
                recordForDeathReport(entity, hitZone, headArmor, torsoArmor,
                    headPlateData, torsoPlateData, bulletEntry, incomingDamage,
                    resolvedPenetration, damageResult, shooter, directEntity, true);
            }
        } else {
            if (damageResult.isPlateBroken()) {
                if (hitZone.isHead() && headPlateData != null) {
                    headPlateData.breakInstantly();
                    savePlateData(headArmor, headPlateData);
                } else if (hitZone.isTorso() && torsoPlateData != null) {
                    torsoPlateData.breakInstantly();
                    savePlateData(torsoArmor, torsoPlateData);
                }
                if (entity instanceof ServerPlayer player) {
                    player.sendSystemMessage(Component.translatable("message.fragmentation.armor_destroyed"));
                }
            }

            if (damageResult.isDamageApplied()) {
                float finalDamage = damageResult.getFinalDamage();
                entity.getPersistentData().putBoolean(IGNORE_ARMOR_REDUCTION_KEY, true);
                entity.getPersistentData().putFloat(INTENDED_DAMAGE_KEY, finalDamage);
                event.setAmount(finalDamage);
            }

            // 死亡战报埋点：枪械（含 CustomNPC 'npc' 路径）与近战/空手都记录。
            if (directEntity != null) {
                recordForDeathReport(entity, hitZone, headArmor, torsoArmor,
                    headPlateData, torsoPlateData, bulletEntry, incomingDamage,
                    resolvedPenetration, damageResult, shooter, directEntity, isMeleeAttack);
            }
        }
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ArmorPlateDamageHandler] Error processing living hurt event", e);
        }
    }

    /**
     * 死亡战报埋点（非 TACZ 路径：CustomNPC 投射物/枪械、近战/空手）：
     * 被甲挡住→未穿透；穿甲且命中前有有效护甲→穿透；无有效护甲→过穿；
     * melee=true→近战分类（武器名/空手）。
     */
    private static void recordForDeathReport(LivingEntity victim, HitZone hitZone,
                                             ItemStack headArmor, ItemStack torsoArmor,
                                             PlateData headPlateData, PlateData torsoPlateData,
                                             BulletConfig.BulletEntry bulletEntry, float incomingDamage,
                                             float resolvedPenetration, BodyPartDamageResult damageResult,
                                             Entity shooter, Entity directEntity, boolean melee) {
        try {
            boolean isHead = hitZone.isHead();
            PlateData activePlate = isHead ? headPlateData
                : (hitZone.isTorso() ? torsoPlateData : null);
            boolean protectedBefore = activePlate != null && activePlate.canProtect();

            String zone = switch (hitZone) {
                case HEAD -> "HEAD";
                case TORSO -> "CHEST";
                case ABDOMEN -> "ABDOMEN";
                case LEFT_ARM -> "LEFT_ARM";
                case RIGHT_ARM -> "RIGHT_ARM";
                case LEFT_LEG -> "LEFT_LEG";
                case RIGHT_LEG -> "RIGHT_LEG";
            };

            String type;
            float finalDamage;
            if (melee) {
                type = HitRecord.MELEE;
                finalDamage = damageResult.isBlockedByPlate()
                    ? incomingDamage * 0.7F
                    : (damageResult.isDamageApplied() ? damageResult.getFinalDamage() : incomingDamage);
            } else if (damageResult.isBlockedByPlate()) {
                type = HitRecord.NOT_PENETRATED;
                finalDamage = 0.0F;
            } else if (damageResult.isDamageApplied()) {
                finalDamage = damageResult.getFinalDamage();
                type = protectedBefore ? HitRecord.PENETRATED : HitRecord.OVER_PENETRATED;
            } else {
                finalDamage = 0.0F;
                type = HitRecord.OVER_PENETRATED;
            }

            String entityTypeId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                .getKey(directEntity.getType()).toString();
            String bulletId;
            float bulletPen;
            float bulletBase;

            if (melee && shooter instanceof LivingEntity attackerLiving) {
                // 近战武器名；空手显示「空手」。
                ItemStack weapon = attackerLiving.getMainHandItem();
                bulletId = weapon.isEmpty() ? "空手"
                    : weapon.getDisplayName().getString();
                bulletPen = 0.0F;
                bulletBase = incomingDamage;
            } else if (bulletEntry != null) {
                bulletId = bulletEntry.itemId != null && !bulletEntry.itemId.isEmpty()
                    ? bulletEntry.itemId : entityTypeId;
                bulletPen = bulletEntry.penetrationLevel;
                bulletBase = bulletEntry.baseDamage > 0
                    ? bulletEntry.baseDamage : incomingDamage;
            } else {
                bulletId = entityTypeId;
                bulletPen = resolvedPenetration / 10.0F;
                bulletBase = incomingDamage;
            }

            String armorName = "";
            int armorLevel = 0;
            int armorDur = 0;
            int armorMax = 0;
            if (activePlate != null) {
                ItemStack armorStack = isHead ? headArmor : torsoArmor;
                armorName = !armorStack.isEmpty()
                    ? armorStack.getDisplayName().getString()
                    : (isHead ? "头盔" : "护甲");
                armorLevel = activePlate.getPlateLevel();
                armorDur = activePlate.getDurability();
                armorMax = activePlate.getMaxDurability();
            }

            String attackerName = shooter != null
                ? shooter.getName().getString()
                : directEntity.getName().getString();

            long hitTick = victim.level().getGameTime();

            // 精确落点：近战用攻击者位置，投射物用子弹与碰撞箱接触点。
            float[] point;
            if (melee && directEntity instanceof LivingEntity directLiving) {
                point = HitLocator.melee(directLiving, victim, zone);
            } else if (!melee && directEntity != null) {
                point = HitLocator.projectile(directEntity, victim, zone);
            } else {
                point = new float[]{0.5F, 0.5F};
            }

            if (victim instanceof Player hurtPlayer) {
                HitRecordCollector.addIncoming(hurtPlayer.getUUID(),
                    new HitRecord(zone, bulletId, bulletPen, bulletBase,
                        armorName, armorLevel, armorDur, armorMax,
                        type, finalDamage, attackerName, hitTick,
                        point[0], point[1], melee));
            }
            if (shooter instanceof Player shootPlayer && shootPlayer != victim) {
                HitRecordCollector.addOutgoing(shootPlayer.getUUID(),
                    new HitRecord(zone, bulletId, bulletPen, bulletBase,
                        armorName, armorLevel, armorDur, armorMax,
                        type, finalDamage, victim.getName().getString(), hitTick,
                        point[0], point[1], melee));
            }
        } catch (Throwable t) {
            FragmentationMod.LOGGER.error("[ArmorPlateDamageHandler] Failed to record death-report hit", t);
        }
    }

    /**
     * 近战伤害判定：直接实体是有生命的攻击者本身（非投射物）。
     * CustomNPC 的 msgId='npc' 路径既用于 NPC 开枪也用于近战，
     * 按距离区分（3 格以内视为近战）。
     */
    private static boolean isMeleeDamage(Entity directEntity, Entity shooter,
                                         LivingEntity victim, DamageSource damageSource) {
        if (directEntity instanceof Projectile) return false;
        if (!(directEntity instanceof LivingEntity)) return false;
        if (shooter != null && directEntity != shooter) return false;
        if ("npc".equals(damageSource.getMsgId())) {
            return directEntity.distanceToSqr(victim) < 9.0D;
        }
        return true;
    }

    /** 非 TACZ 子弹（如 CustomNPCs 投射物）的伤害部位随机。1% 头部，其余 99% 在六个体部均匀分布。 */
    private static final java.util.Random HIT_ZONE_RANDOM = new java.util.Random();

    private static HitZone rollRandomHitZone() {
        if (HIT_ZONE_RANDOM.nextFloat() < 0.01F) {
            return HitZone.HEAD;
        }
        HitZone[] pool = {
            HitZone.TORSO, HitZone.ABDOMEN,
            HitZone.LEFT_ARM, HitZone.RIGHT_ARM,
            HitZone.LEFT_LEG, HitZone.RIGHT_LEG
        };
        return pool[HIT_ZONE_RANDOM.nextInt(pool.length)];
    }

    private static void applyDurabilityDamage(PlateData plateData, float incomingDamage, float penetrationValue, float bulletArmorDamage) {
        PenetrationCalculator calculator = new PenetrationCalculator();
        int durabilityDamage = calculator.computeDurabilityDamage(plateData, incomingDamage, penetrationValue);
        
        if (bulletArmorDamage != 1.0F) {
            durabilityDamage = Math.max(1, (int) (durabilityDamage * bulletArmorDamage));
        }
        
        plateData.applyDurabilityDamage(durabilityDamage);
    }

    private static BulletConfig.BulletEntry getBulletEntry(Object directEntity, Object shooter) {
        BulletConfig bulletConfig = FragmentationMod.getBulletConfig();

        if (directEntity instanceof Projectile projectile) {
            try {
                Class<?> bulletClass = projectile.getClass();
                String foundAmmoId = null;
                
                try {
                    java.lang.reflect.Field ammoIdField = bulletClass.getDeclaredField("ammoId");
                    ammoIdField.setAccessible(true);
                    Object ammoIdValue = ammoIdField.get(projectile);
                    if (ammoIdValue instanceof String) {
                        foundAmmoId = (String) ammoIdValue;
                    }
                } catch (NoSuchFieldException e) {
                    // ignore
                }

                if (foundAmmoId == null) {
                    CompoundTag tag = projectile.getPersistentData();
                    if (tag != null && tag.contains("tacz:ammo_id")) {
                        foundAmmoId = tag.getString("tacz:ammo_id");
                    }
                }

                if (foundAmmoId != null) {
                    // 仅按弹药ID精确匹配；禁止注册ID小写化与 contains 子串模糊匹配
                    // （模糊匹配会让不同弹药互相误判，如 tacz:9mm 命中 eft:9x19_pso）
                    return bulletConfig.getBulletByAmmoId(foundAmmoId);
                }
            } catch (Exception e) {
                // ignore
            }
        }

        if (shooter instanceof LivingEntity living) {
            ItemStack weapon = living.getMainHandItem();
            if (!weapon.isEmpty()) {
                BulletConfig.BulletEntry entry = bulletConfig.getBulletForItem(weapon);
                if (entry != null) return entry;
            }
        }

        return null;
    }

    private static PlateData getPlateDataForArmor(ItemStack armorStack) {
        if (PlateNbt.hasPlate(armorStack)) {
            return PlateNbt.read(armorStack);
        }

        ArmorConfig.ArmorEntry armorEntry = FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
        if (armorEntry != null) {
            PlateData plateData = new PlateData(armorEntry.armorLevel, armorEntry.maxDurability);

            CompoundTag tag = armorStack.getTag();
            if (tag != null && tag.contains("fragmentation_armor_state")) {
                CompoundTag stateTag = tag.getCompound("fragmentation_armor_state");
                plateData.restoreState(
                        stateTag.getInt("dur"),
                        stateTag.getFloat("acc_pen"),
                        stateTag.getBoolean("brk"),
                        stateTag.getInt("hit_count")
                );
            } else {
                int currentDur = DurabilityUtils.getCurrentDurability(armorStack);
                plateData.restoreState(currentDur, 0.0F, currentDur <= 0, 0);
            }

            return plateData;
        }

        return null;
    }

    private static void savePlateData(ItemStack armorStack, PlateData plateData) {
        if (PlateNbt.hasPlate(armorStack)) {
            PlateNbt.write(armorStack, plateData);
            return;
        }

        ArmorConfig.ArmorEntry armorEntry = FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
        if (armorEntry != null) {
            CompoundTag tag = armorStack.getOrCreateTag();
            CompoundTag stateTag = new CompoundTag();
            stateTag.putInt("dur", plateData.getDurability());
            stateTag.putFloat("acc_pen", plateData.getAccumulatedPenetration());
            stateTag.putBoolean("brk", plateData.isBroken());
            stateTag.putInt("hit_count", plateData.getHitCount());
            tag.put("fragmentation_armor_state", stateTag);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        try {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide) {
                return;
            }

            if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
                float intendedDamage = entity.getPersistentData().getFloat(INTENDED_DAMAGE_KEY);
                event.setAmount(intendedDamage);
                FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Restored intended damage in LivingDamageEvent: " + intendedDamage);
            }
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ArmorPlateDamageHandler] Error processing living damage event", e);
        }
    }

    private static boolean shouldBypassPlateProtection(DamageSource damageSource) {
        if (damageSource == null) {
            return true;
        }

        String damageType = damageSource.getMsgId();
        if (damageType == null || damageType.isEmpty()) {
            return false;
        }

        return damageType.equals("fall")
                || damageType.equals("flyIntoWall")
                || damageType.equals("inWall")
                || damageType.equals("cactus")
                || damageType.equals("drown")
                || damageType.equals("starve")
                || damageType.equals("outOfWorld")
                || damageType.equals("genericKill")
                || damageType.equals("magic")
                || damageType.equals("wither")
                || damageType.equals("dragonBreath")
                || damageType.equals("dryOut")
                || damageType.equals("freeze")
                || damageType.equals("stalagmite")
                || damageType.equals("fallingBlock")
                || damageType.equals("anvil")
                || damageType.equals("fallingStalactite")
                || damageType.equals("lava")
                || damageType.equals("hotFloor")
                || damageType.equals("inFire")
                || damageType.equals("onFire")
                || damageType.equals("lightningBolt")
                || damageType.equals("sweetBerryBush")
                || damageType.equals("cramming");
    }

    private static boolean isCombatRelevantDamage(DamageSource damageSource) {
        if (damageSource == null) {
            return false;
        }

        if (damageSource.getDirectEntity() instanceof Projectile) {
            return true;
        }

        if (damageSource.getEntity() instanceof LivingEntity) {
            return true;
        }

        return damageSource.getDirectEntity() instanceof LivingEntity;
    }

    private static void markProjectileAsProtected(LivingEntity entity, DamageSource damageSource) {
        if (entity == null || damageSource == null) {
            return;
        }

        var directEntity = damageSource.getDirectEntity();
        if (!(directEntity instanceof Projectile projectile)) {
            return;
        }

        CompoundTag data = entity.getPersistentData();
        data.putString(PROTECTED_PROJECTILE_UUID_KEY, projectile.getUUID().toString());
        data.putLong(PROTECTED_PROJECTILE_TICK_KEY, entity.level().getGameTime());
    }

    private static boolean isProtectedDuplicateProjectileHit(LivingEntity entity, DamageSource damageSource) {
        if (entity == null || damageSource == null) {
            return false;
        }

        var directEntity = damageSource.getDirectEntity();
        if (!(directEntity instanceof Projectile projectile)) {
            return false;
        }

        CompoundTag data = entity.getPersistentData();
        String lastProjectileUuid = data.getString(PROTECTED_PROJECTILE_UUID_KEY);
        long lastProjectileTick = data.getLong(PROTECTED_PROJECTILE_TICK_KEY);
        long currentTick = entity.level().getGameTime();

        return projectile.getUUID().toString().equals(lastProjectileUuid)
                && currentTick - lastProjectileTick >= 0L
                && currentTick - lastProjectileTick <= DUPLICATE_PROJECTILE_TICK_WINDOW;
    }
    
    private static void applyArmorDurabilityFromZone(LivingEntity entity, DetailedHitZone zone, float damage) {
        try {
            EquipmentSlot slot = zone.getEquipmentSlot();
            if (slot == null) {
                return;
            }
            
            ItemStack armorStack = entity.getItemBySlot(slot);
            if (armorStack.isEmpty()) {
                return;
            }
            
            ArmorConfig.ArmorEntry armorEntry = FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
            if (armorEntry == null) {
                return;
            }
            
            int currentDurability = DurabilityUtils.getCurrentDurability(armorStack);
            int durabilityDamage = Math.max(1, (int) (damage * 0.5f));
            int newDurability = Math.max(0, currentDurability - durabilityDamage);
            
            DurabilityUtils.setCurrentDurability(armorStack, newDurability);
            entity.setItemSlot(slot, armorStack);
            
            if (entity instanceof ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
                serverPlayer.onUpdateAbilities();
            }
            
            FragmentationMod.LOGGER.info("[ArmorPlateDamageHandler] Applied armor durability damage: slot=" + slot + 
                ", durability " + currentDurability + " -> " + newDurability + "/" + armorEntry.maxDurability);
        } catch (Exception e) {
            FragmentationMod.LOGGER.warn("[ArmorPlateDamageHandler] Failed to apply armor durability: " + e.getMessage());
        }
    }
}
