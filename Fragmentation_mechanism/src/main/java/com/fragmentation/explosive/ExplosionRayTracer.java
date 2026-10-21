package com.fragmentation.explosive;

import com.fragmentation.util.WorldCoverManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 爆炸物理共用的体素步进射线。同一条路径上分别累积：
 * 光线（遇首个不透光方块即截止，玻璃按层削弱）、声音（绕过障碍
 * 能力较强，衰减较轻）、冲击波（按方块抗穿阈值乘法衰减）。
 *
 * 方块是否阻挡及其抗穿阈值的唯一判定入口是 {@link #inspect}：
 * covers.json 中注册的掩体以其 penetrationThreshold 为权威值，
 * 未注册方块再退回原版爆炸抗性。
 */
public final class ExplosionRayTracer {
    public static final double STEP = 0.2;

    private static final double SOUND_K = 25.0;
    private static final double BLAST_K = 10.0;
    private static final float GLASS_LIGHT = 0.55F;
    private static final float GLASS_SOUND = 0.85F;
    private static final float GLASS_BLAST = 0.70F;

    /** 单个采样方块的阻挡信息。 */
    public static final class Obstacle {
        public boolean present;
        public boolean glass;
        public double threshold;
    }

    public static final class LineResult {
        public boolean lightBlocked;
        public double lightDistance;
        public float glassFactor = 1.0F;
        public float soundFactor = 1.0F;
        public float blastFactor = 1.0F;
    }

    private ExplosionRayTracer() {
    }

    /**
     * 判定指定位置方块对爆炸传播的阻挡信息。
     * 1) covers.json 注册掩体：必然阻挡，阈值取配置（哪怕方块渲染上
     *    不遮光，也以掩体配置为权威）；
     * 2) 玻璃/玻璃板：按玻璃系数削弱；
     * 3) canOcclude / blocksMotion 的方块：阈值取原版爆炸抗性；
     * 4) 其余（花草、空气等）：不阻挡。
     */
    public static Obstacle inspect(
        BlockGetter level, BlockPos pos, BlockState state
    ) {
        Obstacle obstacle = new Obstacle();
        WorldCoverManager.CoverEntry cover = WorldCoverManager.getCover(state);
        if (cover != null) {
            obstacle.present = true;
            obstacle.threshold = Math.max(0.0, cover.penetrationThreshold());
            return obstacle;
        }
        if (isGlassLike(state)) {
            obstacle.present = true;
            obstacle.glass = true;
            return obstacle;
        }
        if (state.canOcclude() || state.blocksMotion()) {
            obstacle.present = true;
            obstacle.threshold = Math.max(
                0.0F, state.getBlock().getExplosionResistance());
        }
        return obstacle;
    }

    public static LineResult traceLine(Level level, Vec3 from, Vec3 to) {
        LineResult result = new LineResult();
        double total = from.distanceTo(to);
        if (total <= 1.0E-4) {
            result.lightDistance = 0.0;
            return result;
        }
        Vec3 dir = to.subtract(from).scale(1.0 / total);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCell = null;
        double traveled = 0.0;

        while (traveled <= total) {
            double px = from.x + dir.x * traveled;
            double py = from.y + dir.y * traveled;
            double pz = from.z + dir.z * traveled;
            cursor.set(
                (int) Math.floor(px),
                (int) Math.floor(py),
                (int) Math.floor(pz)
            );
            if (!cursor.equals(lastCell)) {
                BlockState state = level.getBlockState(cursor);
                Obstacle obstacle = inspect(level, cursor, state);
                if (obstacle.present) {
                    if (obstacle.glass) {
                        result.glassFactor *= GLASS_LIGHT;
                        result.soundFactor *= GLASS_SOUND;
                        result.blastFactor *= GLASS_BLAST;
                    } else {
                        if (!result.lightBlocked) {
                            result.lightBlocked = true;
                            result.lightDistance = traveled;
                        }
                        result.soundFactor *=
                            (float) (SOUND_K / (SOUND_K + obstacle.threshold));
                        result.blastFactor *=
                            (float) (BLAST_K / (BLAST_K + obstacle.threshold));
                    }
                }
                lastCell = cursor.immutable();
            }
            traveled += STEP;
        }

        if (!result.lightBlocked) {
            result.lightDistance = total;
        }
        return result;
    }

    /** 是否为玻璃/玻璃板类：会大幅削弱但不会完全阻挡闪光。 */
    public static boolean isGlassLike(BlockState state) {
        return state.getBlock() instanceof AbstractGlassBlock
            || state.getBlock() instanceof IronBarsBlock;
    }

    /** 是否在采样点处实体阻挡（门、墙体、薄板等均视为阻挡）。 */
    private static boolean blocksDirectly(BlockState state) {
        return state.canOcclude() || state.blocksMotion();
    }

    /** 简易室内判定：头顶被封盖且四周有足够围护，用于冲击波/闪光反射增益。 */
    public static boolean isIndoor(LevelReader level, Vec3 pos) {
        BlockPos base = BlockPos.containing(pos);
        if (level.canSeeSky(base.above(2))) {
            return false;
        }
        int walls = 0;
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockState state = level.getBlockState(base.offset(dx, dy, dz));
                    if (blocksDirectly(state)) {
                        walls++;
                    }
                }
            }
        }
        return walls >= 18;
    }
}
