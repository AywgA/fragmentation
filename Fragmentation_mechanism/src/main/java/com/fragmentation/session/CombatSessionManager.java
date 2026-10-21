package com.fragmentation.session;

import com.fragmentation.FragmentationMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端会话管理：登录开始行动、每秒采样路线、死亡结算并冻结会话、
 * 重生开启新会话（保留上一次冻结会话供 /fragmentation jiesuan 查看）。
 * 同时提供死亡流程所需的击杀记录与结算地图色块采样。
 */
@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CombatSessionManager {

    /** 当前进行中的会话。 */
    private static final Map<UUID, CombatSession> ACTIVE = new ConcurrentHashMap<>();
    /** 每个玩家最近一次已结束的会话（重生后仍可查看）。 */
    private static final Map<UUID, CombatSession> LAST_ENDED = new ConcurrentHashMap<>();

    private CombatSessionManager() {
    }

    public static CombatSession getActive(UUID playerId) {
        return ACTIVE.get(playerId);
    }

    /**
     * /fragmentation jilu：显式开始对局记录。
     * 若已有进行中的记录则结束旧的并重新开始。
     */
    public static CombatSession startRecording(ServerPlayer player) {
        CombatSession old = ACTIVE.remove(player.getUUID());
        if (old != null && old.isEnded()) {
            LAST_ENDED.put(player.getUUID(), old);
        }
        return startSession(player);
    }

    /**
     * 取结算所用会话：
     * 玩家死亡 → 刚结束的会话；玩家存活时若当前会话尚无淘汰则看上一次会话。
     */
    public static CombatSession getForSettlement(ServerPlayer player) {
        CombatSession active = ACTIVE.get(player.getUUID());
        if (active != null && active.isEnded()) {
            return active;
        }
        if (active != null && !active.kills.isEmpty()) {
            return active;
        }
        CombatSession last = LAST_ENDED.get(player.getUUID());
        return last != null ? last : active;
    }

    // ================= 生命周期 =================

    // 对局记录由 /fragmentation jilu 显式开始，登录/重生不自动开启。

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CombatSession session = ACTIVE.remove(player.getUUID());
            if (session != null && session.isEnded()) {
                LAST_ENDED.put(player.getUUID(), session);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        CombatSession session = ACTIVE.get(player.getUUID());
        if (session == null) {
            return;
        }
        long tick = player.serverLevel().getGameTime();
        if (tick - session.lastRouteSampleTick >= CombatSession.ROUTE_SAMPLE_INTERVAL_TICKS) {
            session.sampleRoute(player.serverLevel(), player.position(), tick);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;

        // 凶手是玩家且已开启对局记录 → 记录淘汰（AI 或玩家）。
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            CombatSession session = ACTIVE.get(killer.getUUID());
            if (session != null && !session.isEnded()) {
                var victim = event.getEntity();
                session.kills.add(new CombatSession.KillEntry(
                    victim.getName().getString(),
                    victim instanceof ServerPlayer,
                    victim.level().getGameTime(),
                    victim.getX(), victim.getY(), victim.getZ(),
                    CombatSession.ResourceKeyLike.of((ServerLevel) victim.level())
                ));
                FragmentationMod.LOGGER.info("[Session] " + killer.getName().getString()
                    + " eliminated " + victim.getName().getString()
                    + " (player=" + (victim instanceof ServerPlayer) + ")");
            }
        }

        // 玩家自身死亡 → 冻结会话。
        if (event.getEntity() instanceof ServerPlayer victim) {
            CombatSession session = ACTIVE.get(victim.getUUID());
            if (session != null && !session.isEnded()) {
                long tick = victim.serverLevel().getGameTime();
                session.endTick = tick;
                // 死亡点补一个路线采样，路线收束到死亡位置。
                session.sampleRoute(victim.serverLevel(), victim.position(), tick);
                LAST_ENDED.put(victim.getUUID(), session);
            }
        }
    }

    private static CombatSession startSession(ServerPlayer player) {
        CombatSession session = new CombatSession(player.serverLevel().getGameTime());
        session.sampleRoute(player.serverLevel(), player.position(), session.startTick);
        ACTIVE.put(player.getUUID(), session);
        return session;
    }

    // ================= 结算地图采样 =================

    /** 结算地图：俯视色块网格 + 覆盖范围。 */
    public static class MapData {
        public int originX;
        public int originZ;
        public int width;
        public int depth;
        public int step;
        public int[] colors;
    }

    /**
     * 按会话路线（targetDim 维度内）采样俯视地图：
     * 覆盖路线范围 + 边距；区域过大时按 step>1 抽样，网格边长不超过 160。
     */
    public static MapData buildMapData(CombatSession session, ServerLevel level) {
        String dim = CombatSession.ResourceKeyLike.of(level).location();

        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        boolean any = false;

        for (CombatSession.RoutePoint p : session.route) {
            if (!p.dim.location().equals(dim)) continue;
            any = true;
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }

        if (!any && !session.route.isEmpty()) {
            CombatSession.RoutePoint p = session.route.get(session.route.size() - 1);
            minX = maxX = p.x;
            minZ = maxZ = p.z;
            any = true;
        }

        MapData data = new MapData();
        if (!any) {
            data.width = 1;
            data.depth = 1;
            data.colors = new int[]{0xFF202024};
            return data;
        }

        int pad = 12;
        int fromX = (int) Math.floor(minX) - pad;
        int toX = (int) Math.ceil(maxX) + pad;
        int fromZ = (int) Math.floor(minZ) - pad;
        int toZ = (int) Math.ceil(maxZ) + pad;

        int spanW = toX - fromX;
        int spanD = toZ - fromZ;
        int step = (int) Math.max(1L, Math.round(Math.max(spanW, spanD) / 160.0F));

        int gridW = Math.max(1, (int) Math.ceil(spanW / (double) step));
        int gridD = Math.max(1, (int) Math.ceil(spanD / (double) step));

        data.originX = fromX;
        data.originZ = fromZ;
        data.width = gridW;
        data.depth = gridD;
        data.step = step;
        data.colors = sampleTopColors(level, fromX, fromZ, gridW, gridD, step);
        return data;
    }

    /** 逐格取地表最高非透明方块的地图颜色；未加载区块透明。 */
    private static int[] sampleTopColors(ServerLevel level, int fromX, int fromZ,
                                         int gridW, int gridD, int step) {
        int[] colors = new int[gridW * gridD];
        LevelHeightAccessor heights = level;
        int minBuildHeight = level.getMinBuildHeight();

        for (int gz = 0; gz < gridD; gz++) {
            for (int gx = 0; gx < gridW; gx++) {
                int wx = fromX + gx * step;
                int wz = fromZ + gz * step;

                if (!level.getChunkSource().hasChunk(wx >> 4, wz >> 4)) {
                    colors[gz * gridW + gx] = 0x00000000;
                    continue;
                }

                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                int color = 0x00000000;

                for (int y = Math.max(minBuildHeight, topY - 1); y >= minBuildHeight; y--) {
                    pos.set(wx, y, wz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    MapColor mc = state.getMapColor(level, pos);
                    if (mc != null && mc.col != 0) {
                        color = 0xFF000000 | mc.col;
                        break;
                    }
                }
                colors[gz * gridW + gx] = color;
            }
        }
        return colors;
    }

    /**
     * 会话内每个淘汰目标的双边交战记录：
     * result[i] = {我方对其命中列表, 其对我方命中列表}，
     * 时间窗取 该击杀前一分钟 且不早于上一次击杀。
     */
    public static List<List<List<com.fragmentation.combat.HitRecord>>> buildEngagementRecords(
        ServerPlayer player, CombatSession session) {
        var allOut = com.fragmentation.combat.HitRecordCollector.snapshotOutgoingAll(player.getUUID());
        var allIn = com.fragmentation.combat.HitRecordCollector.snapshotIncomingAll(player.getUUID());

        List<List<List<com.fragmentation.combat.HitRecord>>> result = new java.util.ArrayList<>();
        long previousKillTick = Long.MIN_VALUE;

        for (CombatSession.KillEntry kill : session.kills) {
            long windowStart = Math.max(previousKillTick + 1L,
                kill.tick - com.fragmentation.combat.HitRecordCollector.WINDOW_TICKS);

            List<com.fragmentation.combat.HitRecord> dealt = new java.util.ArrayList<>();
            List<com.fragmentation.combat.HitRecord> taken = new java.util.ArrayList<>();

            for (var r : allOut) {
                if (r.opponentName.equals(kill.victimName)
                        && r.tick >= windowStart && r.tick <= kill.tick) {
                    dealt.add(r);
                }
            }
            for (var r : allIn) {
                if (r.opponentName.equals(kill.victimName)
                        && r.tick >= windowStart && r.tick <= kill.tick) {
                    taken.add(r);
                }
            }

            result.add(java.util.List.of(dealt, taken));
            previousKillTick = kill.tick;
        }
        return result;
    }
}
