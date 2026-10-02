package com.hyunchang.webapp.util;

/** JVM-wide Kiwoom REST request pacer shared by Korean and US trading services. */
public final class KiwoomApiRateLimiter {
    public static final long MIN_SAFE_INTERVAL_MS = 350;
    private static long nextRequestAt;

    private KiwoomApiRateLimiter() {}

    public static synchronized long reserveDelayMs(long configuredIntervalMs) {
        long interval = Math.max(MIN_SAFE_INTERVAL_MS, configuredIntervalMs);
        long now = System.currentTimeMillis();
        long delay = Math.max(0, nextRequestAt - now);
        nextRequestAt = Math.max(now, nextRequestAt) + interval;
        return delay;
    }
}
