package com.fragmentation.mixin;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ProtectionConfig;
import com.fragmentation.util.DurabilityUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 拦截 iron_repair_kits 的修复完成时点，同步恢复内置面罩耐久。
 *
 * 该模组的修复流程：玩家右键修复套件打开 GUI → 放入物品 → 点击按钮 →
 * 网络包 RepairGuiButtonMessage → RepairItemProcedure.execute() 在服务器执行修复。
 * 修复通过 ItemStack.hurt(负数) 实现，但当头盔本体满耐久（仅面罩受损）时
 * 修复量为 0，任何基于 Damage 变化的检测都不会触发。因此在 execute 完成后
 * 直接按头盔当前耐久比例同步面罩耐久。
 *
 * remap = false：目标类属于第三方模组，不使用 SRG 名。
 * 目标类缺失时（未安装该模组）仅警告不崩溃（defaultRequire=0）。
 */
@Pseudo
@Mixin(targets = "net.mcreator.repairkits.procedures.RepairItemProcedure", remap = false)
public class RepairItemProcedureMixin {

    @Inject(method = "execute", at = @At("TAIL"))
    private static void fragmentation$syncVisorAfterRepair(LevelAccessor world, double x, double y, double z,
                                                           Entity entity, CallbackInfo ci) {
        try {
            if (!(entity instanceof Player player)) return;
            if (player.level().isClientSide()) return;

            // 主手必须持有修复套件，否则这次按钮点击不是有效修复
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty()) return;
            net.minecraft.resources.ResourceLocation heldId = ForgeRegistries.ITEMS.getKey(held.getItem());
            if (heldId == null || !heldId.getNamespace().equals("iron_repair_kits")) return;

            // 与 RepairItemProcedure 相同的方式获取 GUI slot 0 中的物品
            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof Supplier<?> supplier)) return;
            Object obj = supplier.get();
            if (!(obj instanceof Map<?, ?> map)) return;
            Object slotObj = map.get(0);
            if (!(slotObj instanceof Slot slot)) return;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) return;

            CompoundTag tag = stack.getTag();
            if (tag == null) return;
            String visorKey = ProtectionConfig.BUILTIN_VISOR_DURABILITY_KEY;
            String visorMaxKey = ProtectionConfig.BUILTIN_VISOR_MAX_DURABILITY_KEY;
            if (!tag.contains(visorKey) || !tag.contains(visorMaxKey)) return;

            int visorMax = tag.getInt(visorMaxKey);
            int visorCur = tag.getInt(visorKey);
            if (visorMax <= 0 || visorCur >= visorMax) return;

            int maxDur = DurabilityUtils.getMaxDurability(stack);
            if (maxDur <= 0) return;
            int curDur = DurabilityUtils.getCurrentDurability(stack);

            // 按头盔当前耐久比例恢复面罩：头盔修满则面罩修满
            int target = Math.round(visorMax * ((float) curDur / (float) maxDur));
            int newVisor = Math.max(visorCur, Math.min(visorMax, target));
            if (newVisor <= visorCur) return;

            tag.putInt(visorKey, newVisor);

            FragmentationMod.LOGGER.info(
                "[RepairKitMixin] Visor synced after repair kit: visorDur " + visorCur
                    + " -> " + newVisor + "/" + visorMax
                    + " (helmet durability " + curDur + "/" + maxDur + ")");
        } catch (Throwable t) {
            FragmentationMod.LOGGER.error("[RepairKitMixin] Error syncing visor after repair", t);
        }
    }
}
