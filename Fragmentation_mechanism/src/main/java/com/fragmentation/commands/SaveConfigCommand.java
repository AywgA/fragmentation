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

public class SaveConfigCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsave")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    FragmentationMod.getArmorConfig().saveConfig();
                    FragmentationMod.getBulletConfig().saveConfig();
                    FragmentationMod.getPlateConfig().saveConfig();
                    FragmentationMod.getPenetrationConfig().saveConfig();
                    FragmentationMod.getArmorMaterialConfig().saveConfig();
                    FragmentationMod.getProtectionConfig().saveConfig();
                    GunConfig.getInstance().saveConfig();
                    WorldCoverManager.saveConfig();
                    WorldWindManager.saveConfig();
                    WindMeterManager.saveConfig();
                    context.getSource().sendSuccess(() -> 
                        Component.literal("所有配置文件已保存！"), true);
                    return 1;
                })
        );
    }
}
