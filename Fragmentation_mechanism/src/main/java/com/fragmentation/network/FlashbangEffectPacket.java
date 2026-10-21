package com.fragmentation.network;

import com.fragmentation.client.ClientFlashbangManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 个性化闪光弹效果包：每名观察者收到的数值都由服务端按其朝向、
 * 距离与遮挡单独计算。whiteFlash=瞬间纯白闪光强度；overlayPeak=
 * 全屏致盲贴图初始不透明度；blindTicks=致盲持续；deafTicks=耳鸣持续。
 */
public record FlashbangEffectPacket(
    float whiteFlash,
    float overlayPeak,
    int blindTicks,
    int deafTicks
) {
    public static void encode(FlashbangEffectPacket packet, FriendlyByteBuf buffer) {
        buffer.writeFloat(packet.whiteFlash);
        buffer.writeFloat(packet.overlayPeak);
        buffer.writeInt(packet.blindTicks);
        buffer.writeInt(packet.deafTicks);
    }

    public static FlashbangEffectPacket decode(FriendlyByteBuf buffer) {
        return new FlashbangEffectPacket(
            buffer.readFloat(),
            buffer.readFloat(),
            buffer.readInt(),
            buffer.readInt()
        );
    }

    public static void handle(
        FlashbangEffectPacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientFlashbangManager.trigger(
                    packet.whiteFlash,
                    packet.overlayPeak,
                    packet.blindTicks,
                    packet.deafTicks
                )
            )
        );
        context.setPacketHandled(true);
    }
}
