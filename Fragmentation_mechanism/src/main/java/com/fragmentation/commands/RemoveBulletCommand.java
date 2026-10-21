package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RemoveBulletCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("removebullet")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("ammoId", StringArgumentType.greedyString())
                    .executes(context -> {
                        String ammoId = StringArgumentType.getString(context, "ammoId");
                        boolean success = FragmentationMod.getBulletConfig().removeBullet(ammoId);
                        FragmentationMod.getBulletConfig().saveConfig();
                        
                        if (success) {
                            context.getSource().sendSuccess(() -> 
                                Component.literal("已移除子弹配置: " + ammoId), true);
                            return 1;
                        } else {
                            context.getSource().sendFailure(Component.literal("未找到该子弹的配置！"));
                            return 0;
                        }
                    })
                )
        );
    }
}