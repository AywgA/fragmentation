package com.fragmentation.commands;

import com.fragmentation.ballistics.BallisticsMath;
import com.fragmentation.util.WorldWindManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class SetWindCommand {
    private static final int[] AIR_TEMPERATURES = {0, 15, 30, 40};

    private SetWindCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fwind")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("set")
                    .then(createLegacySetBranch("left", WorldWindManager.Direction.LEFT))
                    .then(createLegacySetBranch("right", WorldWindManager.Direction.RIGHT))
                    .then(createCardinalSetBranch("north", WorldWindManager.Direction.NORTH))
                    .then(createCardinalSetBranch("south", WorldWindManager.Direction.SOUTH))
                    .then(createCardinalSetBranch("east", WorldWindManager.Direction.EAST))
                    .then(createCardinalSetBranch("west", WorldWindManager.Direction.WEST))
                    .then(createRandomSetBranch())
                )
                .then(Commands.literal("clear")
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .executes(context -> setWind(
                            context.getSource(),
                            BlockPosArgument.getBlockPos(context, "from"),
                            BlockPosArgument.getBlockPos(context, "from"),
                            WorldWindManager.Direction.LEFT,
                            0.0D,
                            BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
                        ))
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(context -> setWind(
                                context.getSource(),
                                BlockPosArgument.getBlockPos(context, "from"),
                                BlockPosArgument.getBlockPos(context, "to"),
                                WorldWindManager.Direction.LEFT,
                                0.0D,
                                BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
                            ))
                        )
                    )
                )
                .then(Commands.literal("get")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> queryWind(
                            context.getSource(),
                            BlockPosArgument.getBlockPos(context, "pos")
                        ))
                    )
                )
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createLegacySetBranch(
        String literal,
        WorldWindManager.Direction direction
    ) {
        return Commands.literal(literal)
            .then(Commands.argument(
                    "speed",
                    DoubleArgumentType.doubleArg(
                        WorldWindManager.MIN_SPEED,
                        WorldWindManager.MAX_SPEED
                    )
                )
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .executes(context -> setWind(
                        context.getSource(),
                        BlockPosArgument.getBlockPos(context, "from"),
                        BlockPosArgument.getBlockPos(context, "from"),
                        direction,
                        DoubleArgumentType.getDouble(context, "speed"),
                        BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
                    ))
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(context -> setWind(
                            context.getSource(),
                            BlockPosArgument.getBlockPos(context, "from"),
                            BlockPosArgument.getBlockPos(context, "to"),
                            direction,
                            DoubleArgumentType.getDouble(context, "speed"),
                            BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
                        ))
                    )
                )
            );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createCardinalSetBranch(
        String literal,
        WorldWindManager.Direction direction
    ) {
        RequiredArgumentBuilder<CommandSourceStack, Double> speedArgument = Commands.argument(
            "speed",
            DoubleArgumentType.doubleArg(
                WorldWindManager.MIN_SPEED,
                WorldWindManager.MAX_SPEED
            )
        );
        for (int temperatureCelsius : AIR_TEMPERATURES) {
            speedArgument.then(
                Commands.literal(Integer.toString(temperatureCelsius))
                    .then(createPositionBranch(direction, temperatureCelsius))
            );
        }
        return Commands.literal(literal).then(speedArgument);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createPositionBranch(
        WorldWindManager.Direction direction,
        int temperatureCelsius
    ) {
        return Commands.argument("from", BlockPosArgument.blockPos())
            .executes(context -> setWind(
                context.getSource(),
                BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "from"),
                direction,
                DoubleArgumentType.getDouble(context, "speed"),
                temperatureCelsius
            ))
            .then(Commands.argument("to", BlockPosArgument.blockPos())
                .executes(context -> setWind(
                    context.getSource(),
                    BlockPosArgument.getBlockPos(context, "from"),
                    BlockPosArgument.getBlockPos(context, "to"),
                    direction,
                    DoubleArgumentType.getDouble(context, "speed"),
                    temperatureCelsius
                ))
            );
    }

    /**
     * 随机风分支：/fwind set random <最小风速> <最大风速> [温度] <from> [to]
     * 区域内风速在范围内随机，风向随机。温度可省略（用默认温度）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> createRandomSetBranch() {
        RequiredArgumentBuilder<CommandSourceStack, Double> maxSpeedArgument = Commands.argument(
            "maxSpeed",
            DoubleArgumentType.doubleArg(
                WorldWindManager.MIN_SPEED,
                WorldWindManager.MAX_SPEED
            )
        );
        // 默认温度（不填温度字面量，直接给坐标）
        maxSpeedArgument.then(
            createRandomPositionBranch(BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS)
        );
        for (int temperatureCelsius : AIR_TEMPERATURES) {
            maxSpeedArgument.then(
                Commands.literal(Integer.toString(temperatureCelsius))
                    .then(createRandomPositionBranch(temperatureCelsius))
            );
        }

        RequiredArgumentBuilder<CommandSourceStack, Double> minSpeedArgument = Commands.argument(
            "minSpeed",
            DoubleArgumentType.doubleArg(
                WorldWindManager.MIN_SPEED,
                WorldWindManager.MAX_SPEED
            )
        ).then(maxSpeedArgument);

        return Commands.literal("random").then(minSpeedArgument);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> createRandomPositionBranch(
        int temperatureCelsius
    ) {
        return Commands.argument("from", BlockPosArgument.blockPos())
            .executes(context -> setRandomWind(
                context.getSource(),
                BlockPosArgument.getBlockPos(context, "from"),
                BlockPosArgument.getBlockPos(context, "from"),
                DoubleArgumentType.getDouble(context, "minSpeed"),
                DoubleArgumentType.getDouble(context, "maxSpeed"),
                temperatureCelsius
            ))
            .then(Commands.argument("to", BlockPosArgument.blockPos())
                .executes(context -> setRandomWind(
                    context.getSource(),
                    BlockPosArgument.getBlockPos(context, "from"),
                    BlockPosArgument.getBlockPos(context, "to"),
                    DoubleArgumentType.getDouble(context, "minSpeed"),
                    DoubleArgumentType.getDouble(context, "maxSpeed"),
                    temperatureCelsius
                ))
            );
    }

    private static int setRandomWind(
        CommandSourceStack source,
        BlockPos from,
        BlockPos to,
        double minSpeed,
        double maxSpeed,
        int temperatureCelsius
    ) {
        double low = Math.min(minSpeed, maxSpeed);
        double high = Math.max(minSpeed, maxSpeed);
        WorldWindManager.setRandomRegion(
            source.getLevel(),
            from,
            to,
            low,
            high,
            temperatureCelsius
        );
        String range = from.equals(to)
            ? formatPos(from)
            : formatPos(from) + " 至 " + formatPos(to);
        String windDescription = high <= 0.0D
            ? "无风"
            : "随机风 " + String.format("%.2f", low) + "~"
                + String.format("%.2f", high) + " m/s，风向随机";
        source.sendSuccess(
            () -> Component.literal(
                "随机风区已设置: " + range + "，" + windDescription + "，"
                    + formatAir(temperatureCelsius)
            ),
            true
        );
        return 1;
    }

    private static int setWind(
        CommandSourceStack source,
        BlockPos from,
        BlockPos to,
        WorldWindManager.Direction direction,
        double speed,
        int temperatureCelsius
    ) {
        WorldWindManager.setRegion(
            source.getLevel(),
            from,
            to,
            direction,
            speed,
            temperatureCelsius
        );
        String range = from.equals(to)
            ? formatPos(from)
            : formatPos(from) + " 至 " + formatPos(to);
        String windDescription = speed <= 0.0D
            ? "无风"
            : direction.getDisplayName() + " " + String.format("%.2f", speed) + " m/s";
        String airDescription = formatAir(temperatureCelsius);
        source.sendSuccess(
            () -> Component.literal(
                "风区已设置: " + range + "，" + windDescription + "，" + airDescription
            ),
            true
        );
        return 1;
    }

    private static int queryWind(CommandSourceStack source, BlockPos pos) {
        WorldWindManager.WindSample wind = WorldWindManager.getWind(source.getLevel(), pos);
        String description = wind.speed() <= 0.0D
            ? "无风"
            : wind.label() + " " + String.format("%.2f", wind.speed()) + " m/s";
        source.sendSuccess(
            () -> Component.literal(
                formatPos(pos) + " 当前为" + description + "，"
                    + formatAir(wind.temperatureCelsius())
            ),
            false
        );
        return 1;
    }

    private static String formatAir(int temperatureCelsius) {
        return "空气温度 " + temperatureCelsius + "°C，阻力系数 "
            + String.format("%.5f", BallisticsMath.airDragCoefficient(temperatureCelsius));
    }

    private static String formatPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }
}
