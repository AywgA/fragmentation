package com.fragmentation.network;

import com.fragmentation.client.ClientTrajectoryRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record TrajectoryPacket(
    Action action,
    String dimension,
    Vec3 start,
    Vec3 end,
    Type type
) {
    public static TrajectoryPacket add(
        String dimension,
        Vec3 start,
        Vec3 end,
        Type type
    ) {
        return new TrajectoryPacket(Action.ADD, dimension, start, end, type);
    }

    public static TrajectoryPacket clear() {
        return new TrajectoryPacket(
            Action.CLEAR,
            "",
            Vec3.ZERO,
            Vec3.ZERO,
            Type.FLIGHT
        );
    }

    public static void encode(TrajectoryPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
        buffer.writeUtf(packet.dimension);
        writeVec3(buffer, packet.start);
        writeVec3(buffer, packet.end);
        buffer.writeEnum(packet.type);
    }

    public static TrajectoryPacket decode(FriendlyByteBuf buffer) {
        return new TrajectoryPacket(
            buffer.readEnum(Action.class),
            buffer.readUtf(),
            readVec3(buffer),
            readVec3(buffer),
            buffer.readEnum(Type.class)
        );
    }

    public static void handle(
        TrajectoryPacket packet,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientTrajectoryRenderer.handle(packet)
            )
        );
        context.setPacketHandled(true);
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 vector) {
        buffer.writeDouble(vector.x);
        buffer.writeDouble(vector.y);
        buffer.writeDouble(vector.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readDouble()
        );
    }

    public enum Action {
        ADD,
        CLEAR
    }

    public enum Type {
        FLIGHT(1.0F, 0.85F, 0.1F),
        RICOCHET(0.1F, 0.9F, 1.0F),
        FRAGMENT(1.0F, 0.35F, 0.05F),
        PENETRATION(0.2F, 1.0F, 0.25F),
        SHOCKWAVE(1.0F, 0.96F, 0.72F);

        private final float red;
        private final float green;
        private final float blue;

        Type(float red, float green, float blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        public float red() {
            return red;
        }

        public float green() {
            return green;
        }

        public float blue() {
            return blue;
        }
    }
}
