package com.fragmentation.combat;

public enum HitZone {
    HEAD,
    TORSO,
    ABDOMEN,
    LEFT_ARM,
    RIGHT_ARM,
    LEFT_LEG,
    RIGHT_LEG;

    public boolean isHead() {
        return this == HEAD;
    }

    public boolean isTorso() {
        return this == TORSO || this == ABDOMEN;
    }

    public boolean isAbdomen() {
        return this == ABDOMEN;
    }

    public boolean isLimb() {
        return this == LEFT_ARM || this == RIGHT_ARM || this == LEFT_LEG || this == RIGHT_LEG;
    }
}
