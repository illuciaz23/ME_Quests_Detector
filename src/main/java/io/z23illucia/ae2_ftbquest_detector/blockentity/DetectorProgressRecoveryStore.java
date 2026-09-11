package io.z23illucia.ae2_ftbquest_detector.blockentity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保存区块卸载前没能写回的检测器进度。
 * 账本存放在主世界的 SavedData 中，因此可以跨正常重启存活。
 */
final class DetectorProgressRecoveryStore {
    static final int MAX_PENDING_ENTRIES = 8192;
    private static final String DATA_ID = "ae2_ftbquest_detector_progress";
    private static final long FALLBACK_LOG_INTERVAL_MS = 30_000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong LAST_FALLBACK_LOG_MS = new AtomicLong(Long.MIN_VALUE);
    private static final Map<MinecraftServer, PendingProgressLedger> VOLATILE_FALLBACK =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DetectorProgressRecoveryStore() {
    }

    static boolean retain(MinecraftServer server, UUID teamId, long taskId, long targetProgress) {
        if (server == null || teamId == null || taskId <= 0L || targetProgress <= 0L) {
            return false;
        }
        try {
            PendingProgressSavedData data = getOrCreate(server);
            promoteVolatileFallback(server, data);
            data.merge(teamId, taskId, targetProgress);
            return true;
        } catch (RuntimeException exception) {
            logFallback(server, exception);
        }
        retainVolatileFallback(server, teamId, taskId, targetProgress);
        logFallback(server, null);
        return true;
    }

