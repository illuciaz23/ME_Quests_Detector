package io.z23illucia.ae2_ftbquest_detector.utility;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** 同一任务的多次进度更新只保留最大值，避免单 tick 写回爆炸。 */
public final class CoalescingLongQueue<K> {
    private final Map<K, Long> values = new IdentityHashMap<>();
    private final ArrayDeque<K> order = new ArrayDeque<>();

    public synchronized void offerMax(K key, long value) {
        Objects.requireNonNull(key, "key");
        Long previous = values.get(key);
        if (previous == null) {
            values.put(key, value);
            order.addLast(key);
        } else if (value > previous) {
            values.put(key, value);
        }
    }

    public synchronized int drain(int limit, LongHandler<K> handler) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Objects.requireNonNull(handler, "handler");

        int processed = 0;
        int attempts = Math.min(limit, order.size());
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            K key = order.getFirst();
            long value = values.get(key);
            order.removeFirst();
            try {
                handler.accept(key, value);
                values.remove(key);
                processed++;
            } catch (RuntimeException exception) {
                order.addLast(key);
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return processed;
    }

    public synchronized boolean isEmpty() {
        return order.isEmpty();
    }

    public synchronized int size() {
        return order.size();
    }

    public synchronized void clear() {
        order.clear();
        values.clear();
    }

    @FunctionalInterface
    public interface LongHandler<K> {
        void accept(K key, long value);
    }
}
