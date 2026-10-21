package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.GunConfig;
import com.fragmentation.util.WindMeterManager;
import com.fragmentation.util.WorldCoverManager;
import com.fragmentation.util.WorldWindManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class ReloadConfigCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("freload")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    FragmentationMod.getArmorConfig().loadConfig();
                    FragmentationMod.getBulletConfig().loadConfig();
                    FragmentationMod.getPlateConfig().loadConfig();
                    FragmentationMod.getPenetrationConfig().loadConfig();
                    FragmentationMod.getArmorMaterialConfig().loadConfig();
                    FragmentationMod.getProtectionConfig().loadConfig();
                    GunConfig.reload();
                    WorldCoverManager.loadConfig();
                    WorldWindManager.loadConfig();
                    WindMeterManager.loadConfig();
                    context.getSource().sendSuccess(() -> 
                        Component.literal("所有配置文件已重新加载"), true);
                    return 1;
                })
        );
    }
}
