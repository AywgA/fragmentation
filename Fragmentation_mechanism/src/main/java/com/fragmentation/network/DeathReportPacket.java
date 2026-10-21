package com.fragmentation.network;

import com.fragmentation.client.screen.DeathReportScreen;
import com.fragmentation.combat.HitRecord;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C：玩家死亡时，将其死亡前最近的受击记录与造成命中记录同步到客户端，
 * 客户端打开死亡战报界面。
 */
public record DeathReportPacket(
    List<HitRecord> incoming,
    List<HitRecord> outgoing
) {
    public static void encode(DeathReportPacket packet, FriendlyByteBuf buf) {
        writeList(buf, packet.incoming);
        writeList(buf, packet.outgoing);
    }

    public static DeathReportPacket decode(FriendlyByteBuf buf) {
        List<HitRecord> incoming = readList(buf);
        List<HitRecord> outgoing = readList(buf);
        return new DeathReportPacket(incoming, outgoing);
    }

    public static void handle(DeathReportPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> DeathReportScreen.open(packet.incoming, packet.outgoing)
            )
        );
        context.setPacketHandled(true);
    }

    private static void writeList(FriendlyByteBuf buf, List<HitRecord> list) {
        int size = list != null ? list.size() : 0;
        buf.writeVarInt(size);
        if (list != null) {
            for (HitRecord r : list) {
                HitRecord.encode(r, buf);
            }
        }
    }

    private static List<HitRecord> readList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<HitRecord> list = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            list.add(HitRecord.decode(buf));
        }
        return list;
    }
}
