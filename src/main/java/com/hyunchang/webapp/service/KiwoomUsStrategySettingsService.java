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
        s.setAutoExecute(incoming.isAutoExecute());
        s.setMaxOrderPercent(clamp(incoming.getMaxOrderPercent(), 0.1, 100));
        s.setMaxPositions((int) clamp(incoming.getMaxPositions(), 1, 20));
        s.setDailyMaxBuys((int) clamp(incoming.getDailyMaxBuys(), 1, 20));
        s.setMinChangePercent(clamp(incoming.getMinChangePercent(), 0, 20));
        s.setMaxChangePercent(clamp(incoming.getMaxChangePercent(), s.getMinChangePercent(), 30));
        s.setMinVolumeRatio(clamp(incoming.getMinVolumeRatio(), 1, 20));
        s.setMaxSpreadPercent(clamp(incoming.getMaxSpreadPercent(), 0.01, 2));
        s.setStopLossPercent(clamp(incoming.getStopLossPercent(), 0.1, 30));
        s.setTakeProfitPercent(clamp(incoming.getTakeProfitPercent(), 0.1, 100));
        s.setTakeProfitPercent2(
                clamp(incoming.getTakeProfitPercent2(), s.getTakeProfitPercent(), 100));
        s.setMaxHoldingDays((int) clamp(incoming.getMaxHoldingDays(), 1, 30));
        s.setSymbolCooldownDays((int) clamp(incoming.getSymbolCooldownDays(), 1, 30));
        s.setDailyLossLimitPercent(clamp(incoming.getDailyLossLimitPercent(), 0, 30));
        return repository.save(s);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
