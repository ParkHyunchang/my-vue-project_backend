package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.common.security.MenuAccessGuard;
import com.hyunchang.webapp.dto.StockAnalysisRequest;
import com.hyunchang.webapp.service.StockAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock/analyze")
public class StockAnalysisController {
    private static final String MENU_PATH = "/stock";

    private final StockAnalysisService stockAnalysisService;
    private final MenuAccessGuard menuAccessGuard;

    public StockAnalysisController(StockAnalysisService stockAnalysisService,
                                   MenuAccessGuard menuAccessGuard) {
        this.stockAnalysisService = stockAnalysisService;
        this.menuAccessGuard = menuAccessGuard;
    }

    @PostMapping
    public ResponseEntity<?> analyze(@RequestBody StockAnalysisRequest request) {
        if (!menuAccessGuard.hasAccess(MENU_PATH)) return menuAccessGuard.forbidden();
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
            return ResponseEntity.badRequest().body("symbol \uAC12\uC774 \uD544\uC694\uD569\uB2C8\uB2E4.");
        }
        return ResponseEntity.ok(stockAnalysisService.analyze(request.getSymbol(), request.getMarket()));
    }
}
