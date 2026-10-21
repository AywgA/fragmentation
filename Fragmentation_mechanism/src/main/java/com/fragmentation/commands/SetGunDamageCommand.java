package com.fragmentation.commands;

import com.fragmentation.config.GunConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public class SetGunDamageCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetgundamage")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("damage", FloatArgumentType.floatArg(0))
                    .executes(context -> {
                        float damage = FloatArgumentType.getFloat(context, "damage");
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        
                        if (heldItem.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("你必须手持一把枪械！"));
                            return 0;
                        }
                        
                        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(heldItem.getItem());
                        if (itemId == null) {
                            context.getSource().sendFailure(Component.literal("无法获取物品ID！"));
                            return 0;
                        }
                        
                        String gunId = itemId.toString();
                        GunConfig.getInstance().setBaseDamageOverride(gunId, damage);
                        
                        context.getSource().sendSuccess(() -> 
                            Component.literal("枪械基础伤害已设置:\n枪械: " + heldItem.getDisplayName().getString() + 
                                "\nID: " + gunId + 
                                "\n基础伤害: " + String.format("%.1f", damage)), true);
                        return 1;
                    })
                )
                .then(Commands.literal("reset")
                    .executes(context -> {
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        
                        if (heldItem.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("你必须手持一把枪械！"));
                            return 0;
                        }
                        
                        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(heldItem.getItem());
                        if (itemId == null) {
                            context.getSource().sendFailure(Component.literal("无法获取物品ID！"));
                            return 0;
                        }
                        
                        String gunId = itemId.toString();
                        GunConfig.getInstance().setBaseDamageOverride(gunId, null);
                        
                        context.getSource().sendSuccess(() -> 
                            Component.literal("枪械基础伤害已重置:\n枪械: " + heldItem.getDisplayName().getString() + 
                                "\nID: " + gunId + 
                                "\n现在使用 TACZ 原版伤害"), true);
                        return 1;
                    })
                )
        );
    }
}
