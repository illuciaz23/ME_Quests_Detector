package io.z23illucia.ae2_ftbquest_detector.blockentity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.z23illucia.ae2_ftbquest_detector.Config;
import io.z23illucia.ae2_ftbquest_detector.block.DetectorBlock;
import io.z23illucia.ae2_ftbquest_detector.registry.ModBlockEntities;
import io.z23illucia.ae2_ftbquest_detector.registry.ModItems;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamDisplayNameResolver;
import io.z23illucia.ae2_ftbquest_detector.utility.TeamOwnershipValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("null")
public class DetectorBlockEntity extends AENetworkBlockEntity implements IStorageWatcherNode, IGridServiceProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public UUID ownerTeamId;
    public String ownerTeamNameCache;
    public final Set<UUID> shortNameWarnedPlayers = ConcurrentHashMap.newKeySet();

    private final DetectorDetectionService detectionService = new DetectorDetectionService();
    private final DetectorStateRefreshQueue stateRefreshQueue = new DetectorStateRefreshQueue();

    /** 同一 ME 网络存在多个检测器时停止工作。 */
    private boolean networkConflict = false;
    private boolean lifecycleLoaded;
    private boolean chunkUnloadPendingRemoval;
    private MinecraftServer serverContext;
    private String dimensionContext;
    private int teamValidationRetryTicks;
    private boolean delayedTeamRevalidation;
    private UUID pendingOwnerTeamId;
    private int pendingOwnerTeamRetryTicks;
    private int tickCount = 0;

    public DetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DETECTOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode()
                .setIdlePowerUsage(4.0)
                .setVisualRepresentation(ModItems.DETECTOR_BLOCK_ITEM.get())
                .addService(IStorageWatcherNode.class, this);
    }

    /**
     * AE2 会用代表性物品作为节点在网络信息里的展示（图标与名称）。
     * {@code AENetworkBlockEntity} 的字段初始化会调用 {@link #getItemFromBlockEntity()}
     * 覆盖 createMainNode 里设置的视觉代表，而本模组的方块实体类型没有登记到 AE2 的
     * REPRESENTATIVE_ITEMS 表中，默认会退化成空气，因此在 1.20.1 必须显式提供。
     */
    @Override
    protected Item getItemFromBlockEntity() {
        return ModItems.DETECTOR_BLOCK_ITEM.get();
    }

    @Override
    public void updateWatcher(IStackWatcher iStackWatcher) {
        detectionService.updateWatcher(iStackWatcher);
    }

    /** AE2 正在遍历 watcher 时这里只缓存最新数量，避免回调里改 watcher。 */
    @Override
    public void onStackChange(AEKey key, long amount) {
        detectionService.onStackChange(key, amount);
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (ownerTeamId != null) {
            tag.putUUID("TeamId", ownerTeamId);
        }
        if (ownerTeamNameCache != null && !ownerTeamNameCache.isBlank()) {
            tag.putString("TeamNameCache", ownerTeamNameCache);
        }
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        if (tag.hasUUID("TeamId")) {
            ownerTeamId = tag.getUUID("TeamId");
        } else {
            ownerTeamId = null;
        }
        if (tag.contains("TeamNameCache")) {
            String value = tag.getString("TeamNameCache").trim();
            ownerTeamNameCache = value.isEmpty() ? null : value;
        } else {
            ownerTeamNameCache = null;
        }
        shortNameWarnedPlayers.clear();
        detectionService.markCacheDirty();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    public void setOwnerTeam(Player player) {
        try {
            TeamManagerImpl.INSTANCE.getTeamForPlayer((ServerPlayer) player)
                    .ifPresent(team -> assignOwnerTeam(team.getId()));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to resolve owner team for detector at {}", getBlockPos(), exception);
        }
    }

    public void setOwnerTeamId(UUID teamId) {
        assignOwnerTeam(teamId);
    }

    public boolean isNetworkConflict() {
        return networkConflict;
    }

    public void tick() {
        if (this.isRemoved() || this.level == null) {
            return;
        }
        rememberServerContext();
        if (pendingOwnerTeamRetryTicks > 0 && --pendingOwnerTeamRetryTicks == 0
                && pendingOwnerTeamId != null) {
            attemptPendingOwnerMigration();
        }
        if (teamValidationRetryTicks > 0 && --teamValidationRetryTicks == 0) {
            delayedTeamRevalidation = false;
            attemptOwnerTeamReconciliation();
            requestConflictAndBlockStateRefresh();
        }
        try {
            stateRefreshQueue.runPending(this::refreshConflictAndBlockState);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh detector block state at {}; retrying next tick", getBlockPos(), exception);
        }
        tickCount = (tickCount + 1) % Config.DETECTOR_TICK_RATE;
        try {
            detectionService.tick(this, level.getGameTime(), tickCount == 0);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to process detector work at {}; pending work will be retried", getBlockPos(), exception);
        }
    }

    /** 仅使可执行任务视图失效。 */
    public void markActiveCacheDirty() {
        detectionService.markActiveCacheDirty();
    }

    public void requestReconnect() {
        detectionService.requestReconnect();
    }

    public TeamOwnershipValidator.Status getOwnerTeamStatus() {
        return TeamOwnershipValidator.getStatus(ownerTeamId);
    }

    void onOwnerTeamStatusChanged() {
        TeamOwnershipValidator.Status status = getOwnerTeamStatus();
        if (status == TeamOwnershipValidator.Status.NONE
                || status == TeamOwnershipValidator.Status.INVALID) {
            detectionService.discardPendingProgress(this);
        } else {
            detectionService.markActiveCacheDirty();
        }
        delayedTeamRevalidation = true;
        teamValidationRetryTicks = 20;
        requestConflictAndBlockStateRefresh();
    }

    void onQuestFileReloaded() {
        detectionService.persistPendingProgress(this, ownerTeamId);
        detectionService.markCacheDirty();
        requestConflictAndBlockStateRefresh();
    }

    void reassignOwnerTeam(UUID newTeamId) {
        assignOwnerTeam(newTeamId);
    }

    private void assignOwnerTeam(UUID newTeamId) {
        UUID previousTeamId = ownerTeamId;
        if (newTeamId == null || Objects.equals(previousTeamId, newTeamId)) {
            return;
        }
        if (previousTeamId != null && !detectionService.persistPendingProgress(this, previousTeamId)) {
            pendingOwnerTeamId = newTeamId;
            pendingOwnerTeamRetryTicks = 20;
            persistPendingOwnerMigration(newTeamId, "team change");
            return;
        }
        if (!movePendingPersonalTeamRecovery(previousTeamId, newTeamId)) {
            pendingOwnerTeamId = newTeamId;
            pendingOwnerTeamRetryTicks = 20;
            persistPendingOwnerMigration(newTeamId, "team change");
            return;
        }
        String resolvedTeamName = TeamDisplayNameResolver.resolveRawTeamName(newTeamId, null);
        ownerTeamId = newTeamId;
        ownerTeamNameCache = resolvedTeamName;
        pendingOwnerTeamId = null;
        pendingOwnerTeamRetryTicks = 0;
        DetectorTeamMigrationStore.remove(this);
        shortNameWarnedPlayers.clear();
        detectionService.markCacheDirty();
        DetectorEntityList.notifyOwnerTeamIdChanged(this, previousTeamId);
        requestConflictAndBlockStateRefresh();
        setChanged();
    }

    private void reconcileOwnerTeam() {
        UUID effectiveTeamId = TeamOwnershipValidator.resolveEffectiveTeamId(ownerTeamId);
        if (effectiveTeamId != null && !Objects.equals(effectiveTeamId, ownerTeamId)) {
            assignOwnerTeam(effectiveTeamId);
        }
    }

    private boolean movePendingPersonalTeamRecovery(UUID previousTeamId, UUID newTeamId) {
        TeamOwnershipValidator.PersonalTeamStatus personalStatus =
                TeamOwnershipValidator.getPersonalTeamStatus(previousTeamId);
        if (personalStatus == TeamOwnershipValidator.PersonalTeamStatus.OTHER) {
            return true;
        }
        if (personalStatus == TeamOwnershipValidator.PersonalTeamStatus.TEMPORARILY_UNAVAILABLE) {
            return false;
        }
        MinecraftServer server = resolveServerContext();
        return DetectorProgressRecoveryStore.moveTeam(server, previousTeamId, newTeamId);
    }

    private void persistPendingOwnerMigration(UUID targetTeamId, String reason) {
        if (DetectorTeamMigrationStore.retain(this, targetTeamId)) {
            return;
        }
        LOGGER.warn("Could not persist pending detector team migration at {} ({})", getBlockPos(), reason);
    }

    @Override
    public void onReady() {
        rememberServerContext();
        chunkUnloadPendingRemoval = false;
        stateRefreshQueue.activate();
        restorePendingOwnerMigration();
        if (pendingOwnerTeamId == null) {
            attemptOwnerTeamReconciliation();
        }
        detectionService.onLoaded(this);
        super.onReady();
        lifecycleLoaded = true;
        requestReconnect();
        IGrid grid = getMainNode().getGrid();
        DetectorEntityList.register(this, grid);
        requestConflictAndBlockStateRefresh();
        requestGridPeersRefresh(grid);
    }

    @Override
    public void onChunkUnloaded() {
        chunkUnloadPendingRemoval = true;
        unloadDetector();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        boolean preservePendingMigration = chunkUnloadPendingRemoval;
        chunkUnloadPendingRemoval = false;
        unloadDetector();
        if (!preservePendingMigration) {
            DetectorTeamMigrationStore.remove(this);
        }
        super.setRemoved();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        if (level == null) {
            return;
        }
        requestConflictAndBlockStateRefresh();
        IGrid grid = getMainNode().getGrid();
        IGrid previousGrid = DetectorEntityList.updateGrid(this, grid);
        requestGridPeersRefresh(previousGrid);
        if (grid != previousGrid) {
            requestGridPeersRefresh(grid);
        }
    }

    private void requestConflictAndBlockStateRefresh() {
        stateRefreshQueue.request();
    }

    /** 刷新网络冲突状态，并同步更新 POWERED 方块状态。 */
    private void refreshConflictAndBlockState() {
        if (level == null || isRemoved()) {
            return;
        }
        boolean nodeActive = getMainNode().isActive();
        boolean conflict = false;
        if (nodeActive) {
            IGrid grid = getMainNode().getGrid();
            IGrid previousGrid = DetectorEntityList.updateGrid(this, grid);
            if (previousGrid != grid) {
                requestGridPeersRefresh(previousGrid);
            }
            if (grid != null) {
                List<DetectorBlockEntity> peers = DetectorEntityList.copyForGrid(grid);
                conflict = peers.size() > 1;
            }
        }
        boolean conflictChanged = conflict != this.networkConflict;
        this.networkConflict = conflict;

        TeamOwnershipValidator.Status teamStatus = getOwnerTeamStatus();
        if (teamStatus == TeamOwnershipValidator.Status.TEMPORARILY_UNAVAILABLE) {
            teamValidationRetryTicks = 20;
        } else if (!delayedTeamRevalidation) {
            teamValidationRetryTicks = 0;
        }
        boolean shouldPower = nodeActive && !conflict && teamStatus == TeamOwnershipValidator.Status.USABLE;
        BlockState current = getBlockState();
        if (current.hasProperty(DetectorBlock.POWERED)
                && current.getValue(DetectorBlock.POWERED) != shouldPower) {
            level.setBlock(getBlockPos(), current.setValue(DetectorBlock.POWERED, shouldPower), 3);
        }

        if (shouldPower) {
            detectionService.requestFullScan();
        }
        if (conflictChanged) {
            setChanged();
        }
    }

    private void unloadDetector() {
        if (!lifecycleLoaded) {
            return;
        }
        lifecycleLoaded = false;
        teamValidationRetryTicks = 0;
        delayedTeamRevalidation = false;
        if (pendingOwnerTeamId != null) {
            persistPendingOwnerMigration(pendingOwnerTeamId, "unload");
        }
        pendingOwnerTeamId = null;
        pendingOwnerTeamRetryTicks = 0;
        stateRefreshQueue.deactivate();
        long gameTime = level == null ? 0L : level.getGameTime();
        detectionService.onUnloaded(this, gameTime);
        shortNameWarnedPlayers.clear();
        IGrid previousGrid = DetectorEntityList.unregister(this);
        requestGridPeersRefresh(previousGrid);
    }

    private void restorePendingOwnerMigration() {
        try {
            UUID persistedTarget = DetectorTeamMigrationStore.get(this);
            if (persistedTarget == null) {
                return;
            }
            pendingOwnerTeamId = persistedTarget;
            pendingOwnerTeamRetryTicks = 0;
            attemptPendingOwnerMigration();
        } catch (RuntimeException exception) {
            pendingOwnerTeamRetryTicks = 20;
            LOGGER.warn("Failed to restore detector team migration at {}; retrying", getBlockPos(), exception);
            requestConflictAndBlockStateRefresh();
        }
    }

    private void attemptPendingOwnerMigration() {
        try {
            retryPendingOwnerMigration();
        } catch (RuntimeException exception) {
            pendingOwnerTeamRetryTicks = 20;
            LOGGER.warn("Failed to retry detector team migration at {}; retrying", getBlockPos(), exception);
            requestConflictAndBlockStateRefresh();
        }
    }

    private void attemptOwnerTeamReconciliation() {
        try {
            reconcileOwnerTeam();
        } catch (RuntimeException exception) {
            delayedTeamRevalidation = true;
            teamValidationRetryTicks = 20;
            LOGGER.warn("Failed to reconcile detector owner team at {}; retrying", getBlockPos(), exception);
        }
    }

    private void retryPendingOwnerMigration() {
        UUID targetTeamId = pendingOwnerTeamId;
        if (targetTeamId == null) {
            return;
        }
        TeamOwnershipValidator.Status status = TeamOwnershipValidator.getStatus(targetTeamId);
        if (status == TeamOwnershipValidator.Status.TEMPORARILY_UNAVAILABLE) {
            pendingOwnerTeamRetryTicks = 20;
            return;
        }
        if (status == TeamOwnershipValidator.Status.NONE
                || status == TeamOwnershipValidator.Status.INVALID) {
            pendingOwnerTeamId = null;
            pendingOwnerTeamRetryTicks = 0;
            DetectorTeamMigrationStore.remove(this);
            requestConflictAndBlockStateRefresh();
            return;
        }
        if (Objects.equals(ownerTeamId, targetTeamId)) {
            pendingOwnerTeamId = null;
            pendingOwnerTeamRetryTicks = 0;
            DetectorTeamMigrationStore.remove(this);
            return;
        }
        assignOwnerTeam(targetTeamId);
    }

    private void rememberServerContext() {
        if (level != null) {
            dimensionContext = level.dimension().location().toString();
            if (level instanceof ServerLevel serverLevel) {
                serverContext = serverLevel.getServer();
            }
        }
    }

    private MinecraftServer resolveServerContext() {
        rememberServerContext();
        return serverContext;
    }

    MinecraftServer getServerContext() {
        return resolveServerContext();
    }

    String getDimensionContext() {
        rememberServerContext();
        return dimensionContext;
    }

    private void requestGridPeersRefresh(IGrid grid) {
        List<DetectorBlockEntity> peers = DetectorEntityList.copyForGrid(grid);
        for (DetectorBlockEntity peer : peers) {
            if (peer != this && !peer.isRemoved()) {
                peer.requestConflictAndBlockStateRefresh();
            }
        }
    }
}
