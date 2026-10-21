package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class SetBulletCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetbullet")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 110))
                    .then(Commands.argument("armorDamage", DoubleArgumentType.doubleArg(0.0, 1000.0))
                        .executes(context -> updateBullet(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "level"),
                            DoubleArgumentType.getDouble(context, "armorDamage"),
                            null,
                            null,
                            null,
                            null,
                            null
                        ))
                        .then(Commands.argument("baseDamage", FloatArgumentType.floatArg(0))
                            .executes(context -> updateBullet(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "level"),
                                DoubleArgumentType.getDouble(context, "armorDamage"),
                                FloatArgumentType.getFloat(context, "baseDamage"),
                                null,
                                null,
                                null,
                                null
                            ))
                            .then(Commands.argument(
                                    "bulletMassKg",
                                    DoubleArgumentType.doubleArg(0.0D, 10.0D)
                                )
                                .then(Commands.argument(
                                        "bulletRadiusM",
                                        DoubleArgumentType.doubleArg(0.0D, 1.0D)
                                    )
                                    .executes(context -> updateBullet(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "level"),
                                        DoubleArgumentType.getDouble(context, "armorDamage"),
                                        FloatArgumentType.getFloat(context, "baseDamage"),
                                        DoubleArgumentType.getDouble(context, "bulletMassKg"),
                                        DoubleArgumentType.getDouble(context, "bulletRadiusM"),
                                        null,
                                        null
                                    ))
                                    .then(Commands.argument(
                                            "ricochetRate",
                                            DoubleArgumentType.doubleArg(0.0D, 1.0D)
                                        )
                                        .executes(context -> updateBullet(
                                            context.getSource(),
                                            IntegerArgumentType.getInteger(context, "level"),
                                            DoubleArgumentType.getDouble(context, "armorDamage"),
                                            FloatArgumentType.getFloat(context, "baseDamage"),
                                            DoubleArgumentType.getDouble(context, "bulletMassKg"),
                                            DoubleArgumentType.getDouble(context, "bulletRadiusM"),
                                            DoubleArgumentType.getDouble(context, "ricochetRate"),
                                            null
                                        ))
                                        .then(Commands.argument(
                                                "fragmentationRate",
                                                DoubleArgumentType.doubleArg(0.0D, 1.0D)
                                            )
                                            .executes(context -> updateBullet(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "level"),
                                                DoubleArgumentType.getDouble(context, "armorDamage"),
                                                FloatArgumentType.getFloat(context, "baseDamage"),
                                                DoubleArgumentType.getDouble(context, "bulletMassKg"),
                                                DoubleArgumentType.getDouble(context, "bulletRadiusM"),
                                                DoubleArgumentType.getDouble(context, "ricochetRate"),
                                                DoubleArgumentType.getDouble(context, "fragmentationRate")
                                            ))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
        );
    }

    private static int updateBullet(
        CommandSourceStack source,
        int level,
        double armorDamage,
        Float baseDamage,
        Double bulletMass,
        Double bulletRadius,
        Double ricochetRate,
        Double fragmentationRate
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.isEmpty()) {
            source.sendFailure(Component.literal("你必须手持一个子弹物品！"));
            return 0;
        }

        if (heldItem.getItem() instanceof net.minecraft.world.item.ArmorItem) {
            source.sendFailure(Component.literal(
                "手持的是穿戴装备（护甲/弹挂等），不能配置为子弹！"));
            return 0;
        }

        BulletConfig config = FragmentationMod.getBulletConfig();
        BulletConfig.BulletEntry existing = config.getBulletForItem(heldItem);
        float resolvedBaseDamage = baseDamage != null
            ? baseDamage
            : existing != null ? existing.baseDamage : 1.0F;
        config.addBullet(heldItem, level, armorDamage, resolvedBaseDamage);

        BulletConfig.BulletEntry entry = config.getBulletForItem(heldItem);
        if (entry == null) {
            source.sendFailure(Component.literal("子弹配置写入失败"));
            return 0;
        }
        if (bulletMass != null) entry.bulletMass = bulletMass;
        if (bulletRadius != null) entry.bulletRadius = bulletRadius;
        if (ricochetRate != null) entry.ricochetRate = ricochetRate;
        if (fragmentationRate != null) {
            entry.fragmentationRate = fragmentationRate;
        }
        config.saveConfig();

        source.sendSuccess(
            () -> Component.literal(
                "子弹属性已更新:"
                    + "\n物品ID: " + entry.itemId
                    + "\n穿深等级: " + entry.penetrationLevel + " / 110"
                    + "\n对护甲基础伤害: " + entry.armorDamage
                    + "\n子弹基础伤害: " + String.format("%.1f", entry.baseDamage)
                    + "\n子弹重量: " + String.format("%.3f g", entry.bulletMass * 1000.0D)
                    + "\n子弹半径: " + String.format("%.3f mm", entry.bulletRadius * 1000.0D)
                    + "\n跳弹率: " + String.format("%.2f%%", entry.ricochetRate * 100.0D)
                    + "\n碎弹率: " + String.format("%.2f%%", entry.fragmentationRate * 100.0D)
            ),
            true
        );
        return 1;
    }
}
