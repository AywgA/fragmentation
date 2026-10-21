package com.fragmentation.config;

import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 爆炸物配置：闪光弹 / 手雷（按物品注册名）、榴弹（按 TACZ 弹药ID）。
 *
 * 可在世界存档目录 fragmentation/explosives.json 中直接修改：
 *   yield       —— 当量（TNT 当量相对值，基准手雷 = 1.0；伤害随当量
 *                  线性增长，作用半径随当量立方根增长）
 *   fragments   —— 碎片数量（闪光弹填 0）
 *   fragmentPen —— 弹片穿透等级，单位与方块抗穿阈值一致
 *                  （约 9 = 可穿一层石墙后仍有杀伤力）
 */
public class ExplosiveConfig {
    public static final double MIN_YIELD = 0.01;
    public static final double MAX_YIELD = 100.0;
    public static final int MIN_FRAGMENTS = 0;
    public static final int MAX_FRAGMENTS = 4096;
    public static final double MIN_PEN = 0.0;
    public static final double MAX_PEN = 1000.0;

    // —— 基准手雷（当量 1.0）默认尺度 ——
    public static final double BASE_SHOCK_RADIUS = 10.0;
    public static final double BASE_SHOCK_DAMAGE = 60.0;
    public static final double BASE_FRAG_RADIUS = 12.0;
    public static final double BASE_FRAG_DAMAGE = 80.0;
    public static final int BASE_FRAG_COUNT = 256;
    public static final double BASE_FRAG_PEN = 9.0;

    /** 致盲持续上限（ticks，60 秒）。 */
    public static final int MAX_BLIND_TICKS = 1200;
    /** 贴脸直视致盲基准（ticks，30 秒）。 */
    public static final int BASE_BLIND_TICKS = 600;

    // —— 闪光弹基准 ——
    public static final double BASE_FLASH_RANGE = 20.0;

    // —— 附属 mod 自带物品ID（默认走基准参数，配置可覆盖）——
    public static final String ADDON_FLASH_ID =
        "tacz_tactical_breaching:flashbang_grenade";
    public static final String ADDON_FRAG_ID =
        "tacz_tactical_breaching:frag_grenade";

    public enum ExplosiveType {
        FLASH,
        GRENADE,
        LAUNCHER
    }

    public static class ExplosiveEntry {
        public String id = "";
        public ExplosiveType type = ExplosiveType.GRENADE;
        public double yield = 1.0;
        public int fragments = BASE_FRAG_COUNT;
        public double fragmentPen = BASE_FRAG_PEN;
    }

    private List<ExplosiveEntry> explosives = new ArrayList<>();

    public ExplosiveConfig() {
    }

    public List<ExplosiveEntry> getExplosives() {
        return explosives;
    }

