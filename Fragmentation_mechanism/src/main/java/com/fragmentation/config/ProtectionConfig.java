package com.fragmentation.config;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.DetailedHitZone;
import com.fragmentation.util.WorldConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 防护部位与防弹面罩配置（protection.json）。
 *
 * 三类数据：
 * 1. helmet_visors —— 头盔与可挂载防弹面罩的"指定"兼容关系（同一头盔可接受多个面罩）。
 * 2. visor_items —— 防弹面罩自身的防弹属性（护甲等级 / 耐久 / 材料）。
 * 3. protections —— 各防具的防护部位（head/face/chest/abdomen/left_arm/right_arm）
 *    以及可选的护甲属性覆盖。未登记的防具默认防护全部位（保持旧行为）。
 *
 * 所有条目支持同 itemId 下通过 NBT 指纹区分变体（如 transmog 外观），
 * 具体 NBT 条目优先匹配，无 NBT 条目作为通用回退。
 */
public class ProtectionConfig {

    /** 头盔 NBT 中附加防弹面罩的键名（值为完整 ItemStack 序列化）。 */
    public static final String VISOR_NBT_KEY = "fragmentation:visor";

    /** 头盔内置（固定）面罩的当前耐久键名，存储在头盔自身 NBT 中。 */
    public static final String BUILTIN_VISOR_DURABILITY_KEY = "fragmentation_visor_durability";

    /** 头盔内置（固定）面罩的最大耐久键名（tooltip 显示用，客户端可直接从 NBT 读取）。 */
    public static final String BUILTIN_VISOR_MAX_DURABILITY_KEY = "fragmentation_visor_max_durability";

    @SerializedName("helmet_visors")
    private List<HelmetVisorEntry> helmetVisors = new ArrayList<>();

    @SerializedName("visor_items")
    private List<VisorItemEntry> visorItems = new ArrayList<>();

    private List<ProtectionEntry> protections = new ArrayList<>();

    // ============================ 头盔-面罩关系 ============================

    /** 查找头盔的兼容关系条目（NBT 变体优先，无过滤条目回退）。 */
    public HelmetVisorEntry findHelmetEntry(ItemStack helmet) {
        if (helmet == null || helmet.isEmpty()) return null;
        String key = itemKey(helmet);
        CompoundTag tag = helmet.getTag();
        HelmetVisorEntry generic = null;
        for (HelmetVisorEntry e : helmetVisors) {
            if (!key.equals(e.helmetId)) continue;
            if (e.helmetNbt == null || e.helmetNbt.isBlank()) {
                if (generic == null) generic = e;
                continue;
            }
            if (tag != null && nbtFilterMatches(e.helmetNbt, tag)) return e;
        }
        return generic;
    }

    /** 判断指定头盔是否接受该防弹面罩。 */
    public boolean isVisorAccepted(ItemStack helmet, ItemStack visor) {
        HelmetVisorEntry e = findHelmetEntry(helmet);
        if (e == null) return false;
        return e.acceptedVisorIds.contains(itemKey(visor));
    }

    /**
     * 登记"头盔接受该面罩"的兼容关系（命令附加时调用），幂等。
     * 若头盔带稳定变体指纹则新建/使用变体专属条目，否则使用通用条目。
     */
    public void registerAcceptance(ItemStack helmet, ItemStack visor) {
        HelmetVisorEntry e = findHelmetEntry(helmet);
        String visorKey = itemKey(visor);
        if (e == null) {
            e = new HelmetVisorEntry();
            e.helmetId = itemKey(helmet);
            e.helmetNbt = deriveNbtFilter(helmet.getTag());
            helmetVisors.add(e);
        }
        if (!e.acceptedVisorIds.contains(visorKey)) {
            e.acceptedVisorIds.add(visorKey);
        }
    }

    // ============================== 面罩属性 ==============================

    public VisorItemEntry findVisorItem(String itemId) {
        for (VisorItemEntry v : visorItems) {
            if (v.itemId.equals(itemId)) return v;
        }
        return null;
    }

    public VisorItemEntry findVisorItem(ItemStack visor) {
        if (visor == null || visor.isEmpty()) return null;
        return findVisorItem(itemKey(visor));
    }

