package io.z23illucia.ae2_ftbquest_detector.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import io.z23illucia.ae2_ftbquest_detector.blockentity.DetectorEntityList;
import io.z23illucia.ae2_ftbquest_detector.utility.DetectorProgressSyncContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = TeamData.class, remap = false)
public class TeamDataMixin {

    @Inject(
            method = "markDirty",
            at = @At("TAIL")
    )
    public void markDirtyMixin(CallbackInfo ci) {
        if (DetectorProgressSyncContext.isTeamDirtyNotificationSuppressed()) {
            return;
        }
        TeamData self = (TeamData) (Object) this;
        UUID teamId = self.getTeamId();
        if (teamId == null) {
            return;
        }
        DetectorEntityList.markActiveCacheDirtyForTeam(teamId);
    }
}
