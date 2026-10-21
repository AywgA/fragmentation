package com.fragmentation.client;

import com.fragmentation.FragmentationMod;
import com.fragmentation.explosive.GrenadeExplosionHandler;
import com.fragmentation.network.ExplosionVizPacket;
import com.fragmentation.network.TrajectoryPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试可视化客户端渲染。
 * J：正常3D → 冲击波平面示意图 → 穿透平面示意图 → 正常3D（循环）；
 * K：清除全部显示。
 *
 * 冲击波渲染为一个真正的"波面"——参考压力场示意图的多层等压
 * 色带填充曲面：波前红色高压锋面，向内橙→黄→绿→青→蓝递减，
 * 波后中心留出低压空腔；球面（3D）或环形波带（平面）从爆心以
 * 固定波速向外扩张，波面遇墙即在墙前停住，同时从墙面往回反弹
 * 出回震曲面（颜色按反射威力红→黄→绿）。蓝色线为弹片飞行路径，
 * 终点红十字=被掩体阻止、绿十字=命中生物体。
 */
@Mod.EventBusSubscriber(
    modid = FragmentationMod.MOD_ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ClientTrajectoryRenderer {
    // ================= 冲击波物理参数（Rankine–Hugoniot / Friedlander） =================
    // 波前速度 D = c0·√(1+6ΔP/P0)：近场超压高 → 超音速；随超压衰减
    // 趋向音速。波前轨迹对时间积分：r(t) = V_S·t + (V0-V_S)·τ·(1-e^(-t/τ))。
    /** 波前初速（格/tick，近场超音速）。 */
    private static final double V_FRONT_START = 2.4;
    /** 音速（格/tick，远场波前趋向速度）。 */
    private static final double V_SONIC = 0.45;
    /** 波前减速特征时间（tick）。 */
    private static final double FRONT_TAU = 2.2;

    /**
     * 等压色带：参考压力场示意图，波前（最外）红 → 橙 → 黄 → 绿
     * → 青 → 蓝（向内压力递减），最后一层为 Friedlander 负压相
     * （低于环境压力的回吸谷），波后中心留出低压空腔。
     */
    private static final int[] WAVE_BAND_COLORS = {
        0xFF2818, 0xFF7818, 0xFFD018, 0x68E030, 0x28D8C8, 0x2878E0, 0x2850C0
    };
    /** 各层色带的不透明度（外层最实，向内渐淡）。 */
    private static final int[] WAVE_BAND_ALPHAS = {160, 140, 120, 100, 85, 70, 42};
    /** 正压相基准厚度（格）——随传播拉伸（波形展宽）。 */
    private static final double WAVE_BAND_GAP = 1.1;
    /** 回震曲面的不透明度。 */
    private static final int REFLECT_ALPHA = 120;
    /** 弹片飞行速度（格/tick）。 */
    private static final double FRAG_SPEED = 1.1;
    /** 单次爆炸可视化最长保留时间（tick，约 45 秒）。 */
    private static final long WAVE_LIFE_TICKS = 900;

    private static final List<ExplosionView> EXPLOSIONS = new ArrayList<>();
    private static final List<StaticSegment> STATIC_SEGMENTS = new ArrayList<>();

    private static boolean trajectoryEnabled;
    private static ViewMode mode = ViewMode.NORMAL;

    public enum ViewMode {
        NORMAL("正常 3D 视图"),
        SHOCK_PLAN("冲击波平面示意图"),
        PEN_PLAN("穿透平面示意图");

        private final String displayName;

        ViewMode(String displayName) {
            this.displayName = displayName;
        }
    }

    private ClientTrajectoryRenderer() {
    }

    // ================= 数据包入口 =================

    public static void setTrajectoryEnabled(boolean enabled) {
        trajectoryEnabled = enabled;
        if (!enabled) {
            clearAll();
            mode = ViewMode.NORMAL;
        }
    }

    /** 旧的单段包（子弹弹道/跳弹/穿透）：静态完整显示。 */
    public static void handle(TrajectoryPacket packet) {
        if (packet.action() == TrajectoryPacket.Action.CLEAR) {
            clearAll();
            return;
        }
        if (isFinite(packet.start())
            && isFinite(packet.end())
            && packet.start().distanceToSqr(packet.end()) > 1.0E-12D) {
            STATIC_SEGMENTS.add(new StaticSegment(
                packet.dimension(),
                packet.start(),
                packet.end(),
                packet.type()
            ));
        }
    }

    /** 爆炸批量包：整组数据同时保留，供三种视图分别取用。 */
    public static void handleViz(ExplosionVizPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long birthTick = minecraft.level.getGameTime();
        EXPLOSIONS.add(new ExplosionView(
            packet.dimension(),
            packet.center(),
            birthTick,
            packet.shockRadius(),
            List.copyOf(packet.fragLines()),
            List.copyOf(packet.volumeDirs()),
            List.copyOf(packet.planDirs()),
            List.copyOf(packet.penFragments())
        ));
    }

    // ================= J / K 按键 =================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        while (ModKeyBindings.SWITCH_VIEW.consumeClick()) {
            // 调试按键：未开启时静默吞掉，不做任何提示
            if (!trajectoryEnabled) {
                continue;
            }
            mode = switch (mode) {
                case NORMAL -> ViewMode.SHOCK_PLAN;
                case SHOCK_PLAN -> ViewMode.PEN_PLAN;
                case PEN_PLAN -> ViewMode.NORMAL;
            };
            minecraft.gui.setOverlayMessage(
                Component.literal("§e[调试显示] §f当前视图：" + mode.displayName
                    + " §7（J 切换，K 清除）"),
                false
            );
        }

        while (ModKeyBindings.CLEAR_VIEW.consumeClick()) {
            // 调试按键：未开启时静默吞掉，不做任何提示
            if (!trajectoryEnabled) {
                continue;
            }
            clearAll();
            minecraft.gui.setOverlayMessage(
                Component.literal("§e[调试显示] §f已清除全部显示"),
                false
            );
        }
    }

    // ================= 渲染 =================

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (STATIC_SEGMENTS.isEmpty() && EXPLOSIONS.isEmpty()) {
            return;
        }

        String currentDimension =
            minecraft.level.dimension().location().toString();
        long now = minecraft.level.getGameTime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers =
            minecraft.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();
        RenderSystem.lineWidth(2.5F);

        // BufferSource 只有一个共享 BufferBuilder：切换 RenderType
        // 会改变其顶点格式。因此分两个阶段渲染，每种格式写完立即
        // endBatch 提交，绝不在格式切换后继续写前一种格式的顶点
        // （否则抛 "Not filled all elements of the vertex"）。
        //
        // 第一阶段：波面（半透明填充曲面，POSITION_COLOR 格式）
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        switch (mode) {
            case NORMAL ->
                renderNormalWaves(quads, pose, currentDimension, now);
            case SHOCK_PLAN ->
                renderShockPlanWaves(quads, pose, currentDimension, now);
            case PEN_PLAN -> {
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        // 第二阶段：弹道与弹片线（POSITION_COLOR_NORMAL 格式）
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        switch (mode) {
            case NORMAL ->
                renderNormalLines(lines, pose, normalMatrix,
                    currentDimension, now);
            case SHOCK_PLAN -> {
            }
            case PEN_PLAN -> renderPenPlan(
                lines, pose, normalMatrix, currentDimension, now);
        }
        buffers.endBatch(RenderType.lines());

        poseStack.popPose();
        RenderSystem.lineWidth(1.0F);
    }

    /** 正常 3D 视图的线框部分：静态弹道 + 蓝色弹片飞行线。 */
    private static void renderNormalLines(
        VertexConsumer lines,
        Matrix4f pose,
        Matrix3f normalMatrix,
        String dimension,
        long now
    ) {
        for (StaticSegment segment : STATIC_SEGMENTS) {
            if (dimension.equals(segment.dimension())) {
                addLine(lines, pose, normalMatrix,
                    segment.start(), segment.end(),
                    typeRgb(segment.type()), typeRgb(segment.type()));
            }
        }
        for (ExplosionView explosion : EXPLOSIONS) {
            if (!dimension.equals(explosion.dimension())) {
                continue;
            }
            long age = now - explosion.birthTick();
            if (age > WAVE_LIFE_TICKS) {
                continue;
            }
            // 蓝色弹片：起点固定在爆心，末端沿方向生长（飞出）
            for (ExplosionVizPacket.Segment segment : explosion.fragLines()) {
                Vec3 grownEnd = growAlong(
                    segment.start(), segment.end(), age);
                if (segment.start().distanceToSqr(grownEnd) > 1.0E-12D) {
                    addLine(lines, pose, normalMatrix,
                        segment.start(), grownEnd,
                        segment.startRgb(), segment.endRgb());
                }
            }
        }
    }

    /** 正常 3D 视图的波面部分：球面波前曲面 + 回震曲面。 */
    private static void renderNormalWaves(
        VertexConsumer quads,
        Matrix4f pose,
        String dimension,
        long now
    ) {
        for (ExplosionView explosion : EXPLOSIONS) {
            if (!dimension.equals(explosion.dimension())) {
                continue;
            }
            long age = now - explosion.birthTick();
            if (age > WAVE_LIFE_TICKS) {
                continue;
            }
            renderWaveSurface(
                quads, pose, explosion,
                explosion.volumeDirs(),
                ExplosionVizPacket.VOLUME_ROWS,
                ExplosionVizPacket.VOLUME_COLS,
                age,
                true);
        }
    }

    /** 冲击波平面示意图：环形波带向外扩张 + 回震波带。 */
    private static void renderShockPlanWaves(
        VertexConsumer quads,
        Matrix4f pose,
        String dimension,
        long now
    ) {
        for (ExplosionView explosion : EXPLOSIONS) {
            if (!dimension.equals(explosion.dimension())) {
                continue;
            }
            long age = now - explosion.birthTick();
            if (age > WAVE_LIFE_TICKS) {
                continue;
            }
            renderWaveSurface(
                quads, pose, explosion,
                explosion.planDirs(),
                1,
                ExplosionVizPacket.PLAN_DIRS,
                age,
                false);
        }
    }

    /**
     * 波前轨迹（Rankine–Hugoniot）：对速度 v(t)=V_S+(V0-V_S)·e^(-t/τ)
     * 积分——近场以超音速冲出，随超压衰减逐渐减速趋向音速。
     */
    private static double frontRadius(long ticks) {
        return V_SONIC * ticks
            + (V_FRONT_START - V_SONIC) * FRONT_TAU
                * (1.0 - Math.exp(-ticks / FRONT_TAU));
    }

    /**
     * 波幅消散：透明度不做整体硬性截断，而是由每个顶点按"几何扩散
     * ×穿障吸收"后的真实剩余能量逐点衰减（energyAlpha）——波在开阔
     * 空间只会随球面摊薄越来越淡，直到能量低于阈值（服务器剖面
     * reach 即按此截止），绝不会在能量未耗尽时凭空消失。
     */
    private static int energyAlpha(
        ExplosionVizPacket.WaveDir dir,
        double distance,
        double shockRadius,
        int baseAlpha
    ) {
        double energy = GrenadeExplosionHandler.spreadFactor(distance, shockRadius)
            * sampleFactor(dir.samples(), distance);
        return (int) Math.round(baseAlpha * Mth.clamp(energy, 0.0, 1.0));
    }

    /** 查询传播到指定距离时剩余的穿障吸收系数（采样线性插值）。 */
    private static double sampleFactor(
        List<ExplosionVizPacket.WaveSample> samples,
        double distance
    ) {
        if (samples.isEmpty()) {
            return 1.0;
        }
        double prevDistance = 0.0;
        double prevFactor = 1.0;
        for (ExplosionVizPacket.WaveSample sample : samples) {
            if (sample.distance() >= distance) {
                double span = sample.distance() - prevDistance;
                if (span <= 1.0E-6) {
                    return sample.factor();
                }
                double t = (distance - prevDistance) / span;
                return prevFactor + (sample.factor() - prevFactor) * t;
            }
            prevDistance = sample.distance();
            prevFactor = sample.factor();
        }
        return prevFactor;
    }

    /**
     * 波面渲染核心（Friedlander 压力波）：
     * 波前 = frontRadius(t)（超音速→音速减速）；正压相各等压带
     * （红→橙→黄→绿→青→蓝）随传播展宽（波形拉伸）；最内层为
     * 负压相回吸谷；再向内不绘制——波后低压空腔。波面透明度逐
     * 顶点按真实剩余能量衰减。曲面网格由采样方向连成，单方向超过
     * 传播截止距离（能量耗尽或被墙吸收）后该处贴在掩体前方。
     * 波撞墙后回震曲面从墙面往回传播。
     */
    private static void renderWaveSurface(
        VertexConsumer quads,
        Matrix4f pose,
        ExplosionView explosion,
        List<ExplosionVizPacket.WaveDir> dirs,
        int rows,
        int cols,
        long age,
        boolean volume
    ) {
        double radiusFront = frontRadius(age);
        // 正压相厚度随传播拉伸（波形展宽）
        double gap = WAVE_BAND_GAP * (1.0 + 0.08 * radiusFront);
        for (int k = 0; k < WAVE_BAND_COLORS.length; k++) {
            double r = radiusFront - k * gap;
            if (r <= 0.35) {
                break;
            }
            int color = WAVE_BAND_COLORS[k];
            int alpha = WAVE_BAND_ALPHAS[k];
            if (volume) {
                drawSphereSurface(quads, pose, explosion,
                    dirs, rows, cols, r, color, alpha);
            } else {
                drawPlanBand(quads, pose, explosion,
                    dirs, cols, r, color, alpha, gap);
            }
        }
        drawReflectionSurface(
            quads, pose, explosion, dirs, rows, cols, radiusFront, volume);
    }

    /** 球面波前曲面：仰角×方位网格在指定半径处连成四边形面。 */
    private static void drawSphereSurface(
        VertexConsumer quads,
        Matrix4f pose,
        ExplosionView explosion,
        List<ExplosionVizPacket.WaveDir> dirs,
        int rows,
        int cols,
        double radius,
        int bandColor,
        int alpha
    ) {
        for (int row = 0; row < rows - 1; row++) {
            for (int col = 0; col < cols; col++) {
                int colNext = (col + 1) % cols;
                ExplosionVizPacket.WaveDir a = dirs.get(row * cols + col);
                ExplosionVizPacket.WaveDir b =
                    dirs.get(row * cols + colNext);
                ExplosionVizPacket.WaveDir c =
                    dirs.get((row + 1) * cols + colNext);
                ExplosionVizPacket.WaveDir d =
                    dirs.get((row + 1) * cols + col);
                if (a.reach() < 0.05 && b.reach() < 0.05
                    && c.reach() < 0.05 && d.reach() < 0.05) {
                    continue;
                }
                addWaveQuad(quads, pose, explosion,
                    a, b, c, d, radius, bandColor, alpha);
            }
        }
    }

    /** 水平面波前环带：外缘=波前（各方向被墙截止），内缘渐隐。 */
    private static void drawPlanBand(
        VertexConsumer quads,
        Matrix4f pose,
        ExplosionView explosion,
        List<ExplosionVizPacket.WaveDir> dirs,
        int cols,
        double outer,
        int bandColor,
        int alpha,
        double gap
    ) {
        for (int col = 0; col < cols; col++) {
            int colNext = (col + 1) % cols;
            ExplosionVizPacket.WaveDir a = dirs.get(col);
            ExplosionVizPacket.WaveDir b = dirs.get(colNext);
            double foa = Math.min(outer, a.reach());
            double fob = Math.min(outer, b.reach());
            if (foa < 0.1 && fob < 0.1) {
                continue;
            }
            double fia = Math.max(0.12,
                Math.min(outer - gap, a.reach()));
            double fib = Math.max(0.12,
                Math.min(outer - gap, b.reach()));
            double sr = explosion.shockRadius();
            int aa = energyAlpha(a, foa, sr, alpha);
            int ab = energyAlpha(b, fob, sr, alpha);
            int ai = energyAlpha(a, fia, sr, alpha * 45 / 100);
            int aj = energyAlpha(b, fib, sr, alpha * 45 / 100);
            if (aa + ab + ai + aj < 12) {
                continue;
            }
            Vec3 p1 = explosion.center().add(a.dir().scale(foa));
            Vec3 p2 = explosion.center().add(b.dir().scale(fob));
            Vec3 p3 = explosion.center().add(b.dir().scale(fib));
            Vec3 p4 = explosion.center().add(a.dir().scale(fia));
            addQuad(quads, pose,
                p1, argb(bandColor, aa),
                p2, argb(bandColor, ab),
                p3, argb(bandColor, aj),
                p4, argb(bandColor, ai));
        }
    }

    /** 球面网格四边形：各方向取 min(半径, 截止距离)，逐顶点能量透明度。 */
    private static void addWaveQuad(
        VertexConsumer quads,
        Matrix4f pose,
        ExplosionView explosion,
        ExplosionVizPacket.WaveDir a,
        ExplosionVizPacket.WaveDir b,
        ExplosionVizPacket.WaveDir c,
        ExplosionVizPacket.WaveDir d,
        double radius,
        int bandColor,
        int alpha
    ) {
        double fa = Math.min(radius, a.reach());
        double fb = Math.min(radius, b.reach());
        double fc = Math.min(radius, c.reach());
        double fd = Math.min(radius, d.reach());
        if (fa < 0.05 && fb < 0.05 && fc < 0.05 && fd < 0.05) {
            return;
        }
        double sr = explosion.shockRadius();
        int aa = energyAlpha(a, fa, sr, alpha);
        int ab = energyAlpha(b, fb, sr, alpha);
        int ac = energyAlpha(c, fc, sr, alpha);
        int ad = energyAlpha(d, fd, sr, alpha);
        if (aa + ab + ac + ad < 12) {
            return;
        }
        Vec3 center = explosion.center();
        Vec3 p1 = center.add(a.dir().scale(fa));
        Vec3 p2 = center.add(b.dir().scale(fb));
        Vec3 p3 = center.add(c.dir().scale(fc));
        Vec3 p4 = center.add(d.dir().scale(fd));
        addQuad(quads, pose,
            p1, argb(bandColor, aa),
            p2, argb(bandColor, ab),
            p3, argb(bandColor, ac),
            p4, argb(bandColor, ad));
    }

    /**
     * 回震曲面：波前到达墙面后，回波从墙面沿入射反方向往回传播，
     * 威力 = 到墙入射压力 × 墙面反射率 × 回程衰减。
     * 3D：仰角×方位网格面；平面：环带（外缘=回波波前）。
     */
    private static void drawReflectionSurface(
        VertexConsumer quads,
        Matrix4f pose,
        ExplosionView explosion,
        List<ExplosionVizPacket.WaveDir> dirs,
        int rows,
        int cols,
        double radiusFront,
        boolean volume
    ) {
        double shockRadius = explosion.shockRadius();
        Vec3 center = explosion.center();

        if (volume) {
            for (int row = 0; row < rows - 1; row++) {
                for (int col = 0; col < cols; col++) {
                    int colNext = (col + 1) % cols;
                    double[] sa = reflectionState(
                        dirs.get(row * cols + col), radiusFront, shockRadius);
                    double[] sb = reflectionState(
                        dirs.get(row * cols + colNext), radiusFront, shockRadius);
                    double[] sc = reflectionState(
                        dirs.get((row + 1) * cols + colNext), radiusFront, shockRadius);
                    double[] sd = reflectionState(
                        dirs.get((row + 1) * cols + col), radiusFront, shockRadius);
                    if (sa == null || sb == null || sc == null || sd == null) {
                        continue;
                    }
                    Vec3 p1 = center.add(
                        dirs.get(row * cols + col).dir().scale(sa[0]));
                    Vec3 p2 = center.add(
                        dirs.get(row * cols + colNext).dir().scale(sb[0]));
                    Vec3 p3 = center.add(
                        dirs.get((row + 1) * cols + colNext).dir().scale(sc[0]));
                    Vec3 p4 = center.add(
                        dirs.get((row + 1) * cols + col).dir().scale(sd[0]));
                    addQuad(quads, pose,
                        p1, argb((int) sa[1], (int) (REFLECT_ALPHA * sa[2])),
                        p2, argb((int) sb[1], (int) (REFLECT_ALPHA * sb[2])),
                        p3, argb((int) sc[1], (int) (REFLECT_ALPHA * sc[2])),
                        p4, argb((int) sd[1], (int) (REFLECT_ALPHA * sd[2])));
                }
            }
            return;
        }

        // 平面回震环带：外缘=各方向回波波前，内缘=回波带宽度
        for (int col = 0; col < cols; col++) {
            int colNext = (col + 1) % cols;
            ExplosionVizPacket.WaveDir da = dirs.get(col);
            ExplosionVizPacket.WaveDir db = dirs.get(colNext);
            double[] sa = reflectionState(da, radiusFront, shockRadius);
            double[] sb = reflectionState(db, radiusFront, shockRadius);
            if (sa == null || sb == null) {
                continue;
            }
            double outerA = sa[0];
            double outerB = sb[0];
            double innerA = Math.max(0.15, outerA - 1.2);
            double innerB = Math.max(0.15, outerB - 1.2);
            Vec3 p1 = center.add(da.dir().scale(outerA));
            Vec3 p2 = center.add(db.dir().scale(outerB));
            Vec3 p3 = center.add(db.dir().scale(innerB));
            Vec3 p4 = center.add(da.dir().scale(innerA));
            addQuad(quads, pose,
                p1, argb((int) sa[1], (int) (REFLECT_ALPHA * sa[2])),
                p2, argb((int) sb[1], (int) (REFLECT_ALPHA * sb[2])),
                p3, argb((int) sb[1], (int) (REFLECT_ALPHA * sb[2] / 2)),
                p4, argb((int) sa[1], (int) (REFLECT_ALPHA * sa[2] / 2)));
        }
    }

    /**
     * 单方向回波状态：null=无回波；否则
     * {回波距爆心距离, 颜色RGB, 透明度系数（反射后剩余能量）}。
     */
    private static double[] reflectionState(
        ExplosionVizPacket.WaveDir dir,
        double waveRadius,
        double shockRadius
    ) {
        if (dir.wallDist() <= 0.2 || dir.wallReflectivity() <= 0.0) {
            return null;
        }
        // 到达墙面的能量：几何扩散×穿障吸收（不设硬性距离截断）
        double incoming =
            GrenadeExplosionHandler.spreadFactor(dir.wallDist(), shockRadius)
                * dir.factorBeforeWall();
        double amplitude = incoming * dir.wallReflectivity();
        if (amplitude < 0.04) {
            return null;
        }
        double tBack = waveRadius - dir.wallDist();
        if (tBack <= 0.0) {
            return null; // 波还没打到墙
        }
        double reflFront = dir.wallDist() - tBack;
        if (reflFront < 0.15) {
            return null; // 回波已回到爆心
        }
        double falloff = 1.0 - tBack / (dir.wallDist() * 1.15);
        if (falloff <= 0.0) {
            return null;
        }
        double energy = Mth.clamp(amplitude * falloff, 0.0, 1.0);
        return new double[]{reflFront, powerRgb(energy), energy};
    }

    /** 穿透平面示意图：弹片路径水平投影（蓝）+ 终点结局十字标记。 */
    private static void renderPenPlan(
        VertexConsumer lines,
        Matrix4f pose,
        Matrix3f normalMatrix,
        String dimension,
        long now
    ) {
        for (ExplosionView explosion : EXPLOSIONS) {
            if (!dimension.equals(explosion.dimension())) {
                continue;
            }
            long age = now - explosion.birthTick();
            if (age > WAVE_LIFE_TICKS) {
                continue;
            }
            for (ExplosionVizPacket.PlanFragment fragment :
                explosion.fragments()) {
                Vec3 start = fragment.start();
                Vec3 end = fragment.end();
                Vec3 grownEnd = growAlong(start, end, age);
                if (start.distanceToSqr(grownEnd) > 1.0E-12D) {
                    addLine(lines, pose, normalMatrix,
                        start, grownEnd,
                        GrenadeExplosionHandler.fragmentRgb(),
                        GrenadeExplosionHandler.fragmentRgb());
                }

                // 弹片飞到终点后绘制结局标记
                double length = start.distanceTo(end);
                boolean arrived = FRAG_SPEED * age >= length;
                if (arrived && length > 1.0E-4) {
                    if (fragment.outcome() == ExplosionVizPacket.OUTCOME_BLOCKED) {
                        addCross(lines, pose, normalMatrix,
                            end, GrenadeExplosionHandler.BLOCKED_RGB);
                    } else if (fragment.outcome() == ExplosionVizPacket.OUTCOME_ENTITY) {
                        addCross(lines, pose, normalMatrix,
                            end, GrenadeExplosionHandler.ENTITY_RGB);
                    }
                }
            }
        }
    }

    /** 水平面上的小十字标记。 */
    private static void addCross(
        VertexConsumer lines,
        Matrix4f pose,
        Matrix3f normalMatrix,
        Vec3 center,
        int rgb
    ) {
        double size = 0.13;
        addLine(lines, pose, normalMatrix,
            new Vec3(center.x - size, center.y, center.z),
            new Vec3(center.x + size, center.y, center.z), rgb, rgb);
        addLine(lines, pose, normalMatrix,
            new Vec3(center.x, center.y, center.z - size),
            new Vec3(center.x, center.y, center.z + size), rgb, rgb);
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
        mode = ViewMode.NORMAL;
        trajectoryEnabled = false;
    }

    // ================= 威力配色（与服务器伤害模型一致） =================

    /**
     * 冲击波威力 → 颜色：红(1.0，致命区) → 黄(0.5，重伤区)
     * → 绿(0.0，边缘区)。RGB 空间内分段线性且在 0.5 处连续，
     * 相邻顶点经 GPU 插值后整体为平滑渐变，没有色阶跳跃。
     */
    private static int powerRgb(double intensity) {
        double t = Mth.clamp(intensity, 0.0, 1.0);
        double red;
        double green;
        if (t >= 0.5) {
            red = 1.0;
            green = 2.0 * (t - 0.5);
        } else {
            red = 2.0 * t;
            green = 1.0;
        }
        return ((int) (red * 255.0) << 16)
            | ((int) (green * 255.0) << 8);
    }

    /** RGB → ARGB（附透明度）。 */
    private static int argb(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    // ================= 动画与基础工具 =================

    /** 线段末端沿飞行方向生长，progress=1 后为完整路径。 */
    private static Vec3 growAlong(Vec3 start, Vec3 end, long age) {
        Vec3 flight = end.subtract(start);
        double length = flight.length();
        if (length <= 1.0E-6) {
            return end;
        }
        double progress = Mth.clamp(FRAG_SPEED * age / length, 0.0, 1.0);
        return start.add(flight.scale(progress));
    }

    private static int typeRgb(TrajectoryPacket.Type type) {
        return ((int) (type.red() * 255.0F) << 16)
            | ((int) (type.green() * 255.0F) << 8)
            | (int) (type.blue() * 255.0F);
    }

    /** 填充一个四边形面（含双面：正反两次绕序，任意角度可见）。 */
    private static void addQuad(
        VertexConsumer consumer,
        Matrix4f pose,
        Vec3 p1, int c1,
        Vec3 p2, int c2,
        Vec3 p3, int c3,
        Vec3 p4, int c4
    ) {
        quadVertex(consumer, pose, p1, c1);
        quadVertex(consumer, pose, p2, c2);
        quadVertex(consumer, pose, p3, c3);
        quadVertex(consumer, pose, p4, c4);
        // 反面绕序（从另一侧看同样可见）
        quadVertex(consumer, pose, p1, c1);
        quadVertex(consumer, pose, p4, c4);
        quadVertex(consumer, pose, p3, c3);
        quadVertex(consumer, pose, p2, c2);
    }

    private static void quadVertex(
        VertexConsumer consumer,
        Matrix4f pose,
        Vec3 point,
        int argb
    ) {
        consumer.vertex(
                pose,
                (float) point.x,
                (float) point.y,
                (float) point.z
            )
            .color(
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF,
                (argb >>> 24) & 0xFF
            )
            .endVertex();
    }

    private static void addLine(
        VertexConsumer consumer,
        Matrix4f pose,
        Matrix3f normalMatrix,
        Vec3 start,
        Vec3 end,
        int startRgb,
        int endRgb
    ) {
        Vec3 direction = end.subtract(start).normalize();
        addVertex(consumer, pose, normalMatrix,
            start, direction, startRgb);
        addVertex(consumer, pose, normalMatrix,
            end, direction, endRgb);
    }

    private static void addVertex(
        VertexConsumer consumer,
        Matrix4f pose,
        Matrix3f normalMatrix,
        Vec3 point,
        Vec3 normal,
        int rgb
    ) {
        consumer.vertex(
                pose,
                (float) point.x,
                (float) point.y,
                (float) point.z
            )
            .color(
                (rgb >> 16) & 0xFF,
                (rgb >> 8) & 0xFF,
                rgb & 0xFF,
                255
            )
            .normal(
                normalMatrix,
                (float) normal.x,
                (float) normal.y,
                (float) normal.z
            )
            .endVertex();
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null
            && Double.isFinite(vector.x)
            && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    private static void clearAll() {
        EXPLOSIONS.clear();
        STATIC_SEGMENTS.clear();
    }

    private record StaticSegment(
        String dimension,
        Vec3 start,
        Vec3 end,
        TrajectoryPacket.Type type
    ) {
    }

    private record ExplosionView(
        String dimension,
        Vec3 center,
        long birthTick,
        double shockRadius,
        List<ExplosionVizPacket.Segment> fragLines,
        List<ExplosionVizPacket.WaveDir> volumeDirs,
        List<ExplosionVizPacket.WaveDir> planDirs,
        List<ExplosionVizPacket.PlanFragment> fragments
    ) {
    }
}
