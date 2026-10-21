package com.fragmentation.network;

import com.fragmentation.client.screen.SettlementScreen;
import com.fragmentation.combat.HitRecord;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：/fragmentation jiesuan 结算数据：
 * 行动时长、俯视地图色块、行动路线、淘汰列表、每个淘汰目标的双边交战记录。
 */
public class SettlementPacket {

    public long startTick;
    public long durationTicks;

    // 地图色块网格
    public int originX;
    public int originZ;
    public int mapWidth;
    public int mapDepth;
    public int step;
    public int[] colors;

    // 行动路线
    public final List<RoutePoint> route = new ArrayList<>();
    // 淘汰列表（最早 → 最晚）
    public final List<KillInfo> kills = new ArrayList<>();
    // 与 kills 对齐：engagements[i] = {我方命中 dealt, 其对我方命中 taken}
    public final List<Engagement> engagements = new ArrayList<>();

    public static class RoutePoint {
        public final double x;
        public final double y;
        public final double z;
        public final String dim;

        public RoutePoint(double x, double y, double z, String dim) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
        }
    }

    public static class KillInfo {
        public final String victimName;
        public final boolean isPlayer;
        public final long tick;
        public final double x;
        public final double y;
        public final double z;
        public final String dim;

        public KillInfo(String victimName, boolean isPlayer, long tick,
                        double x, double y, double z, String dim) {
            this.victimName = victimName;
            this.isPlayer = isPlayer;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
        }
    }

    public static class Engagement {
        public final List<HitRecord> dealt;
        public final List<HitRecord> taken;

        public Engagement(List<HitRecord> dealt, List<HitRecord> taken) {
            this.dealt = dealt;
            this.taken = taken;
        }
    }

    // ================= 编解码 =================

    public static void encode(SettlementPacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.startTick);
        buf.writeLong(p.durationTicks);

        buf.writeVarInt(p.originX);
        buf.writeVarInt(p.originZ);
        buf.writeVarInt(p.mapWidth);
        buf.writeVarInt(p.mapDepth);
        buf.writeVarInt(p.step);
        buf.writeVarInt(p.colors.length);
        for (int c : p.colors) {
            buf.writeInt(c);
        }

        buf.writeVarInt(p.route.size());
        for (RoutePoint r : p.route) {
            buf.writeDouble(r.x);
            buf.writeDouble(r.y);
            buf.writeDouble(r.z);
            buf.writeUtf(r.dim);
        }

        buf.writeVarInt(p.kills.size());
        for (KillInfo k : p.kills) {
            buf.writeUtf(k.victimName);
            buf.writeBoolean(k.isPlayer);
            buf.writeLong(k.tick);
            buf.writeDouble(k.x);
            buf.writeDouble(k.y);
            buf.writeDouble(k.z);
            buf.writeUtf(k.dim);
        }

        buf.writeVarInt(p.engagements.size());
        for (Engagement e : p.engagements) {
            writeRecords(buf, e.dealt);
            writeRecords(buf, e.taken);
        }
    }

    public static SettlementPacket decode(FriendlyByteBuf buf) {
        SettlementPacket p = new SettlementPacket();
        p.startTick = buf.readLong();
        p.durationTicks = buf.readLong();

        p.originX = buf.readVarInt();
        p.originZ = buf.readVarInt();
        p.mapWidth = buf.readVarInt();
        p.mapDepth = buf.readVarInt();
        p.step = buf.readVarInt();
        int colorCount = buf.readVarInt();
        p.colors = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            p.colors[i] = buf.readInt();
        }

        int routeCount = buf.readVarInt();
        for (int i = 0; i < routeCount; i++) {
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            String dim = buf.readUtf();
            p.route.add(new RoutePoint(x, y, z, dim));
        }

        int killCount = buf.readVarInt();
        for (int i = 0; i < killCount; i++) {
            String name = buf.readUtf();
            boolean isPlayer = buf.readBoolean();
            long tick = buf.readLong();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            String dim = buf.readUtf();
            p.kills.add(new KillInfo(name, isPlayer, tick, x, y, z, dim));
        }

        int engagementCount = buf.readVarInt();
        for (int i = 0; i < engagementCount; i++) {
            List<HitRecord> dealt = readRecords(buf);
            List<HitRecord> taken = readRecords(buf);
            p.engagements.add(new Engagement(dealt, taken));
        }
        return p;
    }

    private static void writeRecords(FriendlyByteBuf buf, List<HitRecord> list) {
        buf.writeVarInt(list.size());
        for (HitRecord r : list) {
            HitRecord.encode(r, buf);
        }
    }

    private static List<HitRecord> readRecords(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<HitRecord> list = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            list.add(HitRecord.decode(buf));
        }
        return list;
    }

    public static void handle(SettlementPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> SettlementScreen.open(packet)
            )
        );
        context.setPacketHandled(true);
    }
}
