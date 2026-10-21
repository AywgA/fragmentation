package com.fragmentation.network;

import com.fragmentation.client.ClientShieldingFieldRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：屏蔽场被命中的瞬间脉冲（沙丘电影风格，事件驱动）。
 *
 * 客户端收到后仅在该游戏刻触发一次短促脉冲（约 5 tick 内消失）：
 * - colorMode  命中配色模式：
 *   · MODE_BLUE       深蓝色 = 高速物体被正常挡下
 *   · MODE_LIGHT_BLUE 浅蓝色 = 负载过高/降级/过载等高压力状态下的受击
 *   · MODE_RED        深红色 = 慢速物体穿过屏蔽场，或屏蔽场失去防护效果
 * - shieldRatio 主层负载比例（≥0.8 表示护盾余量 <20%，闪烁加快）
 * - fast        命中速度是否达到高速阈值（保留字段，供音效钩子使用）
 *
 * layer 保留协议字段（0=次层 1=主层 2=负层），当前渲染不再按层分色。
 */
public record ShieldingFieldHitPacket(
    int entityId,
    Vec3 hitPos,
    int layer,
    int colorMode,
    float shieldRatio,
    boolean fast
) {
    public static final int LAYER_SECONDARY = 0;
    public static final int LAYER_MAIN = 1;
    public static final int LAYER_NEGATIVE = 2;

    /** 深蓝色：高速物体被正常挡下 */
    public static final int MODE_BLUE = 0;
    /** 浅蓝色：负载过高/降级/过载等高压力状态下的受击 */
    public static final int MODE_LIGHT_BLUE = 1;
    /** 深红色：慢速物体穿过 / 屏蔽场失去防护效果 */
    public static final int MODE_RED = 2;

    public static void encode(ShieldingFieldHitPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeDouble(packet.hitPos.x);
        buffer.writeDouble(packet.hitPos.y);
        buffer.writeDouble(packet.hitPos.z);
        buffer.writeVarInt(packet.layer);
        buffer.writeVarInt(packet.colorMode);
        buffer.writeFloat(packet.shieldRatio);
        buffer.writeBoolean(packet.fast);
    }

    public static ShieldingFieldHitPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        Vec3 hitPos = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        int layer = buffer.readVarInt();
        int colorMode = buffer.readVarInt();
        float shieldRatio = buffer.readFloat();
        boolean fast = buffer.readBoolean();
        return new ShieldingFieldHitPacket(entityId, hitPos, layer, colorMode, shieldRatio, fast);
    }

    public static void handle(
        ShieldingFieldHitPacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientShieldingFieldRenderer.handle(packet)
            )
        );
        context.setPacketHandled(true);
    }
}
