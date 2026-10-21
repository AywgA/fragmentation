package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import com.fragmentation.ballistics.BallisticsMath;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WorldWindManager {
    public static final double MIN_SPEED = 0.0D;
    public static final double MAX_SPEED = 100.0D;

    private static final List<WindRegion> REGIONS = new ArrayList<>();
    private static final java.util.Random RANDOM = new java.util.Random();

    private WorldWindManager() {
    }

    public static synchronized void loadConfig() {
        File file = new File(WorldConfigManager.getWindsPath());
        if (!file.exists()) {
            REGIONS.clear();
            saveConfig();
            return;
        }

        try (Reader reader = new InputStreamReader(
            new FileInputStream(file),
            StandardCharsets.UTF_8
        )) {
            List<WindRegion> loaded = new Gson().fromJson(
                reader,
                new TypeToken<List<WindRegion>>() { }.getType()
            );
            REGIONS.clear();
            if (loaded != null) {
                for (WindRegion region : loaded) {
                    if (region != null && region.dimension != null && !region.dimension.isBlank()) {
                        region.normalize();
                        REGIONS.add(region);
                    }
                }
            }
            FragmentationMod.LOGGER.info("[Wind] Loaded {} wind regions", REGIONS.size());
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Wind] Failed to load wind regions", e);
        }
    }

    public static synchronized void saveConfig() {
        File file = new File(WorldConfigManager.getWindsPath());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (Writer writer = new OutputStreamWriter(
            new FileOutputStream(file),
            StandardCharsets.UTF_8
        )) {
            new GsonBuilder().setPrettyPrinting().create().toJson(REGIONS, writer);
        } catch (Exception e) {
            FragmentationMod.LOGGER.error("[Wind] Failed to save wind regions", e);
        }
    }

    public static synchronized void setRegion(
        Level level,
        BlockPos first,
        BlockPos second,
        Direction direction,
        double speed
    ) {
        setRegion(
            level,
            first,
            second,
            direction,
            speed,
            BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
        );
    }

    public static synchronized void setRegion(
        Level level,
        BlockPos first,
        BlockPos second,
        Direction direction,
        double speed,
        int temperatureCelsius
    ) {
        WindRegion region = WindRegion.create(
            level.dimension().location().toString(),
            first,
            second,
            direction,
            speed,
            temperatureCelsius
        );
        REGIONS.removeIf(existing -> existing.hasSameBounds(region));
        REGIONS.add(region);
        saveConfig();
    }

    public static synchronized WindSample getWind(Level level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        for (int index = REGIONS.size() - 1; index >= 0; index--) {
            WindRegion region = REGIONS.get(index);
            if (region.contains(dimension, pos)) {
                return region.sample();
            }
        }
        return WindSample.DEFAULT_AIR;
    }

    /**
     * 设置"随机风区域"：区域内每次采样时，风速在 [minSpeed, maxSpeed] 内随机，
     * 风向（水平方位角）也完全随机。
     */
    public static synchronized void setRandomRegion(
        Level level,
        BlockPos first,
        BlockPos second,
        double minSpeed,
        double maxSpeed,
        int temperatureCelsius
    ) {
        WindRegion region = WindRegion.createRandom(
            level.dimension().location().toString(),
            first,
            second,
            minSpeed,
            maxSpeed,
            temperatureCelsius
        );
        REGIONS.removeIf(existing -> existing.hasSameBounds(region));
        REGIONS.add(region);
        saveConfig();
    }

    /**
     * 供弹道使用的风读取。随机风区域会把"本发子弹"抽到的风速/风向缓存到
     * 子弹实体的 persistentData 中，使同一发子弹飞行期间风保持一致
     * （否则每 tick 重新随机，单条弹道会无规律抖动）。每发新子弹独立随机。
     */
    public static synchronized WindSample getWindForBullet(
        Level level,
        BlockPos pos,
        net.minecraft.world.entity.Entity bullet
    ) {
        String dimension = level.dimension().location().toString();
        WindRegion region = null;
        for (int index = REGIONS.size() - 1; index >= 0; index--) {
            WindRegion candidate = REGIONS.get(index);
            if (candidate.contains(dimension, pos)) {
                region = candidate;
                break;
            }
        }
        if (region == null) {
            return WindSample.DEFAULT_AIR;
        }
        if (!region.randomMode) {
            return region.sample();
        }

        net.minecraft.nbt.CompoundTag data = bullet.getPersistentData();
        if (data.getBoolean(CACHED_WIND_MARKER)) {
            return WindSample.createRandom(
                data.getDouble(CACHED_WIND_SPEED),
                region.temperatureCelsius,
                data.getDouble(CACHED_WIND_X),
                data.getDouble(CACHED_WIND_Z)
            );
        }

        WindSample fresh = region.sample();
        data.putBoolean(CACHED_WIND_MARKER, true);
        data.putDouble(CACHED_WIND_SPEED, fresh.speed());
        data.putDouble(CACHED_WIND_X, fresh.dirX());
        data.putDouble(CACHED_WIND_Z, fresh.dirZ());
        return fresh;
    }

    private static final String CACHED_WIND_MARKER = "fragmentation_cached_wind";
    private static final String CACHED_WIND_SPEED = "fragmentation_cached_wind_speed";
    private static final String CACHED_WIND_X = "fragmentation_cached_wind_x";
    private static final String CACHED_WIND_Z = "fragmentation_cached_wind_z";

    public static double clampSpeed(double speed) {
        if (!Double.isFinite(speed)) return MIN_SPEED;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    public enum Direction {
        LEFT("左侧来风", 0.0D, 0.0D, 1.0D),
        RIGHT("右侧来风", 0.0D, 0.0D, -1.0D),
        NORTH("向北风", 0.0D, -1.0D, 0.0D),
        SOUTH("向南风", 0.0D, 1.0D, 0.0D),
        EAST("向东风", 1.0D, 0.0D, 0.0D),
        WEST("向西风", -1.0D, 0.0D, 0.0D);

        private final String displayName;
        private final double worldX;
        private final double worldZ;
        private final double relativeLateralSign;

        Direction(
            String displayName,
            double worldX,
            double worldZ,
            double relativeLateralSign
        ) {
            this.displayName = displayName;
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.relativeLateralSign = relativeLateralSign;
        }

        public double lateralSpeed(double speed, double rightX, double rightZ) {
            double clampedSpeed = clampSpeed(speed);
            if (relativeLateralSign != 0.0D) {
                return relativeLateralSign * clampedSpeed;
            }
            return BallisticsMath.lateralWindSpeed(
                worldX,
                worldZ,
                clampedSpeed,
                rightX,
                rightZ
            );
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public record WindSample(
        Direction direction,
        double speed,
        int temperatureCelsius,
        double dragCoefficient,
        double dirX,
        double dirZ,
        String customLabel
    ) {
        public static final WindSample DEFAULT_AIR = createFixed(
            Direction.LEFT,
            0.0D,
            BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS
        );

        public double lateralSpeed(double rightX, double rightZ) {
            double clampedSpeed = clampSpeed(speed);
            // LEFT/RIGHT 为相对射手的纯侧向风，保持原有直接符号行为
            if (direction == Direction.LEFT) return clampedSpeed;
            if (direction == Direction.RIGHT) return -clampedSpeed;
            // 基本方向与随机风：把世界风向向量投影到射手右方向
            double lateral = (dirX * rightX + dirZ * rightZ) * clampedSpeed;
            return Double.isFinite(lateral) ? lateral : 0.0D;
        }

        /** 用于显示：随机风返回带方位角的标签，否则返回方向枚举名。 */
        public String label() {
            if (customLabel != null) return customLabel;
            return direction != null ? direction.getDisplayName() : "风";
        }

        private static WindSample createFixed(
            Direction direction,
            double speed,
            int temperatureCelsius
        ) {
            Direction safeDirection = direction != null ? direction : Direction.LEFT;
            int normalizedTemperature = BallisticsMath.isSupportedAirTemperature(
                temperatureCelsius
            )
                ? temperatureCelsius
                : BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS;
            return new WindSample(
                safeDirection,
                clampSpeed(speed),
                normalizedTemperature,
                BallisticsMath.airDragCoefficient(normalizedTemperature),
                safeDirection.worldX,
                safeDirection.worldZ,
                null
            );
        }

        private static WindSample createRandom(
            double speed,
            int temperatureCelsius,
            double dirX,
            double dirZ
        ) {
            int normalizedTemperature = BallisticsMath.isSupportedAirTemperature(
                temperatureCelsius
            )
                ? temperatureCelsius
                : BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS;
            double degrees = Math.toDegrees(Math.atan2(dirZ, dirX));
            if (degrees < 0.0D) degrees += 360.0D;
            String label = String.format(
                java.util.Locale.ROOT,
                "随机风向 %.0f°",
                degrees
            );
            return new WindSample(
                null,
                clampSpeed(speed),
                normalizedTemperature,
                BallisticsMath.airDragCoefficient(normalizedTemperature),
                dirX,
                dirZ,
                label
            );
        }
    }

    private static final class WindRegion {
        private String dimension;
        private int minX;
        private int minY;
        private int minZ;
        private int maxX;
        private int maxY;
        private int maxZ;
        private Direction direction;
        private double speed;
        private int temperatureCelsius = BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS;
        @SerializedName("signedSpeed")
        private Double legacySignedSpeed;
        private boolean randomMode;
        private double minSpeed;

        private static WindRegion create(
            String dimension,
            BlockPos first,
            BlockPos second,
            Direction direction,
            double speed,
            int temperatureCelsius
        ) {
            WindRegion region = baseRegion(dimension, first, second);
            region.direction = direction != null ? direction : Direction.LEFT;
            region.speed = clampSpeed(speed);
            region.temperatureCelsius = normalizeTemperature(temperatureCelsius);
            return region;
        }

        private static WindRegion createRandom(
            String dimension,
            BlockPos first,
            BlockPos second,
            double minSpeed,
            double maxSpeed,
            int temperatureCelsius
        ) {
            WindRegion region = baseRegion(dimension, first, second);
            region.randomMode = true;
            region.minSpeed = clampSpeed(minSpeed);
            region.speed = clampSpeed(maxSpeed);
            region.temperatureCelsius = normalizeTemperature(temperatureCelsius);
            return region;
        }

        private static WindRegion baseRegion(
            String dimension,
            BlockPos first,
            BlockPos second
        ) {
            WindRegion region = new WindRegion();
            region.dimension = dimension;
            region.minX = Math.min(first.getX(), second.getX());
            region.minY = Math.min(first.getY(), second.getY());
            region.minZ = Math.min(first.getZ(), second.getZ());
            region.maxX = Math.max(first.getX(), second.getX());
            region.maxY = Math.max(first.getY(), second.getY());
            region.maxZ = Math.max(first.getZ(), second.getZ());
            return region;
        }

        private static int normalizeTemperature(int temperatureCelsius) {
            return BallisticsMath.isSupportedAirTemperature(temperatureCelsius)
                ? temperatureCelsius
                : BallisticsMath.DEFAULT_AIR_TEMPERATURE_CELSIUS;
        }

        private void normalize() {
            int oldMinX = minX;
            int oldMinY = minY;
            int oldMinZ = minZ;
            minX = Math.min(oldMinX, maxX);
            minY = Math.min(oldMinY, maxY);
            minZ = Math.min(oldMinZ, maxZ);
            maxX = Math.max(oldMinX, maxX);
            maxY = Math.max(oldMinY, maxY);
            maxZ = Math.max(oldMinZ, maxZ);
            if (randomMode) {
                // 随机风区域：方向为每样本动态，不做基本方向迁移
                minSpeed = clampSpeed(minSpeed);
                speed = clampSpeed(speed);
                temperatureCelsius = normalizeTemperature(temperatureCelsius);
                legacySignedSpeed = null;
                return;
            }
            if (direction == null) {
                double signedSpeed = legacySignedSpeed != null ? legacySignedSpeed : 0.0D;
                direction = signedSpeed < 0.0D ? Direction.RIGHT : Direction.LEFT;
                speed = Math.abs(signedSpeed);
            }
            speed = clampSpeed(speed);
            temperatureCelsius = normalizeTemperature(temperatureCelsius);
            legacySignedSpeed = null;
        }

        private WindSample sample() {
            if (randomMode) {
                double low = Math.min(minSpeed, speed);
                double high = Math.max(minSpeed, speed);
                double sampledSpeed = low + (high - low) * RANDOM.nextDouble();
                double angle = RANDOM.nextDouble() * 2.0D * Math.PI;
                double sampledDirX = Math.cos(angle);
                double sampledDirZ = Math.sin(angle);
                return WindSample.createRandom(
                    sampledSpeed,
                    temperatureCelsius,
                    sampledDirX,
                    sampledDirZ
                );
            }
            return WindSample.createFixed(direction, speed, temperatureCelsius);
        }

        private boolean contains(String dimension, BlockPos pos) {
            return this.dimension.equals(dimension)
                && pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        private boolean hasSameBounds(WindRegion other) {
            return dimension.equals(other.dimension)
                && minX == other.minX && minY == other.minY && minZ == other.minZ
                && maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ;
        }
    }
}
