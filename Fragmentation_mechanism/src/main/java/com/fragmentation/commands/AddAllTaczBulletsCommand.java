package com.fragmentation.commands;

import com.fragmentation.FragmentationMod;
import com.fragmentation.config.BulletConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public class AddAllTaczBulletsCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("addalltaczbullets")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    return addAllBullets(context.getSource(), 1, 5.0);
                })
                .then(Commands.argument("penLevel", IntegerArgumentType.integer(1, 110))
                    .executes(context -> {
                        int penLevel = IntegerArgumentType.getInteger(context, "penLevel");
                        return addAllBullets(context.getSource(), penLevel, 5.0);
                    })
                    .then(Commands.argument("armorDamage", DoubleArgumentType.doubleArg(0.0, 1000.0))
                        .executes(context -> {
                            int penLevel = IntegerArgumentType.getInteger(context, "penLevel");
                            double armorDamage = DoubleArgumentType.getDouble(context, "armorDamage");
                            return addAllBullets(context.getSource(), penLevel, armorDamage);
                        })
                    )
                )
        );
    }
    
    private static int addAllBullets(CommandSourceStack source, int penLevel, double armorDamage) {
        BulletConfig config = FragmentationMod.getBulletConfig();
        List<String> addedBullets = new ArrayList<>();
        List<String> skippedBullets = new ArrayList<>();
        
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
            if (itemId == null) continue;
            
            String namespace = itemId.getNamespace();
            String path = itemId.getPath();
            
            if (!namespace.equals("tacz")) continue;
            
            boolean isAmmo = path.contains("ammo") || path.contains("bullet") || path.contains("round");
            if (!isAmmo) continue;
            
            String ammoId = itemId.toString();
            
            if (config.getBulletByAmmoId(ammoId) != null) {
                skippedBullets.add(ammoId);
                continue;
            }
            
            String bulletId = path.replace("/", "_").replace(".", "_");
            
            boolean success = config.addBulletByAmmoId(ammoId, penLevel, armorDamage);
            if (success) {
                addedBullets.add(ammoId);
            } else {
                skippedBullets.add(ammoId);
            }
        }
        
        config.saveConfig();
        
        StringBuilder sb = new StringBuilder();
        sb.append("§a=== 批量添加TACZ弹药完成 ===§r\n");
        sb.append("§f默认穿深等级:§r ").append(penLevel).append(" / 110\n");
        sb.append("§f默认护甲伤害:§r ").append(armorDamage).append("\n");
        sb.append("§a成功添加:§r ").append(addedBullets.size()).append(" 个\n");
        sb.append("§7已存在跳过:§r ").append(skippedBullets.size()).append(" 个\n");
        
        if (!addedBullets.isEmpty()) {
            sb.append("\n§a已添加的弹药:§r\n");
            int count = 0;
            for (String id : addedBullets) {
                if (count < 10) {
                    sb.append("  §7-§r ").append(id).append("\n");
                }
                count++;
            }
            if (count > 10) {
                sb.append("  §7... 还有 ").append(count - 10).append(" 个§r\n");
            }
        }
        
        sb.append("\n§7提示: 使用 /listbullet 查看所有子弹§r\n");
        sb.append("§7提示: 使用 /setbulletpen <等级> 修改单个子弹穿甲等级§r");
        
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
        
        return addedBullets.size();
    }
}
