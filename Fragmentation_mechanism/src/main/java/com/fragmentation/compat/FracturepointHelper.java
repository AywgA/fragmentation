package com.fragmentation.compat;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.PlateConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class FracturepointHelper {
    
    private static final String PLATE_DATA_KEY = "PlateData";
    private static final String FRONT_PLATE_KEY = "FrontPlate";
    private static final String BACK_PLATE_KEY = "BackPlate";
    private static final String TIER_KEY = "Tier";
    private static final String MATERIAL_KEY = "Material";
    private static final String PLATE_ITEM_ID_KEY = "PlateItemId";
    private static final String MAX_DURABILITY_KEY = "MaxDurability";
    private static final String CURRENT_DURABILITY_KEY = "CurrentDurability";

    /**
     * 插板"已耗尽"判定阈值：零耐久保留机制。
     * 设为极小正值而非 0，防止 Fracturepoint 模组移除插板 NBT。
     * 当 CurrentDurability <= 此值时视为插板已耗尽（不提供防御）。
     */
    public static final float DEPLETED_THRESHOLD = 0.001f;
    
    public static boolean hasPlate(ItemStack chestplate) {
        if (chestplate.isEmpty()) return false;
        CompoundTag tag = chestplate.getTag();
        if (tag == null) return false;
        if (!tag.contains(PLATE_DATA_KEY)) return false;
        CompoundTag plateData = tag.getCompound(PLATE_DATA_KEY);
        return hasActivePlate(plateData, FRONT_PLATE_KEY) 
            || hasActivePlate(plateData, BACK_PLATE_KEY);
    }
    
    public static boolean hasFrontPlate(ItemStack chestplate) {
        return getFrontPlate(chestplate) != null;
    }
    
    public static boolean hasBackPlate(ItemStack chestplate) {
        return getBackPlate(chestplate) != null;
    }
    
    public static CompoundTag getFrontPlate(ItemStack chestplate) {
        return getSpecificPlate(chestplate, FRONT_PLATE_KEY);
    }
    
    public static CompoundTag getBackPlate(ItemStack chestplate) {
        return getSpecificPlate(chestplate, BACK_PLATE_KEY);
    }
    
    private static CompoundTag getSpecificPlate(ItemStack chestplate, String plateKey) {
        if (chestplate.isEmpty()) return null;
        CompoundTag tag = chestplate.getTag();
        if (tag == null) return null;
        if (!tag.contains(PLATE_DATA_KEY)) return null;
        CompoundTag plateData = tag.getCompound(PLATE_DATA_KEY);
        if (!hasActivePlate(plateData, plateKey)) return null;
        return plateData.getCompound(plateKey).copy();
    }
    
    private static boolean hasActivePlate(CompoundTag plateData, String plateKey) {
        if (!plateData.contains(plateKey)) return false;
        CompoundTag plate = plateData.getCompound(plateKey);
        if (!plate.contains(TIER_KEY)) return false;
        return true;
    }
    
    public static int getPlateLevel(CompoundTag plateNbt) {
        if (plateNbt == null || !plateNbt.contains(TIER_KEY)) return 0;
        String tierName = plateNbt.getString(TIER_KEY);
        String materialName = plateNbt.contains(MATERIAL_KEY) ? plateNbt.getString(MATERIAL_KEY) : null;

        PlateConfig.PlateEntry configEntry = findPlateConfig(tierName, materialName);
        if (configEntry != null) {
            return configEntry.plateLevel;
        }

        return tierToLevel(tierName);
    }

    /**
     * 获取插板等级。回退链（保证与伤害计算一致，不用 tierToLevel）：
     *   1. plate config 中有该插板 → 用 plate config 等级
     *   2. 否则按 Tier+Material 拼出物品 ID（如 fracturepoint:ceramic_plate_level_iv），
     *      若该物品在 armor config 中有配置 → 用插板自身的护甲配置等级
     *   3. 否则 → 用调用方传入的 fallbackLevel（弹挂/护盾等级）
     */
    public static int getPlateLevel(CompoundTag plateNbt, int fallbackLevel) {
        if (plateNbt == null) return fallbackLevel;
        PlateConfig.PlateEntry configEntry = getPlateConfigEntry(plateNbt);
        if (configEntry != null) {
            return configEntry.plateLevel;
        }

        // 插板被加入了 armor config（而非 plate config）：用插板自身的护甲配置等级
        String tierName = plateNbt.contains(TIER_KEY) ? plateNbt.getString(TIER_KEY) : null;
        String materialName = plateNbt.contains(MATERIAL_KEY) ? plateNbt.getString(MATERIAL_KEY) : null;
        if (tierName != null && materialName != null) {
            String plateItemId = "fracturepoint:" + materialName.toLowerCase()
                + "_plate_" + tierName.toLowerCase();
            ArmorConfig armorConfig = FragmentationMod.getArmorConfig();
            if (armorConfig != null) {
                ArmorConfig.ArmorEntry plateArmorEntry = armorConfig.getArmorById(plateItemId);
                if (plateArmorEntry != null) {
                    return plateArmorEntry.armorLevel;
                }
            }
        }

        return fallbackLevel;
    }
    
    public static float getPlateMaxDurability(CompoundTag plateNbt) {
        if (plateNbt == null || !plateNbt.contains(MAX_DURABILITY_KEY)) return 0;
        float nbtMaxDur = plateNbt.getFloat(MAX_DURABILITY_KEY);
        String tierName = plateNbt.contains(TIER_KEY) ? plateNbt.getString(TIER_KEY) : null;
        String materialName = plateNbt.contains(MATERIAL_KEY) ? plateNbt.getString(MATERIAL_KEY) : null;

        PlateConfig.PlateEntry configEntry = findPlateConfig(tierName, materialName);
        if (configEntry != null) {
            return configEntry.maxDurability;
        }

        // 插板被加入了 armor config（而非 plate config）：用插板自身护甲配置的最大耐久
        if (tierName != null && materialName != null) {
            String plateItemId = "fracturepoint:" + materialName.toLowerCase()
                + "_plate_" + tierName.toLowerCase();
            ArmorConfig armorConfig = FragmentationMod.getArmorConfig();
            if (armorConfig != null) {
                ArmorConfig.ArmorEntry plateArmorEntry = armorConfig.getArmorById(plateItemId);
                if (plateArmorEntry != null) {
                    return plateArmorEntry.maxDurability;
                }
            }
        }

        return nbtMaxDur;
    }
    
    public static float getPlateCurrentDurability(CompoundTag plateNbt) {
        if (plateNbt == null || !plateNbt.contains(CURRENT_DURABILITY_KEY)) return 0;
        return plateNbt.getFloat(CURRENT_DURABILITY_KEY);
    }
    
    public static String getPlateMaterial(CompoundTag plateNbt) {
        if (plateNbt == null || !plateNbt.contains(MATERIAL_KEY)) return "steel";
        String materialName = plateNbt.getString(MATERIAL_KEY).toLowerCase();
        
        if (materialName.contains("ceramic")) return "ceramic";
        if (materialName.contains("steel") || materialName.contains("armor")) return "steel";
        if (materialName.contains("aluminum") || materialName.contains("aluminium")) return "aluminum";
        if (materialName.contains("titanium")) return "titanium";
        if (materialName.contains("uhmwpe") || materialName.contains("polyethylene")) return "uhmwpe";
        if (materialName.contains("kevlar") || materialName.contains("aramid")) return "kevlar";
        
        return "steel";
    }

    public static PlateConfig.PlateEntry getPlateConfigEntry(CompoundTag plateNbt) {
        if (plateNbt == null) return null;

        PlateConfig plateConfig = FragmentationMod.getPlateConfig();
        if (plateConfig == null) return null;

        if (plateNbt.contains(PLATE_ITEM_ID_KEY)) {
            PlateConfig.PlateEntry byItemId = plateConfig.getPlateById(
                plateNbt.getString(PLATE_ITEM_ID_KEY)
            );
            if (byItemId != null) return byItemId;
        }

        String tierName = plateNbt.contains(TIER_KEY)
            ? plateNbt.getString(TIER_KEY)
            : null;
        String materialName = plateNbt.contains(MATERIAL_KEY)
            ? plateNbt.getString(MATERIAL_KEY)
            : null;
        return findPlateConfig(tierName, materialName);
    }
    
    private static PlateConfig.PlateEntry findPlateConfig(String tierName, String materialName) {
        PlateConfig plateConfig = FragmentationMod.getPlateConfig();
        if (plateConfig == null) return null;
        
        if (materialName != null && tierName != null) {
            String combinedId = "fracturepoint:" + materialName.toLowerCase() + "_plate_" + tierName.toLowerCase();
            PlateConfig.PlateEntry entry = plateConfig.getPlateById(combinedId);
            if (entry != null) return entry;
        }
        
        if (tierName != null) {
            String tierId = "fracturepoint:plate_" + tierName.toLowerCase();
            PlateConfig.PlateEntry entry = plateConfig.getPlateById(tierId);
            if (entry != null) return entry;
        }
        
        return null;
    }
    
    public static int getFrontPlateLevel(ItemStack chestplate) {
        return getPlateLevel(getFrontPlate(chestplate));
    }
    
    public static int getBackPlateLevel(ItemStack chestplate) {
        return getPlateLevel(getBackPlate(chestplate));
    }
    
    public static float getFrontPlateDurability(ItemStack chestplate) {
        return getPlateCurrentDurability(getFrontPlate(chestplate));
    }
    
    public static float getBackPlateDurability(ItemStack chestplate) {
        return getPlateCurrentDurability(getBackPlate(chestplate));
    }
    
    public static void damageFrontPlate(ItemStack chestplate, float amount) {
        damageSpecificPlate(chestplate, FRONT_PLATE_KEY, amount);
    }
    
    public static void damageBackPlate(ItemStack chestplate, float amount) {
        damageSpecificPlate(chestplate, BACK_PLATE_KEY, amount);
    }
    
    private static void damageSpecificPlate(ItemStack chestplate, String plateKey, float amount) {
        if (chestplate.isEmpty()) return;
        CompoundTag tag = chestplate.getTag();
        if (tag == null) return;
        if (!tag.contains(PLATE_DATA_KEY)) return;
        CompoundTag plateData = tag.getCompound(PLATE_DATA_KEY);

        if (!plateData.contains(plateKey)) return;
        CompoundTag plate = plateData.getCompound(plateKey);
        if (!plate.contains(TIER_KEY)) return;

        float currentDur = plate.getFloat(CURRENT_DURABILITY_KEY);
        // 已耗尽的插板不再受进一步伤害（防止重复扣减到负值）
        if (currentDur <= DEPLETED_THRESHOLD) return;

        float newDur = currentDur - amount;
        if (newDur <= 0) {
            // 零耐久保留：设为极小正值而非0，防止 Fracturepoint 模组移除插板 NBT。
            // calculateWithPlate 中 plateDurability <= DEPLETED_THRESHOLD 时视为已耗尽。
            newDur = DEPLETED_THRESHOLD;
        }
        plate.putFloat(CURRENT_DURABILITY_KEY, newDur);
        plateData.put(plateKey, plate);
        tag.put(PLATE_DATA_KEY, plateData);

        FragmentationMod.LOGGER.info("[PlateHelper] Damaged {} plate: -{}, remaining: {}",
                plateKey, amount, newDur);
    }
    
    private static final String SYNCED_FRONT_MARKER = "fragmentation_synced_front_plate";
    private static final String SYNCED_BACK_MARKER = "fragmentation_synced_back_plate";

    /**
     * 每 tick 在服务端对穿戴胸甲调用一次，纠正"刚装入"插板的耐久。
     *
     * 背景：Fracturepoint 装入插板时，按其自身默认数值写入 PlateData
     * （如钢制 LEVEL_III 默认 90），不会读取本模组给插板物品配置的
     * 最大耐久（155）。结果当前耐久 90、最大耐久（经我们配置纠正）155。
     *
     * 判定：当某一侧插板的"材料:等级"指纹与本胸甲记录的标记不一致，
     * 说明是新装入/更换的插板；若其当前耐久在 Fracturepoint 自身刻度上
     * 为满耐久（cur >= nbtMax），即未被使用过的新插板，则把当前耐久与
     * NBT 最大耐久一并纠正为本模组配置的最大耐久。
     * 已有损伤的插板（cur < nbtMax）绝不重置，避免回满。
     */
    public static void syncInsertedPlates(ItemStack chestplate) {
        if (chestplate == null || chestplate.isEmpty()) return;
        CompoundTag tag = chestplate.getTag();
        if (tag == null || !tag.contains(PLATE_DATA_KEY)) return;
        CompoundTag plateData = tag.getCompound(PLATE_DATA_KEY);

        syncOneSide(chestplate, plateData, FRONT_PLATE_KEY, SYNCED_FRONT_MARKER);
        syncOneSide(chestplate, plateData, BACK_PLATE_KEY, SYNCED_BACK_MARKER);
    }

    private static void syncOneSide(
        ItemStack chestplate,
        CompoundTag plateData,
        String plateKey,
        String markerKey
    ) {
        CompoundTag tag = chestplate.getTag();

        if (!plateData.contains(plateKey)) {
            // 该侧无插板：清掉标记，便于下次重新装入时识别
            if (tag.contains(markerKey)) {
                tag.remove(markerKey);
            }
            return;
        }

        CompoundTag plate = plateData.getCompound(plateKey);
        if (!plate.contains(TIER_KEY)) return;

        String tier = plate.getString(TIER_KEY);
        String material = plate.contains(MATERIAL_KEY)
            ? plate.getString(MATERIAL_KEY) : "STEEL";
        String fingerprint = material + ":" + tier;

        String previous = tag.contains(markerKey) ? tag.getString(markerKey) : null;
        if (fingerprint.equals(previous)) {
            return; // 持续存在的同一块插板，不动它（含已损伤/已耗尽）
        }

        // 指纹变化：新装入或更换了插板
        float cur = plate.contains(CURRENT_DURABILITY_KEY)
            ? plate.getFloat(CURRENT_DURABILITY_KEY) : 0;
        float nbtMax = plate.contains(MAX_DURABILITY_KEY)
            ? plate.getFloat(MAX_DURABILITY_KEY) : 0;

        // 仅当插板按 Fracturepoint 自身刻度为"全新满耐久"时才纠正，
        // 防止把历史遗留的受损插板误回满。
        if (nbtMax > 0 && cur >= nbtMax - 0.001f) {
            float configuredMax = getPlateMaxDurability(plate);
            if (configuredMax > 0 && Math.abs(cur - configuredMax) > 0.001f) {
                plate.putFloat(MAX_DURABILITY_KEY, configuredMax);
                plate.putFloat(CURRENT_DURABILITY_KEY, configuredMax);
                plateData.put(plateKey, plate);
                tag.put(PLATE_DATA_KEY, plateData);
                FragmentationMod.LOGGER.info(
                    "[PlateHelper] Newly inserted {} plate corrected to configured max durability: {}",
                    plateKey, configuredMax);
            }
        }

        tag.putString(markerKey, fingerprint);
    }

    public static int tierToLevel(String tierName) {
        if (tierName == null) return 0;
        switch (tierName) {
            case "LEVEL_IIA": return 1;
            case "LEVEL_II": return 2;
            case "LEVEL_IIIA": return 3;
            case "LEVEL_III": return 4;
            case "LEVEL_IV": return 5;
            case "DEFAULT":
            default: return 0;
        }
    }
    
    public static String getPlateInfo(ItemStack chestplate) {
        return getPlateInfo(chestplate, 0);
    }

    /**
     * 带 fallback 等级的插板信息。未配置插板时用 fallbackLevel（护甲/护盾等级）
     * 替代 tierToLevel，保证显示与伤害计算一致。
     */
    public static String getPlateInfo(ItemStack chestplate, int fallbackLevel) {
        CompoundTag frontPlate = getFrontPlate(chestplate);
        CompoundTag backPlate = getBackPlate(chestplate);

        StringBuilder sb = new StringBuilder();
        if (frontPlate != null) {
            int level = getPlateLevel(frontPlate, fallbackLevel);
            float cur = getPlateCurrentDurability(frontPlate);
            float max = getPlateMaxDurability(frontPlate);
            sb.append("前插板: Lv").append(level)
              .append(" (").append(String.format("%.1f", cur))
              .append("/").append(String.format("%.1f", max)).append(")");
        }
        if (backPlate != null) {
            if (sb.length() > 0) sb.append(", ");
            int level = getPlateLevel(backPlate, fallbackLevel);
            float cur = getPlateCurrentDurability(backPlate);
            float max = getPlateMaxDurability(backPlate);
            sb.append("后插板: Lv").append(level)
              .append(" (").append(String.format("%.1f", cur))
              .append("/").append(String.format("%.1f", max)).append(")");
        }
        return sb.length() > 0 ? sb.toString() : "无";
    }
}
