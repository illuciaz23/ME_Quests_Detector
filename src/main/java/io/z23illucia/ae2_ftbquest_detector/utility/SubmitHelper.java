package io.z23illucia.ae2_ftbquest_detector.utility;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorEntityList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

public final class SubmitHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SubmitHelper() {
    }

    public static void submitTask(TeamData teamData, ServerPlayer player, Task task, ItemStack craftedItem) {
        if (!teamData.getFile().isServerSide()) {
            return;
        }
        if (!QuestTaskEligibility.canSubmit(task, teamData)) {
            return;
        }
        // 合成事件已经由 FTB Quests 自己处理，检测器只负责玩家主动提交的那一次，避免重复提交。
        if (task instanceof ItemTask && craftedItem != null && !craftedItem.isEmpty()) {
            return;
        }
        if (TeamOwnershipValidator.getStatus(teamData.getTeamId())
                != TeamOwnershipValidator.Status.USABLE) {
            return;
        }

        for (var e : DetectorEntityList.copyForTeam(teamData.getTeamId())) {
            try {
                if (e == null || e.isRemoved() || e.isNetworkConflict()
                        || !e.getMainNode().isReady() || !e.getMainNode().isActive()) {
                    continue;
                }
                var grid = e.getMainNode().getGrid();
                if (grid == null) {
                    continue;
                }

                var storageService = grid.getStorageService();
                if (storageService == null) {
                    continue;
                }

                var inventory = storageService.getInventory();
                if (inventory == null) {
                    continue;
                }

                long amount = task.getMaxProgress() - teamData.getProgress(task);
                if (amount <= 0L) {
                    return;
                }
                IActionSource source = IActionSource.ofPlayer(player);
                if (task instanceof ItemTask itemTask) {
                    long extracted = extractMatchingItems(inventory, itemTask, amount, source);
                    if (extracted > 0L) {
                        teamData.addProgress(itemTask, extracted);
                    }
                    continue;
                }
                if (task instanceof FluidTask fluidTask) {
                    AEKey key = AEFluidKey.of(fluidTask.getFluid(), fluidTask.getFluidNBT());
                    if (key == null) {
                        continue;
                    }
                    long extracted = extractKey(inventory, key, amount, source);
                    if (extracted > 0L) {
                        teamData.addProgress(fluidTask, extracted);
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to submit task {} through detector at {}; another detector may retry it",
                        task.id, e.getBlockPos(), exception);
            }
        }
    }

    private static long extractMatchingItems(MEStorage inventory,
                                             ItemTask task,
                                             long requested,
                                             IActionSource source) {
        if (ItemTaskMatcher.usesExactKey(task)) {
            AEItemKey key = AEItemKey.of(task.getItemStack());
            return key == null ? 0L : extractKey(inventory, key, requested, source);
        }

        KeyCounter available = inventory.getAvailableStacks();
        long extractedTotal = 0L;
        for (AEKey candidate : available.keySet()) {
            if (!(candidate instanceof AEItemKey itemKey)) {
                continue;
            }
            try {
                if (!ItemTaskMatcher.matches(task, itemKey)) {
                    continue;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to match AE item against task {}; skipping this candidate",
                        task.id, exception);
                continue;
            }
            long availableAmount = Math.min(requested - extractedTotal, available.get(candidate));
            if (availableAmount <= 0L) {
                continue;
            }
            long extracted = extractKey(inventory, candidate, availableAmount, source);
            extractedTotal += extracted;
            if (extractedTotal >= requested) {
                return requested;
            }
        }
        return extractedTotal;
    }

    private static long extractKey(MEStorage inventory,
                                   AEKey key,
                                   long requested,
                                   IActionSource source) {
        return ResourceExtraction.execute(
                requested,
                amount -> inventory.extract(key, amount, Actionable.SIMULATE, source),
                amount -> inventory.extract(key, amount, Actionable.MODULATE, source)
        );
    }
}
