package com.fragmentation.mixin;

import com.fragmentation.client.GunRecoilConfig;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 人机功效影响开镜速度：直接缩放 TACZ 开镜进度推进方法
 * aimProgressCalculate(float) 的每帧增量参数（该方法每个客户端
 * tick 被调用一次，参数即为加/减到 [0,1] 内部进度上的增量）。
 *
 * 人机越低，进度逼近 1 越慢，开镜时长真实变化
 * （1 功效≈0.31 倍速即约 3.2 倍慢，55≈0.96，100≈1.5 倍快）。
 * 增量缩放不改变进度的收敛区间，满开镜时枪模/镜头永远精确对齐，
 * 不会出现视角错位。
 */
@Pseudo
@Mixin(
    targets = "com.tacz.guns.client.gameplay.LocalPlayerAim",
    remap = false
)
public abstract class TaczAimMixin {
    @ModifyVariable(
        method = "aimProgressCalculate",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0,
        remap = false
    )
    private float fragmentation$ergoAimDelta(float delta) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return delta;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof IGun)) {
            return delta;
        }
        return (float) (
            delta * GunRecoilConfig.get(stack).aimSpeedFactor());
    }
}
