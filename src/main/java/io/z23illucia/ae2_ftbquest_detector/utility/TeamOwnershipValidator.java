package io.z23illucia.ae2_ftbquest_detector.utility;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 统一判断检测器绑定的 FTB Teams 队伍是否仍可使用。 */
public final class TeamOwnershipValidator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TRANSIENT_LOG_INTERVAL_MS = 30_000L;
    private static final AtomicLong LAST_TRANSIENT_LOG_MS = new AtomicLong(Long.MIN_VALUE);

    public enum Status {
        NONE,
        USABLE,
        INVALID,
        TEMPORARILY_UNAVAILABLE
    }

    public enum PersonalTeamStatus {
        PERSONAL,
        OTHER,
        TEMPORARILY_UNAVAILABLE
    }

    private TeamOwnershipValidator() {
    }

    public static Status getStatus(UUID teamId) {
        return resolve(teamId).status();
    }

    private static Team resolveExistingTeam(UUID teamId) {
        if (teamId == null) {
            return null;
        }

        TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
        if (manager == null) {
            return null;
        }
        Team team = manager.getTeamByID(teamId).orElse(null);
        if (team == null) {
            team = manager.getTeamMap().get(teamId);
        }
        return team;
    }

    public static Team resolveUsableTeam(UUID teamId) {
        Resolution resolution = resolve(teamId);
        return resolution.status() == Status.USABLE ? resolution.team() : null;
    }

    /** 把持久化的个人队伍 ID 解析成玩家当前真正生效的队伍。 */
    public static UUID resolveEffectiveTeamId(UUID persistedTeamId) {
        if (persistedTeamId == null) {
            return null;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            if (manager == null) {
                return persistedTeamId;
            }
            Team personalTeam = manager.getPersonalTeamForPlayerID(persistedTeamId);
            if (personalTeam == null || !persistedTeamId.equals(personalTeam.getId())) {
                return persistedTeamId;
            }
            return manager.getTeamForPlayerID(persistedTeamId)
                    .map(Team::getId)
                    .orElse(persistedTeamId);
        } catch (RuntimeException exception) {
            logTransientFailure(persistedTeamId, exception);
            return persistedTeamId;
        }
    }

    public static PersonalTeamStatus getPersonalTeamStatus(UUID teamId) {
        if (teamId == null) {
            return PersonalTeamStatus.OTHER;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            if (manager == null) {
                return PersonalTeamStatus.TEMPORARILY_UNAVAILABLE;
            }
            Team personalTeam = manager.getPersonalTeamForPlayerID(teamId);
            return personalTeam != null && teamId.equals(personalTeam.getId())
                    ? PersonalTeamStatus.PERSONAL
                    : PersonalTeamStatus.OTHER;
        } catch (RuntimeException exception) {
            logTransientFailure(teamId, exception);
            return PersonalTeamStatus.TEMPORARILY_UNAVAILABLE;
        }
    }

    private static Resolution resolve(UUID teamId) {
        if (teamId == null) {
            return new Resolution(Status.NONE, null);
        }

        try {
            Team team = resolveExistingTeam(teamId);
            if (team == null) {
                return new Resolution(Status.INVALID, null);
            }
            if (team.isPlayerTeam()) {
                if (!team.isValid()) {
                    return new Resolution(Status.INVALID, team);
                }
                Team effectiveTeam = TeamManagerImpl.INSTANCE.getTeamForPlayerID(team.getId()).orElse(null);
                boolean isCurrentPlayerTeam = effectiveTeam != null
                        && team.getId().equals(effectiveTeam.getId())
                        && team.getRankForPlayer(team.getId()).isOwner();
                return new Resolution(isCurrentPlayerTeam ? Status.USABLE : Status.INVALID, team);
            }
            if (team.getMembers().isEmpty()) {
                return new Resolution(Status.INVALID, team);
            }
            if (!team.isValid()) {
                return new Resolution(Status.INVALID, team);
            }
            return new Resolution(Status.USABLE, team);
        } catch (RuntimeException exception) {
            logTransientFailure(teamId, exception);
            return new Resolution(Status.TEMPORARILY_UNAVAILABLE, null);
        }
    }

    private static void logTransientFailure(UUID teamId, RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = LAST_TRANSIENT_LOG_MS.get();
        if ((previous == Long.MIN_VALUE || now - previous >= TRANSIENT_LOG_INTERVAL_MS)
                && LAST_TRANSIENT_LOG_MS.compareAndSet(previous, now)) {
            LOGGER.warn("Temporarily unable to validate detector owner team {}; retrying", teamId, exception);
        }
    }

    private static final class Resolution {
        private final Status status;
        private final Team team;

        private Resolution(Status status, Team team) {
            this.status = status;
            this.team = team;
        }

        private Status status() {
            return status;
        }

        private Team team() {
            return team;
        }
    }
}