    /**
     * 获取面罩属性条目；不存在时自动创建。
     * 若该物品已在 armors.json 中配置，则沿用其护甲等级/耐久/材料，否则写入保守默认值。
     */
    public VisorItemEntry ensureVisorItem(ItemStack visor) {
        String key = itemKey(visor);
        VisorItemEntry v = findVisorItem(key);
        if (v != null) return v;
        v = new VisorItemEntry();
        v.itemId = key;
        ArmorConfig.ArmorEntry existing = FragmentationMod.getArmorConfig().getArmorForItem(visor);
        if (existing != null) {
            v.armorLevel = existing.armorLevel;
            v.maxDurability = existing.maxDurability;
            v.material = existing.getMaterial();
        } else {
            v.armorLevel = 3;
            v.maxDurability = 30;
            v.material = "steel";
        }
        visorItems.add(v);
        return v;
    }

    // ============================ 内置固定面罩 ============================

    /** 查找头盔的内置（固定）面罩条目；未登记或未启用时返回 null。 */
    public HelmetVisorEntry findBuiltinVisorEntry(ItemStack helmet) {
        HelmetVisorEntry e = findHelmetEntry(helmet);
        if (e != null && Boolean.TRUE.equals(e.builtinVisor) && e.builtinMaxDurability != null) {
            return e;
        }
        return null;
    }

    /**
     * 登记/更新头盔的内置（固定）面罩（命令调用）。
     * 等级/材料传 null 时沿用 armors.json 中该头盔的属性。
     */
    public void registerBuiltinVisor(ItemStack helmet, int maxDurability, Integer level, String material) {
        HelmetVisorEntry e = findHelmetEntry(helmet);
        if (e == null) {
            e = new HelmetVisorEntry();
            e.helmetId = itemKey(helmet);
            e.helmetNbt = deriveNbtFilter(helmet.getTag());
            helmetVisors.add(e);
        }
        e.builtinVisor = true;
        e.builtinMaxDurability = maxDurability;
        e.builtinLevel = level;
        e.builtinMaterial = material;
    }

    /** 取消头盔的内置（固定）面罩登记（幂等）。 */
    public boolean removeBuiltinVisor(ItemStack helmet) {
        HelmetVisorEntry e = findHelmetEntry(helmet);
        if (e == null || !Boolean.TRUE.equals(e.builtinVisor)) return false;
        e.builtinVisor = null;
        e.builtinMaxDurability = null;
        e.builtinLevel = null;
        e.builtinMaterial = null;
        return true;
    }

    // ============================== 防护部位 ==============================

    /** 查找防具的防护配置条目（NBT 变体优先，无过滤条目回退）。 */
    public ProtectionEntry findProtection(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String key = itemKey(stack);
        CompoundTag tag = stack.getTag();
        ProtectionEntry generic = null;
        for (ProtectionEntry p : protections) {
            if (!key.equals(p.itemId)) continue;
            if (p.nbt == null || p.nbt.isBlank()) {
                if (generic == null) generic = p;
                continue;
            }
            if (tag != null && nbtFilterMatches(p.nbt, tag)) return p;
        }
        return generic;
    }

    /**
     * 该防具是否防护指定部位。
     * 未登记条目 / 未配置部位列表时默认防护全部位（向后兼容）。
     */
    public boolean coversZone(ItemStack stack, DetailedHitZone zone) {
        ProtectionEntry p = findProtection(stack);
        if (p == null || p.protectedZones == null || p.protectedZones.isEmpty()) {
            return true;
        }
        for (String z : p.protectedZones) {
            if (zone == DetailedHitZone.fromConfigName(z)) return true;
        }
        return false;
    }

    /** 覆盖设置防具的防护部位（命令调用），不存在条目时自动创建。 */
    public void setProtectedZones(ItemStack stack, List<DetailedHitZone> zones) {
        ProtectionEntry p = getOrCreateProtection(stack);
        p.protectedZones = new ArrayList<>();
        for (DetailedHitZone z : zones) {
            p.protectedZones.add(z.configName());
        }
    }

    /** 获取防具的防护配置条目，不存在时创建（含稳定变体指纹）。 */
    public ProtectionEntry getOrCreateProtection(ItemStack stack) {
        ProtectionEntry p = findProtection(stack);
        if (p == null) {
            p = new ProtectionEntry();
            p.itemId = itemKey(stack);
            p.nbt = deriveNbtFilter(stack.getTag());
            protections.add(p);
        }
        return p;
    }

    // ============================== 持久化 ==============================

