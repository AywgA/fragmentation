package com.fragmentation.combat;

import com.fragmentation.combat.penetration.PenetrationResolveContext;
import com.fragmentation.combat.penetration.PenetrationResolver;
import com.fragmentation.data.PlateData;

public final class ArmorPlateCombatHandler {
    private final PenetrationCalculator penetrationCalculator;
    private final PenetrationResolver penetrationResolver;

    public ArmorPlateCombatHandler() {
        this(new PenetrationCalculator(), PenetrationResolver.taczCompatible());
    }

    public ArmorPlateCombatHandler(PenetrationCalculator calculator, PenetrationResolver resolver) {
        this.penetrationCalculator = calculator;
        this.penetrationResolver = resolver;
    }

    public PenetrationResult handleIncomingHit(PlateData plateData, float incomingDamage, float penetrationValue) {
        return penetrationCalculator.resolveHit(plateData, incomingDamage, penetrationValue);
    }

    public PenetrationResult handleIncomingHit(PlateData plateData, float incomingDamage, float penetrationValue, float rawBulletDamage) {
        return penetrationCalculator.resolveHit(plateData, incomingDamage, penetrationValue, rawBulletDamage);
    }

    public float getPenetrationResolver() {
        return 0;
    }

    public float resolvePenetration(PenetrationResolveContext context) {
        return penetrationResolver.resolve(context);
    }
}