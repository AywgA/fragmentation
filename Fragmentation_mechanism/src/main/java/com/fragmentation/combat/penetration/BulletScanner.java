package com.fragmentation.combat.penetration;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BulletScanner {

    private static final Set<String> BULLET_KEYWORDS = Set.of(
            "bullet", "ammo", "cartridge", "round", "shell",
            "子弹", "弹药", "弹壳", "弹丸", "枪弹", "弹匣"
    );

    private static final Set<String> MOD_BLACKLIST = Set.of(
            "minecraft", "forge", "fragmentation"
    );

    private static final Set<String> WEAPON_KEYWORDS = Set.of(
            "gun", "rifle", "pistol", "shotgun", "sniper", "machinegun",
            "firearm", "bow", "crossbow", "弩", "枪", "炮", "弓"
    );

    private static final Set<String> ARMOR_KEYWORDS = Set.of(
            "armor", "helmet", "chestplate", "leggings", "boots", "plate",
            "vest", "jacket", "armour", "护甲", "头盔", "胸甲", "裤子", "靴子", "插板"
    );

    public static List<BulletConfig.BulletEntry> scanAllBullets() {
        List<BulletConfig.BulletEntry> foundBullets = new ArrayList<>();
        
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
            if (itemId == null) continue;
            
            String namespace = itemId.getNamespace();
            if (MOD_BLACKLIST.contains(namespace)) continue;
            
            String path = itemId.getPath().toLowerCase();
            String itemName = new ItemStack(item).getDisplayName().getString().toLowerCase();
            
            if (isBulletItem(path, itemName)) {
                BulletConfig.BulletEntry entry = new BulletConfig.BulletEntry();
                entry.itemId = itemId.toString();
                entry.itemName = new ItemStack(item).getDisplayName().getString();
                entry.penetrationLevel = 0.0f;
                entry.armorDamage = 0;
                
                foundBullets.add(entry);
                
                FragmentationMod.LOGGER.info("[BulletScanner] Found bullet: {} ({})", 
                        entry.itemId, entry.itemName);
            }
        }
        
        FragmentationMod.LOGGER.info("[BulletScanner] Total bullets found: {}", foundBullets.size());
        return foundBullets;
    }

    public static List<BulletConfig.BulletEntry> scanModBullets(String modId) {
        List<BulletConfig.BulletEntry> foundBullets = new ArrayList<>();
        
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
            if (itemId == null) continue;
            
            if (!itemId.getNamespace().equals(modId)) continue;
            
            String path = itemId.getPath().toLowerCase();
            String itemName = new ItemStack(item).getDisplayName().getString().toLowerCase();
            
            if (isBulletItem(path, itemName)) {
                BulletConfig.BulletEntry entry = new BulletConfig.BulletEntry();
                entry.itemId = itemId.toString();
                entry.itemName = new ItemStack(item).getDisplayName().getString();
                entry.penetrationLevel = 0.0f;
                entry.armorDamage = 0;
                
                foundBullets.add(entry);
            }
        }
        
        return foundBullets;
    }

    private static boolean isBulletItem(String path, String itemName) {
        for (String keyword : BULLET_KEYWORDS) {
            if (path.contains(keyword) || itemName.contains(keyword)) {
                for (String weaponKeyword : WEAPON_KEYWORDS) {
                    if (path.contains(weaponKeyword) || itemName.contains(weaponKeyword)) {
                        return false;
                    }
                }
                for (String armorKeyword : ARMOR_KEYWORDS) {
                    if (path.contains(armorKeyword) || itemName.contains(armorKeyword)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    public static boolean needsAutoGenerate() {
        BulletConfig bulletConfig = FragmentationMod.getBulletConfig();
        return bulletConfig.getBullets().isEmpty();
    }

    public static void autoGenerateBulletConfig() {
        if (!needsAutoGenerate()) {
            FragmentationMod.LOGGER.info("[BulletScanner] Config already exists, skipping auto-generation");
            return;
        }
        
        FragmentationMod.LOGGER.info("[BulletScanner] Auto-generating bullet configuration...");
        
        List<BulletConfig.BulletEntry> scannedBullets = scanAllBullets();
        
        BulletConfig bulletConfig = FragmentationMod.getBulletConfig();
        
        for (BulletConfig.BulletEntry entry : scannedBullets) {
            bulletConfig.addBulletByAmmoId(entry.itemId, entry.penetrationLevel, entry.armorDamage);
        }
        
        bulletConfig.saveConfig();
        
        FragmentationMod.LOGGER.info("[BulletScanner] Auto-generated {} bullet entries", scannedBullets.size());
        FragmentationMod.LOGGER.info("[BulletScanner] Note: All bullets have penetrationLevel=0 and armorDamage=0");
        FragmentationMod.LOGGER.info("[BulletScanner] Please configure these values in the bullets.json config file");
    }
}