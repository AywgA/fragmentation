package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ProtectionConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.AnvilRepairEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 铁砧修复事件处理器：当头盔在铁砧中被修复时，同步恢复内置面罩的耐久。
 *
 * 原版铁砧只修复物品的 vanilla Damage tag，不会修改我们存储在 NBT 中的
 * fragmentation_visor_durability。AnvilUpdateEvent 只在配方计算时触发，
 * 玩家实际取出物品时 Forge 会重新计算输出，导致之前的修改被覆盖。
 * 本处理器使用 AnvilRepairEvent（玩家取出时触发），在最终结算时修改物品。
 */
public class AnvilRepairHandler {

    /**
     * 监听铁砧修复完成事件（玩家从铁砧取出物品时触发）。
     * 当修复的物品是包含面罩耐久数据的头盔时，同步恢复面罩耐久。
     */
    @SubscribeEvent
    public static void onAnvilRepair(AnvilRepairEvent event) {
        ItemStack left = event.getLeft();
        if (left.isEmpty()) return;

        CompoundTag leftTag = left.getTag();
        if (leftTag == null) return;

        // 检查左槽是否有内置面罩耐久数据
        if (!leftTag.contains(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY)) {
            return;
        }

        int visorMax = leftTag.getInt(ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY);
        int visorCur = leftTag.getInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY);
        if (visorMax <= 0) return;

        // 获取修复后的输出物品
        ItemStack output = event.getOutput();
        if (output.isEmpty()) return;

        // 计算修复比例：基于原版 Damage tag 的恢复量
        int vanillaMaxDamage = left.getMaxDamage();
        int vanillaCurDamage = left.getDamageValue();
        int outputDamage = output.getDamageValue();

        if (vanillaMaxDamage <= 0) return;

        // 如果输出物品 Damage 没变化，说明不是修复操作
        if (outputDamage >= vanillaCurDamage) return;

        float repairRatio = (float) (vanillaCurDamage - outputDamage) / (float) vanillaMaxDamage;

        // 按比例恢复面罩耐久
        int visorRepair = (int) Math.ceil(visorMax * repairRatio);
        int newVisorDur = Math.min(visorMax, visorCur + visorRepair);

        // 如果面罩已经满了，不需要修改
        if (newVisorDur <= visorCur) return;

        // 修改输出物品的 NBT
        CompoundTag outTag = output.getOrCreateTag();
        outTag.putInt(ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY, newVisorDur);
        outTag.putInt(ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY, visorMax);

        FragmentationMod.LOGGER.info(
            "[AnvilRepair] Helmet repaired: vanillaDamage " + vanillaCurDamage
                + " -> " + outputDamage + ", visorDur " + visorCur
                + " -> " + newVisorDur + "/" + visorMax);
    }
}
