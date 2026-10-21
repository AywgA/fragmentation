package com.fragmentation;

import com.fragmentation.commands.*;
import com.fragmentation.combat.penetration.ArcanaPenetrationExtractor;
import com.fragmentation.combat.penetration.BulletScanner;
import com.fragmentation.combat.penetration.PenetrationResolver;
import com.fragmentation.config.ArmorConfig;
import com.fragmentation.config.ArmorMaterialConfig;
import com.fragmentation.config.BulletConfig;
import com.fragmentation.config.ExplosiveConfig;
import com.fragmentation.config.GunConfig;
import com.fragmentation.config.PlateConfig;
import com.fragmentation.config.PenetrationConfig;
import com.fragmentation.config.ProtectionConfig;
import com.fragmentation.config.ShieldConfig;
import com.fragmentation.config.ShieldingFieldConfig;
import com.fragmentation.event.AnvilRepairHandler;
import com.fragmentation.event.ArmorPlateDamageHandler;
import com.fragmentation.event.DamageEventHandler;
import com.fragmentation.event.ShieldingFieldEventHandler;
import com.fragmentation.event.TaczGunEventHandler;
import com.fragmentation.network.ModNetwork;
import com.fragmentation.registry.ModItems;
import com.fragmentation.util.ArmorSoundManager;
import com.fragmentation.util.WindMeterManager;
import com.fragmentation.util.WorldConfigManager;
import com.fragmentation.util.WorldCoverManager;
import com.fragmentation.util.WorldWindManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FragmentationMod.MOD_ID)
public class FragmentationMod {
    public static final String MOD_ID = "fragmentation";
    public static final String MOD_NAME = "Fragmentation Mechanism";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static ArmorConfig armorConfig;
    private static BulletConfig bulletConfig;
    private static ExplosiveConfig explosiveConfig;
    private static PlateConfig plateConfig;
    private static PenetrationConfig penetrationConfig;
    private static ArmorMaterialConfig armorMaterialConfig;
    private static ShieldConfig shieldConfig;
    private static ProtectionConfig protectionConfig;
    private static PenetrationResolver penetrationResolver;
    private static MinecraftServer server;

