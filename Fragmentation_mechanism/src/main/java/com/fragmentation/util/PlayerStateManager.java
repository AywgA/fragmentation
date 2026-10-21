package com.fragmentation.util;

import com.fragmentation.FragmentationMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerStateManager {
    
    public enum AddMode {
        NONE,
        ARMOR,
        BULLET
    }
    
    private static final Map<UUID, AddMode> playerAddModes = new HashMap<>();
    private static final Map<UUID, Boolean> playerDebugModes = new HashMap<>();
    private static final Map<UUID, Boolean> playerTrajectoryModes = new HashMap<>();
    /** 部位随机结果提示开关：默认关闭，仅当玩家主动开启时才在屏幕上显示随机结果。 */
    private static final Map<UUID, Boolean> playerZoneRandomNoticeModes = new HashMap<>();
    
    public static AddMode getAddMode(Player player) {
        return playerAddModes.getOrDefault(player.getUUID(), AddMode.NONE);
    }
    
    public static void setAddMode(Player player, AddMode mode) {
        playerAddModes.put(player.getUUID(), mode);
    }
    
    public static boolean isDebugMode(Player player) {
        return playerDebugModes.getOrDefault(player.getUUID(), false);
    }
    
    public static void setDebugMode(Player player, boolean enabled) {
        playerDebugModes.put(player.getUUID(), enabled);
    }
    
    public static boolean toggleDebugMode(Player player) {
        boolean current = isDebugMode(player);
        setDebugMode(player, !current);
        return !current;
    }
    
    /**
     * AI 特殊伤害判定模式：持久化到世界数据（world/data/fragmentation_ai_damage_modes.dat），
     * 退出/进入世界、切换维度、死亡均不影响，仅可通过 /fragmentation Ai shut 关闭。
     */
    public static boolean isAiDamageMode(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        return AiDamageModeData.get(server).isEnabled(player.getUUID());
    }

    public static void setAiDamageMode(Player player, boolean enabled) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AiDamageModeData.get(server).setEnabled(player.getUUID(), enabled);
    }

    public static boolean isTrajectoryMode(Player player) {
        return playerTrajectoryModes.getOrDefault(player.getUUID(), false);
    }

    public static void setTrajectoryMode(Player player, boolean enabled) {
        playerTrajectoryModes.put(player.getUUID(), enabled);
    }

    /** 部位随机结果提示是否开启（默认关闭）。 */
    public static boolean isZoneRandomNoticeMode(Player player) {
        return playerZoneRandomNoticeModes.getOrDefault(player.getUUID(), false);
    }

    public static void setZoneRandomNoticeMode(Player player, boolean enabled) {
        playerZoneRandomNoticeModes.put(player.getUUID(), enabled);
    }

    public static void cleanupPlayer(UUID playerId) {
        playerAddModes.remove(playerId);
        playerDebugModes.remove(playerId);
        playerTrajectoryModes.remove(playerId);
        playerZoneRandomNoticeModes.remove(playerId);
    }
    
    @Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
    public static class PlayerCleanupHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            cleanupPlayer(event.getEntity().getUUID());
        }
        
        @SubscribeEvent
        public static void onPlayerDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof Player player) {
                cleanupNonTrajectoryState(player.getUUID());
            }
        }
        
        @SubscribeEvent
        public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            cleanupNonTrajectoryState(event.getEntity().getUUID());
        }

        private static void cleanupNonTrajectoryState(UUID playerId) {
            playerAddModes.remove(playerId);
            playerDebugModes.remove(playerId);
            playerZoneRandomNoticeModes.remove(playerId);
        }
    }
}
