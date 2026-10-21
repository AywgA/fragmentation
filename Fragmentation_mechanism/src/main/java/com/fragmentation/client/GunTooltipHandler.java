package com.fragmentation.client;

import com.fragmentation.FragmentationMod;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

/**
 * 枪械/配件 tooltip：参考《暗区突围：无限》的属性面板。
 *
 * 枪械悬浮提示：显示四项属性（垂直/水平后坐力、人机功效、持枪
 * 稳定度，均为越高越好），数值为"含配件加成"的最终值，与基础值
 * 不同时括号标注配件总增量（提升绿色/降低红色）；下方逐个列出
 * 已装配件及各自的加成增量明细。
 *
 * 配件悬浮提示：显示该配件在 gun_recoil.json attachments 段配置的
 * 属性加成（只列非零项）；未配置时提示如何添加配置。
 */
@Mod.EventBusSubscriber(
    modid = FragmentationMod.MOD_ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class GunTooltipHandler {
    /** 配件槽位中文名。 */
    private static final Map<AttachmentType, String> SLOT_NAMES = Map.of(
        AttachmentType.SCOPE, "瞄具",
        AttachmentType.MUZZLE, "枪口",
        AttachmentType.STOCK, "枪托",
        AttachmentType.GRIP, "握把",
        AttachmentType.LASER, "激光",
        AttachmentType.EXTENDED_MAG, "弹匣",
        AttachmentType.NONE, "配件"
    );
    /** 属性标签顺序与 GunStats record 字段一致。 */
    private static final String[] STAT_NAMES = {
        "垂直后坐力", "水平后坐力", "人机功效", "持枪稳定度"
    };
    private static final String[] STAT_SHORT = {"垂直", "水平", "人机", "稳定"};

    /** 仅用于日志确认新版本已加载（首次悬停枪械时打印一次）。 */
    private static boolean activated;

    private GunTooltipHandler() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.getItem() instanceof IGun gun) {
            if (!activated) {
                activated = true;
                FragmentationMod.LOGGER.info(
                    "[{}] 枪械属性 Tooltip 已激活（本版本含配件属性显示）",
                    FragmentationMod.MOD_ID);
            }
            appendGunTooltip(event.getToolTip(), gun, stack);
        } else if (stack.getItem() instanceof IAttachment attachment) {
            appendAttachmentTooltip(event.getToolTip(), attachment, stack);
        }
    }

    // ==================== 枪械 ====================

    /**
     * 公共入口：向任意行列表追加"四项枪械属性 + 已装配件明细"。
     *
     * 供 TooltipEventHandler 的独占枪械 tooltip 重建复用——独占重建
     * 是显示的最终来源（ItemTooltipEvent 与 RenderTooltipEvent
     * .GatherComponents 两处都走它，且会先 clear 整个列表），因此
     * 属性行必须并入该流程才会实际显示。
     */
    public static void appendGunStatsLines(List<Component> tip, ItemStack stack) {
        if (stack.getItem() instanceof IGun gun) {
            appendGunTooltip(tip, gun, stack);
        }
    }

    private static void appendGunTooltip(
        List<Component> tip, IGun gun, ItemStack stack
    ) {
        String gunId = gun.getGunId(stack).toString();
        GunRecoilConfig.GunStats base = GunRecoilConfig.get(gunId);
        GunRecoilConfig.GunStats fin = GunRecoilConfig.get(stack);

        tip.add(Component.empty());
        tip.add(Component.literal("◆ 枪械属性（数值越高越好）")
            .withStyle(ChatFormatting.GRAY));
        addStat(tip, 0, base.vertical(), fin.vertical());
        addStat(tip, 1, base.horizontal(), fin.horizontal());
        addStat(tip, 2, base.ergonomics(), fin.ergonomics());
        addStat(tip, 3, base.stability(), fin.stability());

        // 已装配件与各自加成明细（按 TACZ 槽位顺序遍历）
        boolean headerAdded = false;
        for (AttachmentType type : AttachmentType.values()) {
            if (type == AttachmentType.NONE) {
                continue;
            }
            ResourceLocation id = gun.getAttachmentId(stack, type);
            if (id == null) {
                continue;
            }
            if (!headerAdded) {
                tip.add(Component.empty());
                tip.add(Component.literal("◆ 已装配件")
                    .withStyle(ChatFormatting.GRAY));
                headerAdded = true;
            }
            tip.add(attachmentLine(type, id));
        }
    }

    /** 单条属性行：最终值白/绿/红，配件改变时括号标注总增量。 */
    private static void addStat(
        List<Component> tip, int index, int base, int fin
    ) {
        MutableComponent line = Component.literal(
                " " + STAT_NAMES[index] + "  ")
            .withStyle(ChatFormatting.GRAY);
        if (fin == base) {
            line.append(Component.literal(String.valueOf(fin))
                .withStyle(ChatFormatting.WHITE));
        } else {
            ChatFormatting color = fin > base
                ? ChatFormatting.GREEN
                : ChatFormatting.RED;
            int delta = fin - base;
            line.append(Component.literal(String.valueOf(fin))
                .withStyle(color));
            line.append(Component.literal("  (" + (delta > 0 ? "+" : "")
                + delta + " 配件)").withStyle(color));
        }
        tip.add(line);
    }

    /** 单条配件行：槽位 + 配件名 + 该配件的非零属性增量。 */
    private static Component attachmentLine(
        AttachmentType type, ResourceLocation id
    ) {
        String name = TimelessAPI.getClientAttachmentIndex(id)
            .map(idx -> idx.getName())
            .orElse(id.toString());
        MutableComponent line = Component.literal(
                "  [" + SLOT_NAMES.getOrDefault(type, "配件") + "] ")
            .withStyle(ChatFormatting.DARK_GRAY);
        line.append(Component.literal(name).withStyle(ChatFormatting.AQUA));
        appendBonus(line, GunRecoilConfig.getAttachmentBonus(id.toString()));
        return line;
    }

    // ==================== 配件 ====================

    private static void appendAttachmentTooltip(
        List<Component> tip, IAttachment attachment, ItemStack stack
    ) {
        ResourceLocation id = attachment.getAttachmentId(stack);
        if (id == null) {
            return;
        }
        GunRecoilConfig.GunStats bonus =
            GunRecoilConfig.getAttachmentBonus(id.toString());
        tip.add(Component.empty());
        tip.add(Component.literal("◆ 配件属性（数值越高越好）")
            .withStyle(ChatFormatting.GRAY));
        if (bonus == null || isZero(bonus)) {
            tip.add(Component.literal(
                    "  未配置本模组加成：可在 gun_recoil.json 的"
                        + " attachments 段添加 \"" + id + "\"")
                .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        int[] vals = {
            bonus.vertical(), bonus.horizontal(),
            bonus.ergonomics(), bonus.stability()
        };
        for (int i = 0; i < vals.length; i++) {
            int v = vals[i];
            if (v == 0) {
                continue;
            }
            tip.add(Component.literal(" " + STAT_NAMES[i] + "  ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal((v > 0 ? "+" : "") + v)
                    .withStyle(v > 0
                        ? ChatFormatting.GREEN
                        : ChatFormatting.RED)));
        }
    }

    private static boolean isZero(GunRecoilConfig.GunStats s) {
        return s.vertical() == 0 && s.horizontal() == 0
            && s.ergonomics() == 0 && s.stability() == 0;
    }

    /** 把配件的非零增量追加到行尾：提升绿色、降低红色。 */
    private static void appendBonus(
        MutableComponent line, GunRecoilConfig.GunStats bonus
    ) {
        if (bonus == null) {
            return;
        }
        int[] vals = {
            bonus.vertical(), bonus.horizontal(),
            bonus.ergonomics(), bonus.stability()
        };
        for (int i = 0; i < vals.length; i++) {
            int v = vals[i];
            if (v == 0) {
                continue;
            }
            line.append(Component.literal("  " + STAT_SHORT[i]
                    + (v > 0 ? "+" : "") + v)
                .withStyle(v > 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
    }
}
