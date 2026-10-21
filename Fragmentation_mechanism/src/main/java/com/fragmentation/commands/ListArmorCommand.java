package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ListArmorCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("listarmor")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    List<ArmorConfig.ArmorEntry> armors = FragmentationMod.getArmorConfig().getArmors();
                    
                    if (armors.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("没有配置任何护甲！"));
                        return 0;
                    }
                    
                    context.getSource().sendSuccess(() -> 
                        Component.literal("=== 已配置的护甲列表 (" + armors.size() + ") ==="), false);
                    
                    for (ArmorConfig.ArmorEntry armor : armors) {
                        String name = armor.getDisplayName();
                        context.getSource().sendSuccess(() -> 
                            Component.literal("- " + name + 
                                " | 等级:" + armor.armorLevel + 
                                " | 耐久:" + armor.maxDurability +
                                " | 跳弹系数:" + String.format(
                                    "%.2f",
                                    armor.ricochetCoefficient
                                )), false);
                    }
                    
                    return armors.size();
                })
        );
    }
}
