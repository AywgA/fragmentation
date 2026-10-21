package com.fragmentation.network;

import com.fragmentation.client.ClientTrajectoryRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：调试显示开关状态（/ftrajectory on|off）。
 * 客户端据此决定 J / K 快捷键是否生效。
 */
public record TrajectoryStatePacket(boolean enabled) {
    public static void encode(TrajectoryStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled);
    }

    public static TrajectoryStatePacket decode(FriendlyByteBuf buffer) {
        return new TrajectoryStatePacket(buffer.readBoolean());
    }

    public static void handle(
        TrajectoryStatePacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientTrajectoryRenderer.setTrajectoryEnabled(packet.enabled)
            )
        );
        context.setPacketHandled(true);
    }
}