    public FragmentationMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::registerKeyMappings);

        ModItems.register(FMLJavaModLoadingContext.get().getModEventBus());
        com.fragmentation.registry.ModSounds.register(FMLJavaModLoadingContext.get().getModEventBus());
        com.fragmentation.registry.ModEntities.register(FMLJavaModLoadingContext.get().getModEventBus());
        ArmorSoundManager.register(FMLJavaModLoadingContext.get().getModEventBus());

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(DamageEventHandler.class);
        MinecraftForge.EVENT_BUS.register(TaczGunEventHandler.class);
        MinecraftForge.EVENT_BUS.register(ArmorPlateDamageHandler.class);
        MinecraftForge.EVENT_BUS.register(ShieldingFieldEventHandler.class);
        MinecraftForge.EVENT_BUS.register(AnvilRepairHandler.class);
        MinecraftForge.EVENT_BUS.register(com.fragmentation.event.ExplosiveThrowHandler.class);

        armorConfig = new ArmorConfig();
        bulletConfig = new BulletConfig();
        explosiveConfig = new ExplosiveConfig();
        plateConfig = new PlateConfig();
        penetrationConfig = new PenetrationConfig();
        armorMaterialConfig = new ArmorMaterialConfig();
        shieldConfig = new ShieldConfig();
        protectionConfig = new ProtectionConfig();
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("========================================");
        LOGGER.info("[{}] Mod Loaded - Version: {}", MOD_NAME, ModLoadingContext.get().getActiveContainer().getModInfo().getVersion());
        LOGGER.info("[{}] TACZ Integration: ENABLED", MOD_NAME);
        LOGGER.info("[{}] Arcana Integration: {}", MOD_NAME, ArcanaPenetrationExtractor.isArcanaAvailable() ? "ENABLED" : "DISABLED");
        LOGGER.info("[{}] Debug Mode: ACTIVE", MOD_NAME);
        LOGGER.info("[{}] Hit Marker System: ENABLED", MOD_NAME);
        LOGGER.info("[{}] Bullet Auto-Scanner: ENABLED", MOD_NAME);
        LOGGER.info("========================================");
        LOGGER.info("[{}] Common setup complete", MOD_NAME);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() ->
            net.minecraft.client.renderer.entity.EntityRenderers.register(
                com.fragmentation.registry.ModEntities.EXPLOSIVE_THROWABLE.get(),
                net.minecraft.client.renderer.entity.ThrownItemRenderer::new
            )
        );
    }

    private void registerKeyMappings(
        net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(com.fragmentation.client.ModKeyBindings.SWITCH_VIEW);
        event.register(com.fragmentation.client.ModKeyBindings.CLEAR_VIEW);
        event.register(com.fragmentation.client.ModKeyBindings.BREATH_HOLD);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        WorldConfigManager.init(event.getServer());
        armorConfig.loadConfig();
        bulletConfig.loadConfig();
        explosiveConfig.loadConfig();
        plateConfig.loadConfig();
        penetrationConfig.loadConfig();
        armorMaterialConfig = ArmorMaterialConfig.loadConfig();
        shieldConfig.loadConfig();
        protectionConfig.loadConfig();
        ShieldingFieldConfig.reload();
        GunConfig.reload();
        WorldCoverManager.loadConfig();
        WorldWindManager.loadConfig();
        WindMeterManager.loadConfig();
        
        BulletScanner.autoGenerateBulletConfig();
        
        penetrationResolver = PenetrationResolver.taczCompatible();
        LOGGER.info("[{}] Penetration resolver initialized with {} extractors", MOD_NAME, penetrationResolver.getExtractorsView().size());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModeCommand.register(event.getDispatcher());
        DebugCommand.register(event.getDispatcher());
        TrajectoryCommand.register(event.getDispatcher());
        AddArmorCommand.register(event.getDispatcher());
        AddBulletCommand.register(event.getDispatcher());
        AddBulletByAmmoCommand.register(event.getDispatcher());
        AddAllTaczBulletsCommand.register(event.getDispatcher());
        AddPlateCommand.register(event.getDispatcher());
        SetGunPowerCommand.register(event.getDispatcher());
        SetGunDamageCommand.register(event.getDispatcher());
        SetGunDetailsCommand.register(event.getDispatcher());
        SetCoverCommand.register(event.getDispatcher());
        SetWindCommand.register(event.getDispatcher());
        WindMeterCommand.register(event.getDispatcher());
        SetArmorCommand.register(event.getDispatcher());
        SetArmorMaterialCommand.register(event.getDispatcher());
        SetPlateMaterialCommand.register(event.getDispatcher());
        SetBulletCommand.register(event.getDispatcher());
        ListArmorCommand.register(event.getDispatcher());
        ListBulletCommand.register(event.getDispatcher());
        RemoveArmorCommand.register(event.getDispatcher());
        RemoveBulletCommand.register(event.getDispatcher());
        AddShieldCommand.register(event.getDispatcher());
        SetShieldCommand.register(event.getDispatcher());
        ListShieldCommand.register(event.getDispatcher());
        ShieldingFieldCommand.register(event.getDispatcher());
        FragmentationAiCommand.register(event.getDispatcher());
        FragmentationCommand.register(event.getDispatcher());
        VisorCommand.register(event.getDispatcher());
        ProtectionCommand.register(event.getDispatcher());
        SaveConfigCommand.register(event.getDispatcher());
        ReloadConfigCommand.register(event.getDispatcher());
        AddFlashCommand.register(event.getDispatcher());
        AddGrenadeCommand.register(event.getDispatcher());
        AddNadeCommand.register(event.getDispatcher());
    }

    public static ArmorConfig getArmorConfig() {
        return armorConfig;
    }

    public static BulletConfig getBulletConfig() {
        return bulletConfig;
    }

    public static ExplosiveConfig getExplosiveConfig() {
        return explosiveConfig;
    }

    public static PlateConfig getPlateConfig() {
        return plateConfig;
    }

    public static PenetrationConfig getPenetrationConfig() {
        return penetrationConfig;
    }

    public static PenetrationResolver getPenetrationResolver() {
        return penetrationResolver;
    }

    public static ArmorMaterialConfig getArmorMaterialConfig() {
        return armorMaterialConfig;
    }

    public static ShieldConfig getShieldConfig() {
        return shieldConfig;
    }

    public static ProtectionConfig getProtectionConfig() {
        return protectionConfig;
    }

    public static MinecraftServer getServer() {
        return server;
    }
}
