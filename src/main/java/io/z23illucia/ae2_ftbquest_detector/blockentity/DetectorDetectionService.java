package io.z23illucia.ae2_ftbquest_detector.blockentity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IStackWatcher;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.z23illucia.ae2_ftbquest_detector.utility.CoalescingLongQueue;
import io.z23illucia.ae2_ftbquest_detector.utility.DetectorProgressSyncContext;
import io.z23illucia.ae2_ftbquest_detector.utility.FtbRuntime;
import io.z23illucia.ae2_ftbquest_detector.utility.QuestTaskEligibility;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamOwnershipValidator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class DetectorDetectionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PARTIAL_PROGRESS_FLUSH_INTERVAL = 40 * 3;
    private static final int MAX_PROGRESS_UPDATES_PER_TICK = 64;

    private final Map<AEKey, List<Task>> cachedTasksByKey = new ConcurrentHashMap<>();
    private final Map<AEKey, List<Task>> activeTasksByKey = new ConcurrentHashMap<>();
    private final DetectorItemTaskIndex itemTaskIndex = new DetectorItemTaskIndex();
    private final Map<AEKey, Long> pendingKeyAmounts = new ConcurrentHashMap<>();
    private final CoalescingLongQueue<Task> progressUpdates = new CoalescingLongQueue<>();
    private final Set<RecoveryKey> recoveryTasksPending = ConcurrentHashMap.newKeySet();

    private IStackWatcher stackWatcher;
    private boolean cacheDirty = true;
    private boolean activeCacheDirty = true;
    private boolean stateDirty = true;
    private boolean fullScanPending = true;
    private boolean reconnectPending;
    private boolean immediateProgressFlushPending;
    private long nextPartialProgressFlushGameTime = Long.MIN_VALUE;

    synchronized void updateWatcher(IStackWatcher watcher) {
        stackWatcher = watcher;
        configureWatcher();
    }

    synchronized void onStackChange(AEKey key, long amount) {
        pendingKeyAmounts.put(key, amount);
        stateDirty = true;
    }

    synchronized void tick(DetectorBlockEntity detector, long gameTime, boolean detectionTick) {
        if (!FtbRuntime.isAvailable()) {
            return;
        }
        if (detectionTick) {
            scanIfNeeded(detector, gameTime);
        }
        flushProgressIfReady(detector, gameTime);
    }

    synchronized void requestReconnect() {
        reconnectPending = true;
        stateDirty = true;
    }

    synchronized void markCacheDirty() {
        cacheDirty = true;
        activeCacheDirty = true;
        pendingKeyAmounts.clear();
        requestFullScan();
    }

    synchronized void markActiveCacheDirty() {
        activeCacheDirty = true;
        requestFullScan();
    }

    private void invalidateCachesPreservingProgress() {
        cacheDirty = true;
        activeCacheDirty = true;
        pendingKeyAmounts.clear();
        requestFullScan();
    }

    synchronized void requestFullScan() {
        fullScanPending = true;
        stateDirty = true;
    }

    /** 在检测器换队伍或任务对象失效前，把排队中的进度落盘。 */
    synchronized boolean persistPendingProgress(DetectorBlockEntity detector, UUID teamId) {
        if (progressUpdates.isEmpty()) {
            return true;
        }
        MinecraftServer server = getServer(detector);
        if (server == null || teamId == null) {
            LOGGER.warn("Cannot persist pending detector progress at {} because its server context is unavailable",
                    detector.getBlockPos());
            return false;
        }

        long gameTime = detector.getLevel() instanceof ServerLevel serverLevel
                ? serverLevel.getGameTime()
                : 0L;
        try {
            flushProgress(detector, gameTime, true);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to flush pending detector progress for team {}; retaining it for retry",
                    teamId, exception);
        }
        if (progressUpdates.isEmpty()) {
            return true;
        }

        try {
            progressUpdates.drain(Integer.MAX_VALUE, (task, targetProgress) -> {
                if (DetectorProgressRecoveryStore.retain(server, teamId, task.id, targetProgress)) {
                    recoveryTasksPending.remove(new RecoveryKey(teamId, task.id));
                    return;
                }
                throw new IllegalStateException("Detector progress recovery storage is unavailable");
            });
            immediateProgressFlushPending = false;
            nextPartialProgressFlushGameTime = Long.MIN_VALUE;
            return progressUpdates.isEmpty();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to persist pending detector progress for team {}; retrying later",
                    teamId, exception);
            return false;
        }
    }

    synchronized void onLoaded(DetectorBlockEntity detector) {
        restoreProgressAfterReload(detector);
        resetForReload(!progressUpdates.isEmpty());
    }

    synchronized void onUnloaded(DetectorBlockEntity detector, long gameTime) {
        if (isOwnerTeamDefinitelyUnavailable(detector)) {
            discardPendingProgress(detector);
        }
        boolean retained = false;
        if (!isOwnerTeamDefinitelyUnavailable(detector)) {
            try {
                flushProgress(detector, gameTime, true);
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to flush detector progress before unloading {}", detector.getBlockPos(), exception);
            } finally {
                retained = retainProgressAfterUnload(detector);
            }
        }
        if (retained) {
            progressUpdates.clear();
            recoveryTasksPending.clear();
        }
        stackWatcher = null;
        cachedTasksByKey.clear();
        activeTasksByKey.clear();
        itemTaskIndex.clear();
        pendingKeyAmounts.clear();
        cacheDirty = true;
        activeCacheDirty = true;
        stateDirty = true;
        fullScanPending = true;
        reconnectPending = false;
        immediateProgressFlushPending = false;
        nextPartialProgressFlushGameTime = Long.MIN_VALUE;
    }

    private void scanIfNeeded(DetectorBlockEntity detector, long gameTime) {
        if (isOwnerTeamDefinitelyUnavailable(detector)) {
            discardPendingProgress(detector);
        } else {
            restoreProgressAfterReload(detector);
        }
        if (reconnectPending) {
            reconnectPending = false;
            invalidateCachesPreservingProgress();
        }
        if (detector.isNetworkConflict()) {
            stateDirty = hasPendingScanWork();
            return;
        }
        if (!stateDirty && !hasPendingScanWork()) {
            return;
        }

        boolean processed = scan(detector, gameTime);
        stateDirty = !processed || hasPendingScanWork();
    }

    private boolean scan(DetectorBlockEntity detector, long gameTime) {
        DetectionContext context = resolveContext(detector, true);
        if (context == null) {
            return false;
        }

        updateTaskCacheIfNeeded(context.file);
        updateActiveCacheIfNeeded(context.teamData);
        if (fullScanPending) {
            pendingKeyAmounts.clear();
            processFullScan(context.teamData, context.availableStacks, gameTime);
            fullScanPending = false;
        } else if (!pendingKeyAmounts.isEmpty()) {
            processPendingKeyUpdates(context.teamData, gameTime);
            pendingKeyAmounts.clear();
        }
        return true;
    }

    private void flushProgressIfReady(DetectorBlockEntity detector, long gameTime) {
        flushProgress(detector, gameTime, false);
    }

    private void flushProgress(DetectorBlockEntity detector, long gameTime, boolean force) {
        if (progressUpdates.isEmpty() || !force && !isFlushReady(gameTime)) {
            return;
        }
        DetectionContext context = resolveContext(detector, false);
        if (context == null) {
            if (!force) {
                stateDirty = true;
            }
            return;
        }

        boolean[] completedTask = {false};
        UUID teamId = context.teamData.getTeamId();
        MinecraftServer server = getServer(detector);
        try (DetectorProgressSyncContext.Scope ignored = DetectorProgressSyncContext.suppressTeamDirtyNotifications()) {
            progressUpdates.drain(MAX_PROGRESS_UPDATES_PER_TICK, (task, targetProgress) -> {
                long currentProgress = context.teamData.getProgress(task);
                long boundedTargetProgress = Math.max(0L,
                        Math.min(targetProgress, Math.max(0L, task.getMaxProgress())));
                if (boundedTargetProgress <= currentProgress) {
                    acknowledgeRecoveryProgress(server, teamId, task, currentProgress);
                    return;
                }
                if (currentProgress < task.getMaxProgress()
                        && boundedTargetProgress >= task.getMaxProgress()) {
                    completedTask[0] = true;
                }
                context.teamData.setProgress(task, boundedTargetProgress);
                acknowledgeRecoveryProgress(server, teamId, task, boundedTargetProgress);
            });
        } finally {
            if (completedTask[0]) {
                DetectorEntityList.markActiveCacheDirtyForTeam(context.teamData.getTeamId());
            }
        }

        if (progressUpdates.isEmpty()) {
            immediateProgressFlushPending = false;
            nextPartialProgressFlushGameTime = Long.MIN_VALUE;
        }
    }

    private DetectionContext resolveContext(DetectorBlockEntity detector, boolean requireInventory) {
        if (requireInventory && (!detector.getMainNode().isReady()
                || !detector.getMainNode().isActive() || detector.isNetworkConflict())) {
            return null;
        }
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        UUID ownerTeamId = detector.ownerTeamId;
        if (file == null || TeamOwnershipValidator.getStatus(ownerTeamId)
                != TeamOwnershipValidator.Status.USABLE) {
            return null;
        }
        TeamData teamData = file.getNullableTeamData(ownerTeamId);
        if (teamData == null || teamData.isLocked()) {
            return null;
        }
        if (!requireInventory) {
            return new DetectionContext(file, teamData, null);
        }

        IGrid grid = detector.getMainNode().getGrid();
        if (grid == null || grid.getStorageService() == null || grid.getStorageService().getInventory() == null) {
            return null;
        }
        return new DetectionContext(file, teamData, grid.getStorageService().getInventory().getAvailableStacks());
    }

    private void updateTaskCacheIfNeeded(ServerQuestFile file) {
        if (!cacheDirty) {
            return;
        }
        cachedTasksByKey.clear();
        itemTaskIndex.clear();

        for (Task task : file.getSubmitTasks()) {
            if (task.consumesResources()) {
                continue;
            }
            if (task instanceof ItemTask itemTask) {
                if (itemTask.getItemStack().isEmpty()) {
                    continue;
                }
                if (itemTaskIndex.addIfFlexible(itemTask)) {
                    continue;
                }
            }
            AEKey key = taskKey(task);
            if (key == null) {
                continue;
            }
            cachedTasksByKey.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(task);
        }
        configureWatcher();
        cacheDirty = false;
        activeCacheDirty = true;
    }

    private void updateActiveCacheIfNeeded(TeamData data) {
        if (!activeCacheDirty) {
            return;
        }
        activeTasksByKey.clear();
        itemTaskIndex.updateActive(data);
        for (Map.Entry<AEKey, List<Task>> entry : cachedTasksByKey.entrySet()) {
            List<Task> active = null;
            for (Task task : entry.getValue()) {
                if (!QuestTaskEligibility.canAutoSubmit(task, data)) {
                    continue;
                }
                if (active == null) {
                    active = new ArrayList<>(1);
                }
                active.add(task);
            }
            if (active != null) {
                activeTasksByKey.put(entry.getKey(), List.copyOf(active));
            }
        }
        activeCacheDirty = false;
    }

    private void processFullScan(TeamData data, KeyCounter availableStacks, long gameTime) {
        for (Map.Entry<AEKey, List<Task>> entry : activeTasksByKey.entrySet()) {
            long amount = availableStacks == null ? 0L : availableStacks.get(entry.getKey());
            queueProgressForKey(data, entry.getKey(), amount, gameTime);
        }
        if (availableStacks == null || !itemTaskIndex.hasActiveFlexibleTasks()) {
            return;
        }

        Map<ItemTask, Long> matchingAmounts = new ConcurrentHashMap<>();
        itemTaskIndex.collectMatchingAmounts(availableStacks, matchingAmounts);
        for (Map.Entry<ItemTask, Long> entry : matchingAmounts.entrySet()) {
            queueProgressForTask(data, entry.getKey(), entry.getValue(), gameTime);
        }
    }

    private void processPendingKeyUpdates(TeamData data, long gameTime) {
        boolean flexibleItemChanged = false;
        for (Map.Entry<AEKey, Long> entry : pendingKeyAmounts.entrySet()) {
            queueProgressForKey(data, entry.getKey(), entry.getValue(), gameTime);
            flexibleItemChanged |= entry.getKey() instanceof AEItemKey && itemTaskIndex.hasActiveFlexibleTasks();
        }
        if (flexibleItemChanged) {
            fullScanPending = true;
        }
    }

    private void queueProgressForKey(TeamData data, AEKey key, long amount, long gameTime) {
        if (amount <= 0L) {
            return;
        }
        List<Task> tasks = activeTasksByKey.get(key);
        if (tasks == null) {
            return;
        }
        for (Task task : tasks) {
            queueProgressForTask(data, task, amount, gameTime);
        }
    }

    private void queueProgressForTask(TeamData data, Task task, long amount, long gameTime) {
        long persistedProgress = data.getProgress(task);
        long targetProgress = Math.min(task.getMaxProgress(), amount);
        if (targetProgress <= persistedProgress) {
            return;
        }
        progressUpdates.offerMax(task, targetProgress);
        if (targetProgress >= task.getMaxProgress()) {
            immediateProgressFlushPending = true;
        } else if (nextPartialProgressFlushGameTime == Long.MIN_VALUE) {
            nextPartialProgressFlushGameTime = gameTime + PARTIAL_PROGRESS_FLUSH_INTERVAL;
        }
    }

    private void configureWatcher() {
        if (stackWatcher == null) {
            return;
        }
        stackWatcher.reset();
        stackWatcher.setWatchAll(itemTaskIndex.hasFlexibleTasks());
        for (AEKey key : cachedTasksByKey.keySet()) {
            stackWatcher.add(key);
        }
    }

    private boolean isFlushReady(long gameTime) {
        return immediateProgressFlushPending
                || nextPartialProgressFlushGameTime != Long.MIN_VALUE && gameTime >= nextPartialProgressFlushGameTime;
    }

    private boolean hasPendingScanWork() {
        return fullScanPending || !pendingKeyAmounts.isEmpty();
    }

    private void clearDerivedState() {
        pendingKeyAmounts.clear();
        progressUpdates.clear();
        recoveryTasksPending.clear();
        immediateProgressFlushPending = false;
        nextPartialProgressFlushGameTime = Long.MIN_VALUE;
    }

    private void resetForReload(boolean preserveProgress) {
        cacheDirty = true;
        activeCacheDirty = true;
        pendingKeyAmounts.clear();
        if (preserveProgress) {
            immediateProgressFlushPending = !progressUpdates.isEmpty();
            nextPartialProgressFlushGameTime = Long.MIN_VALUE;
        } else {
            clearDerivedState();
        }
        stateDirty = true;
        fullScanPending = true;
    }

    private boolean isOwnerTeamDefinitelyUnavailable(DetectorBlockEntity detector) {
        TeamOwnershipValidator.Status status = TeamOwnershipValidator.getStatus(detector.ownerTeamId);
        return status == TeamOwnershipValidator.Status.NONE
                || status == TeamOwnershipValidator.Status.INVALID;
    }

    synchronized void discardPendingProgress(DetectorBlockEntity detector) {
        progressUpdates.clear();
        recoveryTasksPending.clear();
        immediateProgressFlushPending = false;
        nextPartialProgressFlushGameTime = Long.MIN_VALUE;
        discardRecoveredProgress(detector);
    }

    private void restoreProgressAfterReload(DetectorBlockEntity detector) {
        MinecraftServer server = getServer(detector);
        UUID teamId = detector.ownerTeamId;
        if (server == null || teamId == null) {
            return;
        }

        TeamOwnershipValidator.Status status = TeamOwnershipValidator.getStatus(teamId);
        if (status == TeamOwnershipValidator.Status.NONE
                || status == TeamOwnershipValidator.Status.INVALID) {
            recoveryTasksPending.removeIf(key -> key.teamId().equals(teamId));
            DetectorProgressRecoveryStore.discard(server, teamId);
            return;
        }
        if (status != TeamOwnershipValidator.Status.USABLE || ServerQuestFile.INSTANCE == null) {
            return;
        }

        for (DetectorProgressRecoveryStore.PendingProgress pending
                : DetectorProgressRecoveryStore.copyFor(server, teamId)) {
            try {
                Task task = ServerQuestFile.INSTANCE.getTask(pending.taskId());
                if (task == null || task.consumesResources() || taskKey(task) == null) {
                    recoveryTasksPending.remove(new RecoveryKey(teamId, pending.taskId()));
                    DetectorProgressRecoveryStore.remove(server, teamId, pending.taskId());
                    continue;
                }
                long storedTargetProgress = pending.targetProgress();
                long targetProgress = Math.min(task.getMaxProgress(), storedTargetProgress);
                if (targetProgress <= 0L) {
                    recoveryTasksPending.remove(new RecoveryKey(teamId, pending.taskId()));
                    DetectorProgressRecoveryStore.remove(server, teamId, pending.taskId());
                    continue;
                }
                if (targetProgress < storedTargetProgress
                        && !DetectorProgressRecoveryStore.replace(
                        server, teamId, pending.taskId(), targetProgress)) {
                    LOGGER.warn("Failed to clamp detector recovery progress for task {} and team {}; retrying later",
                            pending.taskId(), teamId);
                    continue;
                }
                recoveryTasksPending.add(new RecoveryKey(teamId, pending.taskId()));
                progressUpdates.offerMax(task, targetProgress);
                immediateProgressFlushPending = true;
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to restore detector progress for task {} and team {}",
                        pending.taskId(), teamId, exception);
            }
        }
    }

    private boolean retainProgressAfterUnload(DetectorBlockEntity detector) {
        if (progressUpdates.isEmpty()) {
            return true;
        }
        MinecraftServer server = getServer(detector);
        UUID teamId = detector.ownerTeamId;
        if (server == null || teamId == null) {
            LOGGER.error("Keeping pending detector progress at {} because its server context is unavailable; "
                            + "it will be retried if the block entity is loaded again",
                    detector.getBlockPos());
            return false;
        }

        try {
            progressUpdates.drain(Integer.MAX_VALUE, (task, targetProgress) -> {
                RuntimeException lastFailure = new IllegalStateException(
                        "No recovery storage failure was captured");
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        if (DetectorProgressRecoveryStore.retain(server, teamId, task.id, targetProgress)) {
                            return;
                        }
                        throw new IllegalStateException("Detector progress recovery storage is unavailable");
                    } catch (RuntimeException exception) {
                        lastFailure = exception;
                        LOGGER.warn("Failed to retain detector progress for team {} (attempt {}/3)",
                                teamId, attempt, exception);
                    }
                }
                throw lastFailure;
            });
            return progressUpdates.isEmpty();
        } catch (RuntimeException exception) {
            LOGGER.error("Keeping pending detector progress at {} after recovery storage failure",
                    detector.getBlockPos(), exception);
            return false;
        }
    }

    private void discardRecoveredProgress(DetectorBlockEntity detector) {
        MinecraftServer server = getServer(detector);
        if (server != null && detector.ownerTeamId != null) {
            DetectorProgressRecoveryStore.discard(server, detector.ownerTeamId);
        }
    }

    private void acknowledgeRecoveryProgress(MinecraftServer server, UUID teamId, Task task, long committedProgress) {
        if (!recoveryTasksPending.remove(new RecoveryKey(teamId, task.id)) || server == null) {
            return;
        }
        try {
            DetectorProgressRecoveryStore.removeIfAtMost(server, teamId, task.id, committedProgress);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to acknowledge recovered detector progress for task {} and team {}",
                    task.id, teamId, exception);
        }
    }

    private static MinecraftServer getServer(DetectorBlockEntity detector) {
        if (detector.getLevel() instanceof ServerLevel serverLevel) {
            return serverLevel.getServer();
        }
        MinecraftServer cachedServer = detector.getServerContext();
        if (cachedServer != null) {
            return cachedServer;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            return manager == null ? null : manager.getServer();
        } catch (RuntimeException exception) {
            LOGGER.debug("Failed to resolve the server from FTB Teams", exception);
            return null;
        }
    }

    private static AEKey taskKey(Task task) {
        if (task instanceof FluidTask fluidTask) {
            return AEFluidKey.of(fluidTask.getFluid(), fluidTask.getFluidNBT());
        }
        if (task instanceof ItemTask itemTask) {
            return AEItemKey.of(itemTask.getItemStack());
        }
        return null;
    }

    private record DetectionContext(ServerQuestFile file, TeamData teamData, KeyCounter availableStacks) {
    }

    private record RecoveryKey(UUID teamId, long taskId) {
    }
}
