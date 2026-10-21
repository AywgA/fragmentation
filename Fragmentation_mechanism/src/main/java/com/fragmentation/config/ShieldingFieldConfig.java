package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙丘式三层屏蔽场配置。
 * 配置文件位于世界 fragmentation 目录下的 shielding_field.json。
 */
public class ShieldingFieldConfig {

    // ===== 三层容量（焦耳） =====
    /** 负层（最内层）吸收动能上限，默认 30,000 J */
    public double negativeLayerCapacityJoules = 30000.0D;
    /** 主层（中层）吸收动能上限，默认 100,000 J */
    public double mainLayerCapacityJoules = 100000.0D;
    /** 次层（最外层）动能削减比例，默认 15% */
    public double secondaryLayerReduction = 0.15D;

    // ===== 主层降级机制 =====
    /** 主层负载达到该比例后进入降级状态（只吸收部分动能），默认 80% */
    public double mainDegradedThreshold = 0.80D;
    /** 降级状态下主层吸收子弹当前动能的比例，默认 75% */
    public double mainDegradedAbsorbRatio = 0.75D;
    /** 视觉效果变红阈值（主层负载比例），默认 75% */
    public double mainLoadVisualThreshold = 0.75D;

    // ===== 过载机制 =====
    /** 主层碎除后负层过载容量倍率，默认 2.0（即上限的 200%） */
    public double overloadCapacityMultiplier = 2.0D;
    /** 过载持续时间（秒），默认 180 秒（3 分钟） */
    public int overloadDurationSeconds = 180;
    /** 过载结束后负层负载系数（降至当前负载的比例），默认 50% */
    public double overloadEndLoadFactor = 0.5D;

    // ===== 冷却与恢复 =====
    /** 屏蔽场完全失效后的冷却时间（秒），默认 900 秒（15 分钟） */
    public int cooldownDurationSeconds = 900;
    /** 最后一次被击中后开始恢复所需的无受击时间（秒），默认 15 秒 */
    public int recoveryDelaySeconds = 15;

    /** 冷却后重建速率：负层每秒恢复上限比例，默认 2% */
    public double rebuildRateNegative = 0.02D;
    /** 冷却后重建速率：主层每秒恢复上限比例，默认 5% */
    public double rebuildRateMain = 0.05D;
    /** 冷却后重建速率：次层每秒恢复完整度比例，默认 7% */
    public double rebuildRateSecondary = 0.07D;

    /** 战斗被动恢复速率（运行中、无受击延迟后）：负层每秒 7% */
    public double combatRecoveryRateNegative = 0.07D;
    /** 战斗被动恢复速率：主层每秒 1% */
    public double combatRecoveryRateMain = 0.01D;

    /** 重建期间主层负载高于该比例时子弹正常穿透，默认 75% */
    public double recoveryPenetrationThreshold = 0.75D;

    // ===== 视觉 =====
    /** 屏蔽场视觉半径（格），默认 1.5 */
    public double visualRadius = 1.5D;
    /** 高速/低速判定阈值（m/s）：高于此值为高速（冷蓝闪光），低于为低速（接触点转红），默认 100 */
    public double fastSpeedThresholdMps = 100.0D;

    // ===== 激活物品 =====
    /** 屏蔽场显示名称（tooltip 标题），可自定义 */
    public String displayName = "霍尔茨曼屏蔽场";

    /** 装备后激活屏蔽场的物品 ID 列表（盔甲槽/主副手检测） */
    public List<String> activatorItems = new ArrayList<>();

    private static ShieldingFieldConfig instance;

    public static ShieldingFieldConfig getInstance() {
        if (instance == null) {
            instance = new ShieldingFieldConfig();
        }
        return instance;
    }

    public static void reload() {
        instance = new ShieldingFieldConfig();
        instance.loadConfig();
    }

    public boolean isActivatorItem(String itemId) {
        if (itemId == null) return false;
        for (String id : activatorItems) {
            if (itemId.equals(id)) return true;
        }
        return false;
    }