    static List<PendingProgress> copyFor(MinecraftServer server, UUID teamId) {
        if (server == null || teamId == null) {
            return List.of();
        }
        PendingProgressSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            return data.copyFor(teamId);
        }
        PendingProgressLedger fallback = getVolatileFallback(server, false);
        return fallback == null ? List.of() : fallback.copyFor(teamId);
    }

    static void remove(MinecraftServer server, UUID teamId, long taskId) {
        if (server == null || teamId == null || taskId <= 0L) {
            return;
        }
        PendingProgressSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.remove(teamId, taskId);
        } else {
            PendingProgressLedger fallback = getVolatileFallback(server, false);
            if (fallback != null) {
                fallback.remove(teamId, taskId);
            }
        }
    }

    static void removeIfAtMost(MinecraftServer server, UUID teamId, long taskId, long committedProgress) {
        if (server == null || teamId == null || taskId <= 0L || committedProgress <= 0L) {
            return;
        }
        PendingProgressSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.removeIfAtMost(teamId, taskId, committedProgress);
        } else {
            PendingProgressLedger fallback = getVolatileFallback(server, false);
            if (fallback != null) {
                fallback.removeIfAtMost(teamId, taskId, committedProgress);
            }
        }
    }

    static boolean replace(MinecraftServer server, UUID teamId, long taskId, long targetProgress) {
        if (server == null || teamId == null || taskId <= 0L || targetProgress <= 0L) {
            return false;
        }
        try {
            PendingProgressSavedData data = getOrCreate(server);
            promoteVolatileFallback(server, data);
            data.replace(teamId, taskId, targetProgress);
            return true;
        } catch (RuntimeException exception) {
            logFallback(server, exception);
        }
        PendingProgressLedger fallback = getVolatileFallback(server, true);
        fallback.replace(teamId, taskId, targetProgress);
        logFallback(server, null);
        return true;
    }

    static boolean moveTeam(MinecraftServer server, UUID previousTeamId, UUID newTeamId) {
        if (server == null || previousTeamId == null || newTeamId == null
                || previousTeamId.equals(newTeamId)) {
            return false;
        }
        try {
            PendingProgressSavedData data = getOrCreate(server);
            promoteVolatileFallback(server, data);
            data.moveTeam(previousTeamId, newTeamId);
            return true;
        } catch (RuntimeException exception) {
            logFallback(server, exception);
        }
        PendingProgressLedger fallback = getVolatileFallback(server, false);
        if (fallback != null) {
            fallback.moveTeam(previousTeamId, newTeamId);
            return true;
        }
        return false;
    }

    static void discard(MinecraftServer server, UUID teamId) {
        if (server == null || teamId == null) {
            return;
        }
        PendingProgressSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.discard(teamId);
        } else {
            PendingProgressLedger fallback = getVolatileFallback(server, false);
            if (fallback != null) {
                fallback.discard(teamId);
            }
        }
    }

    static void discardTeamsNotIn(MinecraftServer server, Set<UUID> knownTeamIds) {
        if (server == null || knownTeamIds == null) {
            return;
        }
        PendingProgressSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.discardTeamsNotIn(knownTeamIds);
        } else {
            PendingProgressLedger fallback = getVolatileFallback(server, false);
            if (fallback != null) {
                fallback.discardTeamsNotIn(knownTeamIds);
            }
        }
    }

    private static PendingProgressSavedData getOrCreate(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                PendingProgressSavedData::load, PendingProgressSavedData::new, DATA_ID);
    }

    private static PendingProgressSavedData getExisting(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().get(PendingProgressSavedData::load, DATA_ID);
    }

    private static PendingProgressSavedData tryGetExisting(MinecraftServer server) {
        try {
            return getExisting(server);
        } catch (RuntimeException exception) {
            logFallback(server, exception);
            return null;
        }
    }

    private static void retainVolatileFallback(
            MinecraftServer server, UUID teamId, long taskId, long targetProgress) {
        getVolatileFallback(server, true).merge(teamId, taskId, targetProgress);
    }

    private static PendingProgressLedger getVolatileFallback(MinecraftServer server, boolean create) {
        synchronized (VOLATILE_FALLBACK) {
            if (create) {
                return VOLATILE_FALLBACK.computeIfAbsent(server, ignored -> new PendingProgressLedger());
            }
            return VOLATILE_FALLBACK.get(server);
        }
    }

    private static void promoteVolatileFallback(MinecraftServer server, PendingProgressSavedData data) {
        PendingProgressLedger fallback = getVolatileFallback(server, false);
        if (fallback == null) {
            return;
        }
        try {
            for (PendingProgressEntry entry : fallback.snapshot()) {
                data.merge(entry.teamId(), entry.taskId(), entry.targetProgress());
            }
            synchronized (VOLATILE_FALLBACK) {
                if (VOLATILE_FALLBACK.get(server) == fallback) {
                    VOLATILE_FALLBACK.remove(server);
                }
            }
        } catch (RuntimeException exception) {
            logFallback(server, exception);
        }
    }

    private static void logFallback(MinecraftServer server, RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = LAST_FALLBACK_LOG_MS.get();
        if ((previous == Long.MIN_VALUE || now - previous >= FALLBACK_LOG_INTERVAL_MS)
                && LAST_FALLBACK_LOG_MS.compareAndSet(previous, now)) {
            if (exception == null) {
                LOGGER.warn("Detector progress SavedData is unavailable for {}; retaining progress in the server fallback queue",
                        server);
            } else {
                LOGGER.warn("Failed to access detector progress SavedData for {}; retaining progress in the server fallback queue",
                        server, exception);
            }
        }
    }

    record PendingProgress(long taskId, long targetProgress) {
    }

    private static final class PendingProgressSavedData extends SavedData {
        private final PendingProgressLedger ledger = new PendingProgressLedger();

        private static PendingProgressSavedData load(CompoundTag tag) {
            PendingProgressSavedData data = new PendingProgressSavedData();
            ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (!entry.hasUUID("teamId") || !entry.contains("taskId", Tag.TAG_LONG)
                        || !entry.contains("targetProgress", Tag.TAG_LONG)) {
                    continue;
                }
                long taskId = entry.getLong("taskId");
                long targetProgress = entry.getLong("targetProgress");
                if (taskId > 0L && targetProgress > 0L) {
                    data.ledger.merge(entry.getUUID("teamId"), taskId, targetProgress);
                }
            }
            return data;
        }

        private void merge(UUID teamId, long taskId, long targetProgress) {
            ledger.merge(teamId, taskId, targetProgress);
            setDirty();
        }

        private List<PendingProgress> copyFor(UUID teamId) {
            return ledger.copyFor(teamId);
        }

        private void remove(UUID teamId, long taskId) {
            int before = ledger.size();
            ledger.remove(teamId, taskId);
            if (ledger.size() != before) {
                setDirty();
            }
        }

        private void removeIfAtMost(UUID teamId, long taskId, long committedProgress) {
            int before = ledger.size();
            ledger.removeIfAtMost(teamId, taskId, committedProgress);
            if (ledger.size() != before) {
                setDirty();
            }
        }

        private void replace(UUID teamId, long taskId, long targetProgress) {
            ledger.replace(teamId, taskId, targetProgress);
            setDirty();
        }

        private void moveTeam(UUID previousTeamId, UUID newTeamId) {
            int before = ledger.size();
            ledger.moveTeam(previousTeamId, newTeamId);
            if (ledger.size() != before) {
                setDirty();
            }
        }

        private void discard(UUID teamId) {
            int before = ledger.size();
            ledger.discard(teamId);
            if (ledger.size() != before) {
                setDirty();
            }
        }

        private void discardTeamsNotIn(Set<UUID> knownTeamIds) {
            int before = ledger.size();
            ledger.discardTeamsNotIn(knownTeamIds);
            if (ledger.size() != before) {
                setDirty();
            }
        }

        @Override
        public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
            ListTag entries = new ListTag();
            for (PendingProgressEntry entry : ledger.snapshot()) {
                CompoundTag value = new CompoundTag();
                value.putUUID("teamId", entry.teamId());
                value.putLong("taskId", entry.taskId());
                value.putLong("targetProgress", entry.targetProgress());
                entries.add(value);
            }
            tag.put("entries", entries);
            return tag;
        }
    }

    private record PendingProgressEntry(UUID teamId, long taskId, long targetProgress) {
    }

    static final class PendingProgressLedger {
        private final int maxEntries;
        private final LinkedHashMap<PendingKey, Long> values = new LinkedHashMap<>();

        PendingProgressLedger() {
            this(MAX_PENDING_ENTRIES);
        }

        PendingProgressLedger(int maxEntries) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
            this.maxEntries = maxEntries;
        }

        synchronized void merge(UUID teamId, long taskId, long targetProgress) {
            PendingKey key = new PendingKey(teamId, taskId);
            Long previous = values.get(key);
            if (previous != null) {
                if (targetProgress > previous) {
                    values.put(key, targetProgress);
                }
                return;
            }
            while (values.size() >= maxEntries) {
                Iterator<PendingKey> iterator = values.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
            values.put(key, targetProgress);
        }

        synchronized List<PendingProgress> copyFor(UUID teamId) {
            List<PendingProgress> result = new ArrayList<>();
            for (Map.Entry<PendingKey, Long> entry : values.entrySet()) {
                if (entry.getKey().teamId().equals(teamId)) {
                    result.add(new PendingProgress(entry.getKey().taskId(), entry.getValue()));
                }
            }
            return result;
        }

        synchronized List<PendingProgressEntry> snapshot() {
            List<PendingProgressEntry> result = new ArrayList<>(values.size());
            for (Map.Entry<PendingKey, Long> entry : values.entrySet()) {
                PendingKey key = entry.getKey();
                result.add(new PendingProgressEntry(key.teamId(), key.taskId(), entry.getValue()));
            }
            return result;
        }

        synchronized void remove(UUID teamId, long taskId) {
            values.remove(new PendingKey(teamId, taskId));
        }

        synchronized void removeIfAtMost(UUID teamId, long taskId, long committedProgress) {
            PendingKey key = new PendingKey(teamId, taskId);
            Long targetProgress = values.get(key);
            if (targetProgress != null && targetProgress <= committedProgress) {
                values.remove(key);
            }
        }

        synchronized void replace(UUID teamId, long taskId, long targetProgress) {
            PendingKey key = new PendingKey(teamId, taskId);
            if (!values.containsKey(key)) {
                while (values.size() >= maxEntries) {
                    Iterator<PendingKey> iterator = values.keySet().iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    iterator.next();
                    iterator.remove();
                }
            }
            values.put(key, targetProgress);
        }

        synchronized void moveTeam(UUID previousTeamId, UUID newTeamId) {
            List<PendingProgressEntry> pending = new ArrayList<>();
            for (Map.Entry<PendingKey, Long> entry : values.entrySet()) {
                if (entry.getKey().teamId().equals(previousTeamId)) {
                    pending.add(new PendingProgressEntry(
                            entry.getKey().teamId(), entry.getKey().taskId(), entry.getValue()));
                }
            }
            for (PendingProgressEntry entry : pending) {
                PendingKey previousKey = new PendingKey(previousTeamId, entry.taskId());
                PendingKey newKey = new PendingKey(newTeamId, entry.taskId());
                Long existing = values.get(newKey);
                if (existing == null || entry.targetProgress() > existing) {
                    values.put(newKey, entry.targetProgress());
                }
                values.remove(previousKey);
            }
        }

        synchronized void discard(UUID teamId) {
            values.keySet().removeIf(key -> key.teamId().equals(teamId));
        }

        synchronized void discardTeamsNotIn(Set<UUID> knownTeamIds) {
            values.keySet().removeIf(key -> !knownTeamIds.contains(key.teamId()));
        }

        synchronized int size() {
            return values.size();
        }

        private record PendingKey(UUID teamId, long taskId) {
        }
    }
}
