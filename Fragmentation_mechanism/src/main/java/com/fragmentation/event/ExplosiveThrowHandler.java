package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.entity.ExplosiveThrowableEntity;
import com.fragmentation.registry.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册物品的投掷接管：
 * 不拦截右键、不干预拉环与进度条——原模组物品的投掷手感（进度条、
 * 引信动作、出手动画、碰撞反弹）完整保留，未注册的物品（如烟雾弹）
 * 完全不受影响。当原模组的投掷物实体生成时（EntityJoinLevelEvent），
 * 读取其携带物品判断是否被注册为闪光弹/手雷：若是，取消该实体并
 * 生成一个等价的 ExplosiveThrowableEntity（复制位置/速度/朝向/所有者），
 * 由统一的爆炸系统（伤害模型/调试可视化/护甲穿透）在引信到期时接管。
 *
 * 注意：各模组投掷物基类不同——原版系继承 ThrowableItemProjectile，
 * LR Tactical 等直接继承 Projectile 并自带 getItem()。因此这里用
 * "鸭子类型"反射查找无参 getItem()，任何携带 ItemStack 的投射物都
 * 能识别，避免漏接管导致注册物品失去本模组效果。
 * 附属 mod 自带的两个物品由 Mixin 直接接管爆炸，不在此处理；
 * 本模组自己的实体进入世界时不做任何事。
 */
public final class ExplosiveThrowHandler {
    private static final int FLASH_FUSE = 50;
    private static final int GRENADE_FUSE = 60;

    /** 各实体类的 getItem 方法缓存（无该方法记 NO_METHOD）。 */
    private static final Map<Class<?>, Method> ITEM_GETTERS =
        new ConcurrentHashMap<>();
    private static final Method NO_METHOD;

    static {
        try {
            NO_METHOD = ExplosiveThrowHandler.class.getDeclaredMethod("noMethod");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ExplosiveThrowHandler() {
    }

    private void noMethod() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        // 只关心投射物；本模组实体直接放行
        if (!(entity instanceof Projectile projectile)
            || entity instanceof ExplosiveThrowableEntity) {
            return;
        }

        ItemStack item = carriedItem(projectile);
        if (item == null || item.isEmpty()) {
            return;
        }
        String key = ExplosiveConfig.stackKey(item);
        if (key.isEmpty()
            || ExplosiveConfig.ADDON_FLASH_ID.equals(key)
            || ExplosiveConfig.ADDON_FRAG_ID.equals(key)) {
            return;
        }

        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        ExplosiveConfig.ExplosiveEntry entry = config.getEntry(key);
        if (entry == null
            || entry.type == ExplosiveConfig.ExplosiveType.LAUNCHER) {
            return;
        }

        // 取消原模组实体，生成等价的接管实体（同位置/速度/朝向）
        event.setCanceled(true);
        int fuse = entry.type == ExplosiveConfig.ExplosiveType.FLASH
            ? FLASH_FUSE
            : GRENADE_FUSE;
        Level level = (Level) event.getLevel();
        ExplosiveThrowableEntity replacement = new ExplosiveThrowableEntity(
            ModEntities.EXPLOSIVE_THROWABLE.get(), level);
        replacement.takeOver(projectile, key, item, fuse);
        level.addFreshEntity(replacement);
    }

    /**
     * 鸭子类型读取投射物携带的物品：
     * 优先原版 ThrowableItemProjectile#getItem；否则反射查找该类
     * 公共无参、返回 ItemStack 的 getItem()（如 LR Tactical）。
     */
    private static ItemStack carriedItem(Projectile projectile) {
        if (projectile instanceof ThrowableItemProjectile tip) {
            return tip.getItem();
        }
        Method getter = ITEM_GETTERS.computeIfAbsent(
            projectile.getClass(), ExplosiveThrowHandler::findItemGetter);
        if (getter == NO_METHOD) {
            return ItemStack.EMPTY;
        }
        try {
            Object result = getter.invoke(projectile);
            return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException e) {
            return ItemStack.EMPTY;
        }
    }

    private static Method findItemGetter(Class<?> clazz) {
        try {
            Method method = clazz.getMethod("getItem");
            if (ItemStack.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                return method;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return NO_METHOD;
    }
}
