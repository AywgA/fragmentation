package com.fragmentation.nbt;

import com.fragmentation.data.PlateData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class PlateNbt {
    public static final String ROOT_KEY = "fragmentation_plate";
    private static final String LVL = "lvl";
    private static final String DUR = "dur";
    private static final String MAX = "max";
    private static final String ACC_PEN = "acc_pen";
    private static final String HIT_COUNT = "hit_count";
    private static final String BRK = "brk";

    private PlateNbt() {
    }

    public static boolean hasPlate(ItemStack chestStack) {
        if (chestStack.isEmpty()) {
            return false;
        }
        CompoundTag tag = chestStack.getTag();
        return tag != null && tag.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND);
    }

    public static PlateData read(ItemStack chestStack) {
        CompoundTag tag = chestStack.getTag();
        if (tag == null || !tag.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag p = tag.getCompound(ROOT_KEY);
        int lvl = p.getInt(LVL);
        int max = p.contains(MAX, CompoundTag.TAG_INT)
                ? Math.max(1, p.getInt(MAX))
                : lvl * 10;
        PlateData data = new PlateData(lvl, max);

        int hitCount = 0;
        float accPen = 0.0F;
        if (p.contains(HIT_COUNT, CompoundTag.TAG_INT)) {
            hitCount = p.getInt(HIT_COUNT);
        }
        if (p.contains(ACC_PEN, CompoundTag.TAG_FLOAT)) {
            accPen = p.getFloat(ACC_PEN);
        }
        data.restoreState(p.getInt(DUR), accPen, p.getBoolean(BRK), hitCount);

        return data;
    }

    public static void write(ItemStack chestStack, PlateData data) {
        if (chestStack.isEmpty() || data == null) {
            return;
        }
        CompoundTag root = chestStack.getOrCreateTag();
        CompoundTag p = new CompoundTag();
        p.putInt(LVL, data.getPlateLevel());
        p.putInt(DUR, data.getDurability());
        p.putInt(MAX, data.getMaxDurability());
        p.putFloat(ACC_PEN, data.getAccumulatedPenetration());
        p.putInt(HIT_COUNT, data.getHitCount());
        p.putBoolean(BRK, data.isBroken());
        root.put(ROOT_KEY, p);
    }

    public static void clear(ItemStack chestStack) {
        if (chestStack.isEmpty()) {
            return;
        }
        CompoundTag tag = chestStack.getTag();
        if (tag != null) {
            tag.remove(ROOT_KEY);
            if (tag.isEmpty()) {
                chestStack.setTag(null);
            }
        }
    }
}