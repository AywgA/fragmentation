package com.fragmentation.shielding;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ShieldingFieldConfig;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.network.ShieldingFieldHitPacket;
import com.fragmentation.util.ArmorSoundManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 三层屏蔽场状态机（服务端）。
 *
 * 状态绑定在【屏蔽场发生器物品的 NBT】上（key = "fragmentation_field"），
 * 随物品移动：放在盔甲架上射击、取下、交给别人，负载都属于该发生器本身，
 * 多个发生器互相独立。
 *
 * 层次（由内向外）：负层 → 主层 → 次层。子弹先经次层削减动能，再由主层/负层吸收。
 */
public final class ShieldingFieldManager {

    public static final String FIELD_NBT_KEY = "fragmentation_field";
    private static final long TICKS_PER_SECOND = 20L;

    /** 徒手（空手）攻击的固定动能（焦耳） */
    public static final double MELEE_HAND_ENERGY_J = 16.0D;
    /** 近战武器（刀等）攻击的固定动能（焦耳） */
    public static final double MELEE_WEAPON_ENERGY_J = 65.0D;

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private ShieldingFieldManager() {
    }

    /** 命中处理结果 */
    public record HitResult(boolean absorbed, boolean severe) {
    }

    /** 屏蔽场发生器装备位置 */
    private record FieldItem(EquipmentSlot slot, ItemStack stack) {
    }

    // ==================== 装备检测 ====================

    /** 实体当前是否装备了激活屏蔽场的物品 */
    public static boolean isFieldActive(LivingEntity entity) {
        return findFieldItem(entity) != null;
    }

    /** 获取实体当前佩戴中的发生器物品堆（用于背包扫描时避免重复推进），无则 null */
    public static ItemStack getActiveFieldStack(LivingEntity entity) {
        FieldItem item = findFieldItem(entity);
        return item != null ? item.stack : null;
    }

    /**
     * 屏蔽场当前是否具备拦截能力（用于近战攻击判定）：
     * FAILED = 场已熄灭，攻击直接穿透；
     * RECOVERING 中主层负载高于穿透阈值时同样穿透。
     */
    public static boolean isFieldOperational(LivingEntity entity) {
        FieldItem item = findFieldItem(entity);
        if (item == null) return false;
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        ShieldingFieldState state = loadState(item.stack);
        return switch (state.phase) {
            case FAILED -> false;
            case RECOVERING -> {
                double mainRatio = config.mainLayerCapacityJoules > 0
                    ? state.mainLoad / config.mainLayerCapacityJoules : 1.0D;
                yield mainRatio <= config.recoveryPenetrationThreshold;
            }
            default -> true;
        };
    }

