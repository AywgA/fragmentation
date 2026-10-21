package com.fragmentation.registry;

import com.fragmentation.FragmentationMod;
import com.fragmentation.item.ArmorPlateItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FragmentationMod.MOD_ID);

    public static final RegistryObject<Item> PLATE_LEVEL_1 = ITEMS.register("plate_level_1",
            () -> new ArmorPlateItem(1, new Item.Properties().rarity(Rarity.COMMON)));

    public static final RegistryObject<Item> PLATE_LEVEL_2 = ITEMS.register("plate_level_2",
            () -> new ArmorPlateItem(2, new Item.Properties().rarity(Rarity.COMMON)));

    public static final RegistryObject<Item> PLATE_LEVEL_3 = ITEMS.register("plate_level_3",
            () -> new ArmorPlateItem(3, new Item.Properties().rarity(Rarity.UNCOMMON)));

    public static final RegistryObject<Item> PLATE_LEVEL_4 = ITEMS.register("plate_level_4",
            () -> new ArmorPlateItem(4, new Item.Properties().rarity(Rarity.UNCOMMON)));

    public static final RegistryObject<Item> PLATE_LEVEL_5 = ITEMS.register("plate_level_5",
            () -> new ArmorPlateItem(5, new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<Item> PLATE_LEVEL_6 = ITEMS.register("plate_level_6",
            () -> new ArmorPlateItem(6, new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<Item> PLATE_LEVEL_7 = ITEMS.register("plate_level_7",
            () -> new ArmorPlateItem(7, new Item.Properties().rarity(Rarity.EPIC)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }

    public static Item plateItemForLevel(int level) {
        return switch (level) {
            case 1 -> PLATE_LEVEL_1.get();
            case 2 -> PLATE_LEVEL_2.get();
            case 3 -> PLATE_LEVEL_3.get();
            case 4 -> PLATE_LEVEL_4.get();
            case 5 -> PLATE_LEVEL_5.get();
            case 6 -> PLATE_LEVEL_6.get();
            case 7 -> PLATE_LEVEL_7.get();
            default -> PLATE_LEVEL_3.get();
        };
    }
}