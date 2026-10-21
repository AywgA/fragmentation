package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class RemoveArmorCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("removearmor")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    ItemStack heldItem = player.getMainHandItem();
                    
                    if (heldItem.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("你必须手持一个护甲物品！"));
                        return 0;
                    }
                    
                    boolean success = FragmentationMod.getArmorConfig().removeArmor(heldItem);
                    FragmentationMod.getArmorConfig().saveConfig();
                    
                    if (success) {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("已移除护甲配置: " + heldItem.getDisplayName().getString()), true);
                        return 1;
                    } else {
                        context.getSource().sendFailure(Component.literal("未找到该护甲的配置！"));
                        return 0;
                    }
                })
        );
    }
}
