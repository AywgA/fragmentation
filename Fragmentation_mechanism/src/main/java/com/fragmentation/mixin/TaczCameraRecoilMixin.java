package com.fragmentation.mixin;

import com.fragmentation.client.RealisticRecoilManager;
import com.tacz.guns.api.event.common.GunFireEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 完全替换 TACZ 的相机后坐力：
 *  - initialCameraRecoil(GunFireEvent)：原逻辑把枪包后坐力关键帧
 *    转成样条；取消后改为向 RealisticRecoilManager 施加单发冲量。
 *  - applyCameraRecoil(ComputeCameraAngles)：原逻辑每帧按样条增量
 *    旋转玩家视角；取消后改为推进我方弹簧模型并积分进视角。
 * 塔科夫/暗区式的快速上跳+连发上爬+水平横跳由此接管。
 */
@Pseudo
@Mixin(
    targets = "com.tacz.guns.client.event.CameraSetupEvent",
    remap = false
)
public abstract class TaczCameraRecoilMixin {
    @Inject(
        method = "initialCameraRecoil",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void fragmentation$realRecoilOnFire(
        GunFireEvent event,
        CallbackInfo ci
    ) {
        RealisticRecoilManager.onFire(event);
        ci.cancel();
    }

    @Inject(
        method = "applyCameraRecoil",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void fragmentation$realRecoilApply(
        ViewportEvent.ComputeCameraAngles event,
        CallbackInfo ci
    ) {
        RealisticRecoilManager.applyAngles();
        ci.cancel();
    }
}
