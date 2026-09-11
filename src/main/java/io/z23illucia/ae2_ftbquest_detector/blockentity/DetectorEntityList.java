package io.z23illucia.ae2_ftbquest_detector.blockentity;

import appeng.api.networking.IGrid;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪已加载检测器，并按队伍与 AE2 网络建立索引。 */
public final class DetectorEntityList {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<DetectorBlockEntity> TRACKED_ENTITIES = newWeakSet();
    private static final Map<UUID, Set<DetectorBlockEntity>> BY_TEAM = new ConcurrentHashMap<>();
    private static final GridMembershipIndex<DetectorBlockEntity, IGrid> GRID_INDEX = new GridMembershipIndex<>();

    private DetectorEntityList() {
    }

    public static void register(DetectorBlockEntity be, IGrid grid) {
        synchronized (TRACKED_ENTITIES) {
            TRACKED_ENTITIES.add(be);
            addToTeamIndex(be);
            GRID_INDEX.update(be, grid);
            pruneEmptyTeamIndexes();
        }
    }

    public static IGrid unregister(DetectorBlockEntity be) {
        synchronized (TRACKED_ENTITIES) {
            removeFromTeamIndex(be, be.ownerTeamId);
            TRACKED_ENTITIES.remove(be);
            IGrid previousGrid = GRID_INDEX.remove(be);
            pruneEmptyTeamIndexes();
            return previousGrid;
        }
    }

    public static IGrid updateGrid(DetectorBlockEntity be, IGrid grid) {
        synchronized (TRACKED_ENTITIES) {
            if (TRACKED_ENTITIES.contains(be)) {
                return GRID_INDEX.update(be, grid);
            }
            return null;
        }
    }

    /** ownerTeamId 改变后同步更新队伍索引。 */
    public static void notifyOwnerTeamIdChanged(DetectorBlockEntity be, UUID previousTeamId) {
        synchronized (TRACKED_ENTITIES) {
            if (!TRACKED_ENTITIES.contains(be)) {
                return;
            }
            removeFromTeamIndex(be, previousTeamId);
            addToTeamIndex(be);
        }
    }

    private static void addToTeamIndex(DetectorBlockEntity be) {
        UUID id = be.ownerTeamId;
        if (id == null) {
            return;
        }
        BY_TEAM.computeIfAbsent(id, ignored -> newWeakSet()).add(be);
    }

    private static void removeFromTeamIndex(DetectorBlockEntity be, UUID teamId) {
        if (teamId == null) {
            return;
        }
        Set<DetectorBlockEntity> set = BY_TEAM.get(teamId);
        if (set != null) {
            set.remove(be);
            if (set.isEmpty()) {
                BY_TEAM.remove(teamId);
            }
        }
    }

    /** 返回指定队伍下检测器的可遍历快照。 */
    public static List<DetectorBlockEntity> copyForTeam(UUID teamId) {
        if (teamId == null) {
            return List.of();
        }
        synchronized (TRACKED_ENTITIES) {
            Set<DetectorBlockEntity> set = BY_TEAM.get(teamId);
            if (set == null || set.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(set);
        }
    }

    public static void markActiveCacheDirtyForTeam(UUID teamId) {
        List<DetectorBlockEntity> list = copyForTeam(teamId);
        for (DetectorBlockEntity be : list) {
            if (be != null && !be.isRemoved()) {
                try {
                    be.markActiveCacheDirty();
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to invalidate detector task cache at {}", be.getBlockPos(), exception);
                }
            }
        }
    }

    /** 返回同一 AE2 网络中的检测器快照。 */
    public static List<DetectorBlockEntity> copyForGrid(IGrid grid) {
        synchronized (TRACKED_ENTITIES) {
            return GRID_INDEX.copyForGrid(grid);
        }
    }

    /** 队伍成员关系变化后刷新检测器的有效状态和缓存。 */
    public static void refreshTeamStatus(UUID teamId) {
        List<DetectorBlockEntity> list = copyForTeam(teamId);
        for (DetectorBlockEntity be : list) {
            if (be != null && !be.isRemoved()) {
                try {
                    be.onOwnerTeamStatusChanged();
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to refresh detector team status at {}", be.getBlockPos(), exception);
                }
            }
        }
    }

    /** 将旧队伍的已加载检测器整体迁移到其队长当前的有效队伍。 */
    public static void reassignTeamDetectors(UUID previousTeamId, UUID newTeamId) {
        if (previousTeamId == null || newTeamId == null || previousTeamId.equals(newTeamId)) {
            return;
        }
        List<DetectorBlockEntity> list = copyForTeam(previousTeamId);
        for (DetectorBlockEntity be : list) {
            if (be != null && !be.isRemoved()) {
                try {
                    be.reassignOwnerTeam(newTeamId);
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to reassign detector team at {} from {} to {}",
                            be.getBlockPos(), previousTeamId, newTeamId, exception);
                }
            }
        }
    }

    /** 队伍管理器重新加载后，重新校验所有已加载检测器。 */
    public static void refreshAllTeamStatuses() {
        List<DetectorBlockEntity> list;
        synchronized (TRACKED_ENTITIES) {
            list = new ArrayList<>(TRACKED_ENTITIES);
        }
        for (DetectorBlockEntity be : list) {
            if (be != null && !be.isRemoved()) {
                try {
                    be.onOwnerTeamStatusChanged();
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to refresh detector team status at {}", be.getBlockPos(), exception);
                }
            }
        }
    }

    /** FTB Quests 重新加载任务对象后，使所有任务缓存失效。 */
    public static void markAllTaskCachesDirty() {
        List<DetectorBlockEntity> list;
        synchronized (TRACKED_ENTITIES) {
            list = new ArrayList<>(TRACKED_ENTITIES);
        }
        for (DetectorBlockEntity be : list) {
            if (be != null && !be.isRemoved()) {
                try {
                    be.onQuestFileReloaded();
                } catch (RuntimeException exception) {
                    LOGGER.error("Failed to invalidate detector quest cache at {}", be.getBlockPos(), exception);
                }
            }
        }
    }

    private static Set<DetectorBlockEntity> newWeakSet() {
        return Collections.newSetFromMap(new WeakHashMap<>());
    }

    private static void pruneEmptyTeamIndexes() {
        BY_TEAM.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
}
