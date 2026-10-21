package com.fragmentation.network;

import com.fragmentation.client.ClientTrajectoryRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 一次爆炸的全部调试可视化数据，合并为单个数据包下发。
 *
 * 冲击波不再以"射线线段"表达，而是发送每个采样方向上的传播剖面
 * （reach=传播截止距离、首墙信息、威力衰减采样），由客户端在渲染
 * 时动态生成一条真正的波面：一圈圈等间距的环（像水波）从爆心以
 * 固定波速向外扩张，颜色按该位置的实际威力红→黄→绿渐变；波面撞
 * 到墙后在墙前停住，并从墙面往回反弹出回震环。
 *
 *   fragLines    —— 蓝色弹片 3D 路径
 *   volumeDirs   —— 3D 球面波前方向剖面（VOLUME_ROWS×VOLUME_COLS）
 *   planDirs     —— 水平面波前方向剖面（PLAN_DIRS 个水平方向）
 *   penFragments —— 穿透平面图：弹片路径水平投影 + 结局标记
 * 三类数据同包到达：按 J 切换时无需重新触发爆炸。
 */
public record ExplosionVizPacket(
    String dimension,
    Vec3 center,
    double shockRadius,
    List<Segment> fragLines,
    List<WaveDir> volumeDirs,
    List<WaveDir> planDirs,
    List<PlanFragment> penFragments
) {
    /** 弹片结局：飞到杀伤边缘 / 被掩体阻止 / 命中生物体。 */
    public static final byte OUTCOME_RANGE = 0;
    public static final byte OUTCOME_BLOCKED = 1;
    public static final byte OUTCOME_ENTITY = 2;

    /** 3D 球面采样：仰角行数与方位列数（与服务器一致）。 */
    public static final int VOLUME_ROWS = 7;
    public static final int VOLUME_COLS = 48;
    /** 水平切面采样方向数。 */
    public static final int PLAN_DIRS = 144;

    /**
     * 一条弹片可视化线段及其两端颜色（0xRRGGBB）。
     * 两端异色时，线段在 GPU 中按顶点色平滑插值，天然形成渐变。
     */
    public record Segment(Vec3 start, Vec3 end, int startRgb, int endRgb) {
    }

    /**
     * 单个传播方向的冲击波剖面：
     *   dir              —— 采样方向
     *   reach            —— 传播截止距离（掩体前方），波面在此停住
     *   wallDist         —— 路径上首个阻挡面距离（无墙为 0）
     *   wallReflectivity —— 首墙反射率（决定回震强度）
     *   factorBeforeWall —— 到墙前的穿障剩余系数
     *   samples          —— (距离, 剩余威力系数) 采样，客户端插值取色
     */
    public record WaveDir(
        Vec3 dir,
        float reach,
        float wallDist,
        float wallReflectivity,
        float factorBeforeWall,
        List<WaveSample> samples
    ) {
    }

    /** 威力衰减采样点：传播到 distance 时剩余系数 factor。 */
    public record WaveSample(float distance, float factor) {
    }

    public record PlanFragment(Vec3 start, Vec3 end, byte outcome) {
    }

    public static void encode(ExplosionVizPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.dimension);
        writeVec3(buffer, packet.center);
        buffer.writeDouble(packet.shockRadius);
        buffer.writeCollection(packet.fragLines, ExplosionVizPacket::writeSegment);
        buffer.writeCollection(packet.volumeDirs, ExplosionVizPacket::writeWaveDir);
        buffer.writeCollection(packet.planDirs, ExplosionVizPacket::writeWaveDir);
        buffer.writeCollection(packet.penFragments, (buf, fragment) -> {
            writeVec3(buf, fragment.start());
            writeVec3(buf, fragment.end());
            buf.writeByte(fragment.outcome());
        });
    }

    public static ExplosionVizPacket decode(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf();
        Vec3 center = readVec3(buffer);
        double shockRadius = buffer.readDouble();
        List<Segment> fragLines =
            buffer.readCollection(ArrayList::new, ExplosionVizPacket::readSegment);
        List<WaveDir> volumeDirs =
            buffer.readCollection(ArrayList::new, ExplosionVizPacket::readWaveDir);
        List<WaveDir> planDirs =
            buffer.readCollection(ArrayList::new, ExplosionVizPacket::readWaveDir);
        List<PlanFragment> penFragments = buffer.readCollection(
            ArrayList::new,
            buf -> new PlanFragment(readVec3(buf), readVec3(buf), buf.readByte())
        );
        return new ExplosionVizPacket(
            dimension, center, shockRadius, fragLines, volumeDirs, planDirs, penFragments);
    }

    public static void handle(
        ExplosionVizPacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientTrajectoryRenderer.handleViz(packet)
            )
        );
        context.setPacketHandled(true);
    }

    private static void writeSegment(FriendlyByteBuf buffer, Segment segment) {
        writeVec3(buffer, segment.start());
        writeVec3(buffer, segment.end());
        buffer.writeInt(segment.startRgb());
        buffer.writeInt(segment.endRgb());
    }

    private static Segment readSegment(FriendlyByteBuf buffer) {
        Vec3 start = readVec3(buffer);
        Vec3 end = readVec3(buffer);
        return new Segment(start, end, buffer.readInt(), buffer.readInt());
    }

    private static void writeWaveDir(FriendlyByteBuf buffer, WaveDir dir) {
        buffer.writeFloat((float) dir.dir().x);
        buffer.writeFloat((float) dir.dir().y);
        buffer.writeFloat((float) dir.dir().z);
        buffer.writeFloat(dir.reach());
        buffer.writeFloat(dir.wallDist());
        buffer.writeFloat(dir.wallReflectivity());
        buffer.writeFloat(dir.factorBeforeWall());
        buffer.writeCollection(dir.samples(), (buf, sample) -> {
            buf.writeFloat(sample.distance());
            buf.writeFloat(sample.factor());
        });
    }

    private static WaveDir readWaveDir(FriendlyByteBuf buffer) {
        Vec3 dir = new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        float reach = buffer.readFloat();
        float wallDist = buffer.readFloat();
        float wallReflectivity = buffer.readFloat();
        float factorBeforeWall = buffer.readFloat();
        List<WaveSample> samples = buffer.readCollection(
            ArrayList::new,
            buf -> new WaveSample(buf.readFloat(), buf.readFloat())
        );
        return new WaveDir(dir, reach, wallDist, wallReflectivity, factorBeforeWall, samples);
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 vector) {
        buffer.writeDouble(vector.x);
        buffer.writeDouble(vector.y);
        buffer.writeDouble(vector.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readDouble()
        );
    }
}