    public boolean addActivatorItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) return false;
        if (isActivatorItem(itemId)) return false;
        activatorItems.add(itemId);
        saveConfig();
        return true;
    }

    public boolean removeActivatorItem(String itemId) {
        boolean removed = activatorItems.removeIf(id -> id.equals(itemId));
        if (removed) saveConfig();
        return removed;
    }

    public void saveConfig() {
        try {
            String path = WorldConfigManager.getShieldingFieldPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ShieldingField] saveConfig failed", e);
        }
    }

    public void loadConfig() {
        try {
            String path = WorldConfigManager.getShieldingFieldPath();
            File file = new File(path);
            if (!file.exists()) {
                saveConfig();
                return;
            }
            Gson gson = new Gson();
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                ShieldingFieldConfig loaded = gson.fromJson(root, ShieldingFieldConfig.class);
                if (loaded != null) {
                    copyFrom(loaded);
                }
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ShieldingField] loadConfig failed, keeping defaults", e);
        }
    }

    private void copyFrom(ShieldingFieldConfig o) {
        // 旧档迁移：恰好等于旧默认容量（负层 10000 / 主层 50000）的世界自动升级为新初始值
        this.negativeLayerCapacityJoules = migrateCapacity(
            o.negativeLayerCapacityJoules, 10000.0D, 30000.0D);
        this.mainLayerCapacityJoules = migrateCapacity(
            o.mainLayerCapacityJoules, 50000.0D, 100000.0D);
        this.secondaryLayerReduction = clamp01(o.secondaryLayerReduction, 0.15D);
        this.mainDegradedThreshold = clamp01(o.mainDegradedThreshold, 0.80D);
        this.mainDegradedAbsorbRatio = clamp01(o.mainDegradedAbsorbRatio, 0.75D);
        this.mainLoadVisualThreshold = clamp01(o.mainLoadVisualThreshold, 0.75D);
        this.overloadCapacityMultiplier = positiveOrDefault(o.overloadCapacityMultiplier, 2.0D);
        this.overloadDurationSeconds = Math.max(1, o.overloadDurationSeconds);
        this.overloadEndLoadFactor = clamp01(o.overloadEndLoadFactor, 0.5D);
        this.cooldownDurationSeconds = Math.max(1, o.cooldownDurationSeconds);
        this.recoveryDelaySeconds = Math.max(0, o.recoveryDelaySeconds);
        this.rebuildRateNegative = positiveOrDefault(o.rebuildRateNegative, 0.02D);
        this.rebuildRateMain = positiveOrDefault(o.rebuildRateMain, 0.05D);
        this.rebuildRateSecondary = positiveOrDefault(o.rebuildRateSecondary, 0.07D);
        this.combatRecoveryRateNegative = positiveOrDefault(o.combatRecoveryRateNegative, 0.07D);
        this.combatRecoveryRateMain = positiveOrDefault(o.combatRecoveryRateMain, 0.01D);
        this.recoveryPenetrationThreshold = clamp01(o.recoveryPenetrationThreshold, 0.75D);
        this.visualRadius = positiveOrDefault(o.visualRadius, 1.5D);
        this.fastSpeedThresholdMps = positiveOrDefault(o.fastSpeedThresholdMps, 100.0D);
        this.displayName = (o.displayName != null && !o.displayName.isBlank())
            ? o.displayName : "霍尔茨曼屏蔽场";
        this.activatorItems = o.activatorItems != null ? o.activatorItems : new ArrayList<>();
    }

    private static double positiveOrDefault(double v, double def) {
        return Double.isFinite(v) && v > 0.0D ? v : def;
    }

    /** 旧默认容量自动升级：读取值恰好为旧默认值时替换为新初始容量，自定义值保持不变 */
    private static double migrateCapacity(double v, double oldDefault, double newDefault) {
        if (!Double.isFinite(v) || v <= 0.0D) return newDefault;
        return Math.abs(v - oldDefault) < 1.0E-6D ? newDefault : v;
    }

    private static double clamp01(double v, double def) {
        if (!Double.isFinite(v)) return def;
        return Math.max(0.0D, Math.min(1.0D, v));
    }
}
