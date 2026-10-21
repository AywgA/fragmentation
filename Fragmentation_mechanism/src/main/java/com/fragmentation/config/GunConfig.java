package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class GunConfig {

    public static final float MIN_MOA = 0.0f;
    public static final float MAX_MOA = 1000.0f;
    public static final float DEFAULT_MOA = 0.0f;
    public static final float DEFAULT_HIP_FIRE_MOA = 200.0f;
    public static final String DEFAULT_CALIBER = "未配置";
    public static final float MIN_MUZZLE_VELOCITY = 0.0f;
    public static final float MAX_MUZZLE_VELOCITY = 5000.0f;
    
    public enum PowerLevel {
        EXTREMELY_LOW("极低", 0.70f, 0.75f),
        LOW("低", 0.75f, 0.80f),
        MEDIUM("中", 0.95f, 1.00f),
        HIGH("较高", 1.00f, 1.05f),
        VERY_HIGH("高", 1.05f, 1.10f),
        EXTREMELY_HIGH("极高", 1.10f, 1.15f),
        UNKNOWN("未公布", 1.20f, 1.15f);
        
        private final String displayName;
        private final float damageMultiplier;
        private final float penetrationMultiplier;
        
        PowerLevel(String displayName, float damageMultiplier, float penetrationMultiplier) {
            this.displayName = displayName;
            this.damageMultiplier = damageMultiplier;
            this.penetrationMultiplier = penetrationMultiplier;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public float getDamageMultiplier() {
            return damageMultiplier;
        }
        
        public float getPenetrationMultiplier() {
            return penetrationMultiplier;
        }
        
        public static PowerLevel fromString(String name) {
            if (name == null) return MEDIUM;
            
            String lowerName = name.toLowerCase().trim();
            
            if (lowerName.contains("极低")) return EXTREMELY_LOW;
            if (lowerName.contains("低")) return LOW;
            if (lowerName.contains("中")) return MEDIUM;
            if (lowerName.contains("较高")) return HIGH;
            if (lowerName.contains("高") && !lowerName.contains("极高")) return VERY_HIGH;
            if (lowerName.contains("极高")) return EXTREMELY_HIGH;
            if (lowerName.contains("未公布") || lowerName.contains("未知")) return UNKNOWN;
            
            return MEDIUM;
        }
        
        public static PowerLevel fromId(String id) {
            if (id == null) return MEDIUM;
            
            String lowerId = id.toLowerCase().trim();
            
            if (lowerId.equals("extremely_low") || lowerId.equals("极低")) return EXTREMELY_LOW;
            if (lowerId.equals("low") || lowerId.equals("低")) return LOW;
            if (lowerId.equals("medium") || lowerId.equals("中")) return MEDIUM;
            if (lowerId.equals("high") || lowerId.equals("较高")) return HIGH;
            if (lowerId.equals("very_high") || lowerId.equals("高")) return VERY_HIGH;
            if (lowerId.equals("extremely_high") || lowerId.equals("极高")) return EXTREMELY_HIGH;
            if (lowerId.equals("unknown") || lowerId.equals("未公布")) return UNKNOWN;
            
            return MEDIUM;
        }
    }
    
    private static GunConfig instance;
    
    private Map<String, String> gunPowerLevels = new HashMap<>();
    private Map<String, Float> gunBaseDamageOverrides = new HashMap<>();
    private Map<String, Float> gunMoaValues = new HashMap<>();
    private Map<String, Float> gunHipFireMoaValues = new HashMap<>();
    private Map<String, String> gunCalibers = new HashMap<>();
    private Map<String, Float> gunMuzzleVelocities = new HashMap<>();
    
    private GunConfig() {
    }
    
    public static synchronized GunConfig getInstance() {
        if (instance == null) {
            instance = loadConfig();
        }
        return instance;
    }
    
    public static synchronized void reload() {
        instance = loadConfig();
    }
    
    public static synchronized GunConfig getInstanceFresh() {
        return loadConfig();
    }
    
    public Map<String, String> getGunPowerLevels() {
        return gunPowerLevels;
    }
    
    public void setGunPowerLevel(String gunId, PowerLevel level) {
        gunPowerLevels.put(gunId.toLowerCase(), level.name());
        saveConfig();
    }
    
    public PowerLevel getPowerLevel(String gunId) {
        if (gunId == null || gunId.isEmpty()) return PowerLevel.MEDIUM;
        
        String levelName = gunPowerLevels.get(gunId.toLowerCase());
        if (levelName != null) {
            try {
                return PowerLevel.valueOf(levelName);
            } catch (IllegalArgumentException e) {
            }
        }
        
        return PowerLevel.MEDIUM;
    }
    
    public float getDamageMultiplier(String gunId) {
        return getPowerLevel(gunId).getDamageMultiplier();
    }
    
    public float getPenetrationMultiplier(String gunId) {
        return getPowerLevel(gunId).getPenetrationMultiplier();
    }
    
    public float getArmorDamageMultiplier(String gunId) {
        return getPowerLevel(gunId).getPenetrationMultiplier();
    }
    
    public Float getBaseDamageOverride(String gunId) {
        if (gunId == null || gunId.isEmpty()) return null;
        return gunBaseDamageOverrides.get(gunId.toLowerCase());
    }
    
    public void setBaseDamageOverride(String gunId, Float damage) {
        if (gunId == null || gunId.isEmpty()) return;
        if (damage != null && damage >= 0) {
            gunBaseDamageOverrides.put(gunId.toLowerCase(), damage);
        } else {
            gunBaseDamageOverrides.remove(gunId.toLowerCase());
        }
        saveConfig();
    }

    public float getMoa(String gunId) {
        if (gunId == null || gunId.isEmpty()) return DEFAULT_MOA;
        Float moa = gunMoaValues.get(gunId.toLowerCase());
        return moa != null ? clampMoa(moa) : DEFAULT_MOA;
    }

    public void setMoa(String gunId, float moa) {
        if (gunId == null || gunId.isEmpty()) return;
        gunMoaValues.put(gunId.toLowerCase(), clampMoa(moa));
        saveConfig();
    }

    public float getHipFireMoa(String gunId) {
        if (gunId == null || gunId.isEmpty()) return DEFAULT_HIP_FIRE_MOA;
        Float moa = gunHipFireMoaValues.get(gunId.toLowerCase());
        return moa != null ? clampMoa(moa) : DEFAULT_HIP_FIRE_MOA;
    }

    public void setHipFireMoa(String gunId, float moa) {
        if (gunId == null || gunId.isEmpty()) return;
        gunHipFireMoaValues.put(gunId.toLowerCase(), clampMoa(moa));
        saveConfig();
    }

    public String getCaliber(String gunId) {
        if (gunId == null || gunId.isEmpty()) return DEFAULT_CALIBER;
        String caliber = gunCalibers.get(gunId.toLowerCase());
        return caliber != null && !caliber.isBlank() ? caliber : DEFAULT_CALIBER;
    }

    public void setCaliber(String gunId, String caliber) {
        if (gunId == null || gunId.isEmpty()) return;
        if (caliber == null || caliber.isBlank()) {
            gunCalibers.remove(gunId.toLowerCase());
        } else {
            gunCalibers.put(gunId.toLowerCase(), caliber.trim());
        }
        saveConfig();
    }

    public Float getMuzzleVelocityOverride(String gunId) {
        if (gunId == null || gunId.isEmpty()) return null;
        Float velocity = gunMuzzleVelocities.get(gunId.toLowerCase());
        return velocity != null ? clampMuzzleVelocity(velocity) : null;
    }

    public float getMuzzleVelocity(String gunId, float fallbackVelocity) {
        Float configured = getMuzzleVelocityOverride(gunId);
        return configured != null ? configured : clampMuzzleVelocity(fallbackVelocity);
    }

    public void setMuzzleVelocity(String gunId, float velocityMetersPerSecond) {
        if (gunId == null || gunId.isEmpty()) return;
        gunMuzzleVelocities.put(gunId.toLowerCase(), clampMuzzleVelocity(velocityMetersPerSecond));
        saveConfig();
    }

    public float calculateSpreadRadiusCm(String gunId, float distanceMeters) {
        return calculateSpreadRadiusCm(getMoa(gunId), distanceMeters);
    }

    public static float calculateSpreadRadiusCm(float moa, float distanceMeters) {
        float safeDistance = Float.isFinite(distanceMeters) ? Math.max(0.0f, distanceMeters) : 0.0f;
        return clampMoa(moa) * 2.0f * 2.91f * (safeDistance / 100.0f);
    }

    private static float clampMoa(float moa) {
        if (!Float.isFinite(moa)) return DEFAULT_MOA;
        return Math.max(MIN_MOA, Math.min(MAX_MOA, moa));
    }

    private static float clampMuzzleVelocity(float velocity) {
        if (!Float.isFinite(velocity)) return MIN_MUZZLE_VELOCITY;
        return Math.max(MIN_MUZZLE_VELOCITY, Math.min(MAX_MUZZLE_VELOCITY, velocity));
    }
    
    public void saveConfig() {
        try {
            String path = WorldConfigManager.getGunsPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                GunConfigData data = new GunConfigData();
            data.powerLevels = gunPowerLevels;
            data.baseDamageOverrides = gunBaseDamageOverrides;
            data.moaValues = gunMoaValues;
            data.hipFireMoaValues = gunHipFireMoaValues;
            data.calibers = gunCalibers;
            data.muzzleVelocities = gunMuzzleVelocities;
            gson.toJson(data, writer);
            }
            FragmentationMod.LOGGER.info("[GunConfig] Saved config to: " + path);
        } catch (IOException e) {
            FragmentationMod.LOGGER.error("[GunConfig] Save error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static GunConfig loadConfig() {
        GunConfig config = new GunConfig();
        
        try {
            String path = WorldConfigManager.getGunsPath();
            File file = new File(path);
            
            FragmentationMod.LOGGER.info("[GunConfig] Loading guns config from: " + path + " (exists=" + file.exists() + ")");
            
            if (file.exists()) {
                Gson gson = new Gson();
                try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    GunConfigData data = gson.fromJson(reader, GunConfigData.class);
                    if (data != null) {
                        if (data.powerLevels != null) {
                            config.gunPowerLevels = data.powerLevels;
                            FragmentationMod.LOGGER.info("[GunConfig] Loaded " + data.powerLevels.size() + " gun power levels: " + data.powerLevels.keySet());
                        }
                        if (data.baseDamageOverrides != null) {
                            config.gunBaseDamageOverrides = data.baseDamageOverrides;
                        }
                        if (data.moaValues != null) {
                            data.moaValues.forEach((gunId, moa) -> {
                                if (gunId != null && moa != null) {
                                    config.gunMoaValues.put(gunId.toLowerCase(), clampMoa(moa));
                                }
                            });
                        }
                        if (data.hipFireMoaValues != null) {
                            data.hipFireMoaValues.forEach((gunId, moa) -> {
                                if (gunId != null && moa != null) {
                                    config.gunHipFireMoaValues.put(gunId.toLowerCase(), clampMoa(moa));
                                }
                            });
                        }
                        if (data.calibers != null) {
                            data.calibers.forEach((gunId, caliber) -> {
                                if (gunId != null && caliber != null && !caliber.isBlank()) {
                                    config.gunCalibers.put(gunId.toLowerCase(), caliber.trim());
                                }
                            });
                        }
                        if (data.muzzleVelocities != null) {
                            data.muzzleVelocities.forEach((gunId, velocity) -> {
                                if (gunId != null && velocity != null) {
                                    config.gunMuzzleVelocities.put(
                                        gunId.toLowerCase(),
                                        clampMuzzleVelocity(velocity)
                                    );
                                }
                            });
                        }
                    }
                } catch (com.google.gson.JsonSyntaxException e) {
                    FragmentationMod.LOGGER.warn("[GunConfig] JSON parse error, trying legacy format");
                    try (Reader reader = new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
                        Map<String, String> loaded = gson.fromJson(reader,
                            new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType());
                        if (loaded != null) {
                            config.gunPowerLevels = loaded;
                            FragmentationMod.LOGGER.info("[GunConfig] Loaded " + loaded.size() + " gun power levels (legacy format)");
                        }
                    }
                }
            }
        } catch (IOException e) {
            FragmentationMod.LOGGER.error("[GunConfig] Load error: " + e.getMessage());
            e.printStackTrace();
        }
        
        return config;
    }
    
    private static class GunConfigData {
        public Map<String, String> powerLevels = new HashMap<>();
        public Map<String, Float> baseDamageOverrides = new HashMap<>();
        public Map<String, Float> moaValues = new HashMap<>();
        public Map<String, Float> hipFireMoaValues = new HashMap<>();
        public Map<String, String> calibers = new HashMap<>();
        public Map<String, Float> muzzleVelocities = new HashMap<>();
    }
}
