package com.fragmentation.combat;

import com.fragmentation.data.PlateData;

public class BodyZoneDamageCalculator {
    private final PenetrationCalculator penetrationCalculator;

    public BodyZoneDamageCalculator() {
        this(new PenetrationCalculator());
    }

    public BodyZoneDamageCalculator(PenetrationCalculator penetrationCalculator) {
        this.penetrationCalculator = penetrationCalculator;
    }

    public BodyPartDamageResult resolveDamage(
            HitZone hitZone,
            PlateData headPlate,
            PlateData torsoPlate,
            float incomingDamage,
            float penetrationValue
    ) {
        if (hitZone == null) {
            return new BodyPartDamageResult(HitZone.TORSO, false, true, false, incomingDamage);
        }

        if (hitZone.isHead()) {
            return resolveHeadDamage(headPlate, incomingDamage, penetrationValue);
        }

        if (hitZone.isTorso()) {
            return resolveTorsoDamage(hitZone, torsoPlate, incomingDamage, penetrationValue);
        }

        return resolveLimbDamage(hitZone, incomingDamage);
    }

    private BodyPartDamageResult resolveHeadDamage(PlateData headPlate, float incomingDamage, float penetrationValue) {
        float zoneMultiplier = getZoneDamageMultiplier(HitZone.HEAD);
        if (headPlate == null || !headPlate.canProtect()) {
            return new BodyPartDamageResult(HitZone.HEAD, false, true, false, incomingDamage * zoneMultiplier);
        }

        PenetrationResult result = penetrationCalculator.resolveHit(headPlate, incomingDamage, penetrationValue);
        if (result.isPenetrated()) {
            return new BodyPartDamageResult(HitZone.HEAD, false, true, result.isPlateBroken(), incomingDamage * zoneMultiplier);
        }

        return new BodyPartDamageResult(HitZone.HEAD, true, false, result.isPlateBroken(), 0.0F);
    }

    private BodyPartDamageResult resolveTorsoDamage(HitZone hitZone, PlateData torsoPlate, float incomingDamage, float penetrationValue) {
        // hitZone 可能是 TORSO（胸部，系数 1.0）或 ABDOMEN（腹部，系数 0.5）
        float zoneMultiplier = getZoneDamageMultiplier(hitZone);
        if (torsoPlate == null || !torsoPlate.canProtect()) {
            return new BodyPartDamageResult(hitZone, false, true, false, incomingDamage * zoneMultiplier);
        }

        PenetrationResult result = penetrationCalculator.resolveHit(torsoPlate, incomingDamage, penetrationValue);
        if (result.isPenetrated()) {
            return new BodyPartDamageResult(hitZone, false, true, result.isPlateBroken(), incomingDamage * zoneMultiplier);
        }

        return new BodyPartDamageResult(hitZone, true, false, result.isPlateBroken(), 0.0F);
    }

    private BodyPartDamageResult resolveLimbDamage(HitZone hitZone, float incomingDamage) {
        float zoneMultiplier = getZoneDamageMultiplier(hitZone);
        return new BodyPartDamageResult(hitZone, false, true, false, incomingDamage * zoneMultiplier);
    }

    /**
     * 各部位伤害系数（与 DetailedHitZone.getDamageMultiplier 保持一致）：
     * 头部/胸部 1.0，腹部 0.5，腿部 0.45，手臂 0.35。
     */
    private float getZoneDamageMultiplier(HitZone zone) {
        return switch (zone) {
            case HEAD, TORSO -> 1.0F;
            case ABDOMEN -> 0.50F;
            case LEFT_LEG, RIGHT_LEG -> 0.45F;
            default -> 0.35F; // LEFT_ARM, RIGHT_ARM
        };
    }
}