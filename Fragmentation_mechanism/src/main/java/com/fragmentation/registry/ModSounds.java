package com.fragmentation.registry;

import com.fragmentation.FragmentationMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, FragmentationMod.MOD_ID);

    public static final RegistryObject<SoundEvent> FLASHBANG_TINNITUS = SOUND_EVENTS.register(
        "flashbang/tinnitus",
        () -> SoundEvent.createVariableRangeEvent(
            new ResourceLocation(FragmentationMod.MOD_ID, "flashbang/tinnitus"))
    );

    /** 屏息开始（吸气声，用户提供 xq.ogg）。 */
    public static final RegistryObject<SoundEvent> BREATH_HOLD_START = SOUND_EVENTS.register(
        "breath/hold_start",
        () -> SoundEvent.createVariableRangeEvent(
            new ResourceLocation(FragmentationMod.MOD_ID, "breath/hold_start"))
    );

    /** 屏息结束（呼气声，用户提供 hq.ogg）。 */
    public static final RegistryObject<SoundEvent> BREATH_HOLD_STOP = SOUND_EVENTS.register(
        "breath/hold_stop",
        () -> SoundEvent.createVariableRangeEvent(
            new ResourceLocation(FragmentationMod.MOD_ID, "breath/hold_stop"))
    );

    private ModSounds() {
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
