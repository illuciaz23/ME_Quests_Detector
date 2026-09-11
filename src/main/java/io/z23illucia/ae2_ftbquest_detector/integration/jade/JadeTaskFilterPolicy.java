package io.z23illucia.ae2_ftbquest_detector.integration.jade;

final class JadeTaskFilterPolicy {
    private JadeTaskFilterPolicy() {
    }

    static boolean include(boolean visible, boolean repeatable,
                           boolean ignoreHidden, boolean ignoreRepeatable) {
        return (!ignoreHidden || visible) && (!ignoreRepeatable || !repeatable);
    }
}
