package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class SetArmorCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetarmor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 11))
                    .then(Commands.argument("durability", IntegerArgumentType.integer(1, 100000))
                        .executes(context -> updateArmor(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "level"),
                            IntegerArgumentType.getInteger(context, "durability"),
                            null
                        ))
                        .then(Commands.argument(
                                "ricochetCoefficient",
                                DoubleArgumentType.doubleArg(0.0D, 1.0D)
                            )
                            .executes(context -> updateArmor(
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

    private static int updateArmor(
        CommandSourceStack source,
        int level,
        int durability,
        Double ricochetCoefficient
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.isEmpty()) {
            source.sendFailure(Component.literal("你必须手持一个护甲物品！"));
            return 0;
        }

        ArmorConfig config = FragmentationMod.getArmorConfig();
        if (ricochetCoefficient != null) {
            config.addArmor(
                heldItem,
                level,
                durability,
                ricochetCoefficient
            );
        } else {
            config.addArmor(heldItem, level, durability);
        }
        config.saveConfig();

        int originalMax = heldItem.getItem().getMaxDamage();
        if (originalMax > 0 && durability > 0) {
            float ratio = (float) durability / (float) originalMax;
            int currentDamage = heldItem.getDamageValue();
            int newDamage = (int) Math.floor(currentDamage * ratio);
            heldItem.setDamageValue(Math.min(newDamage, durability));
        }

        ArmorConfig.ArmorEntry entry = config.getArmorForItem(heldItem);
        source.sendSuccess(
            () -> Component.literal(
                "护甲属性已更新:"
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
