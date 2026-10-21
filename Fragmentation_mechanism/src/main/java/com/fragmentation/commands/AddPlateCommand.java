package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.PlateConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AddPlateCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("faddplate")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 6))
                    .then(Commands.argument("durability", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("material", StringArgumentType.string())
                            .executes(context -> {
                                int level = IntegerArgumentType.getInteger(context, "level");
                                int durability = IntegerArgumentType.getInteger(context, "durability");
                                String material = StringArgumentType.getString(context, "material").toLowerCase();
                                Player player = context.getSource().getPlayerOrException();
                                ItemStack heldItem = player.getMainHandItem();
                                
                                if (heldItem.isEmpty()) {
                                    context.getSource().sendFailure(Component.literal("你必须手持一个物品！"));
                                    return 0;
                                }
                                
                                PlateConfig config = FragmentationMod.getPlateConfig();
                                config.addPlate(heldItem, level, durability, material);
                                config.saveConfig();
                                
                                context.getSource().sendSuccess(() -> 
                                    Component.literal("防弹插板已添加:\n等级: " + level + "\n耐久: " + durability + "\n材料: " + material), true);
                                return 1;
                            })
                            .then(Commands.argument(
                                    "ricochetCoefficient",
                                    DoubleArgumentType.doubleArg(0.0D, 1.0D)
                                )
                                .executes(context -> {
                                    int level = IntegerArgumentType.getInteger(
                                        context,
                                        "level"
                                    );
                                    int durability = IntegerArgumentType.getInteger(
                                        context,
                                        "durability"
                                    );
                                    String material = StringArgumentType.getString(
                                        context,
                                        "material"
                                    ).toLowerCase();
                                    double ricochetCoefficient =
                                        DoubleArgumentType.getDouble(
                                            context,
                                            "ricochetCoefficient"
                                        );
                                    Player player =
                                        context.getSource().getPlayerOrException();
                                    ItemStack heldItem = player.getMainHandItem();

                                    if (heldItem.isEmpty()) {
                                        context.getSource().sendFailure(
                                            Component.literal("你必须手持一个物品！")
                                        );
                                        return 0;
                                    }

                                    PlateConfig config =
                                        FragmentationMod.getPlateConfig();
                                    config.addPlate(
                                        heldItem,
                                        level,
                                        durability,
                                        material,
                                        ricochetCoefficient
                                    );
                                    config.saveConfig();

                                    context.getSource().sendSuccess(
                                        () -> Component.literal(
                                            "防弹插板已添加:"
                                                + "\n等级: " + level
                                                + "\n耐久: " + durability
                                                + "\n材料: " + material
                                                + "\n跳弹系数: "
                                                + String.format(
                                                    "%.2f",
                                                    ricochetCoefficient
                                                )
                                        ),
                                        true
                                    );
                                    return 1;
                                })
                            )
                        )
                        .executes(context -> {
                            int level = IntegerArgumentType.getInteger(context, "level");
                            int durability = IntegerArgumentType.getInteger(context, "durability");
                            Player player = context.getSource().getPlayerOrException();
                            ItemStack heldItem = player.getMainHandItem();
                            
                            if (heldItem.isEmpty()) {
                                context.getSource().sendFailure(Component.literal("你必须手持一个物品！"));
                                return 0;
                            }
                            
                            PlateConfig config = FragmentationMod.getPlateConfig();
                            config.addPlate(heldItem, level, durability);
                            config.saveConfig();
                            
                            context.getSource().sendSuccess(() -> 
                                Component.literal("防弹插板已添加:\n等级: " + level + "\n耐久: " + durability + "\n材料: ceramic (默认)"), true);
                            return 1;
                        })
                    )
                )
        );
    }
}
