package com.fragmentation.explosive;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.network.ExplosionVizPacket;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.util.PlayerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 手雷 / 榴弹爆炸的现实化底层。
 *
 * 冲击波：以爆心对每个目标直接采样，超压按距离平方衰减；途中每
 * 穿过一个方块，按其抗穿阈值（爆炸抗性）乘法衰减——高抗性掩体
 * 几乎完全阻断，土层/木板仅部分削弱。
 *
 * 弹片：按随机仰角/方位角成束射出，命中的首个生物体停止弹片；
 * 穿方块时累计抗穿消耗，超过该弹药的弹片穿透等级即被阻止。
 *
 * 所有尺度均来自 explosives.json：当量（伤害线性、半径立方根）、
 * 碎片数量、弹片穿透等级。
 */
public final class GrenadeExplosionHandler {
    private GrenadeExplosionHandler() {
    }

    // ================= 入口 =================

    /** 手雷爆炸：附属 mod 的 FragGrenadeEntity 或本模组通用投掷实体。 */
    public static void onGrenadeExplode(Entity source, String configId) {
        if (!(source.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 pos = source.position().add(0.0, 0.1, 0.0);
        Entity owner = resolveOwner(source);

        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        ExplosiveConfig.ExplosiveEntry entry = config.getEntry(configId);
        explode(level, pos, owner, paramsFor(entry));
    }

    /** TACZ 榴弹 / 高爆子弹的爆炸（ExplodeUtil.createExplosion 被接管）。 */
    public static void onTaczExplosion(
        ServerLevel level,
        Vec3 pos,
        Entity owner,
        float damage,
        float radius,
        String ammoId
    ) {
        ExplosiveConfig config = FragmentationMod.getExplosiveConfig();
        ExplosiveConfig.ExplosiveEntry entry = ammoId != null
            ? config.getEntry(ammoId)
            : null;

        if (entry != null
            && entry.type == ExplosiveConfig.ExplosiveType.LAUNCHER) {
            // 已注册榴弹：完全按配置参数
            explode(level, pos, owner, paramsFor(entry));
        } else {
            // 未注册的爆炸弹：按 TACZ 自带伤害/半径缩放
            double fragRadius = Math.max(1.5, radius);
            int count = Mth.clamp((int) (radius * 32.0F), 24, 128);
            explode(
                level,
                pos,
                owner,
                new Params(
                    fragRadius * 0.75,
                    Math.max(1.0F, damage) * 0.75,
                    fragRadius,
                    Math.max(1.0F, damage),
                    count,
                    ExplosiveConfig.BASE_FRAG_PEN
                )
            );
        }
    }

    /**
     * 外部起爆入口（手中拉环过久起爆等）：由配置条目直接在指定
     * 位置引爆，无需先生成投掷物实体。
     */
    public static void detonateFromConfig(
        ServerLevel level,
        Vec3 pos,
        Entity owner,
        ExplosiveConfig.ExplosiveEntry entry
    ) {
        explode(level, pos, owner, paramsFor(entry));
    }

    /** 由配置条目（当量/碎片/穿透）换算实际作战参数。 */
    private static Params paramsFor(ExplosiveConfig.ExplosiveEntry entry) {
        double yield = entry != null ? ExplosiveConfig.clampYield(entry.yield) : 1.0;
        double radiusScale = Math.cbrt(yield);
        int fragments = entry != null
            ? entry.fragments
            : ExplosiveConfig.BASE_FRAG_COUNT;
        double pen = entry != null
            ? entry.fragmentPen
            : ExplosiveConfig.BASE_FRAG_PEN;
        return new Params(
            ExplosiveConfig.BASE_SHOCK_RADIUS * radiusScale,
            ExplosiveConfig.BASE_SHOCK_DAMAGE * yield,
            ExplosiveConfig.BASE_FRAG_RADIUS * radiusScale,
            ExplosiveConfig.BASE_FRAG_DAMAGE * yield,
            fragments,
            pen
        );
    }

    // ================= 主流程 =================

    private static void explode(ServerLevel level, Vec3 pos, Entity owner, Params p) {
        level.playSound(
            null,
            pos.x, pos.y, pos.z,
            resolveExplosionSound(),
            SoundSource.PLAYERS,
            3.2F, 0.95F
        );
        level.sendParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            pos.x, pos.y, pos.z,
            1, 0.0, 0.0, 0.0, 0.0
        );
        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            pos.x, pos.y, pos.z,
            14, 0.8, 0.8, 0.8, 0.05
        );

        AABB searchBox = new AABB(pos, pos).inflate(p.fragRadius);
        List<LivingEntity> targets = new ArrayList<>(
            level.getEntitiesOfClass(LivingEntity.class, searchBox)
        );
        targets.removeIf(t -> t == owner || !t.isAlive());

        // 密闭空间泄压检测：爆心处于室内时，冲击波无法凭空消失，
        // 能量只能从门/窗/洞口等开口涌出（聚能喷射）。找出这些方向，
        // 伤害与可视化都给予增强。
        double centerEnclosure = enclosureLevel(
            level, pos.add(0.0, 1.0, 0.0));
        List<Vec3> ventDirs = centerEnclosure > 0.4
            ? findVentDirs(level, pos, p.shockRadius * 4.0)
            : List.of();

        // 先施加冲击波
        for (LivingEntity target : targets) {
            applyShockwave(level, pos, owner, target, p, ventDirs);
        }

        // 再施加弹片（冲击波中死亡的目标不再参与）
        targets.removeIf(target -> !target.isAlive());

        String dimension = level.dimension().location().toString();
        List<ServerPlayer> vizViewers = collectVizViewers(level, pos);

        // 收集本次爆炸的全部调试数据，最后合并成单个数据包发给每位观察者。
        // 弹片线、冲击波剖面、弹片平面投影同时生成，J 切换无需重新爆炸。
        List<ExplosionVizPacket.Segment> fragLines = new ArrayList<>();
        List<ExplosionVizPacket.WaveDir> volumeDirs = new ArrayList<>();
        List<ExplosionVizPacket.WaveDir> planDirs = new ArrayList<>();
        List<ExplosionVizPacket.PlanFragment> penPlan = new ArrayList<>();

        for (int i = 0; i < p.fragmentCount; i++) {
            Vec3 dir = randomFragmentDirection(level);
            FragmentCastResult result =
                castFragment(level, pos, dir, owner, targets, p);
            if (!vizViewers.isEmpty() && result.distance > 1.0E-4) {
                Vec3 end3d = pos.add(dir.scale(result.distance));
                // 弹片统一蓝色（两端同色）
                fragLines.add(new ExplosionVizPacket.Segment(
                    pos, end3d, FRAGMENT_RGB, FRAGMENT_RGB
                ));
                // 穿透平面图：路径投影到爆心水平面（俯视）
                penPlan.add(new ExplosionVizPacket.PlanFragment(
                    new Vec3(pos.x, pos.y, pos.z),
                    new Vec3(end3d.x, pos.y, end3d.z),
                    result.outcome
                ));
            }
        }

        // 冲击波可视化：只发送每个采样方向的传播剖面，波面（一圈圈
        // 向外扩张的环，撞墙后反弹回震）由客户端按剖面动态绘制。
        if (!vizViewers.isEmpty()) {
            double blastMaxDist = p.shockRadius * 4.0;
            volumeDirs.addAll(buildWaveVolume(
                level, pos, blastMaxDist, p.shockRadius));
            planDirs.addAll(buildWavePlan(
                level, pos, blastMaxDist, p.shockRadius));
            for (ServerPlayer viewer : vizViewers) {
                ModNetwork.sendToPlayer(
                    viewer,
                    new ExplosionVizPacket(
                        dimension, pos, p.shockRadius,
                        fragLines, volumeDirs, planDirs, penPlan)
                );
            }
        }
    }

