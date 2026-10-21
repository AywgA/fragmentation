package com.fragmentation.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class WorldConfigManager {
    private static final String MOD_DIR = "fragmentation";
    private static final String ARMORS_FILE = "armors.json";
    private static final String BULLETS_FILE = "bullets.json";
    private static final String PLATES_FILE = "plates.json";
    private static final String PENETRATION_FILE = "penetration.json";
    private static final String WEAPONS_FILE = "weapons.json";
    private static final String RECOIL_POWER_FILE = "recoil_power.json";
    private static final String GUNS_FILE = "guns.json";
    private static final String ARMOR_MATERIALS_FILE = "armor_materials.json";
    private static final String SHIELDS_FILE = "shields.json";
    private static final String WINDS_FILE = "winds.json";
    private static final String WIND_METERS_FILE = "wind_meters.json";
    private static final String COVERS_FILE = "covers.json";
    private static final String SHIELDING_FIELD_FILE = "shielding_field.json";
    private static final String PROTECTION_FILE = "protection.json";
    private static final String EXPLOSIVES_FILE = "explosives.json";
    
    private static Path worldConfigDir;
    
    public static void init(MinecraftServer server) {
        worldConfigDir = server.getWorldPath(LevelResource.ROOT).resolve(MOD_DIR);
        ensureDirectoryExists(worldConfigDir);
    }
    
    public static Path getWorldConfigDir() {
        return worldConfigDir;
    }
    
    public static String getArmorsPath() {
        if (worldConfigDir == null) return getFallbackPath(ARMORS_FILE);
        return worldConfigDir.resolve(ARMORS_FILE).toString();
    }
    
    public static String getBulletsPath() {
        if (worldConfigDir == null) return getFallbackPath(BULLETS_FILE);
        return worldConfigDir.resolve(BULLETS_FILE).toString();
    }
    
    public static String getPlatesPath() {
        if (worldConfigDir == null) return getFallbackPath(PLATES_FILE);
        return worldConfigDir.resolve(PLATES_FILE).toString();
    }
    
    public static String getPenetrationPath() {
        if (worldConfigDir == null) return getFallbackPath(PENETRATION_FILE);
        return worldConfigDir.resolve(PENETRATION_FILE).toString();
    }
    
    public static String getWeaponsPath() {
        if (worldConfigDir == null) return getFallbackPath(WEAPONS_FILE);
        return worldConfigDir.resolve(WEAPONS_FILE).toString();
    }
    
    public static String getRecoilPowerPath() {
        if (worldConfigDir == null) return getFallbackPath(RECOIL_POWER_FILE);
        return worldConfigDir.resolve(RECOIL_POWER_FILE).toString();
    }
    
    public static String getGunsPath() {
        if (worldConfigDir == null) return getFallbackPath(GUNS_FILE);
        return worldConfigDir.resolve(GUNS_FILE).toString();
    }
    
    public static String getArmorMaterialsPath() {
        if (worldConfigDir == null) return getFallbackPath(ARMOR_MATERIALS_FILE);
        return worldConfigDir.resolve(ARMOR_MATERIALS_FILE).toString();
    }
    
    public static String getShieldsPath() {
        if (worldConfigDir == null) return getFallbackPath(SHIELDS_FILE);
        return worldConfigDir.resolve(SHIELDS_FILE).toString();
    }

    public static String getWindsPath() {
        if (worldConfigDir == null) return getFallbackPath(WINDS_FILE);
        return worldConfigDir.resolve(WINDS_FILE).toString();
    }

    public static String getWindMetersPath() {
        if (worldConfigDir == null) return getFallbackPath(WIND_METERS_FILE);
        return worldConfigDir.resolve(WIND_METERS_FILE).toString();
    }

    public static String getCoversPath() {
        if (worldConfigDir == null) return getFallbackPath(COVERS_FILE);
        return worldConfigDir.resolve(COVERS_FILE).toString();
    }

    public static String getShieldingFieldPath() {
        if (worldConfigDir == null) return getFallbackPath(SHIELDING_FIELD_FILE);
        return worldConfigDir.resolve(SHIELDING_FIELD_FILE).toString();
    }

    public static String getProtectionPath() {
        if (worldConfigDir == null) return getFallbackPath(PROTECTION_FILE);
        return worldConfigDir.resolve(PROTECTION_FILE).toString();
    }

    public static String getExplosivesPath() {
        if (worldConfigDir == null) return getFallbackPath(EXPLOSIVES_FILE);
        return worldConfigDir.resolve(EXPLOSIVES_FILE).toString();
    }
    
    private static String getFallbackPath(String filename) {
        return Paths.get(getFallbackParent(), filename).toString();
    }
    
    private static String getFallbackParent() {
        return Paths.get("config", MOD_DIR).toString();
    }
    
    private static void ensureDirectoryExists(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static boolean isInitialized() {
        return worldConfigDir != null;
    }
}
