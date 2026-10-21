package com.fragmentation.ballistics;

public final class BallisticsMath {
    public static final double TICK_SECONDS = 1.0D / 20.0D;
    public static final int DEFAULT_AIR_TEMPERATURE_CELSIUS = 15;
    public static final double MAX_ENERGY_RETENTION = 1.0D;
    public static final double MIN_FRAGMENTATION_ENERGY_JOULES = 100.0D;
    public static final double FRAGMENTATION_LOW_PENETRATION_SCALE = 20.0D;
    public static final double MAX_RICOCHET_ANGLE_DEGREES = 45.0D;
    public static final double RICOCHET_PENETRATION_MULTIPLIER = 0.7D;
    public static final int TRAJECTORY_LIFETIME_MARGIN_TICKS = 40;
    public static final int MAX_TRAJECTORY_LIFETIME_TICKS = 6000;

    private BallisticsMath() {
    }

    public static boolean isSupportedAirTemperature(int temperatureCelsius) {
        return temperatureCelsius == 0
            || temperatureCelsius == 15
            || temperatureCelsius == 30
            || temperatureCelsius == 40;
    }

    public static double airDragCoefficient(int temperatureCelsius) {
        return switch (temperatureCelsius) {
            case 0 -> 0.00264D;
            case 15 -> 0.00250D;
            case 30 -> 0.00238D;
            case 40 -> 0.00230D;
            default -> 0.00250D;
        };
    }

    public static double lateralWindSpeed(
        double windX,
        double windZ,
        double windSpeed,
        double rightX,
        double rightZ
    ) {
        double safeSpeed = finiteNonNegative(windSpeed);
        double lateral = (windX * rightX + windZ * rightZ) * safeSpeed;
        return Double.isFinite(lateral) ? lateral : 0.0D;
    }

    public static double initialVelocity(double bulletSpeed, double muzzleVelocity) {
        return bulletSpeed + (muzzleVelocity - bulletSpeed);
    }

    public static double speedRatio(double currentSpeed, double maximumSpeed) {
        if (!Double.isFinite(maximumSpeed) || maximumSpeed <= 0.0D) return 0.0D;
        double ratio = finiteNonNegative(currentSpeed) / maximumSpeed;
        return Math.min(1.0D, ratio);
    }

    public static double energyRetention(double currentSpeed, double maximumSpeed) {
        return speedRatio(currentSpeed, maximumSpeed);
    }

    public static double retainedValue(double baseValue, double energyRetention) {
        double safeBase = finiteNonNegative(baseValue);
        double safeRetention = Double.isFinite(energyRetention)
            ? Math.max(0.0D, Math.min(MAX_ENERGY_RETENTION, energyRetention))
            : 0.0D;
        return safeBase * safeRetention;
    }

    public static double retainedArmorDamage(double baseArmorDamage, double energyRetention) {
        return retainedValue(baseArmorDamage, energyRetention);
    }

    public static double kineticEnergy(double bulletMass, double currentSpeed) {
        double safeMass = finiteNonNegative(bulletMass);
        double safeSpeed = finiteNonNegative(currentSpeed);
        return 0.5D * safeMass * safeSpeed * safeSpeed;
    }

    public static double bulletCrossSection(double bulletRadius) {
        double safeRadius = finiteNonNegative(bulletRadius);
        return Math.PI * safeRadius * safeRadius;
    }

    public static boolean canPenetrateCover(
        double bulletMass,
        double currentSpeed,
        double penetrationThreshold,
        double bulletRadius
    ) {
        if (penetrationThreshold <= 0.0D) {
            return true;
        }
        double requiredEnergy = finiteNonNegative(penetrationThreshold)
            * bulletCrossSection(bulletRadius);
        return bulletMass > 0.0D
            && bulletRadius > 0.0D
            && kineticEnergy(bulletMass, currentSpeed) >= requiredEnergy;
    }

    public static double postPenetrationSpeed(
        double currentSpeed,
        double thicknessCoefficient,
        double densityCoefficient
    ) {
        double speedMultiplier = 1.0D
            - finiteNonNegative(thicknessCoefficient)
            * finiteNonNegative(densityCoefficient);
        return finiteNonNegative(currentSpeed) * Math.max(0.0D, speedMultiplier);
    }

    public static double coverAttenuation(double postPenetrationSpeed, double muzzleVelocity) {
        return Math.pow(speedRatio(postPenetrationSpeed, muzzleVelocity), 0.85D);
    }

