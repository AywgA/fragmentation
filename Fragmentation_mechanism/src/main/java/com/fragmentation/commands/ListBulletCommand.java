package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ListBulletCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("listbullet")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    List<BulletConfig.BulletEntry> bullets = FragmentationMod.getBulletConfig().getBullets();
                    
                    if (bullets.isEmpty()) {
                        context.getSource().sendFailure(Component.literal("没有配置任何子弹！"));
                        return 0;
                    }
                    
                    context.getSource().sendSuccess(() -> 
                        Component.literal("=== 已配置的子弹列表 (" + bullets.size() + ") ==="), false);
                    
                    for (BulletConfig.BulletEntry bullet : bullets) {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("- " + bullet.getDisplayName() + 
                                " | 穿甲等级:" + bullet.penetrationLevel + 
                                " | 护甲伤害:" + bullet.armorDamage +
                                " | 重量:" + String.format("%.3fg", bullet.bulletMass * 1000.0D) +
                                " | 跳弹率:" + String.format("%.2f%%", bullet.ricochetRate * 100.0D) +
                                " | 碎弹率:" + String.format("%.2f%%", bullet.fragmentationRate * 100.0D) +
                                (" | ID:" + bullet.itemId)), false);
                    }
                    
                    return bullets.size();
                })
        );
    }
}
