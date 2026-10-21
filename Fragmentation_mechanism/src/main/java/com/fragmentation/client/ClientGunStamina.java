package com.fragmentation.client;

import com.fragmentation.FragmentationMod;
import com.fragmentation.registry.ModSounds;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 手部体力 与 屏息体力（参考《暗区突围：无限》）。
 *
 * 手臂体力：持枪开镜期间持续消耗（每发子弹额外消耗），人机功效
 * 越低消耗越快；脱战片刻后恢复。手臂疲劳会放大后坐力与持枪晃动。
 * 屏息体力：开镜时按住屏息键（默认左 Alt，可在控制设置自定义）
 * 屏息，屏息期间晃动被压到极低，但快速消耗屏息体力；耗尽后进入
 * "大喘气"状态（晃动剧增）。必须松开屏息键才开始恢复（防止归零后
 * 按住键造成恢复/消耗高频震荡），恢复到 35% 以上才能再次屏息。
 * 屏息开始/结束分别播放吸气（xq.ogg）/呼气（hq.ogg）音效。
 * 现实逻辑：手臂体力耗尽时强行屏息不再降低晃动、反而更不稳；
 * 两条全空时晃动受到额外重罚。
 * HUD 两条均为白色，低于 25% 时变红，在屏幕底部中央横向并排
 * （左手臂、右屏息），图标显示在各自条的中间上方；条全满时
 * 自动隐藏，开始消耗或正在回复时显示，显隐平滑渐变。
 * 人机功效影响体力消耗速度，持枪稳定度影响开镜晃动幅度。
 *
 * 晃动以零均值正弦叠加建模，并以每 tick 增量积分进玩家视角，
 * 停止开镜后偏移自动回到零点，不产生永久视角漂移。
 */
