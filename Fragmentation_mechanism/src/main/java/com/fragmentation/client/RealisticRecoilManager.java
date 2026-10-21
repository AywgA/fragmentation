package com.fragmentation.client;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 参考暗区突围：无限 / 逃离塔科夫手感的过程化枪械后坐力，完全替代
 * TACZ 原有关键帧样条后坐力（由 TaczCameraRecoilMixin 注入）。
 *
 * 每把枪的强度来自 config/fragmentation/gun_recoil.json 的独立属性：
 * 垂直后坐力（vertical）、水平后坐力（horizontal）、人机功效
 * （开镜速度见 TaczAimMixin，体力消耗见 ClientGunStamina）、
 * 持枪稳定度（开镜瞄准晃动幅度，见 ClientGunStamina）。
 *
 * 模型分三层：
 *  1) punch 快速上跳——每发一个冲量，临界阻尼弹簧在约 40ms 内冲到
 *     顶点、约 120ms 回弹（枪口猛抬一下再回位）；
 *  2) climb 慢速累积上爬——连发时枪口持续上抬，只有约 1.1 秒的缓慢
 *     自动回落，需要玩家主动压枪（塔科夫连发核心手感）；
 *  3) yaw 水平横跳——每发随机左右偏移并带轻微随机游走偏向，欠阻尼
 *     回中，不做对称镜像，连发轨迹不可完全预测。
 *
 * 修正因子：射速（越快单发冲量越小）、开镜（按 √倍率 衰减）、姿态
 * （冲刺/腾空/移动/下蹲）、首发 +50%、手臂疲劳额外加成。
 */
public final class RealisticRecoilManager {
    /** 快速上跳弹簧角频率（rad/s）：越大顶点越快。 */
    private static final double PUNCH_OMEGA = 26.0;
    /** 水平横跳弹簧角频率。 */
    private static final double YAW_OMEGA = 20.0;
    /** 累积上爬自动回落时间常数（秒）。 */
    private static final double CLIMB_TAU = 1.1;
    /** 上跳冲量标定：基准枪（vertical=60）单发顶点约 1.2°。 */
    private static final double PUNCH_J = 1.2 * Math.E * PUNCH_OMEGA;
    /** 横跳冲量标定：基准枪（horizontal=55）单发横跳峰值约 0.52°。 */
    private static final double YAW_J = 0.52 * Math.E * YAW_OMEGA;
    /** 每发累积上爬量（度，基准枪）。 */
    private static final double CLIMB_PER_SHOT = 0.30;
    /** 首发额外倍率。 */
    private static final double FIRST_SHOT_MUL = 1.5;
    /** 停火超过该毫秒数视为首发。 */
    private static final long FIRST_SHOT_GAP_MS = 350;
    /** 总偏移保险限幅（度），防止异常数据导致视角飞转。 */
    private static final double PITCH_LIMIT = 18.0;
    private static final double YAW_LIMIT = 10.0;

    private static double punchPitch;
    private static double punchVel;
    private static double climbPitch;
    private static double yawOffset;
    private static double yawVel;
    private static double hDrift;

    private static double lastPitch;
    private static double lastYaw;
    private static long lastFireMs;
    private static long lastFrameMs;

    private RealisticRecoilManager() {
    }

    /** 每次开火（TACZ GunFireEvent，客户端本地玩家）施加单发冲量。 */
    public static void onFire(GunFireEvent event) {
        if (!event.getLogicalSide().isClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || event.getShooter() != player) {
            return;
        }
        ItemStack stack = event.getGunItemStack();
        if (!(stack.getItem() instanceof IGun gun)) {
            return;
        }

        GunRecoilConfig.GunStats stats = GunRecoilConfig.get(stack);
        int rpm = gunRpm(gun, stack);
        double rpmFactor = Mth.clamp(600.0 / Math.max(300.0, rpm), 0.72, 1.30);
        double adsScale = adsScale(mc, player, gun, stack);
        double stanceScale = stanceScale(player);
        double handScale = ClientGunStamina.handRecoilFactor();
        double power = rpmFactor * adsScale * stanceScale * handScale;

        long now = System.currentTimeMillis();
        boolean firstShot = now - lastFireMs > FIRST_SHOT_GAP_MS;
        lastFireMs = now;
        double firstMul = firstShot ? FIRST_SHOT_MUL : 1.0;

        // 垂直：快速上跳冲量 + 慢速累积，强度取该枪垂直后坐力
        double vPower = stats.verticalScale() * power * firstMul;
        punchVel += (0.9 + 0.1 * player.getRandom().nextGaussian())
            * PUNCH_J * vPower;
        climbPitch = Mth.clamp(
            climbPitch + CLIMB_PER_SHOT * stats.verticalScale() * power
                * (0.85 + 0.3 * player.getRandom().nextDouble()),
            0.0, PITCH_LIMIT);

        // 水平：强随机 + 轻微随机游走偏向，强度取该枪水平后坐力
        hDrift = Mth.clamp(
            hDrift * 0.7 + 0.25 * player.getRandom().nextGaussian(),
            -1.0, 1.0);
        yawVel += (0.65 * player.getRandom().nextGaussian() + 0.35 * hDrift)
            * YAW_J * stats.horizontalScale() * power;

        // 开火额外消耗手臂体力
        ClientGunStamina.onShot(stats);
    }

