package com.fragmentation.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AI 特殊伤害判定模式的持久化存储（世界级，随存档保存）。
 * 存储于 world/data/fragmentation_ai_damage_modes.dat，
 * 退出/进入世界、切换维度、死亡均不影响；
 * 只能通过 /fragmentation Ai shut 关闭。
 */
public class AiDamageModeData extends SavedData {

    private static final String DATA_NAME = "fragmentation_ai_damage_modes";

    private final Set<UUID> enabledPlayers = new HashSet<>();

    public static AiDamageModeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            AiDamageModeData::load, AiDamageModeData::new, DATA_NAME);
    }

    public static AiDamageModeData load(CompoundTag tag) {
        AiDamageModeData data = new AiDamageModeData();
        ListTag list = tag.getList("enabled", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.enabledPlayers.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : enabledPlayers) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put("enabled", list);
        return tag;
    }

    public boolean isEnabled(UUID playerId) {
        return enabledPlayers.contains(playerId);
    }

    public void setEnabled(UUID playerId, boolean enabled) {
        boolean changed = enabled ? enabledPlayers.add(playerId) : enabledPlayers.remove(playerId);
        if (changed) {
            setDirty();
        }
    }
}
