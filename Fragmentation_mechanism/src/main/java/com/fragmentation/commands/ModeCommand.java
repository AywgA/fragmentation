package com.fragmentation.commands;

import com.fragmentation.util.PlayerStateManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class ModeCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fmode")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("mode", StringArgumentType.string())
                    .executes(context -> {
                        String modeStr = StringArgumentType.getString(context, "mode").toLowerCase();
                        Player player = context.getSource().getPlayerOrException();
                        
                        PlayerStateManager.AddMode mode;
                        switch (modeStr) {
                            case "armor":
                            case "护甲":
                                mode = PlayerStateManager.AddMode.ARMOR;
                                player.displayClientMessage(Component.literal("§a已切换到护甲添加模式§r\n蹲下手持物品右键即可添加为护甲"), true);
                                break;
                            case "bullet":
                            case "子弹":
                                mode = PlayerStateManager.AddMode.BULLET;
                                player.displayClientMessage(Component.literal("§c已切换到子弹添加模式§r\n蹲下手持物品右键即可添加为子弹"), true);
                                break;
                            case "off":
                            case "关闭":
                            case "none":
                                mode = PlayerStateManager.AddMode.NONE;
                                player.displayClientMessage(Component.literal("§7已关闭添加模式§r\n蹲下右键将显示物品配置信息"), true);
                                break;
                            default:
                                context.getSource().sendFailure(Component.literal("未知模式！可用模式: armor/bullet/off"));
                                return 0;
                        }
                        
                        PlayerStateManager.setAddMode(player, mode);
                        return 1;
                    })
                )
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    PlayerStateManager.AddMode mode = PlayerStateManager.getAddMode(player);
                    String modeName = switch (mode) {
                        case ARMOR -> "§a护甲添加模式§r";
                        case BULLET -> "§c子弹添加模式§r";
                        case NONE -> "§7关闭§r";
                    };
                    context.getSource().sendSuccess(() -> 
                        Component.literal("当前模式: " + modeName + "\n" +
                            "使用 /fmode armor 切换到护甲添加模式\n" +
                            "使用 /fmode bullet 切换到子弹添加模式\n" +
                            "使用 /fmode off 关闭添加模式"), false);
                    return 1;
                })
        );
    }
}