    public ExplosiveEntry getEntry(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (ExplosiveEntry entry : explosives) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public boolean addOrUpdate(
        String id,
        ExplosiveType type,
        double yield,
        int fragments,
        double fragmentPen
    ) {
        if (id == null || id.isBlank()) {
            return false;
        }
        ExplosiveEntry entry = getEntry(id);
        if (entry == null) {
            entry = new ExplosiveEntry();
            entry.id = id;
            explosives.add(entry);
        }
        entry.type = type;
        entry.yield = clampYield(yield);
        entry.fragments = clampFragments(fragments);
        entry.fragmentPen = clampPen(fragmentPen);
        return true;
    }

    public boolean remove(String id) {
        for (int i = 0; i < explosives.size(); i++) {
            if (id.equals(explosives.get(i).id)) {
                explosives.remove(i);
                return true;
            }
        }
        return false;
    }

    /** 当量（缺省配置时返回 1.0）。 */
    public double yieldOf(String id) {
        ExplosiveEntry entry = getEntry(id);
        return entry != null ? clampYield(entry.yield) : 1.0;
    }

    public void saveConfig() {
        try {
            File file = new File(WorldConfigManager.getExplosivesPath());
            File dir = file.getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(explosives, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void loadConfig() {
        try {
            File file = new File(WorldConfigManager.getExplosivesPath());
            if (!file.exists()) {
                saveConfig();
                return;
            }
            Gson gson = new Gson();
            List<ExplosiveEntry> loaded;
            try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                loaded = gson.fromJson(
                    root,
                    new com.google.gson.reflect.TypeToken<List<ExplosiveEntry>>() {
                    }.getType()
                );
            }
            if (loaded != null) {
                loaded.removeIf(java.util.Objects::isNull);
                for (ExplosiveEntry entry : loaded) {
                    if (entry.id == null || entry.id.isBlank() || entry.type == null) {
                        continue;
                    }
                    entry.yield = clampYield(entry.yield);
                    entry.fragments = clampFragments(entry.fragments);
                    entry.fragmentPen = clampPen(entry.fragmentPen);
                }
                explosives = loaded;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static double clampYield(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(MIN_YIELD, Math.min(MAX_YIELD, value));
    }

    public static int clampFragments(int value) {
        return Math.max(MIN_FRAGMENTS, Math.min(MAX_FRAGMENTS, value));
    }

    public static double clampPen(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(MIN_PEN, Math.min(MAX_PEN, value));
    }

    /**
     * 物品实例的配置 key。
     * 部分战术 mod（如 lrtactical）的烟雾弹/闪光弹/手雷是同一个物品
     * 的 NBT 变体，仅靠注册名无法区分。因此：无 NBT 时 key = 物品ID；
     * 有区分性 NBT 时 key = 物品ID#NBT指纹。
     * 注册与投掷两个入口必须使用同一函数，保证查找一致。
     */
    public static String stackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) {
            return "";
        }
        String base = itemId.toString();
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return base;
        }
        return base + "#" + Long.toUnsignedString(hashCompound(tag), 16);
    }

    /** 去掉复合 key 的 NBT 指纹后缀，还原为纯物品注册名。 */
    public static String itemIdOf(String stackKey) {
        if (stackKey == null) {
            return "";
        }
        int hashIndex = stackKey.indexOf('#');
        return hashIndex >= 0 ? stackKey.substring(0, hashIndex) : stackKey;
    }

    private static long hashCompound(CompoundTag tag) {
        long hash = -3750763034362895579L;
        List<String> keys = new ArrayList<>(tag.getAllKeys());
        // 排序后哈希，避免 CompoundTag 内部键顺序不固定导致指纹漂移
        Collections.sort(keys);
        for (String key : keys) {
            hash = mix(hash, key.hashCode());
            Tag value = tag.get(key);
            hash = mix(hash, value != null ? hashValue(value) : 0L);
        }
        return hash;
    }

    private static long hashValue(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return hashCompound(compound);
        }
        if (tag instanceof ListTag list) {
            long hash = 1469598103934665603L;
            for (Tag element : list) {
                hash = mix(hash, hashValue(element));
            }
            return hash;
        }
        if (tag instanceof ByteArrayTag array) {
            long hash = 0L;
            for (byte value : array.getAsByteArray()) {
                hash = mix(hash, value);
            }
            return hash;
        }
        if (tag instanceof IntArrayTag array) {
            long hash = 0L;
            for (int value : array.getAsIntArray()) {
                hash = mix(hash, value);
            }
            return hash;
        }
        if (tag instanceof LongArrayTag array) {
            long hash = 0L;
            for (long value : array.getAsLongArray()) {
                hash = mix(hash, value);
            }
            return hash;
        }
        // 基本数值/字符串标签：字符串形式已包含其类型与值
        return mix(-7046029254386353131L, tag.toString().hashCode());
    }

    private static long mix(long hash, long value) {
        long mixed = hash ^ (value * -7046029254386353131L);
        mixed ^= mixed >>> 33;
        mixed *= -49064778989728563L;
        mixed ^= mixed >>> 29;
        return mixed;
    }
}
