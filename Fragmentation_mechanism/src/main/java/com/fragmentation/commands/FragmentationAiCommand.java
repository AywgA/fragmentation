package com.fragmentation.commands;

import com.fragmentation.util.PlayerStateManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class FragmentationAiCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fragmentation")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("Ai")
                    .then(Commands.literal("turn")
                        .executes(context -> {
                            Player player = context.getSource().getPlayerOrException();
                            PlayerStateManager.setAiDamageMode(player, true);
                            context.getSource().sendSuccess(() ->
                                Component.literal("§a[Fragmentation] AI 特殊伤害判定已开启§r\n" +
                                    "§7子弹穿透防弹衣或头盔后，伤害落点随机重掷：§r\n" +
                                    "§7- 头部 10%§r\n" +
                                    "§7- 胸部/腹部/左臂/右臂/左腿/右腿 共 90%（各 15%）§r\n" +
                                    "§7模式已持久化：重进世界仍生效，仅可用关闭指令关闭"), true);
                            return 1;
                        })
                    )
                    .then(Commands.literal("shut")
                        .executes(context -> {
                            Player player = context.getSource().getPlayerOrException();
                            PlayerStateManager.setAiDamageMode(player, false);
                            context.getSource().sendSuccess(() ->
                                Component.literal("§7[Fragmentation] AI 特殊伤害判定已关闭§r"), true);
                            return 1;
                        })
                    )
                    .then(Commands.literal("notice")
                        .then(Commands.literal("turn")
                            .executes(context -> {
                                Player player = context.getSource().getPlayerOrException();
                                PlayerStateManager.setZoneRandomNoticeMode(player, true);
                                context.getSource().sendSuccess(() ->
                                    Component.literal("§a[Fragmentation] 部位随机结果提示已开启§r\n" +
                                        "§7被 NPC / 非玩家生物击中时，屏幕中下方将显示：§r\n" +
                                        "§7原始命中部位 → 实际结算部位§r"), true);
                                return 1;
                            })
                        )
                        .then(Commands.literal("shut")
                            .executes(context -> {
                                Player player = context.getSource().getPlayerOrException();
                                PlayerStateManager.setZoneRandomNoticeMode(player, false);
                                context.getSource().sendSuccess(() ->
                                    Component.literal("§7[Fragmentation] 部位随机结果提示已关闭§r"), true);
                                return 1;
                            })
                        )
                        .executes(context -> {
                            Player player = context.getSource().getPlayerOrException();
                            boolean noticeOn = PlayerStateManager.isZoneRandomNoticeMode(player);
                            context.getSource().sendSuccess(() ->
                                Component.literal("部位随机结果提示: " +
                                    (noticeOn ? "§a开启§r" : "§7关闭§r") + "\n" +
                                    "使用 /fragmentation Ai notice turn 开启\n" +
                                    "使用 /fragmentation Ai notice shut 关闭"), false);
                            return 1;
                        })
                    )
                    .executes(context -> {
                        Player player = context.getSource().getPlayerOrException();
                        boolean enabled = PlayerStateManager.isAiDamageMode(player);
                        boolean noticeOn = PlayerStateManager.isZoneRandomNoticeMode(player);
                        context.getSource().sendSuccess(() ->
                            Component.literal("AI 特殊伤害判定: " +
                                (enabled ? "§a开启§r" : "§7关闭§r") + "\n" +
                                "使用 /fragmentation Ai turn 开启\n" +
                                "使用 /fragmentation Ai shut 关闭\n" +
                                "部位随机结果提示: " +
                                (noticeOn ? "§a开启§r" : "§7关闭§r") + "\n" +
                                "使用 /fragmentation Ai notice turn / shut 切换"), false);
                        return 1;
                    })
                )
        );
    }
}
