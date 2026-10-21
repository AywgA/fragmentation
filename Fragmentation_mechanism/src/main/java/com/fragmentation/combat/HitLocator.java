package com.fragmentation.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 精确子弹落点：把「子弹接触点 / 近战攻击点」在受害者碰撞箱中的相对位置，
 * 映射为人体图（绘制矩形）上的归一化坐标，支持头皮、指尖等精确位置。
 *
 * 所有坐标：x 0=最左 1=最右；y 0=脚底 1=头顶。
 */
public final class HitLocator {

    // ---------- 世界碰撞箱的部位纵向区间（从脚底起的比例） ----------
    private static final float[] WORLD_BANDS = {
        0.87F, 1.00F,  // HEAD
        0.60F, 0.86F,  // CHEST
        0.45F, 0.59F,  // ABDOMEN
        0.44F, 0.83F,  // ARMS
        0.00F, 0.43F   // LEGS
    };

    // ---------- 人体图各部位矩形 {x0,y0,x1,y1} ----------
    private static final float[] RECT_HEAD      = {0.45F, 0.88F, 0.59F, 0.985F};
    private static final float[] RECT_CHEST     = {0.42F, 0.66F, 0.57F, 0.85F};
    private static final float[] RECT_ABDOMEN   = {0.42F, 0.48F, 0.57F, 0.65F};
    private static final float[] RECT_RIGHT_ARM = {0.30F, 0.46F, 0.41F, 0.82F};
    private static final float[] RECT_LEFT_ARM  = {0.59F, 0.46F, 0.70F, 0.82F};
    private static final float[] RECT_RIGHT_LEG = {0.34F, 0.02F, 0.48F, 0.46F};
    private static final float[] RECT_LEFT_LEG  = {0.52F, 0.02F, 0.66F, 0.46F};

    /** 躯干/头部的 nx 有效映射区间（避免侧面落点被拉到边缘）。 */
    private static final float CENTER_NX_MIN = 0.30F;
    private static final float CENTER_NX_MAX = 0.70F;

    private HitLocator() {
    }

    /**
     * 投射物精确落点：
     * 用子弹本 tick 射线段与受害者碰撞箱求进入点（高速步进子弹的
     * position() 可能还在步进起点）。返回人体图归一化坐标。
     */
    public static float[] projectile(Entity projectile, LivingEntity victim, String zone) {
        Vec3 end = projectile.position();
        Vec3 vel = projectile.getDeltaMovement();
        Vec3 start = end.subtract(vel);
        AABB realBox = victim.getBoundingBox();
        AABB hitBox = realBox.inflate(0.08D);

        Vec3 contact = end;
        Vec3 seg = end.subtract(start);
        double len = seg.length();
        if (len > 1.0E-6D) {
            Vec3 dir = seg.scale(1.0D / len);
            double t = rayBoxEntry(start, dir, hitBox);
            if (t >= 0.0D && t <= len + 0.5D) {
                contact = start.add(dir.scale(t));
            }
        }
        return worldToFigure(realBox, contact, victim, zone);
    }

    /**
     * 近战精确落点：
     * 攻击者眼睛位置钳制到受害者碰撞箱表面（打头则落在头部高度）。
     */
    public static float[] melee(LivingEntity attacker, LivingEntity victim, String zone) {
        AABB box = victim.getBoundingBox();
        Vec3 eye = attacker != null ? attacker.getEyePosition()
            : new Vec3(box.getCenter().x, box.minY + 0.9D, box.getCenter().z);

        Vec3 contact = new Vec3(
            clamp(eye.x, box.minX, box.maxX),
            clamp(eye.y, box.minY, box.maxY),
            clamp(eye.z, box.minZ, box.maxZ)
        );
        return worldToFigure(box, contact, victim, zone);
    }

    // ================= 映射 =================

    private static float[] worldToFigure(AABB box, Vec3 contact, LivingEntity victim, String zone) {
        // 按受害者身体朝向求局部左右（背面视角：其右手在画面左侧）。
        double yaw = Math.toRadians(victim.yBodyRot);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double cx = (box.minX + box.maxX) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        double localRight = (contact.x - cx) * rightX + (contact.z - cz) * rightZ;
        double width = Math.max(1.0E-4D, box.maxX - box.minX);

        float nx = (float) (0.5D - localRight / width);
        float worldFy = (float) ((contact.y - box.minY) / Math.max(1.0E-4D, box.maxY - box.minY));
        nx = clamp(nx, 0.0F, 1.0F);
        worldFy = clamp(worldFy, 0.0F, 1.0F);
        return toFigure(zone, nx, worldFy);
    }

    /** 把碰撞箱内相对位置映射到人体图矩形。 */
    public static float[] toFigure(String zone, float nx, float worldFy) {
        int zi = zoneIndex(zone);
        int bandPair = switch (zi) {
            case 0 -> 0;              // head
            case 1 -> 1;              // chest
            case 2 -> 2;              // abdomen
            case 3, 4 -> 3;           // arms
            default -> 4;             // legs
        };
        float b0 = WORLD_BANDS[bandPair * 2];
        float b1 = WORLD_BANDS[bandPair * 2 + 1];
        float ly = mapClamped(worldFy, b0, b1, 0.0F, 1.0F);

        float[] rect = figureRect(zone);

        // 手臂/腿直接横向映射；头/躯干按中心区间映射。
        float lx;
        if (zi <= 2) {
            lx = mapClamped(nx, CENTER_NX_MIN, CENTER_NX_MAX, 0.0F, 1.0F);
        } else {
            lx = nx;
        }

        float fx = rect[0] + lx * (rect[2] - rect[0]);
        float fy = rect[1] + ly * (rect[3] - rect[1]);
        return new float[]{clamp(fx, 0.0F, 1.0F), clamp(fy, 0.0F, 1.0F)};
    }

    private static float[] figureRect(String zone) {
        return switch (zoneIndex(zone)) {
            case 0 -> RECT_HEAD;
            case 1 -> RECT_CHEST;
            case 2 -> RECT_ABDOMEN;
            case 3 -> RECT_LEFT_ARM;
            case 4 -> RECT_RIGHT_ARM;
            case 5 -> RECT_LEFT_LEG;
            default -> RECT_RIGHT_LEG;
        };
    }

    private static int zoneIndex(String zone) {
        if (zone == null) return 1;
        return switch (zone) {
            case "HEAD", "FACE" -> 0;
            case "CHEST" -> 1;
            case "ABDOMEN" -> 2;
            case "LEFT_ARM" -> 3;
            case "RIGHT_ARM" -> 4;
            case "LEFT_LEG" -> 5;
            case "RIGHT_LEG" -> 6;
            default -> 1;
        };
    }

    // ================= 数学 =================

    /** 射线段进入 AABB 的参数 t；无交点返回 -1。 */
    private static double rayBoxEntry(Vec3 origin, Vec3 dir, AABB box) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {dir.x, dir.y, dir.z};
        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-7D) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1.0D;
            } else {
                double t1 = (lo[i] - o[i]) / d[i];
                double t2 = (hi[i] - o[i]) / d[i];
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
        return tMin >= 0.0D ? tMin : (tMax >= 0.0D ? 0.0D : -1.0D);
    }

    private static float mapClamped(float v, float src0, float src1, float dst0, float dst1) {
        float t = (v - src0) / (src1 - src0);
        t = clamp(t, 0.0F, 1.0F);
        return dst0 + t * (dst1 - dst0);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
