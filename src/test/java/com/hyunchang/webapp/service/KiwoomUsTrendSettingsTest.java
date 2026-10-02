package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.repository.KiwoomUsStrategySettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KiwoomUsTrendSettingsTest {
    private final KiwoomUsStrategySettingsRepository repository =
            mock(KiwoomUsStrategySettingsRepository.class);
    private final KiwoomUsStrategySettingsService service =
            new KiwoomUsStrategySettingsService(repository, new KiwoomProperties());

    @Test
    void newInstallationUsesTrendButExistingDatabaseModeIsNotSwitched() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        service.seed();
        var saved = ArgumentCaptor.forClass(KiwoomUsStrategySettings.class);
        verify(repository).save(saved.capture());
        assertEquals(KiwoomUsStrategySettings.SignalMode.TREND, saved.getValue().getSignalMode());
        reset(repository);
        var existing = new KiwoomUsStrategySettings();
        existing.setTrailingStopAtrMultiplier(0);
        existing.setTrailingActivationR(0);
        existing.setMaxHoldingTradingDays(0);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        service.seed();
        assertEquals(KiwoomUsStrategySettings.SignalMode.OBSERVE, existing.getSignalMode());
        assertEquals(2, existing.getTrailingStopAtrMultiplier());
        assertEquals(1, existing.getTrailingActivationR());
        assertEquals(5, existing.getMaxHoldingTradingDays());
    }

    @Test
    void trendSettingsPersistAndNonFiniteTrailingValuesAreRejected() {
        var existing = new KiwoomUsStrategySettings();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        var incoming = new KiwoomUsStrategySettings();
        incoming.setSignalMode(KiwoomUsStrategySettings.SignalMode.TREND);
        incoming.setTrailingActivationR(1.5);
        incoming.setTrailingStopAtrMultiplier(3);
        incoming.setMaxHoldingTradingDays(4);
        service.save(incoming);
        assertEquals(1.5, existing.getTrailingActivationR());
        assertEquals(3, existing.getTrailingStopAtrMultiplier());
        assertEquals(4, existing.getMaxHoldingTradingDays());
        incoming.setTrailingStopAtrMultiplier(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> service.save(incoming));
    }
}
