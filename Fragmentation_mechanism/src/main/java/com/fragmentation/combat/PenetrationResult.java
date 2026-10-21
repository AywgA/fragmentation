package com.fragmentation.combat;

public class PenetrationResult {
    private final boolean penetrated;
    private final boolean guaranteedPenetration;
    private final boolean plateBroken;
    private final int durabilityDamage;
    private final float finalPlayerDamage;
    private final float accumulatedPenetrationRatio;
    private final float accumulatedPenetration;

    public PenetrationResult(
            boolean penetrated,
            boolean guaranteedPenetration,
            boolean plateBroken,
            int durabilityDamage,
            float finalPlayerDamage,
            float accumulatedPenetrationRatio,
            float accumulatedPenetration
    ) {
        this.penetrated = penetrated;
        this.guaranteedPenetration = guaranteedPenetration;
        this.plateBroken = plateBroken;
        this.durabilityDamage = durabilityDamage;
        this.finalPlayerDamage = finalPlayerDamage;
        this.accumulatedPenetrationRatio = accumulatedPenetrationRatio;
        this.accumulatedPenetration = accumulatedPenetration;
    }

    public boolean isPenetrated() {
        return penetrated;
    }

    public boolean isGuaranteedPenetration() {
        return guaranteedPenetration;
    }

    public boolean isPlateBroken() {
        return plateBroken;
    }

    public int getDurabilityDamage() {
        return durabilityDamage;
    }

    public float getFinalPlayerDamage() {
        return finalPlayerDamage;
    }

    public float getAccumulatedPenetrationRatio() {
        return accumulatedPenetrationRatio;
    }

    public float getAccumulatedPenetration() {
        return accumulatedPenetration;
    }
}