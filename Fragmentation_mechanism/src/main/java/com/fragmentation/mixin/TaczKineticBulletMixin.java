package com.fragmentation.mixin;

import com.fragmentation.ballistics.TaczBallisticsHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tacz.guns.entity.EntityKineticBullet", remap = false)
public abstract class TaczKineticBulletMixin {
    @Shadow(remap = false)
    protected abstract void onHitBlock(
        BlockHitResult hitResult,
        Vec3 startPosition,
        Vec3 endPosition
    );

    @Inject(
        method = "onHitBlock",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void fragmentation$handleCoverImpact(
        BlockHitResult hitResult,
        Vec3 startPosition,
        Vec3 endPosition,
        CallbackInfo callback
    ) {
        Entity bullet = (Entity) (Object) this;
        TaczBallisticsHandler.markBlockHit(bullet, hitResult);
        if (TaczBallisticsHandler.handleCoverImpact(bullet, hitResult)) {
            TaczBallisticsHandler.spawnTaczBulletHole(bullet, hitResult);
            callback.cancel();
        }
    }

    @Inject(
        method = "onBulletTick",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private void fragmentation$handleMissedSpecialBlockHit(
        CallbackInfo callback
    ) {
        Entity bullet = (Entity) (Object) this;
        BlockHitResult hitResult =
            TaczBallisticsHandler.findMissedSpecialBlockHit(bullet);
        if (hitResult != null) {
            Vec3 startPosition = bullet.position();
            onHitBlock(hitResult, startPosition, hitResult.getLocation());
        }
        TaczBallisticsHandler.handleSupplementaryEntityHit(bullet);
    }
}
