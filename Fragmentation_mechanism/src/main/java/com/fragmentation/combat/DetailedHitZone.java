package com.fragmentation.combat;

import net.minecraft.world.entity.EquipmentSlot;

public enum DetailedHitZone {
    HEAD(EquipmentSlot.HEAD, "头部"),
    FACE(EquipmentSlot.HEAD, "面部"),
    CHEST(EquipmentSlot.CHEST, "胸部"),
    ABDOMEN(EquipmentSlot.CHEST, "腹部"),
    LEFT_ARM(EquipmentSlot.CHEST, "左臂"),
    RIGHT_ARM(EquipmentSlot.CHEST, "右臂"),
    LEFT_LEG(EquipmentSlot.LEGS, "左腿"),
    RIGHT_LEG(EquipmentSlot.LEGS, "右腿");

    private final EquipmentSlot equipmentSlot;
    private final String displayName;

    DetailedHitZone(EquipmentSlot equipmentSlot, String displayName) {
        this.equipmentSlot = equipmentSlot;
        this.displayName = displayName;
    }

    public EquipmentSlot getEquipmentSlot() {
        return equipmentSlot;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isLimb() {
        return isArm() || isLeg();
    }

    public boolean isHead() {
        return this == HEAD;
    }

    public boolean isFace() {
        return this == FACE;
    }

    public boolean isAbdomen() {
        return this == ABDOMEN;
    }

    public boolean isArm() {
        return this == LEFT_ARM || this == RIGHT_ARM;
    }

    public boolean isLeg() {
        return this == LEFT_LEG || this == RIGHT_LEG;
    }

    public float getDamageMultiplier() {
        return switch (this) {
            case HEAD, FACE, CHEST -> 1.0f;
            case ABDOMEN -> 0.50f;
            case LEFT_LEG, RIGHT_LEG -> 0.45f;
            default -> 0.35f;
        };
    }

    public DetailedHitZone getOpposite() {
        return switch (this) {
            case LEFT_ARM -> RIGHT_ARM;
            case RIGHT_ARM -> LEFT_ARM;
            case LEFT_LEG -> RIGHT_LEG;
            case RIGHT_LEG -> LEFT_LEG;
            default -> this;
        };
    }

    public static DetailedHitZone[] getAllZones() {
        return values();
    }

    /** 配置文件中使用的稳定名称（下划线小写）。 */
    public String configName() {
        return switch (this) {
            case HEAD -> "head";
            case FACE -> "face";
            case CHEST -> "chest";
            case ABDOMEN -> "abdomen";
            case LEFT_ARM -> "left_arm";
            case RIGHT_ARM -> "right_arm";
            case LEFT_LEG -> "left_leg";
            case RIGHT_LEG -> "right_leg";
        };
    }

    /**
     * 解析配置中的部位名称。容错：忽略大小写、下划线、连字符与空格，
     * 如 "left_arm"、"leftarm"、"Left Arm" 均解析为左臂。
     */
    public static DetailedHitZone fromConfigName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase()
            .replace("_", "").replace("-", "").replace(" ", "");
        return switch (s) {
            case "head" -> HEAD;
            case "face" -> FACE;
            case "chest", "thorax" -> CHEST;
            case "abdomen", "stomach" -> ABDOMEN;
            case "leftarm" -> LEFT_ARM;
            case "rightarm" -> RIGHT_ARM;
            case "leftleg" -> LEFT_LEG;
            case "rightleg" -> RIGHT_LEG;
            default -> null;
        };
    }
}
