package com.fragmentation.data;

public class PlateData {
    private final int plateLevel;
    private final int maxDurability;
    private int durability;
    private float accumulatedPenetration;
    private int hitCount;
    private boolean broken;

    public PlateData(int plateLevel, int maxDurability) {
        this.plateLevel = clampLevel(plateLevel);
        this.maxDurability = Math.max(1, maxDurability);
        this.durability = this.maxDurability;
        this.accumulatedPenetration = 0.0F;
        this.hitCount = 0;
        this.broken = false;
    }

    public int getPlateLevel() {
        return plateLevel;
    }

    public int getMaxDurability() {
        return maxDurability;
    }

    public int getDurability() {
        return durability;
    }

    public float getAccumulatedPenetration() {
        return accumulatedPenetration;
    }

    public void setAccumulatedPenetration(float value) {
        this.accumulatedPenetration = Math.max(0.0F, value);
    }

    public void incrementHitCount() {
        this.hitCount++;
    }

    public int getHitCount() {
        return hitCount;
    }

    public void setHitCount(int count) {
        this.hitCount = Math.max(0, count);
    }

    public void resetAccumulatedPenetration() {
        this.accumulatedPenetration = 0.0F;
        this.hitCount = 0;
    }

    public boolean isBroken() {
        return broken || durability <= 0;
    }

    public void setBroken(boolean broken) {
        this.broken = broken;
    }

    public void breakInstantly() {
        this.durability = 0;
        this.broken = true;
        this.accumulatedPenetration = 0.0F;
        this.hitCount = 0;
    }

    public void applyDurabilityDamage(int damage) {
        this.durability = Math.max(0, this.durability - damage);
        if (this.durability <= 0) {
            this.broken = true;
        }
    }

    public void repairDurability(int amount) {
        int oldDurability = this.durability;
        this.durability = Math.min(maxDurability, this.durability + amount);
        
        if (this.durability == maxDurability && oldDurability < maxDurability) {
            this.hitCount = 0;
            this.accumulatedPenetration = 0.0F;
        }
        
        if (this.durability > 0) {
            this.broken = false;
        }
    }

    public boolean canProtect() {
        return !broken && durability > 0;
    }

    public int getPenetrationThreshold() {
        return plateLevel * 10;
    }

    public float getDurabilityRatio() {
        return Math.max(0.0F, (float) durability / (float) maxDurability);
    }

    public void restoreFull() {
        durability = maxDurability;
        broken = false;
        accumulatedPenetration = 0.0F;
        hitCount = 0;
    }

    public boolean isLowDurability() {
        return durability < (maxDurability * 0.25);
    }

    public static int clampLevel(int level) {
        return Math.max(1, Math.min(7, level));
    }

    public void restoreState(int durability, float accumulatedPenetration, boolean brokenFlag, int hitCount) {
        this.durability = Math.max(0, Math.min(maxDurability, durability));
        this.accumulatedPenetration = Math.max(0.0F, accumulatedPenetration);
        this.broken = brokenFlag || this.durability <= 0;
        
        if (this.broken || this.durability <= 0) {
            this.hitCount = 0;
            this.accumulatedPenetration = 0.0F;
        } else if (this.durability == maxDurability) {
            this.hitCount = 0;
            this.accumulatedPenetration = 0.0F;
        } else {
            this.hitCount = Math.max(0, hitCount);
        }
    }
}