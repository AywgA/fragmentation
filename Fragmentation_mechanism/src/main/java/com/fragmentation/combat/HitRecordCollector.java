package com.fragmentation.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端：按玩家维护两份命中记录缓冲——
 * incoming（该玩家受到的命中）与 outgoing（该玩家造成的命中）。
 * 主死亡战报取死亡前 {@link #WINDOW_TICKS}（约一分钟）；
 * 结算界面的淘汰详情需要更早的记录，因此缓冲按
 * {@link #RETENTION_TICKS}（约四十五分钟）与会话容量上限保留。
 */
public final class HitRecordCollector {
    /** 战报时间窗：60 秒 × 20 TPS。 */
    public static final long WINDOW_TICKS = 20L * 60L;
    /** 记录保留时长：45 分钟。 */
    private static final long RETENTION_TICKS = WINDOW_TICKS * 45L;
    /** 容量上限（防止极端连射撑爆内存）。 */
    private static final int CAPACITY = 4000;

    private static final Map<UUID, Deque<HitRecord>> INCOMING = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<HitRecord>> OUTGOING = new ConcurrentHashMap<>();

    private HitRecordCollector() {
    }

    /** 记录一条该玩家受到的命中。 */
    public static void addIncoming(UUID playerId, HitRecord record) {
        add(INCOMING, playerId, record);
    }

    /** 记录一条该玩家造成的命中。 */
    public static void addOutgoing(UUID playerId, HitRecord record) {
        add(OUTGOING, playerId, record);
    }

    /** 快照该玩家 sinceTick 之后的受击记录（不移除数据），按时间从早到晚。 */
    public static List<HitRecord> snapshotIncomingSince(UUID playerId, long sinceTick) {
        return snapshotSince(INCOMING, playerId, sinceTick);
    }

    /** 快照该玩家 sinceTick 之后的造成命中记录（不移除数据），按时间从早到晚。 */
    public static List<HitRecord> snapshotOutgoingSince(UUID playerId, long sinceTick) {
        return snapshotSince(OUTGOING, playerId, sinceTick);
    }

    /** 快照该玩家全部受击记录（不移除数据）。 */
    public static List<HitRecord> snapshotIncomingAll(UUID playerId) {
        return snapshotSince(INCOMING, playerId, Long.MIN_VALUE);
    }

    /** 快照该玩家全部造成命中记录（不移除数据）。 */
    public static List<HitRecord> snapshotOutgoingAll(UUID playerId) {
        return snapshotSince(OUTGOING, playerId, Long.MIN_VALUE);
    }

    private static void add(Map<UUID, Deque<HitRecord>> map, UUID playerId, HitRecord record) {
        if (playerId == null || record == null) return;
        Deque<HitRecord> deque = map.computeIfAbsent(playerId, k -> new ArrayDeque<>(64));
        synchronized (deque) {
            deque.addLast(record);
            while (deque.size() > CAPACITY) {
                deque.removeFirst();
            }
            long cutoff = record.tick - RETENTION_TICKS;
            while (!deque.isEmpty()) {
                HitRecord first = deque.peekFirst();
                if (first == null || first.tick >= cutoff) break;
                deque.removeFirst();
            }
        }
    }

    private static List<HitRecord> snapshotSince(Map<UUID, Deque<HitRecord>> map,
                                                 UUID playerId, long sinceTick) {
        Deque<HitRecord> deque = map.get(playerId);
        List<HitRecord> result = new ArrayList<>();
        if (deque == null) {
            return result;
        }
        synchronized (deque) {
            for (HitRecord r : deque) {
                if (r.tick >= sinceTick) {
                    result.add(r);
                }
            }
        }
        return result;
    }
}
