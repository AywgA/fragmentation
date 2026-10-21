package com.fragmentation.commands;

import com.fragmentation.session.CombatSessionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /fragmentation 根命令：
 * - jilu   开始对局记录（行动路线、淘汰统计）
 * - jiesuan 进入结算画面
 */
public final class FragmentationCommand {

    private FragmentationCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fragmentation")
                .then(Commands.literal("jilu")
                    .executes(FragmentationCommand::startRecording))
                .then(Commands.literal("jiesuan")
                    .executes(FragmentationCommand::openSettlement))
        );
    }

    private static int startRecording(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CombatSessionManager.startRecording(player);
        player.sendSystemMessage(Component.literal(
            "§a§l对局记录已开始§r\n§7已开始记录你的行动路线、行动时间与淘汰目标。§r\n"
                + "§7死亡后可使用 §f/fragmentation jiesuan §7查看结算。§r"));
        return 1;
    }

    private static int openSettlement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SettlementCommand.open(player);
        return 1;
    }
}
