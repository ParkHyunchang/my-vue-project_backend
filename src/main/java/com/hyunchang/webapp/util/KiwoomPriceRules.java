package com.hyunchang.webapp.util;

/** KRX regular-session limit-price tick validation for ordinary stocks. */
public final class KiwoomPriceRules {
    private KiwoomPriceRules() {}

    public static boolean isValidLimitPrice(long price, String market) {
        if (price <= 0) return false;
        return price % tickSize(price, market) == 0;
    }

    public static long tickSize(long price, String market) {
        if (price < 1_000) return 1;
        if (price < 5_000) return 5;
        if (price < 10_000) return 10;
        if (price < 50_000) return 50;
        if (price < 100_000) return 100;
        if ("KOSDAQ".equalsIgnoreCase(market)) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }
}
