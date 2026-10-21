package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Curios 面部配饰（facewear）访问工具：在实体已穿戴的 Curios 槽位中查找防弹面罩。
 *
 * 扫描策略（两级匹配，防止把身体/背包槽里的防弹衣误判成面罩）：
 * 1. 优先扫描 "facewear"（面部装备）标识符槽位，使用宽松匹配（面罩配置或护甲配置均可）；
 * 2. 其余槽位使用严格匹配（仅 protection.json 中登记的 visor_items）。
 *
 * 运行时需 curios 模组在场（ModList 检测 + Throwable 兜底），缺失时一律返回 null。
 */
public class FacewearHelper {

    /** 一条已穿戴面罩的引用：处理器 + 槽位号 + 当前栈（写回时用同一实例替换以触发同步）。 */
    public record WornVisor(net.minecraftforge.items.IItemHandlerModifiable handler, int index, ItemStack stack) {
        /** 将修改后的面罩写回 Curios 槽位。 */
        public void writeBack(ItemStack updated) {
            if (handler != null) {
                handler.setStackInSlot(index, updated);
            }
        }
    }

    public static boolean curiosLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded("curios");
    }

    /**
     * 在实体已穿戴的 Curios 槽位中查找第一条匹配的面罩。
     *
     * @param facewearMatcher facewear 槽位使用的宽松匹配（该槽只放面部物品，可放宽）
     * @param strictMatcher   其他槽位使用的严格匹配（防止防弹衣等护甲被误认为面罩）
     */
    public static WornVisor findWornVisor(LivingEntity entity,
                                          Predicate<ItemStack> facewearMatcher,
                                          Predicate<ItemStack> strictMatcher) {
        if (entity == null || !curiosLoaded()) return null;
        try {
            ICuriosItemHandler handler = CuriosApi.getCuriosInventory(entity).orElse(null);
            if (handler == null) return null;

            Map<String, ICurioStacksHandler> curios = handler.getCurios();
            if (curios == null || curios.isEmpty()) return null;

            ICurioStacksHandler facewear = curios.get("facewear");
            if (facewear != null) {
                WornVisor found = scan(facewear.getStacks(), facewearMatcher);
                if (found != null) return found;
            }

            for (Map.Entry<String, ICurioStacksHandler> e : curios.entrySet()) {
                if ("facewear".equals(e.getKey())) continue;
                WornVisor found = scan(e.getValue().getStacks(), strictMatcher);
                if (found != null) return found;
            }
        } catch (Throwable t) {
            FragmentationMod.LOGGER.debug("[Facewear] Curios scan failed: " + t);
        }
        return null;
    }

    private static WornVisor scan(net.minecraftforge.items.IItemHandlerModifiable stacks,
                                  Predicate<ItemStack> matcher) {
        if (stacks == null) return null;
        int slots = stacks.getSlots();
        for (int i = 0; i < slots; i++) {
            ItemStack s = stacks.getStackInSlot(i);
            if (!s.isEmpty() && matcher.test(s)) {
                return new WornVisor(stacks, i, s);
            }
        }
        return null;
    }
}
