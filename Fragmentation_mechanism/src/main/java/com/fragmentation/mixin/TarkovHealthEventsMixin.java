package com.fragmentation.mixin;

import com.fragmentation.integration.TarkovDamageContext;
import com.qq.tarkovmod.health.model.BodyPart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 拦截 TarkovMod 的 HealthDamageEvents，在伤害结算前将 BodyPart 替换为随机部位。
 *
 * TarkovMod 只允许注册一个 ScopedDamageBridgeRegistry.Resolver，且它自己的
 * TaczHealthHitBridge 会占用该槽位，因此不能用注册 Resolver 的方式随机化部位。
 * 改用 Mixin 直接拦截 TarkovHealthService.damage() 调用，替换 BodyPart 参数。
 */
@Mixin(targets = "com.qq.tarkovmod.runtime.health.HealthDamageEvents", remap = false)
public class TarkovHealthEventsMixin {

    @Inject(method = "onLivingHurt", at = @At("HEAD"))
    private static void onLivingHurtHead(LivingHurtEvent event, CallbackInfo ci) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DamageSource source = event.getSource();
        Entity directEntity = source.getDirectEntity();
        Entity shooter = source.getEntity();

        boolean isProjectile = directEntity instanceof Projectile;
        boolean isSelfAttack = shooter == player;
        if (isProjectile && !isSelfAttack) {
            TarkovDamageContext.markRandomize();
        }
    }

    @Inject(method = "onLivingHurt", at = @At("RETURN"))
    private static void onLivingHurtReturn(LivingHurtEvent event, CallbackInfo ci) {
        TarkovDamageContext.clear();
    }

    @ModifyArg(
        method = "handleScopedDecision",
        at = @At(
            value = "INVOKE",
            target = "Lcom/qq/tarkovmod/health/api/TarkovHealthService;damage(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;Lcom/qq/tarkovmod/health/model/BodyPart;D)Lcom/qq/tarkovmod/health/api/HealthMutationOutcome;",
            remap = false
        ),
        index = 2
    )
    private static BodyPart randomizeScopedBodyPart(BodyPart original) {
        return TarkovDamageContext.getRandomPart(original);
    }

    @ModifyArg(
        method = "handleGenericDamage",
        at = @At(
            value = "INVOKE",
            target = "Lcom/qq/tarkovmod/health/api/TarkovHealthService;damage(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;Lcom/qq/tarkovmod/health/model/BodyPart;D)Lcom/qq/tarkovmod/health/api/HealthMutationOutcome;",
            remap = false
        ),
        index = 2
    )
    private static BodyPart randomizeGenericBodyPart(BodyPart original) {
        return TarkovDamageContext.getRandomPart(original);
    }
}
