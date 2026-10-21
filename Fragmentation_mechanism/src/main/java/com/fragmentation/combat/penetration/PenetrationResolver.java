package com.fragmentation.combat.penetration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class PenetrationResolver {
    private final List<PenetrationExtractor> extractors;
    private final float defaultPenetration;

    public PenetrationResolver(List<PenetrationExtractor> extractors, float defaultPenetration) {
        this.extractors = List.copyOf(new ArrayList<>(extractors));
        this.defaultPenetration = defaultPenetration;
    }

    public static PenetrationResolver modCompatibleDefault() {
        return new PenetrationResolver(
                List.of(),
                0.0F
        );
    }

    public static PenetrationResolver taczCompatible() {
        List<PenetrationExtractor> extractors = new ArrayList<>();
        
        if (ArcanaPenetrationExtractor.isArcanaAvailable()) {
            extractors.add(new ArcanaPenetrationExtractor());
        }
        
        extractors.add(new TaczPenetrationExtractor());
        
        return new PenetrationResolver(extractors, 5.0F);
    }

    public List<PenetrationExtractor> getExtractorsView() {
        return Collections.unmodifiableList(extractors);
    }

    public float getDefaultPenetration() {
        return defaultPenetration;
    }

    public float resolve(PenetrationResolveContext context) {
        Optional<Float> declared = context.getDeclaredPenetration();
        if (declared.isPresent()) {
            return declared.get();
        }
        
        for (PenetrationExtractor extractor : extractors) {
            Optional<Float> v = extractor.tryExtract(context);
            if (v.isPresent()) {
                return v.get();
            }
        }
        
        return defaultPenetration;
    }
}