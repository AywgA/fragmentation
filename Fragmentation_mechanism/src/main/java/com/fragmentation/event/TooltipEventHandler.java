package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.config.GunConfig;
import com.fragmentation.config.ShieldConfig;
import com.fragmentation.config.PlateConfig;
import com.fragmentation.compat.FracturepointHelper;
import com.fragmentation.config.ShieldingFieldConfig;
import com.fragmentation.util.DurabilityUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import com.fragmentation.util.WorldCoverManager;

import java.util.List;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public class TooltipEventHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        try {
            ItemStack stack = event.getItemStack();
            if (stack.isEmpty()) return;

            List<Component> tooltips = event.getToolTip();

            // 屏蔽场激活物品：仅显示自定义名称 + 负层/主层负载
            if (isShieldingFieldActivator(stack)) {
                buildShieldingFieldTooltip(tooltips, stack);
                return;
            }

            if (rebuildExclusiveGunOrBulletTooltip(stack, tooltips)) {
                return;
            }
            
            boolean isTaczGun = isTaczGun(stack);

            // 物品是否为插板类：Fracturepoint 插板注册 ID 含 "_plate_"（如 ceramic_plate_level_iv）。
            // 注意 "chestplate" 不含 "_plate_"（是 "tplate"），不会误判。
            ResourceLocation itemKey = ForgeRegistries.ITEMS.getKey(stack.getItem());
            String itemIdStr = itemKey != null ? itemKey.toString() : "";
            boolean isPlateItem = itemIdStr.contains("_plate_");

            boolean isWearable = stack.getItem() instanceof net.minecraft.world.item.ArmorItem;
            ArmorConfig armorConfig = FragmentationMod.getArmorConfig();
            ArmorConfig.ArmorEntry armorEntry = isWearable
                ? armorConfig.getArmorForItem(stack)
                : null;

            // 插板 tooltip 优先级最高：先查 plate config，再查"插板被误加入 armor config"的情况
            PlateConfig.PlateEntry plateEntry =
                FragmentationMod.getPlateConfig().getPlateForItem(stack);
            if (plateEntry != null) {
                clearDefaultTooltips(tooltips);

                int currentDurability = DurabilityUtils.getCurrentDurability(stack);
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§a[防弹插板]§r")
                    .withStyle(ChatFormatting.GREEN));
                tooltips.add(Component.literal(
                    "  §7防弹等级: §a" + plateEntry.plateLevel
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7耐久度: §a" + currentDurability
                        + "§7/§a" + plateEntry.maxDurability
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7材料: §a" + plateEntry.getMaterial()
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7跳弹系数: §a"
                        + String.format("%.2f", plateEntry.ricochetCoefficient)
                ).withStyle(ChatFormatting.GRAY));
                addModFooter(tooltips);
                return;
            }

            // 插板类物品被加入了 armor config（而非 plate config）：仍按插板展示
            if (isPlateItem && armorEntry != null) {
                clearDefaultTooltips(tooltips);

                int currentDurability = DurabilityUtils.getCurrentDurability(stack);
                int maxDurability = armorEntry.maxDurability;
                String material = armorEntry.material != null ? armorEntry.material : "ceramic";

                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§a[防弹插板]§r")
                    .withStyle(ChatFormatting.GREEN));
                tooltips.add(Component.literal(
                    "  §7防弹等级: §a" + armorEntry.armorLevel
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7耐久度: §a" + currentDurability
                        + "§7/§a" + maxDurability
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7材料: §a" + material
                ).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7跳弹系数: §a"
                        + String.format("%.2f", armorEntry.ricochetCoefficient)
                ).withStyle(ChatFormatting.GRAY));
                addModFooter(tooltips);
                return;
            }

            if (stack.getItem() instanceof BlockItem blockItem) {
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(
                    blockItem.getBlock()
                );
                WorldCoverManager.CoverEntry cover =
                    WorldCoverManager.getCover(blockId);
                if (cover != null) {
                    clearDefaultTooltips(tooltips);
                    tooltips.add(Component.literal(""));
                    tooltips.add(Component.literal("§6[掩体属性]§r")
                        .withStyle(ChatFormatting.GOLD));
                    tooltips.add(Component.literal(
                        "  §7抗穿透阈值: §6"
                            + String.format("%.6f", cover.penetrationThreshold())
                    ).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal(
                        "  §7厚度系数: §6"
                            + String.format("%.6f", cover.thicknessCoefficient())
                    ).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal(
                        "  §7密度系数: §6"
                            + String.format("%.6f", cover.densityCoefficient())
                    ).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal(
                        "  §7跳弹系数: §6"
                            + String.format("%.2f", cover.ricochetCoefficient())
                    ).withStyle(ChatFormatting.GRAY));
                    addModFooter(tooltips);
                    return;
                }
            }

            if (armorEntry == null) {
                armorEntry = armorConfig.getArmorForItem(stack);
            }
            if (armorEntry != null) {
                clearDefaultTooltips(tooltips);
                
                int currentDurability = DurabilityUtils.getCurrentDurability(stack);
                int maxDurability = armorEntry.maxDurability;
                String material = armorEntry.material != null ? armorEntry.material : "steel";
                
                tooltips.add(Component.literal(""));
                tooltips.add(Component.literal("§6[护甲属性]§r").withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal("  §7防弹等级: §6" + armorEntry.armorLevel).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal("  §7耐久度: §6" + currentDurability + "§7/§6" + maxDurability).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal("  §7材料: §6" + material).withStyle(ChatFormatting.GRAY));
                tooltips.add(Component.literal(
                    "  §7跳弹系数: §6"
                        + String.format("%.2f", armorEntry.ricochetCoefficient)
                ).withStyle(ChatFormatting.GRAY));
                
                addPlateTooltipsFromPlateConfig(tooltips, stack, armorEntry.armorLevel);

                // 面罩耐久（NBT 优先，fallback 到 protection.json）
                addVisorDurabilityTooltip(tooltips, stack);

                // 防护部位
                addProtectionZonesTooltip(tooltips, stack);

                addModFooter(tooltips);
                return;
            }

            // 即使不在 armors.json 中，也检查面罩耐久和防护部位
            // （头盔可能只通过 /fragmentation visor add 登记了面罩）
            addVisorDurabilityTooltip(tooltips, stack);
            addProtectionZonesTooltip(tooltips, stack);
            
            if (!isTaczGun) {
                ShieldConfig shieldConfig = FragmentationMod.getShieldConfig();
                ShieldConfig.ShieldEntry shieldEntry = shieldConfig.getShieldForItem(stack);
                if (shieldEntry != null) {
                    clearDefaultTooltips(tooltips);
                    
                    int currentDurability = DurabilityUtils.getCurrentDurability(stack);
                    int maxDurability = shieldEntry.maxDurability;
                    String material = shieldEntry.material != null ? shieldEntry.material : "steel";
                    
                    tooltips.add(Component.literal(""));
                    tooltips.add(Component.literal("§6[护盾属性]§r").withStyle(ChatFormatting.GOLD));
                    tooltips.add(Component.literal("  §7防弹等级: §6" + shieldEntry.armorLevel).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal("  §7耐久度: §6" + currentDurability + "§7/§6" + maxDurability).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal("  §7材料: §6" + material).withStyle(ChatFormatting.GRAY));
                    tooltips.add(Component.literal(
                        "  §7跳弹系数: §6"
                            + String.format("%.2f", shieldEntry.ricochetCoefficient)
                    ).withStyle(ChatFormatting.GRAY));
                    
                    addPlateTooltipsFromPlateConfig(tooltips, stack, shieldEntry.armorLevel);
                    
                    addModFooter(tooltips);
                    return;
                }
            }
            
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[TooltipEventHandler] Error processing tooltip event", e);
        }
    }

    private static boolean isShieldingFieldActivator(ItemStack stack) {
        try {
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) return false;
            String itemId = key.toString();
            // 优先使用服务端同步到客户端的激活物品列表
            try {
                Class<?> renderer = Class.forName(
                    "com.fragmentation.client.ClientShieldingFieldRenderer");
                Object result = renderer.getMethod("isActivatorItem", String.class)
                    .invoke(null, itemId);
                if (result instanceof Boolean matched && matched) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            // 后备：本地配置（单人游戏同 JVM 时可用）
            return ShieldingFieldConfig.getInstance().isActivatorItem(itemId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 屏蔽场发生器物品的 tooltip：
     * 数据直接来自物品 NBT（随物品同步，盔甲架/背包/地面均可显示），
     * 显示顺序由外到内：次层 → 主层 → 负层；
     * 主/次碎除时只显示剩余的负层；完全失效时显示"负载过高，已失效"+恢复倒计时。
     */
    @SuppressWarnings("unchecked")
    private static void buildShieldingFieldTooltip(List<Component> tooltips, ItemStack stack) {
        tooltips.clear();
        try {
            Class<?> renderer = Class.forName(
                "com.fragmentation.client.ClientShieldingFieldRenderer");
            Object result = renderer.getMethod("fieldTooltipLines", ItemStack.class)
                .invoke(null, stack);
            if (result instanceof List<?> lines && !lines.isEmpty()) {
                for (Object line : lines) {
                    if (line instanceof Component component) {
                        tooltips.add(component);
                    }
                }
                return;
            }
        } catch (Throwable ignored) {
            // 专用服务端无客户端类，退化为最小显示
        }
        tooltips.add(Component.literal(ShieldingFieldConfig.getInstance().displayName)
            .withStyle(ChatFormatting.GOLD));
    }

    public static boolean rebuildExclusiveGunOrBulletTooltip(ItemStack stack, List<Component> tooltips) {
        if (stack == null || stack.isEmpty() || tooltips == null) {
            return false;
        }

        // 穿戴装备（护甲类物品或已配置为护甲的物品）永远不按子弹处理：
        // 防止历史脏数据（如把弹挂误加进 bullets.json）劫持护甲/普通 tooltip
        boolean isWearable = stack.getItem() instanceof net.minecraft.world.item.ArmorItem
            || FragmentationMod.getArmorConfig().getArmorForItem(stack) != null;

        if (!isWearable) {
            BulletConfig.BulletEntry bulletEntry = findBulletEntry(stack, FragmentationMod.getBulletConfig());
            if (bulletEntry != null) {
                tooltips.clear();
                tooltips.add(stack.getHoverName().copy());
                tooltips.add(Component.literal("基础穿深: " + bulletEntry.penetrationLevel).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal("基础甲伤: " + bulletEntry.armorDamage).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal("基础伤害: " + bulletEntry.baseDamage).withStyle(ChatFormatting.GOLD));
                String bulletSpeed = bulletEntry.bulletSpeed != null
                    ? String.format("%.2f m/s", bulletEntry.bulletSpeed)
                    : "沿用 TACZ";
                tooltips.add(Component.literal("子弹速度: " + bulletSpeed).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal(
                    "武器 MOA 影响: " + String.format("%+.2f MOA", bulletEntry.moaModifier)
                ).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal(
                    "弹头重量: " + String.format("%.3f g", bulletEntry.bulletMass * 1000.0D)
                ).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal(
                    "跳弹率: " + String.format("%.2f%%", bulletEntry.ricochetRate * 100.0D)
                ).withStyle(ChatFormatting.GOLD));
                tooltips.add(Component.literal(
                    "碎弹率: " + String.format("%.2f%%", bulletEntry.fragmentationRate * 100.0D)
                ).withStyle(ChatFormatting.GOLD));
                return true;
            }
        }

        String gunId = getTaczGunId(stack);
        if (!isTaczGun(stack) || gunId == null) {
            return false;
        }

        GunConfig gunConfig = GunConfig.getInstanceFresh();
        if (gunConfig == null) {
            return false;
        }

        tooltips.clear();
        tooltips.add(stack.getHoverName().copy());
        tooltips.add(Component.literal("激发威力: " + gunConfig.getPowerLevel(gunId).getDisplayName())
            .withStyle(ChatFormatting.GOLD));

        Float baseDamageOverride = gunConfig.getBaseDamageOverride(gunId);
        if (baseDamageOverride != null) {
            tooltips.add(Component.literal("基础伤害: " + baseDamageOverride).withStyle(ChatFormatting.GOLD));
        }

        tooltips.add(Component.literal("MOA: " + String.format("%.2f", TaczMoaHandler.getAdjustedAdsMoa(stack, gunId)))
            .withStyle(ChatFormatting.GOLD));
        tooltips.add(Component.literal("口径: " + gunConfig.getCaliber(gunId))
            .withStyle(ChatFormatting.GOLD));
        Float muzzleVelocity = gunConfig.getMuzzleVelocityOverride(gunId);
        String muzzleVelocityText = muzzleVelocity != null
            ? String.format("%.2f m/s", muzzleVelocity)
            : "沿用 TACZ";
        tooltips.add(Component.literal("枪膛初速: " + muzzleVelocityText)
            .withStyle(ChatFormatting.GOLD));

        // 追加四项枪械属性（垂直/水平后坐力、人机功效、持枪稳定度）
        // 与已装配件明细。本独占重建是 tooltip 的最终显示来源——
        // ItemTooltipEvent 阶段与 RenderTooltipEvent.GatherComponents
        // 阶段都会先 clear 再走这里，GunTooltipHandler 在
        // ItemTooltipEvent 里追加的属性行会被本处 clear 清掉，
        // 因此必须在此并入，任何界面才能实际看到。
        try {
            com.fragmentation.client.GunTooltipHandler.appendGunStatsLines(
                tooltips, stack);
        } catch (Throwable ignored) {
            // 极端环境下（TACZ 异常）不影响独占 tooltip 本体
        }
        return true;
    }
    
    private static BulletConfig.BulletEntry findBulletEntry(ItemStack stack, BulletConfig bulletConfig) {
        CompoundTag tag = stack.getTag();

        String bulletNbtAmmoId = null;
        if (tag != null) {
            if (tag.contains("AmmoId")) {
                bulletNbtAmmoId = tag.getString("AmmoId");
            } else if (tag.contains("ammoId")) {
                bulletNbtAmmoId = tag.getString("ammoId");
            }

            if (bulletNbtAmmoId == null && tag.contains("Extras")) {
                CompoundTag extras = tag.getCompound("Extras");
                if (extras.contains("AmmoId")) {
                    bulletNbtAmmoId = extras.getString("AmmoId");
                } else if (extras.contains("ammoId")) {
                    bulletNbtAmmoId = extras.getString("ammoId");
                }
            }
        }

        if (bulletNbtAmmoId != null && !bulletNbtAmmoId.isEmpty()) {
            // 有 AmmoId：只按 AmmoId 精确匹配（内部已无注册ID/显示名/模糊回退）
            return bulletConfig.getBulletByTaczAmmoId(bulletNbtAmmoId);
        }

        // 无 AmmoId：仅按注册物品 ID 精确匹配。
        // 不按显示名匹配：同一模组的不同变体物品常显示相同名称
        // （如各色"猎头者t3弹挂"），按名匹配会把弹挂误识别成子弹。
        return bulletConfig.getBulletById(getItemId(stack));
    }
    
    private static void addPlateTooltipsFromPlateConfig(List<Component> tooltips, ItemStack stack, int fallbackLevel) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("PlateData")) {
            return;
        }

        CompoundTag plateData = tag.getCompound("PlateData");
        PlateConfig plateConfig = FragmentationMod.getPlateConfig();

        StringBuilder plateInfo = new StringBuilder();
        boolean hasContent = false;

        if (plateData.contains("FrontPlate")) {
            CompoundTag frontPlate = plateData.getCompound("FrontPlate");
            if (frontPlate.contains("Tier")) {
                float cur = frontPlate.contains("CurrentDurability") ? frontPlate.getFloat("CurrentDurability") : 0;

                PlateConfig.PlateEntry configEntry = findPlateConfigFromNbt(frontPlate, plateConfig);
                float max = FracturepointHelper.getPlateMaxDurability(frontPlate);
                // 统一用 FracturepointHelper 的等级解析（plate config → 插板自身 armor config → fallback）
                int level = FracturepointHelper.getPlateLevel(frontPlate, fallbackLevel);

                if (max > 0) {
                    hasContent = true;
                    plateInfo.append("前插板: Lv").append(level)
                      .append(" (").append(String.format("%.0f", cur))
                      .append("/").append(String.format("%.0f", max)).append(")");
                    if (configEntry != null) {
                        plateInfo.append(" 跳弹系数:")
                            .append(String.format(
                                "%.2f",
                                configEntry.ricochetCoefficient
                            ));
                    }
                }
            }
        }

        if (plateData.contains("BackPlate")) {
            CompoundTag backPlate = plateData.getCompound("BackPlate");
            if (backPlate.contains("Tier")) {
                float cur = backPlate.contains("CurrentDurability") ? backPlate.getFloat("CurrentDurability") : 0;

                PlateConfig.PlateEntry configEntry = findPlateConfigFromNbt(backPlate, plateConfig);
                float max = FracturepointHelper.getPlateMaxDurability(backPlate);
                int level = FracturepointHelper.getPlateLevel(backPlate, fallbackLevel);
                
                if (max > 0) {
                    hasContent = true;
                    if (plateInfo.length() > 0) plateInfo.append(", ");
                    plateInfo.append("后插板: Lv").append(level)
                      .append(" (").append(String.format("%.0f", cur))
                      .append("/").append(String.format("%.0f", max)).append(")");
                    if (configEntry != null) {
                        plateInfo.append(" 跳弹系数:")
                            .append(String.format(
                                "%.2f",
                                configEntry.ricochetCoefficient
                            ));
                    }
                }
            }
        }
        
        if (hasContent) {
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("  §a[防弹插板]§r").withStyle(ChatFormatting.GREEN));
            tooltips.add(Component.literal("    §7" + plateInfo).withStyle(ChatFormatting.GRAY));
        }
    }
    
    private static PlateConfig.PlateEntry findPlateConfigFromNbt(CompoundTag plateNbt, PlateConfig plateConfig) {
        return plateConfig != null
            ? FracturepointHelper.getPlateConfigEntry(plateNbt)
            : null;
    }
    
    private static int tierToLevel(String tierName) {
        if (tierName == null) return 0;
        switch (tierName) {
            case "LEVEL_IIA": return 1;
            case "LEVEL_II": return 2;
            case "LEVEL_IIIA": return 3;
            case "LEVEL_III": return 4;
            case "LEVEL_IV": return 5;
            default: return 0;
        }
    }
    
    /**
     * 头盔固定面罩耐久 tooltip。优先从 NBT 读取（当前/最大），
     * NBT 无值时 fallback 到 protection.json 的 builtin 登记（显示满耐久）。
     * 同时检测面甲开合状态（helmet_top_open）。
     */
    private static void addVisorDurabilityTooltip(List<Component> tooltips, ItemStack stack) {
        CompoundTag tag = stack.getTag();

        int cur = -1;
        int max = 0;

        // 1) NBT 优先
        if (tag != null && tag.contains("fragmentation_visor_durability")) {
            cur = tag.getInt("fragmentation_visor_durability");
            max = tag.contains("fragmentation_visor_max_durability")
                ? tag.getInt("fragmentation_visor_max_durability") : 0;
        }

        // 2) NBT 无值时 fallback 到 protection.json
        if (cur < 0) {
            try {
                com.fragmentation.config.ProtectionConfig pc =
                    FragmentationMod.getProtectionConfig();
                if (pc != null) {
                    com.fragmentation.config.ProtectionConfig.HelmetVisorEntry builtin =
                        pc.findBuiltinVisorEntry(stack);
                    if (builtin != null && builtin.builtinMaxDurability != null) {
                        cur = builtin.builtinMaxDurability;
                        max = builtin.builtinMaxDurability;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (cur < 0) return;

        boolean open = tag != null && tag.getBoolean("helmet_top_open");

        tooltips.add(Component.literal(""));
        if (open) {
            tooltips.add(Component.literal("  §c[面甲已打开：面部无防护]§r")
                .withStyle(ChatFormatting.RED));
        }
        String durText = max > 0
            ? "§b面罩耐久: §f" + Math.max(0, cur) + "§7/§f" + max
            : "§b面罩耐久: §f" + Math.max(0, cur);
        tooltips.add(Component.literal(durText).withStyle(ChatFormatting.AQUA));
    }

    /**
     * 防护部位 tooltip。从 protection.json 读取该防具的防护部位列表。
     * 未登记或部位列表为空时不显示（默认防护全部位）。
     */
    private static void addProtectionZonesTooltip(List<Component> tooltips, ItemStack stack) {
        try {
            com.fragmentation.config.ProtectionConfig pc =
                FragmentationMod.getProtectionConfig();
            if (pc == null) return;
            com.fragmentation.config.ProtectionConfig.ProtectionEntry p =
                pc.findProtection(stack);
            if (p == null || p.protectedZones == null || p.protectedZones.isEmpty()) {
                return;
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String z : p.protectedZones) {
                com.fragmentation.combat.DetailedHitZone zone =
                    com.fragmentation.combat.DetailedHitZone.fromConfigName(z);
                names.add(zone != null ? zone.getDisplayName() : z);
            }
            tooltips.add(Component.literal(
                "  §b防护部位: §f" + String.join("§7、§f", names))
                .withStyle(ChatFormatting.AQUA));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isTaczGun(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        
        if (tag.contains("Extras")) {
            CompoundTag extras = tag.getCompound("Extras");
            if (extras.contains("GunId")) {
                return true;
            }
        }
        
        if (tag.contains("GunId")) {
            return true;
        }
        
        return false;
    }
    
    private static String getTaczGunId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        
        if (tag.contains("Extras")) {
            CompoundTag extras = tag.getCompound("Extras");
            if (extras.contains("GunId")) {
                return extras.getString("GunId");
            }
        }
        
        if (tag.contains("GunId")) {
            return tag.getString("GunId");
        }
        
        return null;
    }
    
    private static String getItemId(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null ? itemId.toString() : "unknown";
    }
    
    private static void clearDefaultTooltips(List<Component> tooltips) {
        if (tooltips == null || tooltips.isEmpty()) return;
        
        for (int i = tooltips.size() - 1; i >= 0; i--) {
            if (i == 0) {
                continue;
            }
            tooltips.remove(i);
        }
    }

    private static void addModFooter(List<Component> tooltips) {
        tooltips.add(Component.literal(""));
        tooltips.add(Component.literal("§8[Fragmentation Mechanism]§r")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
