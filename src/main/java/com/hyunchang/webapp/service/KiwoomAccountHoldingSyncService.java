package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomAccountHolding;
import com.hyunchang.webapp.repository.KiwoomAccountHoldingRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 키움 실계좌를 DB에 복제한다. 수동 일반 포트폴리오는 절대 수정하지 않는다. */
@Service
public class KiwoomAccountHoldingSyncService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final KiwoomTradeService trade;
    private final KiwoomProperties props;
    private final KiwoomAccountHoldingRepository holdings;
    private final KiwoomStrategyAuditService audit;
    private final KiwoomSellAvailabilityDiagnosticService sellAvailabilityDiagnostics;

    public KiwoomAccountHoldingSyncService(
            KiwoomTradeService trade,
            KiwoomProperties props,
            KiwoomAccountHoldingRepository holdings,
            KiwoomStrategyAuditService audit,
            KiwoomSellAvailabilityDiagnosticService sellAvailabilityDiagnostics) {
        this.trade = trade;
        this.props = props;
        this.holdings = holdings;
        this.audit = audit;
        this.sellAvailabilityDiagnostics = sellAvailabilityDiagnostics;
    }

    /** 장 시작 전과 장 종료 뒤에는 수량이 변하지 않아도 스냅샷 시간을 갱신한다. */
    @Scheduled(cron = "0 55 8 * * MON-FRI", zone = "Asia/Seoul")
    public void syncAtMarketOpen() {
        sync("MARKET_OPEN");
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void syncAtMarketClose() {
        sync("MARKET_CLOSE");
    }

    public SyncResult sync(String source) {
        if (!props.isConfigured()) return new SyncResult(0, 0, false, "키움 API가 설정되지 않았습니다.");
        try {
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(15));
            return syncBalance(balance, source, true);
        } catch (Exception e) {
            return new SyncResult(0, 0, false, "키움 잔고 동기화 실패: " + trim(e.getMessage()));
        }
    }

    /** 이미 조회한 키움 잔고로 동기화한다. 체결 확인 뒤 추가 API 호출을 만들지 않는다. */
    public synchronized SyncResult syncBalance(JsonNode balance, String source) {
        return syncBalance(balance, source, false);
    }

    public List<KiwoomAccountHolding> currentHoldings() {
        return holdings.findByActiveTrueOrderByStockCodeAsc();
    }

    private SyncResult syncBalance(JsonNode balance, String source, boolean forceTimestamp) {
        if (!hasHoldingArray(balance))
            return new SyncResult(0, 0, false, "키움 잔고 응답에 종목 배열이 없어 기존 스냅샷을 유지했습니다.");
        List<KiwoomTradeService.Holding> brokerHoldings = trade.parseHoldings(balance);
        sellAvailabilityDiagnostics.logChangedRestrictions(balance, source);
        LocalDateTime now = LocalDateTime.now(KST);
        int changed = 0;
        Map<String, KiwoomAccountHolding> existing = new HashMap<>();
        for (KiwoomAccountHolding holding : holdings.findAll())
            existing.put(holding.getStockCode(), holding);

        for (KiwoomTradeService.Holding broker : brokerHoldings) {
            KiwoomAccountHolding stored = existing.remove(broker.code());
            boolean different = stored == null || differs(stored, broker);
            if (stored == null) stored = new KiwoomAccountHolding();
            if (different || forceTimestamp) {
                stored.updateFrom(
                        broker.code(),
                        broker.name(),
                        broker.quantity(),
                        broker.sellable(),
                        broker.avgPrice(),
                        broker.curPrice(),
                        broker.plPct(),
                        now);
                holdings.save(stored);
            }
            if (different) changed++;
        }
        for (KiwoomAccountHolding stored : existing.values()) {
            if (!stored.isActive()) continue;
            stored.markInactive(now);
            holdings.save(stored);
            changed++;
        }
        if (changed > 0)
            audit.log(
                    "BROKER_HOLDINGS_SYNCED",
                    null,
                    "키움 실계좌 보유현황 동기화: "
                            + source
                            + ", 보유 "
                            + brokerHoldings.size()
                            + "종목, 변경 "
                            + changed
                            + "건");
        return new SyncResult(brokerHoldings.size(), changed, true, "키움 실계좌 보유현황 동기화 완료");
    }

    private boolean differs(KiwoomAccountHolding stored, KiwoomTradeService.Holding broker) {
        return !stored.isActive()
                || !stored.getStockName().equals(broker.name())
                || stored.getQuantity() != broker.quantity()
                || stored.getSellableQuantity() != broker.sellable()
                || stored.getAveragePrice() != broker.avgPrice()
                || stored.getCurrentPrice() != broker.curPrice()
                || Double.compare(stored.getProfitLossPercent(), broker.plPct()) != 0;
    }

    private boolean hasHoldingArray(JsonNode balance) {
        if (balance == null || !balance.isObject()) return false;
        if (balance.path("acnt_evlt_remn_indv_tot").isArray()) return true;
        Iterator<JsonNode> children = balance.elements();
        while (children.hasNext()) if (children.next().isArray()) return true;
        return false;
    }

    private String trim(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(300, value.length()));
    }

    public record SyncResult(int holdingCount, int changedCount, boolean success, String message) {}
}
