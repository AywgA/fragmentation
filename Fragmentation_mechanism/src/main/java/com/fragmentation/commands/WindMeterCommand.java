package com.fragmentation.commands;

import com.fragmentation.util.WindMeterManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class WindMeterCommand {
    private WindMeterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fwindmeter")
                .requires(source -> source.hasPermission(2))
                .executes(context -> addHeldItem(context.getSource()))
                .then(Commands.literal("add")
                    .executes(context -> addHeldItem(context.getSource())))
                .then(Commands.literal("remove")
                    .executes(context -> removeHeldItem(context.getSource())))
                .then(Commands.literal("list")
                    .executes(context -> listItems(context.getSource())))
        );
    }

    private static int addHeldItem(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        String itemId = WindMeterManager.getItemId(stack);
        if (itemId == null) {
            source.sendFailure(Component.literal("你必须在主手手持一个物品！"));
            return 0;
        }
        if (!WindMeterManager.add(stack)) {
            source.sendFailure(Component.literal("该物品已经是风速测量器: " + itemId));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal(
                "已添加风速测量器: " + stack.getHoverName().getString() + " (" + itemId + ")"
            ),
            true
        );
        return 1;
    }

    private static int removeHeldItem(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        String itemId = WindMeterManager.getItemId(stack);
        if (itemId == null) {
            source.sendFailure(Component.literal("你必须在主手手持一个物品！"));
            return 0;
        }
        if (!WindMeterManager.remove(stack)) {
            source.sendFailure(Component.literal("该物品不是风速测量器: " + itemId));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal("已移除风速测量器: " + itemId),
            true
        );
        return 1;
    }

    private static int listItems(CommandSourceStack source) {
        List<String> itemIds = WindMeterManager.getRegisteredItemIds();
        if (itemIds.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前没有已登记的风速测量器。"), false);
            return 1;
        }
        source.sendSuccess(
            () -> Component.literal(
                "已登记的风速测量器 (" + itemIds.size() + "): " + String.join(", ", itemIds)
            ),
            false
        );
        return itemIds.size();
    }
}
