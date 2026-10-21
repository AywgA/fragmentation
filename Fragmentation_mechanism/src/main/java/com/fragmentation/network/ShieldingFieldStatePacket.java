package com.fragmentation.network;

import com.fragmentation.client.ClientShieldingFieldRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：将玩家自身的屏蔽场状态与展示配置同步到客户端
 * （用于 tooltip 显示名称与负载，客户端无需本地配置文件）。
 */
public record ShieldingFieldStatePacket(
    boolean active,
    int phase,
    double negativeLoad,
    double mainLoad,
    double negativeCap,
    double mainCap,
    String displayName,
    List<String> activators
) {
    public static void encode(ShieldingFieldStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
        buffer.writeVarInt(packet.phase);
        buffer.writeDouble(packet.negativeLoad);
        buffer.writeDouble(packet.mainLoad);
        buffer.writeDouble(packet.negativeCap);
        buffer.writeDouble(packet.mainCap);
        buffer.writeUtf(packet.displayName != null ? packet.displayName : "");
        buffer.writeVarInt(packet.activators.size());
        for (String id : packet.activators) {
            buffer.writeUtf(id);
        }
    }

    public static ShieldingFieldStatePacket decode(FriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        int phase = buffer.readVarInt();
        double negativeLoad = buffer.readDouble();
        double mainLoad = buffer.readDouble();
        double negativeCap = buffer.readDouble();
        double mainCap = buffer.readDouble();
        String displayName = buffer.readUtf();
        int count = buffer.readVarInt();
        List<String> activators = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            activators.add(buffer.readUtf());
        }
        return new ShieldingFieldStatePacket(
            active, phase, negativeLoad, mainLoad, negativeCap, mainCap,
            displayName, activators);
    }

    public static void handle(
        ShieldingFieldStatePacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientShieldingFieldRenderer.updateState(packet)
            )
        );
        context.setPacketHandled(true);
    }
}
