package io.z23illucia.ae2_ftbquest_detector.utility;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class BoundedExpiringCache<K, V> {
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final int maxEntries;

    public BoundedExpiringCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    public V get(K key, long now) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (now >= entry.expiresAt) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    public synchronized void put(K key, V value, long expiresAt, long now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        entries.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
        if (!entries.containsKey(key) && entries.size() >= maxEntries) {
            Map.Entry<K, Entry<V>> earliest = null;
            for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
                if (earliest == null || entry.getValue().expiresAt < earliest.getValue().expiresAt) {
                    earliest = entry;
                }
            }
            if (earliest != null) {
                entries.remove(earliest.getKey(), earliest.getValue());
            }
        }
        entries.put(key, new Entry<>(value, expiresAt));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private static final class Entry<V> {
        private final V value;
        private final long expiresAt;

        private Entry(V value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
