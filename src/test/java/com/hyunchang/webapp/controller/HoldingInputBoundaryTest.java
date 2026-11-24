package com.hyunchang.webapp.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.service.*;
import com.hyunchang.webapp.util.HoldingInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class HoldingInputBoundaryTest {
    @Test
    void rejectsFractionalAndOverflowingCreateInputsAcrossLegacyAccounts() {
        var mapper = new ObjectMapper();
        for (Class<?> type :
                java.util.List.of(
                        StockHoldingController.HoldingRequest.class,
                        GeneralHoldingController.HoldingRequest.class,
                        IsaHoldingController.HoldingRequest.class,
                        IrpHoldingController.HoldingRequest.class)) {
            assertThrows(Exception.class, () -> mapper.readValue("{\"quantity\":1.9}", type));
            assertThrows(
                    Exception.class,
                    () -> mapper.readValue("{\"quantity\":9223372036854775808}", type));
            assertDoesNotThrow(() -> mapper.readValue("{\"quantity\":2}", type));
        }
    }

    @Test
    void exactIntegerAndPriceValidationCannotSilentlyCoerceBadInputs() {
        assertNull(HoldingInput.exactLong(1.9));
        assertNull(HoldingInput.exactLong(new BigDecimal("1e30")));
        assertEquals(2L, HoldingInput.exactLong(new BigDecimal("2.0")));
        assertFalse(HoldingInput.validPrice(-1));
        assertFalse(HoldingInput.validPrice(Double.NaN));
        assertFalse(HoldingInput.validPrice("bad"));
        assertFalse(HoldingInput.validMarket("XX"));
        assertTrue(HoldingInput.validMarket("us"));
    }

    @Test
    void accessDeniedBeforeAccountRepositoryOrHoldingMutation() {
        var service = mock(PortfolioAccountService.class);
        var access = mock(MenuAccessGuard.class);
        when(access.forbidden())
                .thenReturn(org.springframework.http.ResponseEntity.status(403).body("Forbidden"));
        var controller = new PortfolioAccountController(service, access);
        assertEquals(403, controller.list().getStatusCode().value());
        assertEquals(403, controller.delete("foreign-account", 1L).getStatusCode().value());
        verifyNoInteractions(service);
    }
}
