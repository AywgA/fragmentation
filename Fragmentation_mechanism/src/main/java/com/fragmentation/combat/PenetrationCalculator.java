package com.fragmentation.combat;

import com.fragmentation.data.PlateData;

import java.util.Random;

public class PenetrationCalculator {
    private static final float EQUALITY_TOLERANCE = 0.1F;

    private final Random random;

    public PenetrationCalculator() {
        this(new Random());
    }

    public PenetrationCalculator(Random random) {
        this.random = random;
    }

    public PenetrationResult resolveHit(PlateData plateData, float incomingDamage, float penetrationValue) {
        return resolveHit(plateData, incomingDamage, penetrationValue, -1.0F);
    }

    public PenetrationResult resolveHit(PlateData plateData, float incomingDamage, float penetrationValue, float rawBulletDamage) {
        if (plateData == null || !plateData.canProtect()) {
            return new PenetrationResult(
                    true,
                    true,
                    true,
                    0,
                    incomingDamage,
                    1.0F,
                    0.0F
            );
        }

        int threshold = plateData.getPenetrationThreshold();

        if (penetrationValue >= 100.0F) {
            plateData.breakInstantly();
            float finalDamage = rawBulletDamage > 0 ? rawBulletDamage : incomingDamage;

            return new PenetrationResult(
                    true,
                    true,
                    true,
                    plateData.getMaxDurability(),
                    finalDamage,
                    1.0F,
                    0.0F
            );
        }

        if (Math.abs(penetrationValue - threshold) <= EQUALITY_TOLERANCE) {
            plateData.breakInstantly();

            return new PenetrationResult(
                    false,
                    false,
                    true,
                    0,
                    0.0F,
                    1.0F,
                    penetrationValue
            );
        }

        if (penetrationValue > threshold + EQUALITY_TOLERANCE) {
            plateData.breakInstantly();
            float finalDamage = rawBulletDamage > 0 ? rawBulletDamage : incomingDamage;

            return new PenetrationResult(
                    true,
                    true,
                    true,
                    plateData.getMaxDurability(),
                    finalDamage,
                    1.0F,
                    0.0F
            );
        }

        if (penetrationValue <= 0.0F) {
            int durabilityDamage = Math.max(1, Math.round(incomingDamage * 0.1F));
            plateData.applyDurabilityDamage(durabilityDamage);

            return new PenetrationResult(
                    false,
                    false,
                    plateData.isBroken(),
                    durabilityDamage,
                    0.0F,
                    0.0F,
                    0.0F
            );
        }

        float penetrationRatio = penetrationValue / threshold;
        float incrementRate;

        if (penetrationRatio >= 0.80F) {
            incrementRate = 0.08F;
        } else if (penetrationRatio >= 0.70F) {
            incrementRate = 0.06F;
        } else if (penetrationRatio >= 0.60F) {
            incrementRate = 0.05F;
        } else if (penetrationRatio >= 0.50F) {
            incrementRate = 0.04F;
        } else if (penetrationRatio >= 0.40F) {
            incrementRate = 0.03F;
        } else if (penetrationRatio >= 0.30F) {
            incrementRate = 0.025F;
        } else if (penetrationRatio >= 0.20F) {
            incrementRate = 0.02F;
        } else if (penetrationRatio >= 0.10F) {
            incrementRate = 0.015F;
        } else {
            incrementRate = 0.01F;
        }

        float accumulatedPenetration = plateData.getAccumulatedPenetration();
        if (accumulatedPenetration == 0.0F) {
            plateData.setHitCount(0);
        }

        plateData.incrementHitCount();
        int hitCount = plateData.getHitCount();

        float currentPenetration = penetrationValue * (1.0F + (hitCount - 1) * incrementRate);
        plateData.setAccumulatedPenetration(currentPenetration);

        float accumulatedRatio = Math.min(1.0F, currentPenetration / threshold);
        boolean shouldBreakPlate = currentPenetration >= threshold;

        int durabilityDamage = computeDurabilityDamage(plateData, incomingDamage, penetrationValue);
        plateData.applyDurabilityDamage(durabilityDamage);

        if (shouldBreakPlate && hitCount >= 2) {
            plateData.breakInstantly();
            plateData.resetAccumulatedPenetration();

            boolean isHighPenetrationBullet = penetrationValue > threshold + EQUALITY_TOLERANCE;

            float finalDamage;
            boolean penetrated;

            if (isHighPenetrationBullet) {
                penetrated = true;
                finalDamage = rawBulletDamage > 0 ? rawBulletDamage : incomingDamage;
            } else {
                penetrated = false;
                finalDamage = 0.0F;
            }

            return new PenetrationResult(
                    penetrated,
                    false,
                    true,
                    durabilityDamage,
                    finalDamage,
                    accumulatedRatio,
                    currentPenetration
            );
        }

        if (shouldBreakPlate && hitCount == 1) {
            return new PenetrationResult(
                    false,
                    false,
                    plateData.isBroken(),
                    durabilityDamage,
                    0.0F,
                    accumulatedRatio,
                    currentPenetration
            );
        }

        return new PenetrationResult(
                false,
                false,
                plateData.isBroken(),
                durabilityDamage,
                0.0F,
                accumulatedRatio,
                currentPenetration
        );
    }

    public int computeDurabilityDamage(PlateData plateData, float incomingDamage, float penetrationValue) {
        float penetrationRatio = penetrationValue / Math.max(1.0F, plateData.getPenetrationThreshold());

        float baseDamage;
        if (penetrationRatio >= 0.80F) {
            baseDamage = 5.0F;
        } else if (penetrationRatio >= 0.70F) {
            baseDamage = 4.0F;
        } else if (penetrationRatio >= 0.60F) {
            baseDamage = 3.0F;
        } else if (penetrationRatio >= 0.50F) {
            baseDamage = 3.0F;
        } else if (penetrationRatio >= 0.40F) {
            baseDamage = 2.0F;
        } else if (penetrationRatio >= 0.30F) {
            baseDamage = 2.0F;
        } else if (penetrationRatio >= 0.20F) {
            baseDamage = 2.0F;
        } else if (penetrationRatio >= 0.10F) {
            baseDamage = 2.0F;
        } else {
            baseDamage = 1.0F;
        }

        return Math.max(1, Math.round(baseDamage));
    }
}