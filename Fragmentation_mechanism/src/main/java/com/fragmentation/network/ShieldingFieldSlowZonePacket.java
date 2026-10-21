package com.fragmentation.network;

import com.fragmentation.client.ClientShieldingFieldRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：同步"慢速物品贴近屏蔽场佩戴者"状态（沙丘设定：慢速穿透）。
 *
 * 服务端每 10 tick 重发或在变化时发送：targetId = 佩戴者实体 ID，
 * attackerIds = 正贴近（蹲下/静止且在近战距离内）的玩家实体 ID 列表（空数组 = 无贴近）。
 * 客户端据此在贴近位置持续显示深红色布面光晕，直到状态解除（淡出）。
 */
public record ShieldingFieldSlowZonePacket(int targetId, int[] attackerIds) {

    public static void encode(ShieldingFieldSlowZonePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.targetId);
        buffer.writeVarIntArray(packet.attackerIds);
    }

    public static ShieldingFieldSlowZonePacket decode(FriendlyByteBuf buffer) {
        return new ShieldingFieldSlowZonePacket(buffer.readVarInt(), buffer.readVarIntArray());
    }

    public static void handle(
        ShieldingFieldSlowZonePacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientShieldingFieldRenderer.updateSlowZones(packet.targetId(), packet.attackerIds())
            )
        );
        context.setPacketHandled(true);
    }
}
