package com.fragmentation.commands;

import com.fragmentation.config.GunConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public final class SetGunDetailsCommand {

    private SetGunDetailsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetgunmoa")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("moa", FloatArgumentType.floatArg(GunConfig.MIN_MOA, GunConfig.MAX_MOA))
                    .executes(context -> {
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        String gunId = resolveGunId(heldItem);
                        if (gunId == null) {
                            context.getSource().sendFailure(Component.literal("你必须手持一把枪械！"));
                            return 0;
                        }

                        float moa = FloatArgumentType.getFloat(context, "moa");
                        GunConfig gunConfig = GunConfig.getInstance();
                        gunConfig.setMoa(gunId, moa);
                        float radiusAt100Meters = gunConfig.calculateSpreadRadiusCm(gunId, 100.0f);

                        context.getSource().sendSuccess(() ->
                            Component.literal("枪械 MOA 已设置:\n枪械ID: " + gunId +
                                "\nMOA: " + String.format("%.2f", moa) +
                                "\n100米散布半径: " + String.format("%.2f", radiusAt100Meters) + " cm"), true);
                        return 1;
                    })
                )
        );

        dispatcher.register(
            Commands.literal("fsetguncaliber")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("caliber", StringArgumentType.greedyString())
                    .executes(context -> {
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        String gunId = resolveGunId(heldItem);
                        if (gunId == null) {
                            context.getSource().sendFailure(Component.literal("你必须手持一把枪械！"));
                            return 0;
                        }

                        String caliber = StringArgumentType.getString(context, "caliber").trim();
                        if (caliber.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("口径不能为空！"));
                            return 0;
                        }

                        GunConfig.getInstance().setCaliber(gunId, caliber);
                        context.getSource().sendSuccess(() ->
                            Component.literal("枪械口径已设置:\n枪械ID: " + gunId + "\n口径: " + caliber), true);
                        return 1;
                    })
                )
        );
    }

    private static String resolveGunId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        CompoundTag tag = stack.getTag();
        if (tag != null) {
            if (tag.contains("Extras")) {
                CompoundTag extras = tag.getCompound("Extras");
                if (extras.contains("GunId")) {
                    return extras.getString("GunId");
                }
            }
            if (tag.contains("GunId")) {
                return tag.getString("GunId");
            }
            if (tag.contains("tacz_gun_id")) {
                return tag.getString("tacz_gun_id");
            }
            if (tag.contains("gun_id")) {
                return tag.getString("gun_id");
            }
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null ? itemId.toString() : null;
    }
}
