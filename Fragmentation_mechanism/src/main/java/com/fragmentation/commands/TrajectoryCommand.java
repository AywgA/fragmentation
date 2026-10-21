package com.fragmentation.commands;

import com.fragmentation.network.ModNetwork;
import com.fragmentation.network.TrajectoryStatePacket;
import com.fragmentation.util.PlayerStateManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;

public final class TrajectoryCommand {
    private TrajectoryCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("ftrajectory")
                .requires(source -> source.hasPermission(2))
                .executes(context -> setEnabled(context.getSource(), true))
                .then(Commands.literal("on")
                    .executes(context -> setEnabled(context.getSource(), true))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(context -> setEnabled(
                            context.getSource(),
                            EntityArgument.getPlayers(context, "targets"),
                            true
                        ))))
                .then(Commands.literal("off")
                    .executes(context -> setEnabled(context.getSource(), false))
                    .then(Commands.argument("targets", EntityArgument.players())
                        .executes(context -> setEnabled(
                            context.getSource(),
                            EntityArgument.getPlayers(context, "targets"),
                            false
                        ))))
        );
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer player) {
            return setEnabled(source, List.of(player), enabled);
        }

        List<ServerPlayer> onlinePlayers =
            source.getServer().getPlayerList().getPlayers();
        if (onlinePlayers.isEmpty()) {
            source.sendFailure(Component.literal("当前没有在线玩家。"));
            return 0;
        }
        return setEnabled(source, onlinePlayers, enabled);
    }

    private static int setEnabled(
        CommandSourceStack source,
        Collection<ServerPlayer> targets,
        boolean enabled
    ) {
        for (ServerPlayer player : targets) {
            PlayerStateManager.setTrajectoryMode(player, enabled);
            if (!enabled) {
                ModNetwork.clearTrajectories(player);
            }
            ModNetwork.sendToPlayer(player, new TrajectoryStatePacket(enabled));
            player.sendSystemMessage(Component.literal(
                enabled
                    ? "§a[弹道显示] 已开启并持续保留：§e黄线=弹道，§b青线=跳弹，§a绿线=穿透；§c红→§e黄→§a绿平滑渐变=冲击波威力（§c红=致命区 §e黄=重伤区 §a绿=边缘区），§9蓝线=弹片。\n§7按 §eJ §7循环切换：正常 → 冲击波平面图 → 穿透平面图；按 §eK §7清除全部显示。"
                    : "§7[弹道显示] 已关闭，已清除全部弹道。"
            ));
        }

        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendSuccess(
                () -> Component.literal(
                    "已为 " + targets.size() + " 名玩家"
                        + (enabled ? "开启" : "关闭") + "弹道显示。"
                ),
                true
            );
        }
        return targets.size();
    }
}
