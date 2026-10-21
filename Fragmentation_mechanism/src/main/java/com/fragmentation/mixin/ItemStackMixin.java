package com.fragmentation.mixin;

import com.fragmentation.util.DurabilityUtils;
import com.google.common.collect.Multimap;
import com.google.common.collect.HashMultimap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public class ItemStackMixin {

    @Inject(method = "getMaxDamage", at = @At("HEAD"), cancellable = true)
    public void getMaxDamage(CallbackInfoReturnable<Integer> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        int maxDurability = DurabilityUtils.getMaxDurability(stack);

        if (maxDurability > 0) {
            cir.setReturnValue(maxDurability);
        }
    }

    @Inject(method = "getDamageValue", at = @At("HEAD"), cancellable = true)
    public void getDamageValue(CallbackInfoReturnable<Integer> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        int maxDurability = DurabilityUtils.getMaxDurability(stack);

        if (maxDurability > 0) {
            int damageValue = DurabilityUtils.getDamageValue(stack);
            cir.setReturnValue(Math.max(0, Math.min(maxDurability, damageValue)));
        }
    }

    @Inject(method = "setDamageValue", at = @At("HEAD"), cancellable = true)
    public void setDamageValue(int damage, CallbackInfo ci) {
        ItemStack stack = (ItemStack) (Object) this;
        int maxDurability = DurabilityUtils.getMaxDurability(stack);

        if (maxDurability > 0) {
            int clampedDamage = Math.max(0, Math.min(maxDurability, damage));
            int currentDamage = DurabilityUtils.getDamageValue(stack);

            // 检测修复操作：damage 值减小表示正在修复
            if (damage < currentDamage) {
                syncVisorDurabilityOnRepair(stack, maxDurability, currentDamage, damage);
            }

            int newDurability = maxDurability - clampedDamage;
            DurabilityUtils.setCurrentDurability(stack, newDurability);
            ci.cancel();
        }
    }

    /**
     * 当物品被修复时（setDamageValue 值减小），按比例同步恢复面罩耐久。
     * 这样 iron_repair_kits 等修复模组也能正确恢复面罩耐久。
     */
    private static void syncVisorDurabilityOnRepair(ItemStack stack, int maxDurability,
                                                     int oldDamage, int newDamage) {
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null) return;

        String visorKey = com.fragmentation.config.ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY;
        String visorMaxKey = com.fragmentation.config.ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY;
        if (!tag.contains(visorKey) || !tag.contains(visorMaxKey)) return;

        int visorMax = tag.getInt(visorMaxKey);
        int visorCur = tag.getInt(visorKey);
        if (visorMax <= 0 || visorCur >= visorMax) return;

        float repairRatio = (float) (oldDamage - newDamage) / (float) maxDurability;
        int visorRepair = (int) Math.ceil(visorMax * repairRatio);
        int newVisorDur = Math.min(visorMax, visorCur + visorRepair);

        tag.putInt(visorKey, newVisorDur);

        com.fragmentation.FragmentationMod.LOGGER.info(
            "[ItemStackMixin] Repair detected: vanillaDamage " + oldDamage
                + " -> " + newDamage + ", visorDur " + visorCur
                + " -> " + newVisorDur + "/" + visorMax);
    }

    @Inject(method = "getAttributeModifiers", at = @At("HEAD"), cancellable = true)
    public void getAttributeModifiers(EquipmentSlot slot, CallbackInfoReturnable<Multimap<Attribute, AttributeModifier>> cir) {
        try {
            ItemStack stack = (ItemStack) (Object) this;
            if (stack.isEmpty()) {
                return;
            }
            
            if (DurabilityUtils.isConfiguredArmor(stack)) {
                cir.setReturnValue(com.google.common.collect.ImmutableMultimap.of());
            }
        } catch (Throwable e) {
            com.fragmentation.FragmentationMod.LOGGER.error("[ItemStackMixin] Error in getAttributeModifiers", e);
        }
    }

    @Inject(method = "isDamaged", at = @At("HEAD"), cancellable = true)
    public void isDamaged(CallbackInfoReturnable<Boolean> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        int maxDurability = DurabilityUtils.getMaxDurability(stack);

        if (maxDurability > 0) {
            // Hide the vanilla durability bar; the mod's tooltip
            // already displays the exact remaining durability.
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hurtAndBreak", at = @At("HEAD"), cancellable = true)
    public void hurtAndBreak(int amount, LivingEntity entity, java.util.function.Consumer<EquipmentSlot> onBroken, CallbackInfo ci) {
        ItemStack stack = (ItemStack) (Object) this;
        int maxDurability = DurabilityUtils.getMaxDurability(stack);

        if (maxDurability > 0) {
            int currentDurability = DurabilityUtils.getCurrentDurability(stack);
            int newDurability = Math.max(0, currentDurability - amount);
            
            DurabilityUtils.setCurrentDurability(stack, newDurability);
            
            ci.cancel();
        }
    }

}
