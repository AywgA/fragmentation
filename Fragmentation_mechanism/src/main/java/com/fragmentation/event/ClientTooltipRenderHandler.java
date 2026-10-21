package com.fragmentation.event;

import com.fragmentation.FragmentationMod;
import com.mojang.datafixers.util.Either;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = FragmentationMod.MOD_ID, value = Dist.CLIENT)
public final class ClientTooltipRenderHandler {

    private ClientTooltipRenderHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGatherTooltipComponents(RenderTooltipEvent.GatherComponents event) {
        List<Component> exclusiveTooltip = new ArrayList<>();
        if (!TooltipEventHandler.rebuildExclusiveGunOrBulletTooltip(
            event.getItemStack(),
            exclusiveTooltip
        )) {
            return;
        }

        event.getTooltipElements().clear();
        for (Component line : exclusiveTooltip) {
            event.getTooltipElements().add(Either.left(line));
        }
    }
}
