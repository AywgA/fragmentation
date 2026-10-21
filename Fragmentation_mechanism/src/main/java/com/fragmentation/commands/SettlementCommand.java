package com.fragmentation.commands;

import com.fragmentation.combat.HitRecord;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.network.SettlementPacket;
import com.fragmentation.session.CombatSession;
import com.fragmentation.session.CombatSessionManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * /fragmentation jiesuan 服务端逻辑：
 * 取最近对局会话，采样地图色块、组装路线/淘汰/双边交战记录并下发结算界面。
 */
public final class SettlementCommand {

    private SettlementCommand() {
    }

    public static void open(ServerPlayer player) {
        CombatSession session = CombatSessionManager.getForSettlement(player);
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c尚无对局记录。请先使用 §f/fragmentation jilu§c 开始记录。"));
            return;
        }

        long nowTick = player.serverLevel().getGameTime();
        long endTick = session.endTick >= 0L ? session.endTick : nowTick;

        // 结算地图维度：路线最后一点所在维度；没有路线则用当前维度。
        ServerLevel level = player.serverLevel();
        if (!session.route.isEmpty()) {
            String dimStr = session.route.get(session.route.size() - 1).dim.location();
            ServerLevel resolved = resolveLevel(player, dimStr);
            if (resolved != null) {
                level = resolved;
            }
        }

        CombatSessionManager.MapData map = CombatSessionManager.buildMapData(session, level);

        SettlementPacket packet = new SettlementPacket();
        packet.startTick = session.startTick;
        packet.durationTicks = Math.max(0L, endTick - session.startTick);

        packet.originX = map.originX;
        packet.originZ = map.originZ;
        packet.mapWidth = map.width;
        packet.mapDepth = map.depth;
        packet.step = map.step;
        packet.colors = map.colors;

        for (CombatSession.RoutePoint r : session.route) {
            packet.route.add(new SettlementPacket.RoutePoint(r.x, r.y, r.z, r.dim.location()));
        }
        for (CombatSession.KillEntry k : session.kills) {
            packet.kills.add(new SettlementPacket.KillInfo(
                k.victimName, k.isPlayer, k.tick, k.x, k.y, k.z, k.dim.location()));
        }

        List<List<List<HitRecord>>> engagements =
            CombatSessionManager.buildEngagementRecords(player, session);
        for (List<List<HitRecord>> pair : engagements) {
            packet.engagements.add(new SettlementPacket.Engagement(pair.get(0), pair.get(1)));
        }
        // 防御性对齐：击杀与交战数量不一致时补空。
        while (packet.engagements.size() < packet.kills.size()) {
            packet.engagements.add(new SettlementPacket.Engagement(List.of(), List.of()));
        }

        ModNetwork.sendToPlayer(player, packet);
    }

    private static ServerLevel resolveLevel(ServerPlayer player, String dimString) {
        try {
            ResourceLocation loc = new ResourceLocation(dimString);
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, loc);
            return player.server.getLevel(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
