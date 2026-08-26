package com.hyunchang.webapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomStrategySettings;
import com.hyunchang.webapp.repository.KiwoomStrategySettingsRepository;
import com.hyunchang.webapp.service.prompt.AiPromptService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KiwoomStrategySettingsServiceTest {

    @Mock private KiwoomStrategySettingsRepository repo;
    @Mock private KiwoomProperties props;
    @Mock private AiPromptService prompts;

    private KiwoomStrategySettingsService service;

    @BeforeEach
    void setUp() {
        service = new KiwoomStrategySettingsService(repo, props, prompts);
    }

    @Test
    void migratesOnlyTheExactLegacyConservativeEntryProfile() {
        KiwoomStrategySettings settings = legacySettings(300_000_000_000L);
        settings.setAutoExecute(true);
        settings.setRiskLoopEnabled(true);
        when(repo.existsById(1L)).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(settings));

        service.seed();

        assertEquals(85, settings.getAutoExecuteMinConfidence());
        assertEquals(8.0, settings.getSwingMaxChangePercent());
        assertEquals(8.0, settings.getSwingMaxVolumeRatio());
        assertEquals(200_000_000_000L, settings.getMinMarketCapWon());
        assertEquals(true, settings.isAutoExecute());
        assertEquals(true, settings.isRiskLoopEnabled());
        verify(repo).save(settings);
    }

    @Test
    void preservesAUserCustomizedProfile() {
        KiwoomStrategySettings settings = legacySettings(250_000_000_000L);
        when(repo.existsById(1L)).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(settings));

        service.seed();

        assertEquals(90, settings.getAutoExecuteMinConfidence());
        assertEquals(5.0, settings.getSwingMaxChangePercent());
        assertEquals(5.0, settings.getSwingMaxVolumeRatio());
        assertEquals(250_000_000_000L, settings.getMinMarketCapWon());
        verify(repo, never()).save(settings);
    }

    private KiwoomStrategySettings legacySettings(long marketCap) {
        KiwoomStrategySettings settings = new KiwoomStrategySettings();
        settings.setAutoExecuteMinConfidence(90);
        settings.setSwingMaxChangePercent(5.0);
        settings.setSwingMinVolumeRatio(1.5);
        settings.setSwingMaxVolumeRatio(5.0);
        settings.setMinMarketCapWon(marketCap);
        settings.setMinTradingValueWon(10_000_000_000L);
        return settings;
    }
}
