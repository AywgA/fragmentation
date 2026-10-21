package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ExplosiveConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 添加闪光弹：手持物品执行 /addflash。
 * 默认当量 1.0、无碎片；可在 explosives.json 修改。
 */
public final class AddFlashCommand {
    private AddFlashCommand() {
    }

    public static void register(
        com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher
    ) {
        dispatcher.register(
            Commands.literal("addflash")
                .requires(source -> source.hasPermission(2))
                .executes(context -> add(
                    context.getSource(),
                    ExplosiveConfig.ExplosiveType.FLASH,
                    1.0, 0, 0.0
                ))
        );
    }

    static int add(
        CommandSourceStack source,
        ExplosiveConfig.ExplosiveType type,
        double yield,
        int fragments,
        double fragmentPen
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("你必须手持要添加的物品！"));
            return 0;
        }
        if (held.getItem() instanceof net.minecraft.world.item.ArmorItem) {
            source.sendFailure(Component.literal(
                "手持的是穿戴装备，不能配置为" + typeName(type) + "！"));
            return 0;
        }

        String id = ExplosiveConfig.stackKey(held);
        if (id.isEmpty()) {
            source.sendFailure(Component.literal("无法识别该物品的注册名！"));
            return 0;
        }

        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        boolean existed = config.getEntry(id) != null;
        config.addOrUpdate(id, type, yield, fragments, fragmentPen);
        config.saveConfig();

        final String action = existed ? "已更新" : "已添加";
        final String typeLabel = typeName(type);
        final double fuseSeconds =
            type == ExplosiveConfig.ExplosiveType.FLASH ? 2.5 : 3.0;
        source.sendSuccess(() -> Component.literal(
            action + typeLabel + "：" + held.getDisplayName().getString()
                + "\n物品ID：" + id
                + "\n当量：" + yield + "，碎片数量：" + fragments
                + "，弹片穿透：" + fragmentPen
                + "\n可在存档目录 fragmentation/explosives.json 修改，右键投掷，引信 "
                + fuseSeconds + " 秒。"
        ), true);
        return 1;
    }

    static String typeName(ExplosiveConfig.ExplosiveType type) {
        return switch (type) {
            case FLASH -> "闪光弹";
            case GRENADE -> "手雷";
            case LAUNCHER -> "榴弹";
        };
    }
}