    // ================= 威力配色 =================

    /** 弹片蓝色（亮天蓝）。 */
    private static final int FRAGMENT_RGB = 0x2E8BFF;

    /** 供客户端取弹片颜色。 */
    public static int fragmentRgb() {
        return FRAGMENT_RGB;
    }
    /** 穿透平面图：被掩体阻止的终点（红）。 */
    public static final int BLOCKED_RGB = 0xFF4040;
    /** 穿透平面图：命中生物体的终点（绿）。 */
    public static final int ENTITY_RGB = 0x39FF60;

    /** 身后墙反射的感知距离（格）：目标距墙超过此值则回震可忽略。 */
    private static final double BACK_WALL_SENSE = 3.0;
    /** 围合度探测距离（格）。 */
    private static final double ENCLOSURE_PROBE = 4.0;
    /** 密闭混响增益系数：围合度 100% 时入射压力的额外比例。 */
    private static final double ENCLOSURE_GAIN = 0.5;
    /** 总超压（入射+反射）相对入射基准的封顶。 */
    private static final double PRESSURE_CAP = 1.75;

    /**
     * 冲击波威力 → 颜色映射已移至客户端（ClientTrajectoryRenderer）。
     * 服务器只发送传播剖面（威力衰减采样），颜色由客户端按同一
     * 模型计算，保证红(致命区)→黄(重伤区)→绿(边缘区)渐变一致。
     */