    public void saveConfig() {
        try {
            String path = WorldConfigManager.getProtectionPath();
            File file = new File(path);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();

            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Protection] Failed to save protection.json", e);
        }
    }

    public void loadConfig() {
        try {
            String path = WorldConfigManager.getProtectionPath();
            File file = new File(path);
            if (!file.exists()) {
                saveConfig();
                return;
            }
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Gson gson = new GsonBuilder()
                .disableHtmlEscaping()
                .create();
            ProtectionConfig loaded = gson.fromJson(content, ProtectionConfig.class);
            if (loaded != null) {
                this.helmetVisors = loaded.helmetVisors != null ? loaded.helmetVisors : new ArrayList<>();
                this.visorItems = loaded.visorItems != null ? loaded.visorItems : new ArrayList<>();
                this.protections = loaded.protections != null ? loaded.protections : new ArrayList<>();
            }
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Protection] Failed to load protection.json", e);
        }
    }

    // ============================== 工具方法 ==============================

    private static String itemKey(ItemStack stack) {
        var rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return rl != null ? rl.toString() : "minecraft:air";
    }

    /**
     * 从物品 NBT 提取稳定的"变体指纹"（目前仅 transmog:transmogItem.id），
     * 不含 Damage 等会变化的字段。
     */
    private static String deriveNbtFilter(CompoundTag tag) {
        if (tag == null) return null;
        String transmogKey = "transmog:transmogItem";
        if (tag.contains(transmogKey, Tag.TAG_COMPOUND)) {
            CompoundTag transmog = tag.getCompound(transmogKey);
            if (transmog.contains("id", Tag.TAG_STRING)) {
                CompoundTag inner = new CompoundTag();
                inner.putString("id", transmog.getString("id"));
                CompoundTag filter = new CompoundTag();
                filter.put(transmogKey, inner);
                // CompoundTag.toString() 输出合法 SNBT（自动为含冒号的键加引号）
                return filter.toString();
            }
        }
        return null;
    }

    private static boolean nbtFilterMatches(String filterSnbt, CompoundTag itemTag) {
        if (filterSnbt == null || filterSnbt.isBlank()) return true;
        if (itemTag == null) return false;
        try {
            CompoundTag filter = TagParser.parseTag(filterSnbt);
            return nbtSubset(filter, itemTag);
        } catch (Exception e) {
            return true;
        }
    }

    /** 判断 filter 是否为 tag 的子集（递归比较嵌套复合标签）。 */
    private static boolean nbtSubset(CompoundTag filter, CompoundTag tag) {
        for (String key : filter.getAllKeys()) {
            if (!tag.contains(key)) return false;
            Tag fv = filter.get(key);
            Tag tv = tag.get(key);
            if (fv instanceof CompoundTag fc && tv instanceof CompoundTag tc) {
                if (!nbtSubset(fc, tc)) return false;
            } else if (!nbtValueEquals(fv, tv)) {
                return false;
            }
        }
        return true;
    }

    private static boolean nbtValueEquals(Tag a, Tag b) {
        if (a == null || b == null) return false;
        try {
            return a.getAsString().equals(b.getAsString());
        } catch (UnsupportedOperationException e) {
            return a.toString().equals(b.toString());
        }
    }

    // ============================== 条目类 ==============================

    /** 头盔与可挂载防弹面罩的兼容关系。 */
    public static class HelmetVisorEntry {
        public String helmetId;
        /** 可选 SNBT 变体指纹；为空时对该 itemId 所有变体生效。 */
        public String helmetNbt;
        public List<String> acceptedVisorIds = new ArrayList<>();
        /** 内置（固定）面罩：头盔自带面部防御，无需附加面罩物品。 */
        public Boolean builtinVisor;
        /** 内置面罩最大耐久。 */
        public Integer builtinMaxDurability;
        /** 内置面罩护甲等级（null 时沿用 armors.json 中头盔的等级）。 */
        public Integer builtinLevel;
        /** 内置面罩材料（null 时沿用 armors.json 中头盔的材料）。 */
        public String builtinMaterial;
    }

    /** 防弹面罩自身属性。 */
    public static class VisorItemEntry {
        public String itemId;
        public Integer armorLevel;
        public Integer maxDurability;
        public String material;
    }

    /** 防具防护部位与可选属性覆盖（null 字段表示沿用 armors.json）。 */
    public static class ProtectionEntry {
        public String itemId;
        /** 可选 SNBT 变体指纹；为空时对该 itemId 所有变体生效。 */
        public String nbt;
        public List<String> protectedZones = new ArrayList<>();
        public Integer armorLevel;
        public Integer maxDurability;
        public String material;
    }
}
