package io.z23illucia.ae2_ftbquest_detector.utility;

import java.util.Objects;

public final class SafeEnumValue {
    private SafeEnumValue() {
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
