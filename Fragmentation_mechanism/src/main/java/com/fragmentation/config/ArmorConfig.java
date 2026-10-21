package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ArmorConfig {
    
    private List<ArmorEntry> armors = new ArrayList<>();
    
    public ArmorConfig() {
    }
    
    public List<ArmorEntry> getArmors() {
        return armors;
    }
    
    public ArmorEntry getArmorForItem(ItemStack stack) {
        if (stack.isEmpty()) return null;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;

        String key = itemId.toString();
        CompoundTag itemTag = stack.getTag();

        // 带NBT过滤的条目优先（更具体）；无过滤条目作为同ID物品的通用回退。
        // 这样同一注册ID的不同NBT变体（如 transmog 外观不同的头盔）可以分别配置。
        ArmorEntry unfilteredFallback = null;
        for (ArmorEntry entry : armors) {
            if (!key.equals(entry.itemId)) continue;
            if (!hasNbtFilter(entry)) {
                if (unfilteredFallback == null) {
                    unfilteredFallback = entry;
                }
                continue;
            }
            if (matchesNbtFilter(entry, itemTag)) {
                return entry;
            }
        }
        return unfilteredFallback;
    }
    
    public ArmorEntry getArmorById(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        
        for (ArmorEntry entry : armors) {
            if (itemId.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }
    
    public boolean addArmor(ItemStack stack, int armorLevel, int maxDurability) {
        ArmorEntry existing = getArmorForItem(stack);
        double ricochetCoefficient = existing != null
            ? existing.ricochetCoefficient
            : 0.0D;
        return addArmor(
            stack,
            armorLevel,
            maxDurability,
            ricochetCoefficient
        );
    }

    public boolean addArmor(
        ItemStack stack,
        int armorLevel,
        int maxDurability,
        double ricochetCoefficient
    ) {
        if (stack.isEmpty()) return false;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;

        String key = itemId.toString();
        CompoundTag itemTag = stack.getTag();
        String variantFilter = deriveNbtFilter(itemTag);

        for (ArmorEntry existing : armors) {
            if (!key.equals(existing.itemId)) continue;
            if (hasNbtFilter(existing)) {
                // 已存在的NBT变体条目：仅当手持物品NBT匹配时才更新它
                if (matchesNbtFilter(existing, itemTag)) {
                    existing.armorLevel = armorLevel;
                    existing.maxDurability = maxDurability;
                    existing.itemName = stack.getDisplayName().getString();
                    existing.ricochetCoefficient = clampUnit(ricochetCoefficient);
                    return true;
                }
            } else if (variantFilter == null) {
                // 无过滤条目只接收"无外观变体特征"的物品，
                // 避免把不同变体（transmog）误当成同一种护甲覆盖配置
                existing.armorLevel = armorLevel;
                existing.maxDurability = maxDurability;
                existing.itemName = stack.getDisplayName().getString();
                existing.ricochetCoefficient = clampUnit(ricochetCoefficient);
                return true;
            }
        }

        ArmorEntry entry = new ArmorEntry();
        entry.itemId = key;
        entry.itemName = stack.getDisplayName().getString();
        entry.armorLevel = armorLevel;
        entry.maxDurability = maxDurability;
        entry.ricochetCoefficient = clampUnit(ricochetCoefficient);
        entry.nbt = variantFilter;

        armors.add(entry);
        return true;
    }
    
    public boolean addArmorById(String itemId, int armorLevel, int maxDurability) {
        if (itemId == null || itemId.isEmpty()) return false;
        
        for (ArmorEntry existing : armors) {
            if (itemId.equals(existing.itemId)) {
                existing.armorLevel = armorLevel;
                existing.maxDurability = maxDurability;
                return true;
            }
        }
        
        ArmorEntry entry = new ArmorEntry();
        entry.itemId = itemId;
        entry.itemName = itemId;
        entry.armorLevel = armorLevel;
        entry.maxDurability = maxDurability;
        
        armors.add(entry);
        return true;
    }
    
    public boolean removeArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;

        // NBT感知移除：只移除与手持物品（含变体）匹配的那一条
        ArmorEntry entry = getArmorForItem(stack);
        return entry != null && armors.remove(entry);
    }
    
    public boolean removeArmor(String itemId) {
        for (int i = 0; i < armors.size(); i++) {
            if (itemId.equals(armors.get(i).itemId)) {
                armors.remove(i);
                return true;
            }
        }
        return false;
    }
    
    public void updateArmor(ItemStack stack, int armorLevel, int maxDurability) {
        ArmorEntry entry = getArmorForItem(stack);
        if (entry != null) {
            entry.armorLevel = armorLevel;
            entry.maxDurability = maxDurability;
        }
    }

    /**
     * 从物品NBT中提取稳定的"变体指纹"作为匹配过滤器。
     * 目前只提取 transmog:transmogItem.id（外观变换目标物品ID），
     * 不包含 Damage/耐久等会随游戏进程变化的字段。
     */
    private static String deriveNbtFilter(CompoundTag tag) {
        if (tag == null) return null;
        String transmogKey = "transmog:transmogItem";
        if (tag.contains(transmogKey, Tag.TAG_COMPOUND)) {
            CompoundTag transmog = tag.getCompound(transmogKey);
            if (transmog.contains("id", Tag.TAG_STRING)) {
                CompoundTag inner = new CompoundTag();
                inner.putString("id", transmog.getString("id"));
                CompoundTag filter = new CompoundTag();
                filter.put(transmogKey, inner);
                // CompoundTag.toString() 输出合法SNBT（自动为含冒号的键加引号）
                return filter.toString();
            }
        }
        return null;
    }

    private static boolean hasNbtFilter(ArmorEntry entry) {
        return entry.nbt != null && !entry.nbt.isBlank();
    }

    private static boolean matchesNbtFilter(ArmorEntry entry, CompoundTag itemTag) {
        if (!hasNbtFilter(entry)) return true;
        if (itemTag == null) return false;
        try {
            CompoundTag filter = TagParser.parseTag(entry.nbt);
            return nbtSubset(filter, itemTag);
        } catch (Exception e) {
            // 过滤器解析失败时按无过滤处理，保持旧行为
            return true;
        }
    }

    /** 判断 filter 是否为 tag 的子集（递归比较嵌套复合标签）。 */
    private static boolean nbtSubset(CompoundTag filter, CompoundTag tag) {
        for (String key : filter.getAllKeys()) {
            if (!tag.contains(key)) return false;
            Tag fv = filter.get(key);
            Tag tv = tag.get(key);
            if (fv instanceof CompoundTag fc && tv instanceof CompoundTag tc) {
                if (!nbtSubset(fc, tc)) return false;
            } else if (!nbtValueEquals(fv, tv)) {
                return false;
            }
        }
        return true;
    }

    private static boolean nbtValueEquals(Tag a, Tag b) {
        if (a == null || b == null) return false;
        try {
            return a.getAsString().equals(b.getAsString());
        } catch (UnsupportedOperationException e) {
            return a.toString().equals(b.toString());
        }
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getArmorsPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            
            FileWriter writer = new FileWriter(file);
            gson.toJson(armors, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void loadConfig() {
        try {
            String path = WorldConfigManager.getArmorsPath();
            File file = new File(path);
            if (!file.exists()) {
                saveConfig();
                return;
            }
            
            Gson gson = new Gson();
            boolean needsSchemaMigration = false;
            
            try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                needsSchemaMigration = root.isJsonArray()
                    && root.getAsJsonArray().asList().stream()
                        .filter(JsonElement::isJsonObject)
                        .anyMatch(element ->
                            !element.getAsJsonObject().has(
                                "ricochetCoefficient"
                            )
                        );
                List<ArmorEntry> loaded = gson.fromJson(
                    root,
                    new com.google.gson.reflect.TypeToken<List<ArmorEntry>>() { }
                        .getType()
                );
                
                if (loaded != null) {
                    loaded.removeIf(java.util.Objects::isNull);
                    for (ArmorEntry entry : loaded) {
                        entry.ricochetCoefficient = clampUnit(
                            entry.ricochetCoefficient
                        );
                    }
                    armors.clear();
                    armors.addAll(loaded);
                } else {
                    FragmentationMod.LOGGER.warn("[ArmorConfig] Loaded config is null, keeping current data");
                }
            }
            if (needsSchemaMigration) {
                saveConfig();
                FragmentationMod.LOGGER.info(
                    "[ArmorConfig] Added ricochetCoefficient to existing entries"
                );
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[ArmorConfig] JSON parse error, keeping current data: " + e.getMessage());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ArmorConfig] Load error, keeping current data: " + e.getMessage());
        }
    }

    private static double clampUnit(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }
    
    public static class ArmorEntry {
        public String itemId;
        public String itemName;
        public int armorLevel;
        public int maxDurability;
        public String material;
        public double ricochetCoefficient;
        /** 可选的NBT过滤条件（SNBT格式）。非空时仅匹配NBT包含该子结构的物品（用于区分同ID不同变体）。 */
        public String nbt;
        
        public ArmorEntry() {
            this.material = "steel";
            this.ricochetCoefficient = 0.0D;
        }
        
        public String getDisplayName() {
            return itemName != null ? itemName : (itemId != null ? itemId : "unknown");
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
        
        public int getPenetrationThreshold() {
            return armorLevel * 10;
        }
        
        public String getMaterial() {
            return material != null ? material : "steel";
        }
    }
}
