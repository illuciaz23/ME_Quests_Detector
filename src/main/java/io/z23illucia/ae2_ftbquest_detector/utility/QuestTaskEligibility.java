package io.z23illucia.ae2_ftbquest_detector.utility;

import dev.ftb.mods.ftbquests.item.MissingItem;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;

import java.util.List;

/** 统一收口检测器写回进度前的任务资格判定。 */
public final class QuestTaskEligibility {
    private QuestTaskEligibility() {
    }

    public static boolean canSubmit(Task task, TeamData teamData) {
        if (task == null || teamData == null || teamData.isLocked()
                || teamData.isCompleted(task)
                || !teamData.canStartTasks(task.getQuest())
                || !isNextSequentialTask(task, teamData)) {
            return false;
        }
        if (task instanceof ItemTask itemTask) {
            return !itemTask.isTaskScreenOnly()
                    && !itemTask.getItemStack().isEmpty()
                    && !(itemTask.getItemStack().getItem() instanceof MissingItem);
        }
        return true;
    }

    public static boolean canAutoSubmit(Task task, TeamData teamData) {
        if (!canSubmit(task, teamData)) {
            return false;
        }
        return !(task instanceof ItemTask itemTask && itemTask.isOnlyFromCrafting());
    }

    /** 等价于 1.20.1 内部的 {@code Task#checkTaskSequence(TeamData)}。 */
    private static boolean isNextSequentialTask(Task task, TeamData teamData) {
        Quest quest = task.getQuest();
        if (!quest.getRequireSequentialTasks()) {
            return true;
        }
        List<Task> tasks = quest.getTasksAsList();
        int index = tasks.indexOf(task);
        return index >= 0 && (index == 0 || teamData.isCompleted(tasks.get(index - 1)));
    }
}
