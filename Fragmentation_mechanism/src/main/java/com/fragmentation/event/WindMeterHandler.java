package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.util.WindMeterManager;
import com.fragmentation.util.WorldWindManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID)
public final class WindMeterHandler {
    private static final Map<UUID, Long> LAST_MEASUREMENT_TICK = new HashMap<>();

    private WindMeterHandler() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        measure(event);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        measure(event);
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        measure(event);
    }

    private static void measure(PlayerInteractEvent event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        if (level.isClientSide) {
            return;
        }

        ItemStack stack = player.getItemInHand(event.getHand());
        if (!WindMeterManager.isWindMeter(stack)) {
            return;
        }

        long gameTime = level.getGameTime();
        Long previousTick = LAST_MEASUREMENT_TICK.put(player.getUUID(), gameTime);
        if (previousTick != null && previousTick == gameTime) {
            return;
        }

        // 右键与蹲下右键行为一致：都测量当前所在位置的精确风速
        BlockPos origin = player.blockPosition();
        player.sendSystemMessage(Component.literal(formatSingleMeasurement(
            origin,
            WorldWindManager.getWind(level, origin)
        )));
    }

    private static String formatSingleMeasurement(
        BlockPos pos,
        WorldWindManager.WindSample sample
    ) {
        return String.format(
            Locale.ROOT,
            "[风速仪 %d, %d, %d] 当前空气阻力 %.5f，当前空气温度 %d°C，当前风速 %s %.2f m/s",
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            sample.dragCoefficient(),
            sample.temperatureCelsius(),
            sample.label(),
            sample.speed()
        );
    }
}
