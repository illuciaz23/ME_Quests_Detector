package io.z23illucia.ae2_ftbquest_detector.utility;

import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import io.z23illucia.ae2_ftbquest_detector.TeamNameDisplayMode;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TeamDisplayNameResolver {
    private TeamDisplayNameResolver() {
    }

    public static String resolveExistingTeamName(UUID ownerTeamId, String cachedTeamName, TeamNameDisplayMode mode) {
        if (ownerTeamId == null) {
            return null;
        }
        TeamNameDisplayMode useMode = mode != null ? mode : TeamNameDisplayMode.NAME_AND_SHORT_ID;
        String teamName = resolveRawTeamName(ownerTeamId, cachedTeamName);
        return formatDisplayName(teamName, ownerTeamId, useMode);
    }

    /** 只对服务端已经解析好的名字做格式化，不读取客户端队伍缓存。 */
    public static String formatDisplayName(String teamName, UUID teamId, TeamNameDisplayMode mode) {
        TeamNameDisplayMode useMode = mode != null ? mode : TeamNameDisplayMode.NAME_AND_SHORT_ID;
        String normalizedName = normalize(teamName);
        String shortId = toShortTeamId(teamId);

        switch (useMode) {
            case NAME_ONLY:
                return firstNonBlank(normalizedName, shortId);
            case SHORT_ID_ONLY:
                return firstNonBlank(shortId, normalizedName);
            case NAME_AND_SHORT_ID:
            default:
                return formatNameAndShortId(normalizedName, shortId);
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private static String formatNameAndShortId(String teamName, String shortId) {
        if (teamName == null) {
            return shortId;
        }
        if (shortId == null) {
            return teamName;
        }
        if (teamName.equals(shortId)) {
            return shortId;
        }
        return teamName + "#" + shortId;
    }

    public static String resolveRawTeamName(UUID ownerTeamId, String cachedTeamName) {
        if (ownerTeamId == null) {
            return null;
        }

        TeamManagerImpl manager = TeamManagerImpl.INSTANCE;
        if (manager == null) {
            return normalize(cachedTeamName);
        }

        Team team = TeamOwnershipValidator.resolveUsableTeam(ownerTeamId);
        if (team == null) {
            return null;
        }

        String bestName = resolveBestName(manager, team, ownerTeamId);
        if (bestName != null) {
            return bestName;
        }

        return normalize(cachedTeamName);
    }

    public static boolean isTeamNameTooShort(UUID ownerTeamId, String cachedTeamName, int minLength) {
        String teamName = resolveRawTeamName(ownerTeamId, cachedTeamName);
        return teamName != null && teamName.length() < minLength;
    }

    private static String resolveBestName(TeamManagerImpl manager, Team team, UUID ownerTeamId) {
        if (team != null && team.isPlayerTeam()) {
            String playerName = normalize(manager.getPlayerName(team.getId()).getString());
            if (playerName != null) {
                return playerName;
            }
        }

        String value = readName(team);
        if (value != null) {
            return value;
        }

        return findNameKeyByTeamId(manager, ownerTeamId);
    }

    private static String readName(Team team) {
        if (team == null) {
            return null;
        }
        String propertyName = normalize(team.getProperty(TeamProperties.DISPLAY_NAME));
        if (propertyName != null) {
            return propertyName;
        }

        String plainName = normalize(team.getName().getString());
        if (plainName != null) {
            return plainName;
        }

        return normalize(team.getColoredName().getString());
    }

    private static String findNameKeyByTeamId(TeamManagerImpl manager, UUID ownerTeamId) {
        for (Map.Entry<String, Team> entry : manager.getTeamNameMap().entrySet()) {
            Team team = entry.getValue();
            if (team == null) {
                continue;
            }
            UUID id = team.getId();
            if (id != null && id.equals(ownerTeamId)) {
                String keyName = normalize(entry.getKey());
                if (keyName != null) {
                    return keyName;
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    private static String toShortTeamId(UUID id) {
        if (id == null) {
            return null;
        }
        String compact = id.toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (compact.length() <= 8) {
            return compact;
        }
        return compact.substring(0, 8);
    }
}