    public static boolean canFragment(double bulletMass, double currentSpeed) {
        return kineticEnergy(bulletMass, currentSpeed)
            >= MIN_FRAGMENTATION_ENERGY_JOULES;
    }

    public static double fragmentationProbability(
        double baseFragmentationRate,
        double currentSpeed,
        double muzzleVelocity,
        double currentPenetration
    ) {
        double baseProbability = clampUnit(baseFragmentationRate)
            * Math.sqrt(speedRatio(currentSpeed, muzzleVelocity));
        if (baseProbability <= 0.0D || baseProbability >= 1.0D) {
            return baseProbability;
        }

        double lowPenetrationFactor = 1.0D / (
            1.0D + finiteNonNegative(currentPenetration)
                / FRAGMENTATION_LOW_PENETRATION_SCALE
        );
        double lowPenetrationBoost = (1.0D - baseProbability)
            * 0.25D
            * lowPenetrationFactor;
        return clampUnit(baseProbability + lowPenetrationBoost);
    }

    /**
     * 单个弹片的伤害系数。所有弹片的伤害总和为原子弹伤害的 85%。
     * 例如 2 个弹片时每个系数为 0.425，3 个时为 0.2833。
     */
    public static double fragmentDamageMultiplier(int fragmentCount) {
        return fragmentCount > 0 ? 0.85D / fragmentCount : 0.0D;
    }

    public static double fragmentPenetration(double currentPenetration) {
        return finiteNonNegative(currentPenetration) * 0.25D;
    }

    public static boolean isRicochetAngle(double incidenceAngleDegrees) {
        return Double.isFinite(incidenceAngleDegrees)
            && incidenceAngleDegrees >= 0.0D
            && incidenceAngleDegrees <= MAX_RICOCHET_ANGLE_DEGREES;
    }

    public static double ricochetProbability(
        double baseRicochetRate,
        double surfaceRicochetCoefficient,
        double incidenceAngleDegrees,
        double currentSpeed,
        double muzzleVelocity
    ) {
        if (!isRicochetAngle(incidenceAngleDegrees)) return 0.0D;
        double configuredRate = clampUnit(baseRicochetRate);
        double surfaceCoefficient = clampUnit(surfaceRicochetCoefficient);
        if (configuredRate >= 1.0D && surfaceCoefficient >= 1.0D) {
            return 1.0D;
        }

        double normalizedAngle = Math.min(
            1.0D,
            finiteNonNegative(incidenceAngleDegrees) / 90.0D
        );
        double angleCorrection = 1.0D - Math.sqrt(normalizedAngle);
        double angleAdjusted = configuredRate
            * surfaceCoefficient
            * (0.7D + 0.3D * angleCorrection);
        return clampUnit(
            angleAdjusted
                * (0.6D + 0.4D * speedRatio(currentSpeed, muzzleVelocity))
        );
    }

    public static double firstHitPenetrationChance(
        double bulletPenetration,
        double armorThreshold
    ) {
        double surplus = finiteNonNegative(bulletPenetration)
            - finiteNonNegative(armorThreshold)
            - 15.0D;
        if (surplus <= 0.0D) return 0.0D;
        return Math.min(1.0D, 0.004D * surplus * surplus * 100.0D);
    }

    public static double durabilityMoaMultiplier(double durabilityRatio) {
        double ratio = Double.isFinite(durabilityRatio)
            ? Math.max(0.0D, Math.min(1.0D, durabilityRatio))
            : 1.0D;
        if (ratio >= 0.5D) {
            return 1.0D + 0.2D * ((1.0D - ratio) / 0.5D);
        }
        double wearBelowHalf = (0.5D - ratio) / 0.5D;
        return 1.2D + 2.8D * Math.pow(wearBelowHalf, 0.7D);
    }

