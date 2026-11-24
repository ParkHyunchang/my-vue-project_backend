package com.hyunchang.webapp.util;

import java.math.BigDecimal;
import java.util.Locale;

/** Shared boundary validation for the legacy integer-quantity portfolios. */
public final class HoldingInput {
    private HoldingInput() {}

    public static Long exactLong(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            return null;
        }
    }

    public static boolean validMarket(String value) {
        return value != null
                && java.util.Set.of("KR", "US").contains(value.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean validPrice(Object value) {
        return value == null
                || (value instanceof Number number
                        && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= 0);
    }

    public static void validate(String market, Long quantity, Double price) {
        if (!validMarket(market) || quantity == null || quantity <= 0 || !validPrice(price)) {
            throw new IllegalArgumentException("시장(KR/US), 양의 정수 수량, 0 이상의 평균 매입가를 확인해 주세요.");
        }
    }
}
