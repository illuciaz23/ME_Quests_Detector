package io.z23illucia.ae2_ftbquest_detector.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import io.z23illucia.ae2_ftbquest_detector.utility.QuestTaskEligibility;
import io.z23illucia.ae2_ftbquest_detector.utility.SubmitHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Task.class)
public class TaskMixin {

    @Inject(
            method = "submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void injectSubmitFluidTask(TeamData teamData, ServerPlayer player, ItemStack craftedItem, CallbackInfo ci) {
        Task thisTask = (Task) (Object) this;
        if (thisTask instanceof FluidTask self
                && QuestTaskEligibility.canSubmit(self, teamData)
                && self.consumesResources()
        ) {
            SubmitHelper.submitTask(teamData, player, self, craftedItem);
        }
    }

    @Inject(
            method = "submitItemsOnInventoryChange",
            at = @At("HEAD"),
            remap = false,
            cancellable = true)
    private void check(CallbackInfoReturnable<Boolean> cir) {
        Task thisTask = (Task) (Object) this;
        if (thisTask instanceof FluidTask self) {
            cir.setReturnValue(!self.consumesResources());
        }
    }
}
