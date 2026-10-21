package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class AddBulletCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addbullet")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    ItemStack heldItem = player.getMainHandItem();
                    
                    if (heldItem.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("你必须手持一个物品！"));
                        return 0;
                    }

                    if (heldItem.getItem() instanceof net.minecraft.world.item.ArmorItem) {
                        context.getSource().sendFailure(Component.literal(
                            "手持的是穿戴装备（护甲/弹挂等），不能配置为子弹！"));
                        return 0;
                    }
                    
                    BulletConfig config = FragmentationMod.getBulletConfig();
                    BulletConfig.BulletEntry existing = config.getBulletForItem(heldItem);
                    
                    float defaultPenLevel = 1.0f;
                    double defaultArmorDamage = 5.0;
                    
                    if (existing != null) {
                        defaultPenLevel = existing.penetrationLevel;
                        defaultArmorDamage = existing.armorDamage;
                    }
                    
                    boolean success = config.addBullet(heldItem, defaultPenLevel, defaultArmorDamage);
                    if (!success) {
                        context.getSource().sendFailure(Component.literal("添加失败！"));
                        return 0;
                    }
                    
                    config.saveConfig();
                    
                    final float finalPenLevel = defaultPenLevel;
                    final double finalArmorDamage = defaultArmorDamage;
                    
                    if (existing != null) {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("已更新子弹: " + heldItem.getDisplayName().getString() + 
                                " (穿甲等级:" + finalPenLevel + 
                                ", 护甲基础伤害:" + finalArmorDamage + ")\n" +
                                "使用 /fsetbullet <1-110> <伤害> 修改属性"), true);
                    } else {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("已添加子弹: " + heldItem.getDisplayName().getString() + 
                                " (默认穿甲等级:" + finalPenLevel + 
                                ", 护甲基础伤害:" + finalArmorDamage + ")\n" +
                                "使用 /fsetbullet <1-110> <伤害> 修改属性"), true);
                    }
                    return 1;
                })
        );
    }
}