    /**
     * 每帧推进弹簧并把增量积分进玩家视角（替代 TACZ 的
     * CameraSetupEvent.applyCameraRecoil）。
     */
    public static void applyAngles() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double dt = lastFrameMs == 0
            ? 1.0 / 60.0
            : Mth.clamp((now - lastFrameMs) / 1000.0, 0.001, 0.05);
        lastFrameMs = now;

        // 半隐式欧拉，子步进保证高帧率/低帧率行为一致
        double step = 1.0 / 240.0;
        for (double t = 0.0; t < dt; t += step) {
            double h = Math.min(step, dt - t);
            // 临界阻尼上跳（ζ=1）：a = -ω²x - 2ωv
            punchVel += (-PUNCH_OMEGA * PUNCH_OMEGA * punchPitch
                - 2.0 * PUNCH_OMEGA * punchVel) * h;
            punchPitch += punchVel * h;
            // 水平欠阻尼（ζ≈0.95），带回弹轻微摆动
            yawVel += (-YAW_OMEGA * YAW_OMEGA * yawOffset
                - 1.9 * YAW_OMEGA * yawVel) * h;
            yawOffset += yawVel * h;
        }
        // 累积上爬缓慢回落（指数衰减）
        climbPitch *= Math.exp(-dt / CLIMB_TAU);

        double pitchOff = Mth.clamp(punchPitch + climbPitch,
            -PITCH_LIMIT, PITCH_LIMIT);
        double yawOff = Mth.clamp(yawOffset, -YAW_LIMIT, YAW_LIMIT);
        double dPitch = pitchOff - lastPitch;
        double dYaw = yawOff - lastYaw;
        // 与 TACZ 同向约定：正俯仰=枪口上抬→XRot 减小；正偏航→YRot 减小
        player.setXRot(player.getXRot() - (float) dPitch);
        player.setYRot(player.getYRot() - (float) dYaw);
        lastPitch = pitchOff;
        lastYaw = yawOff;
    }

    /** 枪械射速（RPM），缺省 600。 */
    private static int gunRpm(IGun gun, ItemStack stack) {
        var opt = TimelessAPI.getClientGunIndex(gun.getGunId(stack));
        if (opt.isEmpty()) {
            return 600;
        }
        GunData data = opt.get().getGunData();
        int rpm = data != null ? data.getRoundsPerMinute() : 600;
        return rpm > 0 ? rpm : 600;
    }

    /**
     * 开镜缩放（沿用 TACZ 公式）：腰射 1.0；开镜后除以
     * min(1.5, √倍率)，高倍镜后坐力明显更小更稳。
     */
    private static double adsScale(
        Minecraft mc, LocalPlayer player, IGun gun, ItemStack stack
    ) {
        float aim = IClientPlayerGunOperator.fromLocalPlayer(player)
            .getClientAimingProgress(mc.getDeltaFrameTime());
        double denominator = Math.min(1.5, Math.sqrt(gun.getAimingZoom(stack)));
        return (1.0 - aim) + aim / denominator;
    }

    /** 姿态系数：冲刺/腾空最大，移动次之，下蹲最小。 */
    private static double stanceScale(LocalPlayer player) {
        if (player.isSprinting()) {
            return 1.28;
        }
        if (!player.onGround()) {
            return 1.18;
        }
        if (player.isCrouching()) {
            return 0.85;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.12 * 0.12) {
            return 1.10;
        }
        return 1.0;
    }
}
