package com.hyunchang.webapp.util;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class TtlCache<K, V> {
    public record Hit<V>(V value, boolean negative) {}

    private record Entry<V>(V value, long expiresAt, boolean negative) {}

    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    public TtlCache(Duration positiveTtl, Duration negativeTtl) {
        this.positiveTtl = Objects.requireNonNull(positiveTtl, "positiveTtl");
        this.negativeTtl = Objects.requireNonNull(negativeTtl, "negativeTtl");
        if (positiveTtl.isNegative() || positiveTtl.isZero()) {
            throw new IllegalArgumentException("positiveTtl must be positive");
        }
        if (negativeTtl.isNegative() || negativeTtl.isZero()) {
            throw new IllegalArgumentException("negativeTtl must be positive");
        }
    }

    public Hit<V> lookup(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            entries.remove(key, entry);
            return null;
        }
        return new Hit<>(entry.value(), entry.negative());
    }

    public void put(K key, V value) {
        entries.put(key, new Entry<>(value, expiresAt(positiveTtl), false));
    }

    public void putNegative(K key) {
        entries.put(key, new Entry<>(null, expiresAt(negativeTtl), true));
    }

    public void invalidate(K key) {
        entries.remove(key);
    }

    private long expiresAt(Duration ttl) {
        return System.currentTimeMillis() + ttl.toMillis();
    }
}
