package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.ProtectionConfig;
import com.fragmentation.util.DurabilityUtils;
import com.fragmentation.util.FacewearHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 防弹面罩命令（/fragmentation visor），仅两条核心操作：
 *
 * - add [耐久] [等级] [材料]：给头戴的头盔添加固定面罩防护。头盔成为完整体
 *   （如给下界合金头盔添加后也具备面部防护）。耐久/等级/材料缺省沿用 armors.json。
 *   面甲可开关的头盔（如 killa_helmet）自动检测 helmet_top_open。
 * - remove：移除该头盔的面罩防护登记。
 * - 无子命令：查看当前状态。
 *
 * 另：Curios 面部配饰（facewear 槽，如 mm:black_visor）无需登记，自动生效。
 */
public class VisorCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // add 子命令（0~3 个参数，逐级嵌套）
        LiteralArgumentBuilder<CommandSourceStack> addNode = Commands.literal("add");
        addNode.executes(ctx -> add(
            ctx.getSource().getPlayerOrException(), 0, null, null));
        addNode.then(Commands.argument("maxDurability", IntegerArgumentType.integer(1))
            .executes(ctx -> add(
                ctx.getSource().getPlayerOrException(),
                IntegerArgumentType.getInteger(ctx, "maxDurability"), null, null))
            .then(Commands.argument("level", IntegerArgumentType.integer(1))
                .executes(ctx -> add(
                    ctx.getSource().getPlayerOrException(),
                    IntegerArgumentType.getInteger(ctx, "maxDurability"),
                    IntegerArgumentType.getInteger(ctx, "level"), null))
                .then(Commands.argument("material", StringArgumentType.word())
                    .executes(ctx -> add(
                        ctx.getSource().getPlayerOrException(),
                        IntegerArgumentType.getInteger(ctx, "maxDurability"),
                        IntegerArgumentType.getInteger(ctx, "level"),
                        StringArgumentType.getString(ctx, "material"))))));

        dispatcher.register(Commands.literal("fragmentation")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("visor")
                .then(addNode)
                .then(Commands.literal("remove")
                    .executes(ctx -> remove(ctx.getSource().getPlayerOrException())))
                .executes(ctx -> info(ctx.getSource().getPlayerOrException()))));
    }

    /**
     * 添加固定面罩防护。头盔成为完整体，具备面部防护效果。
     * 耐久存头盔 NBT（fragmentation_visor_durability）。
     * 面甲可开关的头盔自动检测 helmet_top_open。
     */
    private static int add(Player player, int maxDurability, Integer level, String material) {
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) {
            return fail(player, "需要先在头部装备头盔");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();

        // 耐久/等级/材料缺省时沿用 armors.json
        ArmorConfig.ArmorEntry helmetEntry = FragmentationMod.getArmorConfig().getArmorForItem(helmet);
        int finalDur = maxDurability > 0 ? maxDurability
            : (helmetEntry != null && helmetEntry.maxDurability > 0 ? helmetEntry.maxDurability : 30);
        Integer finalLevel = level;
        String finalMaterial = material;

        if (finalLevel == null && helmetEntry != null) {
            finalLevel = helmetEntry.armorLevel;
        }
        if (finalMaterial == null && helmetEntry != null) {
            finalMaterial = helmetEntry.getMaterial();
        }

        pc.registerBuiltinVisor(helmet, finalDur, finalLevel, finalMaterial);

        // 初始化耐久（已存在则保留当前值，不重置）
        CompoundTag tag = helmet.getOrCreateTag();
        if (!tag.contains(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY)) {
            tag.putInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY, finalDur);
        }
        tag.putInt(ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY, finalDur);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
        }
        pc.saveConfig();

        boolean openable = helmet.hasTag() && helmet.getTag().contains("helmet_top_open");
        player.sendSystemMessage(Component.literal(
            "§a[Fragmentation] §f" + helmet.getDisplayName().getString() + " §a已添加固定面罩防护§r\n" +
            "§7面罩耐久 " + finalDur +
            (finalLevel != null ? "，等级 " + finalLevel : "") +
            (finalMaterial != null ? "，材料 " + finalMaterial : "") + "§r\n" +
            (openable ? "§7面甲打开时面部将失去防护，关闭时恢复§r"
                      : "§7面甲始终关闭，面部持续防护§r")));
        return 1;
    }

    /** 移除头盔的面罩防护登记。 */
    private static int remove(Player player) {
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) {
            return fail(player, "需要先在头部装备头盔");
        }

        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        if (pc.removeBuiltinVisor(helmet)) {
            helmet.getOrCreateTag().remove(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY);
            helmet.getOrCreateTag().remove(ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY);
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
            }
            pc.saveConfig();
            player.sendSystemMessage(Component.literal(
                "§a[Fragmentation] 已移除 §f" + helmet.getDisplayName().getString() + " §a的面罩防护§r"));
        } else {
            player.sendSystemMessage(Component.literal(
                "§c[Fragmentation] 该头盔没有面罩防护登记§r"));
        }
        return 1;
    }

    /** 查看面部防护状态。 */
    private static int info(Player player) {
        ProtectionConfig pc = FragmentationMod.getProtectionConfig();
        StringBuilder sb = new StringBuilder();
        sb.append("§6[Fragmentation] 面部防护状态§r\n");

        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        if (helmet.isEmpty()) {
            sb.append("§7头部：未装备头盔§r\n");
        } else {
            sb.append("§7头盔：§f").append(helmet.getDisplayName().getString());
            boolean open = helmet.hasTag() && helmet.getTag().getBoolean("helmet_top_open");
            sb.append(open ? " §c[面甲打开：面部无防护]§r" : " §a[面甲关闭]§r").append("§r\n");

            ProtectionConfig.HelmetVisorEntry builtin = pc.findBuiltinVisorEntry(helmet);
            if (builtin != null) {
                CompoundTag tag = helmet.hasTag() ? helmet.getTag() : new CompoundTag();
                int cur = tag.contains(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY)
                    ? tag.getInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY)
                    : builtin.builtinMaxDurability;
                sb.append("§a固定面罩：§7耐久 §f").append(Math.max(0, cur)).append("/")
                  .append(builtin.builtinMaxDurability);
                if (builtin.builtinLevel != null) sb.append("§7，等级 §f").append(builtin.builtinLevel);
                if (builtin.builtinMaterial != null) sb.append("§7，材料 §f").append(builtin.builtinMaterial);
                sb.append("§r\n");
            } else {
                sb.append("§7固定面罩：未登记（/fragmentation visor add 添加）§r\n");
            }
        }

        // 已穿戴的 Curios 面部配饰
        if (FacewearHelper.curiosLoaded()) {
            FacewearHelper.WornVisor worn = FacewearHelper.findWornVisor(player,
                s -> pc.findVisorItem(s) != null
                    || FragmentationMod.getArmorConfig().getArmorForItem(s) != null,
                s -> pc.findVisorItem(s) != null);
            if (worn != null) {
                ItemStack w = worn.stack();
                int cur = DurabilityUtils.getCurrentDurability(w);
                int max = Math.max(1, DurabilityUtils.getMaxDurability(w));
                sb.append("§a面部配饰：§f").append(w.getDisplayName().getString())
                  .append("§r §7(").append(cur).append("/").append(max).append("）§r");
            } else {
                sb.append("§7面部配饰：未穿戴§r");
            }
        } else {
            sb.append("§7面部配饰：Curios 模组未安装§r");
        }

        player.sendSystemMessage(Component.literal(sb.toString()));
        return 1;
    }

    private static int fail(Player player, String message) {
        player.sendSystemMessage(Component.literal("§c[Fragmentation] " + message + "§r"));
        return 0;
    }
}
