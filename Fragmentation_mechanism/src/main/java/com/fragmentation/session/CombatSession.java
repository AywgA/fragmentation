package com.fragmentation.session;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家一次行动（从登录/重生到死亡）的会话数据：
 * 起止 tick、行动路线采样点、淘汰目标列表。
 */
public class CombatSession {
    /** 路线采样间隔：每秒一次。 */
    public static final long ROUTE_SAMPLE_INTERVAL_TICKS = 20L;

    public long startTick;
    /** 死亡 tick；仍存活时为 -1。 */
    public long endTick = -1L;
    public final List<RoutePoint> route = new ArrayList<>();
    public final List<KillEntry> kills = new ArrayList<>();
    /** 最后一次路线采样 tick（用于节流）。 */
    public long lastRouteSampleTick = Long.MIN_VALUE;

    public CombatSession(long startTick) {
        this.startTick = startTick;
    }

    /** 采样一次玩家位置（与上一采样点距离够近时跳过）。 */
    public void sampleRoute(ServerLevel level, Vec3 pos, long tick) {
        ResourceKeyLike dim = ResourceKeyLike.of(level);
        if (!route.isEmpty()) {
            RoutePoint last = route.get(route.size() - 1);
            if (last.dim.equals(dim)
                    && last.x == pos.x && last.y == pos.y && last.z == pos.z) {
                lastRouteSampleTick = tick;
                return;
            }
            // 同一维度且移动极小：不新增点（避免原地抖动产生噪点）。
            if (last.dim.equals(dim)
                    && last.squaredDistance(pos) < 0.04D) {
                lastRouteSampleTick = tick;
                return;
            }
        }
        route.add(new RoutePoint(pos.x, pos.y, pos.z, tick, dim));
        lastRouteSampleTick = tick;
    }

    public boolean isEnded() {
        return endTick >= 0L;
    }

    /** 行动路线点。 */
    public static class RoutePoint {
        public final double x;
        public final double y;
        public final double z;
        public final long tick;
        public final ResourceKeyLike dim;

        public RoutePoint(double x, double y, double z, long tick, ResourceKeyLike dim) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
            this.dim = dim;
        }

        public double squaredDistance(Vec3 pos) {
            double dx = x - pos.x;
            double dy = y - pos.y;
            double dz = z - pos.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** 一次淘汰记录。 */
    public static class KillEntry {
        public final String victimName;
        public final boolean isPlayer;
        public final long tick;
        public final double x;
        public final double y;
        public final double z;
        public final ResourceKeyLike dim;

        public KillEntry(String victimName, boolean isPlayer, long tick,
                         double x, double y, double z, ResourceKeyLike dim) {
            this.victimName = victimName;
            this.isPlayer = isPlayer;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
        }
    }

    /** 维度标识（字符串包装，避免直接持有 ResourceKey 序列化问题）。 */
    public record ResourceKeyLike(String location) {
        public static ResourceKeyLike of(ServerLevel level) {
            return new ResourceKeyLike(level.dimension().location().toString());
        }
    }
}
