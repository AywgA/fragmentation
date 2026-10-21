package com.fragmentation.combat.penetration;

import java.util.Optional;

public interface PenetrationExtractor {
    Optional<Float> tryExtract(PenetrationResolveContext context);
}