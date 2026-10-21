package com.fragmentation.mixin;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.DurabilityUtils;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    private static final String IGNORE_ARMOR_REDUCTION_KEY = "fragmentation_ignore_armor_reduction";
    private static final String INTENDED_DAMAGE_KEY = "fragmentation_intended_damage";

    @Inject(method = "hurtArmor", at = @At("HEAD"), cancellable = true)
    public void hurtArmor(DamageSource source, float damage, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        for (ItemStack stack : entity.getArmorSlots()) {
            if (!stack.isEmpty() && DurabilityUtils.isConfiguredArmor(stack)) {
                ci.cancel();
                return;
            }
        }
    }

    @ModifyArg(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setHealth(F)V"), index = 0)
    public float onSetHealthInActuallyHurt(float health) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
            float intendedDamage = entity.getPersistentData().getFloat(INTENDED_DAMAGE_KEY);
            float currentHealth = entity.getHealth();
            float newHealth = Math.max(0.0F, currentHealth - intendedDamage);

            FragmentationMod.LOGGER.info("[LivingEntityMixin] Bypassing all damage reduction: currentHealth=" + currentHealth +
                ", intendedDamage=" + intendedDamage + ", newHealth=" + newHealth + " (original health arg=" + health + ")");

            return newHealth;
        }

        return health;
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    public void onActuallyHurtReturn(DamageSource source, float damage, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
            entity.getPersistentData().remove(IGNORE_ARMOR_REDUCTION_KEY);
            entity.getPersistentData().remove(INTENDED_DAMAGE_KEY);
            FragmentationMod.LOGGER.info("[LivingEntityMixin] Cleared bypass flags after actuallyHurt");
        }
    }
}