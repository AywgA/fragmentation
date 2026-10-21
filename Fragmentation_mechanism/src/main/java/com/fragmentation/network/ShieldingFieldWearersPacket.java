package com.fragmentation.network;

import com.fragmentation.client.ClientShieldingFieldRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：同步当前佩戴屏蔽场发生器的实体 ID 列表（每秒一次）。
 * 客户端用于绘制奔跑状态的极淡蓝色拖影。
 */
public record ShieldingFieldWearersPacket(int[] entityIds) {

    public static void encode(ShieldingFieldWearersPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarIntArray(packet.entityIds);
    }

    public static ShieldingFieldWearersPacket decode(FriendlyByteBuf buffer) {
        return new ShieldingFieldWearersPacket(buffer.readVarIntArray());
    }

    public static void handle(
        ShieldingFieldWearersPacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientShieldingFieldRenderer.updateWearers(packet.entityIds())
            )
        );
        context.setPacketHandled(true);
    }
}
