package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.ballistics.BallisticsMath;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.shielding.ShieldingFieldManager;
import com.fragmentation.config.ShieldingFieldConfig;
import com.fragmentation.network.ShieldingFieldStatePacket;
import com.fragmentation.shielding.ShieldingFieldState;
import com.fragmentation.util.ArmorSoundManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 屏蔽场的服务端事件：
 * - 每 tick 推进所有佩戴发生器的生物（含玩家、盔甲架）的状态机
 * - 跟踪所有生物的近几 tick 水平位移（供近战拦截的移动判定）
 * - 每 20 tick 同步佩戴者实体 ID 列表（供客户端绘制奔跑拖影）
 * - 定期同步【展示配置】（名称、激活物品列表）给客户端，用于 tooltip 识别
 * - 拦截移动状态下的玩家近战攻击（奔跑/行走攻击被拦下，蹲下/站立穿透）
 *
 * 近战拦截为双层保险：
 * 1) AttackEntityEvent：玩家挥击实体（最早的拦截点）
 * 2) LivingAttackEvent：伤害实际生效前的兜底（覆盖模组近战、网络包竞态等情况；
 *    仅拦【直接伤害】（directEntity == 攻击者），子弹/箭矢等投射物不在此拦截）
 *
 * 状态本身保存在物品 NBT 中，随物品同步，无需额外同步负载数据。
 */
