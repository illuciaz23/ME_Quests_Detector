package io.z23illucia.ae2_ftbquest_detector.utility;

import appeng.api.stacks.AEItemKey;
import dev.ftb.mods.ftbquests.integration.item_filtering.ItemMatchingSystem;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 1.20.1 的 FTB Quests 没有数据组件（matchComponents），任务匹配完全依赖
 * {@link ItemTask#test(ItemStack)} 谓词。这里统一收口匹配逻辑。
 */
public final class ItemTaskMatcher {
    private ItemTaskMatcher() {
    }

    /**
     * 1.20.1 不存在 ComponentMatchType，无法判断“精确 AEKey”是否等价于任务条件，
     * 因此所有物品任务都走谓词匹配（由 {@code Map<Item, List<ItemTask>>} 索引收敛开销）。
     */
    public static boolean usesExactKey(ItemTask task) {
        return false;
    }

    public static boolean isFilter(ItemTask task) {
        return isFilter(task.getItemStack());
    }

    public static Item itemType(ItemTask task) {
        return task.getItemStack().getItem();
    }

    public static boolean matches(ItemTask task, AEItemKey candidate) {
        if (candidate == null) {
            return false;
        }
        ItemStack target = task.getItemStack();
        // 空物品任务代表“任意物品”，绝不能拿来匹配整个 ME 网络。
        if (target.isEmpty()) {
            return false;
        }
        ItemStack stack = candidate.toStack();
        if (stack.isEmpty()) {
            return false;
        }
        return task.test(stack);
    }

    private static boolean isFilter(ItemStack stack) {
        return !stack.isEmpty() && ItemMatchingSystem.INSTANCE.isItemFilter(stack);
    }
}
