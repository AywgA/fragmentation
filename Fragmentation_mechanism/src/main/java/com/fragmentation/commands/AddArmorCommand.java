package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.util.DurabilityUtils;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AddArmorCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addarmor")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    ItemStack heldItem = player.getMainHandItem();
                    
                    if (heldItem.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("你必须手持一个物品！"));
                        return 0;
                    }
                    
                    ArmorConfig config = FragmentationMod.getArmorConfig();
                    ArmorConfig.ArmorEntry existing = config.getArmorForItem(heldItem);
                    
                    int defaultLevel = 1;
                    int defaultDurability = heldItem.getMaxDamage() > 0 ? heldItem.getMaxDamage() : 100;
                    
                    if (existing != null) {
                        defaultLevel = existing.armorLevel;
                        defaultDurability = existing.maxDurability;
                    }

                    int entriesBefore = config.getArmors().size();
                    config.addArmor(heldItem, defaultLevel, defaultDurability);
                    config.saveConfig();
                    // 同ID不同NBT变体（如transmog外观）会新建独立条目，而不是覆盖已有配置
                    boolean created = config.getArmors().size() > entriesBefore;
                    
                    int originalMax = heldItem.getItem().getMaxDamage();
                    if (originalMax > 0 && defaultDurability > 0) {
                        float ratio = (float) defaultDurability / (float) originalMax;
                        int currentDamage = heldItem.getDamageValue();
                        int newDamage = (int) Math.floor((float) currentDamage * ratio);
                        heldItem.setDamageValue(Math.min(newDamage, defaultDurability));
                    }
                    
                    // maxDurability 直接从配置读取，不需要设置 NBT 标签
                    
                    final int finalLevel = defaultLevel;
                    final int finalDurability = defaultDurability;
                    final boolean finalCreated = created;

                    if (!finalCreated) {
                        context.getSource().sendSuccess(() ->
                            Component.literal("已更新护甲: " + heldItem.getDisplayName().getString() +
                                " (等级:" + finalLevel + ", 耐久:" + finalDurability + ")\n" +
                                "使用 /fsetarmor <1-6> <耐久> 修改属性"), true);
                    } else if (existing != null) {
                        context.getSource().sendSuccess(() ->
                            Component.literal("该物品是同ID护甲的不同NBT变体，已新建独立护甲条目: " +
                                heldItem.getDisplayName().getString() +
                                " (等级:" + finalLevel + ", 耐久:" + finalDurability + ")\n" +
                                "使用 /fsetarmor <1-6> <耐久> 修改属性"), true);
                    } else {
                        context.getSource().sendSuccess(() ->
                            Component.literal("已添加护甲: " + heldItem.getDisplayName().getString() +
                                " (默认等级:" + finalLevel + ", 耐久:" + finalDurability + ")\n" +
                                "使用 /fsetarmor <1-6> <耐久> 修改属性"), true);
                    }
                    return 1;
                })
        );
    }
}