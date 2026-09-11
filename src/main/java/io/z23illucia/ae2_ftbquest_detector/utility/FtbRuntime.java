package io.z23illucia.ae2_ftbquest_detector.utility;

/**
 * 只解析一次 FTB Quests / FTB Teams 是否存在，避免在热路径上反复 Class.forName。
 */
public final class FtbRuntime {
    private static final boolean AVAILABLE = compute();

    private FtbRuntime() {
    }

    private static boolean compute() {
        try {
            Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
            Class.forName("dev.ftb.mods.ftbteams.data.TeamManagerImpl");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }
}
