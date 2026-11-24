package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.dto.KiwoomAccountOverviewResponse;
import com.hyunchang.webapp.dto.KiwoomAccountOverviewResponse.Holding;
import com.hyunchang.webapp.dto.KiwoomAccountOverviewResponse.Market;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** No repository, risk observer, order, or synchronization dependency. */
@Service
public class KiwoomAccountOverviewService {
    private final KiwoomProperties properties;
    private final KiwoomTradeService kr;
    private final KiwoomUsTradeService us;
    private KiwoomAccountOverviewResponse cached;
    private Instant cachedUntil = Instant.MIN;

    public KiwoomAccountOverviewService(
            KiwoomProperties properties, KiwoomTradeService kr, KiwoomUsTradeService us) {
        this.properties = properties;
        this.kr = kr;
        this.us = us;
    }

    // The application has one configured broker account, not per-request credentials.
    // Share a bounded snapshot only behind the controller's ADMIN authorization.
    public synchronized KiwoomAccountOverviewResponse overview() {
        if (cached != null && Instant.now().isBefore(cachedUntil)) return cached;
        List<Market> markets = List.of(load("KR"), load("US"));
        cached = new KiwoomAccountOverviewResponse("KIWOOM", Instant.now(), markets);
        cachedUntil = Instant.now().plusSeconds(30);
        return cached;
    }

    private Market load(String market) {
        String currency = "KR".equals(market) ? "KRW" : "USD";
        if (!properties.isConfigured())
            return new Market(
                    market,
                    "UNCONFIGURED",
                    currency,
                    "KIWOOM_REST",
                    null,
                    null,
                    List.of(),
                    null,
                    "키움 계좌 조회 설정이 필요합니다.");
        try {
            boolean domestic = "KR".equals(market);
            var responses =
                    Mono.zip(
                                    domestic
                                            ? kr.getBalanceForDisplay()
                                            : us.getBalanceForDisplay(),
                                    domestic
                                            ? kr.getDepositForDisplay()
                                            : us.getDepositForDisplay())
                            .block(Duration.ofSeconds(25));
            if (responses == null) throw new IllegalStateException("Missing response");
            JsonNode balance = responses.getT1();
            JsonNode deposit = responses.getT2();
            requireSuccess(balance);
            requireSuccess(deposit);
            JsonNode rows = balance.path(domestic ? "acnt_evlt_remn_indv_tot" : "result_list");
            if (!rows.isArray()) throw new IllegalStateException("Missing holdings list");
            List<Holding> holdings = new ArrayList<>();
            for (JsonNode row : rows) {
                String symbol = row.path("stk_cd").asText("").trim();
                if (domestic) symbol = symbol.replaceFirst("^A(?=[A-Za-z0-9]{6}$)", "");
                BigDecimal quantity = number(row, domestic ? "rmnd_qty" : "poss_qty");
                if (symbol.isBlank() || quantity == null || quantity.signum() < 0)
                    throw new IllegalStateException("Incomplete holding");
                if (quantity.signum() == 0) continue;
                holdings.add(
                        new Holding(
                                symbol,
                                text(
                                        row,
                                        symbol,
                                        domestic
                                                ? new String[] {"stk_nm"}
                                                : new String[] {
                                                    "frgn_stk_nm", "stk_nm", "stk_enm"
                                                }),
                                quantity,
                                positivePrice(
                                        number(
                                                row,
                                                domestic
                                                        ? new String[] {
                                                            "pur_pric", "avg_prc", "buy_uv"
                                                        }
                                                        : new String[] {
                                                            "frgn_stk_book_uv", "avg_pric"
                                                        })),
                                positivePrice(
                                        number(
                                                row,
                                                domestic
                                                        ? new String[] {"cur_prc", "prpr"}
                                                        : new String[] {"now_pric", "cur_prc"})),
                                number(row, domestic ? "evltv_prft" : "pl_amt"),
                                number(row, domestic ? "prft_rt" : "pl_rt"),
                                currency));
            }
            BigDecimal cash = number(deposit, domestic ? "entr" : "d0_usd_fx_entr");
            return new Market(
                    market,
                    "OK",
                    currency,
                    "KIWOOM_REST",
                    Instant.now(),
                    null,
                    List.copyOf(holdings),
                    cash,
                    (domestic ? "예수금(entr)" : "D+0 USD 외화예수금(d0_usd_fx_entr)")
                            + " · 주문가능금액과 다를 수 있습니다. 최대 30초 조회 캐시; 증권사 기준 시각은 미제공입니다.");
        } catch (RuntimeException error) {
            // Provider messages may contain account details; do not expose them.
            return new Market(
                    market,
                    "ERROR",
                    currency,
                    "KIWOOM_REST",
                    Instant.now(),
                    null,
                    List.of(),
                    null,
                    "계좌 데이터를 확인하지 못했습니다. 잠시 후 다시 조회해 주세요.");
        }
    }

    private void requireSuccess(JsonNode node) {
        if (node == null
                || !node.hasNonNull("return_code")
                || node.path("return_code").asInt(-1) != 0)
            throw new IllegalStateException("Invalid broker response");
    }

    private BigDecimal positivePrice(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value.abs();
    }

    private BigDecimal number(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node == null || node.isNull()) continue;
            try {
                return new BigDecimal(node.asText().replace(",", "").trim());
            } catch (NumberFormatException ignored) {
                /* unknown remains null */
            }
        }
        return null;
    }

    private String text(JsonNode row, String fallback, String... fields) {
        for (String field : fields) {
            String value = row.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return fallback;
    }
}
