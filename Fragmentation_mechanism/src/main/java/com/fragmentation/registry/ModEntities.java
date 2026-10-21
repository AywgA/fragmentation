package com.fragmentation.registry;

import com.fragmentation.FragmentationMod;
import com.fragmentation.entity.ExplosiveThrowableEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FragmentationMod.MOD_ID);

    public static final RegistryObject<EntityType<ExplosiveThrowableEntity>> EXPLOSIVE_THROWABLE =
        ENTITY_TYPES.register(
            "explosive_throwable",
            () -> EntityType.Builder
                .of(
                    (EntityType<ExplosiveThrowableEntity> type, Level level)
                        -> new ExplosiveThrowableEntity(type, level),
                    MobCategory.MISC
                )
                .sized(0.25F, 0.25F)
                .clientTrackingRange(8)
                .updateInterval(20)
                .build("explosive_throwable")
        );

    private ModEntities() {
    }

    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}
