package com.fragmentation.commands;

import com.fragmentation.config.GunConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SetGunPowerCommand {
    
    private static final List<String> POWER_LEVELS = Arrays.asList(
        "extremely_low",
        "low",
        "medium",
        "high",
        "very_high",
        "extremely_high",
        "unknown"
    );
    
    private static final SuggestionProvider<CommandSourceStack> POWER_LEVEL_SUGGESTIONS = (context, builder) -> {
        for (String level : POWER_LEVELS) {
            if (level.startsWith(builder.getRemaining().toLowerCase())) {
                builder.suggest(level);
            }
        }
        return builder.buildFuture();
    };
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetgunpower")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("powerLevel", StringArgumentType.string())
                    .suggests(POWER_LEVEL_SUGGESTIONS)
                    .executes(context -> {
                        String powerLevelStr = StringArgumentType.getString(context, "powerLevel");
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        
                        if (heldItem.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("你必须手持一把枪械！"));
                            return 0;
                        }
                        
                        String gunId;
                        String taczId = getTaczGunId(heldItem);
                        if (taczId != null) {
                            gunId = taczId;
                        } else {
                            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(heldItem.getItem());
                            gunId = itemId != null ? itemId.toString() : "unknown";
                        }
                        
                        GunConfig.PowerLevel level = GunConfig.PowerLevel.fromId(powerLevelStr);
                        
                        GunConfig.getInstance().setGunPowerLevel(gunId, level);
                        
                        context.getSource().sendSuccess(() -> 
                            Component.literal("枪械激发威力已设置:\n枪械: " + heldItem.getDisplayName().getString() + 
                                "\n枪械ID: " + gunId +
                                "\n威力等级: " + level.getDisplayName() + 
                                "\n伤害倍率: " + String.format("%.2f", level.getDamageMultiplier()) + 
                                "\n穿透倍率: " + String.format("%.2f", level.getPenetrationMultiplier())), true);
                        return 1;
                    })
                )
        );
    }
    
    private static String getTaczGunId(ItemStack itemStack) {
        try {
            net.minecraft.nbt.CompoundTag tag = itemStack.getTag();
            if (tag != null) {
                if (tag.contains("tacz_gun_id")) {
                    return tag.getString("tacz_gun_id");
                }
                if (tag.contains("GunId")) {
                    return tag.getString("GunId");
                }
                if (tag.contains("gun_id")) {
                    return tag.getString("gun_id");
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}