package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ShieldConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class ListShieldCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("listshield")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    ShieldConfig config = FragmentationMod.getShieldConfig();
                    
                    if (config.getShields().isEmpty()) {
                        context.getSource().sendFailure(Component.literal("没有配置任何护盾！"));
                        return 0;
                    }
                    
                    StringBuilder message = new StringBuilder();
                    message.append("已配置的护盾:\n");
                    for (ShieldConfig.ShieldEntry entry : config.getShields()) {
                        message.append("  - ");
                        message.append(entry.getDisplayName());
                        message.append(
                            " (等级:" + entry.armorLevel
                                + ", 耐久:" + entry.maxDurability
                                + ", 跳弹系数:" + String.format(
                                    "%.2f",
                                    entry.ricochetCoefficient
                                )
                                + ", ID:" + entry.itemId + ")\n"
                        );
                    }
                    
                    context.getSource().sendSuccess(() -> Component.literal(message.toString()), true);
                    return 1;
                })
        );
    }
}
