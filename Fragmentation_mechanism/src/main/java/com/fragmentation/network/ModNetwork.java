package com.fragmentation.network;

import com.fragmentation.FragmentationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(FragmentationMod.MOD_ID, "main"))
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel();
    private static int messageId;
    private static boolean registered;

    private ModNetwork() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(
            messageId++,
            TrajectoryPacket.class,
            TrajectoryPacket::encode,
            TrajectoryPacket::decode,
            TrajectoryPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            ShieldingFieldHitPacket.class,
            ShieldingFieldHitPacket::encode,
            ShieldingFieldHitPacket::decode,
            ShieldingFieldHitPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            ShieldingFieldStatePacket.class,
            ShieldingFieldStatePacket::encode,
            ShieldingFieldStatePacket::decode,
            ShieldingFieldStatePacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            ShieldingFieldWearersPacket.class,
            ShieldingFieldWearersPacket::encode,
            ShieldingFieldWearersPacket::decode,
            ShieldingFieldWearersPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            ShieldingFieldSlowZonePacket.class,
            ShieldingFieldSlowZonePacket::encode,
            ShieldingFieldSlowZonePacket::decode,
            ShieldingFieldSlowZonePacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            DeathReportPacket.class,
            DeathReportPacket::encode,
            DeathReportPacket::decode,
            DeathReportPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            SettlementPacket.class,
            SettlementPacket::encode,
            SettlementPacket::decode,
            SettlementPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            FlashbangEffectPacket.class,
            FlashbangEffectPacket::encode,
            FlashbangEffectPacket::decode,
            FlashbangEffectPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            ExplosionVizPacket.class,
            ExplosionVizPacket::encode,
            ExplosionVizPacket::decode,
            ExplosionVizPacket::handle
        );
        CHANNEL.registerMessage(
            messageId++,
            TrajectoryStatePacket.class,
            TrajectoryStatePacket::encode,
            TrajectoryStatePacket::decode,
            TrajectoryStatePacket::handle
        );
    }

    /** 向指定玩家发送一个已注册的数据包。 */
    public static void sendToPlayer(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** 客户端向服务器发送一个已注册的数据包。 */
    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void addTrajectory(
        ServerPlayer player,
        String dimension,
        Vec3 start,
        Vec3 end,
        TrajectoryPacket.Type type
    ) {
        CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            TrajectoryPacket.add(dimension, start, end, type)
        );
    }

    public static void clearTrajectories(ServerPlayer player) {
        CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            TrajectoryPacket.clear()
        );
    }

    /** 向能看到该实体的所有玩家广播屏蔽场命中脉冲（沙丘风格，事件驱动） */
    public static void sendShieldingFieldHit(net.minecraft.world.entity.Entity entity,
                                             net.minecraft.world.phys.Vec3 hitPos,
                                             int layer,
                                             int colorMode,
                                             float shieldRatio,
                                             boolean fast) {
        CHANNEL.send(
            PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
            new ShieldingFieldHitPacket(entity.getId(), hitPos, layer, colorMode, shieldRatio, fast)
        );
    }

    /** 将屏蔽场佩戴者实体 ID 列表同步给玩家（供客户端绘制奔跑拖影） */
    public static void sendShieldingFieldWearers(ServerPlayer player, int[] entityIds) {
        CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new ShieldingFieldWearersPacket(entityIds)
        );
    }

    /** 向能看到该实体的玩家广播慢速贴近状态（持续深红表现） */
    public static void sendShieldingFieldSlowZone(net.minecraft.world.entity.Entity target, int[] attackerIds) {
        CHANNEL.send(
            PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target),
            new ShieldingFieldSlowZonePacket(target.getId(), attackerIds)
        );
    }

    /** 将屏蔽场状态同步给玩家自身（用于客户端 tooltip 显示负载） */
    public static void sendShieldingFieldState(
        ServerPlayer player,
        boolean active,
        int phase,
        double negativeLoad,
        double mainLoad,
        double negativeCap,
        double mainCap,
        String displayName,
        java.util.List<String> activators
    ) {
        CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new ShieldingFieldStatePacket(active, phase, negativeLoad, mainLoad,
                negativeCap, mainCap, displayName, activators)
        );
    }
}
