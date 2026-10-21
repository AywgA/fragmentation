package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class AddBulletByAmmoCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addbulletbyammo")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("ammoId", StringArgumentType.greedyString())
                    .executes(context -> {
                        String ammoId = StringArgumentType.getString(context, "ammoId");
                        return addBullet(context.getSource(), ammoId, 1, 5.0);
                    })
                    .then(Commands.argument("penLevel", IntegerArgumentType.integer(1, 110))
                        .executes(context -> {
                            String ammoId = StringArgumentType.getString(context, "ammoId");
                            int penLevel = IntegerArgumentType.getInteger(context, "penLevel");
                            return addBullet(context.getSource(), ammoId, penLevel, 5.0);
                        })
                        .then(Commands.argument("armorDamage", DoubleArgumentType.doubleArg(0.0, 1000.0))
                            .executes(context -> {
                                String ammoId = StringArgumentType.getString(context, "ammoId");
                                int penLevel = IntegerArgumentType.getInteger(context, "penLevel");
                                double armorDamage = DoubleArgumentType.getDouble(context, "armorDamage");
                                return addBullet(context.getSource(), ammoId, penLevel, armorDamage);
                            })
                        )
                    )
                )
        );
    }
    
    private static int addBullet(CommandSourceStack source, String ammoId, int penLevel, double armorDamage) {
        BulletConfig config = FragmentationMod.getBulletConfig();
        
        if (config.getBulletByAmmoId(ammoId) != null) {
            source.sendFailure(Component.literal("该弹药ID已配置：" + ammoId));
            return 0;
        }
        
        boolean success = config.addBulletByAmmoId(ammoId, penLevel, armorDamage);
        if (!success) {
            source.sendFailure(Component.literal("添加失败！"));
            return 0;
        }
        
        config.saveConfig();
        
        source.sendSuccess(() -> 
            Component.literal("已添加子弹配置\n弹药ID: " + ammoId + "\n穿深等级: " + penLevel + " / 110\n对护甲基础伤害: " + armorDamage), true);
        return 1;
    }
}