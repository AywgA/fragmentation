package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.BulletConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public class DamageEventHandler {
    
    private static final Random random = new Random();
    
    private static final String IGNORE_ARMOR_REDUCTION_KEY = "fragmentation_ignore_armor_reduction";
    
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onLivingDamage(LivingDamageEvent event) {
        try {
            if (event.getEntity().level().isClientSide) return;
            
            LivingEntity entity = event.getEntity();
            DamageSource source = event.getSource();
            float originalDamage = event.getAmount();
            
            if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
                return;
            }
            
            if (entity instanceof Player player) {
                DamageCalculationResult result = calculateDamageWithArmor(player, source, originalDamage);
                event.setAmount(result.finalDamage);
            }
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[DamageEventHandler] Error processing damage event", e);
        }
    }
    
    public static DamageCalculationResult calculateDamageWithArmor(Player player, DamageSource source, float damage) {
        DamageCalculationResult result = new DamageCalculationResult();
        result.originalDamage = damage;
        result.finalDamage = damage;
        
        if (damage <= 0) return result;
        
        String damageType = source.getMsgId();
        boolean isProjectile = isProjectileDamage(source, damageType);
        boolean isExplosion = isExplosionDamage(source, damageType);
        boolean isMagic = isMagicDamage(source, damageType);
        boolean isMelee = isMeleeDamage(source, damageType);
        
        EquipmentSlot[] armorSlots = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
        };
        
        float totalBlocked = 0;
        boolean hasAnyArmor = false;
        
        for (EquipmentSlot slot : armorSlots) {
            ItemStack armorStack = player.getItemBySlot(slot);
            if (armorStack.isEmpty()) continue;
            
            ArmorConfig.ArmorEntry armorEntry = FragmentationMod.getArmorConfig().getArmorForItem(armorStack);
            if (armorEntry == null) continue;
            
            hasAnyArmor = true;
            
            int currentDurability = armorStack.getMaxDamage() - armorStack.getDamageValue();
            if (currentDurability <= 0) continue;
            
            float slotMultiplier = getSlotDamageMultiplier(slot, false);
            float damageToSlot = damage * slotMultiplier;
            
            if (damageToSlot <= 0) continue;
            
            float baseReduction = 0.1f + (armorEntry.armorLevel * 0.1f);
            float effectiveReduction = baseReduction;
            
            if (isMagic) {
                effectiveReduction *= 0.2f;
            }
            if (isExplosion) {
                effectiveReduction *= 0.5f;
            }
            
            effectiveReduction = Math.min(effectiveReduction, 0.95f);
            
            float damageBlocked = damageToSlot * effectiveReduction;
            totalBlocked += damageBlocked;
            
            float durabilityDamage = damageToSlot * 0.2f;
            if (isExplosion) {
                durabilityDamage *= 1.5f;
            }
            
            if (durabilityDamage > 0) {
                damageArmor(player, armorStack, (int) Math.ceil(durabilityDamage), slot);
            }
        }
        
        result.finalDamage = Math.max(0, damage - totalBlocked);
        result.wasModified = hasAnyArmor;
        
        return result;
    }
    
    private static float getSlotDamageMultiplier(EquipmentSlot slot, boolean isHeadShot) {
        return switch (slot) {
            case HEAD -> 0.25f;
            case CHEST -> 0.40f;
            case LEGS -> 0.25f;
            case FEET -> 0.10f;
            default -> 0.25f;
        };
    }
    
    private static boolean isProjectileDamage(DamageSource source, String type) {
        return type.contains("arrow") || type.contains("trident") || 
               type.contains("fireball") || type.contains("projectile");
    }
    
    private static boolean isExplosionDamage(DamageSource source, String type) {
        return type.contains("explosion") || type.contains("explode");
    }
    
    private static boolean isMagicDamage(DamageSource source, String type) {
        return type.contains("magic") || type.contains("indirectMagic");
    }
    
    private static boolean isMeleeDamage(DamageSource source, String type) {
        return type.equals("player") || type.equals("mob") || 
               type.contains("sting") || type.equals("thorns");
    }
    
    private static void damageArmor(Player player, ItemStack armorStack, int damage, EquipmentSlot slot) {
        int unbreakingLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.UNBREAKING, armorStack);
        if (unbreakingLevel > 0) {
            int reduceChance = 100 / (unbreakingLevel + 1);
            if (random.nextInt(100) > reduceChance) {
                return;
            }
        }
        
        int currentDamage = armorStack.getDamageValue();
        int maxDamage = armorStack.getMaxDamage();
        
        int newDamage = Math.min(currentDamage + damage, maxDamage);
        armorStack.setDamageValue(newDamage);
    }
    
    public static class DamageCalculationResult {
        public float originalDamage;
        public float finalDamage;
        public boolean wasModified;
    }
}
