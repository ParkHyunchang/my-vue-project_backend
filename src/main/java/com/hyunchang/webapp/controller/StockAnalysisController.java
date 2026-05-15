package com.hyunchang.webapp.controller;

import com.hyunchang.webapp.dto.StockAnalysisRequest;
import com.hyunchang.webapp.service.MenuPermissionService;
import com.hyunchang.webapp.service.StockAnalysisService;
import com.hyunchang.webapp.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock/analyze")
public class StockAnalysisController {
    // /stock 메뉴 권한이 있어야 호출 가능
    private static final String MENU_PATH = "/stock";

    private final StockAnalysisService stockAnalysisService;
    private final MenuPermissionService menuPermissionService;

    public StockAnalysisController(StockAnalysisService stockAnalysisService,
                                   MenuPermissionService menuPermissionService) {
        this.stockAnalysisService = stockAnalysisService;
        this.menuPermissionService = menuPermissionService;
    }

    @PostMapping
    public ResponseEntity<?> analyze(@RequestBody StockAnalysisRequest request) {
        if (!menuPermissionService.hasMenuAccess(SecurityUtils.getCurrentUserRoleName(), MENU_PATH)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("접근 권한이 없습니다.");
        }
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()) {
            return ResponseEntity.badRequest().body("symbol 이 필요합니다.");
        }
        return ResponseEntity.ok(stockAnalysisService.analyze(request.getSymbol(), request.getMarket()));
    }
}
