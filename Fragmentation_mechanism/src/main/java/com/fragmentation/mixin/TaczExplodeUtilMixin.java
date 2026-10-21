package com.fragmentation.mixin;

import com.fragmentation.explosive.GrenadeExplosionHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管 TACZ 的统一爆炸入口（高爆子弹、40mm 榴弹等），
 * 将原版球形爆炸替换为现实冲击波 + 破片模型。
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.util.ExplodeUtil", remap = false)
public class TaczExplodeUtilMixin {
    @Inject(
        method = "createExplosion",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void fragmentation$realisticHe(
        Entity owner,
        Entity exploder,
        float damage,
        float radius,
        boolean knockback,
        boolean destroyBlock,
        Vec3 pos,
        CallbackInfo callback
    ) {
        Level level = exploder != null ? exploder.level()
            : owner != null ? owner.level() : null;
        if (level instanceof ServerLevel serverLevel) {
            String ammoId = null;
            if (exploder != null) {
                net.minecraft.resources.ResourceLocation id =
                    com.fragmentation.ballistics.TaczBallisticsHandler.getAmmoId(exploder);
                if (id != null) {
                    ammoId = id.toString();
                }
            }
            GrenadeExplosionHandler.onTaczExplosion(
                serverLevel, pos, owner, damage, radius, ammoId
            );
            callback.cancel();
        }
    }
}
