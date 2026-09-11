package io.z23illucia.ae2_ftbquest_detector.integration.jade;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import io.z23illucia.ae2_ftbquest_detector.Config;
import io.z23illucia.ae2_ftbquest_detector.block.DetectorBlock;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorBlockEntity;
import io.z23illucia.ae2_ftbquest_detector.network.DetectorClientPreferencesPayload;
import io.z23illucia.ae2_ftbquest_detector.utility.BoundedExpiringCache;
import io.z23illucia.ae2_ftbquest_detector.utility.DetectorStatusPolicy;
import io.z23illucia.ae2_ftbquest_detector.utility.FtbRuntime;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamDisplayNameResolver;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamOwnershipValidator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class DetectorProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    public static final DetectorProvider INSTANCE = new DetectorProvider();
    public static final ResourceLocation UID =
            new ResourceLocation("ae2_ftbquest_detector", "detector");
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long JADE_TASK_STATS_TTL_MS = 1000L;
    private static final int JADE_TASK_STATS_MAX_ENTRIES = 512;
    private static ServerQuestFile jadeTaskStatsFileRef;
    private static final BoundedExpiringCache<JadeTaskStatsCacheKey, JadeTaskStats> JADE_TASK_STATS =
            new BoundedExpiringCache<>(JADE_TASK_STATS_MAX_ENTRIES);

    private record JadeTaskStats(int completed, int total) {
    }

    private record JadeTaskStatsCacheKey(UUID teamId, boolean ignoreHidden, boolean ignoreRepeatable) {
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data.getBoolean("InvalidOwnerTeam")) {
            tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.invalid_owner"));
            return;
        }
        if (data.getBoolean("NoOwnerTeam")) {
            tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.no_owner"));
            return;
        }
        if (data.getBoolean("TemporaryOwnerTeam")) {
            tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.uncharged"));
            return;
        }
        if (data.getBoolean("NetworkConflict")) {
            tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.network_conflict"));
            return;
        }
        boolean isPowered = accessor.getBlockState().getValue(Objects.requireNonNull(DetectorBlock.POWERED));
        if (!isPowered) {
            tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.uncharged"));
            return;
        }
        if (Config.jadeShowOwnerInfo) {
            if (data.hasUUID("TeamId")) {
                UUID teamId = data.getUUID("TeamId");
                String rawTeamName = data.contains("RawTeamName") ? data.getString("RawTeamName") : null;
                String teamName = rawTeamName != null
                        ? TeamDisplayNameResolver.formatDisplayName(rawTeamName, teamId, Config.getTeamNameDisplayMode())
                        : TeamDisplayNameResolver.resolveExistingTeamName(
                                teamId, null, Config.getTeamNameDisplayMode());
                if (teamName != null) {
                    tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.owner_is", teamName));
                }
            } else {
                tooltip.add(Component.translatable("ae2-ftbquests-detector.detector.no_owner"));
            }
        }

        if (Config.jadeShowTaskProgress && data.contains("CompletedTasks")) {
            int completed = data.getInt("CompletedTasks");
            int total = data.getInt("TotalTasks");
            tooltip.add(Component.translatable("ae2-ftbquests-detector.jade.tasks", completed, total));
        }
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            appendServerDataInternal(data, accessor);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to build Jade data for detector", exception);
        }
    }

    private void appendServerDataInternal(CompoundTag data, BlockAccessor accessor) {
        if (!FtbRuntime.isAvailable()) {
            return;
        }
        if (accessor.getBlockEntity() instanceof DetectorBlockEntity detector) {
            TeamOwnershipValidator.Status teamStatus = detector.getOwnerTeamStatus();
            boolean powered = accessor.getBlockState().getValue(Objects.requireNonNull(DetectorBlock.POWERED));
            DetectorStatusPolicy.State presentation = DetectorStatusPolicy.resolve(
                    teamStatus, detector.isNetworkConflict(), powered);
            if (presentation == DetectorStatusPolicy.State.INVALID_OWNER_TEAM) {
                data.putBoolean("InvalidOwnerTeam", true);
                return;
            }
            if (presentation == DetectorStatusPolicy.State.NO_OWNER_TEAM) {
                data.putBoolean("NoOwnerTeam", true);
                return;
            }
            if (presentation == DetectorStatusPolicy.State.TEMPORARILY_UNAVAILABLE) {
                data.putBoolean("TemporaryOwnerTeam", true);
                return;
            }
            if (presentation == DetectorStatusPolicy.State.NETWORK_CONFLICT) {
                data.putBoolean("NetworkConflict", true);
                return;
            }
            if (presentation == DetectorStatusPolicy.State.OFFLINE) {
                return;
            }
            if (detector.ownerTeamId != null) {
                data.putUUID("TeamId", detector.ownerTeamId);
                String rawTeamName = TeamDisplayNameResolver.resolveRawTeamName(
                        detector.ownerTeamId, detector.ownerTeamNameCache);
                if (rawTeamName != null) {
                    data.putString("RawTeamName", rawTeamName);
                }

                if (ServerQuestFile.INSTANCE != null
                        && DetectorClientPreferencesPayload.shouldComputeTaskProgress(accessor.getPlayer())) {
                    TeamData teamData = ServerQuestFile.INSTANCE.getNullableTeamData(detector.ownerTeamId);
                    if (teamData != null) {
                        JadeTaskStats stats = computeOrGetCachedTaskStats(
                                ServerQuestFile.INSTANCE, teamData, detector.ownerTeamId);
                        data.putInt("CompletedTasks", stats.completed);
                        data.putInt("TotalTasks", stats.total);
                    }
                }
            }
        }
    }

    private static synchronized JadeTaskStats computeOrGetCachedTaskStats(ServerQuestFile file, TeamData teamData, UUID teamId) {
        long now = System.currentTimeMillis();
        if (jadeTaskStatsFileRef != file) {
            JADE_TASK_STATS.clear();
            jadeTaskStatsFileRef = file;
        }
        boolean ignoreHidden = Config.serverJadeTaskProgressIgnoreHiddenTasks;
        boolean ignoreRepeatable = Config.serverJadeTaskProgressIgnoreRepeatableTasks;
        JadeTaskStatsCacheKey cacheKey = new JadeTaskStatsCacheKey(teamId, ignoreHidden, ignoreRepeatable);
        JadeTaskStats cached = JADE_TASK_STATS.get(cacheKey, now);
        if (cached != null) {
            return cached;
        }
        List<Task> tasks = file.getAllTasks();
        int total = 0;
        int completed = 0;
        for (Task task : tasks) {
            if (!JadeTaskFilterPolicy.include(
                    task.getQuest().isVisible(teamData),
                    task.getQuest().canBeRepeated(),
                    ignoreHidden,
                    ignoreRepeatable)) {
                continue;
            }
            total++;
            if (teamData.isCompleted(task)) {
                completed++;
            }
        }
        JadeTaskStats stats = new JadeTaskStats(completed, total);
        JADE_TASK_STATS.put(cacheKey, stats, now + JADE_TASK_STATS_TTL_MS, now);
        return stats;
    }

    public static synchronized void invalidateTaskStatsCache() {
        JADE_TASK_STATS.clear();
        jadeTaskStatsFileRef = null;
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
