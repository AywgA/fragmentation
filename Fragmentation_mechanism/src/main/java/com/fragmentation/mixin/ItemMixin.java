package com.fragmentation.mixin;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Item.class)
public class ItemMixin {

    @Inject(method = "getMaxDamage", at = @At("HEAD"), cancellable = true)
    public void getMaxDamage(CallbackInfoReturnable<Integer> cir) {
        Item item = (Item) (Object) this;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) return;

        ArmorConfig.ArmorEntry entry = FragmentationMod.getArmorConfig().getArmorById(id.toString());
        if (entry != null && entry.maxDurability > 0) {
            cir.setReturnValue(entry.maxDurability);
        }
    }
}
