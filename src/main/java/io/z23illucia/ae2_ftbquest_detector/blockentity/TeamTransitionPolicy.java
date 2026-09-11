package io.z23illucia.ae2_ftbquest_detector.blockentity;

import java.util.UUID;

final class TeamTransitionPolicy {
    private TeamTransitionPolicy() {
    }

    static boolean shouldFollowPlayer(
            UUID previousTeamId,
            boolean playerTeam,
            boolean partyTeam,
            boolean playerWasOwner,
            UUID playerId
    ) {
        if (previousTeamId == null || playerId == null) {
            return false;
        }
        if (playerTeam) {
            return playerId.equals(previousTeamId);
        }
        return partyTeam && playerWasOwner;
    }
}
