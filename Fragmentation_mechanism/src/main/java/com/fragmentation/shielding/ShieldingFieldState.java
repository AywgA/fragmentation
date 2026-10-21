package com.fragmentation.shielding;

import net.minecraft.nbt.CompoundTag;

/**
 * 屏蔽场发生器（物品）的三层状态。
 * 状态存储在物品 NBT 中，随物品移动而移动，每个发生器互相独立。
 * 时间单位一律使用游戏刻（level.getGameTime()）。
 */
public class ShieldingFieldState {

    public enum Phase {
        /** 三层完好，正常吸收 */
        ACTIVE,
        /** 主层/次层已碎，负层过载（容量 ×overloadCapacityMultiplier） */
        OVERLOAD,
        /** 全部失效，冷却中（子弹正常伤害） */
        FAILED,
        /** 冷却后重建中（仅主层吸收） */
        RECOVERING
    }

    /** 负层（最内）当前负载，焦耳 */
    public double negativeLoad = 0.0D;
    /** 主层（中）当前负载，焦耳 */
    public double mainLoad = 0.0D;
    /** 次层（最外）完整度，1.0 = 完好，0.0 = 碎除 */
    public double secondaryIntegrity = 1.0D;

    public Phase phase = Phase.ACTIVE;

    /** 当前阶段结束刻（OVERLOAD/FAILED 用） */
    public long phaseEndTick = -1L;
    /** 最后一次被命中的游戏刻 */
    public long lastHitTick = -99999L;

    /** 写入 NBT 时的有效容量（过载时负层容量 × 倍率），供显示使用 */
    public double effectiveNegativeCap = 0.0D;
    public double effectiveMainCap = 0.0D;

    public ShieldingFieldState() {
    }

    public void reset() {
        negativeLoad = 0.0D;
        mainLoad = 0.0D;
        secondaryIntegrity = 1.0D;
        phase = Phase.ACTIVE;
        phaseEndTick = -1L;
        lastHitTick = -99999L;
        effectiveNegativeCap = 0.0D;
        effectiveMainCap = 0.0D;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("phase", phase.ordinal());
        tag.putDouble("negativeLoad", negativeLoad);
        tag.putDouble("mainLoad", mainLoad);
        tag.putDouble("secondaryIntegrity", secondaryIntegrity);
        tag.putLong("phaseEndTick", phaseEndTick);
        tag.putLong("lastHitTick", lastHitTick);
        tag.putDouble("effectiveNegativeCap", effectiveNegativeCap);
        tag.putDouble("effectiveMainCap", effectiveMainCap);
        return tag;
    }

    public static ShieldingFieldState load(CompoundTag tag) {
        ShieldingFieldState state = new ShieldingFieldState();
        if (tag == null) return state;
        int ordinal = tag.getInt("phase");
        Phase[] phases = Phase.values();
        state.phase = (ordinal >= 0 && ordinal < phases.length) ? phases[ordinal] : Phase.ACTIVE;
        state.negativeLoad = Math.max(0.0D, tag.getDouble("negativeLoad"));
        state.mainLoad = Math.max(0.0D, tag.getDouble("mainLoad"));
        state.secondaryIntegrity = Math.max(0.0D, Math.min(1.0D, tag.getDouble("secondaryIntegrity")));
        state.phaseEndTick = tag.getLong("phaseEndTick");
        state.lastHitTick = tag.getLong("lastHitTick");
        state.effectiveNegativeCap = Math.max(0.0D, tag.getDouble("effectiveNegativeCap"));
        state.effectiveMainCap = Math.max(0.0D, tag.getDouble("effectiveMainCap"));
        return state;
    }
}
