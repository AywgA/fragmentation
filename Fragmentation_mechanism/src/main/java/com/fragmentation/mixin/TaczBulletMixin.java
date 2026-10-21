package com.fragmentation.mixin;

import com.fragmentation.FragmentationMod;
import com.fragmentation.ballistics.TaczBallisticsHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class TaczBulletMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void beforeTaczBulletTick(CallbackInfo ci) {
        TaczBallisticsHandler.beforeEntityTick((Entity) (Object) this);
    }
    
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void onSaveWithoutId(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        Entity entity = (Entity) (Object) this;
        String entityClass = entity.getClass().getName();
        
        if (entityClass.contains("tacz") && entityClass.contains("bullet")) {
            FragmentationMod.LOGGER.info("[TACZ Mixin] === Bullet Entity NBT Intercepted ===");
            FragmentationMod.LOGGER.info("[TACZ Mixin] Bullet class: {}", entityClass);
            FragmentationMod.LOGGER.info("[TACZ Mixin] NBT keys: {}", tag.getAllKeys());
            
            for (String key : tag.getAllKeys()) {
                byte tagType = tag.getTagType(key);
                Object value;
                switch (tagType) {
                    case CompoundTag.TAG_STRING:
                        value = tag.getString(key);
                        break;
                    case CompoundTag.TAG_FLOAT:
                        value = tag.getFloat(key);
                        break;
                    case CompoundTag.TAG_DOUBLE:
                        value = tag.getDouble(key);
                        break;
                    case CompoundTag.TAG_INT:
                        value = tag.getInt(key);
                        break;
                    default:
                        value = "[complex type]";
                }
                FragmentationMod.LOGGER.info("[TACZ Mixin]   {}: {} (type={})", key, value, tagType);
            }
        }
    }
}
