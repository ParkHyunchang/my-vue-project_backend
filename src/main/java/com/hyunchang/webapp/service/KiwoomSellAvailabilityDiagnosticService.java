package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Logs a compact, non-sensitive diagnostic only when sellable quantity becomes inconsistent. */
@Service
public class KiwoomSellAvailabilityDiagnosticService {
    private static final Logger log =
            LoggerFactory.getLogger(KiwoomSellAvailabilityDiagnosticService.class);
    private final KiwoomTradeService trade;
    private final Map<String, String> lastLoggedState = new ConcurrentHashMap<>();

    public KiwoomSellAvailabilityDiagnosticService(KiwoomTradeService trade) {
        this.trade = trade;
    }

    /**
     * A repeated one-minute risk scan must not create repeated logs or order-inquiry calls. The
     * same stock is inspected again only after one of the diagnostic balance fields changes.
     */
    public void logChangedRestrictions(JsonNode balance, String source) {
        List<KiwoomTradeService.SellAvailability> restricted =
                trade.sellAvailabilityDiagnostics(balance);
        List<KiwoomTradeService.SellAvailability> changed =
                restricted.stream().filter(holding -> stateChanged(holding)).toList();
        if (changed.isEmpty()) return;

        List<JsonNode> unfilledSellOrders = new ArrayList<>();
        String orderInquiryError = null;
        try {
            collectOrderRows(
                    trade.getUnfilledSellOrders().block(Duration.ofSeconds(10)),
                    unfilledSellOrders);
        } catch (Exception e) {
            orderInquiryError = trim(e.getMessage());
        }

        for (KiwoomTradeService.SellAvailability holding : changed) {
            int unfilledQuantity = unfilledSellQuantity(unfilledSellOrders, holding.stockCode());
            String diagnosis = diagnosis(holding, unfilledQuantity, orderInquiryError);
            log.warn(
                    "[자동매매][매도 가능 수량 확인] 확인 시점={}, 종목={}({}), 보유={}주, 매도 가능={}주, 오늘 매수={}주, 오늘 매도={}주, 미체결 매도={}주, 판정={}",
                    source,
                    holding.stockName(),
                    holding.stockCode(),
                    holding.remainingQuantity(),
                    holding.sellableQuantity(),
                    holding.todayBuyQuantity(),
                    holding.todaySellQuantity(),
                    orderInquiryError == null ? unfilledQuantity : "확인 불가",
                    diagnosis);
        }
    }

    private boolean stateChanged(KiwoomTradeService.SellAvailability holding) {
        String state =
                holding.remainingQuantity()
                        + ":"
                        + holding.sellableQuantity()
                        + ":"
                        + holding.sellableFieldPresent()
                        + ":"
                        + holding.sellableRaw()
                        + ":"
                        + holding.todayBuyQuantity()
                        + ":"
                        + holding.todaySellQuantity()
                        + ":"
                        + holding.creditType();
        return !state.equals(lastLoggedState.put(holding.stockCode(), state));
    }

    private String diagnosis(
            KiwoomTradeService.SellAvailability holding,
            int unfilledQuantity,
            String orderInquiryError) {
        if (orderInquiryError != null) return "미체결 매도 주문 조회 실패";
        if (!holding.sellableFieldPresent()) return "키움 응답에 매도 가능 수량 항목이 없음";
        if (!isNumber(holding.sellableRaw())) return "키움 응답의 매도 가능 수량 형식이 올바르지 않음";
        if (unfilledQuantity > 0) return "미체결 매도 주문이 수량을 점유 중";
        if (isNonCashCredit(holding.creditType())) return "신용·대출 구분 종목이라 매도 가능 수량 재확인 필요";
        return "키움의 매도 가능 수량이 0주";
    }

    private int unfilledSellQuantity(List<JsonNode> rows, String stockCode) {
        return rows.stream()
                .filter(row -> stockCode.equals(normalizedCode(text(row, "stk_cd", "stock_code"))))
                .mapToInt(
                        row -> number(row, "rmn_qty", "unfilled_qty", "ord_remain_qty", "oso_qty"))
                .sum();
    }

    private void collectOrderRows(JsonNode node, List<JsonNode> result) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) collectOrderRows(child, result);
            return;
        }
        if (!node.isObject()) return;
        if (node.has("ord_no") || node.has("order_no")) result.add(node);
        node.elements().forEachRemaining(child -> collectOrderRows(child, result));
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field)) return node.path(field).asText("");
        return "";
    }

    private int number(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.hasNonNull(field)) continue;
            try {
                return Integer.parseInt(node.path(field).asText("").replace(",", "").trim());
            } catch (NumberFormatException ignored) {
                // Continue with the next Kiwoom field alias.
            }
        }
        return 0;
    }

    private String normalizedCode(String value) {
        return value == null ? "" : value.replaceAll("^[A-Za-z]+", "");
    }

    private String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private boolean isNonCashCredit(String creditType) {
        if (creditType == null || creditType.isBlank()) return false;
        String value = creditType.trim().toLowerCase();
        return !value.equals("0")
                && !value.equals("00")
                && !value.contains("현금")
                && !value.equals("cash");
    }

    private boolean isNumber(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Integer.parseInt(value.replace(",", "").trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String trim(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(180, value.length()));
    }
}
