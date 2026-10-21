package com.fragmentation.combat;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 单条枪械命中记录（死亡战报用）。
 *
 * 穿透分类 {@link #penetrationType}：
 * - PENETRATED 穿透：子弹穿透了护甲，伤害有衰减；
 * - OVER_PENETRATED 过穿：子弹完全穿过，伤害没有衰减（无护甲/护甲耐久0/未配置/不防护该部位）；
 * - NOT_PENETRATED 未穿透：子弹未穿透护甲。
 */
public class HitRecord {
    public static final String PENETRATED = "PENETRATED";
    public static final String OVER_PENETRATED = "OVER_PENETRATED";
    public static final String NOT_PENETRATED = "NOT_PENETRATED";
    /** 近战/空手（非枪械命中，单独着色显示）。 */
    public static final String MELEE = "MELEE";

    /** 命中部位（DetailedHitZone 枚举名：HEAD/FACE/CHEST/ABDOMEN/LEFT_ARM/RIGHT_ARM/LEFT_LEG/RIGHT_LEG）。 */
    public String zone;
    /** 子弹标识（配置 itemId / ammoId）。 */
    public String bulletId;
    /** 子弹穿甲等级。 */
    public float bulletPenLevel;
    /** 子弹基础伤害。 */
    public float bulletBaseDamage;
    /** 防护装备名称（无防护时为空串）。 */
    public String armorName;
    /** 防护等级（0 表示无防护）。 */
    public int armorLevel;
    /** 命中后防护装备剩余耐久。 */
    public int armorDurability;
    /** 防护装备最大耐久。 */
    public int armorMaxDurability;
    /** 穿透分类，见常量。 */
    public String penetrationType;
    /** 最终造成的伤害。 */
    public float finalDamage;
    /** 对手名称（受击记录中为攻击者名；造成记录中为受击者名）。 */
    public String opponentName;
    /** 命中发生时的服务端游戏 tick（用于一分钟时间窗与相对时间显示）。 */
    public long tick;
    /** 精确落点：人体图横向比例（0=最左，1=最右）。 */
    public float hitX;
    /** 精确落点：人体图纵向比例（0=脚底，1=头顶）。 */
    public float hitY;
    /** 是否为近战/空手命中（false=枪械/投射物）。 */
    public boolean melee;

    public HitRecord() {
    }

    public HitRecord(String zone, String bulletId, float bulletPenLevel, float bulletBaseDamage,
                     String armorName, int armorLevel, int armorDurability, int armorMaxDurability,
                     String penetrationType, float finalDamage, String opponentName, long tick) {
        this(zone, bulletId, bulletPenLevel, bulletBaseDamage, armorName, armorLevel,
            armorDurability, armorMaxDurability, penetrationType, finalDamage, opponentName,
            tick, 0.5F, 0.5F, false);
    }

    public HitRecord(String zone, String bulletId, float bulletPenLevel, float bulletBaseDamage,
                     String armorName, int armorLevel, int armorDurability, int armorMaxDurability,
                     String penetrationType, float finalDamage, String opponentName, long tick,
                     float hitX, float hitY, boolean melee) {
        this.zone = zone;
        this.bulletId = bulletId;
        this.bulletPenLevel = bulletPenLevel;
        this.bulletBaseDamage = bulletBaseDamage;
        this.armorName = armorName != null ? armorName : "";
        this.armorLevel = armorLevel;
        this.armorDurability = armorDurability;
        this.armorMaxDurability = armorMaxDurability;
        this.penetrationType = penetrationType;
        this.finalDamage = finalDamage;
        this.opponentName = opponentName != null ? opponentName : "";
        this.tick = tick;
        this.hitX = hitX;
        this.hitY = hitY;
        this.melee = melee;
    }

    public static void encode(HitRecord r, FriendlyByteBuf buf) {
        buf.writeUtf(str(r.zone));
        buf.writeUtf(str(r.bulletId));
        buf.writeFloat(r.bulletPenLevel);
        buf.writeFloat(r.bulletBaseDamage);
        buf.writeUtf(str(r.armorName));
        buf.writeVarInt(r.armorLevel);
        buf.writeVarInt(r.armorDurability);
        buf.writeVarInt(r.armorMaxDurability);
        buf.writeUtf(str(r.penetrationType));
        buf.writeFloat(r.finalDamage);
        buf.writeUtf(str(r.opponentName));
        buf.writeLong(r.tick);
        buf.writeFloat(r.hitX);
        buf.writeFloat(r.hitY);
        buf.writeBoolean(r.melee);
    }

    public static HitRecord decode(FriendlyByteBuf buf) {
        HitRecord r = new HitRecord();
        r.zone = buf.readUtf();
        r.bulletId = buf.readUtf();
        r.bulletPenLevel = buf.readFloat();
        r.bulletBaseDamage = buf.readFloat();
        r.armorName = buf.readUtf();
        r.armorLevel = buf.readVarInt();
        r.armorDurability = buf.readVarInt();
        r.armorMaxDurability = buf.readVarInt();
        r.penetrationType = buf.readUtf();
        r.finalDamage = buf.readFloat();
        r.opponentName = buf.readUtf();
        r.tick = buf.readLong();
        r.hitX = buf.readFloat();
        r.hitY = buf.readFloat();
        r.melee = buf.readBoolean();
        return r;
    }

    private static String str(String s) {
        return s != null ? s : "";
    }
}
