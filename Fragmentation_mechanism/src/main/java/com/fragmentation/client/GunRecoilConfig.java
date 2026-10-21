package com.fragmentation.client;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 枪械属性配置（参考《暗区突围：无限》/ 逃离塔科夫的枪械属性面板）。
 *
 * 四项属性统一为"数值越高，枪械越好"：
 *  vertical    垂直后坐力评分（越高枪口上跳越轻）
 *  horizontal  水平后坐力评分（越高连发横跳越小）
 *  ergonomics  人机功效（可为负数~100）：越高开镜越快，且对手部
 *              体力条与屏息条的消耗越低（越低则开镜越慢、消耗越大）
 *  stability   持枪稳定度（可为负数~100）：越高开镜瞄准视角的
 *              晃动幅度越小
 *
 * 持久化到 config/fragmentation/gun_recoil.json，可直接编辑后重启生效：
 *  {
 *    "guns": { "eft:ak47": {"vertical":75,"horizontal":58,...} },
 *    "attachments": { "eft:grip_rk6": {"vertical":8,"horizontal":6,"ergonomics":-3,"stability":2} }
 *  }
 * attachments 段为配件加成（增量）：装上配件后，对应属性在基础值上叠加；
 * 正数=提升（后坐力更低/开镜更快/更稳），负数=降低。
 */
public final class GunRecoilConfig {
    /** 单把枪属性（越高越好）。 */
    public record GunStats(
        int vertical,
        int horizontal,
        int ergonomics,
        int stability
    ) {
        /** 垂直后坐力缩放：评分越高后坐力越小，60=基准 1.0。 */
        public double verticalScale() {
            return 60.0 / Math.max(5.0, vertical);
        }

        /** 水平后坐力缩放：评分越高横跳越小，55=基准 1.0。 */
        public double horizontalScale() {
            return 55.0 / Math.max(5.0, horizontal);
        }

        /** 人机功效 → 开镜速度倍率：低人机极其迟钝（1≈0.31，约 3.2
         *  倍慢），高人机迅速（100≈1.5，快 1.5 倍），55≈0.96 近常速。 */
        public double aimSpeedFactor() {
            return clamp(0.3 + ergonomics * 0.012, 0.3, 1.6);
        }

        /** 人机功效 → 体力消耗倍率：越低开镜越慢且体力消耗越大
         *  （1≈1.39，55≈0.80，100≈0.30）。 */
        public double drainFactor() {
            return clamp(1.4 - ergonomics * 0.011, 0.3, 1.8);
        }

        /** 持枪稳定度 → 开镜晃动倍率：对比强烈——低稳定度明显晃、
         *  高稳定度稳如磐石（1≈1.54，50≈0.90，88≈0.41）。 */
        public double swayFactor() {
            return clamp(1.55 - stability * 0.013, 0.15, 1.75);
        }

        /** 加上配件增量后的新属性（正数=提升）。 */
        public GunStats add(int dv, int dh, int de, int ds) {
            return new GunStats(vertical + dv, horizontal + dh,
                ergonomics + de, stability + ds);
        }

        private static double clamp(double v, double min, double max) {
            return Math.max(min, Math.min(max, v));
        }
    }

    /** 未配置/识别失败枪械的中庸默认值。 */
    public static final GunStats FALLBACK =
        new GunStats(64, 60, 60, 60);

    private static final Path PATH = FMLPaths.CONFIGDIR.get()
        .resolve("fragmentation").resolve("gun_recoil.json");
    private static final Type WRAPPER_TYPE =
        new TypeToken<Wrapper>() { }.getType();

    private static Map<String, GunStats> guns;
    private static Map<String, GunStats> attachments;

    private GunRecoilConfig() {
    }

    /** 查询某把枪（TACZ 枪械ID，如 eft:ak47）的基础属性（不含配件）。 */
    public static GunStats get(String gunId) {
        ensureLoaded();
        GunStats stats = guns.get(gunId);
        return stats != null ? stats : FALLBACK;
    }

    /**
     * 查询某个配件（TACZ 配件ID，如 eft:grip_rk6）的属性加成增量。
     * 未配置返回 null。用于枪械 tooltip 显示每个配件的加成明细。
     */
    public static GunStats getAttachmentBonus(String attachmentId) {
        ensureLoaded();
        return attachments.get(attachmentId);
    }

    /**
     * 查询某把枪**包含当前已装配件加成**后的最终属性。
     * 用于开火/开镜/体力计算时取真实值。
     */
    public static GunStats get(ItemStack stack) {
        if (!(stack.getItem() instanceof IGun gun)) {
            return FALLBACK;
        }
        String gunId = gun.getGunId(stack).toString();
        GunStats base = get(gunId);
        int dv = 0, dh = 0, de = 0, ds = 0;
        for (AttachmentType type : AttachmentType.values()) {
            ResourceLocation id = gun.getAttachmentId(stack, type);
            if (id == null) {
                continue;
            }
            GunStats mod = attachments.get(id.toString());
            if (mod != null) {
                dv += mod.vertical;
                dh += mod.horizontal;
                de += mod.ergonomics;
                ds += mod.stability;
            }
        }
        return base.add(dv, dh, de, ds);
    }