    /**
     * 3D 球面波前采样：仰角 7 行（-90°~+90°，含两极）× 方位 48 列，
     * 每个方向记录传播剖面（截止距离、首墙反射信息、威力衰减采样）。
     * 波面本身由客户端动态生成：一圈圈等间距的环从爆心以固定波速
     * 向外扩张，撞墙后停住并反弹回震。
     */
    private static List<ExplosionVizPacket.WaveDir> buildWaveVolume(
        ServerLevel level,
        Vec3 pos,
        double maxDist,
        double shockRadius
    ) {
        List<ExplosionVizPacket.WaveDir> dirs = new ArrayList<>(
            ExplosionVizPacket.VOLUME_ROWS * ExplosionVizPacket.VOLUME_COLS);
        for (int row = 0; row < ExplosionVizPacket.VOLUME_ROWS; row++) {
            double theta = Math.toRadians(
                -90.0 + 180.0 * row / (ExplosionVizPacket.VOLUME_ROWS - 1));
            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);
            for (int col = 0; col < ExplosionVizPacket.VOLUME_COLS; col++) {
                double phi = Math.toRadians(
                    360.0 * col / ExplosionVizPacket.VOLUME_COLS);
                Vec3 dir = new Vec3(
                    cosTheta * Math.cos(phi),
                    sinTheta,
                    cosTheta * Math.sin(phi)
                );
                dirs.add(waveDirOf(level, pos, dir, maxDist, shockRadius));
            }
        }
        return dirs;
    }

    /**
     * 泄压口方向搜索：密闭空间内冲击波无法凭空消失，能量只能从
     * 门/窗/洞口等开口涌出（聚能喷射）。沿 16 个水平方向采样，
     * 传播未被截止（一路通向外部）的方向即为泄压方向。
     */
    private static List<Vec3> findVentDirs(
        ServerLevel level,
        Vec3 pos,
        double maxDist
    ) {
        List<Vec3> vents = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double phi = Math.toRadians(360.0 * i / 16);
            Vec3 dir = new Vec3(Math.cos(phi), 0.0, Math.sin(phi));
            BlastProfile profile =
                blastProfile(level, pos, dir, maxDist, maxDist / 4.0);
            if (profile.reach >= maxDist * 0.95) {
                vents.add(dir);
            }
        }
        return vents;
    }

    /** 水平切面波前采样：144 个水平方向（每 2.5°）。 */
    private static List<ExplosionVizPacket.WaveDir> buildWavePlan(
        ServerLevel level,
        Vec3 pos,
        double maxDist,
        double shockRadius
    ) {
        List<ExplosionVizPacket.WaveDir> dirs =
            new ArrayList<>(ExplosionVizPacket.PLAN_DIRS);
        for (int i = 0; i < ExplosionVizPacket.PLAN_DIRS; i++) {
            double phi = Math.toRadians(
                360.0 * i / ExplosionVizPacket.PLAN_DIRS);
            dirs.add(waveDirOf(
                level, pos,
                new Vec3(Math.cos(phi), 0.0, Math.sin(phi)),
                maxDist, shockRadius
            ));
        }
        return dirs;
    }

    /** 单方向传播剖面：一次步进得到截止距离、首墙与威力衰减采样。 */
    private static ExplosionVizPacket.WaveDir waveDirOf(
        ServerLevel level,
        Vec3 pos,
        Vec3 dir,
        double maxDist,
        double shockRadius
    ) {
        BlastProfile profile =
            blastProfile(level, pos, dir, maxDist, shockRadius);
        List<ExplosionVizPacket.WaveSample> samples =
            new ArrayList<>(profile.samples.size());
        for (double[] sample : profile.samples) {
            samples.add(new ExplosionVizPacket.WaveSample(
                (float) sample[0], (float) sample[1]));
        }
        return new ExplosionVizPacket.WaveDir(
            dir,
            (float) profile.reach,
            (float) profile.wallDist,
            (float) profile.wallReflectivity,
            (float) profile.factorBeforeWall,
            samples
        );
    }

    // ================= 冲击波 =================

    private static void applyShockwave(
        ServerLevel level,
        Vec3 pos,
        Entity owner,
        LivingEntity target,
        Params p,
        List<Vec3> ventDirs
    ) {
        Vec3 body = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        double distance = pos.distanceTo(body);
        if (distance > p.shockRadius) {
            return;
        }

        ExplosionRayTracer.LineResult line =
            ExplosionRayTracer.traceLine(level, pos, body);

        double radiusRatio = distance / p.shockRadius;
        double pressureRatio = 1.0 - radiusRatio * radiusRatio;
        double transmission = line.blastFactor;
        double incident = pressureRatio * transmission;

        // 聚能喷射：目标位于泄压口方向（±35° 锥角）时，室内能量
        // 集中从开口涌出，超压显著增强。
        if (!ventDirs.isEmpty()) {
            Vec3 horizontal = body.subtract(pos);
            horizontal = new Vec3(horizontal.x, 0.0, horizontal.z);
            if (horizontal.lengthSqr() > 1.0E-8) {
                Vec3 toTarget = horizontal.normalize();
                for (Vec3 vent : ventDirs) {
                    if (toTarget.dot(vent) > 0.82) {
                        incident *= 1.35;
                        break;
                    }
                }
            }
        }

        // ================= 反射回震 =================
        double reflected = 0.0;

        // 1) 身后墙面反射：波越过目标后撞到墙，未被吸收的部分原路
        //    返回形成回震——目标距墙越近、墙面越硬，回震越强。
        Vec3 away = body.subtract(pos);
        if (away.lengthSqr() > 1.0E-8) {
            Vec3 awayDir = away.normalize();
            WallHit backWall = firstWall(
                level, body, awayDir, BACK_WALL_SENSE);
            if (backWall != null) {
                double closeness = 1.0 - backWall.distance / BACK_WALL_SENSE;
                reflected += incident
                    * backWall.reflectivity * closeness * closeness;
            }
        }

        // 2) 密闭空间混响：四周/头顶墙面使冲击波在室内来回反射，
        //    能量无法向空旷处扩散，整体超压显著升高。
        double enclosure = enclosureLevel(level, body);
        reflected += incident * enclosure * ENCLOSURE_GAIN;

        double totalPressure = Math.min(PRESSURE_CAP, incident + reflected);
        float damage = (float) (p.shockDamage * totalPressure);

        if (damage >= 0.5F) {
            target.hurt(target.damageSources().explosion(owner, owner), damage);
        }

        if (target.isAlive()) {
            // 推力只按入射波（反射波来自多方向，主要表现为挤压）
            Vec3 push = target.position().subtract(pos);
            push = new Vec3(push.x, 0.0, push.z);
            if (push.lengthSqr() > 1.0E-6) {
                push = push.normalize().scale(0.9 * incident);
            }
            target.push(push.x, 0.25 * incident, push.z);
        }
    }

    /**
     * 冲击波传播剖面：沿方向以 0.2 步进，每穿过一个方块按其抗穿
     * 阈值乘法衰减，同时每 0.5 距离记录一个 (距离, 剩余系数) 采样点；
     * 系数低于 3% 时传播截止，reach 为掩体前方距离（不嵌入墙体）。
     * 一次步进即可支持任意层数取色，避免重复计算。
     *
     * 同时记录路径上的首个阻挡面（wallDist / wallReflectivity /
     * factorBeforeWall）：波并不会在墙处凭空消失，未被吸收的部分
     * 反射形成回震，供可视化与伤害模型使用。
     */
    /**
     * 球面几何扩散系数（能量守恒）：球面波能量随表面积 4πr² 摊薄，
     * 能流密度近场饱和、远场 ∝ R²/r²，形式 1/(1+(r/R)²)。
     * 客户端配色/透明度使用同一函数。
     */
    public static double spreadFactor(double distance, double shockRadius) {
        double x = distance / shockRadius;
        return 1.0 / (1.0 + x * x);
    }

    /** 波传播截止阈值：几何扩散×穿障吸收后剩余振幅低于此值即不可察觉。 */
    private static final double AMP_EPSILON = 0.03;

    private static BlastProfile blastProfile(
        ServerLevel level,
        Vec3 pos,
        Vec3 dir,
        double maxDist,
        double shockRadius
    ) {
        BlastProfile profile = new BlastProfile();
        profile.samples.add(new double[]{0.0, 1.0});

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCell = null;
        double traveled = 0.0;
        double factor = 1.0;
        int sampleBucket = 0;
        final double step = 0.2;
        // 采样粒度随传播距离自适应，控制包大小（约 16 个采样点）
        final double bucketSize = Math.max(0.5, maxDist / 16.0);

        while (traveled <= maxDist) {
            cursor.set(
                Mth.floor(pos.x + dir.x * traveled),
                Mth.floor(pos.y + dir.y * traveled),
                Mth.floor(pos.z + dir.z * traveled)
            );
            if (!cursor.equals(lastCell)) {
                var state = level.getBlockState(cursor);
                ExplosionRayTracer.Obstacle obstacle =
                    ExplosionRayTracer.inspect(level, cursor, state);
                if (obstacle.present) {
                    // 首个阻挡面：记录反射信息，波继续传播（薄墙可被
                    // 部分穿透），反射波从该墙面往回形成回震。
                    if (profile.wallReflectivity == 0.0) {
                        profile.wallDist = Math.max(0.0, traveled - step);
                        profile.wallReflectivity = reflectivity(obstacle);
                        profile.factorBeforeWall = factor;
                    }
                    if (obstacle.glass) {
                        factor *= 0.7;
                    } else {
                        factor *= 10.0 / (10.0 + obstacle.threshold);
                    }
                }
                lastCell = cursor.immutable();
            }

            // 能量守恒截止：只有能量被墙体吸收（factor）或几何扩散
            // 摊薄到阈值以下时才停止传播，波不会在能量未耗尽时消失。
            if (factor * spreadFactor(traveled, shockRadius) < AMP_EPSILON) {
                profile.reach = Math.max(0.0, traveled - step);
                profile.samples.add(new double[]{traveled, factor});
                return profile;
            }

            traveled += step;
            int bucket = (int) Math.floor(traveled / bucketSize);
            if (bucket > sampleBucket) {
                sampleBucket = bucket;
                profile.samples.add(
                    new double[]{Math.min(traveled, maxDist), factor});
            }
        }
        profile.reach = maxDist;
        return profile;
    }

    /**
     * 从 from 沿 dir 找首个阻挡面，maxDist 内无墙返回 null。
     * 用于身后回震与围合度探测。
     */
    private static WallHit firstWall(
        ServerLevel level,
        Vec3 from,
        Vec3 dir,
        double maxDist
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCell = null;
        for (double t = ExplosionRayTracer.STEP; t <= maxDist;
            t += ExplosionRayTracer.STEP) {
            cursor.set(
                Mth.floor(from.x + dir.x * t),
                Mth.floor(from.y + dir.y * t),
                Mth.floor(from.z + dir.z * t)
            );
            if (!cursor.equals(lastCell)) {
                var state = level.getBlockState(cursor);
                ExplosionRayTracer.Obstacle obstacle =
                    ExplosionRayTracer.inspect(level, cursor, state);
                if (obstacle.present) {
                    return new WallHit(t, reflectivity(obstacle));
                }
                lastCell = cursor.immutable();
            }
        }
        return null;
    }

    /**
     * 目标位置周围的围合度（0~1）：向 18 个方向短距探测墙面，
     * 按命中距离加权（贴得越近贡献越大）。户外仅脚下地面命中，
     * 数值接近 0；密闭小房间四面墙+天花板都命中，数值接近 1。
     */
    private static double enclosureLevel(ServerLevel level, Vec3 body) {
        double sum = 0.0;
        for (Vec3 dir : ENCLOSURE_DIRS) {
            WallHit hit = firstWall(level, body, dir, ENCLOSURE_PROBE);
            if (hit != null) {
                double closeness = 1.0 - hit.distance / ENCLOSURE_PROBE;
                sum += closeness * closeness;
            }
        }
        return Mth.clamp(sum / ENCLOSURE_DIRS.size() * 1.6, 0.0, 1.0);
    }

    /**
     * 墙面反射率：波遇到墙面时未被吸收而反射的能量比例。
     * 玻璃在冲击下会破碎泄压（0.25）；普通固体随抗穿阈值升高：
     * 土/木板约 0.2~0.3，石材约 0.4，钢筋混凝土/金属 0.5+，封顶 0.75。
     */
    private static double reflectivity(ExplosionRayTracer.Obstacle obstacle) {
        if (obstacle.glass) {
            return 0.25;
        }
        return Mth.clamp(
            obstacle.threshold / (obstacle.threshold + 8.0), 0.20, 0.75);
    }

    /** 围合度探测方向：6 主轴 + 4 水平对角 + 8 斜向（含上下）。 */
    private static final List<Vec3> ENCLOSURE_DIRS = List.of(
        new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
        new Vec3(0, 1, 0), new Vec3(0, -1, 0),
        new Vec3(0, 0, 1), new Vec3(0, 0, -1),
        new Vec3(0.707, 0, 0.707), new Vec3(-0.707, 0, 0.707),
        new Vec3(0.707, 0, -0.707), new Vec3(-0.707, 0, -0.707),
        new Vec3(0.707, 0.707, 0), new Vec3(-0.707, 0.707, 0),
        new Vec3(0.707, -0.707, 0), new Vec3(-0.707, -0.707, 0),
        new Vec3(0, 0.707, 0.707), new Vec3(0, 0.707, -0.707),
        new Vec3(0, -0.707, 0.707), new Vec3(0, -0.707, -0.707)
    );

    /** 墙面命中结果：距离与墙面反射率。 */
    private record WallHit(double distance, double reflectivity) {
    }

    /**
     * 冲击波传播剖面：
     * reach=传播截止距离，samples=(距离,剩余系数)；
     * wallDist=首个阻挡面距离，wallReflectivity=该面反射率（0=无墙），
     * factorBeforeWall=到墙前的穿障剩余系数。
     */
    private static final class BlastProfile {
        private final List<double[]> samples = new ArrayList<>();
        private double reach;
        private double wallDist;
        private double wallReflectivity;
        private double factorBeforeWall = 1.0;
    }

    // ================= 弹片 =================

    private static FragmentCastResult castFragment(
        ServerLevel level,
        Vec3 pos,
        Vec3 dir,
        Entity owner,
        List<LivingEntity> targets,
        Params p
    ) {
        double nearestEntity = p.fragRadius;
        LivingEntity hitTarget = null;
        for (LivingEntity target : targets) {
            double hit = rayAabb(pos, dir, target.getBoundingBox().inflate(0.05));
            if (hit >= 0.0 && hit < nearestEntity) {
                nearestEntity = hit;
                hitTarget = target;
            }
        }

        double blockStop = marchFragment(
            level, pos, dir, p.fragRadius, p.fragmentPenCap);
        if (hitTarget != null && nearestEntity <= blockStop) {
            double cumulative = cumulativeResistance(
                level, pos, dir, nearestEntity, p.fragmentPenCap);
            double energy = Math.max(0.0, 1.0 - cumulative / p.fragmentPenCap);
            float damage = (float) (p.fragDamage * energy);
            if (damage >= 0.5F) {
                hitTarget.hurt(
                    hitTarget.damageSources().explosion(owner, owner), damage);
            }
            return new FragmentCastResult(
                nearestEntity, ExplosionVizPacket.OUTCOME_ENTITY);
        }
        if (blockStop < p.fragRadius - 1.0E-6) {
            return new FragmentCastResult(
                blockStop, ExplosionVizPacket.OUTCOME_BLOCKED);
        }
        return new FragmentCastResult(
            p.fragRadius, ExplosionVizPacket.OUTCOME_RANGE);
    }

    /** 弹片投射结果：停止距离与结局（杀伤边缘/掩体阻止/命中生物体）。 */
    private static final class FragmentCastResult {
        private final double distance;
        private final byte outcome;

        private FragmentCastResult(double distance, byte outcome) {
            this.distance = distance;
            this.outcome = outcome;
        }
    }

    /**
     * 弹片穿方块：按真实路径积分密度×厚度累计消耗。
     * 不再是"进入方块就扣整块阈值"，而是每个 0.2 步进只累计
     * 密度 × 步长。弹片只在方块内部行进足够厚度才耗光穿透力，
     * 擦边蹭过墙角、斜穿薄墙都能真实反映。
     */
    private static double marchFragment(
        ServerLevel level,
        Vec3 pos,
        Vec3 dir,
        double maxDist,
        double penCap
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCell = null;
        double traveled = 0.0;
        double cumulative = 0.0;

        while (traveled <= maxDist) {
            cursor.set(
                Mth.floor(pos.x + dir.x * traveled),
                Mth.floor(pos.y + dir.y * traveled),
                Mth.floor(pos.z + dir.z * traveled)
            );
            if (!cursor.equals(lastCell)) {
                var state = level.getBlockState(cursor);
                ExplosionRayTracer.Obstacle obstacle =
                    ExplosionRayTracer.inspect(level, cursor, state);
                if (obstacle.present) {
                    if (obstacle.glass) {
                        // 玻璃极薄：轻微固定消耗
                        cumulative += 0.3;
                    } else {
                        // 密度系数：阈值归一化到每格厚度的穿透成本
                        cumulative += obstacle.threshold * 0.2;
                    }
                    if (cumulative >= penCap) {
                        return Math.max(0.0, traveled - 0.2);
                    }
                }
                lastCell = cursor.immutable();
            }
            traveled += 0.2;
        }
        return maxDist;
    }

    /** 射线传播指定距离时累计的穿透消耗（路径积分密度×厚度）。 */
    private static double cumulativeResistance(
        ServerLevel level,
        Vec3 pos,
        Vec3 dir,
        double distance,
        double penCap
    ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCell = null;
        double traveled = 0.0;
        double cumulative = 0.0;

        while (traveled <= distance && cumulative < penCap) {
            cursor.set(
                Mth.floor(pos.x + dir.x * traveled),
                Mth.floor(pos.y + dir.y * traveled),
                Mth.floor(pos.z + dir.z * traveled)
            );
            if (!cursor.equals(lastCell)) {
                var state = level.getBlockState(cursor);
                ExplosionRayTracer.Obstacle obstacle =
                    ExplosionRayTracer.inspect(level, cursor, state);
                if (obstacle.present) {
                    if (obstacle.glass) {
                        cumulative += 0.3;
                    } else {
                        cumulative += obstacle.threshold * 0.2;
                    }
                }
                lastCell = cursor.immutable();
            }
            traveled += 0.2;
        }
        return cumulative;
    }

    private static Vec3 randomFragmentDirection(ServerLevel level) {
        double phi = level.random.nextDouble() * Math.PI * 2.0;
        // 仰角 -0.52 ~ +1.03 rad（略偏上，与真实破片飞散一致）
        double theta = level.random.nextDouble() * 1.55 - 0.52;
        return new Vec3(
            Math.cos(theta) * Math.cos(phi),
            Math.sin(theta),
            Math.cos(theta) * Math.sin(phi)
        ).normalize();
    }

    /** 射线与 AABB 的 slab 求交，返回最近正向距离；无交点返回 -1。 */
    private static double rayAabb(Vec3 origin, Vec3 dir, AABB box) {
        double tx1 = (box.minX - origin.x) / dir.x;
        double tx2 = (box.maxX - origin.x) / dir.x;
        double tMin = Math.min(tx1, tx2);
        double tMax = Math.max(tx1, tx2);

        double ty1 = (box.minY - origin.y) / dir.y;
        double ty2 = (box.maxY - origin.y) / dir.y;
        tMin = Math.max(tMin, Math.min(ty1, ty2));
        tMax = Math.min(tMax, Math.max(ty1, ty2));

        double tz1 = (box.minZ - origin.z) / dir.z;
        double tz2 = (box.maxZ - origin.z) / dir.z;
        tMin = Math.max(tMin, Math.min(tz1, tz2));
        tMax = Math.min(tMax, Math.max(tz1, tz2));

        if (tMax < Math.max(tMin, 0.0)) {
            return -1.0;
        }
        return Math.max(tMin, 0.0);
    }

    // ================= 杂项 =================

    private static List<ServerPlayer> collectVizViewers(ServerLevel level, Vec3 pos) {
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!PlayerStateManager.isTrajectoryMode(player)) {
                continue;
            }
            if (player.level() != level) {
                continue;
            }
            if (player.position().distanceToSqr(pos) > 96.0 * 96.0) {
                continue;
            }
            viewers.add(player);
        }
        return viewers;
    }

    private static Entity resolveOwner(Entity source) {
        if (source instanceof Projectile projectile && projectile.getOwner() != null) {
            return projectile.getOwner();
        }
        try {
            Field field = source.getClass().getDeclaredField("ownerUuid");
            field.setAccessible(true);
            if (field.get(source) instanceof UUID uuid
                && source.level() instanceof ServerLevel level) {
                Entity owner = level.getEntity(uuid);
                if (owner != null) {
                    return owner;
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
        }
        return null;
    }

    private static SoundEvent resolveExplosionSound() {
        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue(
            new ResourceLocation("tacz_tactical_breaching", "frag_explosion_close")
        );
        return event != null ? event : SoundEvents.GENERIC_EXPLODE;
    }

    private record Params(
        double shockRadius,
        double shockDamage,
        double fragRadius,
        double fragDamage,
        int fragmentCount,
        double fragmentPenCap
    ) {
    }
}
