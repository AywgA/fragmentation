package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ExplosiveConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 添加榴弹（枪挂/自动榴弹发射器发射的 TACZ 高爆弹药）：
 *   /addnade               —— 手持 TACZ 榴弹弹药，自动读取弹药ID
 *   /addnade <弹药ID>      —— 直接指定，如 /addnade tacz:40mm_he
 * 默认当量 1.0、碎片 96、弹片穿透 8；可在 explosives.json 修改。
 */
public final class AddNadeCommand {
    private static final int DEFAULT_FRAGMENTS = 96;
    private static final double DEFAULT_PEN = 8.0;

    private AddNadeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addnade")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Player player = context.getSource().getPlayerOrException();
                    ItemStack held = player.getMainHandItem();
                    if (held.isEmpty()) {
                        context.getSource().sendFailure(Component.literal(
                            "你必须手持榴弹弹药，或使用 /addnade <弹药ID>。"));
                        return 0;
                    }
                    String ammoId = FragmentationMod.getBulletConfig()
                        .getTaczAmmoId(held);
                    if (ammoId == null) {
                        context.getSource().sendFailure(Component.literal(
                            "手持物品不是 TACZ 弹药（读不到 AmmoId），请改用 /addnade <弹药ID>。"));
                        return 0;
                    }
                    return add(context.getSource(), ammoId);
                })
                .then(Commands.argument("ammoId", StringArgumentType.greedyString())
                    .executes(context -> add(
                        context.getSource(),
                        StringArgumentType.getString(context, "ammoId").trim()
                    )))
        );
    }

    private static int add(CommandSourceStack source, String ammoId) {
        if (ammoId == null || ammoId.isBlank()) {
            source.sendFailure(Component.literal("弹药ID不能为空。"));
            return 0;
        }

        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        boolean existed = config.getEntry(ammoId) != null;
        config.addOrUpdate(
            ammoId,
            ExplosiveConfig.ExplosiveType.LAUNCHER,
            1.0, DEFAULT_FRAGMENTS, DEFAULT_PEN
        );
        config.saveConfig();

        final String action = existed ? "已更新" : "已添加";
        source.sendSuccess(() -> Component.literal(
            action + "榴弹，弹药ID：" + ammoId
                + "\n当量：1.0，碎片数量：" + DEFAULT_FRAGMENTS
                + "，弹片穿透：" + DEFAULT_PEN
                + "\n可在存档目录 fragmentation/explosives.json 修改。"
        ), true);
        return 1;
    }
}
