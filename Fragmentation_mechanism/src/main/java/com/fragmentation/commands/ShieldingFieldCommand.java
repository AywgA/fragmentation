package com.fragmentation.commands;

import com.fragmentation.config.ShieldingFieldConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 屏蔽场管理命令：
 * /shieldfield add          将手持物品设为屏蔽场激活装备
 * /shieldfield remove <id>  移除激活物品
 * /shieldfield list         列出激活物品
 * /shieldfield reload       重载配置
 */
public class ShieldingFieldCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("shieldfield")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                    .executes(context -> addHeld(context.getSource())))
                .then(Commands.literal("remove")
                    .then(Commands.argument("itemId", StringArgumentType.string())
                        .executes(context -> remove(
                            context.getSource(),
                            StringArgumentType.getString(context, "itemId")))))
                .then(Commands.literal("list")
                    .executes(context -> list(context.getSource())))
                .then(Commands.literal("reload")
                    .executes(context -> reload(context.getSource())))
        );
    }

    private static int addHeld(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Player player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("你必须手持一个物品！"));
            return 0;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (key == null) {
            source.sendFailure(Component.literal("无法识别该物品。"));
            return 0;
        }
        String itemId = key.toString();
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        if (config.isActivatorItem(itemId)) {
            source.sendSuccess(() -> Component.literal("该物品已是屏蔽场激活装备: " + itemId), true);
            return 1;
        }
        config.addActivatorItem(itemId);
        final String id = itemId;
        source.sendSuccess(() -> Component.literal(
            "已将 " + held.getDisplayName().getString() + " (" + id + ") 设为屏蔽场激活装备。\n"
                + "穿戴（盔甲槽）或手持该物品即可激活三层屏蔽场。"), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, String itemId) {
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        if (config.removeActivatorItem(itemId)) {
            source.sendSuccess(() -> Component.literal("已移除屏蔽场激活物品: " + itemId), true);
            return 1;
        }
        source.sendFailure(Component.literal("未找到激活物品: " + itemId));
        return 0;
    }

    private static int list(CommandSourceStack source) {
        ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
        if (config.activatorItems.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "当前没有屏蔽场激活物品。手持物品执行 /shieldfield add 添加。"), true);
            return 1;
        }
        StringBuilder sb = new StringBuilder("屏蔽场激活物品列表：\n");
        for (String id : config.activatorItems) {
            sb.append(" - ").append(id).append("\n");
        }
        sb.append("负层容量: ").append((long) config.negativeLayerCapacityJoules).append("J, ");
        sb.append("主层容量: ").append((long) config.mainLayerCapacityJoules).append("J, ");
        sb.append("次层削减: ").append((int) (config.secondaryLayerReduction * 100)).append("%");
        String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        ShieldingFieldConfig.reload();
        source.sendSuccess(() -> Component.literal("屏蔽场配置已从 shielding_field.json 重载。"), true);
        return 1;
    }
}
