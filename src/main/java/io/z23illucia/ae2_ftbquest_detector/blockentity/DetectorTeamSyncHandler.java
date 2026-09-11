package io.z23illucia.ae2_ftbquest_detector.blockentity;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.events.ClearFileCacheEvent;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.event.PlayerChangedTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamManagerEvent;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.z23illucia.ae2_ftbquest_detector.utility.FtbRuntime;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;

/** 队伍成员关系变化后立即刷新绑定该队伍的检测器。 */
public final class DetectorTeamSyncHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private DetectorTeamSyncHandler() {
    }

    public static void register() {
        if (!FtbRuntime.isAvailable()) {
            LOGGER.warn("FTB Quests / FTB Teams are missing; detector team synchronization is disabled");
            return;
        }
        TeamEvent.PLAYER_CHANGED.register(DetectorTeamSyncHandler::onPlayerChanged);
        TeamEvent.PLAYER_JOINED_PARTY.register(DetectorTeamSyncHandler::onPlayerJoinedParty);
        TeamEvent.PLAYER_LEFT_PARTY.register(DetectorTeamSyncHandler::onPlayerLeftParty);
        TeamEvent.PLAYER_LOGGED_IN.register(DetectorTeamSyncHandler::onPlayerLoggedIn);
        TeamEvent.DELETED.register(DetectorTeamSyncHandler::onTeamEvent);
        TeamManagerEvent.LOADED.register(DetectorTeamSyncHandler::onTeamManagerLoaded);
        ClearFileCacheEvent.EVENT.register(ignored -> DetectorEntityList.markAllTaskCachesDirty());
    }

    private static void onPlayerChanged(PlayerChangedTeamEvent event) {
        try {
            Team currentTeam = event.getTeam();
            Team previousTeam = event.getPreviousTeam().orElse(null);
            if (shouldFollowPlayer(previousTeam, event.getPlayerId())) {
                DetectorEntityList.reassignTeamDetectors(previousTeam.getId(), currentTeam.getId());
                if (failedToMoveTeamRecovery(previousTeam, currentTeam)) {
                    LOGGER.warn("Could not migrate detector recovery progress from team {} to {} yet",
                            previousTeam.getId(), currentTeam.getId());
                }
            }
            refresh(previousTeam);
            refresh(currentTeam);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to process FTB Teams membership change for player {}", event.getPlayerId(), exception);
        }
    }

    private static void onPlayerJoinedParty(PlayerJoinedPartyTeamEvent event) {
        refresh(event.getPreviousTeam());
        refresh(event.getTeam());
    }

    private static void onPlayerLeftParty(PlayerLeftPartyTeamEvent event) {
        try {
            Team previousTeam = event.getTeam();
            if (event.getTeamDeleted() && shouldFollowPlayer(previousTeam, event.getPlayerId())) {
                Team playerTeam = event.getPlayerTeam();
                DetectorEntityList.reassignTeamDetectors(previousTeam.getId(), playerTeam.getId());
                if (failedToMoveTeamRecovery(previousTeam, playerTeam)) {
                    LOGGER.warn("Could not migrate detector recovery progress from deleted team {} to {} yet",
                            previousTeam.getId(), playerTeam.getId());
                }
            }
            refresh(event.getPlayerTeam());
            refresh(event.getTeam());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to process FTB Teams party leave for player {}", event.getPlayerId(), exception);
        }
    }

    private static void onPlayerLoggedIn(PlayerLoggedInAfterTeamEvent event) {
        Team currentTeam = event.getTeam();
        if (currentTeam != null) {
            DetectorEntityList.reassignTeamDetectors(event.getPlayer().getUUID(), currentTeam.getId());
        }
        refresh(currentTeam);
    }

    private static void onTeamEvent(TeamEvent event) {
        refresh(event.getTeam());
        discardDeletedTeamRecovery(event.getTeam());
        discardDeletedTeamMigration(event.getTeam());
    }

    private static void onTeamManagerLoaded(TeamManagerEvent event) {
        DetectorEntityList.refreshAllTeamStatuses();
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            MinecraftServer server = manager == null ? null : manager.getServer();
            if (server != null) {
                server.execute(() -> {
                    TeamManagerImpl currentManager = TeamManagerImpl.INSTANCE;
                    if (currentManager != null) {
                        Set<UUID> knownTeamIds = Set.copyOf(currentManager.getTeamMap().keySet());
                        DetectorProgressRecoveryStore.discardTeamsNotIn(server, knownTeamIds);
                    }
                });
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to schedule cleanup of detector recovery records after FTB Teams load", exception);
        }
    }

    private static void discardDeletedTeamRecovery(Team team) {
        if (team == null) {
            return;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            if (manager != null) {
                DetectorProgressRecoveryStore.discard(manager.getServer(), team.getId());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to discard detector recovery records for deleted team {}", team.getId(), exception);
        }
    }

    private static void discardDeletedTeamMigration(Team team) {
        if (team == null) {
            return;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            if (manager != null) {
                DetectorTeamMigrationStore.discardTarget(manager.getServer(), team.getId());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to discard pending detector migrations targeting deleted team {}",
                    team.getId(), exception);
        }
    }

    private static void refresh(Team team) {
        if (team != null) {
            refresh(team.getId());
        }
    }

    private static void refresh(UUID teamId) {
        DetectorEntityList.refreshTeamStatus(teamId);
    }

    private static boolean failedToMoveTeamRecovery(Team previousTeam, Team currentTeam) {
        if (previousTeam == null || currentTeam == null) {
            return false;
        }
        try {
            TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
            return manager == null || !DetectorProgressRecoveryStore.moveTeam(
                    manager.getServer(), previousTeam.getId(), currentTeam.getId());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to move detector recovery progress from team {} to {}",
                    previousTeam.getId(), currentTeam.getId(), exception);
            return true;
        }
    }

    static boolean shouldFollowPlayer(Team previousTeam, UUID playerId) {
        if (previousTeam == null || playerId == null) {
            return false;
        }
        return TeamTransitionPolicy.shouldFollowPlayer(
                previousTeam.getId(),
                previousTeam.isPlayerTeam(),
                previousTeam.isPartyTeam(),
                playerId.equals(previousTeam.getOwner()),
                playerId
        );
    }
}
