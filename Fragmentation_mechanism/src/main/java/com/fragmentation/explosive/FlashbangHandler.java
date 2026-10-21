package com.fragmentation.explosive;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.network.FlashbangEffectPacket;
import com.fragmentation.network.ModNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 闪光弹爆炸接管。
 *
 * 关键物理修正：闪光不是一条视线——爆炸瞬间火球是一个发光球面，
 * 光从整个球面各方向发出。因此对每名观察者，在火球球面上均匀
 * 取多个采样点，逐点判定遮挡与入射角：墙体只挡住被其遮蔽的那
 * 部分球面（半遮则效果减半），只有把整个火球全部挡住才完全致盲失败。
 *
 * 方向性仍参考现实：各可见采样点按其相对观察者的入射角加权
 * （正面直视最强、侧面削弱、背闪为 0）；听觉不受朝向限制。
 */
public final class FlashbangHandler {
    /** 发光火球半径（格）。 */
    private static final double FIREBALL_RADIUS = 0.7;
    /** 球面采样点数（斐波那契球面）。 */
    private static final int SAMPLE_COUNT = 18;

    private FlashbangHandler() {
    }

    public static void onDetonate(Entity source, String configId) {
        Level level = source.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 pos = source.position().add(0.0, 0.1, 0.0);
        Entity owner = resolveOwner(source);

        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        ExplosiveConfig.ExplosiveEntry entry = config.getEntry(configId);
        double yield = entry != null ? entry.yield : 1.0;
        double maxRange = ExplosiveConfig.BASE_FLASH_RANGE * Math.cbrt(yield);
        boolean indoor = ExplosionRayTracer.isIndoor(level, pos);

        serverLevel.playSound(
            null,
            pos.x, pos.y, pos.z,
            resolveBang(),
            SoundSource.PLAYERS,
            3.0F,
            indoor ? 0.9F : 1.0F
        );
        serverLevel.sendParticles(
            ParticleTypes.FLASH,
            pos.x, pos.y, pos.z,
            4, 0.1, 0.1, 0.1, 0.0
        );

        List<Vec3> samplePoints = buildSphereSamples(pos);

        for (ServerPlayer player : serverLevel.players()) {
            if (player == source) {
                continue;
            }
            if (player.position().distanceToSqr(pos) > maxRange * maxRange) {
                continue;
            }
            Vec3 eye = player.getEyePosition(1.0F);
            double distance = Math.max(0.5, eye.distanceTo(pos));
            Vec3 look = player.getLookAngle();

            // —— 球面逐点受光 ——
            double lightSum = 0.0;
            for (Vec3 point : samplePoints) {
                ExplosionRayTracer.LineResult line =
                    ExplosionRayTracer.traceLine(level, eye, point);
                if (line.lightBlocked) {
                    continue;
                }
                Vec3 pointDir = point.subtract(eye).normalize();
                double dot = Mth.clamp(look.dot(pointDir), -1.0, 1.0);
                double angle = Math.toDegrees(Math.acos(dot));
                lightSum += angularFactor(angle) * line.glassFactor;
            }

            // 眼睛位于火球内部：全强度
            double visibleFraction = distance < FIREBALL_RADIUS
                ? 1.0
                : lightSum / SAMPLE_COUNT;

            double distanceFactor = 1.0 / (1.0 + 0.05 * distance * distance);
            double direct = visibleFraction * distanceFactor;
            double bounce = (indoor ? 0.14 : 0.02) * distanceFactor;
            double visual = Mth.clamp(direct + bounce, 0.0, 1.0);

            // 声音取球心路径（声波可绕射，衰减较轻）
            ExplosionRayTracer.LineResult centerLine =
                ExplosionRayTracer.traceLine(level, eye, pos);
            double hearingDistance = 1.0 / (1.0 + 0.03 * distance * distance);
            double hearing = Mth.clamp(
                hearingDistance * centerLine.soundFactor * (indoor ? 1.12 : 1.0),
                0.0, 1.0
            );

            if (visual < 0.02 && hearing < 0.03) {
                continue;
            }

            float whiteFlash;
            if (direct > 0.0 && visual > 0.55) {
                whiteFlash = 1.0F;
            } else if (visual > 0.35) {
                whiteFlash = 0.6F;
            } else {
                whiteFlash = 0.0F;
            }
            int blindTicks = Mth.clamp(
                Math.round((float) (visual * ExplosiveConfig.BASE_BLIND_TICKS * yield)),
                0,
                ExplosiveConfig.MAX_BLIND_TICKS
            );
            float overlayPeak = (float) Mth.clamp(visual * 1.12, 0.0, 1.0);
            int deafTicks = Mth.clamp(
                Math.round((float) (hearing * 220.0 * yield)), 0, 400);
            if (blindTicks < 2 && deafTicks < 2) {
                continue;
            }

            ModNetwork.sendToPlayer(
                player,
                new FlashbangEffectPacket(whiteFlash, overlayPeak, blindTicks, deafTicks)
            );
        }
    }

    /** 斐波那契球面均匀采样点。 */
    private static List<Vec3> buildSphereSamples(Vec3 center) {
        List<Vec3> points = new ArrayList<>(SAMPLE_COUNT);
        double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            double y = 1.0 - (i / (double) (SAMPLE_COUNT - 1)) * 2.0;
            double radiusAtY = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = goldenAngle * i;
            points.add(center.add(
                Math.cos(theta) * radiusAtY * FIREBALL_RADIUS,
                y * FIREBALL_RADIUS,
                Math.sin(theta) * radiusAtY * FIREBALL_RADIUS
            ));
        }
        return points;
    }

    /**
     * 人眼视角因素：中央凹直视时闪光全部进入；40° 外进入周边视觉，
     * 快速减弱；超过约 120° 时眼球受光面被鼻梁/眉骨遮挡，直射为 0。
     */
    private static double angularFactor(double angleDegrees) {
        if (angleDegrees <= 40.0) {
            return 1.0;
        }
        if (angleDegrees <= 90.0) {
            return 1.0 - (angleDegrees - 40.0) / 50.0 * 0.65;
        }
        if (angleDegrees <= 120.0) {
            return 0.35 - (angleDegrees - 90.0) / 30.0 * 0.30;
        }
        return 0.0;
    }

    private static Entity resolveOwner(Entity source) {
        if (source instanceof Projectile projectile && projectile.getOwner() != null) {
            return projectile.getOwner();
        }
        try {
            Field field = source.getClass().getDeclaredField("ownerUuid");
            field.setAccessible(true);
            if (field.get(source) instanceof UUID uuid
                && source.level() instanceof ServerLevel level) {
                Entity owner = level.getEntity(uuid);
                if (owner != null) {
                    return owner;
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
        }
        return null;
    }

    private static SoundEvent resolveBang() {
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(
            new ResourceLocation("tacz_tactical_breaching", "flashbang_detonate")
        );
        return event != null ? event : SoundEvents.GENERIC_EXPLODE;
    }
}