@Mod.EventBusSubscriber(
    modid = FragmentationMod.MOD_ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ClientGunStamina {
    private static double hand = 100.0;
    private static double breath = 100.0;
    private static boolean exhausted;
    private static int handIdleTicks;
    private static int breathIdleTicks;
    private static boolean holdingGun;
    /** 上一 tick 是否处于屏息状态（吸/呼气音效的边沿检测）。 */
    private static boolean prevHoldingBreath;
    /** HUD 显隐渐变：0=完全隐藏，1=完全显示。 */
    private static float handAlpha;
    private static float breathAlpha;

    private static double swayPitch;
    private static double swayYaw;
    private static double phase1;
    private static double phase2;
    private static double phase3;

    /** 开镜时手臂被动消耗（点/tick）。 */
    private static final double HAND_AIM_DRAIN = 0.085;
    /** 每发子弹的手臂消耗。 */
    private static final double HAND_SHOT_DRAIN = 1.7;
    /** 屏息消耗（点/tick）。 */
    private static final double BREATH_DRAIN = 0.16;
    /** 手臂恢复（点/tick）。 */
    private static final double HAND_REGEN = 0.14;
    /** 屏息恢复（点/tick）。 */
    private static final double BREATH_REGEN = 0.12;
    /** 晃动基准幅度（度）：低稳定度枪械肉眼可见地晃动。 */
    private static final double SWAY_PITCH = 0.35;
    private static final double SWAY_YAW = 0.50;

    private ClientGunStamina() {
    }

    public static boolean isHoldingGun() {
        return holdingGun;
    }

    /** 手臂疲劳 → 后坐力额外倍率（满血 1.0，空血约 1.45）。 */
    public static double handRecoilFactor() {
        return 1.0 + Math.max(0.0, (30.0 - hand) / 30.0) * 0.45;
    }

    /** 开火时调用：消耗手臂体力。 */
    public static void onShot(GunRecoilConfig.GunStats stats) {
        hand = Mth.clamp(
            hand - HAND_SHOT_DRAIN * stats.drainFactor(), 0.0, 100.0);
        handIdleTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            holdingGun = false;
            prevHoldingBreath = false;
            handAlpha = 0.0F;
            breathAlpha = 0.0F;
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof IGun gun)) {
            holdingGun = false;
            // 不持枪：快速回满，晃动归零
            hand = Math.min(100.0, hand + HAND_REGEN * 2.5);
            breath = Math.min(100.0, breath + BREATH_REGEN * 2.5);
            exhausted = false;
            // 收起武器等同于停止屏息：补呼气声并复位边沿
            if (prevHoldingBreath) {
                prevHoldingBreath = false;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    ModSounds.BREATH_HOLD_STOP.get(), 1.0F, 1.0F));
            }
            updateHudAlpha();
            decaySway(player, 0.25);
            return;
        }
        holdingGun = true;
        GunRecoilConfig.GunStats stats = GunRecoilConfig.get(stack);

        IClientPlayerGunOperator operator =
            IClientPlayerGunOperator.fromLocalPlayer(player);
        boolean aiming = operator.getClientAimingProgress(1.0F) > 0.5F;
        boolean wantBreath = ModKeyBindings.BREATH_HOLD.isDown();
        boolean holdingBreath =
            aiming && wantBreath && breath > 0.5 && !exhausted;

        // 屏息边沿音效：进入屏息播放吸气（xq），离开屏息播放呼气（hq）
        if (holdingBreath && !prevHoldingBreath) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                ModSounds.BREATH_HOLD_START.get(), 1.0F, 1.0F));
        } else if (!holdingBreath && prevHoldingBreath) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                ModSounds.BREATH_HOLD_STOP.get(), 1.0F, 1.0F));
        }
        prevHoldingBreath = holdingBreath;

        if (aiming) {
            hand = Mth.clamp(
                hand - HAND_AIM_DRAIN * stats.drainFactor(), 0.0, 100.0);
            handIdleTicks = 0;
        } else {
            handIdleTicks++;
            if (handIdleTicks > 30) {
                hand = Math.min(100.0, hand + HAND_REGEN);
            }
        }

        if (holdingBreath) {
            breath = Mth.clamp(
                breath - BREATH_DRAIN * stats.drainFactor(), 0.0, 100.0);
            breathIdleTicks = 0;
            if (breath <= 0.0) {
                exhausted = true;
            }
        } else if (wantBreath) {
            // 修复：屏息条归零后，玩家仍按住屏息键时不允许恢复——
            // 必须松开按键（停止屏息）才开始恢复。否则会在
            // "恢复到 35% → 又能屏息 → 立即耗尽"之间高频震荡，
            // 表现为开镜视角一边恢复一边消耗地抽搐。
            breathIdleTicks = 0;
        } else {
            breathIdleTicks++;
            if (breathIdleTicks > 12) {
                breath = Math.min(100.0, breath + BREATH_REGEN);
                if (exhausted && breath >= 35.0) {
                    exhausted = false;
                }
            }
        }

        updateHudAlpha();
        updateSway(player, stats, aiming, holdingBreath);
    }

    /** 零均值多频正弦晃动，每 tick 平滑逼近目标并以增量旋转视角。 */
    private static void updateSway(
        LocalPlayer player,
        GunRecoilConfig.GunStats stats,
        boolean aiming,
        boolean holdingBreath
    ) {
        if (!aiming) {
            decaySway(player, 0.12);
            return;
        }
        double seconds = player.level().getGameTime() / 20.0;
        double ampMul = stats.swayFactor();
        // 手臂疲劳放大晃动（空体力时最高 ×3）
        ampMul *= 1.0 + Math.max(0.0, (30.0 - hand) / 30.0) * 2.0;
        boolean handEmpty = hand < 1.0;
        if (holdingBreath) {
            // 手臂有体力时屏息把晃动压到极低；手臂已经没体力时，
            // 强行屏息起不到好效果，反而更不稳（现实逻辑）
            ampMul *= handEmpty ? 1.5 : 0.07;
        }
        if (exhausted && !holdingBreath) {
            // 大喘气惩罚加重
            ampMul *= 3.0;
        }
        // 手臂与屏息双双耗尽：雪上加霜的额外重罚
        if (handEmpty && exhausted) {
            ampMul *= 1.8;
        }
        double targetPitch =
            (Math.sin(seconds * 0.75 + phase1) * 0.55
                + Math.sin(seconds * 1.35 + phase2) * 0.30
                + Math.sin(seconds * 2.1 + phase3) * 0.15)
                * SWAY_PITCH * ampMul;
        double targetYaw =
            (Math.sin(seconds * 0.62 + phase2) * 0.55
                + Math.sin(seconds * 1.18 + phase3) * 0.30
                + Math.sin(seconds * 1.9 + phase1) * 0.15)
                * SWAY_YAW * ampMul;
        double np = swayPitch + (targetPitch - swayPitch) * 0.10;
        double ny = swayYaw + (targetYaw - swayYaw) * 0.10;
        player.setXRot(player.getXRot() - (float) (np - swayPitch));
        player.setYRot(player.getYRot() - (float) (ny - swayYaw));
        swayPitch = np;
        swayYaw = ny;
    }

    /** 非开镜/不持枪：晃动指数回到零，视角同步回位。 */
    private static void decaySway(LocalPlayer player, double rate) {
        double np = swayPitch * (1.0 - rate);
        double ny = swayYaw * (1.0 - rate);
        player.setXRot(player.getXRot() - (float) (np - swayPitch));
        player.setYRot(player.getYRot() - (float) (ny - swayYaw));
        swayPitch = np;
        swayYaw = ny;
    }

    /**
     * 条全满时隐藏、开始消耗或正在回复时显示：alpha 每 tick 向目标
     * 平滑逼近（约 0.6 秒完成一次淡入/淡出）。
     */
    private static void updateHudAlpha() {
        handAlpha = approach(handAlpha, hand < 99.9 ? 1.0F : 0.0F);
        breathAlpha = approach(breathAlpha, breath < 99.9 ? 1.0F : 0.0F);
    }

    private static float approach(float current, float target) {
        float step = 0.08F;
        if (current < target) {
            return Math.min(target, current + step);
        }
        return Math.max(target, current - step);
    }

    // ================= HUD：底部中央两条体力条（图标 + 白条） =================

    /** 手臂条图标（用户提供，白色透明底）。 */
    private static final ResourceLocation ARM_ICON =
        new ResourceLocation(FragmentationMod.MOD_ID, "textures/gui/hud/arm.png");
    /** 屏息条图标（用户提供，白色透明底）。 */
    private static final ResourceLocation BREATH_ICON =
        new ResourceLocation(FragmentationMod.MOD_ID, "textures/gui/hud/breath.png");

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!holdingGun
            || !event.getOverlay().id()
                .equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var graphics = event.getGuiGraphics();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int barW = 96;
        int barH = 6;
        int gap = 14;
        // 屏幕底部中央：两条横着并排（左手臂、右屏息），
        // 图标显示在各自条的中间上方（图标高 12，间距 2）
        int totalW = barW * 2 + gap;
        int xHand = (w - totalW) / 2;
        int xBreath = xHand + barW + gap;
        int y = h - 14;

        // 条满隐藏：alpha 渐变期间才绘制，图标随条一起淡入淡出
        if (handAlpha > 0.01F || breathAlpha > 0.01F) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (handAlpha > 0.01F) {
                // 手臂图标 320x203 → 19x12
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, handAlpha);
                graphics.blit(ARM_ICON,
                    xHand + (barW - 19) / 2, y - 14,
                    19, 12, 0.0F, 0.0F, 320, 203, 320, 203);
            }
            if (breathAlpha > 0.01F) {
                // 屏息图标 229x203 → 14x12
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, breathAlpha);
                graphics.blit(BREATH_ICON,
                    xBreath + (barW - 14) / 2, y - 14,
                    14, 12, 0.0F, 0.0F, 229, 203, 229, 203);
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }

        if (handAlpha > 0.01F) {
            drawBar(graphics, xHand, y, barW, barH,
                hand / 100.0, false, handAlpha);
        }
        if (breathAlpha > 0.01F) {
            drawBar(graphics, xBreath, y, barW, barH,
                breath / 100.0, exhausted, breathAlpha);
        }
    }

    private static void drawBar(
        net.minecraft.client.gui.GuiGraphics graphics,
        int x,
        int y,
        int width,
        int height,
        double pct,
        boolean blink,
        float alpha
    ) {
        int p = (int) Math.round(Mth.clamp(pct, 0.0, 1.0) * (width - 2));
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1,
            fadeColor(0xB0000000, alpha));
        graphics.fill(x, y, x + width, y + height,
            fadeColor(0xFF1A1A1A, alpha));
        // 基础白色；低于 25% 变红
        int fill = pct < 0.25 ? 0xFFFF4040 : 0xFFFFFFFF;
        // 大喘气期间红白闪烁提示
        Minecraft mc = Minecraft.getInstance();
        if (blink && mc.level != null && mc.level.getGameTime() % 10 < 5) {
            fill = fill == 0xFFFF4040 ? 0xFFFFFFFF : 0xFFFF4040;
        }
        graphics.fill(x + 1, y + 1, x + 1 + p, y + height - 1,
            fadeColor(fill, alpha));
    }

    /** 把 ARGB 颜色的 alpha 通道按比例缩放（显隐渐变用）。 */
    private static int fadeColor(int argb, float scale) {
        int a = (argb >>> 24) & 0xFF;
        a = Math.round(a * Mth.clamp(scale, 0.0F, 1.0F));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 随机相位（屏息开始等时刻可调用，目前保留）。 */
    public static void rerollPhases() {
        phase1 = Math.random() * Math.PI * 2;
        phase2 = Math.random() * Math.PI * 2;
        phase3 = Math.random() * Math.PI * 2;
    }
}
