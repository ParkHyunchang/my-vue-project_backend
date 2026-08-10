package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.repository.KiwoomTradeProposalRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 매도 가능 수량이 보유 수량보다 적을 때 원인을 이해하기 쉬운 형태로 기록한다. */
@Service
public class KiwoomSellAvailabilityDiagnosticService {
    private static final Logger log =
            LoggerFactory.getLogger(KiwoomSellAvailabilityDiagnosticService.class);
    private final KiwoomTradeService trade;
    private final KiwoomTradeProposalRepository proposals;
    private final Map<String, String> lastLoggedState = new ConcurrentHashMap<>();

    public KiwoomSellAvailabilityDiagnosticService(
            KiwoomTradeService trade, KiwoomTradeProposalRepository proposals) {
        this.trade = trade;
        this.proposals = proposals;
    }

    /** 같은 잔고 상태에 대한 반복 로그와 미체결 주문 조회를 만들지 않는다. */
    public void logChangedRestrictions(JsonNode balance, String source) {
        List<KiwoomTradeService.SellAvailability> changed =
                trade.sellAvailabilityDiagnostics(balance).stream()
                        .filter(this::stateChanged)
                        .toList();
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
            List<UnfilledSellOrder> matchingOrders = matchingOrders(unfilledSellOrders, holding);
            int unfilledQuantity =
                    matchingOrders.stream().mapToInt(UnfilledSellOrder::remainingQuantity).sum();

            if (orderInquiryError == null
                    && holding.remainingQuantity() > 0
                    && unfilledQuantity >= holding.remainingQuantity()) {
                log.info(
                        "[자동매매][미체결 매도 주문 유지] 확인 시점={}, 종목={}({}), 보유={}주, 주문={}, 판정=미체결 매도 주문이 보유 전량을 예약 중(정상)",
                        sourceLabel(source),
                        holding.stockName(),
                        holding.stockCode(),
                        holding.remainingQuantity(),
                        orderSummary(matchingOrders));
                continue;
            }

            log.warn(
                    "[자동매매][매도 가능 수량 이상 확인] 확인 시점={}, 종목={}({}), 보유={}주, 매도 가능={}주, 오늘 매수={}주, 오늘 매도={}주, 미체결 매도={}주, 주문={}, 판정={}",
                    sourceLabel(source),
                    holding.stockName(),
                    holding.stockCode(),
                    holding.remainingQuantity(),
                    holding.sellableQuantity(),
                    holding.todayBuyQuantity(),
                    holding.todaySellQuantity(),
                    orderInquiryError == null ? unfilledQuantity : "확인 불가",
                    matchingOrders.isEmpty() ? "없음" : orderSummary(matchingOrders),
                    diagnosis(holding, unfilledQuantity, orderInquiryError));
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

    private List<UnfilledSellOrder> matchingOrders(
            List<JsonNode> rows, KiwoomTradeService.SellAvailability holding) {
        return rows.stream()
                .filter(
                        row ->
                                holding.stockCode()
                                        .equals(normalizedCode(text(row, "stk_cd", "stock_code"))))
                .map(this::toUnfilledSellOrder)
                .toList();
    }

    private UnfilledSellOrder toUnfilledSellOrder(JsonNode row) {
        String orderNumber = blankAsUnknown(text(row, "ord_no", "order_no"));
        long brokerPrice =
                longNumber(row, "ord_pric", "ord_prc", "ord_price", "order_price", "price");
        long orderPrice =
                brokerPrice > 0
                        ? brokerPrice
                        : proposals
                                .findByBrokerOrderNo(orderNumber)
                                .map(proposal -> proposal.getLimitPrice())
                                .orElse(0L);
        return new UnfilledSellOrder(
                orderNumber,
                number(row, "rmn_qty", "unfilled_qty", "ord_remain_qty", "oso_qty"),
                orderPrice);
    }

    private String orderSummary(List<UnfilledSellOrder> orders) {
        return orders.stream()
                .map(
                        order ->
                                (order.orderPrice() > 0
                                                ? String.format("%,d원", order.orderPrice())
                                                : "가격 미확인")
                                        + " "
                                        + order.remainingQuantity()
                                        + "주(주문번호="
                                        + order.orderNumber()
                                        + ")")
                .collect(Collectors.joining(", "));
    }

    private String diagnosis(
            KiwoomTradeService.SellAvailability holding,
            int unfilledQuantity,
            String orderInquiryError) {
        if (orderInquiryError != null) return "미체결 매도 주문 조회 실패: " + orderInquiryError;
        if (!holding.sellableFieldPresent()) return "키움 응답에 매도 가능 수량 항목이 없음";
        if (!isNumber(holding.sellableRaw())) return "키움 응답의 매도 가능 수량 형식이 올바르지 않음";
        if (unfilledQuantity > 0) return "일부 미체결 매도 주문만으로는 매도 가능 0주가 설명되지 않음";
        if (isNonCashCredit(holding.creditType())) return "신용·대출 구분 종목이라 매도 가능 수량 재확인 필요";
        return "원인을 설명할 미체결 매도 주문이 없어 키움 잔고 재확인 필요";
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
                // 다음 키움 필드 별칭을 확인한다.
            }
        }
        return 0;
    }

    private long longNumber(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.hasNonNull(field)) continue;
            try {
                return Math.abs(
                        Long.parseLong(node.path(field).asText("").replace(",", "").trim()));
            } catch (NumberFormatException ignored) {
                // 다음 키움 필드 별칭을 확인한다.
            }
        }
        return 0;
    }

    private String normalizedCode(String value) {
        return value == null ? "" : value.replaceAll("^[A-Za-z]+", "");
    }

    private String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "미확인" : value;
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
        return value == null ? "알 수 없음" : value.substring(0, Math.min(180, value.length()));
    }

    private String sourceLabel(String source) {
        if (source == null || source.isBlank()) return "미확인";
        return switch (source) {
            case "WEBSOCKET_FALLBACK" -> "실시간 시세 연결 끊김 대체 점검";
            case "APPLICATION_RESTART" -> "서버 재시작";
            case "PRE_MARKET_0850" -> "08:50 전일 주문 정리";
            case "MARKET_OPEN_RECHECK" -> "장중 재확인 시 전일 주문 정리";
            case "MARKET_OPEN_0900" -> "09:00 장 시작";
            case "ORDER_STATE_CHANGED" -> "주문 상태 변경";
            case "STOP_TRANSITION_RECHECK" -> "손절·보유기간 청산 전환 재확인";
            case "SETTINGS_CHANGED" -> "매매 규칙 저장 직후";
            default -> source;
        };
    }

    private record UnfilledSellOrder(String orderNumber, int remainingQuantity, long orderPrice) {}
}
