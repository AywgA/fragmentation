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

public class PlateConfig {
    
    private List<PlateEntry> plates = new ArrayList<>();
    
    public PlateConfig() {
    }
    
    public List<PlateEntry> getPlates() {
        return plates;
    }
    
    public PlateEntry getPlateForItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return null;
        
        String key = itemId.toString();
        for (PlateEntry entry : plates) {
            if (key.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }
    
    public PlateEntry getPlateById(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        
        for (PlateEntry entry : plates) {
            if (itemId.equals(entry.itemId)) {
                return entry;
            }
        }
        return null;
    }
    
    public boolean addPlate(ItemStack stack, int plateLevel, int maxDurability) {
        return addPlate(stack, plateLevel, maxDurability, "ceramic");
    }
    
    public boolean addPlate(ItemStack stack, int plateLevel, int maxDurability, String material) {
        PlateEntry existing = getPlateForItem(stack);
        double ricochetCoefficient = existing != null
            ? existing.ricochetCoefficient
            : 0.0D;
        return addPlate(
            stack,
            plateLevel,
            maxDurability,
            material,
            ricochetCoefficient
        );
    }

    public boolean addPlate(
        ItemStack stack,
        int plateLevel,
        int maxDurability,
        String material,
        double ricochetCoefficient
    ) {
        if (stack.isEmpty()) return false;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        
        String key = itemId.toString();
        
        for (PlateEntry existing : plates) {
            if (key.equals(existing.itemId)) {
                existing.plateLevel = Math.max(1, Math.min(6, plateLevel));
                existing.maxDurability = maxDurability;
                existing.itemName = stack.getDisplayName().getString();
                if (material != null && !material.isEmpty()) {
                    existing.material = material;
                }
                existing.ricochetCoefficient = clampUnit(ricochetCoefficient);
                return true;
            }
        }
        
        PlateEntry entry = new PlateEntry();
        entry.itemId = key;
        entry.itemName = stack.getDisplayName().getString();
        entry.plateLevel = Math.max(1, Math.min(6, plateLevel));
        entry.maxDurability = maxDurability;
        entry.material = material != null && !material.isEmpty() ? material : "ceramic";
        entry.ricochetCoefficient = clampUnit(ricochetCoefficient);
        
        plates.add(entry);
        return true;
    }
    
    public boolean addPlateById(String itemId, int plateLevel, int maxDurability) {
        return addPlateById(itemId, plateLevel, maxDurability, "ceramic");
    }
    
    public boolean addPlateById(String itemId, int plateLevel, int maxDurability, String material) {
        if (itemId == null || itemId.isEmpty()) return false;
        
        for (PlateEntry existing : plates) {
            if (itemId.equals(existing.itemId)) {
                existing.plateLevel = Math.max(1, Math.min(6, plateLevel));
                existing.maxDurability = maxDurability;
                if (material != null && !material.isEmpty()) {
                    existing.material = material;
                }
                return true;
            }
        }
        
        PlateEntry entry = new PlateEntry();
        entry.itemId = itemId;
        entry.itemName = itemId;
        entry.plateLevel = Math.max(1, Math.min(6, plateLevel));
        entry.maxDurability = maxDurability;
        entry.material = material != null && !material.isEmpty() ? material : "ceramic";
        
        plates.add(entry);
        return true;
    }
    
    public boolean removePlate(ItemStack stack) {
        if (stack.isEmpty()) return false;
        
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        
        return removePlate(itemId.toString());
    }
    
    public boolean removePlate(String itemId) {
        for (int i = 0; i < plates.size(); i++) {
            if (itemId.equals(plates.get(i).itemId)) {
                plates.remove(i);
                return true;
            }
        }
        return false;
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getPlatesPath();
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
            gson.toJson(plates, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void loadConfig() {
        try {
            String path = WorldConfigManager.getPlatesPath();
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
                List<PlateEntry> loaded = gson.fromJson(
                    root,
                    new com.google.gson.reflect.TypeToken<List<PlateEntry>>() { }
                        .getType()
                );
                
                if (loaded != null) {
                    plates.clear();
                    for (PlateEntry entry : loaded) {
                        if (entry == null) continue;
                        entry.plateLevel = Math.max(1, Math.min(6, entry.plateLevel));
                        entry.ricochetCoefficient = clampUnit(
                            entry.ricochetCoefficient
                        );
                        plates.add(entry);
                    }
                } else {
                    FragmentationMod.LOGGER.warn("[PlateConfig] Loaded config is null, keeping current data");
                }
            }
            if (needsSchemaMigration) {
                saveConfig();
                FragmentationMod.LOGGER.info(
                    "[PlateConfig] Added ricochetCoefficient to existing entries"
                );
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[PlateConfig] JSON parse error, keeping current data: " + e.getMessage());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[PlateConfig] Load error, keeping current data: " + e.getMessage());
        }
    }

    private static double clampUnit(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }
    
    public static class PlateEntry {
        public String itemId;
        public String itemName;
        public int plateLevel;
        public int maxDurability;
        public String material;
        public double ricochetCoefficient;
        
        public PlateEntry() {
            plateLevel = 1;
            material = "ceramic";
            ricochetCoefficient = 0.0D;
        }
        
        public String getDisplayName() {
            return itemName != null ? itemName : (itemId != null ? itemId : "unknown");
        }
        
        public String getMaterial() {
            return material != null ? material : "ceramic";
        }
    }
}