    private static synchronized void ensureLoaded() {
        if (guns != null) {
            return;
        }
        guns = new LinkedHashMap<>();
        attachments = new LinkedHashMap<>();
        guns.putAll(defaultGuns());
        if (!Files.exists(PATH)) {
            save();
            return;
        }
        try {
            String json = Files.readString(PATH, StandardCharsets.UTF_8);
            Wrapper loaded =
                new GsonBuilder().create().fromJson(json, WRAPPER_TYPE);
            if (loaded != null) {
                if (loaded.guns != null) {
                    guns.putAll(loaded.guns);
                }
                if (loaded.attachments != null) {
                    attachments.putAll(loaded.attachments);
                }
            }
        } catch (Exception e) {
            // 配置损坏：保留默认并回写，避免静默失效
            save();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Wrapper wrapper = new Wrapper();
            wrapper.guns = guns;
            wrapper.attachments = attachments;
            String json = new GsonBuilder().setPrettyPrinting().create()
                .toJson(wrapper);
            Files.writeString(PATH, json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static final class Wrapper {
        public Map<String, GunStats> guns = new LinkedHashMap<>();
        public Map<String, GunStats> attachments = new LinkedHashMap<>();
    }

    /**
     * 默认属性表：数值风格参考暗区突围无限——数值越高枪械越好。
     * 大口径/全自动垂直评分低，短冲/小口径垂直评分高；栓狙稳定度
     * 高但人机低；大口径手枪评分接近步枪。
     */
    public static Map<String, GunStats> defaultGuns() {
        Map<String, GunStats> d = new LinkedHashMap<>();
        // ===== 手枪（小口径评分高） =====
        d.put("eft:ucp",        new GunStats(72, 60, 82, 50));
        d.put("eft:mp443",      new GunStats(70, 58, 80, 50));
        d.put("eft:92g",        new GunStats(70, 58, 80, 52));
        d.put("eft:fn57",       new GunStats(74, 62, 78, 52));
        d.put("eft:deagle_l6",  new GunStats(25, 22, 50, 45)); // .50 AE
        d.put("eft:rsh12",      new GunStats(28, 24, 55, 45)); // 12.7 转轮
        d.put("qkl:waltertl",   new GunStats(42, 34, 58, 48)); // 20/70
        // ===== 冲锋枪（低后坐力、高人机） =====
        d.put("eft:mp7a2",      new GunStats(75, 66, 84, 58));
        d.put("eft:mp7a1",      new GunStats(75, 66, 84, 58));
        d.put("eft:mp9n",       new GunStats(72, 64, 85, 56));
        d.put("eft:vector9mm",  new GunStats(76, 70, 76, 60)); // 短剑后坐极低
        d.put("eft:vector",     new GunStats(74, 68, 76, 60)); // .45
        d.put("eft:mp5",        new GunStats(70, 64, 78, 58));
        d.put("eft:sr2m",       new GunStats(68, 60, 72, 56));
        d.put("cib:pp19",       new GunStats(70, 64, 74, 58));
        d.put("tacz:ump45",     new GunStats(65, 60, 74, 58));
        d.put("qkl:csg19",      new GunStats(64, 56, 64, 54));
        // ===== 小口径/中间威力步枪 =====
        d.put("eft:m4",             new GunStats(63, 56, 72, 64));
        d.put("eft:m4sopmod2",      new GunStats(65, 58, 70, 66));
        d.put("eft:ak74n",          new GunStats(65, 54, 62, 62));
        d.put("eft:ak74m",          new GunStats(66, 55, 64, 63));
        d.put("ccrp:aks74u",        new GunStats(59, 48, 66, 58));
        d.put("tacz:g36k",          new GunStats(65, 56, 70, 64));
        d.put("tacz:qbz_95",        new GunStats(65, 56, 66, 64));
        d.put("eft:sdm_qbz95_223",  new GunStats(63, 54, 64, 64));
        d.put("eft:adar",           new GunStats(59, 54, 60, 68)); // 半自动
        d.put("eft:sr3m",           new GunStats(67, 54, 64, 60)); // 9x39
        d.put("eft:asval",          new GunStats(67, 56, 60, 60)); // 9x39 消音
        d.put("eft:vss",            new GunStats(63, 56, 52, 66));
        // ===== 7.62x39 / 大口径突击（后坐力大、评分低） =====
        d.put("eft:ak47",       new GunStats(43, 38, 58, 60));
        d.put("eft:akms",       new GunStats(41, 36, 56, 60));
        d.put("eft:ash12",      new GunStats(18, 20, 48, 62)); // 12.7x55
        // ===== 全威力 / 精确射手 / 栓狙（稳定高、人机低） =====
        d.put("eft:vpo101",     new GunStats(45, 42, 48, 68));
        d.put("eft:svds",       new GunStats(41, 40, 46, 72));
        d.put("eft:m9130s",     new GunStats(37, 38, 40, 74)); // 7.62x54R
        d.put("cib:sv98",       new GunStats(35, 40, 38, 80));
        d.put("tacz:m700",      new GunStats(33, 42, 40, 82));
        d.put("tacz:ai_awp",    new GunStats(18, 34, 34, 86)); // .338
        // ===== 霰弹枪 =====
        d.put("qkl:fk15",       new GunStats(37, 28, 40, 45)); // 16.5mm
        d.put("qkl:fk15p",      new GunStats(43, 32, 44, 48));
        d.put("qkl:clf10",      new GunStats(45, 34, 46, 50)); // 20/70
        d.put("ccrp:m1887_long",new GunStats(39, 30, 44, 52)); // 杠杆
        d.put("cib:686",        new GunStats(37, 28, 42, 50)); // 转轮霰弹
        d.put("qkl:m1897",      new GunStats(39, 30, 44, 52));
        d.put("tacz:aa12",      new GunStats(45, 38, 50, 58)); // 全自动
        d.put("cib:qbs09",      new GunStats(41, 32, 46, 54)); // 18.4mm
        d.put("eft:toz106",     new GunStats(49, 38, 50, 52)); // 20/70 栓动
        // ===== 榴弹发射器 =====
        d.put("tacz:m320",          new GunStats(70, 70, 30, 40));
        d.put("classicr:mgl_40mm",  new GunStats(55, 55, 26, 38));
        return d;
    }
}
