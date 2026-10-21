package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.fragmentation.combat.HitRecordCollector;
import com.fragmentation.network.DeathReportPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家死亡时取出最近的受击/造成命中记录，下发死亡战报界面。
 */
@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DeathReportHandler {

    private DeathReportHandler() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (player.level().isClientSide()) return;

            long nowTick = player.serverLevel().getGameTime();
            long sinceTick = nowTick - HitRecordCollector.WINDOW_TICKS;

            DeathReportPacket packet = new DeathReportPacket(
                HitRecordCollector.snapshotIncomingSince(player.getUUID(), sinceTick),
                HitRecordCollector.snapshotOutgoingSince(player.getUUID(), sinceTick)
            );

            com.fragmentation.network.ModNetwork.sendToPlayer(player, packet);

            FragmentationMod.LOGGER.info(
                "[DeathReport] Sent death report to " + player.getName().getString()
                    + ": incoming=" + packet.incoming().size()
                    + ", outgoing=" + packet.outgoing().size());
        } catch (Throwable t) {
            FragmentationMod.LOGGER.error("[DeathReport] Failed to send death report", t);
        }
    }
}
