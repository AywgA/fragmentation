package com.fragmentation.ballistics;

import com.fragmentation.network.ModNetwork;
import com.fragmentation.network.TrajectoryPacket;
import com.fragmentation.util.PlayerStateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class BallisticsDebugRenderer {
    private static final double MIN_SEGMENT_LENGTH = 0.05D;

    private BallisticsDebugRenderer() {
    }

    public static void sendDebugEvent(Entity bullet, String message) {
        ServerPlayer owner = getOwner(bullet);
        if (owner != null && PlayerStateManager.isDebugMode(owner)) {
            owner.sendSystemMessage(Component.literal(
                "§e[弹道调试] §r" + message
            ));
        }
    }

    public static void renderFlight(
        Entity bullet,
        boolean fragment,
        boolean penetrated,
        boolean ricochet
    ) {
        ServerPlayer owner = getTrajectoryOwner(bullet);
        if (owner == null || !(bullet.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 start = bullet.position();
        Vec3 movement = bullet.getDeltaMovement();
        if (movement.lengthSqr() <= 1.0E-8D) return;
        Vec3 end = start.add(movement);
        BlockHitResult blockHit = level.clip(new ClipContext(
            start,
            end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            bullet
        ));
        if (blockHit.getType() != HitResult.Type.MISS) {
            end = blockHit.getLocation();
        }
        Projectile projectile = (Projectile) bullet;
        Entity projectileOwner = projectile.getOwner();
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            level,
            bullet,
            start,
            end,
            bullet.getBoundingBox().expandTowards(movement).inflate(1.0D),
            candidate -> candidate != bullet
                && candidate != projectileOwner
                && !candidate.isSpectator()
                && candidate.canBeHitByProjectile()
        );
        if (entityHit != null) {
            end = entityHit.getLocation();
        }
        renderLine(
            level,
            owner,
            start,
            end,
            fragment
                ? TrajectoryPacket.Type.FRAGMENT
                : penetrated
                    ? TrajectoryPacket.Type.PENETRATION
                    : ricochet
                        ? TrajectoryPacket.Type.RICOCHET
                        : TrajectoryPacket.Type.FLIGHT
        );
    }

    public static void renderRicochet(
        Entity bullet,
        Vec3 impact,
        Vec3 transitionStart,
        Vec3 movement
    ) {
        renderTransition(
            bullet,
            impact,
            transitionStart,
            movement,
            TrajectoryPacket.Type.RICOCHET
        );
    }

    public static void renderFragment(
        Entity fragment,
        Vec3 spawnPosition,
        Vec3 movement
    ) {
        renderTransition(
            fragment,
            spawnPosition,
            spawnPosition,
            movement,
            TrajectoryPacket.Type.FRAGMENT
        );
    }

    public static void renderPenetration(
        Entity bullet,
        Vec3 impact,
        Vec3 exit
    ) {
        ServerPlayer owner = getTrajectoryOwner(bullet);
        if (owner == null || !(bullet.level() instanceof ServerLevel level)) {
            return;
        }
        renderLine(
            level,
            owner,
            impact,
            exit,
            TrajectoryPacket.Type.PENETRATION
        );
    }

    private static void renderTransition(
        Entity bullet,
        Vec3 renderStart,
        Vec3 transitionStart,
        Vec3 movement,
        TrajectoryPacket.Type type
    ) {
        ServerPlayer owner = getTrajectoryOwner(bullet);
        if (owner == null
            || !(bullet.level() instanceof ServerLevel level)
            || movement == null) {
            return;
        }
        if (movement.lengthSqr() <= 1.0E-8D) {
            movement = bullet.getDeltaMovement();
        }
        if (movement == null || movement.lengthSqr() <= 1.0E-8D) {
            return;
        }
        if (movement.length() < MIN_SEGMENT_LENGTH) {
            movement = movement.normalize().scale(MIN_SEGMENT_LENGTH);
        }
        Vec3 end = transitionStart.add(movement);
        renderLine(
            level,
            owner,
            renderStart,
            end,
            type
        );
    }

    private static void renderLine(
        ServerLevel level,
        ServerPlayer player,
        Vec3 start,
        Vec3 end,
        TrajectoryPacket.Type type
    ) {
        double distance = start.distanceTo(end);
        if (!Double.isFinite(distance) || distance <= 1.0E-6D) return;
        ModNetwork.addTrajectory(
            player,
            level.dimension().location().toString(),
            start,
            end,
            type
        );
    }

    private static ServerPlayer getTrajectoryOwner(Entity bullet) {
        ServerPlayer owner = getOwner(bullet);
        return owner != null && PlayerStateManager.isTrajectoryMode(owner)
            ? owner
            : null;
    }

    private static ServerPlayer getOwner(Entity bullet) {
        if (!(bullet instanceof Projectile projectile)) return null;
        return projectile.getOwner() instanceof ServerPlayer player
            ? player
            : null;
    }
}
