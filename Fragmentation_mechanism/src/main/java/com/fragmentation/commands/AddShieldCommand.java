package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ShieldConfig;
import com.fragmentation.util.DurabilityUtils;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AddShieldCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addshield")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    ItemStack heldItem = player.getMainHandItem();
                    
                    if (heldItem.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("你必须手持一个物品！"));
                        return 0;
                    }
                    
                    ShieldConfig config = FragmentationMod.getShieldConfig();
                    ShieldConfig.ShieldEntry existing = config.getShieldForItem(heldItem);
                    
                    int defaultLevel = 1;
                    int defaultDurability = heldItem.getMaxDamage() > 0 ? heldItem.getMaxDamage() : 100;
                    
                    if (existing != null) {
                        defaultLevel = existing.armorLevel;
                        defaultDurability = existing.maxDurability;
                    }
                    
                    config.addShield(heldItem, defaultLevel, defaultDurability);
                    config.saveConfig();
                    
                    int originalMax = heldItem.getItem().getMaxDamage();
                    int currentDurability;
                    if (originalMax > 0) {
                        float ratio = (float) defaultDurability / (float) originalMax;
                        int currentDamage = heldItem.getDamageValue();
                        currentDurability = Math.max(1, (int) Math.floor((float) (originalMax - currentDamage) * ratio));
                    } else {
                        currentDurability = defaultDurability;
                    }
                    
                    DurabilityUtils.setCurrentDurability(heldItem, currentDurability);
                    
                    if (player.containerMenu != null) {
                        player.containerMenu.broadcastChanges();
                    }
                    
                    final int finalLevel = defaultLevel;
                    final int finalDurability = defaultDurability;
                    
                    if (existing != null) {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("已更新护盾: " + heldItem.getDisplayName().getString() + 
                                " (等级:" + finalLevel + ", 耐久:" + finalDurability + ")\n" +
                                "使用 /fsetshield <1-11> <耐久> 修改属性"), true);
                    } else {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("已添加护盾: " + heldItem.getDisplayName().getString() + 
                                " (默认等级:" + finalLevel + ", 耐久:" + finalDurability + ")\n" +
                                "使用 /fsetshield <1-11> <耐久> 修改属性"), true);
                    }
                    return 1;
                })
        );
    }
}