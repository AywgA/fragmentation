package com.fragmentation.client;

import com.fragmentation.FragmentationMod;
import com.fragmentation.network.ShieldingFieldHitPacket;
import com.fragmentation.network.ShieldingFieldStatePacket;
import com.fragmentation.shielding.ShieldingFieldManager;
import com.fragmentation.shielding.ShieldingFieldState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.ShoulderRidingEntity;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏蔽场客户端表现（沙丘 2021 电影风格，事件驱动状态机）：
 *
 * 1. 默认完全不可见：无粒子、无光晕、无持续罩体。
 * 2. 仅在子弹命中瞬间显现：0~1 tick 内出现，约 3 tick 内快速衰减消失。
 * 3. 形态：三层同心【布面光罩】——像一张布盖在身上（连续曲面，无环形线带），
 *    自动兼容人形（玩家/僵尸等）、村民（长袍）、四足（牛马狼等）轮廓。
 * 4. 高亮集中在【实际落点】：亮斑以命中点为中心高斯衰减（打哪亮哪，不同落点不同亮斑）。
 *    配色按命中模式区分：深蓝（高速挡下）/ 浅蓝（负载高、降级、过载、重建等高压力状态）/
 *    深红（慢速物体穿过，或屏蔽场失去防护效果）。
 * 5. 奔跑时：屏蔽场以极淡蓝色显现，并在身后留下布面拖影。
 * 6. 轮廓与命中点均随实体移动（命中点存相对偏移）。
 * 7. 噪声全部为连续正弦流动（无每帧随机跳变，不爆闪）；护盾余量 <20% 时闪烁加快。
 * 8. 渲染使用加法混合，直接输出到主帧缓冲。
 */
