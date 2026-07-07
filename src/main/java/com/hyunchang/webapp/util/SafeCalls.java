package com.hyunchang.webapp.util;

import java.util.function.Supplier;
import org.slf4j.Logger;

public final class SafeCalls {
    private SafeCalls() {}

    public static <T> T get(Supplier<T> supplier, T fallback, Logger log, String logPrefix) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("{}: {}", logPrefix, e.getMessage());
            return fallback;
        }
    }
}
