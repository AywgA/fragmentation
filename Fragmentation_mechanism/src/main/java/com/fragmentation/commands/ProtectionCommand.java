package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.DetailedHitZone;
import com.fragmentation.config.ProtectionConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 防具防护部位 / 属性覆盖命令（挂在 /fragmentation protection 下）。
 *
 * - zones 头部,胸部,...：手持防具，覆盖设置防护部位
 * - all：防护全部六个部位（头部/面部/胸部/腹部/左臂/右臂）
 * - clear：清除部位限制（恢复默认：防护全部位）
 * - armor &lt;等级&gt; &lt;耐久&gt; &lt;材料&gt;：覆盖该防具的护甲属性（null 时沿用 armors.json）
 * - armorreset：取消属性覆盖
 * - 无子命令：显示手持防具当前配置
 */
public class ProtectionCommand {

    /** 防具可配置的六个防护部位（用户约定，不含腿部）。 */
    private static final DetailedHitZone[] CONFIGURABLE = {
        DetailedHitZone.HEAD, DetailedHitZone.FACE, DetailedHitZone.CHEST,
        DetailedHitZone.ABDOMEN, DetailedHitZone.LEFT_ARM, DetailedHitZone.RIGHT_ARM
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("fragmentation")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("protection")
                    .then(Commands.literal("zones")
                        .then(Commands.argument("names", StringArgumentType.greedyString())
                            .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(
                                    new String[]{"head", "face", "chest", "abdomen", "left_arm", "right_arm", "all"},
                                    builder))
                            .executes(context -> setZones(
                                context.getSource().getPlayerOrException(),
                                StringArgumentType.getString(context, "names")))
                        )
                    )
                    .then(Commands.literal("all")
                        .executes(context -> setAll(context.getSource().getPlayerOrException()))
                    )
                    .then(Commands.literal("clear")
                        .executes(context -> clearZones(context.getSource().getPlayerOrException()))
                    )
                    .then(Commands.literal("armor")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1))
                            .then(Commands.argument("maxDurability", IntegerArgumentType.integer(1))
                                .then(Commands.argument("material", StringArgumentType.word())
                                    .executes(context -> setArmor(context.getSource().getPlayerOrException(),
                                        IntegerArgumentType.getInteger(context, "level"),
                                        IntegerArgumentType.getInteger(context, "maxDurability"),
                                        StringArgumentType.getString(context, "material")))
                                )
                            )
                        )
                    )
                    .then(Commands.literal("armorreset")
                        .executes(context -> resetArmor(context.getSource().getPlayerOrException()))
                    )
                    .executes(context -> info(context.getSource().getPlayerOrException()))
                )
        );
    }

    private static int setZones(Player player, String raw) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要配置的防具");
        }

        if ("all".equalsIgnoreCase(raw.trim())) {
            return setAll(player);
        }

        List<DetailedHitZone> zones = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String part : raw.split("[,，\\s]+")) {
            if (part.isBlank()) continue;
            DetailedHitZone z = DetailedHitZone.fromConfigName(part);
            if (z == null || !isConfigurable(z)) {
                invalid.add(part.trim());
            } else if (!zones.contains(z)) {
                zones.add(z);
            }
        }

        if (!invalid.isEmpty()) {
            return fail(player, "无法识别的部位：" + String.join("、", invalid) +
                "\n合法部位：head(头部) face(面部) chest(胸部) abdomen(腹部) left_arm(左臂) right_arm(右臂)，或 all");
        }
        if (zones.isEmpty()) {
            return fail(player, "未提供任何部位。合法值：head face chest abdomen left_arm right_arm，或 all");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        pc.setProtectedZones(held, zones);
        pc.saveConfig();

        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + held.getDisplayName().getString() + " §a的防护部位已设置：§f" +
                joinDisplayNames(zones) + "§r\n§7命中未防护部位时子弹将直接穿透§r"));
        return 1;
    }

    private static int setAll(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要配置的防具");
        }

        List<DetailedHitZone> all = new ArrayList<>(List.of(CONFIGURABLE));
        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        pc.setProtectedZones(held, all);
        pc.saveConfig();

        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + held.getDisplayName().getString() +
                " §a现在防护全部六个部位：头部/面部/胸部/腹部/左臂/右臂§r"));
        return 1;
    }

    private static int clearZones(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要配置的防具");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        ProtectionConfig.ProtectionEntry p = pc.findProtection(held);
        if (p != null) {
            p.protectedZones = new ArrayList<>();
        }
        pc.saveConfig();

        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + held.getDisplayName().getString() +
                " §a的部位限制已清除，恢复默认防护全部位§r"));
        return 1;
    }

    private static int setArmor(Player player, int level, int maxDurability, String material) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要配置的防具");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        ProtectionConfig.ProtectionEntry p = pc.getOrCreateProtection(held);
        p.armorLevel = level;
        p.maxDurability = maxDurability;
        p.material = material;
        pc.saveConfig();

        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + held.getDisplayName().getString() +
                " §a的护甲属性已覆盖：等级 " + level + "，耐久 " + maxDurability +
                "，材料 " + material + "§r"));
        return 1;
    }

    private static int resetArmor(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要配置的防具");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        ProtectionConfig.ProtectionEntry p = pc.findProtection(held);
        if (p != null) {
            p.armorLevel = null;
            p.maxDurability = null;
            p.material = null;
        }
        pc.saveConfig();

        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + held.getDisplayName().getString() +
                " §a的属性覆盖已取消，恢复使用 armors.json 中的属性§r"));
        return 1;
    }

    private static int info(Player player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(player, "主手需要持有要查看的防具");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        ProtectionConfig.ProtectionEntry p = pc.findProtection(held);

        StringBuilder sb = new StringBuilder();
        sb.append("§6[Fragmentation] 防具：§f").append(held.getDisplayName().getString()).append("§r\n");
        if (p == null || p.protectedZones == null || p.protectedZones.isEmpty()) {
            sb.append("§7防护部位：默认全部位（头部/面部/胸部/腹部/左臂/右臂）§r\n");
        } else {
            List<DetailedHitZone> zones = new ArrayList<>();
            for (String z : p.protectedZones) zones.add(DetailedHitZone.fromConfigName(z));
            sb.append("§7防护部位：§f").append(joinDisplayNames(zones)).append("§r\n");
        }
        if (p != null && (p.armorLevel != null || p.maxDurability != null || p.material != null)) {
            sb.append("§7属性覆盖：等级 ").append(p.armorLevel)
              .append("，耐久 ").append(p.maxDurability)
              .append("，材料 ").append(p.material).append("§r");
        } else {
            sb.append("§7属性：沿用 armors.json§r");
        }

        player.sendSystemMessage(Component.literal(sb.toString()));
        return 1;
    }

    private static boolean isConfigurable(DetailedHitZone z) {
        for (DetailedHitZone c : CONFIGURABLE) if (z == c) return true;
        return false;
    }

    private static String displayName(DetailedHitZone z) {
        return z.getDisplayName();
    }

    private static String joinDisplayNames(List<DetailedHitZone> zones) {
        List<String> names = new ArrayList<>();
        for (DetailedHitZone z : zones) names.add(displayName(z));
        return String.join("、", names);
    }

    private static int fail(Player player, String message) {
        player.sendSystemMessage(Component.literal("§c[Fragmentation] " + message + "§r"));
        return 0;
    }
}
