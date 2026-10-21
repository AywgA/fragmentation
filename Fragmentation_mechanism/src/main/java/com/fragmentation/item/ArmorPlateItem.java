package com.fragmentation.item;

import com.fragmentation.data.PlateData;
import com.fragmentation.nbt.PlateNbt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

public class ArmorPlateItem extends Item {
    private final int plateLevel;

    public ArmorPlateItem(int plateLevel, Properties properties) {
        super(properties);
        this.plateLevel = PlateData.clampLevel(plateLevel);
    }

    public int getPlateLevel() {
        return plateLevel;
    }

    public static int barCapacityForLevel(int level) {
        return switch (level) {
            case 1 -> 25;
            case 2 -> 35;
            case 3 -> 50;
            case 4 -> 65;
            case 5 -> 85;
            case 6 -> 100;
            case 7 -> 120;
            default -> 50;
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        PlateData data = PlateNbt.read(stack);
        if (data != null) {
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_level", data.getPlateLevel())
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_durability", 
                    data.getDurability(), data.getMaxDurability())
                    .withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_threshold", 
                    data.getPenetrationThreshold())
                    .withStyle(ChatFormatting.BLUE));
        } else {
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_level", plateLevel)
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_durability", 
                    barCapacityForLevel(plateLevel), barCapacityForLevel(plateLevel))
                    .withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.fragmentation.plate_threshold", 
                    plateLevel * 10)
                    .withStyle(ChatFormatting.BLUE));
        }
    }
}