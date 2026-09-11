package io.z23illucia.ae2_ftbquest_detector.blockentity;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 记录等待进度落盘的检测器队伍迁移。 */
final class DetectorTeamMigrationStore {
    private static final String DATA_ID = "ae2_ftbquest_detector_team_migrations";
    private static final int MAX_PENDING_MIGRATIONS = 4096;
    private static final long FALLBACK_LOG_INTERVAL_MS = 30_000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong LAST_FALLBACK_LOG_MS = new AtomicLong(Long.MIN_VALUE);
    private static final Map<MinecraftServer, PendingMigrationLedger> VOLATILE_FALLBACK =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DetectorTeamMigrationStore() {
    }

    static boolean retain(DetectorBlockEntity detector, UUID targetTeamId) {
        MigrationKey key = key(detector);
        MinecraftServer server = server(detector);
        return key != null && targetTeamId != null && retain(server, key, targetTeamId);
    }

    static UUID get(DetectorBlockEntity detector) {
        MigrationKey key = key(detector);
        MinecraftServer server = server(detector);
        if (key == null || server == null) {
            return null;
        }
        PendingMigrationSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            return data.get(key);
        }
        PendingMigrationLedger fallback = getVolatileFallback(server, false);
        return fallback == null ? null : fallback.get(key);
    }

    static void remove(DetectorBlockEntity detector) {
        MigrationKey key = key(detector);
        MinecraftServer server = server(detector);
        if (key == null || server == null) {
            return;
        }
        PendingMigrationSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.remove(key);
            return;
        }
        PendingMigrationLedger fallback = getVolatileFallback(server, false);
        if (fallback != null) {
            fallback.remove(key);
        }
    }

    static void discardTarget(MinecraftServer server, UUID targetTeamId) {
        if (server == null || targetTeamId == null) {
            return;
        }
        PendingMigrationSavedData data = tryGetExisting(server);
        if (data != null) {
            promoteVolatileFallback(server, data);
            data.discardTarget(targetTeamId);
        } else {
            PendingMigrationLedger fallback = getVolatileFallback(server, false);
            if (fallback != null) {
                fallback.discardTarget(targetTeamId);
            }
        }
    }

    private static boolean retain(MinecraftServer server, MigrationKey key, UUID targetTeamId) {
        if (server == null || key == null || targetTeamId == null) {
            return false;
        }
        try {
            PendingMigrationSavedData data = getOrCreate(server);
            promoteVolatileFallback(server, data);
            data.retain(key, targetTeamId);
            return true;
        } catch (RuntimeException exception) {
            logFallback(server, exception);
        }
        getVolatileFallback(server, true).retain(key, targetTeamId);
        logFallback(server, null);
        return true;
    }

    private static MigrationKey key(DetectorBlockEntity detector) {
        if (detector == null) {
            return null;
        }
        String dimension = detector.getDimensionContext();
        return dimension == null ? null : new MigrationKey(dimension, detector.getBlockPos().asLong());
    }

    private static MinecraftServer server(DetectorBlockEntity detector) {
        if (detector != null && detector.getLevel() instanceof ServerLevel serverLevel) {
            return serverLevel.getServer();
        }
        if (detector != null && detector.getServerContext() != null) {
            return detector.getServerContext();
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            return manager == null ? null : manager.getServer();
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to resolve the server for detector team migration", exception);
        }
        return null;
    }

    private static PendingMigrationSavedData getOrCreate(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                PendingMigrationSavedData::load, PendingMigrationSavedData::new, DATA_ID);
    }

    private static PendingMigrationSavedData getExisting(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().get(PendingMigrationSavedData::load, DATA_ID);
    }

    private static PendingMigrationSavedData tryGetExisting(MinecraftServer server) {
        try {
            return getExisting(server);
        } catch (RuntimeException exception) {
            logFallback(server, exception);
            return null;
        }
    }

    private static PendingMigrationLedger getVolatileFallback(MinecraftServer server, boolean create) {
        synchronized (VOLATILE_FALLBACK) {
            if (create) {
                return VOLATILE_FALLBACK.computeIfAbsent(server, ignored -> new PendingMigrationLedger());
            }
            return VOLATILE_FALLBACK.get(server);
        }
    }

    private static void promoteVolatileFallback(MinecraftServer server, PendingMigrationSavedData data) {
        PendingMigrationLedger fallback = getVolatileFallback(server, false);
        if (fallback == null) {
            return;
        }
        try {
            for (Map.Entry<MigrationKey, UUID> entry : fallback.snapshot().entrySet()) {
                data.retain(entry.getKey(), entry.getValue());
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
                LOGGER.warn("Detector team migration SavedData is unavailable for {}; retaining migration in the server fallback queue",
                        server);
            } else {
                LOGGER.warn("Failed to access detector team migration SavedData for {}; retaining migration in the server fallback queue",
                        server, exception);
            }
        }
    }

    record MigrationKey(String dimension, long blockPos) {
    }

    static final class PendingMigrationLedger {
        private final LinkedHashMap<MigrationKey, UUID> values = new LinkedHashMap<>();

        synchronized void retain(MigrationKey key, UUID targetTeamId) {
            if (!values.containsKey(key)) {
                while (values.size() >= MAX_PENDING_MIGRATIONS) {
                    Iterator<MigrationKey> iterator = values.keySet().iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    iterator.next();
                    iterator.remove();
                }
            }
            values.put(key, targetTeamId);
        }

        synchronized UUID get(MigrationKey key) {
            return values.get(key);
        }

        synchronized void remove(MigrationKey key) {
            values.remove(key);
        }

        synchronized void discardTarget(UUID targetTeamId) {
            values.entrySet().removeIf(entry -> entry.getValue().equals(targetTeamId));
        }

        synchronized Map<MigrationKey, UUID> snapshot() {
            return new LinkedHashMap<>(values);
        }
    }

    private static final class PendingMigrationSavedData extends SavedData {
        private final PendingMigrationLedger ledger = new PendingMigrationLedger();

        private static PendingMigrationSavedData load(CompoundTag tag) {
            PendingMigrationSavedData data = new PendingMigrationSavedData();
            ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = entries.getCompound(index);
                if (!entry.contains("dimension", Tag.TAG_STRING)
                        || !entry.contains("blockPos", Tag.TAG_LONG)
                        || !entry.hasUUID("targetTeamId")) {
                    continue;
                }
                String dimension = entry.getString("dimension");
                if (!dimension.isBlank()) {
                    data.ledger.retain(
                            new MigrationKey(dimension, entry.getLong("blockPos")),
                            entry.getUUID("targetTeamId"));
                }
            }
            return data;
        }

        private void retain(MigrationKey key, UUID targetTeamId) {
            ledger.retain(key, targetTeamId);
            setDirty();
        }

        private UUID get(MigrationKey key) {
            return ledger.get(key);
        }

        private void remove(MigrationKey key) {
            int before = ledger.snapshot().size();
            ledger.remove(key);
            if (ledger.snapshot().size() != before) {
                setDirty();
            }
        }

        private void discardTarget(UUID targetTeamId) {
            int before = ledger.snapshot().size();
            ledger.discardTarget(targetTeamId);
            if (ledger.snapshot().size() != before) {
                setDirty();
            }
        }

        @Override
        public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
            ListTag entries = new ListTag();
            for (Map.Entry<MigrationKey, UUID> entry : ledger.snapshot().entrySet()) {
                CompoundTag value = new CompoundTag();
                value.putString("dimension", entry.getKey().dimension());
                value.putLong("blockPos", entry.getKey().blockPos());
                value.putUUID("targetTeamId", entry.getValue());
                entries.add(value);
            }
            tag.put("entries", entries);
            return tag;
        }
    }
}