@Mod.EventBusSubscriber(
    modid = FragmentationMod.MOD_ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ClientShieldingFieldRenderer {

    /** 服务端同步的展示配置 */
    private static volatile String selfDisplayName = "霍尔茨曼屏蔽场";
    private static volatile List<String> selfActivators = new ArrayList<>();
    /** 服务端同步的屏蔽场佩戴者实体 ID（用于奔跑拖影） */
    private static volatile Set<Integer> wearers = Set.of();

    private ClientShieldingFieldRenderer() {
    }

    // ==================== 配置包 ====================

    public static void updateState(ShieldingFieldStatePacket packet) {
        if (packet.displayName() != null && !packet.displayName().isEmpty()) {
            selfDisplayName = packet.displayName();
        }
        if (packet.activators() != null) {
            selfActivators = new ArrayList<>(packet.activators());
        }
    }

    /** 服务端同步佩戴者列表（每秒一次，供奔跑拖影使用） */
    public static void updateWearers(int[] entityIds) {
        Set<Integer> set = new HashSet<>();
        for (int id : entityIds) set.add(id);
        wearers = set;
        // 清理已不佩戴的实体拖影缓存
        TRAILS.keySet().removeIf(k -> !set.contains(k));
    }

    public static String selfDisplayName() {
        return selfDisplayName;
    }

    public static boolean isActivatorItem(String itemId) {
        if (itemId == null) return false;
        for (String id : selfActivators) {
            if (itemId.equals(id)) return true;
        }
        return false;
    }

    // ==================== Tooltip（读物品 NBT，从外到内显示） ====================

    /** 为屏蔽场发生器物品构建 tooltip 行（仅客户端调用） */
    public static List<Component> fieldTooltipLines(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(selfDisplayName).withStyle(ChatFormatting.GOLD));

        CompoundTag tag = stack.getTagElement(ShieldingFieldManager.FIELD_NBT_KEY);
        if (tag == null) {
            // 全新发生器：三层完好、零负载
            lines.add(Component.literal("次层状态: 正常").withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("主层负载: 0 J").withStyle(ChatFormatting.BLUE));
            lines.add(Component.literal("负层负载: 0 J").withStyle(ChatFormatting.AQUA));
            return lines;
        }

        ShieldingFieldState state = ShieldingFieldState.load(tag);
        long now = Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.getGameTime() : 0L;

        switch (state.phase) {
            case FAILED -> {
                lines.add(Component.literal("状态: 负载过高，已失效").withStyle(ChatFormatting.RED));
                long remainTicks = Math.max(0L, state.phaseEndTick - now);
                lines.add(Component.literal("恢复剩余时间: " + formatTime(remainTicks))
                    .withStyle(ChatFormatting.RED));
            }
            case OVERLOAD -> {
                // 主层/次层已碎，只显示仍然工作的负层
                double cap = state.effectiveNegativeCap > 0 ? state.effectiveNegativeCap : 0;
                lines.add(Component.literal("主层/次层: 已碎除").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.literal(
                    "负层负载: " + formatLoad(state.negativeLoad, cap)
                ).withStyle(ChatFormatting.AQUA));
                long remainTicks = Math.max(0L, state.phaseEndTick - now);
                lines.add(Component.literal("过载剩余时间: " + formatTime(remainTicks))
                    .withStyle(ChatFormatting.GOLD));
            }
            case RECOVERING -> {
                lines.add(Component.literal("状态: 重建中").withStyle(ChatFormatting.YELLOW));
                lines.add(Component.literal(
                    "次层完整度: " + String.format("%.0f%%", state.secondaryIntegrity * 100.0D)
                ).withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal(
                    "主层负载: " + formatLoad(state.mainLoad, state.effectiveMainCap)
                ).withStyle(ChatFormatting.BLUE));
                lines.add(Component.literal(
                    "负层负载: " + formatLoad(state.negativeLoad, state.effectiveNegativeCap)
                ).withStyle(ChatFormatting.AQUA));
            }
            case ACTIVE -> {
                lines.add(Component.literal("次层状态: 正常").withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal(
                    "主层负载: " + formatLoad(state.mainLoad, state.effectiveMainCap)
                ).withStyle(ChatFormatting.BLUE));
                lines.add(Component.literal(
                    "负层负载: " + formatLoad(state.negativeLoad, state.effectiveNegativeCap)
                ).withStyle(ChatFormatting.AQUA));
            }
        }
        return lines;
    }

    private static String formatLoad(double load, double cap) {
        double clamped = Math.max(0.0D, load);
        if (cap > 0.0D) {
            clamped = Math.min(clamped, cap);
            return String.format("%.0f / %.0f J (%.1f%%)", clamped, cap, clamped / cap * 100.0D);
        }
        return String.format("%.0f J", clamped);
    }

    private static String formatTime(long ticks) {
        long totalSeconds = ticks / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    // ==================== 加法混合渲染类型（直接输出主帧缓冲，双面） ====================

    private static final RenderType GLOW_TYPE = ShieldGlowTypes.GLOW;

    /** 继承 RenderType 以访问受保护的渲染状态分片 */
    private static final class ShieldGlowTypes extends RenderType {
        private ShieldGlowTypes() {
            super("fragment_shield_glow", null, null, 0, false, false, null, null);
        }

        static final RenderType GLOW = create(
            "fragment_shield_glow",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            65536,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RENDERTYPE_LIGHTNING_SHADER)   // position_color 着色器
                .setTransparencyState(LIGHTNING_TRANSPARENCY)  // 加法混合
                .setWriteMaskState(COLOR_WRITE)                // 不写深度
                .setCullState(NO_CULL)                         // 双面可见
                .createCompositeState(false)
        );
    }

    // ==================== 命中脉冲（沙丘风格，事件驱动） ====================

    /** 深蓝色（高速挡下，正常状态）：边缘暗蓝 → 核心亮蓝 */
    private static final float[] BLUE_EDGE = {0.04F, 0.10F, 0.30F};
    private static final float[] BLUE_CORE = {0.25F, 0.55F, 1.00F};
    /** 浅蓝色（高压力状态：负载高/降级/过载/重建）：边缘淡蓝 → 核心近白亮蓝 */
    private static final float[] LIGHT_BLUE_EDGE = {0.20F, 0.45F, 0.80F};
    private static final float[] LIGHT_BLUE_CORE = {0.70F, 0.92F, 1.00F};
    /** 深红色（慢速穿过 / 屏蔽场失效）：边缘暗红 → 核心亮红 */
    private static final float[] RED_EDGE = {0.30F, 0.04F, 0.04F};
    private static final float[] RED_CORE = {0.85F, 0.15F, 0.15F};
    /** 奔跑拖影的极淡蓝色 */
    private static final float[] TRAIL_BLUE = {0.45F, 0.75F, 1.00F};

    /** 脉冲存活时长（tick）：柔和上升 → 短暂保持 → 放射状扩散并平滑淡出 */
    private static final float PULSE_LIFETIME_TICKS = 5.0F;
    /** 三层布面光罩相对皮肤表面的均匀间隙（格）：次层最外，保证轮廓清晰且不贴脸 */
    private static final float[] LAYER_GAPS = {0.36F, 0.28F, 0.21F};
    /** 轮廓纵向层数（高密度网格，保证径向渐变无颗粒感） */
    private static final int RING_LAYERS = 14;
    /** 每层环段数（高密度网格，保证径向渐变无颗粒感） */
    private static final int RING_SEGMENTS = 36;
    /** 命中层（primary）亮度倍率，其余层减弱以显层次 */
    private static final float NON_PRIMARY_FACTOR = 0.45F;
    /** 护盾余量低于 20%（负载 ≥80%）时闪烁加快 */
    private static final float LOW_RESERVE_RATIO = 0.80F;

    /** 每实体的活动脉冲：entityId -> List<Pulse>（同一游戏刻的多层命中合并为一次脉冲） */
    private static final Map<Integer, List<Pulse>> PULSES = new ConcurrentHashMap<>();

    /** 实体尚未同步到客户端时暂存的命中包（最多等几 tick，避免刚加载实体时特效丢失） */
    private static final List<PendingPacket> PENDING = new ArrayList<>();

    private record PendingPacket(ShieldingFieldHitPacket packet, long recvTick) {
    }

    /** 轮廓环缓存：key 由 (形状种类, 高度, 宽度, 层缩放) 组成 */
    private static final Map<Long, List<Vec3[]>> RINGS_CACHE = new ConcurrentHashMap<>();

    /** 奔跑拖影样本窗口（毫秒） */
    private static final long TRAIL_WINDOW_MS = 450L;
    /** 拖影历史：entityId -> 位置样本（仅奔跑时采样） */
    private static final Map<Integer, List<TrailSample>> TRAILS = new ConcurrentHashMap<>();

    private record TrailSample(Vec3 pos, long millis) {
    }

    /**
     * 单次命中脉冲。relHit = 命中点相对实体脚底中心的偏移（随实体移动，保证贴身附着）。
     * colorMode 决定配色：深蓝（正常挡下）/ 浅蓝（高压力状态）/ 深红（慢速穿过或失效）。
     */
    private record Pulse(Vec3 relHit, int layer, int colorMode,
                         float shieldRatio, long birthTick) {
    }

    // ==================== 慢速物品贴近（持续深红状态） ====================

    /** 贴近状态过期时间（tick）：服务端每 10 tick 重发，超过视为状态丢失 */
    private static final long SLOW_ZONE_EXPIRE_TICKS = 60L;
    /** 持续红光淡入时长（tick）——快速出现，贴近后红光立即点亮 */
    private static final float SLOW_GLOW_FADE_IN_TICKS = 2.0F;
    /** 持续红光淡出时长（tick）——离开后颜色缓慢淡出（1.5 秒），不闪烁 */
    private static final float SLOW_GLOW_FADE_OUT_TICKS = 30.0F;

    /** 服务端同步的贴近状态：targetId -> (贴近攻击者 ID 集合, 同步 tick) */
    private static final Map<Integer, SlowZone> SLOW_ZONES = new ConcurrentHashMap<>();
    /** 每个贴近攻击者的淡入淡出状态：attackerId -> SlowGlow */
    private static final Map<Integer, SlowGlow> SLOW_GLOWS = new ConcurrentHashMap<>();

    private record SlowZone(java.util.Set<Integer> attackerIds, long syncTick) {
    }

    /**
     * 单个贴近攻击者的红光状态。present = 当前是否贴近；
     * fade 从 changeTick 起由 fadeAtChange 向目标值（present ? 1 : 0）线性过渡。
     */
    private record SlowGlow(int targetId, boolean present, float fadeAtChange, long changeTick) {
    }

    /** 服务端同步慢速贴近状态（targetId, 贴近攻击者 ID 列表） */
    public static void updateSlowZones(int targetId, int[] attackerIds) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();

        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int id : attackerIds) ids.add(id);
        SLOW_ZONES.put(targetId, new SlowZone(ids, now));

        // 在场内的攻击者：显示红光（淡入到最大亮度，保持）
        for (int id : ids) {
            SlowGlow g = SLOW_GLOWS.get(id);
            float fade = g == null ? 0.0F : slowGlowFade(g, now);
            SLOW_GLOWS.put(id, new SlowGlow(targetId, true, fade, now));
        }
        // 刚离开的攻击者（之前在场内，现在不在）：从最大亮度开始淡出（不闪烁）
        for (var entry : SLOW_GLOWS.entrySet()) {
            SlowGlow g = entry.getValue();
            if (g.targetId() == targetId && g.present() && !ids.contains(entry.getKey())) {
                SLOW_GLOWS.put(entry.getKey(), new SlowGlow(targetId, false, 1.0F, now));
            }
        }
        // 清理已完全淡出且彻底消失的目标
        SLOW_ZONES.entrySet().removeIf(e -> now - e.getValue().syncTick() > SLOW_ZONE_EXPIRE_TICKS * 4L);
    }

    /** 计算 SlowGlow 当前淡入淡出系数（0~1）：淡入 2 tick 快速点亮，淡出 5 tick 平滑消失 */
    private static float slowGlowFade(SlowGlow g, long now) {
        float ticks = g.present() ? SLOW_GLOW_FADE_IN_TICKS : SLOW_GLOW_FADE_OUT_TICKS;
        float dt = (now - g.changeTick()) / ticks;
        return g.present()
            ? Math.min(1.0F, g.fadeAtChange() + dt)
            : Math.max(0.0F, g.fadeAtChange() - dt);
    }

    /** 每 tick 维护贴近红光状态：过期目标触发淡出、完全淡出的删除 */
    private static void tickSlowGlows(long now) {
        // SLOW_ZONES 过期（服务端久未同步）：视为所有在场内攻击者离开，从最大亮度淡出
        SLOW_ZONES.entrySet().removeIf(e -> {
            if (now - e.getValue().syncTick() > SLOW_ZONE_EXPIRE_TICKS) {
                for (int attackerId : e.getValue().attackerIds()) {
                    SLOW_GLOWS.put(attackerId, new SlowGlow(e.getKey(), false, 1.0F, now));
                }
                return true;
            }
            return false;
        });
        // 清理已完全淡出的 SlowGlow
        SLOW_GLOWS.entrySet().removeIf(e -> {
            SlowGlow g = e.getValue();
            return !g.present() && slowGlowFade(g, now) <= 0.001F
                && now - g.changeTick() > (long) SLOW_GLOW_FADE_OUT_TICKS + 1L;
        });
    }

    public static void handle(ShieldingFieldHitPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        int entityId = packet.entityId();
        if (packet.layer() < 0 || packet.layer() > 2) return;

        Entity entity = mc.level.getEntity(entityId);
        if (entity == null) {
            // 实体尚未同步：暂存，等渲染帧再尝试（避免刚进入视野就中弹时特效丢失）
            PENDING.add(new PendingPacket(packet, mc.level.getGameTime()));
            while (PENDING.size() > 40) PENDING.remove(0);
            return;
        }
        registerPulse(entity, packet);
    }

    /** 按命中包为实体登记脉冲（命中点存相对偏移，随实体移动） */
    private static void registerPulse(Entity entity, ShieldingFieldHitPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level.getGameTime();
        // 命中点存为相对实体脚底的偏移：实体移动时效果跟随，始终贴身
        Vec3 relHit = packet.hitPos().subtract(entity.position());
        if (relHit.lengthSqr() > 25.0D) {
            // 偏移过大（异常坐标）时钳制到 2 格内，防止效果飘到别处
            relHit = relHit.normalize().scale(2.0D);
        }
        // 垂直方向钳制在身体附近，防止高速子弹的坏坐标把亮斑带到脚下/空中
        float maxY = entity.getBbHeight() + 0.3F;
        if (relHit.y < -0.2D) relHit = new Vec3(relHit.x, -0.2D, relHit.z);
        if (relHit.y > maxY) relHit = new Vec3(relHit.x, maxY, relHit.z);

        List<Pulse> list = PULSES.computeIfAbsent(entity.getId(), k -> new ArrayList<>());

        // 同一游戏刻内多层命中合并为一次脉冲：接触点取最新，配色取最严重，层取最外
        for (int i = 0; i < list.size(); i++) {
            Pulse p = list.get(i);
            if (p.birthTick() == now) {
                int layer = Math.min(packet.layer(), p.layer());
                int colorMode = Math.max(packet.colorMode(), p.colorMode());
                list.set(i, new Pulse(
                    relHit,
                    layer,
                    colorMode,
                    packet.shieldRatio() >= 0.0F ? packet.shieldRatio() : p.shieldRatio(),
                    now
                ));
                playImpactSound(entity.getId(), packet.fast());
                return;
            }
        }

        list.add(new Pulse(relHit, packet.layer(), packet.colorMode(),
            packet.shieldRatio(), now));
        playImpactSound(entity.getId(), packet.fast());
    }

    /** 渲染帧尝试消费暂存的命中包（实体已同步则登记，超时丢弃） */
    private static void drainPending(ClientLevel level, long now) {
        if (PENDING.isEmpty()) return;
        PENDING.removeIf(pending -> {
            if (now - pending.recvTick() > 6L) return true;
            Entity entity = level.getEntity(pending.packet().entityId());
            if (entity == null) return false;
            registerPulse(entity, pending.packet());
            return true;
        });
    }

    /**
     * 命中音效钩子（音效已在服务端 onHit 中接入 ArmorSoundManager，此客户端钩子保留备用）。
     */
    @SuppressWarnings("unused")
    private static void playImpactSound(int entityId, boolean fast) {
        // 备用：如需客户端本地播放可在此接入（保持与光效同一 tick）
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        long now = level.getGameTime();
        drainPending(level, now);
        tickSlowGlows(now);
        // 只按时间清理脉冲；实体瞬时丢失（同步抖动）不删，避免他视角闪烁
        PULSES.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(p -> now - p.birthTick() > (long) PULSE_LIFETIME_TICKS + 2L);
            return entry.getValue().isEmpty();
        });

        Set<Integer> currentWearers = wearers;
        if (PULSES.isEmpty() && currentWearers.isEmpty() && SLOW_GLOWS.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(GLOW_TYPE);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f pose = poseStack.last().pose();

        if (!PULSES.isEmpty()) {
            for (var entry : PULSES.entrySet()) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity == null) continue;
                for (Pulse pulse : entry.getValue()) {
                    renderPulse(consumer, pose, camera, entity, pulse, now);
                }
            }
        }

        // 慢速物品贴近：持续深红光晕
        if (!SLOW_GLOWS.isEmpty()) {
            renderSlowZones(consumer, pose, camera, level, now);
        }

        renderTrails(consumer, pose, camera, level, mc, currentWearers);

        poseStack.popPose();
        buffers.endBatch(GLOW_TYPE);
    }

    /** 渲染一次命中脉冲：仅三层布面光罩，落点最亮并沿布面向四周放射状渐隐 */
    private static void renderPulse(
        VertexConsumer consumer, Matrix4f pose, Vec3 camera,
        Entity entity, Pulse pulse, long now
    ) {
        float age = (now - pulse.birthTick());
        float env = pulseEnvelope(age);
        if (env <= 0.0F) return;

        boolean lowReserve = pulse.shieldRatio() >= LOW_RESERVE_RATIO;
        // 按命中配色模式选色板：深蓝（正常挡下）/ 浅蓝（高压力状态）/ 深红（慢速穿过或失效）
        float[] edge;
        float[] core;
        switch (pulse.colorMode()) {
            case ShieldingFieldHitPacket.MODE_RED -> {
                edge = RED_EDGE;
                core = RED_CORE;
            }
            case ShieldingFieldHitPacket.MODE_LIGHT_BLUE -> {
                edge = LIGHT_BLUE_EDGE;
                core = LIGHT_BLUE_CORE;
            }
            default -> {
                edge = BLUE_EDGE;
                core = BLUE_CORE;
            }
        }

        int kind = shapeKindOf(entity);
        float h = entity.getBbHeight();
        float w = entity.getBbWidth();
        Vec3 base = entity.position();                       // 脚底中心（tick 位置，跟随移动）
        double cy = h * centerRatio(kind);                   // 身体中心
        Vec3 relCenter = new Vec3(0.0D, cy, 0.0D);

        double tSec = Util.getMillis() / 1000.0D;
        float noiseAmp = lowReserve ? 0.020F : 0.010F;

        // 亮斑中心 = 落点沿身体中轴径向投影到该层盾面（球状判定思路，打哪亮哪）
        float yawRad = (float) Math.toRadians(entity.getYRot());
        Vec3[] spotCenters = new Vec3[3];
        for (int layer = 0; layer < 3; layer++) {
            spotCenters[layer] = projectOnShell(
                kind, h, w, LAYER_GAPS[layer], yawRad, pulse.relHit());
        }
        // 受击方向取命中层盾面上的投影点（即使原始落点坐标异常也不会跑到脚下）
        Vec3 primarySpot = spotCenters[pulse.layer()];
        Vec3 hitDir = primarySpot.subtract(relCenter);
        double hl = hitDir.length();
        hitDir = hl > 0.05D ? hitDir.scale(1.0D / hl) : new Vec3(0.0D, 0.0D, 1.0D);

        // 双层高斯光晕：亮核 + 大范围柔光；柔光半径随时间扩大，形成“击中后光沿布面放射散开”
        float lifeT = Math.max(0.0F, Math.min(1.0F, age / PULSE_LIFETIME_TICKS));
        float coreSigma = 0.20F + 0.08F * lifeT;
        float haloSigma = 0.42F + 0.55F * lifeT;
        float invCore = 1.0F / (2.0F * coreSigma * coreSigma);
        float invHalo = 1.0F / (2.0F * haloSigma * haloSigma);

        for (int layer = 0; layer < 3; layer++) {
            boolean primary = (layer == pulse.layer());
            float layerFactor = primary ? 1.0F : NON_PRIMARY_FACTOR;
            List<Vec3[]> rings = rotatedRings(
                outlineRings(kind, h, w, LAYER_GAPS[layer]), yawRad);
            // 连续微闪（正弦，无随机跳变）；低余量时闪烁加快
            float flicker = lowReserve
                ? 0.70F + 0.30F * (float) Math.sin(tSec * 90.0D + layer * 2.1D)
                : 0.88F + 0.12F * (float) Math.sin(tSec * 30.0D + layer * 2.1D);

            renderClothShell(consumer, pose, camera, base, rings, h,
                relCenter, hitDir, spotCenters[layer], invCore, invHalo,
                env, layerFactor, flicker, edge, core, tSec, noiseAmp);
        }
    }

    /** 渲染所有慢速贴近红光 */
    private static void renderSlowZones(
        VertexConsumer consumer, Matrix4f pose, Vec3 camera,
        ClientLevel level, long now
    ) {
        for (var entry : SLOW_GLOWS.entrySet()) {
            SlowGlow glow = entry.getValue();
            float fade = slowGlowFade(glow, now);
            if (fade <= 0.02F) continue;
            Entity target = level.getEntity(glow.targetId());
            Entity attacker = level.getEntity(entry.getKey());
            if (!(target instanceof LivingEntity living) || !(attacker instanceof net.minecraft.world.entity.player.Player player)) continue;
            if (target.isRemoved() || attacker.isRemoved() || !player.isAlive()) continue;
            renderSlowZone(consumer, pose, camera, living, player, fade);
        }
    }

    /**
     * 单个慢速贴近红光：接触点持续深红高斯光晕（呼吸式微脉动，不熄灭）。
     * "打在哪层哪层红"：按攻击者身体近边缘与各层盾面半径的贴合程度选择唯一的红色层。
     */
    private static void renderSlowZone(
        VertexConsumer consumer, Matrix4f pose, Vec3 camera,
        LivingEntity target, net.minecraft.world.entity.player.Player attacker, float fade
    ) {
        int kind = shapeKindOf(target);
        float h = target.getBbHeight();
        float w = target.getBbWidth();
        Vec3 base = target.position();
        double cy = h * centerRatio(kind);
        Vec3 relCenter = new Vec3(0.0D, cy, 0.0D);
        double tSec = Util.getMillis() / 1000.0D;
        float yawRad = (float) Math.toRadians(target.getYRot());

        // 接触点：攻击者眼睛 → 目标中心的射线与目标碰撞箱的进入点（相对脚底）
        Vec3 contact = slowContactPos(attacker, target).subtract(base);
        Vec3[] spots = new Vec3[3];
        for (int layer = 0; layer < 3; layer++) {
            spots[layer] = projectOnShell(kind, h, w, LAYER_GAPS[layer], yawRad, contact);
        }

        // 层选择：攻击者身体近边缘到目标中轴的水平距离，与哪层盾面半径最接近，哪层变红
        double axisDist = Math.sqrt(horizontalDistSq(attacker.position(), base));
        double nearEdge = Math.max(0.0D, axisDist - attacker.getBbWidth() * 0.5D);
        int chosenLayer = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int layer = 0; layer < 3; layer++) {
            double r = Math.sqrt(spots[layer].x * spots[layer].x + spots[layer].z * spots[layer].z);
            double diff = Math.abs(r - nearEdge);
            if (diff < bestDiff) {
                bestDiff = diff;
                chosenLayer = layer;
            }
        }

        Vec3 spot = spots[chosenLayer];
        Vec3 hitDir = spot.subtract(relCenter);
        double hl = hitDir.length();
        hitDir = hl > 0.05D ? hitDir.scale(1.0D / hl) : new Vec3(0.0D, 0.0D, 1.0D);

        // 持续呼吸式红光：柔和正弦脉动，不熄灭；比命中脉冲更弥散的大范围柔光
        float breathe = 0.78F + 0.22F * (float) Math.sin(tSec * 36.0D);
        float env = fade * breathe * 0.9F;
        float invCore = 1.0F / (2.0F * 0.26F * 0.26F);
        float invHalo = 1.0F / (2.0F * 0.75F * 0.75F);

        List<Vec3[]> rings = rotatedRings(
            outlineRings(kind, h, w, LAYER_GAPS[chosenLayer]), yawRad);
        renderClothShell(consumer, pose, camera, base, rings, h,
            relCenter, hitDir, spot, invCore, invHalo,
            env, 1.0F, 1.0F, RED_EDGE, RED_CORE, tSec, 0.010F);
    }

    /** 慢速贴近接触点：攻击者眼睛 → 目标中心的射线与目标碰撞箱的进入点 */
    private static Vec3 slowContactPos(Entity attacker, Entity target) {
        Vec3 origin = attacker.getEyePosition();
        Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 dir = aim.subtract(origin);
        double len = dir.length();
        if (len < 1.0E-4D) return aim;
        dir = dir.scale(1.0D / len);

        AABB box = target.getBoundingBox().inflate(0.05D);
        double tEnter = rayBoxEntry(origin, dir, box);
        if (tEnter >= 0.0D && tEnter <= len + 1.0D) {
            return origin.add(dir.scale(tEnter));
        }
        return aim;
    }

    /** 射线与 AABB 的进入距离（不相交返回 -1） */
    private static double rayBoxEntry(Vec3 o, Vec3 d, AABB box) {
        double tMin = 0.0D;
        double tMax = Double.MAX_VALUE;
        double[][] axes = {
            {o.x, d.x, box.minX, box.maxX},
            {o.y, d.y, box.minY, box.maxY},
            {o.z, d.z, box.minZ, box.maxZ}
        };
        for (double[] a : axes) {
            if (Math.abs(a[1]) < 1.0E-9D) {
                if (a[0] < a[2] || a[0] > a[3]) return -1.0D;
            } else {
                double t1 = (a[2] - a[0]) / a[1];
                double t2 = (a[3] - a[0]) / a[1];
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return -1.0D;
            }
        }
        return tMin;
    }

    /** 两点间水平距离平方（忽略 y） */
    private static double horizontalDistSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /**
     * 一层连续布面：侧面网格 + 顶帽封口（像一张布罩在身上，脚底开口，不产生地面圆环）。
     * 亮度 = 极淡底光 + 落点双层高斯光晕（核心亮、外圈沿布面放射状平滑扩散至透明）。
     */
    private static void renderClothShell(
        VertexConsumer consumer, Matrix4f pose, Vec3 camera,
        Vec3 base, List<Vec3[]> rings, float h,
        Vec3 relCenter, Vec3 hitDir, Vec3 spotCenter,
        float invCore, float invHalo,
        float env, float layerFactor, float flicker,
        float[] edge, float[] core, double tSec, float noiseAmp
    ) {
        int n = rings.get(0).length;

        // 侧面：相邻高度层之间的连续四边形网格
        for (int i = 0; i < rings.size() - 1; i++) {
            Vec3[] ra = rings.get(i);
            Vec3[] rb = rings.get(i + 1);
            for (int j = 0; j < n; j++) {
                int j2 = (j + 1) % n;
                Vec3 p00 = ra[j];
                Vec3 p01 = ra[j2];
                Vec3 p10 = rb[j];
                Vec3 p11 = rb[j2];

                float[] v00 = clothShade(p00, relCenter, hitDir, spotCenter, invCore, invHalo,
                    h, env, layerFactor, flicker, edge, core);
                float[] v01 = clothShade(p01, relCenter, hitDir, spotCenter, invCore, invHalo,
                    h, env, layerFactor, flicker, edge, core);
                float[] v10 = clothShade(p10, relCenter, hitDir, spotCenter, invCore, invHalo,
                    h, env, layerFactor, flicker, edge, core);
                float[] v11 = clothShade(p11, relCenter, hitDir, spotCenter, invCore, invHalo,
                    h, env, layerFactor, flicker, edge, core);
                if (v00[3] < 0.008F && v01[3] < 0.008F && v10[3] < 0.008F && v11[3] < 0.008F) {
                    continue;
                }

                glowQuad(consumer, pose,
                    jitter(base, p00, tSec, noiseAmp), jitter(base, p10, tSec, noiseAmp),
                    jitter(base, p11, tSec, noiseAmp), jitter(base, p01, tSec, noiseAmp),
                    v00, v00[3], v10, v10[3], v11, v11[3], v01, v01[3]);
            }
        }

        // 仅头顶封口（布盖住头顶）；脚底刻意开口，避免地面出现像素圆环
        capFan(consumer, pose, camera, base, rings.get(rings.size() - 1),
            new Vec3(0.0D, h, 0.0D), relCenter, hitDir, spotCenter, invCore, invHalo,
            env, layerFactor, h, flicker, edge, core, tSec, noiseAmp);
    }

    /** 顶帽封口扇：环边与头顶中心连成三角形扇，亮度同样走落点径向光晕 */
    private static void capFan(
        VertexConsumer consumer, Matrix4f pose, Vec3 camera,
        Vec3 base, Vec3[] ring, Vec3 apexRel,
        Vec3 relCenter, Vec3 hitDir, Vec3 spotCenter,
        float invCore, float invHalo,
        float env, float layerFactor, float h, float flicker,
        float[] edge, float[] core, double tSec, float noiseAmp
    ) {
        int n = ring.length;
        float[] vApex = clothShade(apexRel, relCenter, hitDir, spotCenter, invCore, invHalo,
            h, env, layerFactor, flicker, edge, core);
        Vec3 apexJ = jitter(base, apexRel, tSec, noiseAmp);

        for (int j = 0; j < n; j++) {
            Vec3 p = ring[j];
            Vec3 q = ring[(j + 1) % n];
            float[] v1 = clothShade(p, relCenter, hitDir, spotCenter, invCore, invHalo,
                h, env, layerFactor, flicker, edge, core);
            float[] v2 = clothShade(q, relCenter, hitDir, spotCenter, invCore, invHalo,
                h, env, layerFactor, flicker, edge, core);
            if (v1[3] < 0.008F && v2[3] < 0.008F && vApex[3] < 0.008F) continue;

            glowQuad(consumer, pose,
                jitter(base, p, tSec, noiseAmp), jitter(base, q, tSec, noiseAmp),
                apexJ, apexJ,
                v1, v1[3], v2, v2[3], vApex, vApex[3], vApex, vApex[3]);
        }
    }

    /** 顶点位置加轻微正弦流动噪声（连续，不跳变，像布的波动） */
    private static Vec3 jitter(Vec3 base, Vec3 rel, double tSec, float noiseAmp) {
        double ph = rel.x * 9.1D + rel.y * 13.7D + rel.z * 7.3D;
        return base.add(rel).add(
            Math.sin(tSec * 13.0D + ph) * noiseAmp,
            Math.sin(tSec * 15.3D + ph * 1.4D) * noiseAmp,
            Math.sin(tSec * 11.7D + ph * 0.8D) * noiseAmp
        );
    }

    /** 相对偏移方向与命中方向的夹角余弦（背面为 0） */
    private static double facingOf(Vec3 rel, Vec3 relCenter, Vec3 hitDir) {
        Vec3 dir = rel.subtract(relCenter);
        double len = dir.length();
        if (len < 1.0E-4D) return 0.0D;
        return Math.max(0.0D, dir.scale(1.0D / len).dot(hitDir));
    }

    /** 高度淡出因子：脚/头两端稍暗，中部全亮（头部不至于消失） */
    private static double heightFade(float relY, float h) {
        double t = Math.max(0.0D, Math.min(1.0D, relY / h));
        return Math.max(0.12D, Math.sin(Math.PI * (0.15D + 0.70D * t)));
    }

    /**
     * 布面顶点着色：极淡底光 + 落点【双层高斯径向光晕】。
     * 亮核（小而亮）+ 柔光（大范围，随时间扩大）：被击中处最亮，
     * 沿布面向四周放射状平滑衰减直至完全透明；受击面最亮，侧向保留柔光。
     * 颜色在 edge/core 两端色之间按光晕强度插值（色板由命中配色模式决定）。
     */
    private static float[] clothShade(
        Vec3 rel, Vec3 relCenter, Vec3 hitDir, Vec3 spotCenter,
        float invCore, float invHalo,
        float h, float env, float layerFactor, float flicker,
        float[] edge, float[] core
    ) {
        double distSq = rel.distanceToSqr(spotCenter);
        double coreGlow = Math.exp(-distSq * invCore);   // 亮核
        double halo = Math.exp(-distSq * invHalo);       // 放射状柔光
        float facing = (float) facingOf(rel, relCenter, hitDir);
        double fade = heightFade((float) rel.y, h);

        double glow = coreGlow + 0.45D * halo;
        float side = 0.60F + 0.40F * facing;             // 受击面最亮，侧向也有光
        float spot = (float) (glow * side);
        float a = (float) (env * layerFactor * fade * flicker * (0.03D + 0.30D * spot));
        a = Math.max(0.0F, Math.min(0.34F, a));

        float w = Math.max(0.0F, Math.min(1.0F, (float) (coreGlow * 1.25D + halo * 0.40D)));
        float[] c = new float[4];
        for (int i = 0; i < 3; i++) {
            c[i] = edge[i] + (core[i] - edge[i]) * w;
        }
        c[3] = a;
        return c;
    }

    /**
     * 将命中点沿身体中轴【径向投影】到该层盾面（球状判定思路）：
     * 保持命中高度与方位角不变，把水平半径放到椭圆盾面边界上。
     * 这样无论原始落点在体内/体外/坐标异常，亮斑都稳定出现在对应高度与方位的盾面上：
     * 打头亮头、打腿亮腿，从不同方向/距离射击都不会落到脚下。
     */
    private static Vec3 projectOnShell(int kind, float h, float w, float gap,
                                       float yawRad, Vec3 relHit) {
        // 世界相对坐标反旋转回实体局部空间（局部面朝 +Z）
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);
        double lx = relHit.x * cosY + relHit.z * sinY;
        double lz = -relHit.x * sinY + relHit.z * cosY;

        // 命中高度 → 轮廓纵向参数 t（与 outlineRings 的 y 映射一致）
        double t = (relHit.y / h - 0.02D) / 0.98D;
        t = Math.max(0.0D, Math.min(1.0D, t));
        float[] f = bodyProfile(kind, (float) t);
        float rx = w * 0.5F * f[0] + gap;
        float rz = w * 0.5F * f[1] + gap * 0.8F;

        // 沿同一方位角放到椭圆边界
        double ang = Math.atan2(lz, lx);
        double px = rx * Math.cos(ang);
        double pz = rz * Math.sin(ang);
        float py = (float) (h * (0.02F + 0.98F * t));

        // 旋转回世界坐标
        return new Vec3(
            px * cosY - pz * sinY,
            py,
            px * sinY + pz * cosY
        );
    }

    /**
     * 脉冲时间包络（两端均为 smoothstep，出现与消失都非常柔和）：
     * 0~0.6 tick 平滑亮起 → 之后平滑衰减至寿命结束。
     */
    private static float pulseEnvelope(float t) {
        if (t <= 0.0F || t >= PULSE_LIFETIME_TICKS) return 0.0F;
        if (t <= 0.6F) {
            float x = t / 0.6F;
            return x * x * (3.0F - 2.0F * x);
        }
        float f = 1.0F - (t - 0.6F) / (PULSE_LIFETIME_TICKS - 0.6F);
        return f * f * (3.0F - 2.0F * f);
    }

    // ==================== 形状兼容（人形 / 村民 / 四足） ====================

    private static final int KIND_HUMANOID = 0;
    private static final int KIND_VILLAGER = 1;
    private static final int KIND_QUADRUPED = 2;

    /** 按实体类别选择屏蔽场轮廓形状 */
    private static int shapeKindOf(Entity entity) {
        if (entity instanceof AbstractVillager) return KIND_VILLAGER;
        if (entity instanceof WaterAnimal) return KIND_QUADRUPED;
        if (entity instanceof Animal animal
            && !(animal instanceof FlyingAnimal)
            && !(animal instanceof ShoulderRidingEntity)) {
            return KIND_QUADRUPED;
        }
        return KIND_HUMANOID;
    }

    /** 身体中心相对高度（四足动物身体偏上） */
    private static float centerRatio(int kind) {
        return kind == KIND_QUADRUPED ? 0.62F : 0.55F;
    }

    /**
     * 生成贴合实体外形的水平轮廓环集合（实体局部空间，面朝 +Z），按形状/尺寸/间隙缓存。
     * 截面为椭圆（rx = 肩宽方向，rz = 前后厚度方向），半径 = 半宽×体型系数 + 固定间隙，
     * 保证布与皮肤间距全身一致（腿、脖子等窄处也不会贴死）。
     */
    private static List<Vec3[]> outlineRings(int kind, float h, float w, float gap) {
        long key = ((long) kind << 48)
            ^ ((long) Float.floatToIntBits(h) << 24)
            ^ (Float.floatToIntBits(w) * 7919L)
            ^ Float.floatToIntBits(gap);
        return RINGS_CACHE.computeIfAbsent(key, k -> {
            List<Vec3[]> rings = new ArrayList<>();
            for (int i = 0; i < RING_LAYERS; i++) {
                float t = i / (float) (RING_LAYERS - 1);       // 0=脚 1=头
                float y = h * (0.02F + 0.98F * t);
                float[] f = bodyProfile(kind, t);
                float rx = w * 0.5F * f[0] + gap;
                float rz = w * 0.5F * f[1] + gap * 0.8F;
                Vec3[] ring = new Vec3[RING_SEGMENTS];
                for (int j = 0; j < RING_SEGMENTS; j++) {
                    double ang = (j / (double) RING_SEGMENTS) * 2.0D * Math.PI;
                    ring[j] = new Vec3(Math.cos(ang) * rx, y, Math.sin(ang) * rz);
                }
                rings.add(ring);
            }
            return rings;
        });
    }

    /** 将局部空间轮廓环（面朝 +Z）按实体身体朝向旋转到世界坐标 */
    private static List<Vec3[]> rotatedRings(List<Vec3[]> local, float yawRad) {
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        List<Vec3[]> out = new ArrayList<>(local.size());
        for (Vec3[] ring : local) {
            Vec3[] r = new Vec3[ring.length];
            for (int i = 0; i < ring.length; i++) {
                Vec3 p = ring[i];
                r[i] = new Vec3(p.x * cos - p.z * sin, p.y, p.x * sin + p.z * cos);
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 体型曲线（脚→头），返回 {rx 系数, rz 系数}（相对碰撞箱半宽）：
     * 人形=肩宽身薄、细颈、独立头部；村民=长袍桶状、大头；四足=躯干沿朝向拉长。
     */
    private static float[] bodyProfile(int kind, float t) {
        float[] ts;
        float[] fx;
        float[] fz;
        switch (kind) {
            case KIND_VILLAGER -> {
                // 村民：长袍下摆与身体同宽（前后饱满）、胸窄、颈收、大头（略含鼻子厚度）
                ts = new float[]{0.00F, 0.12F, 0.52F, 0.64F, 0.76F, 0.84F, 0.90F, 1.00F};
                fx = new float[]{1.00F, 1.00F, 0.95F, 0.78F, 0.80F, 0.45F, 0.85F, 0.85F};
                fz = new float[]{1.00F, 1.00F, 0.90F, 0.60F, 0.55F, 0.45F, 0.85F, 0.90F};
            }
            case KIND_QUADRUPED -> {
                // 四足：四肢占四角（长宽都大）、桶状躯干沿朝向拉长、背顶收窄
                ts = new float[]{0.00F, 0.15F, 0.42F, 0.75F, 0.90F, 1.00F};
                fx = new float[]{0.95F, 0.95F, 1.00F, 1.00F, 0.80F, 0.60F};
                fz = new float[]{1.20F, 1.25F, 1.35F, 1.30F, 1.00F, 0.65F};
            }
            default -> {
                // 人形（玩家/僵尸等）：双腿并拢（窄而薄）→ 手臂垂两侧（肩宽）→
                // 胸身薄 → 细颈 → 独立头部（接近正圆）
                ts = new float[]{0.00F, 0.10F, 0.40F, 0.46F, 0.56F, 0.72F, 0.80F, 0.86F, 0.92F, 1.00F};
                fx = new float[]{0.83F, 0.83F, 0.83F, 0.95F, 0.85F, 1.00F, 1.00F, 0.38F, 0.83F, 0.83F};
                fz = new float[]{0.42F, 0.42F, 0.42F, 0.48F, 0.45F, 0.50F, 0.52F, 0.38F, 0.83F, 0.83F};
            }
        }
        for (int i = 1; i < ts.length; i++) {
            if (t <= ts[i]) {
                float f = (t - ts[i - 1]) / (ts[i] - ts[i - 1]);
                return new float[]{
                    fx[i - 1] + (fx[i] - fx[i - 1]) * f,
                    fz[i - 1] + (fz[i] - fz[i - 1]) * f
                };
            }
        }
        return new float[]{fx[fx.length - 1], fz[fz.length - 1]};
    }

    // ==================== 奔跑拖影（极淡蓝色布面） ====================

    /** 渲染所有佩戴者的奔跑拖影（仅奔跑时） */
    private static void renderTrails(VertexConsumer consumer, Matrix4f pose, Vec3 camera,
                                     ClientLevel level, Minecraft mc, Set<Integer> currentWearers) {
        if (currentWearers.isEmpty()) return;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        long nowMs = Util.getMillis();

        for (Integer id : currentWearers) {
            Entity entity = level.getEntity(id);
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                TRAILS.remove(id);
                continue;
            }
            if (!living.isSprinting()) {
                TRAILS.remove(id);
                continue;
            }
            // 第一人称下不渲染自己的拖影（避免糊脸）
            if (living == mc.player && firstPerson) {
                TRAILS.remove(id);
                continue;
            }

            List<TrailSample> samples = TRAILS.computeIfAbsent(id, k -> new ArrayList<>());
            samples.add(new TrailSample(living.position(), nowMs));
            samples.removeIf(s -> nowMs - s.millis() > TRAIL_WINDOW_MS);

            renderTrail(consumer, pose, camera, living, samples, nowMs);
        }
    }

    /** 当前位置极淡蓝色布面 + 身后两道渐淡的历史拖影 */
    private static void renderTrail(VertexConsumer consumer, Matrix4f pose, Vec3 camera,
                                    LivingEntity entity, List<TrailSample> samples, long nowMs) {
        float h = entity.getBbHeight();
        float w = entity.getBbWidth();
        int kind = shapeKindOf(entity);
        List<Vec3[]> rings = rotatedRings(
            outlineRings(kind, h, w, LAYER_GAPS[2]),
            (float) Math.toRadians(entity.getYRot()));
        double tSec = Util.getMillis() / 1000.0D;

        // 当前位置：极淡蓝色布面
        drawTrailCloth(consumer, pose, camera, entity.position(), rings, h, 0.10F, tSec);

        // 拖影：取约 150ms 与 300ms 前的历史位置，越旧越淡
        float[] ghostAges = {150.0F, 300.0F};
        float[] ghostAlpha = {0.050F, 0.024F};
        for (int g = 0; g < ghostAges.length; g++) {
            TrailSample best = null;
            float bestDiff = Float.MAX_VALUE;
            for (TrailSample s : samples) {
                float diff = Math.abs((nowMs - s.millis()) - ghostAges[g]);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            if (best == null || bestDiff > 120.0F) continue;
            // 几乎没移动就没有拖影
            if (best.pos().distanceToSqr(entity.position()) < 0.0009D) continue;
            drawTrailCloth(consumer, pose, camera, best.pos(), rings, h, ghostAlpha[g], tSec);
        }
    }

    /** 在指定位置画一层极淡蓝色布面（侧面网格 + 顶/底封口） */
    private static void drawTrailCloth(VertexConsumer consumer, Matrix4f pose, Vec3 camera,
                                       Vec3 base, List<Vec3[]> rings, float h,
                                       float alphaBase, double tSec) {
        int n = rings.get(0).length;
        float noiseAmp = 0.004F;

        for (int i = 0; i < rings.size() - 1; i++) {
            Vec3[] ra = rings.get(i);
            Vec3[] rb = rings.get(i + 1);
            for (int j = 0; j < n; j++) {
                int j2 = (j + 1) % n;
                Vec3 p00 = ra[j];
                Vec3 p01 = ra[j2];
                Vec3 p10 = rb[j];
                Vec3 p11 = rb[j2];
                float a00 = (float) (alphaBase * heightFade((float) p00.y, h));
                float a01 = (float) (alphaBase * heightFade((float) p01.y, h));
                float a10 = (float) (alphaBase * heightFade((float) p10.y, h));
                float a11 = (float) (alphaBase * heightFade((float) p11.y, h));
                if (a00 < 0.008F && a01 < 0.008F && a10 < 0.008F && a11 < 0.008F) continue;
                glowQuad(consumer, pose,
                    jitter(base, p00, tSec, noiseAmp), jitter(base, p10, tSec, noiseAmp),
                    jitter(base, p11, tSec, noiseAmp), jitter(base, p01, tSec, noiseAmp),
                    TRAIL_BLUE, a00, TRAIL_BLUE, a10, TRAIL_BLUE, a11, TRAIL_BLUE, a01);
            }
        }

        // 仅顶帽封口（脚底开口，与命中光罩一致，不产生地面圆环）
        capTrailFan(consumer, pose, camera, base, rings.get(rings.size() - 1),
            new Vec3(0.0D, h, 0.0D), h, alphaBase, tSec, noiseAmp);
    }

    /** 拖影布面封口扇（均匀淡 alpha） */
    private static void capTrailFan(VertexConsumer consumer, Matrix4f pose, Vec3 camera,
                                    Vec3 base, Vec3[] ring, Vec3 apexRel, float h,
                                    float alphaBase, double tSec, float noiseAmp) {
        float aApex = (float) (alphaBase * heightFade((float) apexRel.y, h));
        Vec3 apexJ = jitter(base, apexRel, tSec, noiseAmp);
        int n = ring.length;
        for (int j = 0; j < n; j++) {
            Vec3 p = ring[j];
            Vec3 q = ring[(j + 1) % n];
            float a1 = (float) (alphaBase * heightFade((float) p.y, h));
            float a2 = (float) (alphaBase * heightFade((float) q.y, h));
            if (a1 < 0.008F && a2 < 0.008F && aApex < 0.008F) continue;
            glowQuad(consumer, pose,
                jitter(base, p, tSec, noiseAmp), jitter(base, q, tSec, noiseAmp),
                apexJ, apexJ,
                TRAIL_BLUE, a1, TRAIL_BLUE, a2, TRAIL_BLUE, aApex, TRAIL_BLUE, aApex);
        }
    }

    /** 填充四边形（布面），四顶点可分别指定颜色与 alpha（连续渐变，无拼接缝） */
    private static void glowQuad(
        VertexConsumer consumer, Matrix4f pose,
        Vec3 p00, Vec3 p10, Vec3 p11, Vec3 p01,
        float[] c00, float a00, float[] c10, float a10,
        float[] c11, float a11, float[] c01, float a01
    ) {
        if (a00 <= 0.008F && a10 <= 0.008F && a11 <= 0.008F && a01 <= 0.008F) return;
        vertex(consumer, pose, p00.x, p00.y, p00.z, c00[0], c00[1], c00[2], a00);
        vertex(consumer, pose, p10.x, p10.y, p10.z, c10[0], c10[1], c10[2], a10);
        vertex(consumer, pose, p11.x, p11.y, p11.z, c11[0], c11[1], c11[2], a11);
        vertex(consumer, pose, p01.x, p01.y, p01.z, c01[0], c01[1], c01[2], a01);
    }

    private static void vertex(
        VertexConsumer consumer, Matrix4f pose,
        double x, double y, double z,
        float r, float g, float b, float a
    ) {
        consumer.vertex(pose, (float) x, (float) y, (float) z)
            .color(r, g, b, a)
            .endVertex();
    }
}
