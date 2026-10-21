package com.fragmentation.integration;

import com.fragmentation.FragmentationMod;
import com.qq.tarkovmod.health.model.BodyPart;

import java.util.Random;

/**
 * ThreadLocal 上下文：在 LivingHurtEvent HEAD 注入中设置随机部位，
 * 供 TarkovHealthEventsMixin 的 @ModifyArg 读取来替换 BodyPart。
 */
public final class TarkovDamageContext {

    private static final Random RANDOM = new Random();
    private static final ThreadLocal<Boolean> shouldRandomize = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<BodyPart> randomBodyPart = new ThreadLocal<>();

    private TarkovDamageContext() {}

    /** 在 LivingHurtEvent HEAD 注入中调用：标记当前伤害需要随机部位 */
    public static void markRandomize() {
        shouldRandomize.set(true);
        randomBodyPart.set(pickRandomBodyPart());
        FragmentationMod.LOGGER.info("[TarkovDamageContext] Marked for randomize");
    }

    /** 在 Mixin @ModifyArg 中调用：如果标记了随机，返回随机部位；否则返回原始值 */
    public static BodyPart getRandomPart(BodyPart original) {
        if (shouldRandomize.get()) {
            BodyPart random = randomBodyPart.get();
            if (random != null) {
                FragmentationMod.LOGGER.info(
                    "[TarkovDamageContext] Replacing body part: " + original + " -> " + random);
                return random;
            }
        }
        return original;
    }

    /** 在 LivingHurtEvent RETURN 注入中调用：清理 ThreadLocal */
    public static void clear() {
        shouldRandomize.remove();
        randomBodyPart.remove();
    }

    /** 1% 头部，99% 在其余 6 个部位均匀分布 */
    private static BodyPart pickRandomBodyPart() {
        if (RANDOM.nextFloat() < 0.01F) {
            return BodyPart.HEAD;
        }
        BodyPart[] pool = {
            BodyPart.THORAX, BodyPart.STOMACH,
            BodyPart.LEFT_ARM, BodyPart.RIGHT_ARM,
            BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG
        };
        return pool[RANDOM.nextInt(pool.length)];
    }
}
