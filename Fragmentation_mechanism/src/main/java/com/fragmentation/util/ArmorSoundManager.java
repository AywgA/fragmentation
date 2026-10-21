package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ArmorSoundManager {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, FragmentationMod.MOD_ID);

    public static final RegistryObject<SoundEvent> RICOCHET = SOUND_EVENTS.register("armor.ricochet",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "armor/ricochet")));

    public static final RegistryObject<SoundEvent> PENETRATION = SOUND_EVENTS.register("armor.penetration",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "armor/penetration")));

    /** 防弹衣：子弹被挡下（未穿透）→ wct（每发未穿透子弹播放一次） */
    public static final RegistryObject<SoundEvent> ARMOR_WCT = SOUND_EVENTS.register("armor.wct",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "armor/wct")));

    /** 防弹衣：子弹穿透 / 护甲耐久归零 → ct（每件护甲每次被穿透周期只播一次，修满后才允许再播） */
    public static final RegistryObject<SoundEvent> ARMOR_CT = SOUND_EVENTS.register("armor.ct",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "armor/ct")));

    /** 屏蔽场：高速物体被挡下（gaosu） */
    public static final RegistryObject<SoundEvent> SHIELD_HIGH_SPEED = SOUND_EVENTS.register("shielding.gaosu",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "shielding/gaosu")));

    /** 屏蔽场：低速物品穿过 / 失去防护效果（disu） */
    public static final RegistryObject<SoundEvent> SHIELD_LOW_SPEED = SOUND_EVENTS.register("shielding.disu",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(FragmentationMod.MOD_ID, "shielding/disu")));

    private static final String PLAYED_PENETRATION_TAG = "fragmentation_played_penetration";
    /** ct（穿透）音效本周期是否已播放：修满耐久时重置 */
    private static final String PLAYED_CT_TAG = "fragmentation_played_ct";

    private static final float RICOCHET_VOLUME = 2.0F;
    private static final float PENETRATION_VOLUME = 2.5F;
    private static final float RICOCHET_PITCH = 1.0F;
    private static final float PENETRATION_PITCH = 0.9F;
    private static final float SHIELD_VOLUME = 2.0F;
    private static final float SHIELD_PITCH = 1.0F;
    private static final float ARMOR_WCT_VOLUME = 2.0F;
    private static final float ARMOR_CT_VOLUME = 1.5F;

    /** 屏蔽场音效距离传播：5 格内满音量清楚；5~15 格线性衰减；15 格外完全听不见 */
    private static final double SHIELD_SOUND_FULL_RANGE = 5.0D;
    private static final double SHIELD_SOUND_MAX_RANGE = 15.0D;

    /** wct（未穿透）：20 格内清楚；20~35 格线性削弱；35 格外听不见 */
    private static final double WCT_FULL_RANGE = 20.0D;
    private static final double WCT_MAX_RANGE = 35.0D;
    /** ct（穿透）：15 格内正常；15~25 格线性衰减；25 格外听不见 */
    private static final double CT_FULL_RANGE = 15.0D;
    private static final double CT_MAX_RANGE = 25.0D;

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }

    public static void playRicochetSound(LivingEntity entity) {
        if (!entity.level().isClientSide) {
            entity.level().playSound(null,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    RICOCHET.get(),
                    SoundSource.PLAYERS,
                    RICOCHET_VOLUME,
                    RICOCHET_PITCH);
        }
    }

    public static void playPenetrationSound(LivingEntity entity, ItemStack armorStack) {
        if (!entity.level().isClientSide) {
            if (hasPlayedPenetration(armorStack)) {
                return;
            }

            entity.level().playSound(null,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    PENETRATION.get(),
                    SoundSource.PLAYERS,
                    PENETRATION_VOLUME,
                    PENETRATION_PITCH);

            markPlayedPenetration(armorStack);
        }
    }

    public static void playRicochetSoundClient(LivingEntity entity) {
        if (entity.level().isClientSide) {
            entity.level().playLocalSound(
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    RICOCHET.get(),
                    SoundSource.PLAYERS,
                    RICOCHET_VOLUME,
                    RICOCHET_PITCH,
                    false);
        }
    }

    public static void playPenetrationSoundClient(LivingEntity entity) {
        if (entity.level().isClientSide) {
            entity.level().playLocalSound(
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    PENETRATION.get(),
                    SoundSource.PLAYERS,
                    PENETRATION_VOLUME,
                    PENETRATION_PITCH,
                    false);
        }
    }

    /** 屏蔽场：高速物体被挡下 → gaosu（按距离衰减：5 格清楚，10 格较轻，15 格外听不见） */
    public static void playShieldBlockSound(LivingEntity entity) {
        broadcastShieldSound(entity, SHIELD_HIGH_SPEED.get());
    }

    /** 屏蔽场：低速物品穿过 / 失去防护效果 → disu（按距离衰减：5 格清楚，10 格较轻，15 格外听不见） */
    public static void playShieldPenetrateSound(LivingEntity entity) {
        broadcastShieldSound(entity, SHIELD_LOW_SPEED.get());
    }

    /**
     * 屏蔽场音效距离传播：向 15 格内玩家逐人发送音效包并按距离调音量。
     * ≤5 格满音量；5~15 格线性衰减（10 格时约一半）；>15 格不发送（听不见）。
     * 佩戴者本人距离为 0，必然以满音量收到，保证自己能听到。
     */
    private static void broadcastShieldSound(LivingEntity entity, SoundEvent soundEvent) {
        broadcastRangedSound(entity, new Vec3(entity.getX(), entity.getY(), entity.getZ()),
            soundEvent, SHIELD_VOLUME, SHIELD_SOUND_FULL_RANGE, SHIELD_SOUND_MAX_RANGE);
    }

    /**
     * 防弹衣未被穿透（每发子弹播放一次 wct），并在命中点生成少量白色烟雾。
     * 20 格内清楚；20~35 格削弱；35 格外听不见。佩戴者本人必听到。
     */
    public static void playArmorBlockSound(LivingEntity entity, Vec3 hitPos) {
        if (entity.level().isClientSide) return;
        Vec3 pos = hitPos != null ? hitPos : new Vec3(entity.getX(), entity.getY(0.9), entity.getZ());
        broadcastRangedSound(entity, pos, ARMOR_WCT.get(), ARMOR_WCT_VOLUME, WCT_FULL_RANGE, WCT_MAX_RANGE);
        spawnBlockSmoke(entity, pos);
    }

    /**
     * 防弹衣被穿透 / 耐久归零 → ct。每件护甲每个"被穿透周期"只播一次：
     * NBT 标记 fragmentation_played_ct，只有耐久被修补回满值时才重置（见 checkAndResetPenetrationState）。
     * 必须传入即将写回装备槽的那个 ItemStack（调用方的副本），标记才能持久化。
     * 15 格内正常；15~25 格衰减；25 格外听不见。佩戴者本人必听到。
     */
    public static void playArmorPenetrateSound(LivingEntity entity, ItemStack armorStack, Vec3 hitPos) {
        if (entity.level().isClientSide) return;
        if (armorStack == null || armorStack.isEmpty()) return;
        CompoundTag tag = armorStack.getOrCreateTag();
        if (tag.getBoolean(PLAYED_CT_TAG)) return;
        tag.putBoolean(PLAYED_CT_TAG, true);

        Vec3 pos = hitPos != null ? hitPos : new Vec3(entity.getX(), entity.getY(0.9), entity.getZ());
        broadcastRangedSound(entity, pos, ARMOR_CT.get(), ARMOR_CT_VOLUME, CT_FULL_RANGE, CT_MAX_RANGE);
    }

    /** 未穿透命中点：少量白色烟雾（原版 CLOUD 粒子，服务端自动同步给周围玩家） */
    private static void spawnBlockSmoke(LivingEntity entity, Vec3 pos) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        level.sendParticles(ParticleTypes.CLOUD,
            pos.x, pos.y, pos.z,
            6,
            0.12D, 0.12D, 0.12D,
            0.02D);
    }

    /**
     * 按距离逐玩家发送音效包：≤fullRange 满音量；fullRange~maxRange 线性衰减到 0；
     * >maxRange 不发送。发声位置 pos（命中点/实体位置）。佩戴者本人距离≈0，必以满音量听到。
     */
    private static void broadcastRangedSound(LivingEntity entity, Vec3 pos, SoundEvent soundEvent,
                                             float baseVolume, double fullRange, double maxRange) {
        if (entity.level().isClientSide || !(entity.level() instanceof ServerLevel level)) return;

        long seed = level.random.nextLong();
        double fullSq = fullRange * fullRange;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            double distSq = player.distanceToSqr(pos.x, pos.y, pos.z);
            if (distSq > maxRange * maxRange) continue;

            float volume;
            if (distSq <= fullSq) {
                volume = baseVolume;
            } else {
                double dist = Math.sqrt(distSq);
                float factor = (float) (1.0D - (dist - fullRange) / (maxRange - fullRange));
                volume = baseVolume * Math.max(0.0F, factor);
            }
            if (volume <= 0.0F) continue;

            player.connection.send(new ClientboundSoundPacket(
                Holder.direct(soundEvent),
                SoundSource.PLAYERS,
                pos.x, pos.y, pos.z,
                volume,
                SHIELD_PITCH,
                seed));
        }
    }

    public static boolean hasPlayedPenetration(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(PLAYED_PENETRATION_TAG);
    }

    public static void markPlayedPenetration(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(PLAYED_PENETRATION_TAG, true);
    }

    public static void resetPenetrationState(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(PLAYED_PENETRATION_TAG);
        }
    }

    /** ct 标记是否已播放（修满后才允许再播） */
    public static boolean hasPlayedCt(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(PLAYED_CT_TAG);
    }

    public static void resetCtState(ItemStack stack) {
        if (stack.isEmpty()) return;
        CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(PLAYED_CT_TAG);
    }

    public static void checkAndResetPenetrationState(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        
        int maxDurability = DurabilityUtils.getMaxDurability(stack);
        if (maxDurability <= 0) {
            return;
        }

        int currentDurability = DurabilityUtils.getCurrentDurability(stack);
        if (currentDurability >= maxDurability) {
            // 修补回满：重置所有"本周期已播放"标记（旧穿透音 + ct）
            if (hasPlayedPenetration(stack)) {
                resetPenetrationState(stack);
            }
            if (hasPlayedCt(stack)) {
                resetCtState(stack);
            }
        }
    }
}
