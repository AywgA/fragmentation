package com.fragmentation.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class DamageCalculationMixin {

    private static final String IGNORE_ARMOR_REDUCTION_KEY = "fragmentation_ignore_armor_reduction";

    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    public void getDamageAfterArmorAbsorb(DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;
        
        if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
            cir.setReturnValue(damage);
        }
    }

    @Inject(method = "getDamageAfterMagicAbsorb", at = @At("HEAD"), cancellable = true)
    public void getDamageAfterMagicAbsorb(DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
        LivingEntity entity = (LivingEntity) (Object) this;
        
        if (entity.getPersistentData().getBoolean(IGNORE_ARMOR_REDUCTION_KEY)) {
            cir.setReturnValue(damage);
        }
    }
}
