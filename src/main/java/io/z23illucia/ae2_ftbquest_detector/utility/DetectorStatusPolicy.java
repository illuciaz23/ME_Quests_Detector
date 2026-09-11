package io.z23illucia.ae2_ftbquest_detector.utility;

/** 依据队伍、网络与方块状态推导检测器对外展示的状态。 */
public final class DetectorStatusPolicy {
    private DetectorStatusPolicy() {
    }

    public enum State {
        NO_OWNER_TEAM,
        INVALID_OWNER_TEAM,
        TEMPORARILY_UNAVAILABLE,
        NETWORK_CONFLICT,
        OFFLINE,
        ACTIVE
    }

    public static State resolve(
            TeamOwnershipValidator.Status teamStatus,
            boolean networkConflict,
            boolean powered
    ) {
        if (teamStatus == null) {
            return State.INVALID_OWNER_TEAM;
        }
        switch (teamStatus) {
            case NONE:
                return State.NO_OWNER_TEAM;
            case INVALID:
                return State.INVALID_OWNER_TEAM;
            case TEMPORARILY_UNAVAILABLE:
                return State.TEMPORARILY_UNAVAILABLE;
            case USABLE:
            default:
                if (networkConflict) {
                    return State.NETWORK_CONFLICT;
                }
                return powered ? State.ACTIVE : State.OFFLINE;
        }
    }
}
