package io.z23illucia.ae2_ftbquest_detector.blockentity;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import io.z23illucia.ae2_ftbquest_detector.utility.ItemTaskMatcher;
import io.z23illucia.ae2_ftbquest_detector.utility.QuestTaskEligibility;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 按物品类型索引非精确匹配的物品任务，并汇总同一物品的所有 AE 变体。 */
final class DetectorItemTaskIndex {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<Item, List<ItemTask>> cachedTasksByItem = new ConcurrentHashMap<>();
    private final List<ItemTask> cachedFilterTasks = new CopyOnWriteArrayList<>();
    private final Map<Item, List<ItemTask>> activeTasksByItem = new ConcurrentHashMap<>();
    private final List<ItemTask> activeFilterTasks = new CopyOnWriteArrayList<>();

    void clear() {
        cachedTasksByItem.clear();
        cachedFilterTasks.clear();
        activeTasksByItem.clear();
        activeFilterTasks.clear();
    }

    /** 精确 AEKey 任务返回 false，交给检测器的精确索引处理。 */
    boolean addIfFlexible(ItemTask task) {
        if (ItemTaskMatcher.usesExactKey(task)) {
            return false;
        }
        if (ItemTaskMatcher.isFilter(task)) {
            cachedFilterTasks.add(task);
        } else {
            cachedTasksByItem.computeIfAbsent(
                    ItemTaskMatcher.itemType(task), ignored -> new CopyOnWriteArrayList<>()).add(task);
        }
        return true;
    }

    void updateActive(TeamData data) {
        activeTasksByItem.clear();
        activeFilterTasks.clear();
        for (Map.Entry<Item, List<ItemTask>> entry : cachedTasksByItem.entrySet()) {
            List<ItemTask> active = activeTasks(entry.getValue(), data);
            if (active != null) {
                activeTasksByItem.put(entry.getKey(), active);
            }
        }
        List<ItemTask> activeFilters = activeTasks(cachedFilterTasks, data);
        if (activeFilters != null) {
            activeFilterTasks.addAll(activeFilters);
        }
    }

    boolean hasFlexibleTasks() {
        return !cachedTasksByItem.isEmpty() || !cachedFilterTasks.isEmpty();
    }

    boolean hasActiveFlexibleTasks() {
        return !activeTasksByItem.isEmpty() || !activeFilterTasks.isEmpty();
    }

    void collectMatchingAmounts(KeyCounter availableStacks, Map<ItemTask, Long> matchingAmounts) {
        if (availableStacks == null || !hasActiveFlexibleTasks()) {
            return;
        }
        for (AEKey key : availableStacks.keySet()) {
            if (!(key instanceof AEItemKey itemKey)) {
                continue;
            }
            long amount = availableStacks.get(key);
            if (amount <= 0L) {
                continue;
            }
            ItemStack candidate = itemKey.toStack();
            if (candidate.isEmpty()) {
                continue;
            }
            collectMatchingAmounts(matchingAmounts, activeTasksByItem.get(itemKey.getItem()), candidate, amount);
            collectMatchingAmounts(matchingAmounts, activeFilterTasks, candidate, amount);
        }
    }

    private void collectMatchingAmounts(Map<ItemTask, Long> matchingAmounts,
                                        List<ItemTask> tasks,
                                        ItemStack candidate,
                                        long amount) {
        if (tasks == null) {
            return;
        }
        for (ItemTask task : tasks) {
            try {
                if (!task.test(candidate)) {
                    continue;
                }
                matchingAmounts.compute(task, (ignored, previous) -> {
                    long current = previous == null ? 0L : previous;
                    long maximum = task.getMaxProgress();
                    return current >= maximum - amount ? maximum : current + amount;
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to match AE item against task {}; skipping this candidate",
                        task.id, exception);
            }
        }
    }

    private static List<ItemTask> activeTasks(List<ItemTask> tasks, TeamData data) {
        List<ItemTask> active = null;
        for (ItemTask task : tasks) {
            if (!QuestTaskEligibility.canAutoSubmit(task, data)) {
                continue;
            }
            if (active == null) {
                active = new ArrayList<>(1);
            }
            active.add(task);
        }
        return active == null ? null : List.copyOf(active);
    }
}
