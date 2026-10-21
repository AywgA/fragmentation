package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class BulletConfig {
    public static final float MIN_BULLET_SPEED = 0.0f;
    public static final float MAX_BULLET_SPEED = 5000.0f;
    public static final float MIN_MOA_MODIFIER = -100.0f;
    public static final float MAX_MOA_MODIFIER = 100.0f;
    
    private List<BulletEntry> bullets = new ArrayList<>();
    
    public BulletConfig() {
    }
    
    public List<BulletEntry> getBullets() {
        return bullets;
    }
    
    public BulletEntry getBulletForItem(ItemStack stack) {
        if (stack.isEmpty()) return null;

        String taczAmmoId = getTaczAmmoId(stack);
        if (taczAmmoId != null) {
            // 带 AmmoId 的弹药（如 TACZ 子弹）只允许按 AmmoId 精确匹配。
            // 禁止回退到注册物品 ID：tacz:ammo 是所有 TACZ 弹药共用的注册 ID，
            // 回退会导致不同弹药互相误判（如 tacz:9mm 命中 eft:9x19_pso 的条目）。
            BulletEntry exact = getBulletByExactTaczAmmoId(taczAmmoId);
            if (exact != null) return exact;
            // 兼容手工配置：条目只写了 itemId=弹药ID 的情况（仍为精确匹配）
            return getBulletById(taczAmmoId);
        }

        // 无 AmmoId 的普通物品：仅按注册物品 ID 精确匹配
        return getBulletById(getItemId(stack));
    }

    public String getTaczAmmoId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null) return null;

        String direct = firstNonBlank(
            tag.getString("AmmoId"),
            tag.getString("ammoId")
        );
        if (direct != null) return direct;

        if (tag.contains("Extras")) {
            net.minecraft.nbt.CompoundTag extras = tag.getCompound("Extras");
            String extrasId = firstNonBlank(
                extras.getString("ExtraAmmo"),
                extras.getString("AmmoId"),
                extras.getString("ammoId")
            );
            if (extrasId != null) return extrasId;
        }
        return null;
    }
    
    public BulletEntry getBulletById(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        
        for (BulletEntry entry : bullets) {
            if (itemId.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }

    public Float getBulletSpeedOverride(String ammoId) {
        BulletEntry entry = getBulletByAmmoId(ammoId);
        return entry != null && entry.bulletSpeed != null
            ? clampBulletSpeed(entry.bulletSpeed)
            : null;
    }

    public float getBulletSpeed(String ammoId, float fallbackSpeed) {
        Float configured = getBulletSpeedOverride(ammoId);
        return configured != null ? configured : clampBulletSpeed(fallbackSpeed);
    }

    public boolean setBulletSpeed(String ammoId, float speedMetersPerSecond) {
        BulletEntry entry = getBulletByAmmoId(ammoId);
        if (entry == null) return false;
        entry.bulletSpeed = clampBulletSpeed(speedMetersPerSecond);
        saveConfig();
        return true;
    }

    private static float clampBulletSpeed(float speed) {
        if (!Float.isFinite(speed)) return MIN_BULLET_SPEED;
        return Math.max(MIN_BULLET_SPEED, Math.min(MAX_BULLET_SPEED, speed));
    }

    public float getMoaModifier(String ammoId) {
        BulletEntry entry = getBulletByAmmoId(ammoId);
        return entry != null ? clampMoaModifier(entry.moaModifier) : 0.0f;
    }

    public boolean setMoaModifier(String ammoId, float moaModifier) {
        BulletEntry entry = getBulletByAmmoId(ammoId);
        if (entry == null) return false;
        entry.moaModifier = clampMoaModifier(moaModifier);
        saveConfig();
        return true;
    }

    private static float clampMoaModifier(float moaModifier) {
        if (!Float.isFinite(moaModifier)) return 0.0f;
        return Math.max(
            MIN_MOA_MODIFIER,
            Math.min(MAX_MOA_MODIFIER, moaModifier)
        );
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private static double clampUnit(double value) {
        return Math.min(1.0D, finiteNonNegative(value));
    }

    public BulletEntry getBulletByTaczAmmoId(String taczAmmoId) {
        if (taczAmmoId == null || taczAmmoId.isEmpty()) return null;

        // 仅精确匹配：taczAmmoId 字段或 itemId 字段与弹药ID完全一致。
        // 不做归一化/前缀剥离/子串等模糊匹配（会导致 eft:9x19_pso 与 tacz:9mm 互相误判）。
        BulletEntry exact = getBulletByExactTaczAmmoId(taczAmmoId);
        if (exact != null) return exact;

        for (BulletEntry entry : bullets) {
            if (taczAmmoId.equals(entry.itemId)) {
                return entry;
            }
        }

        return null;
    }

    private BulletEntry getBulletByExactTaczAmmoId(String taczAmmoId) {
        if (taczAmmoId == null || taczAmmoId.isBlank()) return null;
        for (BulletEntry entry : bullets) {
            if (taczAmmoId.equals(entry.taczAmmoId)) {
                return entry;
            }
        }
        return null;
    }
    
    public BulletEntry getBulletByAmmoId(String ammoId) {
        if (ammoId == null || ammoId.isEmpty()) return null;
        // 仅精确匹配（taczAmmoId 或 itemId 与弹药ID完全一致）
        return getBulletByTaczAmmoId(ammoId);
    }
    
    public boolean addBullet(ItemStack stack, float penetrationLevel, double armorDamage) {
        return addBullet(stack, penetrationLevel, armorDamage, 1.0f);
    }
    
    public boolean addBullet(ItemStack stack, float penetrationLevel, double armorDamage, float baseDamage) {
        if (stack.isEmpty()) return false;

        String taczAmmoId = getTaczAmmoId(stack);
        if (taczAmmoId != null) {
            // TACZ 弹药必须按 AmmoId 建档，禁止并入按注册物品 ID 命名的条目
            // （tacz:ammo 是所有 TACZ 弹药共用的注册 ID，并入会造成跨弹药误判/覆盖）
            return addBulletByTaczAmmoId(
                taczAmmoId,
                penetrationLevel,
                armorDamage,
                baseDamage,
                stack.getDisplayName().getString()
            );
        }

        // 无 AmmoId 的普通自定义子弹物品：按注册物品 ID 建档
        String itemId = getItemId(stack);
        String itemName = stack.getDisplayName().getString();

        BulletEntry existing = getBulletById(itemId);
        if (existing != null) {
            existing.penetrationLevel = penetrationLevel;
            existing.armorDamage = armorDamage;
            existing.baseDamage = baseDamage;
            existing.itemName = itemName;
            return true;
        }

        BulletEntry entry = new BulletEntry();
        entry.itemId = itemId;
        entry.itemName = itemName;
        entry.penetrationLevel = penetrationLevel;
        entry.armorDamage = armorDamage;
        entry.baseDamage = baseDamage;

        bullets.add(entry);
        return true;
    }
    
    public boolean addBulletByTaczAmmoId(String taczAmmoId, float penetrationLevel, double armorDamage) {
        return addBulletByTaczAmmoId(taczAmmoId, penetrationLevel, armorDamage, 1.0f);
    }
    
    public boolean addBulletByTaczAmmoId(String taczAmmoId, float penetrationLevel, double armorDamage, float baseDamage) {
        return addBulletByTaczAmmoId(taczAmmoId, penetrationLevel, armorDamage, baseDamage, null);
    }

    public boolean addBulletByTaczAmmoId(
        String taczAmmoId,
        float penetrationLevel,
        double armorDamage,
        float baseDamage,
        String displayName
    ) {
        if (taczAmmoId == null || taczAmmoId.isEmpty()) return false;

        for (BulletEntry existing : bullets) {
            if (taczAmmoId.equals(existing.taczAmmoId)) {
                existing.penetrationLevel = penetrationLevel;
                existing.armorDamage = armorDamage;
                existing.baseDamage = baseDamage;
                if (displayName != null && !displayName.isBlank()) {
                    existing.itemName = displayName;
                }
                return true;
            }
            if (taczAmmoId.equals(existing.itemId)) {
                existing.taczAmmoId = taczAmmoId;
                existing.penetrationLevel = penetrationLevel;
                existing.armorDamage = armorDamage;
                existing.baseDamage = baseDamage;
                if (displayName != null && !displayName.isBlank()) {
                    existing.itemName = displayName;
                }
                return true;
            }
        }

        BulletEntry entry = new BulletEntry();
        entry.taczAmmoId = taczAmmoId;
        entry.itemId = taczAmmoId;
        entry.itemName = displayName != null && !displayName.isBlank() ? displayName : taczAmmoId;
        entry.penetrationLevel = penetrationLevel;
        entry.armorDamage = armorDamage;
        entry.baseDamage = baseDamage;

        bullets.add(entry);
        return true;
    }
    
    public boolean addBulletById(String itemId, float penetrationLevel, double armorDamage) {
        return addBulletById(itemId, penetrationLevel, armorDamage, 1.0f);
    }
    
    public boolean addBulletById(String itemId, float penetrationLevel, double armorDamage, float baseDamage) {
        if (itemId == null || itemId.isEmpty()) return false;
        
        for (BulletEntry existing : bullets) {
            if (itemId.equals(existing.itemId)) {
                existing.penetrationLevel = penetrationLevel;
                existing.armorDamage = armorDamage;
                existing.baseDamage = baseDamage;
                return true;
            }
        }
        
        BulletEntry entry = new BulletEntry();
        entry.itemId = itemId;
        entry.itemName = itemId;
        entry.penetrationLevel = penetrationLevel;
        entry.armorDamage = armorDamage;
        entry.baseDamage = baseDamage;
        
        bullets.add(entry);
        return true;
    }
    
    public boolean addBulletByAmmoId(String ammoId, float penetrationLevel, double armorDamage) {
        return addBulletByTaczAmmoId(ammoId, penetrationLevel, armorDamage);
    }
    
    public boolean removeBullet(String itemId) {
        for (int i = 0; i < bullets.size(); i++) {
            if (itemId.equals(bullets.get(i).itemId) || itemId.equals(bullets.get(i).taczAmmoId)) {
                bullets.remove(i);
                return true;
            }
        }
        return false;
    }
    
    private String getItemId(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null ? itemId.toString() : "unknown";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getBulletsPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
            
            FileWriter writer = new FileWriter(file);
            gson.toJson(bullets, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void loadConfig() {
        try {
            String path = WorldConfigManager.getBulletsPath();
            File file = new File(path);
            if (!file.exists()) {
                saveConfig();
                return;
            }
            
            Gson gson = new Gson();
            boolean needsBulletSchemaMigration = false;
            
            try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                needsBulletSchemaMigration = root.isJsonArray()
                    && root.getAsJsonArray().asList().stream()
                        .filter(JsonElement::isJsonObject)
                        .anyMatch(element ->
                            !element.getAsJsonObject().has("bulletSpeed")
                                || !element.getAsJsonObject().has("moaModifier")
                                || !element.getAsJsonObject().has("bulletMass")
                                || !element.getAsJsonObject().has("bulletRadius")
                                || !element.getAsJsonObject().has("ricochetRate")
                                || !element.getAsJsonObject().has("fragmentationRate")
                        );
                List<BulletEntry> loaded = gson.fromJson(
                    root,
                    new com.google.gson.reflect.TypeToken<List<BulletEntry>>(){}.getType()
                );
                
                if (loaded != null) {
                    loaded.removeIf(java.util.Objects::isNull);
                    for (BulletEntry entry : loaded) {
                        if (entry.bulletSpeed != null) {
                            entry.bulletSpeed = clampBulletSpeed(entry.bulletSpeed);
                        }
                        entry.moaModifier = clampMoaModifier(entry.moaModifier);
                        entry.bulletMass = finiteNonNegative(entry.bulletMass);
                        entry.bulletRadius = finiteNonNegative(entry.bulletRadius);
                        entry.ricochetRate = clampUnit(entry.ricochetRate);
                        entry.fragmentationRate = clampUnit(entry.fragmentationRate);
                    }
                    // 兼容性迁移：历史版本曾把 TACZ 弹药按注册物品 ID "tacz:ammo" 建档，
                    // 而 tacz:ammo 是所有 TACZ 弹药共用的注册 ID，会导致跨弹药误判
                    // （如 tacz:9mm 命中 eft:9x19_pso 的条目）。统一改写为按 AmmoId 建档。
                    boolean migratedSharedAmmoId = false;
                    for (BulletEntry entry : loaded) {
                        if ("tacz:ammo".equals(entry.itemId)
                            && entry.taczAmmoId != null && !entry.taczAmmoId.isBlank()
                            && !entry.taczAmmoId.equals(entry.itemId)) {
                            entry.itemId = entry.taczAmmoId;
                            migratedSharedAmmoId = true;
                        }
                    }
                    // 迁移后按弹药ID去重（保留首个条目）
                    java.util.Set<String> seenKeys = new java.util.HashSet<>();
                    loaded.removeIf(entry -> {
                        String canonKey = entry.taczAmmoId != null && !entry.taczAmmoId.isBlank()
                            ? entry.taczAmmoId
                            : entry.itemId;
                        if (canonKey == null || canonKey.isBlank()) return false;
                        return !seenKeys.add(canonKey);
                    });
                    if (migratedSharedAmmoId) {
                        FragmentationMod.LOGGER.info(
                            "[BulletConfig] Migrated entries keyed by shared registry id 'tacz:ammo' to their AmmoId"
                        );
                    }
                    bullets.clear();
                    bullets.addAll(loaded);
                    if (migratedSharedAmmoId) {
                        saveConfig();
                    }
                } else {
                    FragmentationMod.LOGGER.warn("[BulletConfig] Loaded config is null, keeping current data");
                }
            }
            if (needsBulletSchemaMigration) {
                saveConfig();
                FragmentationMod.LOGGER.info(
                    "[BulletConfig] Added missing ballistic fields to existing bullet entries"
                );
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[BulletConfig] JSON parse error, keeping current data: " + e.getMessage());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[BulletConfig] Load error, keeping current data: " + e.getMessage());
        }
    }
    
    public static class BulletEntry {
        public String itemId;
        public String taczAmmoId;
        public String itemName;
        public float penetrationLevel;
        public double armorDamage;
        public float baseDamage = 1.0f;
        public Float bulletSpeed;
        public float moaModifier = 0.0f;
        public double bulletMass = 0.0D;
        public double bulletRadius = 0.0D;
        public double ricochetRate = 0.0D;
        public double fragmentationRate = 0.0D;
        
        public BulletEntry() {
        }
        
        public String getDisplayName() {
            return itemName != null ? itemName : (taczAmmoId != null ? taczAmmoId : (itemId != null ? itemId : "unknown"));
        }
        
        public ItemStack getItemStack() {
            if (itemId == null) return ItemStack.EMPTY;
            
            try {
                ResourceLocation rl = new ResourceLocation(itemId);
                Item item = ForgeRegistries.ITEMS.getValue(rl);
                if (item != null) {
                    return new ItemStack(item);
                }
            } catch (Exception e) {
            }
            
            return ItemStack.EMPTY;
        }
    }
}
