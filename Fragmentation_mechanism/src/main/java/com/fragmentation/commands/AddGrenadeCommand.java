package com.fragmentation.commands;

import com.fragmentation.config.ExplosiveConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 添加手雷：手持物品执行 /addgrenade。
 * 默认当量 1.0、碎片 256、弹片穿透 9；可在 explosives.json 修改。
 */
public final class AddGrenadeCommand {
    private AddGrenadeCommand() {
    }

    public static void register(
        com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher
    ) {
        dispatcher.register(
            Commands.literal("addgrenade")
                .requires(source -> source.hasPermission(2))
                .executes(context -> AddFlashCommand.add(
                    context.getSource(),
                    com.fragmentation.config.ExplosiveConfig.ExplosiveType.GRENADE,
                    1.0,
                    ExplosiveConfig.BASE_FRAG_COUNT,
                    ExplosiveConfig.BASE_FRAG_PEN
                ))
        );
    }
}
