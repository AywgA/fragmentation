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

public class ShieldConfig {
    
    private List<ShieldEntry> shields = new ArrayList<>();
    
    public ShieldConfig() {
    }
    
    public List<ShieldEntry> getShields() {
        return shields;
    }
    
    public ShieldEntry getShieldForItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;
        
        String key = itemId.toString();
        for (ShieldEntry entry : shields) {
            if (key.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }
    
    public ShieldEntry getShieldById(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        
        for (ShieldEntry entry : shields) {
            if (itemId.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }
    
    public boolean addShield(ItemStack stack, int armorLevel, int maxDurability) {
        ShieldEntry existing = getShieldForItem(stack);
        double ricochetCoefficient = existing != null
            ? existing.ricochetCoefficient
            : 0.0D;
        return addShield(
            stack,
            armorLevel,
            maxDurability,
            ricochetCoefficient
        );
    }

    public boolean addShield(
        ItemStack stack,
        int armorLevel,
        int maxDurability,
        double ricochetCoefficient
    ) {
        if (stack.isEmpty()) return false;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        
        String key = itemId.toString();
        
        for (ShieldEntry existing : shields) {
            if (key.equals(existing.itemId)) {
                existing.armorLevel = armorLevel;
                existing.maxDurability = maxDurability;
                existing.itemName = stack.getDisplayName().getString();
                existing.ricochetCoefficient = clampUnit(ricochetCoefficient);
                return true;
            }
        }
        
        ShieldEntry entry = new ShieldEntry();
        entry.itemId = key;
        entry.itemName = stack.getDisplayName().getString();
        entry.armorLevel = armorLevel;
        entry.maxDurability = maxDurability;
        entry.ricochetCoefficient = clampUnit(ricochetCoefficient);
        
        shields.add(entry);
        return true;
    }
    
    public boolean addShieldById(String itemId, int armorLevel, int maxDurability) {
        if (itemId == null || itemId.isEmpty()) return false;
        
        for (ShieldEntry existing : shields) {
            if (itemId.equals(existing.itemId)) {
                existing.armorLevel = armorLevel;
                existing.maxDurability = maxDurability;
                return true;
            }
        }
        
        ShieldEntry entry = new ShieldEntry();
        entry.itemId = itemId;
        entry.itemName = itemId;
        entry.armorLevel = armorLevel;
        entry.maxDurability = maxDurability;
        
        shields.add(entry);
        return true;
    }
    
    public boolean removeShield(ItemStack stack) {
        if (stack.isEmpty()) return false;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        
        return removeShield(itemId.toString());
    }
    
    public boolean removeShield(String itemId) {
        for (int i = 0; i < shields.size(); i++) {
            if (itemId.equals(shields.get(i).itemId)) {
                shields.remove(i);
                return true;
            }
        }
        return false;
    }
    
    public void updateShield(ItemStack stack, int armorLevel, int maxDurability) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return;
        
        String key = itemId.toString();
        for (ShieldEntry entry : shields) {
            if (key.equals(entry.itemId)) {
                entry.armorLevel = armorLevel;
                entry.maxDurability = maxDurability;
                return;
            }
        }
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getShieldsPath();
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
            gson.toJson(shields, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void loadConfig() {
        try {
            String path = WorldConfigManager.getShieldsPath();
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
                List<ShieldEntry> loaded = gson.fromJson(
                    root,
                    new com.google.gson.reflect.TypeToken<List<ShieldEntry>>() { }
                        .getType()
                );
                
                if (loaded != null) {
                    loaded.removeIf(java.util.Objects::isNull);
                    for (ShieldEntry entry : loaded) {
                        entry.ricochetCoefficient = clampUnit(
                            entry.ricochetCoefficient
                        );
                    }
                    shields.clear();
                    shields.addAll(loaded);
                } else {
                    FragmentationMod.LOGGER.warn("[ShieldConfig] Loaded config is null, keeping current data");
                }
            }
            if (needsSchemaMigration) {
                saveConfig();
                FragmentationMod.LOGGER.info(
                    "[ShieldConfig] Added ricochetCoefficient to existing entries"
                );
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[ShieldConfig] JSON parse error, keeping current data: " + e.getMessage());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[ShieldConfig] Load error, keeping current data: " + e.getMessage());
        }
    }

    private static double clampUnit(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }
    
    public static class ShieldEntry {
        public String itemId;
        public String itemName;
        public int armorLevel;
        public int maxDurability;
        public String material;
        public double ricochetCoefficient;
        
        public ShieldEntry() {
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
