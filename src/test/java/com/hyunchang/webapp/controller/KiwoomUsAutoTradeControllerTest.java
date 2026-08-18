package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyunchang.webapp.config.KiwoomProperties;
import com.hyunchang.webapp.entity.KiwoomUsStrategySettings;
import com.hyunchang.webapp.service.KiwoomAuthService;
import com.hyunchang.webapp.service.KiwoomUsAuditService;
import com.hyunchang.webapp.service.KiwoomUsAutoTradeService;
import com.hyunchang.webapp.service.KiwoomUsEventService;
import com.hyunchang.webapp.service.KiwoomUsIndexUniverseService;
import com.hyunchang.webapp.service.KiwoomUsStrategySettingsService;
import com.hyunchang.webapp.service.kiwoom.KiwoomUsAutoTradeState;
import org.junit.jupiter.api.Test;

class KiwoomUsAutoTradeControllerTest {

    @Test
    void changedSettingsAreWrittenToAuditAndLiveEvents() {
        KiwoomUsStrategySettingsService settingsService =
                mock(KiwoomUsStrategySettingsService.class);
        KiwoomUsAuditService audit = mock(KiwoomUsAuditService.class);
        KiwoomUsEventService events = mock(KiwoomUsEventService.class);
        KiwoomUsStrategySettings before = new KiwoomUsStrategySettings();
        KiwoomUsStrategySettings request = new KiwoomUsStrategySettings();
        request.setMinChangePercent(3);
        when(settingsService.current()).thenReturn(before);
        when(settingsService.save(request)).thenReturn(request);
        KiwoomUsAutoTradeController controller =
                new KiwoomUsAutoTradeController(
                        new KiwoomProperties(),
                        mock(KiwoomAuthService.class),
                        mock(KiwoomUsAutoTradeState.class),
                        mock(KiwoomUsAutoTradeService.class),
                        settingsService,
                        audit,
                        events,
                        mock(KiwoomUsIndexUniverseService.class));

        KiwoomUsStrategySettings saved = controller.settings(request);

        assertSame(request, saved);
        verify(audit).log(eq("SETTINGS_CHANGED"), isNull(), contains("최소 등락률(%) 2 → 3"));
        verify(events).publish(eq("SETTINGS_CHANGED"), contains("최소 등락률(%) 2 → 3"), isNull());
    }
}
