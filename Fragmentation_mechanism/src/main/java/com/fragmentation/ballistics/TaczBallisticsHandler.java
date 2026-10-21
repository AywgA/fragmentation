package com.fragmentation.ballistics;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.config.GunConfig;
import com.fragmentation.util.WorldCoverManager;
import com.fragmentation.util.WorldWindManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public final class TaczBallisticsHandler {
    private static final String TACZ_BULLET_CLASS = "com.tacz.guns.entity.EntityKineticBullet";
    private static final String CURRENT_SPEED_KEY = "fragmentation_current_speed_mps";
    private static final String MAXIMUM_SPEED_KEY = "fragmentation_maximum_speed_mps";
    private static final String COVER_PENETRATED_KEY = "fragmentation_cover_penetrated";
    private static final String PENETRATION_MULTIPLIER_KEY =
        "fragmentation_penetration_multiplier";
    private static final String DAMAGE_MULTIPLIER_KEY =
        "fragmentation_damage_multiplier";
    private static final String MASS_MULTIPLIER_KEY =
        "fragmentation_mass_multiplier";
    private static final String FRAGMENT_KEY = "fragmentation_is_fragment";
    private static final String FRAGMENTED_KEY = "fragmentation_has_fragmented";
    private static final String RICOCHET_KEY = "fragmentation_debug_ricochet";
    private static final String BLOCK_HIT_TICK_KEY =
        "fragmentation_block_hit_tick";
    private static final double DEFAULT_GRAVITY_ACCELERATION = 9.81D;
    private static final double EPSILON = 1.0E-8D;
    private static final double COLLISION_TRACE_EPSILON = 1.0E-4D;
    private static final double COVER_EXIT_EPSILON = 1.0E-4D;
    private static final double FRAGMENT_SPAWN_EPSILON = 0.02D;
    private static final Map<Entity, BallisticState> STATES = Collections.synchronizedMap(
        new WeakHashMap<>()
    );

    private static final String SELF_HIT_CHECK_KEY =
        "fragmentation_self_hit_check";

    private static Class<?> bulletClass;
    private static Method getGunIdMethod;
    private static Method getAmmoIdMethod;
    private static Field ammoIdField;
    private static Field gunDisplayIdField;
    private static Field gravityField;
    private static Field lifeField;
    private static Field pierceField;
    private static Field startPosField;
    private static Field explosionField;
    private static Constructor<?> bulletHoleConstructor;
    private static boolean bulletHoleReflectionInitialized;
    private static List<Field> copiedBulletFields = List.of();
    private static boolean reflectionInitialized;

    private static Class<?> tacHitResultClass;
    private static Class<?> entityResultClass;
    private static Constructor<?> entityResultConstructor;
    private static Constructor<?> tacHitResultConstructor;
    private static Method onHitEntityMethod;
    private static boolean entityHitReflectionInitialized;

    private TaczBallisticsHandler() {
    }

    public static SpeedSnapshot getSpeedSnapshot(Entity entity) {
        return getSpeedSnapshot(entity, null);
    }

    public static SpeedSnapshot getSpeedSnapshot(Entity entity, Entity hitEntity) {
        if (entity == null) return new SpeedSnapshot(0.0D, 0.0D);

        boolean coverPenetrated = entity.getPersistentData().getBoolean(COVER_PENETRATED_KEY);
        BallisticState state = STATES.get(entity);
        if (state != null) {
            double currentVelocity = hitEntity != null
                ? impactVelocity(entity, hitEntity, state)
                : state.forwardVelocity;
            return new SpeedSnapshot(
                currentVelocity,
                state.maximumVelocity,
                coverPenetrated
            );
        }

        double storedCurrent = entity.getPersistentData().getDouble(CURRENT_SPEED_KEY);
        double storedMaximum = entity.getPersistentData().getDouble(MAXIMUM_SPEED_KEY);
        if (storedMaximum > EPSILON) {
            return new SpeedSnapshot(storedCurrent, storedMaximum, coverPenetrated);
        }

        double movementSpeed = entity.getDeltaMovement().length() / BallisticsMath.TICK_SECONDS;
        return movementSpeed > EPSILON
            ? new SpeedSnapshot(movementSpeed, movementSpeed)
            : new SpeedSnapshot(0.0D, 0.0D);
    }

    public static SpeedSnapshot prepareForCoverImpact(Entity entity) {
        if (entity == null
            || entity.level().isClientSide
            || !TACZ_BULLET_CLASS.equals(entity.getClass().getName())
            || !initializeReflection()) {
            return getSpeedSnapshot(entity);
        }

        BallisticState state = STATES.get(entity);
        if (state == null) {
            state = initializeState(entity);
            if (state != null) {
                STATES.put(entity, state);
            }
        }
        return getSpeedSnapshot(entity);
    }

    public static ResourceLocation getAmmoId(Entity entity) {
        if (entity == null || !initializeReflection() || !bulletClass.isInstance(entity)) {
            return null;
        }
        try {
            Object value = getAmmoIdMethod != null
                ? getAmmoIdMethod.invoke(entity)
                : ammoIdField != null ? ammoIdField.get(entity) : null;
            return asResourceLocation(value);
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to read TACZ bullet ammo ID: {}",
                e.getMessage()
            );
            return null;
        }
    }

    public static double getPenetrationMultiplier(Entity entity) {
        return readMultiplier(entity, PENETRATION_MULTIPLIER_KEY);
    }

    public static double getDamageMultiplier(Entity entity) {
        return readMultiplier(entity, DAMAGE_MULTIPLIER_KEY);
    }

    public static void markBlockHit(Entity entity, BlockHitResult hitResult) {
        if (entity == null
            || hitResult == null
            || hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        entity.getPersistentData().putInt(BLOCK_HIT_TICK_KEY, entity.tickCount);
    }

    public static BlockHitResult findMissedSpecialBlockHit(Entity entity) {
        if (entity == null
            || entity.isRemoved()
            || entity.level().isClientSide
            || (!entity.getPersistentData().getBoolean(FRAGMENT_KEY)
                && !entity.getPersistentData().getBoolean(RICOCHET_KEY))
            || (entity.getPersistentData().contains(BLOCK_HIT_TICK_KEY)
                && entity.getPersistentData().getInt(BLOCK_HIT_TICK_KEY)
                    == entity.tickCount)) {
            return null;
        }

        Vec3 movement = entity.getDeltaMovement();
        if (!isFinite(movement) || movement.lengthSqr() <= EPSILON) {
            return null;
        }
        BlockHitResult hitResult = entity.level().clip(
            new ClipContext(
                entity.position(),
                entity.position().add(movement),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                entity
            )
        );
        return hitResult.getType() == HitResult.Type.BLOCK
            ? hitResult
            : null;
    }

    public static boolean handleCoverImpact(
        Entity entity,
        BlockHitResult hitResult
    ) {
        if (entity == null
            || hitResult == null
            || entity.level().isClientSide
            || !TACZ_BULLET_CLASS.equals(entity.getClass().getName())
            || isExplosive(entity)) {
            return false;
        }

        BlockState blockState = entity.level().getBlockState(
            hitResult.getBlockPos()
        );
        WorldCoverManager.CoverEntry cover =
            WorldCoverManager.getCover(blockState);
        if (cover == null) return false;

        if (tryRicochetCover(entity, hitResult, cover)) {
            return true;
        }
        int fragmentCount = tryFragmentAtCover(entity, hitResult);
        if (fragmentCount > 0) {
            return true;
        }
        boolean penetrated = tryPenetrateCover(entity, hitResult);
        return penetrated;
    }

    public static void spawnTaczBulletHole(
        Entity entity,
        BlockHitResult hitResult
    ) {
        if (entity == null
            || hitResult == null
            || !(entity.level() instanceof ServerLevel serverLevel)
            || !initializeBulletHoleReflection()) {
            return;
        }

        try {
            ResourceLocation ammoId = getAmmoId(entity);
            ResourceLocation gunId = asResourceLocation(
                getGunIdMethod.invoke(entity)
            );
            ResourceLocation gunDisplayId = asResourceLocation(
                gunDisplayIdField.get(entity)
            );
            if (ammoId == null || gunId == null) return;
            if (gunDisplayId == null) {
                gunDisplayId = gunId;
            }

            ParticleOptions bulletHole = (ParticleOptions)
                bulletHoleConstructor.newInstance(
                    hitResult.getDirection(),
                    hitResult.getBlockPos(),
                    ammoId.toString(),
                    gunId.toString(),
                    gunDisplayId.toString()
                );
            Vec3 hitPosition = hitResult.getLocation();
            serverLevel.sendParticles(
                bulletHole,
                hitPosition.x,
                hitPosition.y,
                hitPosition.z,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D
            );
        } catch (ReflectiveOperationException | ClassCastException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to create TACZ bullet hole: {}",
                e.getMessage()
            );
        }
    }

    public static void triggerEntityHit(
        Entity bullet,
        Entity hitEntity,
        Vec3 hitPos,
        boolean headshot
    ) {
        if (bullet == null
            || hitEntity == null
            || !initializeReflection()
            || !initializeEntityHitReflection()
            || onHitEntityMethod == null
            || tacHitResultConstructor == null
            || entityResultConstructor == null) {
            return;
        }
        try {
            Object entityResult = entityResultConstructor.newInstance(
                hitEntity,
                hitPos,
                headshot
            );
            Object tacHitResult = tacHitResultConstructor.newInstance(
                entityResult
            );
            Vec3 start = bullet.position();
            Vec3 end = hitPos;
            onHitEntityMethod.invoke(bullet, tacHitResult, start, end);
            FragmentationMod.LOGGER.debug(
                "[Ballistics] Triggered supplementary onHitEntity: entity={}, hitPos={}, headshot={}",
                hitEntity.getStringUUID(),
                hitPos,
                headshot
            );
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to trigger TACZ onHitEntity: {}",
                e.getMessage()
            );
        }
    }

    public static void handleSupplementaryEntityHit(Entity bullet) {
        if (bullet == null
            || bullet.level().isClientSide
            || bullet.isRemoved()
            || !TACZ_BULLET_CLASS.equals(bullet.getClass().getName())
            || !initializeReflection()) {
            return;
        }

        boolean isFragment = bullet.getPersistentData().getBoolean(FRAGMENT_KEY);
        boolean isRicochet = bullet.getPersistentData().getBoolean(RICOCHET_KEY);
        boolean penetrated = bullet.getPersistentData().getBoolean(
            COVER_PENETRATED_KEY
        );

        if (penetrated) {
            checkEntityHitAlongTrajectory(bullet, false);
        }

        if (isFragment || isRicochet) {
            String checkKey = SELF_HIT_CHECK_KEY + bullet.tickCount;
            if (bullet.getPersistentData().getBoolean(checkKey)) {
                return;
            }
            bullet.getPersistentData().putBoolean(checkKey, true);
            checkEntityHitAlongTrajectory(bullet, true);
        }
    }

    private static void checkEntityHitAlongTrajectory(
        Entity bullet,
        boolean includeOwner
    ) {
        if (!(bullet.level() instanceof ServerLevel level)
            || !(bullet instanceof Projectile projectile)) {
            return;
        }
        Vec3 start = bullet.position();
        Vec3 movement = bullet.getDeltaMovement();
        if (!isFinite(movement) || movement.lengthSqr() <= EPSILON) {
            BallisticState state = STATES.get(bullet);
            if (state == null
                || state.forward == null
                || state.forwardVelocity <= EPSILON) {
                return;
            }
            movement = state.forward.scale(
                state.forwardVelocity * BallisticsMath.TICK_SECONDS
            );
            if (!isFinite(movement) || movement.lengthSqr() <= EPSILON) {
                return;
            }
        }
        Vec3 end = start.add(movement);

        Entity projectileOwner = projectile.getOwner();
        AABB searchBox = bullet.getBoundingBox()
            .expandTowards(movement)
            .inflate(1.0D);
        Entity closestEntity = null;
        Vec3 closestHitPos = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity candidate : level.getEntities(
            bullet, searchBox,
            candidate -> candidate != bullet
                && (includeOwner || candidate != projectileOwner)
                && !candidate.isSpectator()
                && candidate.canBeHitByProjectile()
        )) {
            AABB entityBox = candidate.getBoundingBox();
            Optional<Vec3> clipHit = entityBox.clip(start, end);
            if (clipHit.isPresent()) {
                double dist = start.distanceToSqr(clipHit.get());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestEntity = candidate;
                    closestHitPos = clipHit.get();
                }
            } else if (entityBox.contains(start)) {
                closestEntity = candidate;
                closestHitPos = start;
                closestDist = 0.0D;
            }
        }
        if (closestEntity == null) {
            return;
        }

        keepBulletAliveAfterEntityHit(bullet);
        triggerEntityHit(bullet, closestEntity, closestHitPos, false);
    }

    private static boolean tryRicochetCover(
        Entity entity,
        BlockHitResult hitResult,
        WorldCoverManager.CoverEntry cover
    ) {
        if (cover.ricochetCoefficient() <= 0.0D) return false;

        prepareForCoverImpact(entity);
        BallisticState state = STATES.get(entity);
        BulletConfig.BulletEntry bullet = getBulletEntry(entity);
        if (state == null) {
            return false;
        }
        if (bullet == null) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                "跳弹未判定：运行中的 ammoId 未匹配 bullets.json。"
            );
            return false;
        }
        if (bullet.ricochetRate <= 0.0D) return false;

        Vec3 movement = entity.getDeltaMovement();
        if (movement.lengthSqr() <= EPSILON) return false;

        Vec3 incoming = movement.normalize();
        Vec3 surfaceNormal = Vec3.atLowerCornerOf(
            hitResult.getDirection().getNormal()
        );
        double incidenceAngle = incidenceAngleDegrees(
            incoming,
            surfaceNormal
        );
        double currentSpeed = impactVelocity(
            entity,
            hitResult.getLocation(),
            state
        );
        double probability = BallisticsMath.ricochetProbability(
            bullet.ricochetRate,
            cover.ricochetCoefficient(),
            incidenceAngle,
            currentSpeed,
            state.maximumVelocity
        );
        double roll = entity.level().random.nextDouble();
        if (roll >= probability) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "掩体跳弹未触发：%s，入射角 %.1f°，实际概率 %.1f%%，随机值 %.1f%%。",
                    cover.blockId(),
                    incidenceAngle,
                    probability * 100.0D,
                    roll * 100.0D
                )
            );
            return false;
        }

        Vec3 ricochetDirection = randomRicochetDirection(
            incoming,
            surfaceNormal,
            entity
        );
        applyRicochet(entity, state, ricochetDirection, currentSpeed);
        Vec3 exitPosition = hitResult.getLocation()
            .add(surfaceNormal.scale(COVER_EXIT_EPSILON))
            .add(ricochetDirection.scale(COVER_EXIT_EPSILON));
        entity.setPos(exitPosition.x, exitPosition.y, exitPosition.z);
        resetTaczDamageOrigin(entity, exitPosition);
        BallisticsDebugRenderer.sendDebugEvent(
            entity,
            String.format(
                "§b跳弹已触发§r：掩体 %s，入射角 %.1f°，概率 %.1f%%，穿深保留 70%%。",
                cover.blockId(),
                incidenceAngle,
                probability * 100.0D
            )
        );

        FragmentationMod.LOGGER.debug(
            "[Ricochet] Cover={}, angle={}deg, probability={}, speed={}m/s, penetrationMultiplier={}",
            cover.blockId(),
            incidenceAngle,
            probability,
            currentSpeed,
            getPenetrationMultiplier(entity)
        );
        // TACZ moves the projectile after onHitBlock returns. Holding it at
        // the impact point prevents that untraced movement from skipping the
        // next wall or entity; the ballistic state restores motion next tick.
        entity.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    private static int tryFragmentAtCover(
        Entity entity,
        BlockHitResult hitResult
    ) {
        BulletConfig.BulletEntry bullet = getBulletEntry(entity);
        if (bullet == null) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                "掩体碎弹未判定：运行中的 ammoId 未匹配 bullets.json。"
            );
            return 0;
        }
        BlockState blockState = entity.level().getBlockState(
            hitResult.getBlockPos()
        );
        ResourceLocation blockId = net.minecraftforge.registries.ForgeRegistries
            .BLOCKS.getKey(blockState.getBlock());
        String surfaceName = blockId != null
            ? "掩体 " + blockId
            : "掩体";
        return tryFragment(
            entity,
            null,
            bullet,
            bullet.penetrationLevel * getPenetrationMultiplier(entity),
            hitResult.getLocation(),
            Vec3.atLowerCornerOf(hitResult.getDirection().getNormal()),
            surfaceName
        );
    }

    public static void applyCoverPenetrationSpeed(Entity entity, double postPenetrationSpeed) {
        if (entity == null) return;
        double safeSpeed = Double.isFinite(postPenetrationSpeed)
            ? Math.max(0.0D, postPenetrationSpeed)
            : 0.0D;
        BallisticState state = STATES.get(entity);
        if (state == null) {
            prepareForCoverImpact(entity);
            state = STATES.get(entity);
        }
        if (state != null) {
            state.forwardVelocity = safeSpeed;
            state.frameStartVelocity = safeSpeed;
            state.frameEndVelocity = safeSpeed;
        }

        Vec3 movement = entity.getDeltaMovement();
        if (movement.lengthSqr() > EPSILON) {
            entity.setDeltaMovement(
                movement.normalize().scale(safeSpeed * BallisticsMath.TICK_SECONDS)
            );
        }
        entity.getPersistentData().putDouble(CURRENT_SPEED_KEY, safeSpeed);
        entity.getPersistentData().putBoolean(COVER_PENETRATED_KEY, true);
    }

    public static boolean tryPenetrateCover(Entity entity, BlockHitResult hitResult) {
        if (entity == null
            || hitResult == null
            || entity.level().isClientSide
            || !TACZ_BULLET_CLASS.equals(entity.getClass().getName())) {
            return false;
        }

        BlockPos blockPos = hitResult.getBlockPos();
        BlockState blockState = entity.level().getBlockState(blockPos);
        WorldCoverManager.CoverEntry cover = WorldCoverManager.getCover(blockState);
        if (cover == null) return false;

        prepareForCoverImpact(entity);
        BallisticState state = STATES.get(entity);
        if (state == null) return false;

        long blockKey = blockPos.asLong();
        if (state.penetratedCoverBlocks.contains(blockKey)) {
            movePastCover(entity, hitResult, blockState);
            entity.setDeltaMovement(Vec3.ZERO);
            return true;
        }

        ResourceLocation ammoId = getAmmoId(entity);
        BulletConfig.BulletEntry bullet = getBulletEntry(entity);
        boolean zeroThreshold = cover.penetrationThreshold() <= 0.0D;
        if (bullet == null && !zeroThreshold) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                "掩体穿透未判定：运行中的 ammoId 未匹配 bullets.json。"
            );
            return false;
        }

        double massMultiplier = readMultiplier(entity, MASS_MULTIPLIER_KEY);
        double effectiveMass = bullet != null
            ? bullet.bulletMass * massMultiplier
            : 0.0D;
        double effectiveRadius = (bullet != null ? bullet.bulletRadius : 0.0D)
            * (entity.getPersistentData().getBoolean(FRAGMENT_KEY) ? 0.5D : 1.0D);
        if (!zeroThreshold
            && (effectiveMass <= 0.0D || effectiveRadius <= 0.0D)) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                "掩体穿透未判定：子弹重量或半径未配置。"
            );
            return false;
        }
        double currentSpeed = impactVelocity(
            entity,
            hitResult.getLocation(),
            state
        );
        if (!BallisticsMath.canPenetrateCover(
            effectiveMass,
            currentSpeed,
            cover.penetrationThreshold(),
            effectiveRadius
        )) {
            double kineticEnergy = BallisticsMath.kineticEnergy(
                effectiveMass,
                currentSpeed
            );
            double requiredEnergy = cover.penetrationThreshold()
                * BallisticsMath.bulletCrossSection(effectiveRadius);
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "掩体未穿透：%s，动能 %.1fJ < 所需 %.1fJ。",
                    cover.blockId(),
                    kineticEnergy,
                    requiredEnergy
                )
            );
            return false;
        }

        double postPenetrationSpeed = BallisticsMath.postPenetrationSpeed(
            currentSpeed,
            cover.thicknessCoefficient(),
            cover.densityCoefficient()
        );
        if (postPenetrationSpeed <= EPSILON) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "掩体未穿透：%s 的厚度×密度使穿透后速度降为 0。",
                    cover.blockId()
                )
            );
            return false;
        }

        state.penetratedCoverBlocks.add(blockKey);
        applyCoverPenetrationSpeed(entity, postPenetrationSpeed);
        movePastCover(entity, hitResult, blockState);
        Vec3 exitPosition = entity.position();
        BallisticsDebugRenderer.renderPenetration(
            entity,
            hitResult.getLocation(),
            exitPosition
        );
        // Prevent TACZ from moving the bullet untraced after onHitBlock
        // returns; the ballistic state restores motion next tick via
        // applyNextFrame in beforeEntityTick.
        entity.setDeltaMovement(Vec3.ZERO);

        double kineticEnergy = BallisticsMath.kineticEnergy(
            effectiveMass,
            currentSpeed
        );
        double requiredEnergy = cover.penetrationThreshold()
            * BallisticsMath.bulletCrossSection(effectiveRadius);
        BallisticsDebugRenderer.sendDebugEvent(
            entity,
            String.format(
                "§a掩体已穿透§r：%s，速度 %.1f→%.1fm/s，动能 %.1fJ / 所需 %.1fJ。",
                cover.blockId(),
                currentSpeed,
                postPenetrationSpeed,
                kineticEnergy,
                requiredEnergy
            )
        );
        FragmentationMod.LOGGER.debug(
            "[Cover] Bullet penetrated {} with ammo={}, energy={}J, required={}J, speed={}m/s -> {}m/s",
            cover.blockId(),
            ammoId,
            kineticEnergy,
            requiredEnergy,
            currentSpeed,
            postPenetrationSpeed
        );
        return true;
    }

    private static BulletConfig.BulletEntry getBulletEntry(Entity entity) {
        ResourceLocation ammoId = getAmmoId(entity);
        BulletConfig bulletConfig = FragmentationMod.getBulletConfig();
        return bulletConfig != null && ammoId != null
            ? bulletConfig.getBulletByAmmoId(ammoId.toString())
            : null;
    }

    public static int tryFragment(
        Entity entity,
        Entity hitEntity,
        BulletConfig.BulletEntry bullet,
        double currentPenetration
    ) {
        return tryFragment(
            entity,
            hitEntity,
            bullet,
            currentPenetration,
            null,
            null,
            hitEntity != null ? hitEntity.getDisplayName().getString() : "目标"
        );
    }

    private static int tryFragment(
        Entity entity,
        Entity hitEntity,
        BulletConfig.BulletEntry bullet,
        double currentPenetration,
        Vec3 impactPosition,
        Vec3 surfaceNormal,
        String surfaceName
    ) {
        if (entity == null
            || bullet == null
            || entity.level().isClientSide
            || entity.getPersistentData().getBoolean(FRAGMENT_KEY)
            || entity.getPersistentData().getBoolean(FRAGMENTED_KEY)
            || bullet.fragmentationRate <= 0.0D) {
            return 0;
        }

        prepareForCoverImpact(entity);
        BallisticState state = STATES.get(entity);
        if (state == null) return 0;

        double currentSpeed = hitEntity != null
            ? impactVelocity(entity, hitEntity, state)
            : state.forwardVelocity;
        double effectiveMass = bullet.bulletMass
            * readMultiplier(entity, MASS_MULTIPLIER_KEY);
        boolean fragmentationEligible = BallisticsMath.canFragment(
            effectiveMass,
            currentSpeed
        );
        if (!fragmentationEligible) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "碎弹未判定：%s，动能 %.1fJ（至少 %.1fJ）；有效穿深 %.1f 不限制碎弹。",
                    surfaceName,
                    BallisticsMath.kineticEnergy(effectiveMass, currentSpeed),
                    BallisticsMath.MIN_FRAGMENTATION_ENERGY_JOULES,
                    currentPenetration
                )
            );
            return 0;
        }

        double probability = BallisticsMath.fragmentationProbability(
            bullet.fragmentationRate,
            currentSpeed,
            state.maximumVelocity,
            currentPenetration
        );
        double roll = entity.level().random.nextDouble();
        if (roll >= probability) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "碎弹未触发：%s，实际概率 %.1f%%，随机值 %.1f%%。",
                    surfaceName,
                    probability * 100.0D,
                    roll * 100.0D
                )
            );
            return 0;
        }

        entity.getPersistentData().putBoolean(FRAGMENTED_KEY, true);
        int requestedCount = entity.level().random.nextBoolean() ? 2 : 3;
        double fragmentMultiplier =
            BallisticsMath.fragmentDamageMultiplier(requestedCount);
        Vec3 movement = entity.getDeltaMovement();
        Vec3 direction = movement.lengthSqr() > EPSILON
            ? movement.normalize()
            : state.forward;
        Vec3 normal = surfaceNormal != null
            && surfaceNormal.lengthSqr() > EPSILON
            ? surfaceNormal.normalize()
            : null;
        Vec3 defaultSpawnPosition = impactPosition != null
            ? impactPosition.subtract(direction.scale(FRAGMENT_SPAWN_EPSILON))
            : fragmentSpawnPosition(entity, hitEntity, direction);

        int spawnedCount = 0;
        for (int i = 0; i < requestedCount; i++) {
            Vec3 fragmentForward = normal != null ? normal : direction;
            Vec3 fragmentDirection = randomFragmentDirection(
                fragmentForward,
                entity
            );
            if (normal != null && fragmentDirection.dot(normal) <= 0.0D) {
                fragmentDirection = fragmentDirection.subtract(
                    normal.scale(2.0D * fragmentDirection.dot(normal))
                ).normalize();
            }
            Vec3 spawnPosition = normal != null
                ? impactPosition
                    .add(normal.scale(FRAGMENT_SPAWN_EPSILON))
                    .add(fragmentDirection.scale(FRAGMENT_SPAWN_EPSILON))
                : defaultSpawnPosition;
            if (spawnFragment(
                entity,
                state,
                fragmentDirection,
                spawnPosition,
                currentSpeed,
                fragmentMultiplier
            )) {
                spawnedCount++;
            }
        }
        if (spawnedCount <= 0) {
            entity.getPersistentData().putBoolean(FRAGMENTED_KEY, false);
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                "碎弹判定已触发，但 TACZ 碎片实体创建失败。"
            );
            return 0;
        }

        BallisticsDebugRenderer.sendDebugEvent(
            entity,
            String.format(
                "§6碎弹已触发§r：%s，概率 %.1f%%，生成 %d 枚独立碎片。",
                surfaceName,
                probability * 100.0D,
                spawnedCount
            )
        );

        FragmentationMod.LOGGER.debug(
            "[Fragmentation] Spawned {}/{} fragments, probability={}, currentPenetration={}, fragmentPenetration={}, currentSpeed={}m/s",
            spawnedCount,
            requestedCount,
            probability,
            currentPenetration,
            BallisticsMath.fragmentPenetration(currentPenetration),
            currentSpeed
        );
        STATES.remove(entity);
        entity.discard();
        return spawnedCount;
    }

    public static boolean tryRicochetEntity(
        Entity entity,
        Entity hitEntity,
        BulletConfig.BulletEntry bullet,
        double surfaceRicochetCoefficient,
        Vec3 surfaceNormal
    ) {
        if (entity == null
            || hitEntity == null
            || bullet == null
            || surfaceRicochetCoefficient <= 0.0D
            || bullet.ricochetRate <= 0.0D
            || entity.level().isClientSide) {
            return false;
        }

        prepareForCoverImpact(entity);
        BallisticState state = STATES.get(entity);
        Vec3 movement = entity.getDeltaMovement();
        if (state == null
            || movement.lengthSqr() <= EPSILON
            || surfaceNormal == null
            || surfaceNormal.lengthSqr() <= EPSILON) {
            return false;
        }

        Vec3 incoming = movement.normalize();
        Vec3 normal = surfaceNormal.normalize();
        if (incoming.dot(normal) > 0.0D) {
            normal = normal.scale(-1.0D);
        }
        double incidenceAngle = incidenceAngleDegrees(incoming, normal);
        double currentSpeed = impactVelocity(entity, hitEntity, state);
        double probability = BallisticsMath.ricochetProbability(
            bullet.ricochetRate,
            surfaceRicochetCoefficient,
            incidenceAngle,
            currentSpeed,
            state.maximumVelocity
        );
        double roll = entity.level().random.nextDouble();
        if (roll >= probability) {
            BallisticsDebugRenderer.sendDebugEvent(
                entity,
                String.format(
                    "护甲跳弹未触发：入射角 %.1f°，实际概率 %.1f%%，随机值 %.1f%%。",
                    incidenceAngle,
                    probability * 100.0D,
                    roll * 100.0D
                )
            );
            return false;
        }

        Vec3 ricochetDirection = randomRicochetDirection(
            incoming,
            normal,
            entity
        );
        movePastEntity(entity, hitEntity, ricochetDirection);
        applyRicochet(entity, state, ricochetDirection, currentSpeed);
        keepBulletAliveAfterEntityHit(entity);
        resetTaczDamageOrigin(entity, entity.position());
        BallisticsDebugRenderer.sendDebugEvent(
            entity,
            String.format(
                "§b护甲跳弹已触发§r：目标 %s，入射角 %.1f°，概率 %.1f%%，穿深保留 70%%。",
                hitEntity.getDisplayName().getString(),
                incidenceAngle,
                probability * 100.0D
            )
        );

        FragmentationMod.LOGGER.debug(
            "[Ricochet] Entity={}, angle={}deg, probability={}, speed={}m/s, penetrationMultiplier={}",
            hitEntity.getStringUUID(),
            incidenceAngle,
            probability,
            currentSpeed,
            getPenetrationMultiplier(entity)
        );
        return true;
    }

    private static void movePastCover(
        Entity entity,
        BlockHitResult hitResult,
        BlockState blockState
    ) {
        Vec3 movement = entity.getDeltaMovement();
        if (movement.lengthSqr() <= EPSILON) return;

        Vec3 direction = movement.normalize();
        BlockPos blockPos = hitResult.getBlockPos();
        VoxelShape collisionShape = blockState.getCollisionShape(entity.level(), blockPos);
        AABB bounds = collisionShape.isEmpty()
            ? new AABB(
                blockPos.getX(),
                blockPos.getY(),
                blockPos.getZ(),
                blockPos.getX() + 1.0D,
                blockPos.getY() + 1.0D,
                blockPos.getZ() + 1.0D
            )
            : collisionShape.bounds().move(blockPos);
        Vec3 hitLocation = hitResult.getLocation();

        double exitDistance = Double.POSITIVE_INFINITY;
        exitDistance = Math.min(
            exitDistance,
            distanceToExit(hitLocation.x, direction.x, bounds.minX, bounds.maxX)
        );
        exitDistance = Math.min(
            exitDistance,
            distanceToExit(hitLocation.y, direction.y, bounds.minY, bounds.maxY)
        );
        exitDistance = Math.min(
            exitDistance,
            distanceToExit(hitLocation.z, direction.z, bounds.minZ, bounds.maxZ)
        );
        if (!Double.isFinite(exitDistance)) {
            exitDistance = 0.0D;
        }

        Vec3 exitPosition = hitLocation.add(
            direction.scale(Math.max(0.0D, exitDistance) + COVER_EXIT_EPSILON)
        );
        entity.setPos(exitPosition.x, exitPosition.y, exitPosition.z);
    }

    private static double distanceToExit(
        double location,
        double direction,
        double minimum,
        double maximum
    ) {
        if (direction > EPSILON) {
            return Math.max(0.0D, (maximum - location) / direction);
        }
        if (direction < -EPSILON) {
            return Math.max(0.0D, (minimum - location) / direction);
        }
        return Double.POSITIVE_INFINITY;
    }

    private static Vec3 fragmentSpawnPosition(
        Entity entity,
        Entity hitEntity,
        Vec3 direction
    ) {
        if (hitEntity == null) {
            return entity.position().add(
                direction.scale(FRAGMENT_SPAWN_EPSILON)
            );
        }

        Vec3 start = entity.position();
        Vec3 end = start.add(entity.getDeltaMovement());
        Vec3 impact = hitEntity.getBoundingBox()
            .clip(start, end)
            .orElse(hitEntity.getBoundingBox().getCenter());
        return impact.subtract(direction.scale(FRAGMENT_SPAWN_EPSILON));
    }

    private static boolean spawnFragment(
        Entity source,
        BallisticState sourceState,
        Vec3 direction,
        Vec3 spawnPosition,
        double currentSpeed,
        double fragmentMultiplier
    ) {
        if (!(source.level() instanceof ServerLevel serverLevel)
            || !initializeReflection()) {
            return false;
        }

        Entity fragment = source.getType().create(serverLevel);
        if (fragment == null || !bulletClass.isInstance(fragment)) {
            return false;
        }

        try {
            for (Field field : copiedBulletFields) {
                field.set(fragment, field.get(source));
            }
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Fragmentation] Failed to copy TACZ bullet fields: {}",
                e.getMessage()
            );
            return false;
        }

        if (source instanceof Projectile sourceProjectile
            && fragment instanceof Projectile fragmentProjectile) {
            fragmentProjectile.setOwner(sourceProjectile.getOwner());
        }
        fragment.getPersistentData().merge(
            source.getPersistentData().copy()
        );
        fragment.getPersistentData().putBoolean(FRAGMENT_KEY, true);
        fragment.getPersistentData().putBoolean(FRAGMENTED_KEY, false);
        fragment.getPersistentData().putDouble(
            DAMAGE_MULTIPLIER_KEY,
            getDamageMultiplier(source) * fragmentMultiplier
        );
        fragment.getPersistentData().putDouble(
            PENETRATION_MULTIPLIER_KEY,
            getPenetrationMultiplier(source) * 0.25D
        );
        fragment.getPersistentData().putDouble(
            MASS_MULTIPLIER_KEY,
            readMultiplier(source, MASS_MULTIPLIER_KEY)
                * fragmentMultiplier
        );
        fragment.getPersistentData().putDouble(
            CURRENT_SPEED_KEY,
            currentSpeed
        );
        fragment.getPersistentData().putDouble(
            MAXIMUM_SPEED_KEY,
            sourceState.maximumVelocity
        );
        fragment.setPos(spawnPosition.x, spawnPosition.y, spawnPosition.z);
        fragment.setDeltaMovement(
            direction.scale(currentSpeed * BallisticsMath.TICK_SECONDS)
        );
        resetTaczDamageOrigin(fragment, spawnPosition);
        ensureBulletCanHitEntity(fragment);

        BallisticState fragmentState = new BallisticState(
            direction,
            horizontalRight(direction),
            currentSpeed,
            sourceState.maximumVelocity,
            sourceState.muzzleVelocity,
            sourceState.gravityAcceleration
        );
        fragmentState.elapsedSeconds = sourceState.elapsedSeconds;
        fragmentState.distanceMeters = sourceState.distanceMeters;
        fragmentState.previousLagSeconds = sourceState.previousLagSeconds;
        fragmentState.penetratedCoverBlocks.addAll(
            sourceState.penetratedCoverBlocks
        );
        STATES.put(fragment, fragmentState);
        extendTaczLifetimeForArc(
            fragment,
            direction,
            currentSpeed,
            fragmentState.gravityAcceleration
        );
        if (!serverLevel.addFreshEntity(fragment)) {
            STATES.remove(fragment);
            return false;
        }
        BallisticsDebugRenderer.renderFlight(fragment, true, false, false);
        return true;
    }

    private static double incidenceAngleDegrees(
        Vec3 incoming,
        Vec3 surfaceNormal
    ) {
        if (incoming.lengthSqr() <= EPSILON
            || surfaceNormal.lengthSqr() <= EPSILON) {
            return 90.0D;
        }
        double normalComponent = Math.abs(
            incoming.normalize().dot(surfaceNormal.normalize())
        );
        return Math.toDegrees(
            Math.asin(Math.max(0.0D, Math.min(1.0D, normalComponent)))
        );
    }

    private static Vec3 randomFragmentDirection(
        Vec3 forward,
        Entity entity
    ) {
        Vec3 normalizedForward = forward.normalize();
        Vec3 helper = Math.abs(normalizedForward.y) < 0.9D
            ? new Vec3(0.0D, 1.0D, 0.0D)
            : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 tangent = normalizedForward.cross(helper).normalize();
        Vec3 bitangent = normalizedForward.cross(tangent).normalize();
        double angle = Math.toRadians(
            5.0D + entity.level().random.nextDouble() * 20.0D
        );
        double azimuth = entity.level().random.nextDouble()
            * Math.PI * 2.0D;
        return normalizedForward.scale(Math.cos(angle))
            .add(tangent.scale(Math.sin(angle) * Math.cos(azimuth)))
            .add(bitangent.scale(Math.sin(angle) * Math.sin(azimuth)))
            .normalize();
    }

    private static Vec3 randomRicochetDirection(
        Vec3 incoming,
        Vec3 surfaceNormal,
        Entity entity
    ) {
        Vec3 normal = surfaceNormal.normalize();
        Vec3 reflected = incoming.subtract(
            normal.scale(2.0D * incoming.dot(normal))
        ).normalize();
        Vec3 helper = Math.abs(reflected.y) < 0.9D
            ? new Vec3(0.0D, 1.0D, 0.0D)
            : new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 tangent = reflected.cross(helper).normalize();
        Vec3 bitangent = reflected.cross(tangent).normalize();
        double angle = Math.toRadians(
            20.0D + entity.level().random.nextDouble() * 40.0D
        );
        double azimuth = entity.level().random.nextDouble()
            * Math.PI * 2.0D;
        Vec3 direction = reflected.scale(Math.cos(angle))
            .add(tangent.scale(Math.sin(angle) * Math.cos(azimuth)))
            .add(bitangent.scale(Math.sin(angle) * Math.sin(azimuth)))
            .normalize();
        if (direction.dot(normal) < 0.0D) {
            direction = direction.subtract(
                normal.scale(2.0D * direction.dot(normal))
            ).normalize();
        }
        return direction;
    }

    private static void applyRicochet(
        Entity entity,
        BallisticState state,
        Vec3 direction,
        double currentSpeed
    ) {
        state.forward = direction;
        state.right = horizontalRight(direction);
        state.forwardVelocity = currentSpeed;
        state.frameStartVelocity = currentSpeed;
        state.frameEndVelocity = currentSpeed;
        state.downwardVelocity = 0.0D;
        entity.setDeltaMovement(
            direction.scale(currentSpeed * BallisticsMath.TICK_SECONDS)
        );
        entity.getPersistentData().putDouble(
            PENETRATION_MULTIPLIER_KEY,
            getPenetrationMultiplier(entity)
                * BallisticsMath.RICOCHET_PENETRATION_MULTIPLIER
        );
        entity.getPersistentData().putDouble(
            DAMAGE_MULTIPLIER_KEY,
            getDamageMultiplier(entity)
                * BallisticsMath.RICOCHET_PENETRATION_MULTIPLIER
        );
        entity.getPersistentData().putBoolean(RICOCHET_KEY, true);
        entity.getPersistentData().putDouble(CURRENT_SPEED_KEY, currentSpeed);
        extendTaczLifetimeForArc(
            entity,
            direction,
            currentSpeed,
            state.gravityAcceleration
        );
    }

    private static void movePastEntity(
        Entity projectile,
        Entity hitEntity,
        Vec3 direction
    ) {
        Vec3 start = projectile.position();
        Vec3 end = start.add(projectile.getDeltaMovement());
        Vec3 impact = hitEntity.getBoundingBox()
            .clip(start, end)
            .orElse(hitEntity.getBoundingBox().getCenter());
        Vec3 exitPosition = impact.add(
            direction.scale(FRAGMENT_SPAWN_EPSILON)
        );
        projectile.setPos(
            exitPosition.x,
            exitPosition.y,
            exitPosition.z
        );
    }

    private static void keepBulletAliveAfterEntityHit(Entity entity) {
        if (pierceField == null) return;
        try {
            int currentPierce = pierceField.getInt(entity);
            if (currentPierce < 2) {
                pierceField.setInt(entity, 2);
            }
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Ricochet] Failed to preserve TACZ bullet pierce count: {}",
                e.getMessage()
            );
        }
    }

    private static void ensureBulletCanHitEntity(Entity entity) {
        if (pierceField == null) return;
        try {
            if (pierceField.getInt(entity) < 1) {
                pierceField.setInt(entity, 1);
            }
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to initialize TACZ bullet pierce count: {}",
                e.getMessage()
            );
        }
    }

    private static void resetTaczDamageOrigin(Entity entity, Vec3 origin) {
        if (startPosField == null || entity == null || origin == null) return;
        try {
            startPosField.set(entity, origin);
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to reset TACZ damage origin: {}",
                e.getMessage()
            );
        }
    }

    private static void extendTaczLifetimeForArc(
        Entity entity,
        Vec3 direction,
        double speed,
        double gravityAcceleration
    ) {
        if (lifeField == null || entity == null || direction == null) return;
        int remainingTicks = BallisticsMath.requiredArcLifetimeTicks(
            direction.y * speed,
            gravityAcceleration
        );
        if (remainingTicks <= 0) return;
        try {
            int requiredLifetime = Math.min(
                BallisticsMath.MAX_TRAJECTORY_LIFETIME_TICKS,
                entity.tickCount + remainingTicks
            );
            if (lifeField.getInt(entity) < requiredLifetime) {
                lifeField.setInt(entity, requiredLifetime);
            }
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to extend TACZ bullet lifetime: {}",
                e.getMessage()
            );
        }
    }

    private static double readMultiplier(Entity entity, String key) {
        if (entity == null || !entity.getPersistentData().contains(key)) {
            return 1.0D;
        }
        double value = entity.getPersistentData().getDouble(key);
        return Double.isFinite(value) ? Math.max(0.0D, value) : 1.0D;
    }

    public static void beforeEntityTick(Entity entity) {
        if (entity.level().isClientSide
            || !TACZ_BULLET_CLASS.equals(entity.getClass().getName())
            || !initializeReflection()) {
            return;
        }
        if (entity.isRemoved()) {
            STATES.remove(entity);
            return;
        }

        BallisticState state = STATES.get(entity);
        if (state == null) {
            state = initializeState(entity);
            if (state == null) return;
            STATES.put(entity, state);
            // Keep the spawn tick at muzzle velocity; drag starts on the next tick.
            BallisticsDebugRenderer.renderFlight(
                entity,
                entity.getPersistentData().getBoolean(FRAGMENT_KEY),
                entity.getPersistentData().getBoolean(COVER_PENETRATED_KEY),
                entity.getPersistentData().getBoolean(RICOCHET_KEY)
            );
            entity.getPersistentData().putBoolean(COVER_PENETRATED_KEY, false);
            return;
        }

        applyNextFrame(entity, state);
        clampSpecialProjectileMovementToFirstBlock(entity);
        BallisticsDebugRenderer.renderFlight(
            entity,
            entity.getPersistentData().getBoolean(FRAGMENT_KEY),
            entity.getPersistentData().getBoolean(COVER_PENETRATED_KEY),
            entity.getPersistentData().getBoolean(RICOCHET_KEY)
        );
        entity.getPersistentData().putBoolean(COVER_PENETRATED_KEY, false);
    }

    private static BallisticState initializeState(Entity entity) {
        if (!(entity instanceof Projectile projectile)) return null;

        try {
            ResourceLocation gunId = asResourceLocation(getGunIdMethod.invoke(entity));
            ResourceLocation ammoId = getAmmoId(entity);
            if (gunId == null) return null;

            Vec3 inheritedMovement = inheritedMovement(projectile.getOwner());
            Vec3 ballisticMovement = entity.getDeltaMovement().subtract(inheritedMovement);
            if (ballisticMovement.lengthSqr() <= EPSILON) {
                ballisticMovement = entity.getDeltaMovement();
            }
            if (ballisticMovement.lengthSqr() <= EPSILON) return null;

            Vec3 forward = ballisticMovement.normalize();
            double taczSpeed = ballisticMovement.length() / BallisticsMath.TICK_SECONDS;
            float muzzleVelocity = GunConfig.getInstance().getMuzzleVelocity(
                gunId.toString(),
                (float) taczSpeed
            );
            BulletConfig bulletConfig = FragmentationMod.getBulletConfig();
            float bulletSpeed = bulletConfig != null
                ? bulletConfig.getBulletSpeed(
                    ammoId != null ? ammoId.toString() : "",
                    (float) taczSpeed
                )
                : (float) taczSpeed;
            double initialVelocity = BallisticsMath.initialVelocity(
                bulletSpeed,
                muzzleVelocity
            );
            double maximumVelocity = initialVelocity;

            double taczGravity = readNonNegativeFloat(gravityField, entity, -1.0D);
            double gravityAcceleration = taczGravity >= 0.0D
                ? taczGravity / (BallisticsMath.TICK_SECONDS * BallisticsMath.TICK_SECONDS)
                : DEFAULT_GRAVITY_ACCELERATION;
            WorldWindManager.WindSample environment = WorldWindManager.getWindForBullet(
                entity.level(),
                entity.blockPosition(),
                entity
            );

            Vec3 right = horizontalRight(forward);
            entity.setDeltaMovement(forward.scale(initialVelocity * BallisticsMath.TICK_SECONDS));
            entity.getPersistentData().putDouble(CURRENT_SPEED_KEY, initialVelocity);
            entity.getPersistentData().putDouble(MAXIMUM_SPEED_KEY, maximumVelocity);
            extendTaczLifetimeForArc(
                entity,
                forward,
                initialVelocity,
                gravityAcceleration
            );

            FragmentationMod.LOGGER.debug(
                "[Ballistics] Initialized gun={}, ammo={}, muzzle={}m/s, bulletConfig={}m/s, maximum={}m/s, air={}C, drag={}, gravity={}",
                gunId,
                ammoId,
                muzzleVelocity,
                bulletSpeed,
                maximumVelocity,
                environment.temperatureCelsius(),
                environment.dragCoefficient(),
                gravityAcceleration
            );
            return new BallisticState(
                forward,
                right,
                initialVelocity,
                maximumVelocity,
                muzzleVelocity,
                gravityAcceleration
            );
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn("[Ballistics] Failed to initialize TACZ bullet: {}", e.getMessage());
            return null;
        }
    }

    private static void applyNextFrame(Entity entity, BallisticState state) {
        double timeStep = BallisticsMath.TICK_SECONDS;
        WorldWindManager.WindSample environment = WorldWindManager.getWindForBullet(
            entity.level(),
            entity.blockPosition(),
            entity
        );
        double dragCoefficient = environment.dragCoefficient();
        double gravityAcceleration = state.gravityAcceleration;
        if (entity.isInWater()) {
            dragCoefficient = state.forwardVelocity > EPSILON
                ? 0.4D / (state.forwardVelocity * timeStep)
                : environment.dragCoefficient();
            gravityAcceleration *= 0.6D;
        }

        state.frameStartVelocity = state.forwardVelocity;
        state.forwardVelocity = BallisticsMath.nextForwardVelocity(
            state.forwardVelocity,
            dragCoefficient,
            timeStep
        );
        state.frameEndVelocity = state.forwardVelocity;
        state.downwardVelocity = BallisticsMath.nextDownwardVelocity(
            state.downwardVelocity,
            gravityAcceleration,
            dragCoefficient,
            timeStep
        );
        entity.getPersistentData().putDouble(CURRENT_SPEED_KEY, state.forwardVelocity);
        entity.getPersistentData().putDouble(MAXIMUM_SPEED_KEY, state.maximumVelocity);

        double forwardDistance = state.forwardVelocity * timeStep;
        state.distanceMeters += forwardDistance;
        state.elapsedSeconds += timeStep;

        double lagTime = BallisticsMath.lagTime(
            state.elapsedSeconds,
            state.distanceMeters,
            state.muzzleVelocity
        );
        double lagDelta = Math.max(0.0D, lagTime - state.previousLagSeconds);
        state.previousLagSeconds = lagTime;

        double lateralWindSpeed = environment.lateralSpeed(
            state.right.x,
            state.right.z
        );
        double windDisplacement = lateralWindSpeed * lagDelta;

        Vec3 movement = state.forward.scale(forwardDistance)
            .add(0.0D, -state.downwardVelocity * timeStep, 0.0D)
            .add(state.right.scale(windDisplacement));
        if (isFinite(movement)) {
            entity.setDeltaMovement(movement);
        }
    }

    private static void clampSpecialProjectileMovementToFirstBlock(Entity entity) {
        if (!entity.getPersistentData().getBoolean(FRAGMENT_KEY)
            && !entity.getPersistentData().getBoolean(RICOCHET_KEY)) {
            return;
        }

        Vec3 movement = entity.getDeltaMovement();
        if (!isFinite(movement) || movement.lengthSqr() <= EPSILON) return;

        Vec3 start = entity.position();
        Vec3 end = start.add(movement);
        BlockHitResult hitResult = entity.level().clip(
            new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                entity
            )
        );
        if (hitResult.getType() != HitResult.Type.BLOCK) return;

        Vec3 direction = movement.normalize();
        Vec3 clampedEnd = hitResult.getLocation().add(
            direction.scale(COLLISION_TRACE_EPSILON)
        );
        entity.setDeltaMovement(clampedEnd.subtract(start));
    }

    private static double impactVelocity(
        Entity projectile,
        Entity hitEntity,
        BallisticState state
    ) {
        if (Math.abs(state.frameStartVelocity - state.frameEndVelocity) <= EPSILON) {
            return state.forwardVelocity;
        }

        Vec3 movement = projectile.getDeltaMovement();
        double movementLengthSquared = movement.lengthSqr();
        if (movementLengthSquared <= EPSILON) {
            return state.forwardVelocity;
        }

        Vec3 frameStart = projectile.position();
        if (hitEntity.getBoundingBox().contains(frameStart)) {
            return state.frameStartVelocity;
        }

        Vec3 frameEnd = frameStart.add(movement);
        Vec3 impactPoint = hitEntity.getBoundingBox()
            .clip(frameStart, frameEnd)
            .orElse(hitEntity.getBoundingBox().getCenter());
        return impactVelocity(projectile, impactPoint, state);
    }

    private static double impactVelocity(
        Entity projectile,
        Vec3 impactPoint,
        BallisticState state
    ) {
        if (Math.abs(state.frameStartVelocity - state.frameEndVelocity) <= EPSILON) {
            return state.forwardVelocity;
        }

        Vec3 movement = projectile.getDeltaMovement();
        double movementLengthSquared = movement.lengthSqr();
        if (movementLengthSquared <= EPSILON) {
            return state.forwardVelocity;
        }

        Vec3 frameStart = projectile.position();
        double frameFraction = impactPoint.subtract(frameStart).dot(movement)
            / movementLengthSquared;
        return BallisticsMath.velocityAtFrameFraction(
            state.frameStartVelocity,
            state.frameEndVelocity,
            frameFraction
        );
    }

    private static Vec3 inheritedMovement(Entity owner) {
        if (!(owner instanceof LivingEntity shooter)) return Vec3.ZERO;
        Vec3 movement = shooter.getDeltaMovement();
        return new Vec3(
            movement.x,
            shooter.onGround() ? 0.0D : movement.y,
            movement.z
        );
    }

    private static Vec3 horizontalRight(Vec3 forward) {
        Vec3 horizontal = new Vec3(forward.x, 0.0D, forward.z);
        if (horizontal.lengthSqr() <= EPSILON) {
            return new Vec3(1.0D, 0.0D, 0.0D);
        }
        return horizontal.normalize().cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
    }

    private static boolean initializeReflection() {
        if (reflectionInitialized) return bulletClass != null && getGunIdMethod != null;
        reflectionInitialized = true;
        try {
            bulletClass = Class.forName(TACZ_BULLET_CLASS);
            getGunIdMethod = bulletClass.getMethod("getGunId");
            try {
                getAmmoIdMethod = bulletClass.getMethod("getAmmoId");
            } catch (NoSuchMethodException ignored) {
                getAmmoIdMethod = null;
            }
            ammoIdField = accessibleField(bulletClass, "ammoId");
            gunDisplayIdField = accessibleField(bulletClass, "gunDisplayId");
            gravityField = accessibleField(bulletClass, "gravity");
            lifeField = accessibleField(bulletClass, "life");
            pierceField = accessibleField(bulletClass, "pierce");
            startPosField = accessibleField(bulletClass, "startPos");
            explosionField = accessibleField(bulletClass, "explosion");
            List<Field> fields = new ArrayList<>();
            for (Field field : bulletClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
            copiedBulletFields = List.copyOf(fields);
            initializeEntityHitReflection();
            return true;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn("[Ballistics] TACZ bullet API unavailable: {}", e.getMessage());
            return false;
        }
    }

    private static boolean initializeEntityHitReflection() {
        if (entityHitReflectionInitialized) {
            return onHitEntityMethod != null;
        }
        entityHitReflectionInitialized = true;
        try {
            tacHitResultClass = Class.forName(
                "com.tacz.guns.util.TacHitResult"
            );
            entityResultClass = Class.forName(
                "com.tacz.guns.entity.EntityKineticBullet$EntityResult"
            );
            for (Constructor<?> constructor : entityResultClass.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                entityResultConstructor = constructor;
                break;
            }
            tacHitResultConstructor = tacHitResultClass.getDeclaredConstructor(
                entityResultClass
            );
            tacHitResultConstructor.setAccessible(true);
            onHitEntityMethod = bulletClass.getDeclaredMethod(
                "onHitEntity",
                tacHitResultClass,
                Vec3.class,
                Vec3.class
            );
            onHitEntityMethod.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] TACZ onHitEntity API unavailable: {}",
                e.getMessage()
            );
            return false;
        }
    }

    private static boolean initializeBulletHoleReflection() {
        if (bulletHoleReflectionInitialized) {
            return bulletHoleConstructor != null
                && gunDisplayIdField != null;
        }
        bulletHoleReflectionInitialized = true;
        if (!initializeReflection() || gunDisplayIdField == null) {
            return false;
        }

        try {
            Class<?> bulletHoleClass = Class.forName(
                "com.tacz.guns.particles.BulletHoleOption"
            );
            bulletHoleConstructor = bulletHoleClass.getConstructor(
                Direction.class,
                BlockPos.class,
                String.class,
                String.class,
                String.class
            );
            return true;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] TACZ bullet-hole API unavailable: {}",
                e.getMessage()
            );
            return false;
        }
    }

    private static boolean isExplosive(Entity entity) {
        if (entity == null
            || !initializeReflection()
            || explosionField == null) {
            return false;
        }
        try {
            return explosionField.getBoolean(entity);
        } catch (IllegalAccessException e) {
            FragmentationMod.LOGGER.warn(
                "[Ballistics] Failed to read TACZ explosion flag: {}",
                e.getMessage()
            );
            return false;
        }
    }

    private static Field accessibleField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            FragmentationMod.LOGGER.warn("[Ballistics] TACZ field {} unavailable", name);
            return null;
        }
    }

    private static double readNonNegativeFloat(
        Field field,
        Entity entity,
        double fallback
    ) throws IllegalAccessException {
        if (field == null) return fallback;
        Object value = field.get(entity);
        if (!(value instanceof Number number)) return fallback;
        double result = number.doubleValue();
        return Double.isFinite(result) ? Math.max(0.0D, result) : fallback;
    }

    private static ResourceLocation asResourceLocation(Object value) {
        return value instanceof ResourceLocation id ? id : null;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x)
            && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    private static final class BallisticState {
        private Vec3 forward;
        private Vec3 right;
        private final double maximumVelocity;
        private final double muzzleVelocity;
        private final double gravityAcceleration;
        private final Set<Long> penetratedCoverBlocks = new HashSet<>();
        private double forwardVelocity;
        private double frameStartVelocity;
        private double frameEndVelocity;
        private double downwardVelocity;
        private double elapsedSeconds;
        private double distanceMeters;
        private double previousLagSeconds;

        private BallisticState(
            Vec3 forward,
            Vec3 right,
            double forwardVelocity,
            double maximumVelocity,
            double muzzleVelocity,
            double gravityAcceleration
        ) {
            this.forward = forward;
            this.right = right;
            this.forwardVelocity = forwardVelocity;
            this.frameStartVelocity = forwardVelocity;
            this.frameEndVelocity = forwardVelocity;
            this.maximumVelocity = maximumVelocity;
            this.muzzleVelocity = muzzleVelocity;
            this.gravityAcceleration = gravityAcceleration;
        }
    }

    public record SpeedSnapshot(
        double currentSpeed,
        double maximumSpeed,
        boolean coverPenetrated
    ) {
        public SpeedSnapshot(double currentSpeed, double maximumSpeed) {
            this(currentSpeed, maximumSpeed, false);
        }

        public double speedRatio() {
            return BallisticsMath.speedRatio(currentSpeed, maximumSpeed);
        }

        public double energyRetention() {
            return coverPenetrated
                ? BallisticsMath.coverAttenuation(currentSpeed, maximumSpeed)
                : BallisticsMath.energyRetention(currentSpeed, maximumSpeed);
        }
    }
}
