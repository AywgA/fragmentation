package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class PenetrationConfig {
    
    private Map<String, PenetrationEntry> entries = new HashMap<>();
    
    public PenetrationConfig() {
    }
    
    public PenetrationEntry getPenetration(int bulletLevel, int armorLevel) {
        bulletLevel = Math.min(Math.max(bulletLevel, 1), 110);
        armorLevel = Math.min(Math.max(armorLevel, 1), 11);
        String key = bulletLevel + "_" + armorLevel;
        return entries.get(key);
    }
    
    public Map<String, PenetrationEntry> getEntries() {
        return entries;
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getPenetrationPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(PenetrationEntry.class, new PenetrationEntrySerializer())
                .create();
            
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                gson.toJson(entries, writer);
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[PenetrationConfig] Save error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void loadConfig() {
        try {
            String path = WorldConfigManager.getPenetrationPath();
            File file = new File(path);
            if (!file.exists()) {
                generateDefaultConfig();
                saveConfig();
                return;
            }
            
            Gson gson = new GsonBuilder()
                .registerTypeAdapter(PenetrationEntry.class, new PenetrationEntryDeserializer())
                .create();
            
            try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                Map<String, PenetrationEntry> loaded = gson.fromJson(reader, new TypeToken<Map<String, PenetrationEntry>>(){}.getType());
                
                if (loaded != null) {
                    entries = loaded;
                } else {
                    FragmentationMod.LOGGER.warn("[PenetrationConfig] Loaded config is null, keeping current data");
                }
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            FragmentationMod.LOGGER.error("[PenetrationConfig] JSON parse error, keeping current data: " + e.getMessage());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[PenetrationConfig] Load error, keeping current data: " + e.getMessage());
        }
    }
    
    private void generateDefaultConfig() {
        entries.clear();
        
        int[] bulletLevels = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110};
        int[] armorLevels = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        
        for (int bLevel : bulletLevels) {
            for (int aLevel : armorLevels) {
                int armorThresholdHigh = aLevel * 10 + 20;
                int armorThresholdLow = aLevel * 10 - 10;
                
                double damageMultiplier;
                double armorDamageMultiplier;
                double penetrationChance;
                
                if (bLevel >= armorThresholdHigh) {
                    penetrationChance = 1.0;
                    float reductionPerLevel = 0.04f;
                    damageMultiplier = Math.max(0.4, Math.min(0.6, 1.0 - aLevel * reductionPerLevel));
                    armorDamageMultiplier = 0.5 + Math.random() * 0.5;
                } else if (bLevel <= armorThresholdLow) {
                    penetrationChance = 0.0;
                    damageMultiplier = 0.01;
                    armorDamageMultiplier = 1.5 + Math.random() * 1.5;
                } else {
                    penetrationChance = (double) (bLevel - aLevel * 10) / 20.0;
                    float reductionPerLevel = 0.04f;
                    damageMultiplier = Math.max(0.4, Math.min(0.6, 1.0 - aLevel * reductionPerLevel));
                    armorDamageMultiplier = 1.0 + Math.random() * 1.0;
                }
                
                String key = bLevel + "_" + aLevel;
                entries.put(key, new PenetrationEntry(bLevel, aLevel, damageMultiplier, armorDamageMultiplier, penetrationChance));
            }
        }
    }
    
    public static class PenetrationEntry {
        public int bulletLevel;
        public int armorLevel;
        public double damageMultiplier;
        public double armorDamageMultiplier;
        public double penetrationChance;
        
        public PenetrationEntry(int bulletLevel, int armorLevel, 
                                double damageMultiplier, double armorDamageMultiplier, 
                                double penetrationChance) {
            this.bulletLevel = bulletLevel;
            this.armorLevel = armorLevel;
            this.damageMultiplier = damageMultiplier;
            this.armorDamageMultiplier = armorDamageMultiplier;
            this.penetrationChance = penetrationChance;
        }
    }
    
    private static class PenetrationEntrySerializer implements JsonSerializer<PenetrationEntry> {
        @Override
        public JsonElement serialize(PenetrationEntry entry, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("bulletLevel", entry.bulletLevel);
            obj.addProperty("armorLevel", entry.armorLevel);
            obj.addProperty("damageMultiplier", entry.damageMultiplier);
            obj.addProperty("armorDamageMultiplier", entry.armorDamageMultiplier);
            obj.addProperty("penetrationChance", entry.penetrationChance);
            return obj;
        }
    }
    
    private static class PenetrationEntryDeserializer implements JsonDeserializer<PenetrationEntry> {
        @Override
        public PenetrationEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            int bulletLevel = obj.has("bulletLevel") ? obj.get("bulletLevel").getAsInt() : 1;
            int armorLevel = obj.has("armorLevel") ? obj.get("armorLevel").getAsInt() : 1;
            double damageMultiplier = obj.has("damageMultiplier") ? obj.get("damageMultiplier").getAsDouble() : 1.0;
            double armorDamageMultiplier = obj.has("armorDamageMultiplier") ? obj.get("armorDamageMultiplier").getAsDouble() : 1.0;
            double penetrationChance = obj.has("penetrationChance") ? obj.get("penetrationChance").getAsDouble() : 0.5;
            return new PenetrationEntry(bulletLevel, armorLevel, damageMultiplier, armorDamageMultiplier, penetrationChance);
        }
    }
}