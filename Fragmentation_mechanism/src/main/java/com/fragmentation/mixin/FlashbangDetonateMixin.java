package com.fragmentation.mixin;

import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.explosive.FlashbangHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = "com.victoriomods.taczballisticbreaching.FlashbangGrenadeEntity",
    remap = false
)
public class FlashbangDetonateMixin {
    @Inject(
        method = "detonate",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void fragmentation$realisticFlashbang(CallbackInfo callback) {
        FlashbangHandler.onDetonate(
            (Entity) (Object) this,
            ExplosiveConfig.ADDON_FLASH_ID
        );
        callback.cancel();
    }
}
