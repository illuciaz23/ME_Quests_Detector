package io.z23illucia.ae2_ftbquest_detector.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import io.z23illucia.ae2_ftbquest_detector.utility.QuestTaskEligibility;
import io.z23illucia.ae2_ftbquest_detector.utility.SubmitHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemTask.class)
public class ItemTaskMixin {

    @Inject(
            method = "submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void injectSubmitItemTask(TeamData teamData, ServerPlayer player, ItemStack craftedItem, CallbackInfo ci) {
        ItemTask self = (ItemTask) (Object) this;
        if (self.consumesResources()
                && QuestTaskEligibility.canSubmit(self, teamData)
        ) {
            SubmitHelper.submitTask(teamData, player, self, craftedItem);
        }
    }
}
