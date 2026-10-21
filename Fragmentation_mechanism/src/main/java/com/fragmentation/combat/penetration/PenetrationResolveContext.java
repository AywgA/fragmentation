package com.fragmentation.combat.penetration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class PenetrationResolveContext {
    private final float incomingDamage;
    private final Float declaredPenetration;
    private final Float rawBulletDamage;
    private final Map<String, Object> attachments;

    private PenetrationResolveContext(float incomingDamage, Float declaredPenetration, Float rawBulletDamage, Map<String, Object> attachments) {
        this.incomingDamage = incomingDamage;
        this.declaredPenetration = declaredPenetration;
        this.rawBulletDamage = rawBulletDamage;
        this.attachments = attachments;
    }

    public static Builder builder(float incomingDamage) {
        return new Builder(incomingDamage);
    }

    public float getIncomingDamage() {
        return incomingDamage;
    }

    public Optional<Float> getDeclaredPenetration() {
        return Optional.ofNullable(declaredPenetration);
    }

    public Optional<Float> getRawBulletDamage() {
        return Optional.ofNullable(rawBulletDamage);
    }

    public Optional<Object> getAttachment(String key) {
        return Optional.ofNullable(attachments.get(key));
    }

    public Map<String, Object> getAttachmentsView() {
        return Collections.unmodifiableMap(attachments);
    }

    public static final class Builder {
        private final float incomingDamage;
        private Float declaredPenetration;
        private Float rawBulletDamage;
        private final Map<String, Object> attachments = new HashMap<>();

        private Builder(float incomingDamage) {
            this.incomingDamage = incomingDamage;
        }

        public Builder declaredPenetration(float value) {
            this.declaredPenetration = value;
            return this;
        }

        public Builder attach(String key, Object value) {
            if (key != null && value != null) {
                attachments.put(key, value);
            }
            return this;
        }

        public Builder rawBulletDamage(float value) {
            this.rawBulletDamage = value;
            return this;
        }

        public PenetrationResolveContext build() {
            return new PenetrationResolveContext(
                    incomingDamage,
                    declaredPenetration,
                    rawBulletDamage,
                    Map.copyOf(attachments)
            );
        }
    }
}