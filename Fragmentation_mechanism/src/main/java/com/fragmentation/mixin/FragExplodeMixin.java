package com.fragmentation.mixin;

import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.explosive.GrenadeExplosionHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = "com.victoriomods.taczballisticbreaching.FragGrenadeEntity",
    remap = false
)
public class FragExplodeMixin {
    @Inject(
        method = "explode",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void fragmentation$realisticExplosion(CallbackInfo callback) {
        GrenadeExplosionHandler.onGrenadeExplode(
            (Entity) (Object) this,
            ExplosiveConfig.ADDON_FRAG_ID
        );
        callback.cancel();
    }
}
