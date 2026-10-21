package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class ArmorMaterialConfig {
    
    public enum MaterialType {
        CERAMIC,
        STEEL,
        ALUMINUM,
        TITANIUM,
        UHMWPE,
        KEVLAR
    }
    
    private Map<String, MaterialEntry> materials = new HashMap<>();
    
    public ArmorMaterialConfig() {
    }
    
    public Map<String, MaterialEntry> getMaterials() {
        return materials;
    }
    
    public MaterialEntry getMaterial(String materialName) {
        if (materialName == null || materialName.isEmpty()) return null;
        return materials.get(materialName.toLowerCase());
    }
    
    public MaterialEntry getMaterial(MaterialType type) {
        return materials.get(type.name().toLowerCase());
    }
    
    public void addMaterial(String name, float damageAbsorptionRatio, float armorDamageRatio) {
        materials.put(name.toLowerCase(), new MaterialEntry(name, damageAbsorptionRatio, armorDamageRatio));
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getArmorMaterialsPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                gson.toJson(this, writer);
            }
        } catch (IOException e) {
            FragmentationMod.LOGGER.error("[ArmorMaterialConfig] Save error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static ArmorMaterialConfig loadConfig() {
        try {
            String path = WorldConfigManager.getArmorMaterialsPath();
            File file = new File(path);
            
            if (file.exists()) {
                try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    Gson gson = new Gson();
                    return gson.fromJson(reader, ArmorMaterialConfig.class);
                }
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[ArmorMaterialConfig] JSON parse error, using defaults");
        } catch (IOException e) {
            FragmentationMod.LOGGER.error("[ArmorMaterialConfig] Load error, using defaults: " + e.getMessage());
            e.printStackTrace();
        }
        
        ArmorMaterialConfig config = new ArmorMaterialConfig();
        config.generateDefaultConfig();
        return config;
    }
    
    private void generateDefaultConfig() {
        materials.clear();
        
        materials.put("ceramic", new MaterialEntry("ceramic", 1.7f, 1.9f));
        materials.put("steel", new MaterialEntry("steel", 1.0f, 1.0f));
        materials.put("aluminum", new MaterialEntry("aluminum", 1.1f, 1.2f));
        materials.put("titanium", new MaterialEntry("titanium", 1.05f, 1.1f));
        materials.put("uhmwpe", new MaterialEntry("uhmwpe", 1.1f, 0.8f));
        materials.put("kevlar", new MaterialEntry("kevlar", 1.2f, 0.85f));
    }
    
    public static class MaterialEntry {
        public String name;
        public float damageAbsorptionRatio;
        public float armorDamageRatio;
        
        public MaterialEntry() {
        }
        
        public MaterialEntry(String name, float damageAbsorptionRatio, float armorDamageRatio) {
            this.name = name;
            this.damageAbsorptionRatio = damageAbsorptionRatio;
            this.armorDamageRatio = armorDamageRatio;
        }
        
        public float applyDamageReduction(float damage) {
            return damage / damageAbsorptionRatio;
        }
        
        public float applyArmorDamageMultiplier(float armorDamage) {
            return armorDamage * armorDamageRatio;
        }
    }
}