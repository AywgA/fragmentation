package com.fragmentation.combat;

public class BodyPartDamageResult {
    private final HitZone hitZone;
    private final boolean blockedByPlate;
    private final boolean damageApplied;
    private final boolean plateBroken;
    private final float finalDamage;

    public BodyPartDamageResult(HitZone hitZone, boolean blockedByPlate, boolean damageApplied, boolean plateBroken, float finalDamage) {
        this.hitZone = hitZone;
        this.blockedByPlate = blockedByPlate;
        this.damageApplied = damageApplied;
        this.plateBroken = plateBroken;
        this.finalDamage = finalDamage;
    }

    public HitZone getHitZone() {
        return hitZone;
    }

    public boolean isBlockedByPlate() {
        return blockedByPlate;
    }

    public boolean isDamageApplied() {
        return damageApplied;
    }

    public boolean isPlateBroken() {
        return plateBroken;
    }

    public float getFinalDamage() {
        return finalDamage;
    }
}