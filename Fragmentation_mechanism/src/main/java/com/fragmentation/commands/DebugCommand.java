package com.fragmentation.commands;

import com.fragmentation.util.PlayerStateManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class DebugCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fdebug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    boolean enabled = PlayerStateManager.toggleDebugMode(player);
                    
                    if (enabled) {
                        player.displayClientMessage(Component.literal(
                            "§6=== 碎裂机制调试模式已开启 ===§r\n" +
                            "§7受到伤害时将显示详细信息：§r\n" +
                            "§7- 原始伤害 / 最终伤害§r\n" +
                            "§7- 子弹等级 / 护甲等级§r\n" +
                            "§7- 是否穿透 / 跳弹§r\n" +
                            "§7- 各部位护甲损耗§r"
                        ), true);
                    } else {
                        player.displayClientMessage(Component.literal("§7调试模式已关闭§r"), true);
                    }
                    
                    return 1;
                })
        );
    }
}
