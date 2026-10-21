package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorldCoverManager {
    private static final Map<String, CoverEntry> COVERS = new LinkedHashMap<>();

    private WorldCoverManager() {
    }

    public static synchronized void loadConfig() {
        File file = new File(WorldConfigManager.getCoversPath());
        if (!file.exists()) {
            COVERS.clear();
            saveConfig();
            return;
        }

        try (Reader reader = new InputStreamReader(
            new FileInputStream(file),
            StandardCharsets.UTF_8
        )) {
            JsonElement root = JsonParser.parseReader(reader);
            boolean needsSchemaMigration = root.isJsonArray()
                && root.getAsJsonArray().asList().stream()
                    .filter(JsonElement::isJsonObject)
                    .anyMatch(element ->
                        !element.getAsJsonObject().has("ricochetCoefficient")
                    );
            List<CoverEntry> loaded = new Gson().fromJson(
                root,
                new TypeToken<List<CoverEntry>>() { }.getType()
            );
            COVERS.clear();
            if (loaded != null) {
                for (CoverEntry entry : loaded) {
                    CoverEntry normalized = normalize(entry);
                    if (normalized != null) {
                        COVERS.put(normalized.blockId(), normalized);
                    }
                }
            }
            if (needsSchemaMigration) {
                saveConfig();
                FragmentationMod.LOGGER.info(
                    "[Cover] Added ricochetCoefficient to existing entries"
                );
            }
            FragmentationMod.LOGGER.info("[Cover] Loaded {} cover definitions", COVERS.size());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Cover] Failed to load cover definitions", e);
        }
    }

    public static synchronized void saveConfig() {
        File file = new File(WorldConfigManager.getCoversPath());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(file),
            StandardCharsets.UTF_8
        )) {
            new GsonBuilder().setPrettyPrinting().create().toJson(
                listCovers(),
                new TypeToken<List<CoverEntry>>() { }.getType(),
                writer
            );
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Cover] Failed to save cover definitions", e);
        }
    }

    public static synchronized CoverEntry setCover(
        ResourceLocation blockId,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient
    ) {
        CoverEntry existing = getCover(blockId);
        double ricochetCoefficient = existing != null
            ? existing.ricochetCoefficient()
            : 0.0D;
        return setCover(
            blockId,
            penetrationThreshold,
            thicknessCoefficient,
            densityCoefficient,
            ricochetCoefficient
        );
    }

    public static synchronized CoverEntry setCover(
        ResourceLocation blockId,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient,
        double ricochetCoefficient
    ) {
        if (blockId == null) return null;
        CoverEntry entry = new CoverEntry(
            blockId.toString(),
            finiteNonNegative(penetrationThreshold),
            finiteNonNegative(thicknessCoefficient),
            finiteNonNegative(densityCoefficient),
            clampUnit(ricochetCoefficient)
        );
        COVERS.put(entry.blockId(), entry);
        saveConfig();
        return entry;
    }

    public static synchronized boolean removeCover(ResourceLocation blockId) {
        if (blockId == null || COVERS.remove(blockId.toString()) == null) {
            return false;
        }
        saveConfig();
        return true;
    }

    public static synchronized CoverEntry getCover(ResourceLocation blockId) {
        return blockId != null ? COVERS.get(blockId.toString()) : null;
    }

    public static synchronized CoverEntry getCover(BlockState state) {
        if (state == null) return null;
        return getCover(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    public static synchronized List<CoverEntry> listCovers() {
        List<CoverEntry> entries = new ArrayList<>(COVERS.values());
        entries.sort(Comparator.comparing(CoverEntry::blockId));
        return entries;
    }

    private static CoverEntry normalize(CoverEntry entry) {
        if (entry == null || entry.blockId() == null) return null;
        ResourceLocation blockId = ResourceLocation.tryParse(entry.blockId().trim());
        if (blockId == null) {
            FragmentationMod.LOGGER.warn(
                "[Cover] Ignoring invalid block ID in covers.json: {}",
                entry.blockId()
            );
            return null;
        }
        return new CoverEntry(
            blockId.toString(),
            finiteNonNegative(entry.penetrationThreshold()),
            finiteNonNegative(entry.thicknessCoefficient()),
            finiteNonNegative(entry.densityCoefficient()),
            clampUnit(entry.ricochetCoefficient())
        );
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    private static double clampUnit(double value) {
        return Math.min(1.0D, finiteNonNegative(value));
    }

    public record CoverEntry(
        String blockId,
        double penetrationThreshold,
        double thicknessCoefficient,
        double densityCoefficient,
        double ricochetCoefficient
    ) {
    }
}