    private static FieldItem findFieldItem(LivingEntity entity) {
        if (entity == null) return null;
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (isFieldGeneratorStack(stack)) {
                return new FieldItem(slot, stack);
            }
        }
        return null;
    }

    /** 物品堆是否为激活屏蔽场的发生器（按注册 ID 匹配配置） */
    public static boolean isFieldGeneratorStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && config.isActivatorItem(key.toString());
    }

    /**
     * 保证注册为身位遮挡装备的物品【耐久无限】：
     * 写入 Unbreakable 标签（vanilla 所有耐久损耗路径都会跳过），并清零已有损伤。
     *
     * @return 物品是否被修改（调用方负责同步槽位）
     */
    public static boolean ensureIndestructible(ItemStack stack) {
        if (!isFieldGeneratorStack(stack)) return false;
        boolean changed = false;
        if (stack.getDamageValue() != 0) {
            stack.setDamageValue(0);
            changed = true;
        }
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.getBoolean("Unbreakable")) {
            tag.putBoolean("Unbreakable", true);
            changed = true;
        }
        return changed;
    }

    // ==================== 状态读写（物品 NBT） ====================

    public static ShieldingFieldState loadState(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new ShieldingFieldState();
        CompoundTag tag = stack.getTagElement(FIELD_NBT_KEY);
        if (tag == null) return new ShieldingFieldState();
        return ShieldingFieldState.load(tag);
    }

    public static void saveState(ItemStack stack, ShieldingFieldState state,
                                 ShieldingFieldConfig config) {
        if (stack == null || stack.isEmpty()) return;
        // 记录当前有效容量，供 tooltip 显示
        state.effectiveNegativeCap = config.negativeLayerCapacityJoules
            * (state.phase == ShieldingFieldState.Phase.OVERLOAD
                ? config.overloadCapacityMultiplier : 1.0D);
        state.effectiveMainCap = config.mainLayerCapacityJoules;
        stack.addTagElement(FIELD_NBT_KEY, state.save());
    }

    // ==================== 命中吸收逻辑 ====================

    /**
     * 子弹命中实体时调用。
     *
     * @param entity              被命中的实体
     * @param kineticEnergyJoules 子弹命中时动能（焦耳）
     * @param speedMps            子弹命中时速度（m/s），用于高速/低速视觉与音效判定
     * @param gameTick            当前游戏刻
     * @param hitPos              子弹命中点（用于点亮特效）
     * @return 吸收结果：absorbed=true 表示子弹被完全吸收（应取消伤害）
     */
    public static HitResult onHit(LivingEntity entity, double kineticEnergyJoules, double speedMps,
                                  long gameTick, Vec3 hitPos) {
        FieldItem item = findFieldItem(entity);
        if (item == null) {
            return new HitResult(false, false);
        }
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        ShieldingFieldState state = loadState(item.stack);
        state.lastHitTick = gameTick;

        double negCap = config.negativeLayerCapacityJoules;
        double mainCap = config.mainLayerCapacityJoules;

        // 过载/冷却可能在未被 tick 推进时刚好到期，先推进一次
        advancePhaseTransitions(state, config, gameTick, negCap, mainCap);
        // 记录推进后的阶段：用于判定本次命中是否导致屏蔽场失去防护效果
        ShieldingFieldState.Phase prevPhase = state.phase;

        boolean severe = isSevere(state, config, mainCap);
        // 命中点投影到实体中心水平面上的方向（用于球面点亮）
        Vec3 fxPos = hitPos != null ? hitPos : entity.position();
        // 高速/低速判定：高速 → 蓝色闪光（正常挡下）；低速穿过 / 失效 → 深红（沙丘设定）
        boolean fast = speedMps >= config.fastSpeedThresholdMps;
        // 命中配色模式：高压力状态（负载高/降级/过载/重建）受击 → 浅蓝；正常挡下 → 深蓝；
        // 深红仅用于慢速穿过与屏蔽场失效（见各失效/穿透分支）
        int strainMode = severe
            ? ShieldingFieldHitPacket.MODE_LIGHT_BLUE : ShieldingFieldHitPacket.MODE_BLUE;

        HitResult result;
        switch (state.phase) {
            case FAILED -> {
                // 场已熄灭：受击直接穿透，深红表现
                sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN,
                    ShieldingFieldHitPacket.MODE_RED, state, mainCap, fast);
                result = new HitResult(false, true);
            }
            case RECOVERING -> {
                // 重建期：只有主层吸收；主层负载高于阈值则穿透
                double mainRatio = mainCap > 0 ? state.mainLoad / mainCap : 1.0D;
                if (mainRatio > config.recoveryPenetrationThreshold) {
                    // 重建期穿透：慢速 → 深红；高速 → 浅蓝（高压力状态）
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN,
                        fast ? ShieldingFieldHitPacket.MODE_LIGHT_BLUE : ShieldingFieldHitPacket.MODE_RED,
                        state, mainCap, fast);
                    result = new HitResult(false, true);
                } else {
                    double remaining = mainCap - state.mainLoad;
                    double absorbedEnergy = Math.min(Math.max(0.0D, kineticEnergyJoules), remaining);
                    state.mainLoad += absorbedEnergy;
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN, strainMode,
                        state, mainCap, fast);
                    result = (kineticEnergyJoules - absorbedEnergy) > 1.0D
                        ? new HitResult(false, true)
                        : new HitResult(true, severe);
                }
            }
            case OVERLOAD -> {
                // 主/次已碎，子弹直接命中负层（无次层削减），负层容量 = 基础 × 过载倍率
                double boostedCap = negCap * config.overloadCapacityMultiplier;
                double remaining = boostedCap - state.negativeLoad;
                // 主/次层已碎除：高压力状态，浅蓝
                sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE, strainMode,
                    state, mainCap, fast);
                if (kineticEnergyJoules <= remaining) {
                    state.negativeLoad += kineticEnergyJoules;
                    result = new HitResult(true, true);
                } else {
                    state.negativeLoad = boostedCap;
                    enterFailed(state, config, gameTick);
                    FragmentationMod.LOGGER.warn(
                        "[ShieldingField] {} 负层过载容量耗尽，屏蔽场完全失效，进入 {} 秒冷却",
                        entity.getName().getString(), config.cooldownDurationSeconds);
                    // 失效瞬间：深红
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE,
                        ShieldingFieldHitPacket.MODE_RED, state, mainCap, fast);
                    result = new HitResult(false, true);
                }
            }
            case ACTIVE -> {
                // 次层削减动能
                double energy = kineticEnergyJoules;
                if (state.secondaryIntegrity >= 1.0D) {
                    energy *= (1.0D - config.secondaryLayerReduction);
                    // 次层正常削减：深蓝闪光，无穿透
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_SECONDARY,
                        ShieldingFieldHitPacket.MODE_BLUE, state, mainCap, fast);
                }

                // 主层已满再被击中 → 碎除主层/次层，进入过载
                if (state.mainLoad >= mainCap - 1.0D) {
                    // 主层碎除：高压力状态，浅蓝
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN, strainMode,
                        state, mainCap, fast);
                    enterOverload(state, config, gameTick);
                    double boostedCap = negCap * config.overloadCapacityMultiplier;
                    double remaining = boostedCap - state.negativeLoad;
                    if (energy <= remaining) {
                        state.negativeLoad += energy;
                        sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE, strainMode,
                            state, mainCap, fast);
                        FragmentationMod.LOGGER.info(
                            "[ShieldingField] {} 主层碎除，进入过载（{}秒）",
                            entity.getName().getString(), config.overloadDurationSeconds);
                        result = new HitResult(true, true);
                    } else {
                        state.negativeLoad = boostedCap;
                        enterFailed(state, config, gameTick);
                        // 失效瞬间：深红
                        sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE,
                            ShieldingFieldHitPacket.MODE_RED, state, mainCap, fast);
                        result = new HitResult(false, true);
                    }
                    break;
                }

                double mainRatio = mainCap > 0 ? state.mainLoad / mainCap : 0.0D;
                if (mainRatio >= config.mainDegradedThreshold) {
                    // 降级：主层吸 75%，负层吸 25%
                    double mainShare = energy * config.mainDegradedAbsorbRatio;
                    double negShare = energy - mainShare;
                    double mainRemaining = mainCap - state.mainLoad;
                    double mainAbsorbed = Math.min(mainShare, mainRemaining);
                    state.mainLoad += mainAbsorbed;
                    // 降级状态受击：浅蓝（高压力）
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN, strainMode,
                        state, mainCap, fast);
                    double mainLeftover = mainShare - mainAbsorbed;

                    double negTotal = negShare + mainLeftover;
                    double negRemaining = negCap - state.negativeLoad;
                    if (negTotal <= negRemaining) {
                        state.negativeLoad += negTotal;
                        sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE, strainMode,
                            state, mainCap, fast);
                        result = new HitResult(true, severe);
                    } else {
                        state.negativeLoad = negCap;
                        enterFailed(state, config, gameTick);
                        FragmentationMod.LOGGER.warn(
                            "[ShieldingField] {} 负层达到极限，屏蔽场完全失效",
                            entity.getName().getString());
                        // 失效瞬间：深红
                        sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE,
                            ShieldingFieldHitPacket.MODE_RED, state, mainCap, fast);
                        result = new HitResult(false, true);
                    }
                } else {
                    // 正常：主层全吸，吸不下的交给负层
                    double mainRemaining = mainCap - state.mainLoad;
                    double mainAbsorbed = Math.min(energy, mainRemaining);
                    state.mainLoad += mainAbsorbed;
                    sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_MAIN, strainMode,
                        state, mainCap, fast);
                    double leftover = energy - mainAbsorbed;
                    if (leftover <= 0.0D) {
                        result = new HitResult(true, severe);
                    } else {
                        double negRemaining = negCap - state.negativeLoad;
                        if (leftover <= negRemaining) {
                            state.negativeLoad += leftover;
                            sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE, strainMode,
                                state, mainCap, fast);
                            result = new HitResult(true, severe);
                        } else {
                            state.negativeLoad = negCap;
                            enterFailed(state, config, gameTick);
                            // 失效瞬间：深红
                            sendHit(entity, fxPos, ShieldingFieldHitPacket.LAYER_NEGATIVE,
                                ShieldingFieldHitPacket.MODE_RED, state, mainCap, fast);
                            result = new HitResult(false, true);
                        }
                    }
                }
            }
            default -> result = new HitResult(false, severe);
        }

        saveState(item.stack, state, config);

        // ===== 音效判定 =====
        // 高速物体被挡下 → gaosu；低速物品穿过 / 本次命中导致失去防护效果 → disu。
        // 屏蔽场已失效（FAILED）时再被击中属于无防护受击，不重复播放。
        if (prevPhase != ShieldingFieldState.Phase.FAILED) {
            boolean failedNow = state.phase == ShieldingFieldState.Phase.FAILED;
            if (failedNow || !result.absorbed()) {
                ArmorSoundManager.playShieldPenetrateSound(entity);
            } else if (fast) {
                ArmorSoundManager.playShieldBlockSound(entity);
            }
        }

        // 通知实体更新装备槽（保证盔甲架/玩家的物品 NBT 同步到客户端）
        entity.setItemSlot(item.slot, item.stack);
        return result;
    }

    /**
     * 广播命中脉冲。
     *
     * @param colorMode 配色模式：MODE_BLUE 深蓝（正常挡下）/
     *                  MODE_LIGHT_BLUE 浅蓝（高压力状态：负载高/降级/过载/重建）/
     *                  MODE_RED 深红（慢速穿过或屏蔽场失效）
     */
    private static void sendHit(LivingEntity entity, Vec3 pos, int layer, int colorMode,
                                ShieldingFieldState state, double mainCap, boolean fast) {
        double ratio = mainCap > 0 && state != null ? state.mainLoad / mainCap : -1.0D;
        ModNetwork.sendShieldingFieldHit(entity, pos, layer, colorMode,
            (float) Math.max(-1.0D, Math.min(1.0D, ratio)),
            fast);
    }

    /**
     * 慢速物体穿过屏蔽场（沙丘设定：慢速穿透）：广播一次深红色命中脉冲。
     * 不吸收动能、不改变状态，仅客户端表现；供近战慢速穿透（蹲/站攻击）调用。
     */
    public static void sendSlowPassThroughFx(LivingEntity entity, Vec3 hitPos) {
        FieldItem item = findFieldItem(entity);
        if (item == null) return;
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        ShieldingFieldState state = loadState(item.stack);
        Vec3 pos = hitPos != null ? hitPos : entity.position();
        sendHit(entity, pos, ShieldingFieldHitPacket.LAYER_MAIN,
            ShieldingFieldHitPacket.MODE_RED, state, config.mainLayerCapacityJoules, false);
    }

    private static boolean isSevere(ShieldingFieldState state, ShieldingFieldConfig config, double mainCap) {
        if (state.phase != ShieldingFieldState.Phase.ACTIVE) return true;
        double ratio = mainCap > 0 ? state.mainLoad / mainCap : 0.0D;
        return ratio >= config.mainLoadVisualThreshold;
    }

    private static void enterOverload(ShieldingFieldState state, ShieldingFieldConfig config, long gameTick) {
        state.phase = ShieldingFieldState.Phase.OVERLOAD;
        state.secondaryIntegrity = 0.0D;
        state.mainLoad = config.mainLayerCapacityJoules;
        state.phaseEndTick = gameTick + (long) config.overloadDurationSeconds * TICKS_PER_SECOND;
    }

    private static void enterFailed(ShieldingFieldState state, ShieldingFieldConfig config, long gameTick) {
        state.phase = ShieldingFieldState.Phase.FAILED;
        state.secondaryIntegrity = 0.0D;
        state.mainLoad = config.mainLayerCapacityJoules;
        state.negativeLoad = config.negativeLayerCapacityJoules;
        state.phaseEndTick = gameTick + (long) config.cooldownDurationSeconds * TICKS_PER_SECOND;
    }

    private static void enterRecovering(ShieldingFieldState state) {
        state.phase = ShieldingFieldState.Phase.RECOVERING;
        state.phaseEndTick = -1L;
    }

    private static void advancePhaseTransitions(ShieldingFieldState state, ShieldingFieldConfig config,
                                                long gameTick, double negCap, double mainCap) {
        if (state.phase == ShieldingFieldState.Phase.OVERLOAD && gameTick >= state.phaseEndTick) {
            state.negativeLoad *= config.overloadEndLoadFactor;
            state.mainLoad = mainCap;
            state.secondaryIntegrity = 0.0D;
            enterRecovering(state);
            FragmentationMod.LOGGER.info("[ShieldingField] 过载结束，负层负载减半，进入重建期");
        } else if (state.phase == ShieldingFieldState.Phase.FAILED && gameTick >= state.phaseEndTick) {
            state.negativeLoad = negCap;
            state.mainLoad = mainCap;
            state.secondaryIntegrity = 0.0D;
            enterRecovering(state);
            FragmentationMod.LOGGER.info("[ShieldingField] 冷却结束，屏蔽场开始重建");
        }
    }

    // ==================== 每刻推进 ====================

    /**
     * 每个服务端刻调用，推进所有佩戴屏蔽场发生器的实体的计时与恢复。
     */
    public static void tick(Iterable<? extends LivingEntity> entities, long gameTick) {
        for (LivingEntity entity : entities) {
            if (entity == null || entity.isRemoved() || !entity.isAlive()) continue;

            // 装备槽内所有激活装备保证耐久无限（Unbreakable + 损伤清零）
            for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
                ItemStack worn = entity.getItemBySlot(slot);
                if (ensureIndestructible(worn)) {
                    entity.setItemSlot(slot, worn);
                }
            }

            FieldItem item = findFieldItem(entity);
            if (item == null) continue;

            if (tickFieldItem(item.stack, gameTick)) {
                entity.setItemSlot(item.slot, item.stack);
            }
        }
    }

    /**
     * 推进【未佩戴】发生器（放在玩家背包/副手等）的计时与恢复：
     * 从身上取下后负载同样按配置速率回复、过载/冷却同样计时。
     * 只处理带有状态 NBT 的发生器；全新无状态物品无需推进。
     *
     * @return 状态是否发生变化（调用方负责同步物品堆）
     */
    public static boolean tickFieldItem(ItemStack stack, long gameTick) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getTagElement(FIELD_NBT_KEY) == null) return false;

        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        double negCap = config.negativeLayerCapacityJoules;
        double mainCap = config.mainLayerCapacityJoules;
        double dt = 1.0D / TICKS_PER_SECOND;
        long delayTicks = (long) config.recoveryDelaySeconds * TICKS_PER_SECOND;

        ShieldingFieldState state = loadState(stack);
        advancePhaseTransitions(state, config, gameTick, negCap, mainCap);

        boolean recoveryGate = gameTick - state.lastHitTick >= delayTicks;
        boolean changed = false;

        if (state.phase == ShieldingFieldState.Phase.ACTIVE && recoveryGate) {
            // 战斗被动恢复（负载下降）
            double oldMain = state.mainLoad;
            double oldNeg = state.negativeLoad;
            state.mainLoad = Math.max(0.0D,
                state.mainLoad - config.combatRecoveryRateMain * mainCap * dt);
            state.negativeLoad = Math.max(0.0D,
                state.negativeLoad - config.combatRecoveryRateNegative * negCap * dt);
            changed = oldMain != state.mainLoad || oldNeg != state.negativeLoad;
        } else if (state.phase == ShieldingFieldState.Phase.RECOVERING && recoveryGate) {
            // 冷却后重建
            state.mainLoad = Math.max(0.0D,
                state.mainLoad - config.rebuildRateMain * mainCap * dt);
            state.negativeLoad = Math.max(0.0D,
                state.negativeLoad - config.rebuildRateNegative * negCap * dt);
            state.secondaryIntegrity = Math.min(1.0D,
                state.secondaryIntegrity + config.rebuildRateSecondary * dt);
            changed = true;
            if (state.mainLoad <= 0.0D && state.negativeLoad <= 0.0D
                && state.secondaryIntegrity >= 1.0D) {
                state.reset();
                FragmentationMod.LOGGER.info("[ShieldingField] 背包内屏蔽场重建完成");
            }
        } else if (state.phase == ShieldingFieldState.Phase.OVERLOAD
            || state.phase == ShieldingFieldState.Phase.FAILED) {
            // 阶段推进可能修改了状态
            changed = true;
        }

        if (changed) {
            saveState(stack, state, config);
        }
        return changed;
    }
}
