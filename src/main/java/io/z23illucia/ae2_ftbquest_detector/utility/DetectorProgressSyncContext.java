package io.z23illucia.ae2_ftbquest_detector.utility;

/** 检测器批量写回部分进度时，临时抑制 TeamData 的逐次脏反馈。 */
public final class DetectorProgressSyncContext {
    private static final ThreadLocal<Integer> SUPPRESSED_TEAM_DIRTY_DEPTH = ThreadLocal.withInitial(() -> 0);

    private DetectorProgressSyncContext() {
    }

    public static Scope suppressTeamDirtyNotifications() {
        SUPPRESSED_TEAM_DIRTY_DEPTH.set(SUPPRESSED_TEAM_DIRTY_DEPTH.get() + 1);
        return new Scope();
    }

    public static boolean isTeamDirtyNotificationSuppressed() {
        return SUPPRESSED_TEAM_DIRTY_DEPTH.get() > 0;
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            int depth = SUPPRESSED_TEAM_DIRTY_DEPTH.get() - 1;
            if (depth <= 0) {
                SUPPRESSED_TEAM_DIRTY_DEPTH.remove();
            } else {
                SUPPRESSED_TEAM_DIRTY_DEPTH.set(depth);
            }
        }
    }
}
