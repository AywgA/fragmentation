package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WindMeterManager {
    private static final Set<String> ITEM_IDS = new LinkedHashSet<>();

    private WindMeterManager() {
    }

    public static synchronized void loadConfig() {
        File file = new File(WorldConfigManager.getWindMetersPath());
        if (!file.exists()) {
            ITEM_IDS.clear();
            saveConfig();
            return;
        }

        try (Reader reader = new InputStreamReader(
            new FileInputStream(file),
            StandardCharsets.UTF_8
        )) {
            List<String> loaded = new Gson().fromJson(
                reader,
                new TypeToken<List<String>>() { }.getType()
            );
            ITEM_IDS.clear();
            if (loaded != null) {
                for (String itemId : loaded) {
                    ResourceLocation parsed = ResourceLocation.tryParse(itemId);
                    if (parsed != null) {
                        ITEM_IDS.add(parsed.toString());
                    }
                }
            }
            FragmentationMod.LOGGER.info("[WindMeter] Loaded {} meter items", ITEM_IDS.size());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[WindMeter] Failed to load meter items", e);
        }
    }

    public static synchronized void saveConfig() {
        File file = new File(WorldConfigManager.getWindMetersPath());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(file),
            StandardCharsets.UTF_8
        )) {
            new GsonBuilder().setPrettyPrinting().create().toJson(ITEM_IDS, writer);
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[WindMeter] Failed to save meter items", e);
        }
    }

    public static synchronized boolean add(ItemStack stack) {
        String itemId = getItemId(stack);
        if (itemId == null || !ITEM_IDS.add(itemId)) {
            return false;
        }
        saveConfig();
        return true;
    }

    public static synchronized boolean remove(ItemStack stack) {
        String itemId = getItemId(stack);
        if (itemId == null || !ITEM_IDS.remove(itemId)) {
            return false;
        }
        saveConfig();
        return true;
    }

    public static synchronized boolean isWindMeter(ItemStack stack) {
        String itemId = getItemId(stack);
        return itemId != null && ITEM_IDS.contains(itemId);
    }

    public static synchronized List<String> getRegisteredItemIds() {
        return new ArrayList<>(ITEM_IDS);
    }

    public static String getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null ? itemId.toString() : null;
    }
}
