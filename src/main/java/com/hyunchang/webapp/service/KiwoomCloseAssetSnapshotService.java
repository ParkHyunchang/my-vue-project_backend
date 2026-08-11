package com.hyunchang.webapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.service.kiwoom.KiwoomAutoTradeState;
import com.hyunchang.webapp.util.KiwoomMarketHours;
import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Records the regular-session close used only for the dashboard's previous-close comparison. */
@Service
public class KiwoomCloseAssetSnapshotService {
    private static final Logger log =
            LoggerFactory.getLogger(KiwoomCloseAssetSnapshotService.class);

    private final KiwoomProperties properties;
    private final KiwoomTradeService trade;
    private final KiwoomAutoTradeState state;

    public KiwoomCloseAssetSnapshotService(
            KiwoomProperties properties, KiwoomTradeService trade, KiwoomAutoTradeState state) {
        this.properties = properties;
        this.trade = trade;
        this.state = state;
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Seoul")
    public void captureRegularClose() {
        if (!properties.isConfigured()
                || !KiwoomMarketHours.isTradingDay(LocalDate.now(KiwoomMarketHours.KST))) return;
        try {
            JsonNode deposit = trade.getDeposit().block(Duration.ofSeconds(10));
            JsonNode balance = trade.getBalance().block(Duration.ofSeconds(10));
            KiwoomTradeService.AccountAsset asset = trade.accountAsset(deposit, balance);
            state.recordClosingAsset(asset.amount());
            log.info("[자동매매][전일 대비 기준자산 저장] 자산={}, 기준={}", asset.amount(), asset.source());
        } catch (Exception e) {
            log.warn("[자동매매][전일 대비 기준자산 저장 실패] {}", e.getMessage());
        }
    }
}
