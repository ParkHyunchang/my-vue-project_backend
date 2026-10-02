package com.hyunchang.webapp.service;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.repository.KiwoomUsStrategySettingsRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class KiwoomUsStrategySettingsService {
    private final KiwoomUsStrategySettingsRepository repository;
    private final KiwoomProperties properties;

    public KiwoomUsStrategySettingsService(
            KiwoomUsStrategySettingsRepository repository, KiwoomProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void seed() {
        var existing = repository.findById(1L);
        var s = existing.orElseGet(KiwoomUsStrategySettings::new);
        boolean created = existing.isEmpty();
        boolean changed = created;
        if (s.getMaxOrderPercent() <= 0) {
            s.setMaxOrderPercent(properties.getUs().getMaxOrderPercent());
            changed = true;
        }
        // ddl-auto=update may create numeric columns with zero for existing rows.
        if (s.getRiskPerTradePercent() <= 0) {
            s.setRiskPerTradePercent(0.5);
            changed = true;
        }
        if (s.getAtrStopMultiplier() <= 0) {
            s.setAtrStopMultiplier(2);
            changed = true;
        }
        if (s.getMaxEntryExtensionAtr() <= 0) {
            s.setMaxEntryExtensionAtr(1);
            changed = true;
        }
        if (created) {
            s.setMaxPositions(properties.getUs().getMaxPositions());
            s.setDailyMaxBuys(properties.getUs().getDailyMaxBuys());
            s.setDailyLossLimitPercent(properties.getUs().getDailyLossLimitPercent());
        }
        if (changed) repository.save(s);
    }

    public KiwoomUsStrategySettings current() {
        return repository.findById(1L).orElseGet(KiwoomUsStrategySettings::new);
    }

    public KiwoomUsStrategySettings save(KiwoomUsStrategySettings incoming) {
        var s = current();
        s.setMaxOrderPercent(clamp(incoming.getMaxOrderPercent(), 0.1, 100));
        s.setMaxPositions((int) clamp(incoming.getMaxPositions(), 1, 20));
        s.setDailyMaxBuys((int) clamp(incoming.getDailyMaxBuys(), 1, 20));
        s.setMinChangePercent(clamp(incoming.getMinChangePercent(), 0, 20));
        double minimumChange =
                incoming.getSignalMode() == KiwoomUsStrategySettings.SignalMode.TREND
                        ? 0
                        : s.getMinChangePercent();
        s.setMaxChangePercent(clamp(incoming.getMaxChangePercent(), minimumChange, 30));
        s.setMinVolumeRatio(clamp(incoming.getMinVolumeRatio(), 0.5, 5));
        s.setFundamentalFilterEnabled(incoming.isFundamentalFilterEnabled());
        s.setMaxForwardPe(clamp(incoming.getMaxForwardPe(), 5, 100));
        s.setMinRoePercent(clamp(incoming.getMinRoePercent(), 0, 50));
        s.setMaxSpreadPercent(clamp(incoming.getMaxSpreadPercent(), 0.05, 1));
        s.setStopLossPercent(clamp(incoming.getStopLossPercent(), 0.1, 30));
        s.setTakeProfitPercent(clamp(incoming.getTakeProfitPercent(), 0.1, 100));
        s.setTakeProfitPercent2(
                clamp(incoming.getTakeProfitPercent2(), s.getTakeProfitPercent(), 100));
        s.setMaxHoldingDays((int) clamp(incoming.getMaxHoldingDays(), 1, 30));
        s.setSymbolCooldownDays((int) clamp(incoming.getSymbolCooldownDays(), 1, 30));
        s.setDailyLossLimitPercent(clamp(incoming.getDailyLossLimitPercent(), 0, 30));
        s.setSignalMode(incoming.getSignalMode());
        s.setMinRelativeStrengthPercent(clamp(incoming.getMinRelativeStrengthPercent(), 0, 30));
        s.setRiskPerTradePercent(clamp(incoming.getRiskPerTradePercent(), 0.1, 1));
        s.setAtrStopMultiplier(clamp(incoming.getAtrStopMultiplier(), 1, 4));
        s.setMaxEntryExtensionAtr(clamp(incoming.getMaxEntryExtensionAtr(), 0.1, 2));
        return repository.save(s);
    }

    private double clamp(double v, double min, double max) {
        if (!Double.isFinite(v)) throw new IllegalArgumentException("설정값은 유한한 숫자여야 합니다.");
        return Math.max(min, Math.min(max, v));
    }
}