@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public final class ShieldingFieldEventHandler {

    private static final long CONFIG_SYNC_INTERVAL_TICKS = 40L;
    private static final long WEARER_SYNC_INTERVAL_TICKS = 20L;

    /** 慢速贴近状态无变化时的重发间隔（tick），防丢包导致客户端状态卡死 */
    private static final long SLOW_ZONE_SYNC_INTERVAL_TICKS = 10L;
    /** 慢速贴近进入距离（水平中心距，格）：贴身（小于 1 格）才算"低速物品进入屏蔽场" */
    private static final double SLOW_ZONE_ENTER_RANGE = 1.0D;
    /** 慢速贴近退出距离（格）：离开该距离才重置已播标记（滞回区间防临界抖动重复触发） */
    private static final double SLOW_ZONE_EXIT_RANGE = 1.5D;
    /** 慢速贴近垂直判定范围（格，脚底高差） */
    private static final double SLOW_ZONE_VERTICAL_RANGE = 2.0D;
    /** 慢速贴近的"静止"位移阈值（格/tick）：低于该值视为站立（≈1 m/s 以下均算慢速） */
    private static final double SLOW_ZONE_MOVE_THRESHOLD_BPT = 0.05D;

    /** 上一次发送的慢速贴近状态：目标实体 ID -> 贴近攻击者实体 ID 集合（含视觉滞回） */
    private static final Map<Integer, java.util.Set<Integer>> LAST_SLOW_ZONES = new HashMap<>();

    /**
     * 慢速音效【已触发】状态：目标 ID -> 攻击者 ID 集合。
     * 进入慢速区（< 1 格且慢速）时触发一次并锁定；只有持续 2 tick 远离超过
     * 退出距离（1.5 格）才解锁。1 格边界处的走停/位置抖动不会重复触发。
     */
    private static final Map<Integer, java.util.Set<Integer>> SLOW_SOUND_ARMED = new HashMap<>();
    /** 已锁定对连续处于退出距离外的 tick 数（pairKey -> 计数），用于去抖解锁 */
    private static final Map<Long, Integer> SLOW_SOUND_EXIT_TICKS = new HashMap<>();
    /** 退出距离外需持续多少 tick 才解锁（防临界位置抖动导致连续滴滴） */
    private static final int SLOW_SOUND_DISARM_CONFIRM_TICKS = 2;

    /**
     * CNPC 枪械投射物的估算弹头质量（kg）。与 TACZ 路径 resolveFieldBulletMass
     * 的兜底值（0.008kg）保持一致。
     */
    private static final double CNPC_BULLET_MASS_KG = 0.008D;

    /** 移动判定阈值（格/tick）：位移高于该值视为行走/奔跑（站立 ≈ 0） */
    private static final double MOVE_THRESHOLD_BPT = 0.02D;
    /** 移动历史窗口大小（tick 数） */
    private static final int HISTORY_SIZE = 5;
    /** 历史记录过期时间（tick） */
    private static final long HISTORY_EXPIRE_TICKS = 200L;

    /** 每实体最近几个 tick 的位置（滑动窗口，抗网络包顺序竞态） */
    private static final Map<UUID, ArrayDeque<Vec3>> POSITION_HISTORY = new HashMap<>();
    /** 每实体历史记录的更新 tick（用于过期清理） */
    private static final Map<UUID, Long> HISTORY_TICKS = new HashMap<>();

    private ShieldingFieldEventHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.getServer() == null) return;
        long gameTick = event.getServer().overworld().getGameTime();
        boolean syncConfig = gameTick % CONFIG_SYNC_INTERVAL_TICKS == 0L;
        boolean syncWearers = gameTick % WEARER_SYNC_INTERVAL_TICKS == 0L;

        // 清理过期的历史记录（实体离开/死亡 10 秒后）
        HISTORY_TICKS.entrySet().removeIf(e -> gameTick - e.getValue() > HISTORY_EXPIRE_TICKS);
        POSITION_HISTORY.keySet().removeIf(id -> !HISTORY_TICKS.containsKey(id));

        for (ServerLevel level : event.getServer().getAllLevels()) {
            // 推进所有佩戴屏蔽场发生器的生物（玩家 + 盔甲架等）
            java.util.List<LivingEntity> wearers = new java.util.ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living && !living.isRemoved() && living.isAlive()) {
                    wearers.add(living);
                }
            }
            ShieldingFieldManager.tick(wearers, gameTick);

            // 玩家背包（含副手/盔甲槽）内【未佩戴】的发生器同样回复负载
            for (ServerPlayer player : level.players()) {
                tickInventoryGenerators(player, gameTick);
            }

            // 记录滑动窗口位置（供近战攻击的移动判定）
            for (LivingEntity living : wearers) {
                ArrayDeque<Vec3> dq = POSITION_HISTORY.computeIfAbsent(
                    living.getUUID(), k -> new ArrayDeque<>());
                dq.addLast(living.position());
                while (dq.size() > HISTORY_SIZE) {
                    dq.pollFirst();
                }
                HISTORY_TICKS.put(living.getUUID(), gameTick);
            }

            // 慢速物品贴近佩戴者：持续深红状态（沙丘设定：慢速穿透）
            tickSlowZones(level, gameTick, wearers);

            if (syncWearers) {
                int[] ids = new int[wearers.size()];
                int n = 0;
                for (LivingEntity living : wearers) {
                    if (ShieldingFieldManager.isFieldActive(living)) {
                        ids[n++] = living.getId();
                    }
                }
                int[] trimmed = n == ids.length ? ids : java.util.Arrays.copyOf(ids, n);
                for (ServerPlayer player : level.players()) {
                    ModNetwork.sendShieldingFieldWearers(player, trimmed);
                }
            }

            if (syncConfig) {
                for (ServerPlayer player : level.players()) {
                    syncConfig(player);
                }
            }
        }
    }

    // ==================== 慢速物品贴近（持续深红状态） ====================

    /**
     * 每 tick 扫描佩戴者：找出于贴身距离内【蹲下或几乎静止】的玩家（低速物品）。
     * 只要低速物品还贴着屏蔽场，客户端就持续显示深红色光晕（在哪层盾面就在哪层红）。
     * 变化即同步；无变化每 10 tick 重发，防丢包导致客户端状态卡死。
     * <p>
     * 进入判定 = 水平中心距小于 1 格；已播 disu 音效的标记只在攻击者远离超过
     * 退出距离（1.5 格，滞回）后才重置，临界徘徊不会连续触发。
     */
    private static void tickSlowZones(ServerLevel level, long gameTick, java.util.List<LivingEntity> wearers) {
        double enterSq = SLOW_ZONE_ENTER_RANGE * SLOW_ZONE_ENTER_RANGE;
        double exitSq = SLOW_ZONE_EXIT_RANGE * SLOW_ZONE_EXIT_RANGE;

        Map<Integer, java.util.Set<Integer>> current = new HashMap<>();
        Map<Integer, java.util.Set<Integer>> inZoneMap = new HashMap<>();
        for (LivingEntity target : wearers) {
            if (!ShieldingFieldManager.isFieldActive(target)) continue;
            if (!ShieldingFieldManager.isFieldOperational(target)) continue;

            // inZone：真正在慢速区内（< 1 格且慢速）的攻击者，用于音效边沿触发
            java.util.Set<Integer> inZone = inZoneMap.computeIfAbsent(target.getId(), k -> new java.util.HashSet<>());

            // 视觉滞回：上一 tick 已显示红光的攻击者，只要未远离退出距离就保持显示。
            // 防止边界/走停抖动导致红光反复闪烁；真正远离（>1.5格）后客户端才缓慢淡出
            java.util.Set<Integer> retained = LAST_SLOW_ZONES.get(target.getId());
            if (retained != null && !retained.isEmpty()) {
                for (int attackerId : retained) {
                    net.minecraft.world.entity.Entity a = level.getEntity(attackerId);
                    if (!(a instanceof Player p) || p == target || p.isRemoved() || !p.isAlive()) continue;
                    double rdx = p.getX() - target.getX();
                    double rdz = p.getZ() - target.getZ();
                    if (rdx * rdx + rdz * rdz > exitSq) continue;
                    if (Math.abs(p.getY() - target.getY()) > SLOW_ZONE_VERTICAL_RANGE) continue;
                    current.computeIfAbsent(target.getId(), k -> new java.util.HashSet<>())
                        .add(attackerId);
                }
            }

            AABB box = target.getBoundingBox().inflate(
                SLOW_ZONE_ENTER_RANGE, SLOW_ZONE_VERTICAL_RANGE, SLOW_ZONE_ENTER_RANGE);
            for (Player attacker : level.getEntitiesOfClass(Player.class, box)) {
                if (attacker == target || !attacker.isAlive() || attacker.isSpectator()) continue;
                // 精确水平中心距判定：小于 1 格才算贴身进入
                double dx = attacker.getX() - target.getX();
                double dz = attacker.getZ() - target.getZ();
                if (dx * dx + dz * dz > enterSq) continue;
                if (Math.abs(attacker.getY() - target.getY()) > SLOW_ZONE_VERTICAL_RANGE) continue;
                // 慢速 = 蹲下或几乎静止（低延迟独立判定，不等 5 tick 滑动窗口排空）
                if (!isSlowForZone(attacker)) continue;
                inZone.add(attacker.getId());
                current.computeIfAbsent(target.getId(), k -> new java.util.HashSet<>())
                    .add(attacker.getId());
            }
        }

        // ===== 音效状态机：一次进场只响一次 =====
        // 第 1 步：本 tick 真正在慢速区内（<1 格且慢速）的攻击者，未锁定才响一次。
        // 注意：只在这里"加锁/播放"，绝不在"本 tick 没扫描到"时解锁——
        // 慢速判定的瞬时抖动（走停/数据竞态）不能导致重复播放。
        for (var entry : inZoneMap.entrySet()) {
            int targetId = entry.getKey();
            if (!(level.getEntity(targetId) instanceof LivingEntity target)) continue;
            java.util.Set<Integer> armed =
                SLOW_SOUND_ARMED.computeIfAbsent(targetId, k -> new java.util.HashSet<>());
            for (int attackerId : entry.getValue()) {
                if (armed.add(attackerId)) {
                    ArmorSoundManager.playShieldPenetrateSound(target);
                }
                SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(targetId, attackerId));
            }
        }

        // 第 2 步：解锁只看【真实距离】，与本 tick 慢速扫描结果完全无关。
        // 只要攻击者还在红色慢速范围（1.5 格滞回区）内，锁定永不解除，绝不放第二次；
        // 连续 2 tick 真正远离超过 1.5 格才解锁，之后重新进入才会再响。
        var armedIter = SLOW_SOUND_ARMED.entrySet().iterator();
        while (armedIter.hasNext()) {
            var armedEntry = armedIter.next();
            int targetId = armedEntry.getKey();
            java.util.Set<Integer> armed = armedEntry.getValue();

            boolean fieldAlive = level.getEntity(targetId) instanceof LivingEntity le
                && ShieldingFieldManager.isFieldActive(le)
                && ShieldingFieldManager.isFieldOperational(le);
            if (!fieldAlive) {
                // 场失效 / 佩戴者消失：解除其全部锁定
                for (int attackerId : armed) {
                    SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(targetId, attackerId));
                }
                armedIter.remove();
                continue;
            }
            LivingEntity target = (LivingEntity) level.getEntity(targetId);

            armed.removeIf(attackerId -> {
                if (!(level.getEntity(attackerId) instanceof Player p)
                    || p == target || p.isRemoved() || !p.isAlive()) {
                    SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(targetId, attackerId));
                    return true;
                }
                double dx = p.getX() - target.getX();
                double dz = p.getZ() - target.getZ();
                boolean far = dx * dx + dz * dz > exitSq
                    || Math.abs(p.getY() - target.getY()) > SLOW_ZONE_VERTICAL_RANGE;
                if (!far) {
                    // 仍在红色范围内：重置远离计数，保持锁定
                    SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(targetId, attackerId));
                    return false;
                }
                int ticks = SLOW_SOUND_EXIT_TICKS.merge(
                    slowSoundPairKey(targetId, attackerId), 1, Integer::sum);
                if (ticks >= SLOW_SOUND_DISARM_CONFIRM_TICKS) {
                    SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(targetId, attackerId));
                    return true; // 确认离开，解锁
                }
                return false;
            });
            if (armed.isEmpty()) armedIter.remove();
        }

        boolean periodic = gameTick % SLOW_ZONE_SYNC_INTERVAL_TICKS == 0L;
        for (var entry : current.entrySet()) {
            java.util.Set<Integer> last = LAST_SLOW_ZONES.get(entry.getKey());
            if (periodic || last == null || !last.equals(entry.getValue())) {
                broadcastSlowZone(level, entry.getKey(), entry.getValue());
            }
        }
        // 状态解除（目标离开/贴近者走开）：发一次空状态清除客户端表现
        LAST_SLOW_ZONES.keySet().removeIf(id -> {
            if (current.containsKey(id)) return false;
            broadcastSlowZone(level, id, java.util.Set.of());
            return true;
        });
        current.forEach((id, set) -> LAST_SLOW_ZONES.put(id, set));
    }

    private static long slowSoundPairKey(int targetId, int attackerId) {
        return ((long) targetId << 32) | (attackerId & 0xFFFFFFFFL);
    }

    /**
     * 低速攻击穿过屏蔽场时的 disu 音效：与贴近共用同一套锁定状态。
     * 攻击者进场时已响过（已锁定）则不重复；未锁定则响一次并锁定。
     */
    private static void tryPlaySlowSound(LivingEntity target, Player attacker) {
        java.util.Set<Integer> armed =
            SLOW_SOUND_ARMED.computeIfAbsent(target.getId(), k -> new java.util.HashSet<>());
        if (armed.add(attacker.getId())) {
            ArmorSoundManager.playShieldPenetrateSound(target);
        }
        SLOW_SOUND_EXIT_TICKS.remove(slowSoundPairKey(target.getId(), attacker.getId()));
    }

    private static void broadcastSlowZone(ServerLevel level, int targetId, java.util.Set<Integer> attackerIds) {
        net.minecraft.world.entity.Entity target = level.getEntity(targetId);
        if (target == null) return;
        int[] ids = attackerIds.stream().mapToInt(Integer::intValue).toArray();
        ModNetwork.sendShieldingFieldSlowZone(target, ids);
    }

    // ==================== 背包内发生器回复 ====================

    /**
     * 扫描玩家背包/副手/盔甲槽内所有发生器：推进负载回复 + 保证耐久无限；
     * 当前正佩戴在身上的那一件由 {@link ShieldingFieldManager#tick} 处理，跳过避免重复推进。
     */
    private static void tickInventoryGenerators(ServerPlayer player, long gameTick) {
        net.minecraft.world.item.ItemStack worn = ShieldingFieldManager.getActiveFieldStack(player);
        boolean changed = false;

        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> inv = player.getInventory().items;
        for (int i = 0; i < inv.size(); i++) {
            if (processInventoryStack(inv.get(i), worn, gameTick)) changed = true;
        }
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().offhand) {
            if (processInventoryStack(stack, worn, gameTick)) changed = true;
        }
        for (net.minecraft.world.item.ItemStack stack : player.getInventory().armor) {
            if (processInventoryStack(stack, worn, gameTick)) changed = true;
        }

        // NBT 变化后同步容器槽位到客户端（保证 tooltip 负载与耐久实时更新）
        if (changed) {
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
        }
    }

    /** 背包内单件发生器：耐久无限 + 未佩戴时推进回复。佩戴中的按引用跳过 */
    private static boolean processInventoryStack(
        net.minecraft.world.item.ItemStack stack,
        net.minecraft.world.item.ItemStack worn,
        long gameTick
    ) {
        if (!ShieldingFieldManager.isFieldGeneratorStack(stack)) return false;
        boolean changed = ShieldingFieldManager.ensureIndestructible(stack);
        if (stack != worn && ShieldingFieldManager.tickFieldItem(stack, gameTick)) {
            changed = true;
        }
        return changed;
    }

    // ==================== 近战拦截（沙丘设定：场拦移动攻击，静/蹲穿透） ====================

    /** 近战拦截判定（两层事件共用）：返回 true 表示应被屏蔽场拦下 */
    private static boolean shouldInterceptMelee(Player attacker, LivingEntity target) {
        // 目标无屏蔽场 → 不干预
        if (!ShieldingFieldManager.isFieldActive(target)) return false;
        // 屏蔽场熄灭/重建期主层过载 → 场失效，攻击穿透
        if (!ShieldingFieldManager.isFieldOperational(target)) return false;
        // 蹲下（潜行）攻击 → 穿透屏蔽场
        if (attacker.isCrouching()) return false;
        // 站立不动攻击 → 穿透屏蔽场
        return isMovingAttacker(attacker);
    }

    /** 近战动能注入（徒手 16 J 固定；近战武器 65 J 固定）+ 取消伤害 */
    private static void interceptMelee(Player attacker, LivingEntity target) {
        boolean bareHand = attacker.getMainHandItem().isEmpty();
        double energy = bareHand
            ? ShieldingFieldManager.MELEE_HAND_ENERGY_J
            : ShieldingFieldManager.MELEE_WEAPON_ENERGY_J;
        long tick = target.level().getGameTime();
        Vec3 contact = meleeContactPos(attacker, target);

        ShieldingFieldManager.HitResult result = null;
        try {
            result = ShieldingFieldManager.onHit(target, energy, 2.0D, tick, contact);
        } catch (Throwable fieldError) {
            FragmentationMod.LOGGER.error("[ShieldingField] 近战拦截异常", fieldError);
        }
        // 移动近战被挡下 → gaosu 音效（与枪械高速命中一致）；
        // 场失效/穿透时 onHit 内部已播 disu，这里不叠加
        if (result != null && result.absorbed()) {
            ArmorSoundManager.playShieldBlockSound(target);
        }
        FragmentationMod.LOGGER.info(
            "[ShieldingField] 拦截 {} 的移动近战攻击（{}J，{}），目标 {}",
            attacker.getName().getString(),
            String.format("%.0f", energy),
            bareHand ? "徒手" : "近战武器",
            target.getName().getString());
    }

    /**
     * 第一层：玩家挥击实体（最早拦截点）。
     * 行走/奔跑状态攻击 → 被屏蔽场拦下（取消挥击，动能注入屏蔽场）。
     */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player attacker = event.getEntity();
        if (attacker.level().isClientSide) return;
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (!shouldInterceptMelee(attacker, target)) return;

        interceptMelee(attacker, target);
        event.setCanceled(true);
    }

    /**
     * 第二层（兜底）：伤害实际生效前拦截。
     * 覆盖：模组自定义近战（不经过 Player.attack）、攻击包先于移动包处理导致的竞态漏判等。
     * 仅拦截【近战直接伤害】——directEntity == 攻击者本人；
     * 子弹/箭矢等投射物的 directEntity 是投射物实体，不在此拦截（由子弹命中逻辑处理）。
     */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof Player attacker)) return;
        if (attacker == victim) return;
        // 仅近战直接伤害（投射物的 directEntity 是投射物本身）
        if (event.getSource().getDirectEntity() != event.getSource().getEntity()) return;
        if (!shouldInterceptMelee(attacker, victim)) {
            // 蹲下/站立（低速）穿过活跃屏蔽场 → disu 音效 + 深红闪光
            // （只在伤害层处理，避免与挥击层重复）
            slowPassThroughFx(attacker, victim);
            return;
        }

        interceptMelee(attacker, victim);
        event.setCanceled(true);
    }

    /**
     * 拦截 CustomNPCs（CNPC）枪械/远程投射物对屏蔽场佩戴者的伤害。
     * <p>
     * CNPC 的 {@code noppes.npcs.entity.EntityProjectile}（继承 ThrowableProjectile）
     * 命中实体时调用 {@code entity.hurt(DamageSources.thrown(projectile, owner), damage)}：
     * 攻击者（owner）不是玩家、directEntity 是投射物本身，因此近战拦截两层
     * （AttackEntityEvent + 仅玩家近战直接伤害的 LivingAttackEvent）都不覆盖它。
     * <p>
     * 这里按投射物实际速度估算动能（与 TACZ 路径相同的 E=½mv²、0.008kg 兜底质量），
     * 交给 {@link ShieldingFieldManager#onHit} 统一判定：吸收则取消伤害；
     * 场失效/过载未吸收时放行，伤害正常结算（onHit 内部已处理红光与音效）。
     */
    @SubscribeEvent
    public static void onCnpcProjectileAttack(LivingAttackEvent event) {
        try {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) return;

            net.minecraft.world.entity.Entity direct = event.getSource().getDirectEntity();
            if (direct == null || !isCnpcProjectile(direct)) return;

            net.minecraft.world.entity.Entity owner = event.getSource().getEntity();
            if (owner == victim) return;

            // 与 TACZ 路径一致：只要求场处于激活，具体阶段（含 FAILED/RECOVERING）
            // 全部交给 onHit 处理，未吸收时本方法不取消、伤害正常进入。
            if (!ShieldingFieldManager.isFieldActive(victim)) return;

            // blocks/tick → m/s
            Vec3 velocity = direct.getDeltaMovement();
            double speedBpt = velocity != null ? velocity.length() : 0.0D;
            double speedMps = speedBpt * 20.0D;
            double energy = BallisticsMath.kineticEnergy(CNPC_BULLET_MASS_KG, speedMps);
            long tick = victim.level().getGameTime();
            Vec3 contact = direct.position();

            ShieldingFieldManager.HitResult result = ShieldingFieldManager.onHit(
                victim, energy, speedMps, tick, contact);

            if (result.absorbed()) {
                event.setCanceled(true);
                FragmentationMod.LOGGER.info(
                    "[ShieldingField] 吸收 CNPC 投射物动能 {}J（speed={}m/s），目标 {} 无伤",
                    String.format("%.1f", energy),
                    String.format("%.1f", speedMps),
                    victim.getName().getString());
            } else {
                FragmentationMod.LOGGER.info(
                    "[ShieldingField] CNPC 投射物未被吸收（{}J），伤害正常结算，目标 {}",
                    String.format("%.1f", energy),
                    victim.getName().getString());
            }
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ShieldingField] CNPC 投射物拦截异常，放行伤害", e);
        }
    }

    /** 软检测（无编译依赖）：direct 是否为 CustomNPCs 的 EntityProjectile（含同包子类） */
    private static boolean isCnpcProjectile(net.minecraft.world.entity.Entity direct) {
        return direct.getClass().getName()
            .startsWith("noppes.npcs.entity.EntityProjectile");
    }

    /** 低速攻击穿过【活跃且有效】的屏蔽场 → disu 音效（锁定状态机保证一次进场只响一次）+ 深红色命中闪光 */
    private static void slowPassThroughFx(Player attacker, LivingEntity target) {
        if (!ShieldingFieldManager.isFieldActive(target)) return;
        if (!ShieldingFieldManager.isFieldOperational(target)) return;
        // 与慢速贴近共用锁定：进场时已响过则不重复；远离 1.5 格后再次进入/攻击才重播
        tryPlaySlowSound(target, attacker);
        ShieldingFieldManager.sendSlowPassThroughFx(target, meleeContactPos(attacker, target));
    }

    /**
     * 攻击者是否处于移动状态（行走/奔跑）。
     * 使用最近几个 tick 的位置滑动窗口：只要窗口内出现过明显位移即视为移动，
     * 避免攻击包先于移动包处理时把行走误判为站立（单 tick 采样竞态）。
     * 冲刺状态直接视为移动。
     */
    private static boolean isMovingAttacker(LivingEntity attacker) {
        if (attacker.isSprinting()) return true;
        ArrayDeque<Vec3> dq = POSITION_HISTORY.get(attacker.getUUID());
        if (dq == null || dq.isEmpty()) return false;

        Vec3 cur = attacker.position();
        double maxDelta = 0.0D;
        Vec3 prev = null;
        for (Vec3 p : dq) {
            if (prev != null) {
                maxDelta = Math.max(maxDelta, horizontalDist(prev, p));
            }
            prev = p;
        }
        // 窗口最新位置与当前位置（本 tick 攻击瞬间）
        maxDelta = Math.max(maxDelta, horizontalDist(dq.peekLast(), cur));
        // 窗口首尾跨度（覆盖持续慢速移动）
        maxDelta = Math.max(maxDelta, horizontalDist(dq.peekFirst(), cur));
        return maxDelta > MOVE_THRESHOLD_BPT;
    }

    /**
     * 慢速贴近判定（低延迟版）：蹲下，或最近一 tick 位移极小（≈站立）。
     * 独立于近战拦截的 5 tick 滑动窗口——近战拦截需要长窗口抗"攻击包先于移动包"竞态，
     * 而贴近显示只求快速响应：玩家停下后当 tick 即可判定，瞬时误差仅 1 tick 红光闪烁，无影响。
     */
    private static boolean isSlowForZone(Player attacker) {
        if (attacker.isCrouching()) return true;
        ArrayDeque<Vec3> dq = POSITION_HISTORY.get(attacker.getUUID());
        if (dq == null || dq.size() < 2) return true;
        java.util.Iterator<Vec3> it = dq.descendingIterator();
        Vec3 last = it.next();  // 本 tick 记录的位置
        Vec3 prev = it.next();  // 上一 tick 的位置
        return horizontalDist(prev, last) <= SLOW_ZONE_MOVE_THRESHOLD_BPT;
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 近战接触点：攻击者视线与目标碰撞箱的射线进入点。
     * 保留真实命中高度——打头高亮在头部、打腿高亮在腿部，不同落点显示不同。
     */
    private static Vec3 meleeContactPos(LivingEntity attacker, LivingEntity target) {
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

    /** 将展示配置同步给玩家自身（供客户端识别激活物品与显示名称） */
    private static void syncConfig(ServerPlayer player) {
        try {
            ShieldingFieldConfig config = ShieldingFieldConfig.getInstance();
            double negCap = config.negativeLayerCapacityJoules;
            double mainCap = config.mainLayerCapacityJoules;
            ModNetwork.sendShieldingFieldState(
                player, false, ShieldingFieldState.Phase.ACTIVE.ordinal(),
                0.0D, 0.0D, negCap, mainCap,
                config.displayName, config.activatorItems);
        } catch (Throwable e) {
            FragmentationMod.LOGGER.error("[ShieldingField] 配置同步失败", e);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            syncConfig(serverPlayer);
        }
    }
}
