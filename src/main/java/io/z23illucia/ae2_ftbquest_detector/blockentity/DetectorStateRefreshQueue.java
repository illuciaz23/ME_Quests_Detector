package io.z23illucia.ae2_ftbquest_detector.blockentity;

final class DetectorStateRefreshQueue {
    private boolean active;
    private boolean pending;

    synchronized void activate() {
        active = true;
    }

    synchronized void request() {
        if (active) {
            pending = true;
        }
    }

    synchronized boolean consume() {
        if (!active || !pending) {
            return false;
        }
        pending = false;
        return true;
    }

    boolean runPending(Runnable refresh) {
        if (!consume()) {
            return false;
        }
        try {
            refresh.run();
            return true;
        } catch (RuntimeException exception) {
            request();
            throw exception;
        }
    }

    synchronized void deactivate() {
        active = false;
        pending = false;
    }
}