    /**
     * Mirrors the TACZ: Durability addon's inaccuracy formula exactly.
     * Source: com.corrinedev.gundurability.mixin.AccuracyMixin#shootOnce
     *
     * Formula: inaccuracyMultiplier = (maxDurability - currentDurability) / inaccuracyRate + 1
     *
     * Defaults (when the gundurability addon is installed with default config):
     *   maxDurability  = 2000 (Config.MAXDURABILITY)
     *   inaccuracyRate = 500  (Config.INACCURACYRATE)
     *
     * Resulting curve with defaults:
     *   100% durability -> 1.0x (no penalty)
     *   75% durability  -> 2.0x inaccuracy
     *   50% durability  -> 3.0x inaccuracy
     *   25% durability  -> 4.0x inaccuracy
     *    0% durability  -> 5.0x inaccuracy
     *
     * This matches the upstream addon's behavior for cross-mod compatibility,
     * so players who already run gundurability see consistent inaccuracy here too.
     *
     * @param currentDurability current durability value read from the gun NBT (>=0)
     * @param maxDurability    configured maximum durability for this gun (>=0)
     * @param inaccuracyRate   gundurability's INACCURACYRATE config value (>0 to divide)
     * @return multiplier >= 1.0 to apply on top of the base MOA/inaccuracy
     */
    public static double gundurabilityMoaMultiplier(
        int currentDurability,
        int maxDurability,
        int inaccuracyRate
    ) {
        int safeCurrent = Math.max(0, currentDurability);
        int safeMax = Math.max(0, maxDurability);
        int safeRate = inaccuracyRate > 0 ? inaccuracyRate : 1;
        if (safeMax <= 0) {
            return 1.0D;
        }
        int wear = Math.abs(safeCurrent - safeMax);
        if (safeCurrent > safeMax) {
            wear = 0;
        }
        double ratio = (double) wear / (double) safeRate;
        return ratio + 1.0D;
    }

    public static double nextForwardVelocity(
        double currentVelocity,
        double dragCoefficient,
        double timeStep
    ) {
        double next = currentVelocity
            - dragCoefficient * currentVelocity * currentVelocity * timeStep;
        return finiteNonNegative(next);
    }

    public static double velocityAtFrameFraction(
        double frameStartVelocity,
        double frameEndVelocity,
        double frameFraction
    ) {
        double start = finiteNonNegative(frameStartVelocity);
        double end = finiteNonNegative(frameEndVelocity);
        double fraction = Double.isFinite(frameFraction)
            ? Math.max(0.0D, Math.min(1.0D, frameFraction))
            : 0.0D;
        return finiteNonNegative(start + (end - start) * fraction);
    }

    public static double nextDownwardVelocity(
        double currentVelocity,
        double gravityAcceleration,
        double dragCoefficient,
        double timeStep
    ) {
        double next = currentVelocity
            + (gravityAcceleration - dragCoefficient * currentVelocity * currentVelocity) * timeStep;
        return finiteNonNegative(next);
    }

    public static int requiredArcLifetimeTicks(
        double upwardVelocity,
        double gravityAcceleration
    ) {
        double velocity = finiteNonNegative(upwardVelocity);
        double gravity = finiteNonNegative(gravityAcceleration);
        if (velocity <= 0.0D || gravity <= 0.0D) {
            return 0;
        }
        double arcSeconds = 2.0D * velocity / gravity;
        long arcTicks = (long) Math.ceil(arcSeconds / TICK_SECONDS)
            + TRAJECTORY_LIFETIME_MARGIN_TICKS;
        return (int) Math.min(MAX_TRAJECTORY_LIFETIME_TICKS, arcTicks);
    }

    public static double estimateFlightTime(
        double distanceMeters,
        double dragCoefficient,
        double bulletSpeed,
        double muzzleVelocity
    ) {
        double distance = finiteNonNegative(distanceMeters);
        double initialVelocity = initialVelocity(bulletSpeed, muzzleVelocity);
        if (initialVelocity <= 0.0D) return 0.0D;
        if (dragCoefficient <= 0.0D || !Double.isFinite(dragCoefficient)) {
            return distance / initialVelocity;
        }

        double denominator = dragCoefficient * initialVelocity;
        double argument = 1.0D + denominator * distance;
        return argument > 0.0D ? Math.log(argument) / denominator : 0.0D;
    }

    public static double estimateGravityDrop(double gravityAcceleration, double flightTime) {
        double safeGravity = finiteNonNegative(gravityAcceleration);
        double safeTime = finiteNonNegative(flightTime);
        return 0.5D * safeGravity * safeTime * safeTime;
    }

    public static double lagTime(
        double actualFlightTime,
        double distanceMeters,
        double muzzleVelocity
    ) {
        double safeTime = finiteNonNegative(actualFlightTime);
        if (muzzleVelocity <= 0.0D || !Double.isFinite(muzzleVelocity)) {
            return 0.0D;
        }
        return Math.max(0.0D, safeTime - finiteNonNegative(distanceMeters) / muzzleVelocity);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private static double clampUnit(double value) {
        return Math.min(1.0D, finiteNonNegative(value));
    }
}
