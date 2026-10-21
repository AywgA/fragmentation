package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ShieldConfig;
import com.fragmentation.util.DurabilityUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class SetShieldCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetshield")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 11))
                    .then(Commands.argument("durability", IntegerArgumentType.integer(1, 100000))
                        .executes(context -> updateShield(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "level"),
                            IntegerArgumentType.getInteger(context, "durability"),
                            null
                        ))
                        .then(Commands.argument(
                                "ricochetCoefficient",
                                DoubleArgumentType.doubleArg(0.0D, 1.0D)
                            )
                            .executes(context -> updateShield(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "level"),
                                IntegerArgumentType.getInteger(context, "durability"),
                                DoubleArgumentType.getDouble(
                                    context,
                                    "ricochetCoefficient"
                                )
                            ))
                        )
                    )
                )
        );
    }

    private static int updateShield(
        CommandSourceStack source,
        int level,
        int durability,
        Double ricochetCoefficient
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.isEmpty()) {
            source.sendFailure(Component.literal("你必须手持一个护盾物品！"));
            return 0;
        }

        ShieldConfig config = FragmentationMod.getShieldConfig();
        if (ricochetCoefficient != null) {
            config.addShield(
                heldItem,
                level,
                durability,
                ricochetCoefficient
            );
        } else {
            config.addShield(heldItem, level, durability);
        }
        config.saveConfig();

        int originalMax = heldItem.getItem().getMaxDamage();
        int currentDurability;
        if (originalMax > 0) {
            float ratio = (float) durability / (float) originalMax;
            int currentDamage = heldItem.getDamageValue();
            currentDurability = Math.max(
                1,
                (int) Math.floor((originalMax - currentDamage) * ratio)
            );
        } else {
            currentDurability = durability;
        }

        DurabilityUtils.setCurrentDurability(heldItem, currentDurability);
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }

        ShieldConfig.ShieldEntry entry = config.getShieldForItem(heldItem);
        source.sendSuccess(
            () -> Component.literal(
                "护盾属性已更新:"
                    + "\n等级: " + level
                    + "\n耐久: " + durability
                    + "\n跳弹系数: " + String.format(
                        "%.2f",
                        entry != null ? entry.ricochetCoefficient : 0.0D
                    )
            ),
            true
        );
        return 1;
    }
}
