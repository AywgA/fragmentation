package com.fragmentation.commands;

import com.fragmentation.util.WorldCoverManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public final class SetCoverCommand {
    private SetCoverCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fcover")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add").then(createSetBranch()))
                .then(Commands.literal("set").then(createSetBranch()))
                .then(Commands.literal("get")
                    .then(Commands.argument("block", ResourceLocationArgument.id())
                        .executes(context -> showCover(
                            context.getSource(),
                            ResourceLocationArgument.getId(context, "block")
                        ))
                    )
                )
                .then(Commands.literal("remove")
                    .then(Commands.argument("block", ResourceLocationArgument.id())
                        .executes(context -> removeCover(
                            context.getSource(),
                            ResourceLocationArgument.getId(context, "block")
                        ))
                    )
                )
                .then(Commands.literal("list")
                    .executes(context -> listCovers(context.getSource()))
                )
                .then(Commands.literal("reload")
                    .executes(context -> {
                        WorldCoverManager.loadConfig();
                        context.getSource().sendSuccess(
                            () -> Component.literal("掩体配置已重新加载"),
                            true
                        );
                        return 1;
                    })
                )
        );
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createSetBranch() {
        return Commands.argument(
                "penetrationThreshold",
                DoubleArgumentType.doubleArg(0.0D)
            )
            .then(Commands.argument(
                    "thicknessCoefficient",
                    DoubleArgumentType.doubleArg(0.0D)
                )
                .then(Commands.argument(
                        "densityCoefficient",
                        DoubleArgumentType.doubleArg(0.0D)
                    )
                    .executes(context -> setHeldCover(
                        context.getSource(),
                        DoubleArgumentType.getDouble(context, "penetrationThreshold"),
                        DoubleArgumentType.getDouble(context, "thicknessCoefficient"),
                        DoubleArgumentType.getDouble(context, "densityCoefficient"),
                        null
                    ))
                    .then(Commands.argument(
                            "ricochetCoefficient",
                            DoubleArgumentType.doubleArg(0.0D, 1.0D)
                        )
                        .executes(context -> setHeldCover(
                            context.getSource(),
                            DoubleArgumentType.getDouble(context, "penetrationThreshold"),
                            DoubleArgumentType.getDouble(context, "thicknessCoefficient"),
                            DoubleArgumentType.getDouble(context, "densityCoefficient"),
                            DoubleArgumentType.getDouble(context, "ricochetCoefficient")
                        ))
                    )
                )
            );
    }

    private static int setHeldCover(
        CommandSourceStack source,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient,
        Double ricochetCoefficient
    ) {
        ResourceLocation blockId = getHeldBlockId(source);
        if (blockId == null) return 0;
        return ricochetCoefficient != null
            ? setCover(
                source,
                blockId,
                penetrationThreshold,
                thicknessCoefficient,
                densityCoefficient,
                ricochetCoefficient
            )
            : setCover(
                source,
                blockId,
                penetrationThreshold,
                thicknessCoefficient,
                densityCoefficient
            );
    }

    private static ResourceLocation getHeldBlockId(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("该命令只能由玩家执行"));
            return null;
        }
        ItemStack heldItem = player.getMainHandItem();
        if (!(heldItem.getItem() instanceof BlockItem blockItem)) {
            source.sendFailure(Component.literal("主手必须持有一个方块"));
            return null;
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(blockItem.getBlock());
        if (blockId == null) {
            source.sendFailure(Component.literal("无法识别主手方块"));
        }
        return blockId;
    }

    private static int setCover(
        CommandSourceStack source,
        ResourceLocation blockId,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient
    ) {
        return setCover(
            source,
            blockId,
            penetrationThreshold,
            thicknessCoefficient,
            densityCoefficient,
            null
        );
    }

    private static int setCover(
        CommandSourceStack source,
        ResourceLocation blockId,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient,
        Double ricochetCoefficient
    ) {
        if (blockId == null) return 0;
        if (!ForgeRegistries.BLOCKS.containsKey(blockId)) {
            source.sendFailure(Component.literal("未找到方块: " + blockId));
            return 0;
        }

        WorldCoverManager.CoverEntry entry = ricochetCoefficient != null
            ? WorldCoverManager.setCover(
                blockId,
                penetrationThreshold,
                thicknessCoefficient,
                densityCoefficient,
                ricochetCoefficient
            )
            : WorldCoverManager.setCover(
                blockId,
                penetrationThreshold,
                thicknessCoefficient,
                densityCoefficient
            );
        if (entry == null) {
            source.sendFailure(Component.literal("掩体配置写入失败"));
            return 0;
        }

        double speedMultiplier = Math.max(
            0.0D,
            1.0D - entry.thicknessCoefficient() * entry.densityCoefficient()
        );
        source.sendSuccess(
            () -> Component.literal(
                "掩体配置已保存: " + formatEntry(entry)
                    + "\n穿透后速度倍率: " + String.format("%.4f", speedMultiplier)
            ),
            true
        );
        return 1;
    }

    private static int showCover(CommandSourceStack source, ResourceLocation blockId) {
        WorldCoverManager.CoverEntry entry = WorldCoverManager.getCover(blockId);
        if (entry == null) {
            source.sendFailure(Component.literal("该方块未配置为掩体: " + blockId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(formatEntry(entry)), false);
        return 1;
    }

    private static int removeCover(CommandSourceStack source, ResourceLocation blockId) {
        if (!WorldCoverManager.removeCover(blockId)) {
            source.sendFailure(Component.literal("该方块未配置为掩体: " + blockId));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal("已删除掩体配置: " + blockId),
            true
        );
        return 1;
    }

    private static int listCovers(CommandSourceStack source) {
        List<WorldCoverManager.CoverEntry> entries = WorldCoverManager.listCovers();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前没有掩体配置"), false);
            return 1;
        }
        source.sendSuccess(
            () -> Component.literal("=== 掩体配置 (" + entries.size() + ") ==="),
            false
        );
        for (WorldCoverManager.CoverEntry entry : entries) {
            source.sendSuccess(() -> Component.literal(formatEntry(entry)), false);
        }
        return entries.size();
    }

    private static String formatEntry(WorldCoverManager.CoverEntry entry) {
        return entry.blockId()
            + " | 抗穿透阈值=" + String.format("%.6f", entry.penetrationThreshold())
            + " | 厚度系数=" + String.format("%.6f", entry.thicknessCoefficient())
            + " | 密度系数=" + String.format("%.6f", entry.densityCoefficient())
            + " | 跳弹系数=" + String.format("%.2f", entry.ricochetCoefficient());
    }
}
