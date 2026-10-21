package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;

public class SetArmorMaterialCommand {
    
    private static final List<String> MATERIALS = Arrays.asList(
        "ceramic",
        "steel",
        "aluminum",
        "titanium",
        "uhmwpe",
        "kevlar"
    );
    
    private static final SuggestionProvider<CommandSourceStack> MATERIAL_SUGGESTIONS = (context, builder) -> {
        for (String material : MATERIALS) {
            if (material.startsWith(builder.getRemaining().toLowerCase())) {
                builder.suggest(material);
            }
        }
        return builder.buildFuture();
    };
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fsetarmormaterial")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("material", StringArgumentType.string())
                    .suggests(MATERIAL_SUGGESTIONS)
                    .executes(context -> {
                        String material = StringArgumentType.getString(context, "material").toLowerCase();
                        Player player = context.getSource().getPlayerOrException();
                        ItemStack heldItem = player.getMainHandItem();
                        
                        if (heldItem.isEmpty()) {
                            context.getSource().sendFailure(Component.literal("你必须手持一个护甲物品！"));
                            return 0;
                        }
                        
                        ArmorConfig config = FragmentationMod.getArmorConfig();
                        ArmorConfig.ArmorEntry existing = config.getArmorForItem(heldItem);
                        
                        if (existing == null) {
                            context.getSource().sendFailure(Component.literal("该物品未配置为护甲！请先使用 /addarmor 添加"));
                            return 0;
                        }
                        
                        existing.material = material;
                        config.saveConfig();
                        
                        context.getSource().sendSuccess(() -> 
                            Component.literal("护甲材料已更新:\n材料: " + material), true);
                        return 1;
                    })
                )
        );
    }
}